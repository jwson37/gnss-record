package com.example.gnsslogger

import android.app.PendingIntent
import android.bluetooth.*
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
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.Semaphore
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLSocketFactory
import kotlin.math.*

/** Recording session: lives outside the activities so it keeps running while you switch screens. */
object Session {
    class Pt(val lat: Double, val lon: Double, val q: Int, val marked: Boolean, val sess: Int = 0, val avg: Boolean = false)
    class Item(val key: String, val label: String)
    private class Cfg(val dev: String, val baud: Int, val host: String, val port: Int,
                      val mount: String, val user: String, val pass: String, val tls: Boolean, val crs: String)
    private interface Link { fun read(buf: ByteArray): Int; fun write(b: ByteArray, n: Int); fun close() }

    private val SPP: UUID = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB")
    private const val USB_PERM = "com.example.gnsslogger.USB_PERMISSION"
    private val FIX = mapOf(0 to "none", 1 to "GPS", 2 to "DGPS", 3 to "PPS", 4 to "RTK fixed",
        5 to "RTK float", 6 to "dead reckoning", 7 to "manual", 8 to "simulated")

    val points = ArrayList<Pt>()               // points drawn on the map (guard with synchronized)
    @Volatile var running = false            // connected / connecting to the receiver
    @Volatile var recording = false          // logging points to CSV and the map
    @Volatile var linkState = 0              // 0 off, 1 connecting, 2 connected
    @Volatile var linkType = ""              // USB / BT / BLE
    @Volatile var fixQ = 0                   // latest GGA fix quality
    @Volatile var note = ""                  // result of the last recording
    @Volatile private var cfg: Cfg? = null
    private val recLock = Any()
    private var lw: BufferedWriter? = null
    private var mw: BufferedWriter? = null
    @Volatile var message = "Tap an icon above to connect, then REC to record"
    @Volatile var fixName = "no fix"; @Volatile var sats = "-"; @Volatile var hdop = "-"
    @Volatile var latS = "-"; @Volatile var lonS = "-"; @Volatile var heightS = "-"
    @Volatile var lastLat = Double.NaN; @Volatile var lastLon = Double.NaN
    @Volatile var crsS = ""; @Volatile var eS = "-"; @Volatile var nS = "-"
    @Volatile var groundS = ""
    @Volatile var ntripStatus = "idle"; @Volatile var count = 0
    @Volatile var onUpdate: (() -> Unit)? = null
    @Volatile private var rtcmBytes = 0L
    @Volatile private var link: Link? = null
    @Volatile private var lastGga: String? = null
    @Volatile private var pendingMark: String? = null
    @Volatile private var rmcDate: String? = null
    private var ntripSock: Socket? = null
    private var markCount = 0
    // per-recording-period state
    @Volatile private var sessNo = 0
    private var sessCounter = 0
    private val fixes = ArrayList<DoubleArray>()   // lat, lon, height, weight, fix quality
    private var outDir: File? = null
    private var logName = ""
    private var sessCrs = "EPSG:4326"
    private var sessStartMs = 0L
    @Volatile private var statThresh = 1.0
    @Volatile private var sessPole = 0.0   // sensor height above ground, metres

    fun listDevices(ctx: Context): List<Item> {
        val usb = ctx.getSystemService(Context.USB_SERVICE) as UsbManager
        val l = ArrayList<Item>()
        usb.deviceList.values.forEach {
            l.add(Item("usb:${it.vendorId}:${it.productId}",
                "USB ${it.productName ?: "device"} (%04x:%04x)".format(it.vendorId, it.productId)))
        }
        BluetoothAdapter.getDefaultAdapter()?.bondedDevices?.forEach {
            if (it.type != BluetoothDevice.DEVICE_TYPE_LE) l.add(Item("bt:${it.address}", "BT ${it.name} (${it.address})"))
            if (it.type == BluetoothDevice.DEVICE_TYPE_LE || it.type == BluetoothDevice.DEVICE_TYPE_DUAL)
                l.add(Item("ble:${it.address}", "BLE ${it.name} (${it.address}) paired"))
        }
        return l
    }

    // Sensor (pole) height input: entered in m, ft or US ft; used in metres.
    val UNIT_NAMES = listOf("m", "ft", "ftUS")
    private val UNIT_FACTORS = doubleArrayOf(1.0, 0.3048, 1200.0 / 3937.0)
    fun poleMeters(p: SharedPreferences): Double =
        (p.getString("ah", "0")?.toDoubleOrNull() ?: 0.0) * UNIT_FACTORS[p.getInt("ahu", 0).coerceIn(0, 2)]
    fun poleLabel(p: SharedPreferences): String =
        "${p.getString("ah", "0")?.ifBlank { "0" }} ${UNIT_NAMES[p.getInt("ahu", 0).coerceIn(0, 2)]}"

    fun mark() { pendingMark = "P${++markCount}" }

    private const val HEADER = "utc_time,lat_wgs84_deg,lon_wgs84_deg,ellipsoidal_height_m,msl_altitude_m,geoid_sep_m,fix_quality,fix_name,satellites,hdop,diff_age_s,diff_station,crs,easting,northing,sensor_height_m,ground_ellipsoidal_m,ground_msl_m"

    fun refreshPole(ctx: Context) {
        if (!recording) sessPole = poleMeters(ctx.applicationContext.getSharedPreferences("cfg", Context.MODE_PRIVATE))
    }

    /** Connect to the receiver and the NTRIP caster. Nothing is recorded until startRecording(). */
    fun connect(ctx: Context) {
        if (running) return
        val app = ctx.applicationContext
        val p = app.getSharedPreferences("cfg", Context.MODE_PRIVATE)
        val dev = p.getString("dev", null)
        if (dev == null) { message = "Choose a receiver first (tap an icon above)"; onUpdate?.invoke(); return }
        val c = Cfg(dev, p.getString("baud", "115200")!!.toIntOrNull() ?: 115200,
            p.getString("host", "")!!, p.getString("port", "2101")!!.toIntOrNull() ?: 2101,
            p.getString("mount", "")!!, p.getString("user", "")!!, p.getString("pass", "")!!,
            p.getBoolean("tls", false), p.getString("crs", "EPSG:4326")!!)
        cfg = c
        sessPole = poleMeters(p); groundS = ""
        rtcmBytes = 0; fixName = "no fix"; fixQ = 0; sats = "-"; hdop = "-"
        lastLat = Double.NaN; lastLon = Double.NaN
        linkType = when { dev.startsWith("usb:") -> "USB"; dev.startsWith("ble:") -> "BLE"; else -> "BT" }
        linkState = 1; message = "Connecting..."; running = true
        Thread { gnssLoop(app, c) }.start()
        Thread { ntripLoop(c) }.start()
        onUpdate?.invoke()
    }

    /** Start a new recording period (connects first if needed). */
    fun startRecording(ctx: Context) {
        val app = ctx.applicationContext
        if (!running) connect(app)
        if (!running || recording) return
        val c = cfg ?: return
        val p = app.getSharedPreferences("cfg", Context.MODE_PRIVATE)
        val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC") }.format(Date())
        val dir = app.getExternalFilesDir(null)!!
        val logFile = File(dir, "gnss_log_$stamp.csv")
        val marksFile = File(dir, "gnss_log_${stamp}_points.csv")
        sessNo = sessCounter++; synchronized(fixes) { fixes.clear() }
        outDir = dir; logName = logFile.name; sessCrs = c.crs; sessStartMs = System.currentTimeMillis()
        statThresh = p.getString("stat", "1.0")!!.toDoubleOrNull() ?: 1.0
        sessPole = poleMeters(p)
        count = 0; markCount = 0; pendingMark = null; note = ""
        try {
            synchronized(recLock) {
                lw = BufferedWriter(FileWriter(logFile)).also { it.write(HEADER + "\n") }
                mw = BufferedWriter(FileWriter(marksFile)).also { it.write("name,$HEADER\n") }
                recording = true
            }
        } catch (e: Exception) { note = "Cannot create log file: ${e.message}" }
        onUpdate?.invoke()
    }

    private fun endRecording(): String {
        if (!recording) return ""
        synchronized(recLock) {
            recording = false
            try { lw?.close() } catch (_: Exception) {}
            try { mw?.close() } catch (_: Exception) {}
            lw = null; mw = null
        }
        return finishAverage()
    }

    @Synchronized fun stopRecording() {
        if (!recording) return
        val tail = endRecording()
        note = "Recording stopped - $count points saved$tail"
        onUpdate?.invoke()
    }

    /** Disconnect from the receiver and the caster (also ends any recording). */
    @Synchronized fun stop(reason: String? = null) {
        val wasRunning = running
        val wasRec = recording
        val tail = endRecording()
        running = false
        try { link?.close() } catch (_: Exception) {}
        link = null
        try { ntripSock?.close() } catch (_: Exception) {}
        ntripStatus = "idle"; linkState = 0; fixQ = 0; fixName = "no fix"; sats = "-"
        if (wasRec) note = "Recording stopped - $count points saved$tail"
        message = reason ?: if (wasRunning) "Disconnected" else message
        onUpdate?.invoke()
    }

    private fun addFix(lat: Double, lon: Double, h: Double, q: Int, hdop: Double, msl: Double) {
        val qw = when (q) { 4 -> 1.0; 5 -> 0.25; 2 -> 0.05; else -> 0.02 }   // RTK fixed counts most
        val d = max(hdop, 0.5)
        synchronized(fixes) { fixes.add(doubleArrayOf(lat, lon, h, qw / (d * d), q.toDouble(), msl)) }
    }

    /** At the end of a recording: if the antenna stayed put, save a weighted average and draw it as a plus sign. */
    private fun finishAverage(): String {
        val fx = synchronized(fixes) { ArrayList(fixes).also { fixes.clear() } }
        val n = fx.size
        if (n < 5) return ""
        var sw = 0.0; var sLat = 0.0; var sLon = 0.0; var sH = 0.0; var swH = 0.0; var sM = 0.0; var swM = 0.0
        for (f in fx) {
            sw += f[3]; sLat += f[3] * f[0]; sLon += f[3] * f[1]
            if (!f[2].isNaN()) { sH += f[3] * f[2]; swH += f[3] }
            if (!f[5].isNaN()) { sM += f[3] * f[5]; swM += f[3] }
        }
        val mLat = sLat / sw; val mLon = sLon / sw; val mH = if (swH > 0) sH / swH else Double.NaN
        val mM = if (swM > 0) sM / swM else Double.NaN
        val kN = 111320.0; val kE = 111320.0 * cos(Math.toRadians(mLat))
        val dist = DoubleArray(n); var vE = 0.0; var vN = 0.0; var vH = 0.0; var fixed = 0
        for ((i, f) in fx.withIndex()) {
            val dn = (f[0] - mLat) * kN; val de = (f[1] - mLon) * kE
            dist[i] = sqrt(dn * dn + de * de)
            vE += f[3] * de * de; vN += f[3] * dn * dn
            if (!f[2].isNaN()) vH += f[3] * (f[2] - mH) * (f[2] - mH)
            if (f[4] == 4.0) fixed++
        }
        dist.sort()
        val p95 = dist[min(n - 1, (n * 0.95).toInt())]
        if (p95 > statThresh) return "\nMoved too much to average (95%% radius %.2f m > %.2f m)".format(Locale.US, p95, statThresh)

        val sdH = sqrt((vE + vN) / sw); val sdV = if (swH > 0) sqrt(vH / swH) else Double.NaN
        val iso = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC") }
        val en = try { Proj.forward(sessCrs, mLat, mLon) } catch (_: Exception) { null }
        val fm = if (en != null && (abs(en.e) > 180 || abs(en.n) > 90)) "%.4f" else "%.9f"
        val crsCols = if (en != null) "${en.spec},${String.format(Locale.US, fm, en.e)},${String.format(Locale.US, fm, en.n)}" else ",,"
        val hCols = String.format(Locale.US, "%.4f", sessPole) + "," +
            (if (mH.isNaN()) "" else String.format(Locale.US, "%.4f", mH - sessPole)) + "," +
            (if (mM.isNaN()) "" else String.format(Locale.US, "%.4f", mM - sessPole))
        val row = String.format(Locale.US, "%s,%s,%d,%d,%.9f,%.9f,%s,%.4f,%s,%.1f,%.4f,%s,%s,%s",
            iso.format(Date(sessStartMs)), iso.format(Date()), n, (System.currentTimeMillis() - sessStartMs) / 1000,
            mLat, mLon, if (mH.isNaN()) "" else String.format(Locale.US, "%.4f", mH), sdH,
            if (sdV.isNaN()) "" else String.format(Locale.US, "%.4f", sdV), 100.0 * fixed / n, p95, crsCols, hCols, logName)
        outDir?.let { dir ->
            try {
                val af = File(dir, "gnss_averages.csv")
                val isNew = !af.exists()
                FileWriter(af, true).use { w ->
                    if (isNew) w.write("start_utc,end_utc,n_fixes,duration_s,lat_wgs84_deg,lon_wgs84_deg,ellipsoidal_height_m,sd_horiz_m,sd_vert_m,pct_rtk_fixed,radius95_m,crs,easting,northing,sensor_height_m,ground_ellipsoidal_m,ground_msl_m,log_file\n")
                    w.write(row + "\n")
                }
            } catch (_: Exception) {}
        }
        synchronized(points) { points.add(Pt(mLat, mLon, 4, false, sessNo, true)) }
        return "\nAverage of $n fixes saved (sd %.1f cm)".format(Locale.US, sdH * 100)
    }

    // ---- Bluetooth LE (UART-over-GATT) -------------------------------------------------
    private val NUS = UUID.fromString("6e400001-b5a3-f393-e0a9-e50e24dcca9e")      // Nordic UART service
    private val NUS_RX = UUID.fromString("6e400002-b5a3-f393-e0a9-e50e24dcca9e")   // phone writes here
    private val NUS_TX = UUID.fromString("6e400003-b5a3-f393-e0a9-e50e24dcca9e")   // phone receives notifications here
    private val CCCD = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
    private fun u16(s: String) = UUID.fromString("0000$s-0000-1000-8000-00805f9b34fb")

    /** Returns (notify characteristic, write characteristic) for the first UART-like service found. */
    private fun pickChars(g: BluetoothGatt): Pair<BluetoothGattCharacteristic, BluetoothGattCharacteristic>? {
        g.getService(NUS)?.let { s ->
            val tx = s.getCharacteristic(NUS_TX); val rx = s.getCharacteristic(NUS_RX)
            if (tx != null && rx != null) return tx to rx
        }
        val single = listOf(u16("ffe0") to u16("ffe1"),   // HM-10 style modules
            UUID.fromString("2456e1b9-26e2-8f83-e744-f34f01e9d701") to UUID.fromString("2456e1b9-26e2-8f83-e744-f34f01e9d703")) // u-blox SPS
        for ((svc, ch) in single) g.getService(svc)?.getCharacteristic(ch)?.let { return it to it }
        var notif: BluetoothGattCharacteristic? = null; var wr: BluetoothGattCharacteristic? = null
        val nMask = BluetoothGattCharacteristic.PROPERTY_NOTIFY or BluetoothGattCharacteristic.PROPERTY_INDICATE
        val wMask = BluetoothGattCharacteristic.PROPERTY_WRITE or BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE
        for (s in g.services) {
            val id = s.uuid.toString()
            if (id.startsWith("00001800") || id.startsWith("00001801")) continue
            for (c in s.characteristics) {
                if (notif == null && (c.properties and nMask) != 0) notif = c
                if (wr == null && (c.properties and wMask) != 0) wr = c
            }
        }
        return if (notif != null && wr != null) notif to wr else null
    }

    private fun openBle(app: Context, addr: String): Link {
        val bt = BluetoothAdapter.getDefaultAdapter() ?: throw IOException("No Bluetooth")
        val q = LinkedBlockingQueue<ByteArray>()     // incoming notifications; empty array = disconnected
        val writeSem = Semaphore(0)
        val ready = CountDownLatch(1)
        val closed = AtomicBoolean(false); val mtu = AtomicInteger(23)
        val chars = arrayOfNulls<BluetoothGattCharacteristic>(2)   // [0] notify, [1] write
        val fail = arrayOfNulls<String>(1)
        val cb = object : BluetoothGattCallback() {
            override fun onConnectionStateChange(g: BluetoothGatt, status: Int, state: Int) {
                if (state == BluetoothProfile.STATE_CONNECTED && status == BluetoothGatt.GATT_SUCCESS) {
                    if (!g.requestMtu(247)) g.discoverServices()
                } else {
                    closed.set(true); if (fail[0] == null) fail[0] = "BLE connection failed or lost (status $status)"
                    q.offer(ByteArray(0)); ready.countDown()
                }
            }
            override fun onMtuChanged(g: BluetoothGatt, m: Int, status: Int) {
                if (status == BluetoothGatt.GATT_SUCCESS) mtu.set(m)
                g.discoverServices()
            }
            override fun onServicesDiscovered(g: BluetoothGatt, status: Int) {
                val pair = if (status == BluetoothGatt.GATT_SUCCESS) pickChars(g) else null
                if (pair == null) { fail[0] = "BLE: no serial (UART) service found on this device"; ready.countDown(); return }
                chars[0] = pair.first; chars[1] = pair.second
                g.setCharacteristicNotification(pair.first, true)
                val d = pair.first.getDescriptor(CCCD)
                if (d == null) { ready.countDown(); return }
                d.setValue(if ((pair.first.properties and BluetoothGattCharacteristic.PROPERTY_NOTIFY) != 0)
                    BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE else BluetoothGattDescriptor.ENABLE_INDICATION_VALUE)
                if (!g.writeDescriptor(d)) ready.countDown()
            }
            override fun onDescriptorWrite(g: BluetoothGatt, d: BluetoothGattDescriptor, status: Int) { ready.countDown() }
            override fun onCharacteristicChanged(g: BluetoothGatt, c: BluetoothGattCharacteristic) {
                c.getValue()?.let { q.offer(it.copyOf()) }
            }
            override fun onCharacteristicWrite(g: BluetoothGatt, c: BluetoothGattCharacteristic, status: Int) { writeSem.release() }
        }
        val gatt = bt.getRemoteDevice(addr).connectGatt(app, false, cb, BluetoothDevice.TRANSPORT_LE)
        if (!ready.await(20, TimeUnit.SECONDS) || chars[1] == null) {
            try { gatt.disconnect(); gatt.close() } catch (_: Exception) {}
            throw IOException(fail[0] ?: "BLE: connection timed out")
        }
        val wr = chars[1]!!
        val wtype = if ((wr.properties and BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE) != 0)
            BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE else BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
        var left = ByteArray(0)
        return object : Link {
            override fun read(buf: ByteArray): Int {
                if (left.isEmpty()) {
                    val d = q.poll(1000, TimeUnit.MILLISECONDS) ?: return if (closed.get()) -1 else 0
                    if (d.isEmpty()) return -1
                    left = d
                }
                val n = min(buf.size, left.size)
                System.arraycopy(left, 0, buf, 0, n)
                left = left.copyOfRange(n, left.size)
                return n
            }
            override fun write(b: ByteArray, n: Int) {
                var off = 0; var tries = 0
                val chunk = max(20, mtu.get() - 3)
                while (off < n) {
                    if (closed.get()) throw IOException("BLE connection lost")
                    val len = min(chunk, n - off)
                    wr.setValue(b.copyOfRange(off, off + len)); wr.writeType = wtype
                    writeSem.drainPermits()
                    if (!gatt.writeCharacteristic(wr)) {
                        if (++tries > 100) throw IOException("BLE write failed")
                        Thread.sleep(15); continue
                    }
                    tries = 0
                    writeSem.tryAcquire(1500, TimeUnit.MILLISECONDS)
                    off += len
                }
            }
            override fun close() { closed.set(true); try { gatt.disconnect(); gatt.close() } catch (_: Exception) {} }
        }
    }

    private fun openLink(app: Context, c: Cfg): Link {
        if (c.dev.startsWith("ble:")) return openBle(app, c.dev.substring(4))
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

    private fun gnssLoop(app: Context, c: Cfg) {
        val dayFmt = SimpleDateFormat("yyyy-MM-dd", Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC") }
        var err: String? = null
        try {
            val l = openLink(app, c); link = l
            linkState = 2; message = "Connected - waiting for fix"; onUpdate?.invoke()

            fun process(line: String) {
                if (!checksumOk(line)) return
                val f = line.substring(1, line.lastIndexOf('*')).split(",")
                val kind = f[0].takeLast(3)
                if (kind == "RMC" && f.size > 9 && f[9].length == 6) {
                    val d = f[9]; rmcDate = "20${d.substring(4, 6)}-${d.substring(2, 4)}-${d.substring(0, 2)}"
                } else if (kind == "GGA" && f.size > 14) {
                    lastGga = line + "\r\n"
                    val q = f[6].toIntOrNull() ?: 0
                    sats = f[7]; hdop = f[8]; fixName = FIX[q] ?: q.toString(); fixQ = q
                    if (q == 0 || f[2].isEmpty() || f[4].isEmpty() || f[1].length < 6) { onUpdate?.invoke(); return }
                    val lat = dmToDeg(f[2], f[3]); val lon = dmToDeg(f[4], f[5])
                    val msl = f[9].toDoubleOrNull(); val sep = f[11].toDoubleOrNull()
                    val ell = if (msl != null && sep != null) String.format(Locale.US, "%.4f", msl + sep) else ""
                    val t = f[1]
                    val ts = "${rmcDate ?: dayFmt.format(Date())}T${t.substring(0, 2)}:${t.substring(2, 4)}:${t.substring(4)}Z"
                    val en = try { Proj.forward(c.crs, lat, lon) } catch (_: Exception) { null }
                    val fm = if (en != null && (abs(en.e) > 180 || abs(en.n) > 90)) "%.4f" else "%.9f"
                    val crsCols = if (en != null) "${en.spec},${String.format(Locale.US, fm, en.e)},${String.format(Locale.US, fm, en.n)}" else ",,"
                    val gEll = if (msl != null && sep != null) String.format(Locale.US, "%.4f", msl + sep - sessPole) else ""
                    val gMsl = if (msl != null) String.format(Locale.US, "%.4f", msl - sessPole) else ""
                    val hCols = String.format(Locale.US, "%.4f", sessPole) + "," + gEll + "," + gMsl
                    val row = "$ts,${"%.9f".format(Locale.US, lat)},${"%.9f".format(Locale.US, lon)},$ell,${f[9]},${f[11]},$q,$fixName,${f[7]},${f[8]},${f[13]},${f[14]},$crsCols,$hCols"
                    // live status (always)
                    latS = "%.9f".format(Locale.US, lat); lonS = "%.9f".format(Locale.US, lon); heightS = ell
                    groundS = if (sessPole != 0.0) gEll else ""
                    lastLat = lat; lastLon = lon
                    if (en != null) { crsS = en.spec; eS = String.format(Locale.US, fm, en.e); nS = String.format(Locale.US, fm, en.n) } else crsS = ""
                    // recording (only between REC and STOP)
                    if (recording) synchronized(recLock) {
                        val w = lw
                        if (recording && w != null) {
                            w.write(row + "\n"); w.flush(); count++
                            addFix(lat, lon, if (msl != null && sep != null) msl + sep else Double.NaN, q, f[8].toDoubleOrNull() ?: 1.0, msl ?: Double.NaN)
                            val mk = pendingMark
                            if (mk != null) { mw?.write("$mk,$row\n"); mw?.flush(); pendingMark = null }
                            val last = points.lastOrNull()
                            if (mk != null || last == null || last.sess != sessNo || last.q != q || abs(last.lat - lat) + abs(last.lon - lon) > 1e-7)
                                synchronized(points) { points.add(Pt(lat, lon, q, mk != null, sessNo)) }
                        }
                    }
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
            if (running) err = "Receiver connection lost"
        } catch (e: Exception) {
            if (running) err = "GNSS error: ${e.message}"
        } finally { stop(err) }
    }

    private fun ntripLoop(c: Cfg) {
        if (c.host.isBlank() || c.mount.isBlank()) { ntripStatus = "not configured"; onUpdate?.invoke(); return }
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
