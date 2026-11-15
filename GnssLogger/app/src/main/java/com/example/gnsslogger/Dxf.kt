package com.example.gnsslogger

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Point
import android.net.Uri
import android.provider.OpenableColumns
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.Overlay
import java.io.*
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.math.*

/** Imported ASCII DXF drawings, projected to WGS84 at import time using the selected coordinate system. */
object DxfStore {
    class Shape(val lat: DoubleArray, val lon: DoubleArray, val closed: Boolean, val color: Int) {
        val minLat = lat.min(); val maxLat = lat.max(); val minLon = lon.min(); val maxLon = lon.max()
    }
    class Pnt(val lat: Double, val lon: Double, val color: Int)
    class Label(val lat: Double, val lon: Double, val text: String, val color: Int)
    class Drawing(val name: String, val spec: String, val shapes: List<Shape>, val pts: List<Pnt>,
                  val labels: List<Label>, val bbox: DoubleArray) { @Volatile var visible = true }

    val layers = CopyOnWriteArrayList<Drawing>()
    @Volatile var pendingZoom: DoubleArray? = null   // minLat, minLon, maxLat, maxLon
    @Volatile private var loaded = false

    fun loadAll(ctx: Context, done: () -> Unit) {
        if (loaded) return
        loaded = true
        Thread {
            val dir = File(ctx.filesDir, "dxf")
            dir.listFiles()?.filter { it.extension.equals("dxf", true) }?.sortedBy { it.name }?.forEach { f ->
                try {
                    val spec = File(dir, f.nameWithoutExtension + ".crs").readText().trim()
                    layers.add(parse(f, spec)); done()
                } catch (_: Exception) {}
            }
        }.start()
    }

    fun import(ctx: Context, uri: Uri): String {
        var disp = "drawing.dxf"
        ctx.contentResolver.query(uri, null, null, null, null)?.use { c ->
            if (c.moveToFirst()) { val i = c.getColumnIndex(OpenableColumns.DISPLAY_NAME); if (i >= 0) disp = c.getString(i) }
        }
        if (disp.endsWith(".dwg", true)) throw IOException("DWG is not supported - export as ASCII DXF")
        val base0 = disp.substringBeforeLast('.').replace(Regex("[^A-Za-z0-9_-]"), "_").ifEmpty { "drawing" }
        val dir = File(ctx.filesDir, "dxf").apply { mkdirs() }
        var base = base0; var n = 2
        while (File(dir, "$base.dxf").exists()) base = "${base0}_${n++}"
        val f = File(dir, "$base.dxf")
        (ctx.contentResolver.openInputStream(uri) ?: throw IOException("cannot read file")).use { i ->
            f.outputStream().use { o -> i.copyTo(o) }
        }
        try {
            val spec = Proj.resolve(Proj.spec, Session.lastLat, Session.lastLon)
            File(dir, "$base.crs").writeText(spec)
            val d = parse(f, spec)
            layers.add(d); pendingZoom = d.bbox
            return "Imported ${d.name}: ${d.shapes.size} lines, ${d.pts.size} points, ${d.labels.size} labels"
        } catch (e: Exception) {
            f.delete(); File(dir, "$base.crs").delete(); throw e
        }
    }

    fun remove(ctx: Context, d: Drawing) {
        layers.remove(d)
        val dir = File(ctx.filesDir, "dxf")
        File(dir, "${d.name}.dxf").delete(); File(dir, "${d.name}.crs").delete()
    }

    private class Ent(val type: String) {
        val c = ArrayList<Int>(); val v = ArrayList<String>()
        fun s(code: Int): String? { val i = c.indexOf(code); return if (i >= 0) v[i] else null }
        fun d(code: Int): Double = s(code)?.toDoubleOrNull() ?: Double.NaN
        fun i(code: Int, def: Int = 0): Int = s(code)?.toIntOrNull() ?: def
        fun all(code: Int): List<Double> = c.indices.filter { c[it] == code }.mapNotNull { v[it].toDoubleOrNull() }
    }

    private fun aci(n: Int): Int = when (n) {
        1 -> Color.RED; 2 -> Color.rgb(220, 190, 0); 3 -> Color.rgb(0, 160, 0); 4 -> Color.rgb(0, 170, 190)
        5 -> Color.BLUE; 6 -> Color.MAGENTA; 7, 0 -> Color.BLACK; 8 -> Color.DKGRAY; 9 -> Color.GRAY
        in 10..249 -> Color.HSVToColor(floatArrayOf(((n - 10) / 10) * 15f, if (n % 10 >= 6) 0.5f else 1f, if (n % 2 == 0) 0.95f else 0.65f))
        else -> Color.GRAY
    }

    private fun clean(s: String) = s.replace(Regex("\\\\[A-Za-z][^;\\\\]*;"), "").replace("\\P", " ")
        .replace("{", "").replace("}", "").trim()

    private fun parse(f: File, spec: String): Drawing {
        val head = ByteArray(22); f.inputStream().use { it.read(head) }
        if (String(head, Charsets.ISO_8859_1).startsWith("AutoCAD Binary DXF")) throw IOException("Binary DXF not supported - save as ASCII DXF")
        val layerColor = HashMap<String, Int>()
        val shapes = ArrayList<Shape>(); val pts = ArrayList<Pnt>(); val labels = ArrayList<Label>()
        var section = ""
        var polyHead: Ent? = null; val polyX = ArrayList<Double>(); val polyY = ArrayList<Double>()

        fun project(x: Double, y: Double): DoubleArray? = try {
            val r = Proj.toWgs84(spec, x, y)
            if (r[0].isNaN() || r[1].isNaN() || abs(r[0]) > 90 || abs(r[1]) > 180) null else r
        } catch (_: Exception) { null }

        fun colorOf(e: Ent): Int {
            val c = e.i(62, 256)
            return aci(if (c == 256 || c == 0) (layerColor[e.s(8) ?: "0"] ?: 7) else abs(c))
        }

        fun addShape(xs: List<Double>, ys: List<Double>, closed: Boolean, color: Int) {
            val n = min(xs.size, ys.size)
            if (n < 2) return
            val la = DoubleArray(n); val lo = DoubleArray(n); var k = 0
            for (i in 0 until n) {
                val ll = project(xs[i], ys[i]) ?: continue
                la[k] = ll[0]; lo[k] = ll[1]; k++
            }
            if (k >= 2) shapes.add(Shape(la.copyOf(k), lo.copyOf(k), closed, color))
        }

        fun arc(cx: Double, cy: Double, r: Double, a0: Double, a1: Double, color: Int) {
            var end = a1; if (end <= a0) end += 360.0
            val steps = max(4, ceil((end - a0) / 5.0).toInt())
            val xs = ArrayList<Double>(); val ys = ArrayList<Double>()
            for (i in 0..steps) {
                val a = Math.toRadians(a0 + (end - a0) * i / steps)
                xs.add(cx + r * cos(a)); ys.add(cy + r * sin(a))
            }
            addShape(xs, ys, false, color)
        }

        fun finish(e: Ent?) {
            if (e == null) return
            when (e.type) {
                "SECTION" -> section = e.s(2) ?: ""
                "ENDSEC" -> section = ""
                "LAYER" -> if (section == "TABLES") { e.s(2)?.let { layerColor[it] = abs(e.i(62, 7)) } }
                else -> if (section == "ENTITIES") when (e.type) {
                    "LINE" -> addShape(listOf(e.d(10), e.d(11)), listOf(e.d(20), e.d(21)), false, colorOf(e))
                    "LWPOLYLINE" -> addShape(e.all(10), e.all(20), (e.i(70) and 1) == 1, colorOf(e))
                    "POLYLINE" -> { polyHead = e; polyX.clear(); polyY.clear() }
                    "VERTEX" -> if (polyHead != null) { polyX.add(e.d(10)); polyY.add(e.d(20)) }
                    "SEQEND" -> { polyHead?.let { addShape(polyX, polyY, (it.i(70) and 1) == 1, colorOf(it)) }; polyHead = null }
                    "CIRCLE" -> arc(e.d(10), e.d(20), e.d(40), 0.0, 360.0, colorOf(e))
                    "ARC" -> arc(e.d(10), e.d(20), e.d(40), e.d(50), e.d(51), colorOf(e))
                    "POINT" -> project(e.d(10), e.d(20))?.let { pts.add(Pnt(it[0], it[1], colorOf(e))) }
                    "TEXT", "MTEXT" -> {
                        val t = clean(e.s(1) ?: "")
                        if (t.isNotBlank()) project(e.d(10), e.d(20))?.let { labels.add(Label(it[0], it[1], t, colorOf(e))) }
                    }
                }
            }
        }

        var cur: Ent? = null
        BufferedReader(InputStreamReader(f.inputStream(), Charsets.ISO_8859_1), 1 shl 16).use { r ->
            while (true) {
                val cl = r.readLine() ?: break
                val vl = r.readLine() ?: break
                val code = cl.trim().toIntOrNull() ?: continue
                if (code == 0) { finish(cur); cur = Ent(vl.trim()) }
                else cur?.let { it.c.add(code); it.v.add(vl.trim()) }
            }
            finish(cur)
        }
        if (shapes.isEmpty() && pts.isEmpty() && labels.isEmpty())
            throw IOException("No drawable geometry found (supports LINE, POLYLINE, LWPOLYLINE, ARC, CIRCLE, POINT, TEXT) or the projection does not match the DXF coordinates")

        var mnLa = 90.0; var mnLo = 180.0; var mxLa = -90.0; var mxLo = -180.0
        fun ext(la: Double, lo: Double) { mnLa = min(mnLa, la); mxLa = max(mxLa, la); mnLo = min(mnLo, lo); mxLo = max(mxLo, lo) }
        shapes.forEach { ext(it.minLat, it.minLon); ext(it.maxLat, it.maxLon) }
        pts.forEach { ext(it.lat, it.lon) }
        labels.forEach { ext(it.lat, it.lon) }
        return Drawing(f.nameWithoutExtension, spec, shapes, pts, labels, doubleArrayOf(mnLa, mnLo, mxLa, mxLo))
    }
}

class DxfOverlay(private val density: Float) : Overlay() {
    private val line = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 1.5f * density; strokeJoin = Paint.Join.ROUND }
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply { textSize = 11f * density }
    private val g = GeoPoint(0.0, 0.0); private val px = Point(); private val path = Path()

    override fun draw(c: Canvas, map: MapView, shadow: Boolean) {
        if (shadow || DxfStore.layers.isEmpty()) return
        val bb = map.boundingBox; val proj = map.projection
        for (d in DxfStore.layers) {
            if (!d.visible) continue
            for (s in d.shapes) {
                if (s.maxLat < bb.latSouth || s.minLat > bb.latNorth || s.maxLon < bb.lonWest || s.minLon > bb.lonEast) continue
                path.rewind()
                for (i in s.lat.indices) {
                    g.latitude = s.lat[i]; g.longitude = s.lon[i]; proj.toPixels(g, px)
                    if (i == 0) path.moveTo(px.x.toFloat(), px.y.toFloat()) else path.lineTo(px.x.toFloat(), px.y.toFloat())
                }
                if (s.closed) path.close()
                line.color = s.color; c.drawPath(path, line)
            }
            for (p in d.pts) {
                g.latitude = p.lat; g.longitude = p.lon; proj.toPixels(g, px)
                fill.color = p.color; c.drawCircle(px.x.toFloat(), px.y.toFloat(), 3f * density, fill)
            }
            if (map.zoomLevelDouble >= 18.0) for (t in d.labels) {
                g.latitude = t.lat; g.longitude = t.lon; proj.toPixels(g, px)
                text.color = t.color; c.drawText(t.text, px.x + 4f * density, px.y.toFloat(), text)
            }
        }
    }
}
