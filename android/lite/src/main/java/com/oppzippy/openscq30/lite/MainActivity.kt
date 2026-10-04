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
import android.os.Handler
import android.os.Looper
import android.text.Editable
import android.text.TextWatcher
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import com.oppzippy.openscq30.lib.bindings.LanguageIdentifier
import com.oppzippy.openscq30.lib.bindings.OpenScq30Device
import com.oppzippy.openscq30.lib.bindings.OpenScq30Session
import com.oppzippy.openscq30.lib.bindings.SettingIdValuePair
import com.oppzippy.openscq30.lib.bindings.deviceModels
import com.oppzippy.openscq30.lib.bindings.initNativeI18n
import com.oppzippy.openscq30.lib.bindings.initNativeLogging
import com.oppzippy.openscq30.lib.bindings.newSession
import com.oppzippy.openscq30.lib.bindings.translateDeviceModel
import com.oppzippy.openscq30.lib.wrapper.PairedDevice
import com.oppzippy.openscq30.lib.wrapper.Setting
import com.oppzippy.openscq30.lib.wrapper.Value
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
    companion object {
        private const val REQUEST_ENABLE_BT = 1001
        private const val MAX_CONNECT_ATTEMPTS = 3
        private const val RETRY_DELAY_MS = 1500L
        private const val KEY_SEND_DELAY_MS = 700L
    }
 
    private enum class Screen { LIST, PICKER, CONNECTED }
 
    // bonded = already paired in Android's Bluetooth. Rows with bonded=false are nearby devices found by scanning.
    private data class Row(val name: String, val mac: String, val model: String?, val bonded: Boolean = true)
 
    private class SettingItem(val category: String, val key: String, val setting: Any?)
 
    private class ChoiceButton(val option: String?, val label: String, val button: Button)
 
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val prefs by lazy { getSharedPreferences("lite", MODE_PRIVATE) }
    private val uiHandler = Handler(Looper.getMainLooper())
 
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
    private var pendingPairMac: String? = null
 
    private var bondedRows: List<Row> = emptyList()
    private val discovered = LinkedHashMap<String, String>() // MAC (upper case) -> name
    private var deviceRows: List<Row> = emptyList()
 
    private var statusView: TextView? = null
    private var connectionView: TextView? = null
    private var listView: ListView? = null
    private var settingsContainer: LinearLayout? = null
 
    // One entry per setting id: a function that refreshes its control from a new value.
    private val controls = HashMap<String, (Any?) -> Unit>()
    private val sections = HashMap<String, LinearLayout>()
    private val loggedSkips = HashSet<String>()
    private var updatingUi = false
    private var lastRefreshError = ""
 
    // Names that have no Hebrew translation yet (N = setting or category name, O = option). Listed in the log.
    private val untranslated = LinkedHashSet<String>()
    private var reportedUntranslated = 0
 
    private val statusLines = ArrayList<String>()
 
    private val numberList = Regex("\\[(\\d+(?:, \\d+)*)\\]")
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
 
    // ---------- Hebrew helpers (the translations themselves are in HebrewLabels.kt) ----------
 
    private fun prettify(key: String): String =
        key.replace(Regex("([a-z])([A-Z])"), "$1 $2").replaceFirstChar { it.uppercase(Locale.US) }
 
    // Name of a setting or a category, in Hebrew when known.
    private fun settingName(key: String): String {
        val hebrew = HebrewLabels.name(key)
        if (hebrew != null) return hebrew
        untranslated.add("N:$key")
        return prettify(key)
    }
 
    // Label of an option (a button), in Hebrew when known.
    private fun optionText(option: String, localized: String): String {
        val hebrew = HebrewLabels.option(option, localized)
        if (hebrew != null) return hebrew
        untranslated.add("O:$option")
        return localized.ifBlank { option }
    }
 
    // Lists the names that still have no translation, so they can be added to HebrewLabels.kt.
    private fun reportUntranslated() {
        if (untranslated.size == reportedUntranslated) return
        reportedUntranslated = untranslated.size
        line("חסר תרגום (${untranslated.size}): " + untranslated.joinToString(", "))
    }
 
    private fun rtl(view: View) {
        view.layoutDirection = View.LAYOUT_DIRECTION_RTL
    }
 
    private val scanReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                BluetoothDevice.ACTION_FOUND -> {
                    val found = intent.getParcelableExtra<BluetoothDevice>(BluetoothDevice.EXTRA_DEVICE) ?: return
                    val mac = found.address.uppercase(Locale.US)
                    val name = intent.getStringExtra(BluetoothDevice.EXTRA_NAME) ?: found.name ?: ""
                    val isNew = !discovered.containsKey(mac)
                    if (isNew || name.isNotEmpty()) discovered[mac] = name
                    if (isNew && isAnker(mac)) line("נמצא: ${name.ifEmpty { "לא ידוע" }} ($mac)")
                    if (currentScreen == Screen.LIST) rebuildRows()
                }
                BluetoothDevice.ACTION_BOND_STATE_CHANGED -> {
                    val changed = intent.getParcelableExtra<BluetoothDevice>(BluetoothDevice.EXTRA_DEVICE) ?: return
                    val mac = changed.address.uppercase(Locale.US)
                    val state = intent.getIntExtra(BluetoothDevice.EXTRA_BOND_STATE, BluetoothDevice.ERROR)
                    val previous = intent.getIntExtra(BluetoothDevice.EXTRA_PREVIOUS_BOND_STATE, BluetoothDevice.ERROR)
                    if (mac != pendingPairMac) {
                        if (state == BluetoothDevice.BOND_BONDED && currentScreen == Screen.LIST) refreshDeviceList()
                        return
                    }
                    if (state == BluetoothDevice.BOND_BONDED) {
                        pendingPairMac = null
                        line("זווג: ${changed.name ?: ""} ($mac)")
                        discovered.remove(mac)
                        scope.launch {
                            try {
                                bondedRows = allRows()
                                rebuildRows()
                                val row = bondedRows.firstOrNull { it.mac.equals(mac, ignoreCase = true) }
                                if (row != null) onDeviceChosen(row)
                            } catch (t: Throwable) {
                                if (t is CancellationException) throw t
                                line("טעינת הרשימה נכשלה:\n" + describe(t))
                            }
                        }
                    } else if (state == BluetoothDevice.BOND_NONE && previous == BluetoothDevice.BOND_BONDING) {
                        pendingPairMac = null
                        line("הזיווג נכשל. הכנס את האוזניות למצב זיווג ונסה שוב.")
                    }
                }
                BluetoothAdapter.ACTION_DISCOVERY_FINISHED -> line("הסריקה הסתיימה")
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
            Screen.CONNECTED -> connectionView?.text = statusLines.takeLast(15).joinToString("\n")
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
 
    private fun matchWrap() =
        LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
 
    // ---------- Bluetooth enable dialog ----------
 
    private fun ensureBluetoothEnabled(onReady: () -> Unit) {
        val adapter = BluetoothAdapter.getDefaultAdapter()
        if (adapter == null) {
            line("המכשיר הזה לא תומך ב-Bluetooth")
            return
        }
        if (adapter.isEnabled) {
            onReady()
            return
        }
        pendingAfterEnable = onReady
        line("Bluetooth כבוי – מבקש להפעיל...")
        try {
            startActivityForResult(Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE), REQUEST_ENABLE_BT)
        } catch (t: Throwable) {
            pendingAfterEnable = null
            line("לא הצלחתי לבקש הפעלת Bluetooth: ${t.message}")
        }
    }
 
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQUEST_ENABLE_BT) {
            val next = pendingAfterEnable
            pendingAfterEnable = null
            if (resultCode == RESULT_OK) {
                line("Bluetooth הופעל")
                next?.invoke()
            } else {
                line("Bluetooth לא הופעל")
            }
        }
    }
 
    // ---------- device list screen ----------
 
    private fun showList() {
        currentScreen = Screen.LIST
        connectionView = null
        settingsContainer = null
 
        val showAllButton = makeButton(if (showAll) "הכל: פעיל" else "הכל: כבוי") {}
        showAllButton.setOnClickListener {
            showAll = !showAll
            showAllButton.text = if (showAll) "הכל: פעיל" else "הכל: כבוי"
            refreshDeviceList()
        }
        val buttons = LinearLayout(this)
        buttons.orientation = LinearLayout.HORIZONTAL
        val weight = { LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f) }
        buttons.addView(makeButton("סרוק") { startScan() }, weight())
        buttons.addView(makeButton("רענן") { refreshDeviceList() }, weight())
        buttons.addView(showAllButton, weight())
        buttons.addView(
            makeButton("עדכון") {
                UpdateManager.showUpdateDialog(this, { disconnect("עדכון") }, { line(it) })
            },
            weight(),
        )
 
        val list = ListView(this)
        listView = list
        list.setOnItemClickListener { _, _, position, _ ->
            if (position < deviceRows.size) onDeviceChosen(deviceRows[position])
        }
        list.setOnItemLongClickListener { _, _, position, _ ->
            if (position < deviceRows.size && deviceRows[position].bonded) showDeviceMenu(deviceRows[position])
            true
        }
 
        val status = TextView(this)
        status.textSize = 11f
        status.setPadding(8, 8, 8, 8)
        statusView = status
 
        val root = LinearLayout(this)
        root.orientation = LinearLayout.VERTICAL
        rtl(root)
        root.addView(buttons, matchWrap())
        root.addView(list, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        root.addView(status, matchWrap())
        setContentView(root)
 
        updateListAdapter()
        renderStatus()
        refreshDeviceList()
    }
 
    private fun rowText(row: Row): String {
        val name = row.name.ifEmpty { "לא ידוע" }
        val modelLine = when {
            !row.bonded -> "(בקרבת מקום – לחץ לזיווג)"
            row.model != null -> "דגם: ${modelName(row.model)}"
            else -> "(לחץ לבחירת דגם)"
        }
        return "$name\n${row.mac}\n$modelLine"
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
 
    // Combines the paired devices and the nearby (scanned) devices into the visible list.
    private fun rebuildRows() {
        val bondedMacs = bondedRows.map { it.mac.uppercase(Locale.US) }.toSet()
        val bonded = bondedRows.filter { showAll || isAnker(it.mac) || it.model != null }
        val nearby = discovered.entries
            .filter { it.key !in bondedMacs && (showAll || isAnker(it.key)) }
            .map { Row(it.value, it.key, null, false) }
        deviceRows = bonded + nearby
        updateListAdapter()
    }
 
    private fun refreshDeviceList() {
        if (session == null) return
        scope.launch {
            try {
                bondedRows = allRows()
                rebuildRows()
                if (deviceRows.isEmpty()) {
                    line("לא נמצאו מכשירי Anker (${bondedRows.size} מכשירים מזווגים בסך הכל). לחץ על סרוק, או הפעל 'הכל'.")
                }
            } catch (t: Throwable) {
                if (t is CancellationException) throw t
                line("טעינת הרשימה נכשלה:\n" + describe(t))
            }
        }
    }
 
    private fun onDeviceChosen(row: Row) {
        if (!row.bonded) {
            pairNearby(row)
        } else if (row.model != null) {
            startConnection(row.mac)
        } else {
            showModelPicker(row)
        }
    }
 
    // Pairs with a nearby device from inside the app (no need to open Android's Bluetooth settings).
    private fun pairNearby(row: Row) {
        ensureBluetoothEnabled {
            val adapter = BluetoothAdapter.getDefaultAdapter()
            if (adapter != null) {
                try {
                    if (adapter.isDiscovering) adapter.cancelDiscovery()
                    val device = adapter.getRemoteDevice(row.mac)
                    pendingPairMac = row.mac.uppercase(Locale.US)
                    line("מזווג עם ${row.name.ifEmpty { "לא ידוע" }}... אשר בטלפון אם מתבקש")
                    if (!device.createBond()) {
                        pendingPairMac = null
                        line("לא ניתן להתחיל זיווג")
                    }
                } catch (t: Throwable) {
                    pendingPairMac = null
                    line("הזיווג נכשל:\n" + describe(t))
                }
            }
        }
    }
 
    private fun showDeviceMenu(row: Row) {
        val items: Array<CharSequence> = if (row.model != null) {
            arrayOf("שנה דגם", "בטל שיוך דגם")
        } else {
            arrayOf("בחר דגם")
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
                            line("שיוך הדגם בוטל: ${row.name}")
                            refreshDeviceList()
                        } catch (t: Throwable) {
                            if (t is CancellationException) throw t
                            line("ביטול השיוך נכשל:\n" + describe(t))
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
        settingsContainer = null
 
        val models: List<Pair<String, String>> = try {
            deviceModels()
                .filter { it != "SoundcoreDevelopment" }
                .map { Pair(it, modelName(it)) }
                .sortedBy { it.second }
        } catch (t: Throwable) {
            line("טעינת רשימת הדגמים נכשלה:\n" + describe(t))
            showList()
            return
        }
 
        val title = TextView(this)
        title.textSize = 14f
        title.setPadding(8, 8, 8, 8)
        title.text = "בחר את הדגם של ${row.name}"
 
        val search = EditText(this)
        search.hint = "חפש דגם"
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
        rtl(root)
        root.addView(title, matchWrap())
        root.addView(search, matchWrap())
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
                line("שויך הדגם ${modelName(modelId)} ל-${row.name}")
                startConnection(row.mac)
            } catch (t: Throwable) {
                if (t is CancellationException) throw t
                line("שיוך הדגם נכשל:\n" + describe(t))
                toast("שיוך הדגם נכשל")
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
            line("Bluetooth כבוי או לא זמין")
            return
        }
        discovered.clear()
        if (currentScreen == Screen.LIST) rebuildRows()
        line("סורק מכשירי Anker בסביבה... אוזניות חדשות חייבות להיות במצב זיווג")
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
                    line("חיבור אוטומטי: עדיין אין מכשיר ששויך לו דגם")
                    beginScan()
                    return@launch
                }
                line("מתחבר אוטומטית ל-${target.name}...")
                startConnection(target.mac)
            } catch (t: Throwable) {
                if (t is CancellationException) throw t
                line("החיבור האוטומטי נכשל:\n" + describe(t))
            }
        }
    }
 
    // ---------- connected screen ----------
 
    private fun showConnectedScreen(mac: String) {
        currentScreen = Screen.CONNECTED
        currentMac = mac
        statusView = null
        controls.clear()
        sections.clear()
        loggedSkips.clear()
        lastRefreshError = ""
        reportedUntranslated = 0
 
        val container = LinearLayout(this)
        container.orientation = LinearLayout.VERTICAL
        settingsContainer = container
 
        val log = TextView(this)
        log.textSize = 11f
        log.setPadding(16, 16, 16, 16)
        connectionView = log
 
        val content = LinearLayout(this)
        content.orientation = LinearLayout.VERTICAL
        content.addView(container, matchWrap())
        content.addView(log, matchWrap())
 
        val scrollView = ScrollView(this)
        scrollView.addView(content)
 
        val buttons = LinearLayout(this)
        buttons.orientation = LinearLayout.HORIZONTAL
        buttons.addView(
            makeButton("חזרה") { onBackPressed() },
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f),
        )
        buttons.addView(
            makeButton("התחבר מחדש") { startConnection(currentMac) },
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f),
        )
 
        val root = LinearLayout(this)
        root.orientation = LinearLayout.VERTICAL
        rtl(root)
        root.addView(buttons, matchWrap())
        root.addView(scrollView, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        setContentView(root)
        renderStatus()
    }
 
    // ---------- connect / disconnect ----------
 
    private fun startConnection(mac: String) {
        if (session == null || !nativeReady) {
            line("עדיין לא מוכן")
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
            line("התנתק מ-$currentMac ($reason)")
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
            line("עדיין לא מוכן")
            return
        }
        disconnect("חיבור חדש")
        statusLines.clear()
        showConnectedScreen(mac)
        line("--- מתחבר אל $mac ---")
        connectionJob = scope.launch {
            try {
                // Let any previous socket finish closing, and make sure no Bluetooth scan is running.
                delay(500)
                try {
                    BluetoothAdapter.getDefaultAdapter()?.cancelDiscovery()
                } catch (_: Throwable) {
                }
 
                val backends = connectionBackends(applicationContext, scope)
                val connected = connectWithRetry(activeSession, backends, mac)
                if (connected == null) {
                    line("ויתרתי אחרי $MAX_CONNECT_ATTEMPTS ניסיונות. לחץ על 'התחבר מחדש' כדי לנסות שוב.")
                    return@launch
                }
 
                activeDevice = connected
                prefs.edit().putString("last_mac", mac).apply()
                line("מחובר, דגם: ${modelName(connected.model())}")
 
                // Re-read all settings every few seconds (only while the app is on screen).
                while (isActive) {
                    if (!ActiveSockets.isConnected(mac)) {
                        line("החיבור אבד – התנתק מ-$mac")
                        if (activeDevice === connected) activeDevice = null
                        releaseDevice(connected, mac)
                        break
                    }
                    try {
                        val items = ArrayList<SettingItem>()
                        connected.categories().forEach { category ->
                            connected.settingsInCategory(category).forEach { id ->
                                items.add(SettingItem(category.toString(), id.toString(), connected.setting(id)))
                            }
                        }
                        syncSettings(connected, items)
                        lastRefreshError = ""
                    } catch (t: Throwable) {
                        if (t is CancellationException) throw t
                        val text = describe(t)
                        if (text != lastRefreshError) {
                            lastRefreshError = text
                            line("הרענון נכשל:\n$text")
                        }
                    }
                    delay(refreshIntervalMs)
                }
            } catch (t: Throwable) {
                if (t is CancellationException) throw t
                line("שגיאה:\n" + describe(t))
            }
        }
    }
 
    // Tries to connect up to MAX_CONNECT_ATTEMPTS times. The return type is inferred from
    // connectWithBackends, so no generated type name needs to be guessed.
    private suspend fun connectWithRetry(
        activeSession: OpenScq30Session,
        backends: com.oppzippy.openscq30.lib.bindings.ManualConnectionBackends,
        mac: String,
    ) = run {
        var result = tryConnectOnce(activeSession, backends, mac, 1)
        var attempt = 1
        while (result == null && attempt < MAX_CONNECT_ATTEMPTS) {
            line("מנסה שוב (ניסיון ${attempt + 1} מתוך $MAX_CONNECT_ATTEMPTS)...")
            delay(RETRY_DELAY_MS)
            attempt += 1
            result = tryConnectOnce(activeSession, backends, mac, attempt)
        }
        result
    }
 
    private suspend fun tryConnectOnce(
        activeSession: OpenScq30Session,
        backends: com.oppzippy.openscq30.lib.bindings.ManualConnectionBackends,
        mac: String,
        attempt: Int,
    ) = try {
        activeSession.connectWithBackends(backends, mac)
    } catch (t: Throwable) {
        if (t is CancellationException) throw t
        line("החיבור נכשל (ניסיון $attempt מתוך $MAX_CONNECT_ATTEMPTS):\n" + describe(t))
        try {
            ActiveSockets.close(mac)
        } catch (_: Throwable) {
        }
        null
    }
 
    // ---------- generic settings screen ----------
    // Every setting returned by the core gets a control matching its type. Changing a control sends the new
    // value with device.setSettingValues(listOf(SettingIdValuePair(id, value))).
 
    private fun sendValue(device: Any, key: String, value: Value, description: String) {
        scope.launch {
            try {
                (device as OpenScq30Device).setSettingValues(listOf(SettingIdValuePair(key, value)))
                line("נשלח: $description")
            } catch (t: Throwable) {
                if (t is CancellationException) throw t
                line("השליחה נכשלה:\n" + describe(t))
            }
        }
    }
 
    private fun syncSettings(device: Any, items: List<SettingItem>) {
        val container = settingsContainer ?: return
        for (item in items) {
            val setting = item.setting ?: continue
            var updater = controls[item.key]
            if (updater == null) {
                val built = buildControl(device, item.key, setting)
                if (built == null) {
                    if (loggedSkips.add(item.key)) line("(דילגתי על ${item.key}: ${setting.javaClass.simpleName})")
                    continue
                }
                var section = sections[item.category]
                if (section == null) {
                    section = LinearLayout(this)
                    section.orientation = LinearLayout.VERTICAL
                    val header = TextView(this)
                    header.text = settingName(item.category)
                    header.textSize = 14f
                    header.setPadding(8, 16, 8, 4)
                    section.addView(header)
                    container.addView(section, matchWrap())
                    sections[item.category] = section
                }
                section.addView(built.first, matchWrap())
                controls[item.key] = built.second
                updater = built.second
            }
            updatingUi = true
            try {
                updater(setting)
            } finally {
                updatingUi = false
            }
        }
        reportUntranslated()
    }
 
    private fun buildControl(device: Any, key: String, setting: Any): Pair<View, (Any?) -> Unit>? {
        return when (setting) {
            is Setting.ToggleSetting -> toggleControl(device, key, setting)
            is Setting.I32RangeSetting -> rangeControl(device, key, setting)
            is Setting.EqualizerSetting -> equalizerControl(device, key, setting)
            is Setting.SelectSetting -> choiceControl(
                device, key, setting.setting.options, setting.setting.localizedOptions, false,
                { (it as? Setting.SelectSetting)?.value },
                { Value.StringValue(it ?: "") },
            )
            is Setting.OptionalSelectSetting -> choiceControl(
                device, key, setting.setting.options, setting.setting.localizedOptions, true,
                { (it as? Setting.OptionalSelectSetting)?.value },
                { Value.OptionalStringValue(it) },
            )
            is Setting.PresetEqualizerProfileSelect -> choiceControl(
                device, key, setting.select.options, setting.select.localizedOptions, true,
                { (it as? Setting.PresetEqualizerProfileSelect)?.value },
                { Value.OptionalStringValue(it) },
            )
            is Setting.ModifiableSelectSetting ->
                if (setting.setting.options.isEmpty()) {
                    infoControl(key, "(ריק)") { "(ריק)" }
                } else {
                    choiceControl(
                        device, key, setting.setting.options, setting.setting.localizedOptions, true,
                        { (it as? Setting.ModifiableSelectSetting)?.value },
                        { Value.OptionalStringValue(it) },
                    )
                }
            is Setting.MultiSelectSetting ->
                if (setting.setting.options.isEmpty()) {
                    infoControl(key, "(ריק)") { "(ריק)" }
                } else {
                    multiControl(device, key, setting)
                }
            is Setting.InformationSetting -> infoControl(key, HebrewLabels.value(setting.translatedValue)) {
                HebrewLabels.value((it as? Setting.InformationSetting)?.translatedValue ?: "")
            }
            else -> null
        }
    }
 
    private fun infoControl(key: String, initial: String, textOf: (Any?) -> String): Pair<View, (Any?) -> Unit> {
        val view = TextView(this)
        view.textSize = 13f
        view.setPadding(8, 4, 8, 4)
        view.text = "${settingName(key)}: $initial"
        return Pair(view, { s -> view.text = "${settingName(key)}: ${textOf(s)}" })
    }
 
    private fun toggleControl(device: Any, key: String, setting: Setting.ToggleSetting): Pair<View, (Any?) -> Unit> {
        val box = CheckBox(this)
        box.text = settingName(key)
        box.textSize = 13f
        box.isChecked = setting.value
        box.setOnCheckedChangeListener { _, checked ->
            if (!updatingUi) {
                sendValue(
                    device,
                    key,
                    Value.BoolValue(checked),
                    "${settingName(key)} = ${if (checked) "פועל" else "כבוי"}",
                )
            }
        }
        val updater: (Any?) -> Unit = { s ->
            if (s is Setting.ToggleSetting) box.isChecked = s.value
        }
        return Pair(box, updater)
    }
 
    // Sliders: with touch the value is sent when the finger is lifted. With a keypad there is no "lifted" event,
    // so the value is sent shortly after the last key press.
    private fun rangeControl(device: Any, key: String, setting: Setting.I32RangeSetting): Pair<View, (Any?) -> Unit> {
        val range = setting.setting
        val start = range.start
        val step = if (range.step <= 0) 1 else range.step
        val max = (range.end - start) / step
 
        val root = LinearLayout(this)
        root.orientation = LinearLayout.VERTICAL
        val label = TextView(this)
        label.textSize = 13f
        label.setPadding(8, 4, 8, 0)
        val bar = SeekBar(this)
        bar.layoutDirection = View.LAYOUT_DIRECTION_LTR
        bar.max = max
        bar.progress = (setting.value - start) / step
        label.text = "${settingName(key)}: ${setting.value}"
        var dragging = false
        var pendingSend: Runnable? = null
        val sendNow = Runnable {
            pendingSend = null
            val value = start + bar.progress * step
            sendValue(device, key, Value.I32Value(value), "${settingName(key)} = $value")
        }
        bar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar, progress: Int, fromUser: Boolean) {
                label.text = "${settingName(key)}: ${start + progress * step}"
                if (fromUser && !dragging) {
                    pendingSend?.let { uiHandler.removeCallbacks(it) }
                    pendingSend = sendNow
                    uiHandler.postDelayed(sendNow, KEY_SEND_DELAY_MS)
                }
            }
 
            override fun onStartTrackingTouch(seekBar: SeekBar) {
                dragging = true
            }
 
            override fun onStopTrackingTouch(seekBar: SeekBar) {
                dragging = false
                pendingSend?.let { uiHandler.removeCallbacks(it) }
                pendingSend = null
                val value = start + seekBar.progress * step
                sendValue(device, key, Value.I32Value(value), "${settingName(key)} = $value")
            }
        })
        root.addView(label, matchWrap())
        root.addView(bar, matchWrap())
        val updater: (Any?) -> Unit = { s ->
            if (s is Setting.I32RangeSetting && !dragging && pendingSend == null) {
                bar.progress = (s.value - start) / step
                label.text = "${settingName(key)}: ${s.value}"
            }
        }
        return Pair(root, updater)
    }
 
    private fun equalizerControl(
        device: Any,
        key: String,
        setting: Setting.EqualizerSetting,
    ): Pair<View, (Any?) -> Unit> {
        val eq = setting.setting
        val bandCount = eq.bandHz.size
        val min = eq.min.toInt()
        val max = eq.max.toInt()
        val scale = Math.pow(10.0, eq.fractionDigits.toDouble())
        val values = IntArray(bandCount) { setting.value.getOrElse(it) { 0 }.toInt() }
        val dragging = BooleanArray(bandCount)
        var pendingSend: Runnable? = null
        val sendNow = Runnable {
            pendingSend = null
            sendValue(
                device,
                key,
                Value.I16VecValue(values.map { it.toShort() }),
                settingName(key),
            )
        }
 
        val root = LinearLayout(this)
        root.orientation = LinearLayout.VERTICAL
        val title = TextView(this)
        title.text = settingName(key)
        title.textSize = 13f
        title.setPadding(8, 4, 8, 0)
        root.addView(title)
 
        val labels = ArrayList<TextView>()
        val bars = ArrayList<SeekBar>()
 
        fun labelText(i: Int) =
            "${eq.bandHz[i]} Hz: " + String.format(Locale.US, "%.1f", values[i] / scale)
 
        for (i in 0 until bandCount) {
            val label = TextView(this)
            label.textSize = 11f
            label.setPadding(8, 2, 8, 0)
            label.text = labelText(i)
            val bar = SeekBar(this)
            bar.layoutDirection = View.LAYOUT_DIRECTION_LTR
            bar.max = max - min
            bar.progress = values[i] - min
            bar.isEnabled = !setting.readOnly
            bar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(seekBar: SeekBar, progress: Int, fromUser: Boolean) {
                    values[i] = progress + min
                    label.text = labelText(i)
                    if (fromUser && !dragging[i]) {
                        pendingSend?.let { uiHandler.removeCallbacks(it) }
                        pendingSend = sendNow
                        uiHandler.postDelayed(sendNow, KEY_SEND_DELAY_MS)
                    }
                }
 
                override fun onStartTrackingTouch(seekBar: SeekBar) {
                    dragging[i] = true
                }
 
                override fun onStopTrackingTouch(seekBar: SeekBar) {
                    dragging[i] = false
                    pendingSend?.let { uiHandler.removeCallbacks(it) }
                    pendingSend = null
                    values[i] = seekBar.progress + min
                    sendValue(
                        device,
                        key,
                        Value.I16VecValue(values.map { it.toShort() }),
                        "${settingName(key)} – ${eq.bandHz[i]} Hz",
                    )
                }
            })
            labels.add(label)
            bars.add(bar)
            root.addView(label, matchWrap())
            root.addView(bar, matchWrap())
        }
 
        val updater: (Any?) -> Unit = { s ->
            if (s is Setting.EqualizerSetting && !dragging.any { it } && pendingSend == null) {
                for (i in 0 until bandCount) {
                    values[i] = s.value.getOrElse(i) { 0 }.toInt()
                    bars[i].progress = values[i] - min
                    labels[i].text = labelText(i)
                }
            }
        }
        return Pair(root, updater)
    }
 
    // Buttons for a list of options. With allowNone, an extra "-" button selects "no value".
    private fun choiceControl(
        device: Any,
        key: String,
        options: List<String>,
        labels: List<String>,
        allowNone: Boolean,
        currentOf: (Any?) -> String?,
        makeValue: (String?) -> Value,
    ): Pair<View, (Any?) -> Unit> {
        val root = LinearLayout(this)
        root.orientation = LinearLayout.VERTICAL
 
        val title = TextView(this)
        title.text = settingName(key)
        title.textSize = 13f
        title.setPadding(8, 4, 8, 0)
        root.addView(title)
 
        val entries = ArrayList<Pair<String?, String>>()
        options.forEachIndexed { i, option ->
            entries.add(Pair(option, optionText(option, labels.getOrElse(i) { option })))
        }
        if (allowNone) entries.add(Pair(null, "-"))
 
        val choiceButtons = ArrayList<ChoiceButton>()
        fun highlight(current: String?) {
            choiceButtons.forEach {
                it.button.text = (if (it.option == current) "● " else "") + it.label
            }
        }
 
        entries.chunked(3).forEach { chunk ->
            val row = LinearLayout(this)
            row.orientation = LinearLayout.HORIZONTAL
            chunk.forEach { (option, label) ->
                val button = makeButton(label) {
                    if (!updatingUi) {
                        sendValue(device, key, makeValue(option), "${settingName(key)} = $label")
                        highlight(option)
                    }
                }
                row.addView(button, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
                choiceButtons.add(ChoiceButton(option, label, button))
            }
            root.addView(row, matchWrap())
        }
 
        val updater: (Any?) -> Unit = { s -> highlight(currentOf(s)) }
        return Pair(root, updater)
    }
 
    private fun multiControl(
        device: Any,
        key: String,
        setting: Setting.MultiSelectSetting,
    ): Pair<View, (Any?) -> Unit> {
        val root = LinearLayout(this)
        root.orientation = LinearLayout.VERTICAL
        val title = TextView(this)
        title.text = settingName(key)
        title.textSize = 13f
        title.setPadding(8, 4, 8, 0)
        root.addView(title)
 
        val boxes = ArrayList<Pair<String, CheckBox>>()
        val options = setting.setting.options
        val labels = setting.setting.localizedOptions
        options.forEachIndexed { i, option ->
            val box = CheckBox(this)
            box.text = optionText(option, labels.getOrElse(i) { option })
            box.textSize = 12f
            box.isChecked = option in setting.values
            box.setOnCheckedChangeListener { _, _ ->
                if (!updatingUi) {
                    val selected = boxes.filter { it.second.isChecked }.map { it.first }
                    sendValue(device, key, Value.StringVecValue(selected), "${settingName(key)} עודכן")
                }
            }
            boxes.add(Pair(option, box))
            root.addView(box)
        }
        val updater: (Any?) -> Unit = { s ->
            if (s is Setting.MultiSelectSetting) {
                boxes.forEach { it.second.isChecked = it.first in s.values }
            }
        }
        return Pair(root, updater)
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
        line("גרסה: v7-עדכון")
        line("גרסת אנדרואיד (SDK): ${Build.VERSION.SDK_INT}")
 
        try {
            System.loadLibrary("openscq30_android")
            System.loadLibrary("jnidispatch")
            initNativeLogging()
            initNativeI18n(listOf(LanguageIdentifier("en", null, null, emptyList())))
            nativeReady = true
            line("תקין: ספריית הליבה נטענה")
        } catch (t: Throwable) {
            line("טעינת הליבה נכשלה:\n" + describe(t))
            return
        }
 
        scope.launch {
            try {
                session = newSession(File(filesDir, "openscq30.db").absolutePath)
                line("החיבור למסד הנתונים תקין")
                refreshDeviceList()
                ensureBluetoothEnabled { autoConnect() }
            } catch (t: Throwable) {
                if (t is CancellationException) throw t
                line("פתיחת מסד הנתונים נכשלה:\n" + describe(t))
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
                disconnect("יציאה מהאפליקציה")
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
                disconnect("חזרה")
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
        try {
            BluetoothAdapter.getDefaultAdapter()?.cancelDiscovery()
        } catch (_: Throwable) {
        }
        uiHandler.removeCallbacksAndMessages(null)
        disconnect("סגירת האפליקציה")
        scope.cancel()
        super.onDestroy()
    }
}
 
