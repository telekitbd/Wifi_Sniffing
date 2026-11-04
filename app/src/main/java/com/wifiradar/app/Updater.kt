package com.wifiradar.app

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

object Updater {
    data class Info(val code: Int, val name: String, val notes: String, val url: String)

    /** Latest GitHub release, or null if it could not be read. */
    fun check(): Info? {
        return try {
            val c = URL("https://api.github.com/repos/${BuildConfig.UPDATE_REPO}/releases/latest")
                .openConnection() as HttpURLConnection
            c.connectTimeout = 8000; c.readTimeout = 8000
            c.setRequestProperty("Accept", "application/vnd.github+json")
            if (c.responseCode != 200) return null
            val j = JSONObject(c.inputStream.bufferedReader().readText())
            val code = j.getString("tag_name").substringAfterLast("-").toIntOrNull() ?: return null
            val assets = j.getJSONArray("assets")
            var url: String? = null
            for (i in 0 until assets.length()) {
                val a = assets.getJSONObject(i)
                if (a.getString("name").endsWith(".apk")) { url = a.getString("browser_download_url"); break }
            }
            Info(code, j.optString("name"), j.optString("body"), url ?: return null)
        } catch (e: Exception) { null }
    }

    fun download(url: String, dest: File, onProgress: (Int) -> Unit): Boolean {
        return try {
            dest.parentFile?.mkdirs()
            val c = URL(url).openConnection() as HttpURLConnection
            c.connectTimeout = 10000; c.readTimeout = 20000
            val total = c.contentLength.toLong()
            var done = 0L; var last = -1
            c.inputStream.use { i ->
                dest.outputStream().use { o ->
                    val buf = ByteArray(32 * 1024)
                    while (true) {
                        val n = i.read(buf); if (n < 0) break
                        o.write(buf, 0, n); done += n
                        if (total > 0) {
                            val p = (done * 100 / total).toInt()
                            if (p != last) { last = p; onProgress(p) }
                        }
                    }
                }
            }
            dest.length() > 0
        } catch (e: Exception) { false }
    }

    /** Root: silent update over the old app (no uninstall), then reopen the app. */
    fun rootInstall(apk: File): Boolean {
        return try {
            val tmp = "/data/local/tmp/wr_update.apk"
            val cmd = "cp '${apk.absolutePath}' $tmp && chmod 644 $tmp && pm install -r $tmp 2>&1 && " +
                "(rm -f $tmp; am start -n com.wifiradar.app/.MainActivity >/dev/null 2>&1)"
            val p = Runtime.getRuntime().exec(arrayOf("su", "-c", cmd))
            val out = p.inputStream.bufferedReader().readText()
            p.waitFor()
            out.contains("Success")
        } catch (e: Exception) { false }
    }

    /** No root: normal Android installer prompt. It updates in place, no uninstall needed. */
    fun sessionInstall(ctx: Context, apk: File) {
        val pi = ctx.packageManager.packageInstaller
        val id = pi.createSession(PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL))
        pi.openSession(id).use { s ->
            apk.inputStream().use { i ->
                s.openWrite("wifiradar", 0, apk.length()).use { o -> i.copyTo(o); s.fsync(o) }
            }
            val intent = Intent(ctx, InstallReceiver::class.java)
            val pend = PendingIntent.getBroadcast(ctx, id, intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE)
            s.commit(pend.intentSender)
        }
    }
}
