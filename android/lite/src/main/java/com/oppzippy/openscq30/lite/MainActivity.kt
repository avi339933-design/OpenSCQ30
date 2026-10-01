package com.oppzippy.openscq30.lite

import android.app.Activity
import android.app.AlertDialog
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import com.oppzippy.openscq30.lib.bindings.LanguageIdentifier
import com.oppzippy.openscq30.lib.bindings.OpenScq30Session
import com.oppzippy.openscq30.lib.bindings.deviceModels
import com.oppzippy.openscq30.lib.bindings.initNativeI18n
import com.oppzippy.openscq30.lib.bindings.initNativeLogging
import com.oppzippy.openscq30.lib.bindings.newSession
import com.oppzippy.openscq30.lib.bindings.translateDeviceModel
import com.oppzippy.openscq30.lib.wrapper.PairedDevice
import java.io.File
import java.lang.reflect.InvocationTargetException
import java.util.Locale
import kotlin.coroutines.Continuation
import kotlin.coroutines.intrinsics.suspendCoroutineUninterceptedOrReturn
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
    companion object {
        private const val REQUEST_ENABLE_BT = 1001
        private const val MAX_CONNECT_ATTEMPTS = 3
        private const val RETRY_DELAY_MS = 1500L
    }

    private enum class Screen { LIST, PICKER, CONNECTED }

    private data class Row(val name: String, val mac: String, val model: String?)

    private class NoiseButton(val option: String, val label: String, val button: Button)

    private class NoiseGroup(val root: LinearLayout, val buttons: List<NoiseButton>) {
        fun highlight(current: String?) {
            buttons.forEach {
                it.button.text = (if (it.option == current) "● " else "") + it.label
            }
        }
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val prefs by lazy { getSharedPreferences("lite", MODE_PRIVATE) }

    private var session: OpenScq30Session? = null
    private var nativeReady = false
    private var connectionJob: Job? = null
    private var activeDevice: Any? = null
    private var currentScreen = Screen.LIST
    private var currentMac = ""
    private var showAll = false
    private var autoConnectDone = false
    private var resumeMac: String? = null
    private var pendingAfterEnable: (() -> Unit)? = null
    private var deviceRows: List<Row> = emptyList()

    private var statusView: TextView? = null
    private var connectionView: TextView? = null
    private var listView: ListView? = null
    private var noiseContainer: LinearLayout? = null

    private val noiseGroups = LinkedHashMap<String, NoiseGroup>()
    private val loggedSkips = HashSet<String>()
    private var noiseErrorShown = false

    private val statusLines = ArrayList<String>()
    private var batteryText = ""
    private var settingsText = ""

    private val numberList = Regex("\\[(\\d+(?:, \\d+)*)\\]")
    private val noiseNameRegex = Regex("(?i)(noise|ambient|transparen)")
    private val refreshIntervalMs = 3000L

    // Only devices whose Bluetooth MAC address starts with one of these prefixes (the first 3 bytes,
    // which identify the manufacturer) are shown by default. The device name is NOT used for filtering.
    // Prefixes of devices you assign a model to are remembered automatically.
    private val ankerMacPrefixes = listOf(
        "7C:E9:13", // seen on Liberty 4 Pro
    )

    private fun knownPrefixes(): Set<String> {
        val learned: Set<String> = prefs.getStringSet("learned_prefixes", null) ?: emptySet()
        return ankerMacPrefixes.toSet() + learned
    }

    private fun isAnker(mac: String?): Boolean {
        val upper = mac?.uppercase(Locale.US) ?: return false
        return knownPrefixes().any { upper.startsWith(it) }
    }

    private fun learnPrefix(mac: String) {
        val prefix = mac.uppercase(Locale.US).take(8)
        val current: Set<String> = prefs.getStringSet("learned_prefixes", null) ?: emptySet()
        val updated = HashSet<String>(current)
        updated.add(prefix)
        prefs.edit().putStringSet("learned_prefixes", updated).apply()
    }

    private fun modelName(model: String): String = try {
        translateDeviceModel(model)
    } catch (t: Throwable) {
        model
    }

    private val scanReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                BluetoothDevice.ACTION_FOUND -> {
                    val found = intent.getParcelableExtra<BluetoothDevice>(BluetoothDevice.EXTRA_DEVICE) ?: return
                    if (!isAnker(found.address)) return
                    line("Found: ${found.name ?: ""} (${found.address})")
                    if (found.bondState == BluetoothDevice.BOND_NONE) {
                        BluetoothAdapter.getDefaultAdapter()?.cancelDiscovery()
                        line("Pairing...")
                        found.createBond()
                    }
                }
                BluetoothDevice.ACTION_BOND_STATE_CHANGED -> {
                    val changed = intent.getParcelableExtra<BluetoothDevice>(BluetoothDevice.EXTRA_DEVICE) ?: return
                    val state = intent.getIntExtra(BluetoothDevice.EXTRA_BOND_STATE, BluetoothDevice.ERROR)
                    if (state == BluetoothDevice.BOND_BONDED && isAnker(changed.address)) {
                        line("Bonded: ${changed.name ?: ""} (${changed.address})")
                        if (currentScreen == Screen.LIST) refreshDeviceList()
                    }
                }
                BluetoothAdapter.ACTION_DISCOVERY_FINISHED -> line("Scan finished")
            }
        }
    }

    // ---------- logging helpers ----------

    private fun line(text: String) {
        statusLines.add(text)
        while (statusLines.size > 200) statusLines.removeAt(0)
        renderStatus()
    }

    private fun renderStatus() {
        when (currentScreen) {
            Screen.CONNECTED ->
                connectionView?.text = statusLines.takeLast(40).joinToString("\n") + "\n" + batteryText + settingsText
            else -> statusView?.text = statusLines.takeLast(5).joinToString("\n")
        }
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

    private fun toast(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
    }

    private fun makeButton(label: String, onClick: () -> Unit): Button {
        val button = Button(this)
        button.text = label
        button.textSize = 12f
        button.setOnClickListener { onClick() }
        return button
    }

    // ---------- Bluetooth enable dialog ----------

    private fun ensureBluetoothEnabled(onReady: () -> Unit) {
        val adapter = BluetoothAdapter.getDefaultAdapter()
        if (adapter == null) {
            line("Bluetooth is not supported on this device")
            return
        }
        if (adapter.isEnabled) {
            onReady()
            return
        }
        pendingAfterEnable = onReady
        line("Bluetooth is off - asking to enable...")
        try {
            startActivityForResult(Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE), REQUEST_ENABLE_BT)
        } catch (t: Throwable) {
            pendingAfterEnable = null
            line("Could not ask to enable Bluetooth: ${t.message}")
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQUEST_ENABLE_BT) {
            val next = pendingAfterEnable
            pendingAfterEnable = null
            if (resultCode == RESULT_OK) {
                line("Bluetooth enabled")
                next?.invoke()
            } else {
                line("Bluetooth was not enabled")
            }
        }
    }

    // ---------- device list screen ----------

    private fun showList() {
        currentScreen = Screen.LIST
        connectionView = null
        noiseContainer = null

        val showAllButton = makeButton(if (showAll) "All: on" else "All: off") {}
        showAllButton.setOnClickListener {
            showAll = !showAll
            showAllButton.text = if (showAll) "All: on" else "All: off"
            refreshDeviceList()
        }
        val buttons = LinearLayout(this)
        buttons.orientation = LinearLayout.HORIZONTAL
        val weight = { LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f) }
        buttons.addView(makeButton("Scan & pair") { startScan() }, weight())
        buttons.addView(makeButton("Refresh") { refreshDeviceList() }, weight())
        buttons.addView(showAllButton, weight())

        val list = ListView(this)
        listView = list
        list.setOnItemClickListener { _, _, position, _ ->
            if (position < deviceRows.size) onDeviceChosen(deviceRows[position])
        }
        list.setOnItemLongClickListener { _, _, position, _ ->
            if (position < deviceRows.size) showDeviceMenu(deviceRows[position])
            true
        }

        val status = TextView(this)
        status.textSize = 11f
        status.setPadding(8, 8, 8, 8)
        statusView = status

        val root = LinearLayout(this)
        root.orientation = LinearLayout.VERTICAL
        root.addView(
            buttons,
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT),
        )
        root.addView(list, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        root.addView(
            status,
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT),
        )
        setContentView(root)

        updateListAdapter()
        renderStatus()
        refreshDeviceList()
    }

    private fun rowText(row: Row): String {
        val modelLine = if (row.model != null) "-> ${modelName(row.model)}" else "(tap to choose model)"
        return "${row.name}\n${row.mac}\n$modelLine"
    }

    private fun updateListAdapter() {
        val texts = deviceRows.map { rowText(it) }
        listView?.adapter = ArrayAdapter<String>(this, android.R.layout.simple_list_item_1, texts)
    }

    // All paired (bonded) Bluetooth devices, each with its assigned model (or null).
    private suspend fun allRows(): List<Row> {
        val activeSession = session ?: return emptyList()
        val bonded = AndroidRfcommConnectionBackendImpl(applicationContext, scope).devices()
        val assigned = activeSession.pairedDevices()
        return bonded.map { d ->
            val model = assigned
                .firstOrNull { it.macAddress.equals(d.macAddress, ignoreCase = true) }
                ?.model
            Row(d.name, d.macAddress, model)
        }
    }

    private fun refreshDeviceList() {
        if (session == null) return
        scope.launch {
            try {
                val all = allRows()
                deviceRows = all.filter { showAll || isAnker(it.mac) || it.model != null }
                updateListAdapter()
                if (deviceRows.isEmpty()) {
                    line("No Anker devices found (${all.size} paired in total). Try Scan & pair, or All: on.")
                }
            } catch (t: Throwable) {
                if (t is CancellationException) throw t
                line("List failed:\n" + describe(t))
            }
        }
    }

    private fun onDeviceChosen(row: Row) {
        if (row.model != null) {
            startConnection(row.mac)
        } else {
            showModelPicker(row)
        }
    }

    private fun showDeviceMenu(row: Row) {
        val items: Array<CharSequence> = if (row.model != null) {
            arrayOf("Change model", "Forget model (unpair)")
        } else {
            arrayOf("Choose model")
        }
        AlertDialog.Builder(this)
            .setTitle(row.name)
            .setItems(items) { _, which ->
                if (which == 0) {
                    showModelPicker(row)
                } else {
                    val activeSession = session ?: return@setItems
                    scope.launch {
                        try {
                            activeSession.unpair(row.mac)
                            if (row.mac.equals(prefs.getString("last_mac", null), ignoreCase = true)) {
                                prefs.edit().remove("last_mac").apply()
                            }
                            line("Unpaired ${row.name}")
                            refreshDeviceList()
                        } catch (t: Throwable) {
                            if (t is CancellationException) throw t
                            line("Unpair failed:\n" + describe(t))
                        }
                    }
                }
            }
            .show()
    }

    // ---------- model picker screen ----------

    private fun suggestMatches(deviceName: String, modelLabel: String): Boolean {
        val a = deviceName.lowercase(Locale.US).trim()
        val b = modelLabel.lowercase(Locale.US).trim()
        return b.length >= 5 && (a == b || a.contains(b))
    }

    private fun showModelPicker(row: Row) {
        currentScreen = Screen.PICKER
        connectionView = null
        statusView = null
        noiseContainer = null

        val models: List<Pair<String, String>> = try {
            deviceModels()
                .filter { it != "SoundcoreDevelopment" }
                .map { Pair(it, modelName(it)) }
                .sortedBy { it.second }
        } catch (t: Throwable) {
            line("Could not load model list:\n" + describe(t))
            showList()
            return
        }

        val title = TextView(this)
        title.textSize = 14f
        title.setPadding(8, 8, 8, 8)
        title.text = "Select the model of ${row.name}"

        val search = EditText(this)
        search.hint = "Search model"
        search.setSingleLine(true)

        val list = ListView(this)
        var shown: List<Pair<String, String>> = emptyList()

        fun update() {
            val query = search.text.toString().trim().lowercase(Locale.US)
            val filtered = models.filter { (id, label) ->
                query.isEmpty() ||
                    label.lowercase(Locale.US).contains(query) ||
                    (query.length >= 4 && id.lowercase(Locale.US).contains(query))
            }
            val (suggested, others) = filtered.partition { suggestMatches(row.name, it.second) }
            shown = suggested + others
            val texts = suggested.map { "* ${it.second}" } + others.map { it.second }
            list.adapter = ArrayAdapter<String>(this, android.R.layout.simple_list_item_1, texts)
        }

        search.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                update()
            }
        })
        list.setOnItemClickListener { _, _, position, _ ->
            if (position < shown.size) onModelChosen(row, shown[position].first)
        }

        val root = LinearLayout(this)
        root.orientation = LinearLayout.VERTICAL
        root.addView(
            title,
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT),
        )
        root.addView(
            search,
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT),
        )
        root.addView(list, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        setContentView(root)
        update()
    }

    private fun onModelChosen(row: Row, modelId: String) {
        val activeSession = session ?: return
        scope.launch {
            try {
                activeSession.pair(PairedDevice(macAddress = row.mac, model = modelId, isDemo = false))
                learnPrefix(row.mac)
                line("Assigned ${modelName(modelId)} to ${row.name}")
                startConnection(row.mac)
            } catch (t: Throwable) {
                if (t is CancellationException) throw t
                line("Assign failed:\n" + describe(t))
                toast("Assign failed")
                showList()
            }
        }
    }

    // ---------- scan ----------

    private fun startScan() {
        ensureBluetoothEnabled { beginScan() }
    }

    private fun beginScan() {
        val adapter = BluetoothAdapter.getDefaultAdapter()
        if (adapter == null || !adapter.isEnabled) {
            line("Bluetooth is off or unavailable")
            return
        }
        line("Scanning for Anker devices... put the earbuds in pairing mode")
        if (adapter.isDiscovering) adapter.cancelDiscovery()
        adapter.startDiscovery()
    }

    // ---------- auto-connect on app start ----------

    private fun autoConnect() {
        if (autoConnectDone) return
        autoConnectDone = true
        if (session == null) return
        scope.launch {
            try {
                val assigned = allRows().filter { it.model != null }
                val last = prefs.getString("last_mac", null)
                val target = assigned.firstOrNull { it.mac.equals(last, ignoreCase = true) }
                    ?: assigned.singleOrNull()
                if (target == null) {
                    line("Auto-connect: no device with an assigned model yet")
                    return@launch
                }
                line("Auto-connecting to ${target.name}...")
                startConnection(target.mac)
            } catch (t: Throwable) {
                if (t is CancellationException) throw t
                line("Auto-connect failed:\n" + describe(t))
            }
        }
    }

    // ---------- connected screen ----------

    private fun showConnectedScreen(mac: String) {
        currentScreen = Screen.CONNECTED
        currentMac = mac
        statusView = null
        noiseGroups.clear()
        loggedSkips.clear()
        noiseErrorShown = false

        val text = TextView(this)
        text.textSize = 12f
        text.setPadding(16, 16, 16, 16)
        connectionView = text
        val scrollView = ScrollView(this)
        scrollView.addView(text)

        val noise = LinearLayout(this)
        noise.orientation = LinearLayout.VERTICAL
        noiseContainer = noise

        val buttons = LinearLayout(this)
        buttons.orientation = LinearLayout.HORIZONTAL
        buttons.addView(
            makeButton("Back") { onBackPressed() },
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f),
        )
        buttons.addView(
            makeButton("Reconnect") { startConnection(currentMac) },
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f),
        )

        val root = LinearLayout(this)
        root.orientation = LinearLayout.VERTICAL
        root.addView(
            buttons,
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT),
        )
        root.addView(
            noise,
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT),
        )
        root.addView(scrollView, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        setContentView(root)
    }

    // ---------- connect / disconnect ----------

    private fun startConnection(mac: String) {
        if (session == null || !nativeReady) {
            line("Not ready yet")
            return
        }
        ensureBluetoothEnabled { connectNow(mac) }
    }

    // Tears down the current connection (if any). Returns true if something was active.
    private fun disconnect(reason: String): Boolean {
        val job = connectionJob
        val device = activeDevice
        val wasActive = (job != null && job.isActive) || device != null
        connectionJob = null
        activeDevice = null
        job?.cancel()
        if (wasActive) {
            releaseDevice(device, currentMac)
            line("Disconnected from $currentMac ($reason)")
        }
        return wasActive
    }

    // Closes the Rust device handle and the Bluetooth socket off the main thread.
    private fun releaseDevice(device: Any?, mac: String) {
        Thread {
            try {
                if (device is AutoCloseable) {
                    device.close()
                } else if (device != null) {
                    device.javaClass.methods
                        .firstOrNull { it.name == "destroy" && it.parameterTypes.isEmpty() }
                        ?.invoke(device)
                }
            } catch (_: Throwable) {
            }
            try {
                ActiveSockets.close(mac)
            } catch (_: Throwable) {
            }
        }.start()
    }

    private fun connectNow(mac: String) {
        val activeSession = session
        if (activeSession == null || !nativeReady) {
            line("Not ready yet")
            return
        }
        disconnect("new connection")
        batteryText = ""
        settingsText = ""
        statusLines.clear()
        showConnectedScreen(mac)
        line("--- connecting to $mac ---")
        connectionJob = scope.launch {
            try {
                // Let any previous socket finish closing, and make sure no Bluetooth scan is running
                // (an active scan disturbs RFCOMM connections).
                delay(500)
                try {
                    BluetoothAdapter.getDefaultAdapter()?.cancelDiscovery()
                } catch (_: Throwable) {
                }

                val backends = connectionBackends(applicationContext, scope)
                var device: com.oppzippy.openscq30.lib.wrapper.ConnectedDeviceHolder? = null
                var connectedDevice: Any? = null
                var attempt = 1
                while (connectedDevice == null && attempt <= MAX_CONNECT_ATTEMPTS) {
                    try {
                        connectedDevice = activeSession.connectWithBackends(backends, mac)
                    } catch (t: Throwable) {
                        if (t is CancellationException) throw t
                        line("Connect failed (attempt $attempt/$MAX_CONNECT_ATTEMPTS):\n" + describe(t))
                        // Close the half-open socket so the retry starts clean.
                        try {
                            ActiveSockets.close(mac)
                        } catch (_: Throwable) {
                        }
                        if (attempt < MAX_CONNECT_ATTEMPTS) {
                            line("Retrying...")
                            delay(RETRY_DELAY_MS)
                        }
                    }
                    attempt += 1
                }
                if (connectedDevice == null) {
                    line("Giving up after $MAX_CONNECT_ATTEMPTS attempts. Press Reconnect to try again.")
                    return@launch
                }
                val connected = connectedDevice

                activeDevice = connected
                prefs.edit().putString("last_mac", mac).apply()
                line("CONNECTED, model=${modelName(connected.model())}")

                // Re-read all settings every few seconds (only while the app is on screen).
                while (isActive) {
                    if (!ActiveSockets.isConnected(mac)) {
                        line("Connection lost - disconnected from $mac")
                        if (activeDevice === connected) activeDevice = null
                        releaseDevice(connected, mac)
                        break
                    }
                    try {
                        val battery = StringBuilder()
                        val all = StringBuilder()
                        val noiseIds = ArrayList<Any>()
                        connected.categories().forEach { category ->
                            all.append("[").append(category).append("]\n")
                            connected.settingsInCategory(category).forEach { id ->
                                val value = connected.setting(id).toString().take(100)
                                all.append("  ").append(id).append(" = ").append(value).append("\n")
                                if (id.toString().contains("attery", ignoreCase = true)) {
                                    battery.append("  ").append(id).append(" = ").append(value).append("\n")
                                }
                                if (noiseNameRegex.containsMatchIn(id.toString())) {
                                    noiseIds.add(id)
                                }
                            }
                        }
                        batteryText = if (battery.isEmpty()) "" else "[battery summary]\n$battery"
                        settingsText = all.toString()
                        renderStatus()

                        try {
                            syncNoiseControls(connected, noiseIds)
                        } catch (t: Throwable) {
                            if (t is CancellationException) throw t
                            if (!noiseErrorShown) {
                                noiseErrorShown = true
                                line("Noise controls failed:\n" + describe(t))
                            }
                        }
                    } catch (t: Throwable) {
                        if (t is CancellationException) throw t
                        settingsText = "Refresh failed:\n" + describe(t)
                        renderStatus()
                    }
                    delay(refreshIntervalMs)
                }
            } catch (t: Throwable) {
                if (t is CancellationException) throw t
                line("FAIL:\n" + describe(t))
            }
        }
    }

    // ---------- noise mode buttons ----------
    // These helpers use reflection so that the build does not depend on the exact names of the generated
    // Setting/Value classes. If a button does not appear, the log shows which class/members were found.

    private fun prop(target: Any, name: String): Any? {
        val getter = "get" + name.substring(0, 1).uppercase(Locale.US) + name.substring(1)
        val method = target.javaClass.methods.firstOrNull { it.name == getter && it.parameterTypes.isEmpty() }
            ?: target.javaClass.methods.firstOrNull { it.name == name && it.parameterTypes.isEmpty() }
        return try {
            method?.invoke(target)
        } catch (_: Throwable) {
            null
        }
    }

    private fun members(target: Any): String =
        target.javaClass.name + " " + target.javaClass.methods
            .map { it.name }
            .filter { it.startsWith("get") || it == "value" || it == "options" }
            .distinct()
            .joinToString(",")

    // Calls a method by name, whether it is a normal function or a suspend function.
    private suspend fun callAny(target: Any, name: String, vararg args: Any?): Any? {
        val methods = target.javaClass.methods.filter { it.name == name }
        val plain = methods.firstOrNull { it.parameterTypes.size == args.size }
        try {
            if (plain != null) return plain.invoke(target, *args)
            val suspending = methods.firstOrNull {
                it.parameterTypes.size == args.size + 1 &&
                    Continuation::class.java.isAssignableFrom(it.parameterTypes.last())
            } ?: throw NoSuchMethodException("$name(${args.size} args) on ${target.javaClass.name}")
            return suspendCoroutineUninterceptedOrReturn<Any?> { cont ->
                suspending.invoke(target, *args, cont)
            }
        } catch (e: InvocationTargetException) {
            throw e.targetException ?: e
        }
    }

    private fun makeStringValue(text: String): Any {
        val bases = listOf(
            "com.oppzippy.openscq30.lib.wrapper.Value",
            "com.oppzippy.openscq30.lib.bindings.Value",
        )
        val nested = listOf("String", "StringValue", "Str")
        for (base in bases) {
            for (name in nested) {
                try {
                    val cls = Class.forName(base + "$" + name)
                    return cls.getConstructor(String::class.java).newInstance(text)
                } catch (_: Throwable) {
                }
            }
        }
        throw IllegalStateException("Value.String class not found (tried $bases)")
    }

    private fun isPlainSelect(typeName: String): Boolean =
        typeName.contains("Select") &&
            !typeName.contains("Optional") &&
            !typeName.contains("Modifiable") &&
            !typeName.contains("Multi")

    private fun prettify(key: String): String = key.replace(Regex("([a-z])([A-Z])"), "$1 $2")

    private suspend fun syncNoiseControls(device: Any, ids: List<Any>) {
        for (id in ids) {
            val key = id.toString()
            val setting = callAny(device, "setting", id) ?: continue
            val typeName = setting.javaClass.simpleName
            if (!isPlainSelect(typeName)) {
                if (loggedSkips.add("type:$key")) line("(noise: skipped $key, type $typeName)")
                continue
            }
            val select = prop(setting, "setting") ?: setting
            val options = (prop(select, "options") as? List<*>)?.map { it.toString() }
            if (options == null || options.isEmpty()) {
                if (loggedSkips.add("opt:$key")) line("(noise: no options for $key: ${members(select)})")
                continue
            }
            val labels = (prop(select, "localizedOptions") as? List<*>)?.map { it.toString() } ?: options
            val current = prop(setting, "value")?.toString()

            var group = noiseGroups[key]
            if (group == null) {
                group = buildNoiseGroup(device, id, key, options, labels)
                noiseGroups[key] = group
                noiseContainer?.addView(
                    group.root,
                    LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT,
                    ),
                )
            }
            group.highlight(current)
        }
    }

    private fun buildNoiseGroup(
        device: Any,
        id: Any,
        key: String,
        options: List<String>,
        labels: List<String>,
    ): NoiseGroup {
        val root = LinearLayout(this)
        root.orientation = LinearLayout.VERTICAL

        val title = TextView(this)
        title.text = prettify(key)
        title.textSize = 12f
        title.setPadding(8, 4, 8, 0)
        root.addView(title)

        val buttons = ArrayList<NoiseButton>()
        options.chunked(3).forEachIndexed { chunkIndex, chunk ->
            val row = LinearLayout(this)
            row.orientation = LinearLayout.HORIZONTAL
            chunk.forEachIndexed { i, option ->
                val label = labels.getOrElse(chunkIndex * 3 + i) { option }
                val button = makeButton(label) {}
                row.addView(button, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
                buttons.add(NoiseButton(option, label, button))
            }
            root.addView(
                row,
                LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT),
            )
        }

        val group = NoiseGroup(root, buttons)
        buttons.forEach { nb ->
            nb.button.setOnClickListener {
                scope.launch {
                    try {
                        callAny(device, "setSetting", id, makeStringValue(nb.option))
                        line("Set ${prettify(key)} = ${nb.label}")
                        group.highlight(nb.option)
                    } catch (t: Throwable) {
                        if (t is CancellationException) throw t
                        line("Set failed:\n" + describe(t))
                    }
                }
            }
        }
        return group
    }

    // ---------- lifecycle ----------

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val filter = IntentFilter()
        filter.addAction(BluetoothDevice.ACTION_FOUND)
        filter.addAction(BluetoothDevice.ACTION_BOND_STATE_CHANGED)
        filter.addAction(BluetoothAdapter.ACTION_DISCOVERY_FINISHED)
        registerReceiver(scanReceiver, filter)

        showList()
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

        scope.launch {
            try {
                session = newSession(File(filesDir, "openscq30.db").absolutePath)
                line("Session OK")
                refreshDeviceList()
                ensureBluetoothEnabled { autoConnect() }
            } catch (t: Throwable) {
                if (t is CancellationException) throw t
                line("Session failed:\n" + describe(t))
            }
        }
    }

    // When the app leaves the screen: close the connection (no polling in the background, and the
    // earbuds become free for other devices). When it comes back: reconnect automatically.
    override fun onStop() {
        super.onStop()
        if (currentScreen == Screen.CONNECTED && !isFinishing) {
            val job = connectionJob
            val wasActive = (job != null && job.isActive) || activeDevice != null
            if (wasActive) {
                resumeMac = currentMac
                disconnect("left app")
            }
        }
    }

    override fun onStart() {
        super.onStart()
        val mac = resumeMac
        resumeMac = null
        if (mac != null && session != null && nativeReady) {
            startConnection(mac)
        }
    }

    override fun onBackPressed() {
        when (currentScreen) {
            Screen.CONNECTED -> {
                autoConnectDone = true
                resumeMac = null
                disconnect("back")
                showList()
            }
            Screen.PICKER -> showList()
            Screen.LIST -> super.onBackPressed()
        }
    }

    override fun onDestroy() {
        try {
            unregisterReceiver(scanReceiver)
        } catch (_: Throwable) {
        }
        disconnect("app closed")
        scope.cancel()
        super.onDestroy()
    }
}
