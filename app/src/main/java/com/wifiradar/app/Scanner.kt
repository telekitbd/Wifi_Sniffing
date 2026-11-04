package com.wifiradar.app

import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.Inet4Address
import java.net.InetAddress
import java.net.NetworkInterface
import java.util.concurrent.Callable
import java.util.concurrent.Executors

data class Device(val ip: String, val mac: String, val name: String, val tags: List<String>)

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

    fun scan(): List<Device> {
        val (iface, myIp) = localWifi() ?: return emptyList()
        val base = myIp.substringBeforeLast(".")

        // 1) wake every host so it appears in the neighbour (ARP) table
        su("for i in \$(seq 1 254); do ping -c 1 -W 1 $base.\$i >/dev/null 2>&1 & done; wait")

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
                if (ip == gateway) tags += "Router"
                if (isRandomMac(mac)) tags += "Private MAC"
                Device(ip, mac, resolveName(ip), tags)
            })
        }
        val result = futures.map { it.get() }.toMutableList()
        pool.shutdown()

        result.add(Device(myIp, "(hidden by Android)", android.os.Build.MODEL, listOf("This device")))
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
