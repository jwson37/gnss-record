package com.example.gnsslogger

import android.content.Context
import org.locationtech.proj4j.CRSFactory
import org.locationtech.proj4j.CoordinateReferenceSystem
import org.locationtech.proj4j.CoordinateTransform
import org.locationtech.proj4j.CoordinateTransformFactory
import org.locationtech.proj4j.ProjCoordinate

/** Coordinate-system handling. A spec is: "EPSG:n", "ESRI:n", a bare WKID number, a "+proj=..." string, or AUTO. */
object Proj {
    const val AUTO = "AUTO_UTM"
    class En(val spec: String, val e: Double, val n: Double)

    private val crsFactory = CRSFactory()
    private val ctf = CoordinateTransformFactory()
    private val wgs84: CoordinateReferenceSystem by lazy { crsFactory.createFromName("EPSG:4326") }
    private val crsCache = HashMap<String, CoordinateReferenceSystem>()
    private val fwd = HashMap<String, CoordinateTransform>()
    private val inv = HashMap<String, CoordinateTransform>()
    @Volatile var spec = "EPSG:4326"

    fun load(ctx: Context) { spec = ctx.getSharedPreferences("cfg", Context.MODE_PRIVATE).getString("crs", "EPSG:4326")!! }

    @Synchronized
    fun crs(s0: String): CoordinateReferenceSystem = crsCache.getOrPut(s0) {
        val s = s0.trim()
        when {
            s.startsWith("+") -> crsFactory.createFromParameters("custom", s)
            s.isNotEmpty() && s.all { it.isDigit() } -> {
                val n = s.toInt()
                val code = when (n) { 102100, 102113, 3785, 900913 -> 3857; else -> n }   // Esri web-mercator aliases
                try { crsFactory.createFromName("EPSG:$code") } catch (_: Exception) { crsFactory.createFromName("ESRI:$code") }
            }
            else -> crsFactory.createFromName(s)
        }
    }

    fun resolve(s: String, lat: Double, lon: Double): String {
        if (s != AUTO) return s
        if (lat.isNaN() || lon.isNaN()) throw IllegalStateException("Auto UTM needs a GNSS fix first - pick a fixed projection or record a point")
        val zone = (Math.floor((lon + 180.0) / 6.0).toInt() + 1).coerceIn(1, 60)
        return "EPSG:${(if (lat >= 0) 32600 else 32700) + zone}"
    }

    /** WGS84 lat/lon -> easting/northing in the given spec (units of that CRS). */
    @Synchronized
    fun forward(s0: String, lat: Double, lon: Double): En {
        val s = resolve(s0, lat, lon)
        val t = fwd.getOrPut(s) { ctf.createTransform(wgs84, crs(s)) }
        val out = ProjCoordinate()
        t.transform(ProjCoordinate(lon, lat), out)
        return En(s, out.x, out.y)
    }

    /** Easting/northing in spec -> [lat, lon] WGS84. */
    @Synchronized
    fun toWgs84(s: String, x: Double, y: Double): DoubleArray {
        val t = inv.getOrPut(s) { ctf.createTransform(crs(s), wgs84) }
        val out = ProjCoordinate()
        t.transform(ProjCoordinate(x, y), out)
        return doubleArrayOf(out.y, out.x)
    }

    fun describe(s: String): String =
        if (s == AUTO) "WGS 1984 / UTM, zone chosen automatically from your position"
        else crs(s).let { "${it.name}\n${it.parameterString}" }
}
