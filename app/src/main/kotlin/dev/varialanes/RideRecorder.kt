package dev.varialanes

import android.content.Context
import android.os.SystemClock
import android.util.Log
import java.io.BufferedWriter
import java.io.File
import java.io.FileWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * Records every raw radar packet and Bluetooth event to a CSV file so a ride can be replayed and
 * analysed later. Files live in the app's external files folder:
 *   /sdcard/Android/data/dev.varialanes/files/rides/varia-YYYYMMDD-HHMMSS.csv
 * (pull with: adb pull /sdcard/Android/data/dev.varialanes/files/rides)
 *
 * Line format: <ms since boot>,<type>,<data>
 *   PKT  = raw radar notification as hex
 *   EVT  = Bluetooth / connection event text
 * A new file starts with the first data after recording has been idle for 2 minutes.
 */
object RideRecorder {
    private const val TAG = "VariaLanes"
    private const val IDLE_CLOSE_MS = 120_000L
    private const val MAX_FILE_BYTES = 40L * 1024 * 1024
    private const val KEEP_FILES = 20

    private val io = Executors.newSingleThreadScheduledExecutor()
    private var dir: File? = null
    private var writer: BufferedWriter? = null
    private var currentFile: File? = null
    private var bytesWritten = 0L
    private var lastWriteMs = 0L

    @Volatile var enabled = true
    @Volatile var packetsInFile = 0L
        private set

    @Synchronized
    fun init(context: Context) {
        if (dir != null) return
        dir = File(context.applicationContext.getExternalFilesDir(null) ?: context.applicationContext.filesDir, "rides")
            .apply { mkdirs() }
        enabled = Settings.load(context).recordRides
        io.scheduleWithFixedDelay({ flushOrClose() }, 2, 2, TimeUnit.SECONDS)
    }

    fun packet(bytes: ByteArray) {
        if (!enabled) return
        val now = SystemClock.elapsedRealtime()
        val hex = bytes.joinToString("") { "%02x".format(it) }
        io.execute {
            write("$now,PKT,$hex")
            packetsInFile++
        }
    }

    fun event(text: String) {
        if (!enabled) return
        val now = SystemClock.elapsedRealtime()
        val clean = text.replace(',', ';').replace('\n', ' ')
        io.execute { write("$now,EVT,$clean") }
    }

    fun folder(): File? = dir

    fun files(): List<File> = dir?.listFiles { f -> f.name.endsWith(".csv") }?.sortedByDescending { it.name } ?: emptyList()

    fun currentFileName(): String? = currentFile?.name

    // ---- runs on the io thread ----

    private fun write(line: String) {
        try {
            val w = writer ?: open() ?: return
            w.write(line)
            w.write("\n")
            bytesWritten += line.length + 1
            lastWriteMs = SystemClock.elapsedRealtime()
            if (bytesWritten > MAX_FILE_BYTES) close()
        } catch (e: Exception) {
            Log.w(TAG, "Recorder write failed", e)
            close()
        }
    }

    private fun open(): BufferedWriter? {
        val d = dir ?: return null
        val stamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.ROOT).format(Date())
        val f = File(d, "varia-$stamp.csv")
        return try {
            val w = BufferedWriter(FileWriter(f, true), 64 * 1024)
            val wall = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssZ", Locale.ROOT).format(Date())
            w.write("# Varia Lanes ride recording; started $wall; boot ms ${SystemClock.elapsedRealtime()}\n")
            w.write("# ms_since_boot,type,data  (PKT = radar notification hex, EVT = event)\n")
            writer = w
            currentFile = f
            bytesWritten = 0
            packetsInFile = 0
            prune(d)
            w
        } catch (e: Exception) {
            Log.w(TAG, "Recorder open failed", e)
            null
        }
    }

    private fun flushOrClose() {
        try {
            val w = writer ?: return
            if (SystemClock.elapsedRealtime() - lastWriteMs > IDLE_CLOSE_MS) close() else w.flush()
        } catch (e: Exception) {
            Log.w(TAG, "Recorder flush failed", e)
        }
    }

    private fun close() {
        runCatching { writer?.flush(); writer?.close() }
        writer = null
        currentFile = null
    }

    private fun prune(d: File) {
        d.listFiles { f -> f.name.endsWith(".csv") }
            ?.sortedByDescending { it.name }
            ?.drop(KEEP_FILES)
            ?.forEach { it.delete() }
    }
}
