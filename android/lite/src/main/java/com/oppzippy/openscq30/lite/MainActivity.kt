package com.oppzippy.openscq30.lite

import android.app.Activity
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.Bundle
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.oppzippy.openscq30.lib.bindings.LanguageIdentifier
import com.oppzippy.openscq30.lib.bindings.initNativeI18n
import com.oppzippy.openscq30.lib.bindings.initNativeLogging
import com.oppzippy.openscq30.lib.bindings.newSession
import com.oppzippy.openscq30.lib.wrapper.PairedDevice
import java.io.File
import java.util.Locale
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

class MainActivity : Activity() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private lateinit var textView: TextView
    private val log = StringBuilder()
    private var batteryText = ""
    private var settingsText = ""
    private var connectionJob: Job? = null
    private var nativeReady = false
    private val numberList = Regex("\\[(\\d+(?:, \\d+)*)\\]")
    private val refreshIntervalMs = 3000L

    private val scanReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                BluetoothDevice.ACTION_FOUND -> {
                    val found = intent.getParcelableExtra<BluetoothDevice>(BluetoothDevice.EXTRA_DEVICE) ?: return
                    val name = found.name ?: ""
                    line("Found: $name (${found.address})")
                    if (name.contains("Liberty", ignoreCase = true) &&
                        found.bondState == BluetoothDevice.BOND_NONE
                    ) {
                        BluetoothAdapter.getDefaultAdapter()?.cancelDiscovery()
                        line("Pairing with $name...")
                        found.createBond()
                    }
                }
                BluetoothDevice.ACTION_BOND_STATE_CHANGED -> {
                    val changed = intent.getParcelableExtra<BluetoothDevice>(BluetoothDevice.EXTRA_DEVICE) ?: return
                    val state = intent.getIntExtra(BluetoothDevice.EXTRA_BOND_STATE, BluetoothDevice.ERROR)
                    if (state == BluetoothDevice.BOND_BONDED) {
                        line("Bonded: ${changed.name ?: ""} (${changed.address})")
                        if ((changed.name ?: "").contains("Liberty", ignoreCase = true)) {
                            startConnection()
                        }
                    }
                }
                BluetoothAdapter.ACTION_DISCOVERY_FINISHED -> line("Scan finished")
            }
        }
    }

    private fun render() {
        textView.text = log.toString() + batteryText + settingsText
    }

    private fun line(text: String) {
        log.append(text).append("\n")
        render()
    }

    private fun dumpArrays(message: String): String {
        val out = StringBuilder()
        var index = 0
        for (match in numberList.findAll(message)) {
            val bytes = match.groupValues[1].split(", ").map { it.toInt() and 0xFF }
            if (bytes.size < 8) continue
            index += 1
            out.append("array #").append(index).append(" len=").append(bytes.size).append("\n")
            bytes.chunked(8).forEachIndexed { row, chunk ->
                out.append(String.format(Locale.US, "%03d:", row * 8))
                chunk.forEach { out.append(String.format(Locale.US, " %02X", it)) }
                out.append("\n")
            }
        }
        return out.toString()
    }

    private fun describe(error: Throwable): String {
        val out = StringBuilder()
        var current: Throwable? = error
        var depth = 0
        var lastDumped = ""
        while (current != null && depth < 4) {
            val message = current.message ?: ""
            val shortMessage = if (message.length > 120) message.take(120) + "..." else message
            out.append("[").append(depth).append("] ")
                .append(current.javaClass.simpleName)
                .append(": ")
                .append(shortMessage)
                .append("\n")
            if (message != lastDumped) {
                out.append(dumpArrays(message))
                lastDumped = message
            }
            current = current.cause
            depth += 1
        }
        return out.toString()
    }

    private fun startScan() {
        val adapter = BluetoothAdapter.getDefaultAdapter()
        if (adapter == null || !adapter.isEnabled) {
            line("Bluetooth is off or unavailable")
            return
        }
        line("Scanning... put the earbuds in pairing mode")
        if (adapter.isDiscovering) adapter.cancelDiscovery()
        adapter.startDiscovery()
    }

    private fun startConnection() {
        if (!nativeReady) {
            line("Native library is not loaded")
            return
        }
        connectionJob?.cancel()
        batteryText = ""
        settingsText = ""
        line("--- connecting ---")
        connectionJob = scope.launch {
            try {
                val backends = connectionBackends(applicationContext, scope)
                val devices = AndroidRfcommConnectionBackendImpl(applicationContext, scope).devices()
                line("Paired bluetooth devices: ${devices.size}")
                devices.forEach { line("- ${it.name} (${it.macAddress})") }

                val target = devices.firstOrNull { it.name.contains("Liberty", ignoreCase = true) }
                if (target == null) {
                    line("No Liberty device found. Press Scan & pair.")
                    return@launch
                }
                val mac = target.macAddress
                line("Target: ${target.name}")

                val session = newSession(File(filesDir, "openscq30.db").absolutePath)
                line("Session OK")

                // Pair first so that we connect only once (the first connect attempt always failed before).
                try {
                    session.pair(PairedDevice(macAddress = mac, model = "SoundcoreA3954", isDemo = false))
                    line("Pair OK (model guess SoundcoreA3954)")
                } catch (t: Throwable) {
                    if (t is CancellationException) throw t
                    line("Pair skipped:\n" + describe(t))
                }

                val device = try {
                    session.connectWithBackends(backends, mac)
                } catch (t: Throwable) {
                    if (t is CancellationException) throw t
                    line("Connect failed:\n" + describe(t))
                    null
                }
                val connected = device ?: return@launch

                line("CONNECTED, model=${connected.model()}")

                // Re-read all settings every few seconds so battery etc. stay current.
                while (isActive) {
                    try {
                        val battery = StringBuilder()
                        val all = StringBuilder()
                        connected.categories().forEach { category ->
                            all.append("[").append(category).append("]\n")
                            connected.settingsInCategory(category).forEach { id ->
                                val value = connected.setting(id).toString().take(100)
                                all.append("  ").append(id).append(" = ").append(value).append("\n")
                                if (id.toString().contains("attery", ignoreCase = true)) {
                                    battery.append("  ").append(id).append(" = ").append(value).append("\n")
                                }
                            }
                        }
                        batteryText = if (battery.isEmpty()) "" else "[battery summary]\n$battery"
                        settingsText = all.toString()
                        render()
                    } catch (t: Throwable) {
                        if (t is CancellationException) throw t
                        settingsText = "Refresh failed:\n" + describe(t)
                        render()
                    }
                    delay(refreshIntervalMs)
                }
            } catch (t: Throwable) {
                if (t is CancellationException) throw t
                line("FAIL:\n" + describe(t))
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        textView = TextView(this)
        textView.textSize = 12f
        textView.setPadding(16, 16, 16, 16)
        val scrollView = ScrollView(this)
        scrollView.addView(textView)

        val scanButton = Button(this)
        scanButton.text = "Scan & pair"
        scanButton.setOnClickListener { startScan() }
        val reconnectButton = Button(this)
        reconnectButton.text = "Reconnect"
        reconnectButton.setOnClickListener { startConnection() }

        val buttons = LinearLayout(this)
        buttons.orientation = LinearLayout.HORIZONTAL
        buttons.addView(scanButton, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        buttons.addView(reconnectButton, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))

        val root = LinearLayout(this)
        root.orientation = LinearLayout.VERTICAL
        root.addView(
            buttons,
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT),
        )
        root.addView(scrollView, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        setContentView(root)

        val filter = IntentFilter()
        filter.addAction(BluetoothDevice.ACTION_FOUND)
        filter.addAction(BluetoothDevice.ACTION_BOND_STATE_CHANGED)
        filter.addAction(BluetoothAdapter.ACTION_DISCOVERY_FINISHED)
        registerReceiver(scanReceiver, filter)

        line("Android SDK: ${Build.VERSION.SDK_INT}")

        try {
            System.loadLibrary("openscq30_android")
            System.loadLibrary("jnidispatch")
            initNativeLogging()
            initNativeI18n(listOf(LanguageIdentifier("en", null, null, emptyList())))
            nativeReady = true
            line("OK: native + bindings + i18n")
        } catch (t: Throwable) {
            line("FAIL init:\n" + describe(t))
            return
        }

        startConnection()
    }

    override fun onDestroy() {
        try {
            unregisterReceiver(scanReceiver)
        } catch (_: Throwable) {
        }
        scope.cancel()
        super.onDestroy()
    }
}
