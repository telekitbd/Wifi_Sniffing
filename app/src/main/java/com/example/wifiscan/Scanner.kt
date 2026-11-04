package com.example.wifiscan

import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.Inet4Address
import java.net.InetAddress
import java.net.NetworkInterface
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

data class Device(val ip: String, val mac: String, val name: String, val note: String)

object Scanner {

    fun su(cmd: String): String = try {
        val p = Runtime.getRuntime().exec(arrayOf("su", "-c", cmd))
        val out = p.inputStream.bufferedReader().readText()
        p.waitFor()
        out
    } catch (e: Exception) { "" }

    fun hasRoot() = su("id").contains("uid=0")

    /** Returns (wifi interface name, own ip) */
    fun localWifi(): Pair<String, String>? {
        for (ni in NetworkInterface.getNetworkInterfaces()) {
            if (!ni.isUp || ni.isLoopback || !ni.name.startsWith("wlan")) continue
            for (a in ni.inetAddresses) if (a is Inet4Address) return ni.name to a.hostAddress!!
        }
        return null
    }

    fun scan(): List<Device> {
        val (iface, myIp) = localWifi() ?: return emptyList()
        val base = myIp.substringBeforeLast(".")

        // 1) wake every host so it lands in the neighbour (ARP) table
        su("for i in \$(seq 1 254); do ping -c 1 -W 1 $base.\$i >/dev/null 2>&1 & done; wait")

        // 2) read ARP/neighbour table with root
        val rows = su("ip neigh show dev $iface").lines().mapNotNull { line ->
            val p = line.trim().split(Regex("\\s+"))
            val i = p.indexOf("lladdr")
            if (i > 0 && !line.contains("FAILED")) p[0] to p[i + 1].lowercase() else null
        }

        // 3) resolve names in parallel
        val pool = Executors.newFixedThreadPool(24)
        val futures = rows.map { (ip, mac) ->
            pool.submit(Callable {
                Device(ip, mac, resolveName(ip), if (isRandomMac(mac)) "Private/random MAC" else "")
            })
        }
        val result = futures.map { it.get() }.toMutableList()
        pool.shutdown()
        pool.awaitTermination(2, TimeUnit.SECONDS)

        result.add(Device(myIp, "(this device)", android.os.Build.MODEL, "You"))
        return result.sortedBy { it.ip.substringAfterLast(".").toInt() }
    }

    private fun isRandomMac(mac: String): Boolean {
        val c = mac.getOrNull(1) ?: return false
        return c in "26aeAE"
    }

    private fun resolveName(ip: String): String {
        val dns = try {
            val h = InetAddress.getByName(ip).canonicalHostName
            if (h != ip) h else null
        } catch (e: Exception) { null }
        return dns ?: netbios(ip) ?: "Unknown"
    }

    /** NetBIOS node status query (UDP 137) */
    private fun netbios(ip: String): String? = try {
        val q = byteArrayOf(
            0x12, 0x34, 0, 0, 0, 1, 0, 0, 0, 0, 0, 0, 0x20
        ) + "CKAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA".toByteArray() +
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
