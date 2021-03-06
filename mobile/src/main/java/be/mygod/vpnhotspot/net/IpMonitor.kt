package be.mygod.vpnhotspot.net

import android.util.Log
import be.mygod.vpnhotspot.App.Companion.app
import be.mygod.vpnhotspot.R
import be.mygod.vpnhotspot.util.thread
import java.io.InterruptedIOException
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit

abstract class IpMonitor : Runnable {
    protected abstract val monitoredObject: String
    protected abstract fun processLine(line: String)
    protected abstract fun processLines(lines: Sequence<String>)

    private var monitor: Process? = null
    private var pool: ScheduledExecutorService? = null

    init {
        thread("${javaClass.simpleName}-input") {
            // monitor may get rejected by SELinux
            val monitor = ProcessBuilder("sh", "-c",
                    "ip monitor $monitoredObject || su -c 'ip monitor $monitoredObject'")
                    .redirectErrorStream(true)
                    .start()
            this.monitor = monitor
            thread("${javaClass.simpleName}-error") {
                try {
                    monitor.errorStream.bufferedReader().forEachLine { Log.e(javaClass.simpleName, it) }
                } catch (_: InterruptedIOException) { }
            }
            try {
                monitor.inputStream.bufferedReader().forEachLine(this::processLine)
                monitor.waitFor()
                if (monitor.exitValue() == 0) return@thread
                Log.w(javaClass.simpleName, "Failed to set up monitor, switching to polling")
                val pool = Executors.newScheduledThreadPool(1)
                pool.scheduleAtFixedRate(this, 1, 1, TimeUnit.SECONDS)
                this.pool = pool
            } catch (_: InterruptedIOException) { }
        }
    }

    fun flush() = thread("${javaClass.simpleName}-flush") { run() }

    override fun run() {
        val process = ProcessBuilder("ip", monitoredObject)
                .redirectErrorStream(true)
                .start()
        process.waitFor()
        thread("${javaClass.simpleName}-flush-error") {
            val err = process.errorStream.bufferedReader().readText()
            if (err.isNotBlank()) {
                Log.e(javaClass.simpleName, err)
                app.toast(R.string.noisy_su_failure)
            }
        }
        process.inputStream.bufferedReader().useLines(this::processLines)
    }

    fun destroy() {
        val monitor = monitor
        if (monitor != null) thread("${javaClass.simpleName}-killer") { monitor.destroy() }
        pool?.shutdown()
    }
}
