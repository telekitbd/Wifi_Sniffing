package com.example.wifiscan

import android.app.Activity
import android.graphics.Typeface
import android.os.Bundle
import android.view.View
import android.widget.*
import kotlin.concurrent.thread

class MainActivity : Activity() {
    private lateinit var status: TextView
    private lateinit var list: ListView
    private lateinit var btn: Button

    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(32, 64, 32, 32) }
        btn = Button(this).apply { text = "Scan network"; setOnClickListener { scan() } }
        status = TextView(this).apply { text = "Connect to WiFi, then tap Scan"; setPadding(0, 16, 0, 16) }
        list = ListView(this)
        root.addView(btn); root.addView(status); root.addView(list)
        setContentView(root)
    }

    private fun scan() {
        btn.isEnabled = false
        status.text = "Scanning... (takes ~5-10s)"
        thread {
            val ok = Scanner.hasRoot()
            val devices = if (ok) Scanner.scan() else emptyList()
            runOnUiThread {
                btn.isEnabled = true
                if (!ok) { status.text = "Root access denied. Grant root in Magisk/KernelSU."; return@runOnUiThread }
                status.text = "${devices.size} devices found"
                list.adapter = object : ArrayAdapter<Device>(this, android.R.layout.simple_list_item_2, android.R.id.text1, devices) {
                    override fun getView(pos: Int, v: View?, parent: android.view.ViewGroup): View {
                        val view = super.getView(pos, v, parent)
                        val d = getItem(pos)!!
                        view.findViewById<TextView>(android.R.id.text1).apply { text = "${d.name}  •  ${d.ip}"; typeface = Typeface.DEFAULT_BOLD }
                        view.findViewById<TextView>(android.R.id.text2).text = d.mac + if (d.note.isNotEmpty()) "  (${d.note})" else ""
                        return view
                    }
                }
            }
        }
    }
}
