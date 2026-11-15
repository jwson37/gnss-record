package com.example.gnsslogger

import android.app.PendingIntent
import android.bluetooth.BluetoothAdapter
import android.content.*
import android.hardware.usb.UsbManager
import android.util.Base64
import com.hoho.android.usbserial.driver.CdcAcmSerialDriver
import com.hoho.android.usbserial.driver.UsbSerialPort
import com.hoho.android.usbserial.driver.UsbSerialProber
import java.io.*
import java.net.Socket
import java.net.SocketTimeoutException
import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLSocketFactory
import kotlin.math.abs

/** Recording session: lives outside the activities so it keeps running while you switch screens. */
object Session {
    class Pt(val lat: Double, val lon: Double, val q: Int, val marked: Boolean)
    class Item(val key: String, val label: String)
    private class Cfg(val dev: String, val baud: Int, val host: String, val port: Int,
                      val mount: String, val user: String, val pass: String, val tls: Boolean, val crs: String)
    private interface Link { fun read(buf: ByteArray): Int; fun write(b: ByteArray, n: Int); fun close() }

    private val SPP: UUID = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB")
    private const val USB_PERM = "com.example.gnsslogger.USB_PERMISSION"
    private val FIX = mapOf(0 to "none", 1 to "GPS", 2 to "DGPS", 3 to "PPS", 4 to "RTK fixed",
        5 to "RTK float", 6 to "dead reckoning", 7 to "manual", 8 to "simulated")

    val points = ArrayList<Pt>()               // points drawn on the map (guard with synchronized)
    @Volatile var running = false
    @Volatile var message = "Tap REC to connect and start recording"
    @Volatile var fixName = "no fix"; @Volatile var sats = "-"; @Volatile var hdop = "-"
    @Volatile var latS = "-"; @Volatile var lonS = "-"; @Volatile var heightS = "-"
    @Volatile var lastLat = Double.NaN; @Volatile var lastLon = Double.NaN
    @Volatile var crsS = ""; @Volatile var eS = "-"; @Volatile var nS = "-"
    @Volatile var ntripStatus = "idle"; @Volatile var count = 0
    @Volatile var onUpdate: (() -> Unit)? = null
    @Volatile private var rtcmBytes = 0L
    @Volatile private var link: Link? = null
    @Volatile private var lastGga: String? = null
    @Volatile private var pendingMark: String? = null
    @Volatile private var rmcDate: String? = null
    private var ntripSock: Socket? = null
    private var markCount = 0

    fun listDevices(ctx: Context): List<Item> {
        val usb = ctx.getSystemService(Context.USB_SERVICE) as UsbManager
        val l = ArrayList<Item>()
        usb.deviceList.values.forEach {
            l.add(Item("usb:${it.vendorId}:${it.productId}",
                "USB ${it.productName ?: "device"} (%04x:%04x)".format(it.vendorId, it.productId)))
        }
        BluetoothAdapter.getDefaultAdapter()?.bondedDevices?.forEach {
            l.add(Item("bt:${it.address}", "BT ${it.name} (${it.address})"))
        }
        return l
    }

    fun mark() { pendingMark = "P${++markCount}" }

    fun start(ctx: Context) {
        if (running) return
        val app = ctx.applicationContext
        val p = app.getSharedPreferences("cfg", Context.MODE_PRIVATE)
        val dev = p.getString("dev", null)
        if (dev == null) { message = "Choose a receiver in Settings (gear icon)"; onUpdate?.invoke(); return }
        val c = Cfg(dev, p.getString("baud", "115200")!!.toIntOrNull() ?: 115200,
            p.getString("host", "")!!, p.getString("port", "2101")!!.toIntOrNull() ?: 2101,
            p.getString("mount", "")!!, p.getString("user", "")!!, p.getString("pass", "")!!,
            p.getBoolean("tls", false), p.getString("crs", "EPSG:4326")!!)
        val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC") }.format(Date())
        val dir = app.getExternalFilesDir(null)!!
        val logFile = File(dir, "gnss_log_$stamp.csv")
        val marksFile = File(dir, "gnss_log_${stamp}_points.csv")
        synchronized(points) { points.clear() }
        count = 0; rtcmBytes = 0; markCount = 0; pendingMark = null
        fixName = "no fix"; lastLat = Double.NaN; lastLon = Double.NaN
        message = "Connecting..."; running = true
        Thread { gnssLoop(app, c, logFile, marksFile) }.start()
        Thread { ntripLoop(c) }.start()
        onUpdate?.invoke()
    }

    fun stop(reason: String? = null) {
        val was = running
        running = false
        try { link?.close() } catch (_: Exception) {}
        link = null
        try { ntripSock?.close() } catch (_: Exception) {}
        ntripStatus = "idle"
        message = reason ?: if (was) "Stopped - $count points saved" else message
        onUpdate?.invoke()
    }

    private fun openLink(app: Context, c: Cfg): Link {
        if (c.dev.startsWith("bt:")) {
            val bt = BluetoothAdapter.getDefaultAdapter() ?: throw IOException("No Bluetooth")
            bt.cancelDiscovery()
            val s = bt.getRemoteDevice(c.dev.substring(3)).createRfcommSocketToServiceRecord(SPP)
            s.connect()
            val i = s.inputStream; val o = s.outputStream
            return object : Link {
                override fun read(buf: ByteArray) = i.read(buf)
                override fun write(b: ByteArray, n: Int) { o.write(b, 0, n); o.flush() }
                override fun close() { try { s.close() } catch (_: Exception) {} }
            }
        }
        val usbMgr = app.getSystemService(Context.USB_SERVICE) as UsbManager
        val ids = c.dev.split(":"); val vid = ids[1].toInt(); val pid = ids[2].toInt()
        val dev = usbMgr.deviceList.values.firstOrNull { it.vendorId == vid && it.productId == pid }
            ?: throw IOException("USB receiver not found - replug it")
        if (!usbMgr.hasPermission(dev)) {
            val latch = CountDownLatch(1); val granted = BooleanArray(1)
            val rx = object : BroadcastReceiver() {
                override fun onReceive(ctx: Context, i: Intent) {
                    granted[0] = i.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false); latch.countDown()
                }
            }
            app.registerReceiver(rx, IntentFilter(USB_PERM))
            usbMgr.requestPermission(dev, PendingIntent.getBroadcast(app, 0, Intent(USB_PERM), 0))
            latch.await(30, TimeUnit.SECONDS); app.unregisterReceiver(rx)
            if (!granted[0]) throw IOException("USB permission denied")
        }
        val driver = UsbSerialProber.getDefaultProber().probeDevice(dev) ?: CdcAcmSerialDriver(dev)
        val conn = usbMgr.openDevice(dev) ?: throw IOException("cannot open USB device")
        val sp = driver.ports[0]
        sp.open(conn)
        sp.setParameters(c.baud, 8, UsbSerialPort.STOPBITS_1, UsbSerialPort.PARITY_NONE)
        try { sp.dtr = true } catch (_: Exception) {}
        return object : Link {
            override fun read(buf: ByteArray) = sp.read(buf, 1000)
            override fun write(b: ByteArray, n: Int) { sp.write(b.copyOf(n), 1000) }
            override fun close() { try { sp.close() } catch (_: Exception) {} }
        }
    }

    private fun gnssLoop(app: Context, c: Cfg, logFile: File, marksFile: File) {
        val header = "utc_time,lat_wgs84_deg,lon_wgs84_deg,ellipsoidal_height_m,msl_altitude_m,geoid_sep_m,fix_quality,fix_name,satellites,hdop,diff_age_s,diff_station,crs,easting,northing"
        val dayFmt = SimpleDateFormat("yyyy-MM-dd", Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC") }
        var err: String? = null
        try {
            val l = openLink(app, c); link = l
            message = "Connected - waiting for fix"; onUpdate?.invoke()
            val lw = BufferedWriter(FileWriter(logFile)); lw.write(header + "\n")
            val mw = BufferedWriter(FileWriter(marksFile)); mw.write("name,$header\n")

            fun process(line: String) {
                if (!checksumOk(line)) return
                val f = line.substring(1, line.lastIndexOf('*')).split(",")
                val kind = f[0].takeLast(3)
                if (kind == "RMC" && f.size > 9 && f[9].length == 6) {
                    val d = f[9]; rmcDate = "20${d.substring(4, 6)}-${d.substring(2, 4)}-${d.substring(0, 2)}"
                } else if (kind == "GGA" && f.size > 14) {
                    lastGga = line + "\r\n"
                    val q = f[6].toIntOrNull() ?: 0
                    sats = f[7]; hdop = f[8]; fixName = FIX[q] ?: q.toString()
                    if (q == 0 || f[2].isEmpty() || f[4].isEmpty() || f[1].length < 6) { onUpdate?.invoke(); return }
                    val lat = dmToDeg(f[2], f[3]); val lon = dmToDeg(f[4], f[5])
                    val msl = f[9].toDoubleOrNull(); val sep = f[11].toDoubleOrNull()
                    val ell = if (msl != null && sep != null) String.format(Locale.US, "%.4f", msl + sep) else ""
                    val t = f[1]
                    val ts = "${rmcDate ?: dayFmt.format(Date())}T${t.substring(0, 2)}:${t.substring(2, 4)}:${t.substring(4)}Z"
                    val en = try { Proj.forward(c.crs, lat, lon) } catch (_: Exception) { null }
                    val fm = if (en != null && (abs(en.e) > 180 || abs(en.n) > 90)) "%.4f" else "%.9f"
                    val crsCols = if (en != null) "${en.spec},${String.format(Locale.US, fm, en.e)},${String.format(Locale.US, fm, en.n)}" else ",,"
                    val row = "$ts,${"%.9f".format(Locale.US, lat)},${"%.9f".format(Locale.US, lon)},$ell,${f[9]},${f[11]},$q,$fixName,${f[7]},${f[8]},${f[13]},${f[14]},$crsCols"
                    lw.write(row + "\n"); lw.flush(); count++
                    val mk = pendingMark
                    if (mk != null) { mw.write("$mk,$row\n"); mw.flush(); pendingMark = null }
                    latS = "%.9f".format(Locale.US, lat); lonS = "%.9f".format(Locale.US, lon); heightS = ell
                    lastLat = lat; lastLon = lon
                    if (en != null) { crsS = en.spec; eS = String.format(Locale.US, fm, en.e); nS = String.format(Locale.US, fm, en.n) } else crsS = ""
                    val last = points.lastOrNull()
                    if (mk != null || last == null || last.q != q || abs(last.lat - lat) + abs(last.lon - lon) > 1e-7)
                        synchronized(points) { points.add(Pt(lat, lon, q, mk != null)) }
                    message = ""
                    onUpdate?.invoke()
                }
            }

            val buf = ByteArray(4096); val sb = StringBuilder()
            while (running) {
                val n = l.read(buf)
                if (n < 0) break
                if (n == 0) continue
                sb.append(String(buf, 0, n, Charsets.ISO_8859_1))
                while (true) {
                    val i = sb.indexOf("\n"); if (i < 0) break
                    val line = sb.substring(0, i).trim(); sb.delete(0, i + 1)
                    process(line)
                }
                if (sb.length > 8192) sb.clear()
            }
            lw.close(); mw.close()
        } catch (e: Exception) {
            if (running) err = "GNSS error: ${e.message}"
        } finally { stop(err) }
    }

    private fun ntripLoop(c: Cfg) {
        while (running && link == null) Thread.sleep(200)
        while (running) {
            try {
                ntripStatus = "connecting"; onUpdate?.invoke()
                val s: Socket = if (c.tls) SSLSocketFactory.getDefault().createSocket(c.host, c.port) else Socket(c.host, c.port)
                ntripSock = s
                val auth = Base64.encodeToString("${c.user}:${c.pass}".toByteArray(), Base64.NO_WRAP)
                val req = "GET /${c.mount} HTTP/1.1\r\nHost: ${c.host}\r\nNtrip-Version: Ntrip/2.0\r\n" +
                    "User-Agent: NTRIP GnssLogger/1.0\r\nAuthorization: Basic $auth\r\nConnection: close\r\n\r\n"
                s.getOutputStream().apply { write(req.toByteArray()); flush() }
                val inp = s.getInputStream()
                val hdr = StringBuilder()
                while (!hdr.endsWith("\r\n\r\n") && hdr.length < 4096) {
                    val b = inp.read(); if (b < 0) throw IOException("caster closed connection"); hdr.append(b.toChar())
                }
                val first = hdr.lineSequence().first()
                if (!first.contains("200")) throw IOException("caster replied: $first")
                ntripStatus = "streaming"
                s.soTimeout = 1000
                val buf = ByteArray(4096); var lastSent = 0L
                while (running) {
                    val g = lastGga
                    if (g != null && System.currentTimeMillis() - lastSent > 10000) {
                        s.getOutputStream().apply { write(g.toByteArray()); flush() }; lastSent = System.currentTimeMillis()
                    }
                    val n = try { inp.read(buf) } catch (_: SocketTimeoutException) { continue }
                    if (n < 0) throw IOException("stream ended")
                    link?.write(buf, n)
                    rtcmBytes += n
                }
            } catch (e: Exception) {
                ntripStatus = "error: ${e.message}"
            } finally { try { ntripSock?.close() } catch (_: Exception) {} }
            if (running) Thread.sleep(5000)
        }
    }

    private fun checksumOk(l: String): Boolean {
        if (!l.startsWith("$") || !l.contains('*')) return false
        val star = l.lastIndexOf('*')
        var c = 0; for (i in 1 until star) c = c xor l[i].code
        return l.length >= star + 3 && l.substring(star + 1, star + 3).toIntOrNull(16) == c
    }

    private fun dmToDeg(v: String, hemi: String): Double {
        val d = v.indexOf('.')
        val deg = v.substring(0, d - 2).toDouble() + v.substring(d - 2).toDouble() / 60.0
        return if (hemi == "S" || hemi == "W") -deg else deg
    }
}
