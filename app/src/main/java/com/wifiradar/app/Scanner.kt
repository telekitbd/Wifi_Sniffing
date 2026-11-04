package com.wifiradar.app

import android.content.Context
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.Socket
import java.util.concurrent.Callable
import java.util.concurrent.Executors

data class Device(
    val ip: String, val mac: String, val name: String, val tags: List<String>,
    val type: String = "Unknown", val vendor: String? = null,
    val sent: Int = 0, val lost: Int = 0, val avgMs: Double? = null
)

object Scanner {

    fun su(cmd: String): String = try {
        val p = Runtime.getRuntime().exec(arrayOf("su", "-c", cmd))
        val out = p.inputStream.bufferedReader().readText()
        p.waitFor()
        out
    } catch (e: Exception) { "" }

    fun hasRoot() = su("id").contains("uid=0")

    /** (interface, own ip) of the WiFi connection, or null */
    fun localWifi(): Pair<String, String>? {
        val all = NetworkInterface.getNetworkInterfaces() ?: return null
        for (ni in all) {
            if (!ni.isUp || ni.isLoopback || !ni.name.startsWith("wlan")) continue
            for (a in ni.inetAddresses) if (a is Inet4Address) return ni.name to a.hostAddress!!
        }
        return null
    }

    // ---- vendor (OUI) database, loaded once from res/raw/oui.txt ----
    private var oui: HashMap<String, String>? = null
    private fun vendorOf(ctx: Context, mac: String): String? {
        val db = oui ?: HashMap<String, String>(40000).also { m ->
            ctx.resources.openRawResource(R.raw.oui).bufferedReader().forEachLine { l ->
                if (l.length > 7) m[l.substring(0, 6)] = l.substring(7)
            }
            oui = m
        }
        return db[mac.replace(":", "").take(6).lowercase()]
    }

    private val phoneBrands = listOf("samsung", "xiaomi", "huawei", "oppo", "vivo", "oneplus", "realme",
        "motorola", "google", "tecno", "transsion", "honor", "nothing", "lg elec", "sony mobile", "htc",
        "infinix", "itel", "symphony", "walton", "lava", "micromax", "meizu", "fairphone", "nokia", "hmd")
    private val pcBrands = listOf("intel", "dell", "lenovo", "hewlett", "hp inc", "asustek", "acer",
        "microsoft", "liteon", "lite-on", "azurewave", "realtek", "killer", "micro-star",
        "gigabyte", "giga-byte", "toshiba", "fujitsu", "wistron", "quanta", "compal", "pegatron", "foxconn", "hon hai")
    private val routerBrands = listOf("tp-link", "tenda", "d-link", "netgear", "mikrotik", "routerboard",
        "ubiquiti", "zyxel", "totolink", "cudy", "linksys", "belkin", "tplink", "ruijie", "fiberhome",
        "zte", "huawei device", "grandstream", "draytek", "juniper", "cisco", "aruba", "ruckus")
    private val tvBrands = listOf("roku", "vizio", "tcl", "hisense", "skyworth", "amazon", "espressif", "tuya")
    private val printerBrands = listOf("epson", "brother", "canon", "xerox", "ricoh", "lexmark", "kyocera")

    private fun has(s: String, keys: List<String>) = keys.any { s.contains(it) }

    private val portsToProbe = intArrayOf(62078, 445, 135, 3389, 5900, 548, 9100, 631, 8009, 5555)
    private fun openPorts(ip: String): Set<Int> {
        val open = mutableSetOf<Int>()
        for (p in portsToProbe) try {
            Socket().use { it.connect(InetSocketAddress(ip, p), 250); open += p }
        } catch (e: Exception) { }
        return open
    }

    /** returns (type, isGuess) */
    private fun classify(name: String, vendor: String?, ttl: Int?, ports: Set<Int>, router: Boolean, randomMac: Boolean): Pair<String, Boolean> {
        val n = name.lowercase()
        val v = vendor?.lowercase() ?: ""
        if (router) return "Router" to false
        // hostname clues (most reliable)
        if (n.contains("iphone") || n.contains("ipad")) return "Phone (iPhone/iPad)" to false
        if (n.contains("macbook") || n.contains("imac") || n.contains("mac-mini") || n.contains("macmini") || n.contains("mac-pro")) return "Computer (Mac)" to false
        if (n.startsWith("desktop-") || n.startsWith("laptop-") || n.startsWith("win") || n.contains("-pc") || n.startsWith("pc-")) return "Computer (Windows)" to false
        if (has(n, listOf("android", "galaxy", "redmi", "xiaomi", "poco", "oppo", "vivo", "realme", "pixel", "oneplus", "huawei", "honor", "tecno", "infinix", "itel", "samsung-sm", "sm-"))) return "Phone (Android)" to false
        if (has(n, listOf("-tv", "tv-", "roku", "chromecast", "bravia", "firetv", "smarttv"))) return "TV / Streaming" to false
        if (has(n, listOf("printer", "epson", "laserjet", "deskjet"))) return "Printer" to false
        // open ports
        if (62078 in ports) return "Phone (iPhone/iPad)" to false
        if (9100 in ports || 631 in ports) return "Printer" to false
        if (445 in ports || 135 in ports || 3389 in ports) return "Computer (Windows)" to false
        if (5900 in ports || 548 in ports) return "Computer (Mac)" to false
        if (5555 in ports) return "Phone (Android)" to true
        if (8009 in ports) return "TV / Streaming" to false
        // vendor from MAC (only valid when MAC is not randomised)
        if (!randomMac && vendor != null) {
            if (v.contains("apple")) return (if (ttl != null && ttl > 64) "Apple device" else "Apple (iPhone / Mac)") to true
            if (has(v, routerBrands) && !has(v, listOf("huawei"))) return "Router / Network device" to true
            if (has(v, phoneBrands)) return "Phone" to true
            if (has(v, pcBrands)) return "Computer" to true
            if (has(v, printerBrands)) return "Printer" to true
            if (has(v, tvBrands)) return "TV / IoT device" to true
        }
        if (ttl != null && ttl in 65..128) return "Computer (Windows)" to true
        if (randomMac) return "Phone" to true      // random MAC is almost always a phone
        return "Unknown" to false
    }

    /** Does this WiFi network really reach the internet? (pings public servers through the wlan interface) */
    fun hasInternet(iface: String): Boolean =
        listOf("8.8.8.8", "1.1.1.1").any { su("ping -c 2 -W 2 -I $iface $it 2>&1").contains(Regex("[1-9]\\d* (packets )?received")) }

    /** Sends 10 pings and counts lost frames: (sent, lost, avg latency ms) */
    private fun pingStats(ip: String): Triple<Int, Int, Double?> {
        val out = su("ping -c 10 -i 0.2 -W 1 $ip 2>&1")
        val m = Regex("(\\d+) packets transmitted, (\\d+) (?:packets )?received").find(out)
        val sent = m?.groupValues?.get(1)?.toIntOrNull() ?: 10
        val recv = m?.groupValues?.get(2)?.toIntOrNull() ?: 0
        val avg = Regex("= [\\d.]+/([\\d.]+)/").find(out)?.groupValues?.get(1)?.toDoubleOrNull()
        return Triple(sent, sent - recv, avg)
    }

    fun scan(ctx: Context): List<Device> {
        val (iface, myIp) = localWifi() ?: return emptyList()
        val base = myIp.substringBeforeLast(".")

        // 1) wake every host so it appears in the neighbour (ARP) table
        val ttlOut = su("for i in \$(seq 1 254); do (r=\$(ping -c 1 -W 1 $base.\$i 2>/dev/null | grep -o 'ttl=[0-9]*'); [ -n \"\$r\" ] && echo \"$base.\$i \$r\") & done; wait")
        val ttls = ttlOut.lines().mapNotNull { l ->
            val p = l.trim().split(" ")
            if (p.size == 2) p[0] to p[1].removePrefix("ttl=").toIntOrNull() else null
        }.toMap()

        // 2) gateway (router)
        val gateway = Regex("default via (\\S+)").find(su("ip route show dev $iface"))?.groupValues?.get(1)

        // 3) read ARP/neighbour table with root
        val rows = su("ip neigh show dev $iface").lines().mapNotNull { line ->
            val p = line.trim().split(Regex("\\s+"))
            val i = p.indexOf("lladdr")
            if (i > 0 && !line.contains("FAILED")) p[0] to p[i + 1].lowercase() else null
        }

        // 4) resolve names in parallel
        val pool = Executors.newFixedThreadPool(24)
        val futures = rows.map { (ip, mac) ->
            pool.submit(Callable {
                val tags = mutableListOf<String>()
                val random = isRandomMac(mac)
                val name = resolveName(ip)
                val vendor = if (random) null else vendorOf(ctx, mac)
                val (type, guess) = classify(name, vendor, ttls[ip], openPorts(ip), ip == gateway, random)
                tags += if (guess) "$type?" else type
                if (vendor != null) tags += vendor
                if (random) tags += "Private MAC"
                val (sent, lost, avg) = pingStats(ip)
                Device(ip, mac, name, tags, type, vendor, sent, lost, avg)
            })
        }
        val result = futures.map { it.get() }.toMutableList()
        pool.shutdown()

        result.add(Device(myIp, "(hidden by Android)", android.os.Build.MODEL,
            listOf("This device", "Phone", android.os.Build.MANUFACTURER.replaceFirstChar { it.uppercase() }),
            "Phone", android.os.Build.MANUFACTURER))
        return result.sortedBy { it.ip.substringAfterLast(".").toIntOrNull() ?: 999 }
    }

    private fun isRandomMac(mac: String) = mac.getOrNull(1)?.let { it in "26ae" } ?: false

    private fun resolveName(ip: String): String {
        val dns = try {
            val h = InetAddress.getByName(ip).canonicalHostName
            if (h != ip) h else null
        } catch (e: Exception) { null }
        return dns ?: netbios(ip) ?: "Unknown"
    }

    /** NetBIOS node status query (UDP 137) */
    private fun netbios(ip: String): String? = try {
        val q = byteArrayOf(0x12, 0x34, 0, 0, 0, 1, 0, 0, 0, 0, 0, 0, 0x20) +
            "CKAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA".toByteArray() +
            byteArrayOf(0, 0, 0x21, 0, 1)
        DatagramSocket().use { s ->
            s.soTimeout = 700
            s.send(DatagramPacket(q, q.size, InetAddress.getByName(ip), 137))
            val buf = ByteArray(512)
            val r = DatagramPacket(buf, buf.size)
            s.receive(r)
            if (r.length > 72) String(buf, 57, 15).trim().ifEmpty { null } else null
        }
    } catch (e: Exception) { null }
}
