package com.example.gnsslogger

import android.animation.ValueAnimator
import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.graphics.*
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.Bundle
import android.os.VibrationEffect
import android.os.Vibrator
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.view.Gravity
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.view.View
import android.view.WindowManager
import android.widget.*
import org.osmdroid.config.Configuration
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.util.BoundingBox
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.Overlay
import org.osmdroid.views.overlay.ScaleBarOverlay
import java.io.File

val ICON_GREY = Color.rgb(150, 150, 150)     // icons: grey = not connected
val ICON_GREEN = Color.rgb(46, 204, 64)      // green = connected / good / RTK fixed
val ICON_ORANGE = Color.rgb(255, 140, 0)     // orange = connecting / float
// one fill color per recording period, cycled (kept clear of green/orange)
val PALETTE = intArrayOf(Color.rgb(30, 90, 255), Color.rgb(200, 0, 200), Color.rgb(0, 190, 210), Color.rgb(120, 60, 200),
    Color.rgb(210, 30, 30), Color.rgb(120, 80, 40), Color.rgb(230, 60, 150), Color.rgb(0, 110, 120), Color.rgb(150, 150, 0))
val COLOR_FIXED = Color.rgb(46, 204, 64)     // green  = RTK fixed
val COLOR_FLOAT = Color.rgb(255, 140, 0)     // orange = RTK float
val COLOR_OTHER = Color.rgb(140, 140, 140)   // gray   = autonomous / DGPS

class MainActivity : Activity() {
    private lateinit var map: MapView
    private lateinit var status: TextView
    private lateinit var rec: Button
    private lateinit var poleBtn: Button
    private val ticker = Handler(Looper.getMainLooper())
    private val tick = object : Runnable { override fun run() { Session.sampleRates(); refresh(); ticker.postDelayed(this, 1000) } }
    private lateinit var llText: TextView
    private lateinit var icRx: StatusIcon
    private lateinit var icNt: StatusIcon
    private lateinit var icSat: StatusIcon
    private lateinit var scaleBar: ScaleBarOverlay
    private var blink: ValueAnimator? = null
    private var centered = false

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private fun circle(text: String, color: Int, size: Int, sp: Float) = Button(this).apply {
        this.text = text; setTextColor(Color.WHITE); textSize = sp; setTypeface(typeface, Typeface.BOLD)
        background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(color) }
        stateListAnimator = null; setPadding(0, 0, 0, 0)
        layoutParams = LinearLayout.LayoutParams(dp(size), dp(size)).apply { setMargins(dp(14), 0, dp(14), 0) }
    }

    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        val cfg = Configuration.getInstance()
        cfg.userAgentValue = packageName
        val base = File(getExternalFilesDir(null), "osmdroid")
        cfg.osmdroidBasePath = base; cfg.osmdroidTileCache = File(base, "tiles")

        val root = FrameLayout(this)
        map = MapView(this).apply {
            setTileSource(TileSourceFactory.MAPNIK); setMultiTouchControls(true)
            minZoomLevel = 2.0; maxZoomLevel = 22.0; controller.setZoom(4.0)
            overlays.add(DxfOverlay(resources.displayMetrics.density))
            overlays.add(PointsOverlay(Session.points, resources.displayMetrics.density))
            val dens = resources.displayMetrics.density
            scaleBar = ScaleBarOverlay(this).apply {
                setAlignBottom(true); setScaleBarOffset((12f * dens).toInt(), (110f * dens).toInt()); setLineWidth(2f * dens)
                setTextPaint(Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.BLACK; textSize = 12f * dens })
            }
            overlays.add(scaleBar)
        }
        root.addView(map, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
        DxfStore.loadAll(applicationContext) { runOnUiThread { map.invalidate() } }

        // top bar: 3 status icons (receiver, NTRIP cloud, satellites) + gear, then status text and legend
        val top = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; setBackgroundColor(Color.argb(215, 255, 255, 255))
            setPadding(dp(12), dp(6), dp(8), dp(8))
        }
        icRx = StatusIcon(this, 0); icNt = StatusIcon(this, 1); icSat = StatusIcon(this, 2)
        val iconRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        for (ic in listOf(icRx, icNt, icSat)) {
            ic.setOnClickListener { showConnectionDialog() }
            iconRow.addView(ic, LinearLayout.LayoutParams(dp(58), dp(50)))
        }
        iconRow.addView(View(this), LinearLayout.LayoutParams(0, 1, 1f))
        iconRow.addView(Button(this).apply {
            text = "\u2699"; textSize = 22f
            setOnClickListener { startActivity(Intent(this@MainActivity, SettingsActivity::class.java)) }
        }, LinearLayout.LayoutParams(dp(56), dp(48)))
        status = TextView(this).apply { typeface = Typeface.MONOSPACE; textSize = 11f; setTextColor(Color.BLACK) }
        llText = TextView(this).apply {
            typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD); setTextColor(Color.BLACK)
            setSingleLine(); visibility = View.GONE
        }
        top.addView(iconRow); top.addView(llText); top.addView(status)
        root.addView(top, FrameLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT, Gravity.TOP))

        // bottom row: MARK | REC | recenter
        val bottom = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER }
        val mark = circle("MARK", Color.DKGRAY, 60, 11f)
        rec = circle("REC", Color.RED, 88, 16f)
        val loc = circle("\u2316", Color.DKGRAY, 60, 24f)
        bottom.addView(mark); bottom.addView(rec); bottom.addView(loc)
        root.addView(bottom, FrameLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT, Gravity.BOTTOM).apply { bottomMargin = dp(28) })
        poleBtn = Button(this).apply { textSize = 12f; setOnClickListener { editPole() } }
        root.addView(poleBtn, FrameLayout.LayoutParams(WRAP_CONTENT, dp(40), Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL).apply { bottomMargin = dp(140) })
        val fit = circle("FIT", Color.DKGRAY, 52, 11f)   // tap: latest recording; long-press: everything
        fit.setOnClickListener { zoomExtents(false) }
        fit.setOnLongClickListener { zoomExtents(true); true }
        root.addView(fit, FrameLayout.LayoutParams(dp(52), dp(52), Gravity.BOTTOM or Gravity.END).apply { bottomMargin = dp(150); rightMargin = dp(12) })
        setContentView(root)

        rec.setOnClickListener {
            if (Session.recording) { buzz(longArrayOf(0, 70, 70, 70)); Session.stopRecording() }
            else { buzz(longArrayOf(0, 120)); Session.startRecording(applicationContext) }
            refresh()
        }
        mark.setOnClickListener {
            if (Session.recording) { Session.mark(); buzz(longArrayOf(0, 40)) }
            else Toast.makeText(this, "Press REC first", Toast.LENGTH_SHORT).show()
        }
        loc.setOnClickListener {
            if (!Session.lastLat.isNaN()) { map.controller.setZoom(20.0); map.controller.setCenter(GeoPoint(Session.lastLat, Session.lastLon)) }
        }
    }

    @Suppress("DEPRECATION")
    private fun buzz(pattern: LongArray) {
        val v = getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
        if (Build.VERSION.SDK_INT >= 26) v.vibrate(VibrationEffect.createWaveform(pattern, -1)) else v.vibrate(pattern, -1)
    }

    private fun refresh() {
        val s = Session
        status.text = (when {
            s.running && s.message.isEmpty() ->
                "h ${s.heightS} m${if (s.groundS.isNotEmpty()) "  ground ${s.groundS} m" else ""}\n" +
                    "NTRIP ${s.ntripStatus} | " + (if (s.recording) "REC ${s.count} pts" else "not recording") +
                    "\nRX ${fmtBps(s.rxBps)} bps | RTCM ${fmtBps(s.ntBps)} bps" +
                    (if (s.crsS.isNotEmpty() && s.crsS != "EPSG:4326") "\n${s.crsS}  E ${s.eS}  N ${s.nS}" else "")
            s.running -> "${s.message}\nNTRIP ${s.ntripStatus}"
            else -> s.message
        }) + (if (s.note.isNotEmpty()) "\n${s.note}" else "")
        val live = s.running && s.message.isEmpty()
        llText.visibility = if (live) View.VISIBLE else View.GONE
        if (live) { val t = "${s.latS}, ${s.lonS}"; llText.text = t; fitLatLon(t) }
        rec.text = if (s.recording) "STOP" else "REC"
        if (s.recording) startBlink() else stopBlink()
        updateIcons()
        if (!centered && !s.lastLat.isNaN()) {
            map.controller.setZoom(20.0); map.controller.setCenter(GeoPoint(s.lastLat, s.lastLon)); centered = true
        }
        map.invalidate()
    }

    /** Zoom to the extents of the latest recording (all = every recording plus visible DXF overlays). */
    private fun zoomExtents(all: Boolean) {
        var mnLa = 90.0; var mnLo = 180.0; var mxLa = -90.0; var mxLo = -180.0; var n = 0
        synchronized(Session.points) {
            val last = Session.points.maxOfOrNull { it.sess } ?: -1
            for (p in Session.points) {
                if (!all && p.sess != last) continue
                mnLa = minOf(mnLa, p.lat); mxLa = maxOf(mxLa, p.lat); mnLo = minOf(mnLo, p.lon); mxLo = maxOf(mxLo, p.lon); n++
            }
        }
        if (all) DxfStore.layers.filter { it.visible }.forEach { d ->
            mnLa = minOf(mnLa, d.bbox[0]); mnLo = minOf(mnLo, d.bbox[1]); mxLa = maxOf(mxLa, d.bbox[2]); mxLo = maxOf(mxLo, d.bbox[3]); n++
        }
        if (n == 0) { Toast.makeText(this, "No recorded points yet", Toast.LENGTH_SHORT).show(); return }
        val minSpan = 6e-5   // about 6 m, so a stationary recording is not zoomed out of reach
        if (mxLa - mnLa < minSpan) { val c = (mxLa + mnLa) / 2; mnLa = c - minSpan / 2; mxLa = c + minSpan / 2 }
        if (mxLo - mnLo < minSpan) { val c = (mxLo + mnLo) / 2; mnLo = c - minSpan / 2; mxLo = c + minSpan / 2 }
        zoomTo(doubleArrayOf(mnLa, mnLo, mxLa, mxLo))
    }

    private fun zoomTo(z: DoubleArray) {   // z = minLat, minLon, maxLat, maxLon
        if (z[2] - z[0] < 1e-6 && z[3] - z[1] < 1e-6) { map.controller.setZoom(19.0); map.controller.setCenter(GeoPoint(z[0], z[1])) }
        else map.zoomToBoundingBox(BoundingBox(z[2], z[3], z[0], z[1]), false, dp(80))
    }

    private fun refreshPole() {
        poleBtn.text = "Sensor height: " + Session.poleLabel(getSharedPreferences("cfg", Context.MODE_PRIVATE))
    }

    private fun editPole() {
        if (Session.recording) { Toast.makeText(this, "Stop recording to change the sensor height", Toast.LENGTH_SHORT).show(); return }
        val p = getSharedPreferences("cfg", Context.MODE_PRIVATE)
        val unit = Session.UNIT_NAMES[p.getInt("ahu", 0).coerceIn(0, 2)]
        val e = EditText(this).apply {
            inputType = android.text.InputType.TYPE_CLASS_NUMBER or android.text.InputType.TYPE_NUMBER_FLAG_DECIMAL or android.text.InputType.TYPE_NUMBER_FLAG_SIGNED
            setText(p.getString("ah", "0")); setSelectAllOnFocus(true)
        }
        AlertDialog.Builder(this).setTitle("Sensor height above ground ($unit)").setView(e)
            .setPositiveButton("OK") { _, _ ->
                p.edit().putString("ah", e.text.toString().trim().ifEmpty { "0" }).apply(); refreshPole(); Session.refreshPole(applicationContext)
            }.setNegativeButton("Cancel", null).show()
    }

    /** Size the lat/lon text so the line fills the screen width. */
    private fun fitLatLon(txt: String) {
        val avail = (resources.displayMetrics.widthPixels - dp(20)).toFloat()
        val pt = Paint().apply { typeface = llText.typeface; textSize = 100f }
        val w = pt.measureText(txt)
        if (w <= 0f) return
        val px = minOf(100f * avail / w, 30f * resources.displayMetrics.density)
        llText.setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, px)
    }

    private fun updateIcons() {
        val s = Session
        icRx.set(when (s.linkState) { 2 -> ICON_GREEN; 1 -> ICON_ORANGE; else -> ICON_GREY },
            when (s.linkState) { 2 -> fmtBps(s.rxBps); 1 -> "..."; else -> "" })
        val nt = s.ntripStatus
        icNt.set(when { nt == "streaming" -> ICON_GREEN; nt == "connecting" || nt.startsWith("error") -> ICON_ORANGE; else -> ICON_GREY },
            when { nt == "streaming" -> fmtBps(s.ntBps); nt == "connecting" -> "..."; nt.startsWith("error") -> "ERR"; else -> "" })
        val used = s.sats.toIntOrNull()
        icSat.set(when { s.linkState != 2 || s.fixQ == 0 -> ICON_GREY; s.fixQ == 4 -> ICON_GREEN; else -> ICON_ORANGE },
            if (s.linkState != 2 || used == null) "" else if (s.seen > 0) "$used/${maxOf(s.seen, used)}" else "$used")
    }

    // scale bar units follow the sensor-height unit: m -> metric, ft / ftUS -> imperial
    private fun applyScaleUnits() {
        val u = getSharedPreferences("cfg", Context.MODE_PRIVATE).getInt("ahu", 0)
        scaleBar.setUnitsOfMeasure(if (u == 0) ScaleBarOverlay.UnitsOfMeasure.metric else ScaleBarOverlay.UnitsOfMeasure.imperial)
        map.invalidate()
    }

    /** Popup shared by all three status icons: connect/disconnect, receiver, baud rate, sensor height. */
    private fun showConnectionDialog() {
        val s = Session
        val p = getSharedPreferences("cfg", Context.MODE_PRIVATE)
        val devs = ArrayList(Session.listDevices(this))
        val savedDev = p.getString("dev", null)
        if (savedDev != null && savedDev.startsWith("ble:") && devs.none { it.key == savedDev })
            devs.add(Session.Item(savedDev, "BLE saved device (${savedDev.substring(4)})"))
        val bauds = (listOf("4800", "9600", "19200", "38400", "57600", "115200", "230400", "460800", "921600") +
            (p.getString("baud", "115200") ?: "115200")).distinct().sortedBy { it.toIntOrNull() ?: 0 }
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(20), dp(8), dp(20), 0) }
        fun label(t: String) = box.addView(TextView(this).apply {
            text = t; textSize = 13f; setTextColor(Color.DKGRAY); setPadding(0, dp(14), 0, 0)
        })
        box.addView(TextView(this).apply {
            typeface = Typeface.MONOSPACE; textSize = 12f; setTextColor(Color.BLACK)
            text = "Receiver:   ${when (s.linkState) { 2 -> "connected (${s.linkType})"; 1 -> "connecting..."; else -> "not connected" }}\n" +
                "NTRIP:      ${s.ntripStatus}\n" +
                "Satellites: ${if (s.linkState == 2) "${s.fixName}, ${s.sats} used" + (if (s.seen > 0) " / ${s.seen} seen" else "") else "-"}"
        })
        label("Receiver")
        val devSpin = Spinner(this).apply {
            adapter = ArrayAdapter(this@MainActivity, android.R.layout.simple_spinner_dropdown_item,
                if (devs.isEmpty()) listOf("No devices found (plug in / pair, or scan BLE in More settings)") else devs.map { it.label })
            devs.indexOfFirst { it.key == savedDev }.let { if (it >= 0) setSelection(it) }
            isEnabled = !s.running
        }
        box.addView(devSpin)
        label("Baud rate (USB receivers)")
        val baudSpin = Spinner(this).apply {
            adapter = ArrayAdapter(this@MainActivity, android.R.layout.simple_spinner_dropdown_item, bauds)
            setSelection(bauds.indexOf(p.getString("baud", "115200")).coerceAtLeast(0))
            isEnabled = !s.running
        }
        box.addView(baudSpin)
        label("Instrument (sensor) height above ground")
        val hEdit = EditText(this).apply {
            inputType = android.text.InputType.TYPE_CLASS_NUMBER or android.text.InputType.TYPE_NUMBER_FLAG_DECIMAL or android.text.InputType.TYPE_NUMBER_FLAG_SIGNED
            setText(p.getString("ah", "0")); setSingleLine(); isEnabled = !s.recording
        }
        val hUnit = Spinner(this).apply {
            adapter = ArrayAdapter(this@MainActivity, android.R.layout.simple_spinner_dropdown_item, Session.UNIT_NAMES)
            setSelection(p.getInt("ahu", 0).coerceIn(0, 2)); isEnabled = !s.recording
        }
        val hRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        hRow.addView(hEdit, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f)); hRow.addView(hUnit)
        box.addView(hRow)
        if (s.recording) box.addView(TextView(this).apply { text = "Height is locked while recording."; textSize = 11f })

        fun save() {
            val e = p.edit()
            if (!s.running) {
                devs.getOrNull(devSpin.selectedItemPosition)?.let { e.putString("dev", it.key) }
                e.putString("baud", bauds[baudSpin.selectedItemPosition])
            }
            if (!s.recording) e.putString("ah", hEdit.text.toString().trim().ifEmpty { "0" }).putInt("ahu", hUnit.selectedItemPosition)
            e.apply(); refreshPole(); Session.refreshPole(applicationContext); applyScaleUnits()
        }

        AlertDialog.Builder(this).setTitle("Connection").setView(ScrollView(this).apply { addView(box) })
            .setPositiveButton(if (s.running) "Disconnect" else "Connect") { _, _ ->
                save()
                if (s.running) Session.stop() else Session.connect(applicationContext)
                refresh()
            }
            .setNeutralButton("More settings") { _, _ -> save(); startActivity(Intent(this, SettingsActivity::class.java)) }
            .setNegativeButton("Close") { _, _ -> save() }
            .show()
    }

    private fun startBlink() {
        if (blink != null) return
        blink = ValueAnimator.ofFloat(1f, 0.25f).apply {
            duration = 600; repeatCount = ValueAnimator.INFINITE; repeatMode = ValueAnimator.REVERSE
            addUpdateListener { rec.alpha = it.animatedValue as Float }; start()
        }
    }

    private fun stopBlink() { blink?.cancel(); blink = null; rec.alpha = 1f }

    override fun onResume() {
        super.onResume(); map.onResume()
        Session.onUpdate = { runOnUiThread { refresh() } }
        refreshPole(); applyScaleUnits()
        ticker.removeCallbacks(tick); ticker.post(tick)
        val z = DxfStore.pendingZoom; DxfStore.pendingZoom = null
        if (z != null) { centered = true; map.post { zoomTo(z) } } else centered = false
        refresh()
    }

    override fun onPause() {
        ticker.removeCallbacks(tick); Session.onUpdate = null; stopBlink(); map.onPause(); super.onPause()
    }
}

class PointsOverlay(private val pts: List<Session.Pt>, private val density: Float) : Overlay() {
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 3.5f * density }
    private val markEdge = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 2.5f * density; color = Color.BLACK }
    private val plusEdge = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 1.5f * density; color = Color.BLACK }
    private val r = 6f * density
    private val g = GeoPoint(0.0, 0.0)
    private val px = Point()
    private val dia = Path()

    override fun draw(c: Canvas, map: MapView, shadow: Boolean) {
        if (shadow) return
        val proj = map.projection
        synchronized(pts) {
            val n = pts.size
            for (i in 0 until n) {
                val p = pts[i]
                g.latitude = p.lat; g.longitude = p.lon
                proj.toPixels(g, px)
                if (px.x < -50 || px.y < -50 || px.x > map.width + 50 || px.y > map.height + 50) continue
                val x = px.x.toFloat(); val y = px.y.toFloat()
                val sc = PALETTE[p.sess % PALETTE.size]
                if (p.avg) {   // weighted average of a stationary recording: plus sign in the recording's color
                    val d = r * 2.8f; val w = r * 0.4f
                    dia.rewind()
                    dia.moveTo(x - w, y - d); dia.lineTo(x + w, y - d); dia.lineTo(x + w, y - w); dia.lineTo(x + d, y - w)
                    dia.lineTo(x + d, y + w); dia.lineTo(x + w, y + w); dia.lineTo(x + w, y + d); dia.lineTo(x - w, y + d)
                    dia.lineTo(x - w, y + w); dia.lineTo(x - d, y + w); dia.lineTo(x - d, y - w); dia.lineTo(x - w, y - w)
                    dia.close()
                    fill.color = sc; c.drawPath(dia, fill); c.drawPath(dia, plusEdge)
                    continue
                }
                fill.color = sc
                val rad = if (i == n - 1 && Session.recording) r * 1.8f else r
                c.drawCircle(x, y, rad, fill)
                ring.color = when (p.q) { 4 -> COLOR_FIXED; 5 -> COLOR_FLOAT; else -> COLOR_OTHER }; c.drawCircle(x, y, rad, ring)
                if (p.marked) c.drawCircle(x, y, rad + 3f * density, markEdge)
            }
        }
    }
}

/** Data rate in bits per second, short form: 950, 4.8k, 12k, 1.2M. */
fun fmtBps(b: Int): String = when {
    b >= 1_000_000 -> String.format(java.util.Locale.US, "%.1fM", b / 1e6)
    b >= 10_000 -> "${b / 1000}k"
    b >= 1_000 -> String.format(java.util.Locale.US, "%.1fk", b / 1000.0)
    else -> "$b"
}

/** Status icon: kind 0 = receiver, 1 = cloud (NTRIP), 2 = satellite. Grey / green / orange, text drawn inside the icon. */
class StatusIcon(ctx: Context, private val kind: Int) : View(ctx) {
    private var color = ICON_GREY
    private var label = ""
    private val p = Paint(Paint.ANTI_ALIAS_FLAG)
    private val tp = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER; typeface = Typeface.DEFAULT_BOLD; color = Color.WHITE
    }

    fun set(c: Int, l: String) { if (c != color || l != label) { color = c; label = l; invalidate() } }

    /** Text centred at cx, shrunk to fit maxW (everything in the 48-unit icon grid). */
    private fun txt(c: Canvas, t: String, cx: Float, base: Float, size: Float, maxW: Float) {
        tp.textSize = size
        val w = tp.measureText(t)
        if (w > maxW) tp.textSize = size * maxW / w
        c.drawText(t, cx, base, tp)
    }

    override fun onDraw(c: Canvas) {
        val w = width.toFloat(); val h = height.toFloat()
        val s = minOf(w, h) - 2f
        c.save(); c.translate((w - s) / 2f, (h - s) / 2f); c.scale(s / 48f, s / 48f)
        p.color = color; p.style = Paint.Style.FILL; p.strokeWidth = 3f; p.strokeCap = Paint.Cap.ROUND
        when (kind) {
            0 -> {   // survey GNSS receiver: dome, band, base, pole. Text sits in the dome.
                c.drawArc(RectF(6f, 8f, 42f, 44f), 180f, 180f, true, p)
                c.drawRoundRect(RectF(4f, 28f, 44f, 35f), 3f, 3f, p)
                c.drawRoundRect(RectF(21f, 35f, 27f, 46f), 1f, 1f, p)
                p.color = Color.WHITE
                c.drawCircle(11f, 31.5f, 1.3f, p); c.drawCircle(24f, 31.5f, 1.3f, p); c.drawCircle(37f, 31.5f, 1.3f, p)
                if (label.isNotEmpty()) txt(c, label, 24f, 23f, 11f, 28f)
            }
            1 -> {   // cloud
                c.drawCircle(15f, 31f, 8f, p); c.drawCircle(25f, 23f, 11f, p); c.drawCircle(35f, 30f, 8f, p)
                c.drawRect(15f, 31f, 35f, 39f, p)
                if (label.isNotEmpty()) txt(c, label, 25f, 34f, 11f, 28f)
            }
            else -> {   // satellite: body with solar panels. Text sits in the body: used over seen.
                c.drawRect(1f, 17f, 12f, 31f, p); c.drawRect(36f, 17f, 47f, 31f, p)
                c.drawRoundRect(RectF(13f, 12f, 35f, 38f), 3f, 3f, p)
                p.style = Paint.Style.STROKE; c.drawLine(24f, 12f, 24f, 7f, p); p.style = Paint.Style.FILL
                c.drawCircle(24f, 5.5f, 3f, p)
                p.color = Color.WHITE; p.strokeWidth = 1f; p.style = Paint.Style.STROKE
                c.drawLine(6.5f, 17f, 6.5f, 31f, p); c.drawLine(41.5f, 17f, 41.5f, 31f, p)
                c.drawLine(1f, 24f, 12f, 24f, p); c.drawLine(36f, 24f, 47f, 24f, p)
                val parts = label.split("/")
                if (parts.size == 2) {
                    txt(c, parts[0], 24f, 24f, 10.5f, 19f); txt(c, parts[1], 24f, 36f, 10.5f, 19f)
                    p.strokeWidth = 1.2f; c.drawLine(17f, 26.5f, 31f, 26.5f, p)
                } else if (label.isNotEmpty()) txt(c, label, 24f, 29f, 12f, 19f)
            }
        }
        c.restore()
    }
}
