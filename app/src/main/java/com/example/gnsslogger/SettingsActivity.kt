package com.example.gnsslogger

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.*

class SettingsActivity : Activity() {
    private lateinit var spinner: Spinner
    private lateinit var baud: EditText; private lateinit var host: EditText; private lateinit var port: EditText
    private lateinit var mount: EditText; private lateinit var user: EditText; private lateinit var pass: EditText
    private lateinit var tls: CheckBox
    private lateinit var stat: EditText
    private lateinit var projSpinner: Spinner; private lateinit var customCrs: EditText; private lateinit var projInfo: TextView
    private lateinit var dxfBox: LinearLayout
    private var items: List<Session.Item> = emptyList()

    // label -> spec ("" = custom). Esri WKIDs equal to EPSG codes work directly.
    private val presets: List<Pair<String, String>> =
        listOf("WGS 1984 geographic - lon/lat (WKID 4326)" to "EPSG:4326",
            "WGS 1984 UTM - automatic zone" to Proj.AUTO,
            "WGS 1984 Web Mercator (WKID 102100)" to "102100") +
        (10..19).map { "NAD 1983 UTM Zone ${it}N (WKID 269$it)" to "EPSG:269$it" } +
        listOf("Custom: WKID / EPSG: / ESRI: code or +proj string" to "")

    private fun field(parent: LinearLayout, hint: String, def: String, pwd: Boolean = false): EditText {
        val e = EditText(this)
        e.hint = hint; e.setText(def); e.setSingleLine()
        if (pwd) e.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
        parent.addView(e); return e
    }

    private fun label(parent: LinearLayout, t: String) = parent.addView(TextView(this).apply {
        text = t; textSize = 14f; setTextColor(Color.BLACK); setTypeface(typeface, Typeface.BOLD); setPadding(0, 40, 0, 4)
    })

    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        val p = getSharedPreferences("cfg", Context.MODE_PRIVATE)
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(36, 36, 36, 36) }
        setContentView(ScrollView(this).apply { addView(root) })
        root.addView(TextView(this).apply { text = "Settings"; textSize = 24f; setTextColor(Color.BLACK) })

        label(root, "GNSS receiver (USB OTG or paired Bluetooth)")
        spinner = Spinner(this); root.addView(spinner)
        root.addView(Button(this).apply { text = "Refresh device list"; setOnClickListener { loadDevices(null) } })
        baud = field(root, "USB baud rate", p.getString("baud", "115200")!!)

        label(root, "NTRIP correction service")
        host = field(root, "Host", p.getString("host", "")!!)
        port = field(root, "Port", p.getString("port", "2101")!!)
        mount = field(root, "Mountpoint", p.getString("mount", "")!!)
        user = field(root, "Username", p.getString("user", "")!!)
        pass = field(root, "Password", p.getString("pass", "")!!, true)
        tls = CheckBox(this).apply { text = "Use TLS"; isChecked = p.getBoolean("tls", false) }
        root.addView(tls)

        label(root, "Averaging")
        root.addView(TextView(this).apply {
            text = "If a recording stays within this radius (95% of fixes), a weighted average (RTK fixed weighted most, by 1/HDOP^2) is saved to gnss_averages.csv and drawn as a diamond."
            textSize = 12f
        })
        stat = field(root, "Stationary threshold (m)", p.getString("stat", "1.0")!!)
        root.addView(Button(this).apply {
            text = "Clear points from map"
            setOnClickListener { synchronized(Session.points) { Session.points.clear() }; Toast.makeText(this@SettingsActivity, "Map points cleared", Toast.LENGTH_SHORT).show() }
        })

        label(root, "Coordinate system (Esri WKID)")
        root.addView(TextView(this).apply {
            text = "Adds easting/northing columns to the CSV and is used to read imported DXF files. Lat/lon stay WGS84."
            textSize = 12f
        })
        projSpinner = Spinner(this)
        projSpinner.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, presets.map { it.first })
        root.addView(projSpinner)
        customCrs = field(root, "e.g. 26917, 102629, ESRI:102629, +proj=utm +zone=16 ...", "")
        customCrs.visibility = View.GONE
        projInfo = TextView(this).apply { textSize = 11f; typeface = Typeface.MONOSPACE; setTextColor(Color.DKGRAY) }
        root.addView(Button(this).apply { text = "Apply coordinate system"; setOnClickListener { applyProjection(false) } })
        root.addView(projInfo)
        val saved = p.getString("crs", "EPSG:4326")!!
        val idx = presets.indexOfFirst { it.second == saved }
        if (idx >= 0) projSpinner.setSelection(idx) else { projSpinner.setSelection(presets.size - 1); customCrs.setText(saved) }
        customCrs.visibility = if (presets[projSpinner.selectedItemPosition].second.isEmpty()) View.VISIBLE else View.GONE
        projSpinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(a: AdapterView<*>?, v: View?, pos: Int, id: Long) {
                customCrs.visibility = if (presets[pos].second.isEmpty()) View.VISIBLE else View.GONE
            }
            override fun onNothingSelected(a: AdapterView<*>?) {}
        }
        applyProjection(true)

        label(root, "DXF overlays")
        root.addView(TextView(this).apply {
            text = "ASCII DXF only (LINE, POLYLINE, LWPOLYLINE, ARC, CIRCLE, POINT, TEXT). Coordinates are read in the coordinate system selected above. To fix a wrong system, remove and re-import."
            textSize = 12f
        })
        root.addView(Button(this).apply {
            text = "Import DXF file..."
            setOnClickListener {
                if (applyProjection(false)) startActivityForResult(
                    Intent(Intent.ACTION_OPEN_DOCUMENT).apply { addCategory(Intent.CATEGORY_OPENABLE); type = "*/*" }, 42)
            }
        })
        dxfBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        root.addView(dxfBox)
        refreshDxf()

        root.addView(TextView(this).apply {
            text = "\nChanges apply the next time you press REC.\nCSV logs: ${getExternalFilesDir(null)?.absolutePath}"
            textSize = 12f; setPadding(0, 28, 0, 28)
        })
        root.addView(Button(this).apply { text = "Done"; setOnClickListener { finish() } })
        loadDevices(p.getString("dev", null))
    }

    private fun currentSpec(): String {
        val s = presets[projSpinner.selectedItemPosition].second
        return if (s.isEmpty()) customCrs.text.toString().trim() else s
    }

    private fun applyProjection(silent: Boolean): Boolean {
        val s = currentSpec()
        return try {
            if (s.isEmpty()) throw IllegalArgumentException("enter a WKID or +proj string")
            val d = Proj.describe(s)
            getSharedPreferences("cfg", Context.MODE_PRIVATE).edit().putString("crs", s).apply()
            Proj.spec = s; projInfo.text = d; true
        } catch (e: Exception) {
            projInfo.text = "Invalid coordinate system: ${e.message}"
            if (!silent) Toast.makeText(this, "Unknown coordinate system", Toast.LENGTH_SHORT).show()
            false
        }
    }

    private fun refreshDxf() {
        dxfBox.removeAllViews()
        if (DxfStore.layers.isEmpty()) dxfBox.addView(TextView(this).apply { text = "No overlays imported"; textSize = 12f })
        for (d in DxfStore.layers) {
            val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
            row.addView(CheckBox(this).apply {
                text = "${d.name}  [${d.spec}]"; textSize = 13f; isChecked = d.visible
                setOnCheckedChangeListener { _, v -> d.visible = v }
            }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            row.addView(Button(this).apply {
                text = "Remove"; setOnClickListener { DxfStore.remove(applicationContext, d); refreshDxf() }
            })
            dxfBox.addView(row)
        }
    }

    override fun onActivityResult(req: Int, res: Int, data: Intent?) {
        super.onActivityResult(req, res, data)
        val uri = data?.data
        if (req != 42 || res != RESULT_OK || uri == null) return
        Toast.makeText(this, "Importing...", Toast.LENGTH_SHORT).show()
        Thread {
            val msg = try { DxfStore.import(applicationContext, uri) } catch (e: Exception) { "Import failed: ${e.message}" }
            runOnUiThread { Toast.makeText(this, msg, Toast.LENGTH_LONG).show(); refreshDxf() }
        }.start()
    }

    private fun loadDevices(select: String?) {
        items = Session.listDevices(this)
        spinner.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item,
            if (items.isEmpty()) listOf("No devices - plug in USB or pair Bluetooth, then Refresh") else items.map { it.label })
        items.indexOfFirst { it.key == select }.let { if (it >= 0) spinner.setSelection(it) }
    }

    override fun onPause() {
        applyProjection(true)
        val e = getSharedPreferences("cfg", Context.MODE_PRIVATE).edit()
            .putString("baud", baud.text.toString()).putString("host", host.text.toString().trim())
            .putString("port", port.text.toString().trim()).putString("mount", mount.text.toString().trim())
            .putString("user", user.text.toString().trim()).putString("pass", pass.text.toString())
            .putBoolean("tls", tls.isChecked).putString("stat", stat.text.toString())
        items.getOrNull(spinner.selectedItemPosition)?.let { e.putString("dev", it.key) }
        e.apply()
        super.onPause()
    }
}
