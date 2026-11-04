package com.wifiradar.app

import android.app.Activity
import android.app.AlertDialog
import android.content.ClipData
import android.content.Intent
import android.net.Uri
import android.graphics.Typeface
import android.widget.LinearLayout
import android.widget.ScrollView
import android.content.ClipboardManager
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.provider.Settings
import java.io.File
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.Button
import android.widget.ListView
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import kotlin.concurrent.thread

class MainActivity : Activity() {
    private lateinit var scanBtn: Button
    private lateinit var rootDot: View
    private lateinit var rootText: TextView
    private lateinit var netText: TextView
    private lateinit var countText: TextView
    private lateinit var progress: ProgressBar
    private lateinit var list: ListView
    private lateinit var empty: TextView
    private val devices = mutableListOf<Device>()

    private val palette = intArrayOf(
        0xFF22D3EE.toInt(), 0xFFA78BFA.toInt(), 0xFFF472B6.toInt(),
        0xFF34D399.toInt(), 0xFFFBBF24.toInt(), 0xFF60A5FA.toInt(), 0xFFFB923C.toInt()
    )

    private val adapter = object : BaseAdapter() {
        override fun getCount() = devices.size
        override fun getItem(p: Int) = devices[p]
        override fun getItemId(p: Int) = p.toLong()
        override fun getView(p: Int, v: View?, parent: ViewGroup): View {
            val view = v ?: LayoutInflater.from(this@MainActivity).inflate(R.layout.item_device, parent, false)
            val d = devices[p]
            val avatar = view.findViewById<TextView>(R.id.avatar)
            avatar.text = if (d.name == "Unknown") "?" else d.name.first().uppercase()
            (avatar.background.mutate() as GradientDrawable)
                .setColor(palette[(d.mac.hashCode() and 0x7fffffff) % palette.size])
            view.findViewById<TextView>(R.id.name).text = d.name
            view.findViewById<TextView>(R.id.ip).text = d.ip
            view.findViewById<TextView>(R.id.mac).text = d.mac
            view.findViewById<TextView>(R.id.copyBtn).apply {
                visibility = if (d.mac.startsWith("(")) View.GONE else View.VISIBLE
                setOnClickListener { copyText("MAC", d.mac, "Copied ${d.mac}") }
            }
            val box = view.findViewById<View>(R.id.statusBox)
            if (d.sent == 0) box.visibility = View.GONE
            else {
                box.visibility = View.VISIBLE
                val pct = d.lost * 100 / d.sent
                val noReply = d.lost >= d.sent
                val col = when { pct == 0 -> 0xFF34D399.toInt(); pct <= 10 -> 0xFFFBBF24.toInt(); else -> 0xFFF87171.toInt() }
                view.findViewById<TextView>(R.id.status).apply {
                    text = if (noReply) "\u25CF NO REPLY" else "\u25CF ONLINE"
                    setTextColor(col)
                    (background.mutate() as GradientDrawable).setColor((col and 0x00FFFFFF) or 0x26000000)
                }
                view.findViewById<TextView>(R.id.pingText).apply {
                    text = d.avgMs?.let { "Ping ${"%.0f".format(it)} ms" } ?: if (noReply) "connected, not responding" else ""
                    setTextColor(if (d.avgMs == null) 0xFF94A3B8.toInt() else 0xFFF1F5F9.toInt())
                }
                view.findViewById<TextView>(R.id.lostText).apply {
                    text = "${d.lost}/${d.sent}  ($pct%)"
                    setTextColor(col)
                }
                view.findViewById<View>(R.id.barFill).apply {
                    (background.mutate() as GradientDrawable).setColor(col)
                    layoutParams = (layoutParams as LinearLayout.LayoutParams).also { it.weight = (100 - pct).toFloat() }
                }
            }
            view.findViewById<TextView>(R.id.tags).apply {
                text = d.tags.joinToString("  •  ")
                visibility = if (d.tags.isEmpty()) View.GONE else View.VISIBLE
            }
            return view
        }
    }

    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        setContentView(R.layout.activity_main)
        scanBtn = findViewById(R.id.scanBtn)
        rootDot = findViewById(R.id.rootDot)
        rootText = findViewById(R.id.rootText)
        netText = findViewById(R.id.netText)
        countText = findViewById(R.id.countText)
        progress = findViewById(R.id.progress)
        list = findViewById(R.id.list)
        empty = findViewById(R.id.empty)

        list.adapter = adapter
        list.setOnItemLongClickListener { _, _, pos, _ ->
            val d = devices[pos]
            copyText("Device", "${d.name}\n${d.type}\n${d.ip}\n${d.mac}", "Copied name, type, IP and MAC")
            true
        }
        findViewById<View>(R.id.copyAllBtn).setOnClickListener {
            val rows = devices.filter { !it.mac.startsWith("(") }
            if (rows.isEmpty()) {
                Toast.makeText(this, "Nothing to copy. Scan first.", Toast.LENGTH_SHORT).show()
            } else {
                copyText("Devices", rows.joinToString("\n") { "${it.name} | ${it.type} | ${it.ip} | ${it.mac}" },
                    "Copied ${rows.size} devices")
            }
        }
        findViewById<View>(R.id.helpBtn).setOnClickListener { showHelp() }
        findViewById<View>(R.id.creditsBtn).setOnClickListener { showCredits() }
        scanBtn.setOnClickListener { scan() }
        findViewById<TextView>(R.id.updateBtn).apply {
            text = "Check update (v${BuildConfig.VERSION_NAME})"
            setOnClickListener { checkUpdate(true) }
        }
        checkUpdate(false)

        refreshStatus()
        val prefs = getSharedPreferences("p", MODE_PRIVATE)
        if (!prefs.getBoolean("seen_help", false)) {
            prefs.edit().putBoolean("seen_help", true).apply()
            showHelp()
        }
    }

    private fun checkUpdate(manual: Boolean) {
        if (manual) Toast.makeText(this, "Checking for update...", Toast.LENGTH_SHORT).show()
        thread {
            val info = Updater.check()
            runOnUiThread {
                if (isFinishing) return@runOnUiThread
                when {
                    info != null && info.code > BuildConfig.VERSION_CODE -> showUpdate(info)
                    manual && info == null -> Toast.makeText(this, "Couldn't check for update. Check your internet.", Toast.LENGTH_LONG).show()
                    manual -> Toast.makeText(this, "You have the latest version", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    private fun showUpdate(info: Updater.Info) {
        val notes = info.notes.trim().ifEmpty { "Bug fixes and improvements." }
        AlertDialog.Builder(this, android.R.style.Theme_Material_Dialog_Alert)
            .setTitle("Update available")
            .setMessage("${info.name}\n(you have v${BuildConfig.VERSION_NAME})\n\n$notes\n\nYour app is updated in place. No need to uninstall.")
            .setPositiveButton("Update now") { _, _ -> startUpdate(info) }
            .setNegativeButton("Later", null)
            .show()
    }

    private fun startUpdate(info: Updater.Info) {
        val dlg = AlertDialog.Builder(this, android.R.style.Theme_Material_Dialog_Alert)
            .setTitle("Updating")
            .setMessage("Downloading... 0%")
            .setCancelable(false)
            .show()
        thread {
            val apk = File(cacheDir, "updates/WifiRadar.apk")
            val ok = Updater.download(info.url, apk) { p ->
                runOnUiThread { dlg.setMessage("Downloading... $p%") }
            }
            if (!ok) {
                runOnUiThread { dlg.dismiss(); Toast.makeText(this, "Download failed. Try again.", Toast.LENGTH_LONG).show() }
                return@thread
            }
            runOnUiThread { dlg.setMessage("Installing...") }
            if (Scanner.hasRoot() && Updater.rootInstall(apk)) return@thread
            runOnUiThread {
                dlg.dismiss()
                if (!packageManager.canRequestPackageInstalls()) {
                    Toast.makeText(this, "Allow \"Install unknown apps\" for WiFi Radar, then tap Update again", Toast.LENGTH_LONG).show()
                    startActivity(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:$packageName")))
                } else {
                    try { Updater.sessionInstall(this, apk) }
                    catch (e: Exception) { Toast.makeText(this, "Install failed: ${e.message}", Toast.LENGTH_LONG).show() }
                }
            }
        }
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private fun showCredits() {
        val links = listOf(
            "Facebook Group" to "https://facebook.com/share/g/1JbRQgSuZz/",
            "Messenger Group (MAX)" to "https://m.me/j/Abado-eMQJTWGYcV",
            "Telegram Group" to "https://t.me/+mU6PXP7hXR9hN2M1",
            "Proof / Verification" to "https://t.me/nayemroot",
            "YouTube Channel" to "https://youtube.com/@bengleraptor",
            "TikTok" to "https://tiktok.com/@naimbinharun"
        )
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(8), dp(20), dp(8))
        }
        box.addView(TextView(this).apply {
            text = "Stay connected and get new updates by joining our social media and community groups below."
            setTextColor(0xFFCBD5E1.toInt()); textSize = 14f
        })
        for ((label, url) in links) {
            box.addView(TextView(this).apply {
                text = label
                setTextColor(0xFF22D3EE.toInt()); textSize = 15f; typeface = Typeface.DEFAULT_BOLD
                background = getDrawable(R.drawable.bg_card)
                setPadding(dp(16), dp(12), dp(16), dp(12))
                setOnClickListener {
                    try {
                        startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
                    } catch (e: Exception) {
                        copyText(label, url, "Link copied")
                    }
                }
            }, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(10) })
        }
        box.addView(TextView(this).apply {
            text = "Thank you for being with us. Your support and cooperation mean a lot to us."
            setTextColor(0xFF94A3B8.toInt()); textSize = 13f
            setPadding(0, dp(16), 0, 0)
        })
        AlertDialog.Builder(this, android.R.style.Theme_Material_Dialog_Alert)
            .setTitle("Credits & Community")
            .setView(ScrollView(this).apply { addView(box) })
            .setPositiveButton("Close", null)
            .show()
    }

    private fun copyText(label: String, text: String, toast: String) {
        val cm = getSystemService(CLIPBOARD_SERVICE) as ClipboardManager
        cm.setPrimaryClip(ClipData.newPlainText(label, text))
        Toast.makeText(this, toast, Toast.LENGTH_SHORT).show()
    }

    private fun dot(color: Int) {
        rootDot.background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(color) }
    }

    private fun showStatus(root: Boolean, wifi: Pair<String, String>?, internet: Boolean? = null) {
        dot(if (root) 0xFF34D399.toInt() else 0xFFF87171.toInt())
        rootText.text = if (root) "Root granted" else "No root access"
        netText.text = if (wifi != null) "Network: ${wifi.second.substringBeforeLast(".")}.0/24"
        else "Not connected to WiFi"
        if (wifi != null && internet != null) {
            netText.text = netText.text.toString() + "\nInternet: " + if (internet) "WORKING" else "NO INTERNET"
            netText.setTextColor(if (internet) 0xFF34D399.toInt() else 0xFFF87171.toInt())
        } else netText.setTextColor(0xFF94A3B8.toInt())
    }

    private fun refreshStatus() {
        dot(Color.GRAY)
        thread {
            val root = Scanner.hasRoot()
            val wifi = Scanner.localWifi()
            runOnUiThread { showStatus(root, wifi) }
        }
    }

    private fun scan() {
        scanBtn.isEnabled = false
        scanBtn.alpha = 0.5f
        scanBtn.text = "Scanning..."
        progress.visibility = View.VISIBLE
        empty.visibility = View.GONE
        thread {
            val root = Scanner.hasRoot()
            val wifi = Scanner.localWifi()
            val found = if (root && wifi != null) Scanner.scan(applicationContext) else emptyList()
            val net = if (root && wifi != null) Scanner.hasInternet(wifi.first) else null
            runOnUiThread {
                scanBtn.isEnabled = true
                scanBtn.alpha = 1f
                scanBtn.text = "Scan network"
                progress.visibility = View.INVISIBLE
                devices.clear(); devices.addAll(found)
                adapter.notifyDataSetChanged()
                countText.text = found.size.toString()
                showStatus(root, wifi, net)
                if (found.isEmpty()) {
                    empty.visibility = View.VISIBLE
                    empty.text = when {
                        !root -> "Root access was denied.\nGrant it in Magisk / KernelSU\nand try again."
                        wifi == null -> "Connect to a WiFi network\nand try again."
                        else -> "No devices found."
                    }
                }
            }
        }
    }

    private fun showHelp() {
        AlertDialog.Builder(this, android.R.style.Theme_Material_Dialog_Alert)
            .setTitle("How to use")
            .setMessage(
                "REQUIREMENTS\n" +
                "• Rooted phone (Magisk / KernelSU)\n" +
                "• Connected to the WiFi you want to scan\n\n" +
                "STEPS\n" +
                "1. Open the app and allow root when asked\n" +
                "2. Tap \"Scan network\" and wait 5-10 seconds\n" +
                "3. Each device shows name, IP and MAC\n" +
                "4. Tap \"Copy MAC\" on a card to copy its MAC\n" +
                "5. Long-press a card to copy name + IP + MAC\n" +
                "6. \"Copy all\" copies the whole list\n\n" +
                "GOOD TO KNOW\n" +
                "• Type (Phone / Computer / Router...) is detected from name, open ports and MAC vendor. A \"?\" means it is a best guess\n" +
                "• Top card: \"Internet: WORKING / NO INTERNET\" tells if this whole WiFi has internet. Each device shows ONLINE (green) or NO REPLY (red). Android cannot see whether one single device has internet\n" +
                "• \"Frames lost\" = how many of 10 test pings got no reply. More lost frames and higher ping usually means that device (or the WiFi) is busy\n" +
                "• \"Unknown\" = device does not share its name\n" +
                "• \"Private MAC\" = phone uses a random MAC for this WiFi\n" +
                "• Your own MAC is hidden by Android\n" +
                "• Scan only networks you own or have permission to scan"
            )
            .setPositiveButton("Got it", null)
            .show()
    }
}
