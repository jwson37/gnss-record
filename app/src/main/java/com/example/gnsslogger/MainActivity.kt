package com.example.gnsslogger

import android.animation.ValueAnimator
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.*
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Bundle
import android.os.VibrationEffect
import android.os.Vibrator
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.view.Gravity
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.view.WindowManager
import android.widget.*
import org.osmdroid.config.Configuration
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.util.BoundingBox
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.Overlay
import java.io.File

val COLOR_FIXED = Color.rgb(46, 204, 64)     // green  = RTK fixed
val COLOR_FLOAT = Color.rgb(255, 140, 0)     // orange = RTK float
val COLOR_OTHER = Color.rgb(140, 140, 140)   // gray   = autonomous / DGPS

class MainActivity : Activity() {
    private lateinit var map: MapView
    private lateinit var status: TextView
    private lateinit var rec: Button
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
        }
        root.addView(map, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
        DxfStore.loadAll(applicationContext) { runOnUiThread { map.invalidate() } }

        // top bar: status + legend + settings
        val top = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; setBackgroundColor(Color.argb(215, 255, 255, 255))
            setPadding(dp(12), dp(8), dp(8), dp(8)); gravity = Gravity.CENTER_VERTICAL
        }
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        status = TextView(this).apply { typeface = Typeface.MONOSPACE; textSize = 12f; setTextColor(Color.BLACK) }
        val legend = TextView(this).apply { textSize = 12f; text = legendText() }
        col.addView(status); col.addView(legend)
        top.addView(col, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
        top.addView(Button(this).apply {
            text = "\u2699"; textSize = 22f
            setOnClickListener { startActivity(Intent(this@MainActivity, SettingsActivity::class.java)) }
        }, LinearLayout.LayoutParams(dp(56), dp(48)))
        root.addView(top, FrameLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT, Gravity.TOP))

        // bottom row: MARK | REC | recenter
        val bottom = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER }
        val mark = circle("MARK", Color.DKGRAY, 60, 11f)
        rec = circle("REC", Color.RED, 88, 16f)
        val loc = circle("\u2316", Color.DKGRAY, 60, 24f)
        bottom.addView(mark); bottom.addView(rec); bottom.addView(loc)
        root.addView(bottom, FrameLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT, Gravity.BOTTOM).apply { bottomMargin = dp(28) })
        setContentView(root)

        rec.setOnClickListener {
            if (Session.running) { buzz(longArrayOf(0, 70, 70, 70)); Session.stop() }
            else { buzz(longArrayOf(0, 120)); centered = false; Session.start(applicationContext) }
            refresh()
        }
        mark.setOnClickListener {
            if (Session.running) { Session.mark(); buzz(longArrayOf(0, 40)) }
            else Toast.makeText(this, "Press REC first", Toast.LENGTH_SHORT).show()
        }
        loc.setOnClickListener {
            if (!Session.lastLat.isNaN()) { map.controller.setZoom(20.0); map.controller.setCenter(GeoPoint(Session.lastLat, Session.lastLon)) }
        }
    }

    private fun legendText() = SpannableStringBuilder().apply {
        fun dot(label: String, c: Int) {
            val s = length; append("\u25CF "); setSpan(ForegroundColorSpan(c), s, length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            append("$label   ")
        }
        dot("RTK fixed", COLOR_FIXED); dot("RTK float", COLOR_FLOAT); dot("other", COLOR_OTHER)
    }

    @Suppress("DEPRECATION")
    private fun buzz(pattern: LongArray) {
        val v = getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
        if (Build.VERSION.SDK_INT >= 26) v.vibrate(VibrationEffect.createWaveform(pattern, -1)) else v.vibrate(pattern, -1)
    }

    private fun refresh() {
        val s = Session
        status.text = if (s.running && s.message.isEmpty())
            "${s.fixName} | sats ${s.sats} | HDOP ${s.hdop}\n${s.latS}, ${s.lonS}\nh ${s.heightS} m | NTRIP ${s.ntripStatus} | ${s.count} pts" +
                (if (s.crsS.isNotEmpty() && s.crsS != "EPSG:4326") "\n${s.crsS}  E ${s.eS}  N ${s.nS}" else "")
        else if (s.running) "${s.message}\nNTRIP ${s.ntripStatus}" else s.message
        rec.text = if (s.running) "STOP" else "REC"
        if (s.running) startBlink() else stopBlink()
        if (!centered && !s.lastLat.isNaN()) {
            map.controller.setZoom(20.0); map.controller.setCenter(GeoPoint(s.lastLat, s.lastLon)); centered = true
        }
        map.invalidate()
    }

    private fun zoomTo(z: DoubleArray) {   // z = minLat, minLon, maxLat, maxLon
        if (z[2] - z[0] < 1e-6 && z[3] - z[1] < 1e-6) { map.controller.setZoom(19.0); map.controller.setCenter(GeoPoint(z[0], z[1])) }
        else map.zoomToBoundingBox(BoundingBox(z[2], z[3], z[0], z[1]), false, dp(48))
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
        val z = DxfStore.pendingZoom; DxfStore.pendingZoom = null
        if (z != null) { centered = true; map.post { zoomTo(z) } } else centered = false
        refresh()
    }

    override fun onPause() {
        Session.onUpdate = null; stopBlink(); map.onPause(); super.onPause()
    }
}

class PointsOverlay(private val pts: List<Session.Pt>, density: Float) : Overlay() {
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val edge = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 1.5f * density; color = Color.WHITE }
    private val markEdge = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 3f * density; color = Color.BLACK }
    private val r = 5f * density
    private val g = GeoPoint(0.0, 0.0)
    private val px = Point()

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
                fill.color = when (p.q) { 4 -> COLOR_FIXED; 5 -> COLOR_FLOAT; else -> COLOR_OTHER }
                val rad = if (i == n - 1) r * 1.8f else r
                c.drawCircle(px.x.toFloat(), px.y.toFloat(), rad, fill)
                c.drawCircle(px.x.toFloat(), px.y.toFloat(), rad, if (p.marked) markEdge else edge)
            }
        }
    }
}
