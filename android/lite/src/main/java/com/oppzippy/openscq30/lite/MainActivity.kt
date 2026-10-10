package com.oppzippy.openscq30.lite

import android.app.Activity
import android.app.AlertDialog
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.StateListDrawable
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.Editable
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.TextWatcher
import android.text.style.ForegroundColorSpan
import android.view.Gravity
import android.view.KeyEvent
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
import com.oppzippy.openscq30.lib.wrapper.MultiSelectWithRemoveCommandInner
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

        private const val PAGE_SOUND = 0
        private const val PAGE_EQ = 1
        private const val PAGE_BUTTONS = 2
        private const val PAGE_MORE = 3
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

    // ----- connected screen: header, tabs and pages -----
    private val pageTitles = listOf("סאונד", "איקוולייזר", "כפתורים", "עוד")
    private val pages = ArrayList<LinearLayout>()
    private val tabButtons = ArrayList<Button>()
    private var scrollViewMain: ScrollView? = null
    private var titleView: TextView? = null
    private var batteryView: TextView? = null
    private var miniStatus: TextView? = null
    private var moreContainer: LinearLayout? = null
    private var buttonsLeft: LinearLayout? = null
    private var buttonsRight: LinearLayout? = null
    private var buttonsOther: LinearLayout? = null
    private var sideLeftButton: Button? = null
    private var sideRightButton: Button? = null

    // One entry per setting (category/id): a function that refreshes its control from a new value.
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

    // Colors, close to the original Soundcore app.
    private val colAccent = 0xFF1CB5E8.toInt()
    private val colBg = 0xFFF2F4F7.toInt()
    private val colCard = 0xFFFFFFFF.toInt()
    private val colText = 0xFF1B1F24.toInt()
    private val colSubText = 0xFF6B7480.toInt()
    private val colChip = 0xFFE9EDF2.toInt()
    private val colGood = 0xFF2EAE4F.toInt()
    private val colBad = 0xFFE0453A.toInt()

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
            Screen.CONNECTED -> {
                connectionView?.text = statusLines.takeLast(15).joinToString("\n")
                miniStatus?.text = statusLines.lastOrNull()?.lineSequence()?.firstOrNull()?.take(70) ?: ""
            }
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

    // Plain button, used on the device list and model picker screens.
    private fun makeButton(label: String, onClick: () -> Unit): Button {
        val button = Button(this)
        button.text = label
        button.textSize = 12f
        button.setOnClickListener { onClick() }
        return button
    }

    private fun matchWrap() =
        LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)

    // ---------- look and feel helpers (connected screen) ----------

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density + 0.5f).toInt()

    private fun rounded(fill: Int, radiusDp: Int, stroke: Int = 0, strokeDp: Int = 0): GradientDrawable {
        val drawable = GradientDrawable()
        drawable.setColor(fill)
        drawable.cornerRadius = dp(radiusDp).toFloat()
        if (strokeDp > 0) drawable.setStroke(dp(strokeDp), stroke)
        return drawable
    }

    // The focused (keypad) and pressed states get a dark outline, so it is always clear where the focus is.
    private fun chipBackground(selected: Boolean): StateListDrawable {
        val base = if (selected) colAccent else colChip
        val states = StateListDrawable()
        states.addState(intArrayOf(android.R.attr.state_focused), rounded(base, 8, colText, 2))
        states.addState(intArrayOf(android.R.attr.state_pressed), rounded(base, 8, colText, 2))
        states.addState(intArrayOf(), rounded(base, 8))
        return states
    }

    private fun styleChip(button: Button, selected: Boolean) {
        button.setTextColor(if (selected) Color.WHITE else colText)
        button.background = chipBackground(selected)
    }

    private fun chip(label: String, selected: Boolean, onClick: () -> Unit): Button {
        val button = Button(this)
        button.text = label
        button.textSize = 12f
        button.minHeight = dp(34)
        button.minimumHeight = dp(34)
        button.setPadding(dp(6), dp(4), dp(6), dp(4))
        styleChip(button, selected)
        button.setOnClickListener { onClick() }
        return button
    }

    private fun makeText(text: String, size: Float = 13f, color: Int = colText, bold: Boolean = false): TextView {
        val view = TextView(this)
        view.text = text
        view.textSize = size
        view.setTextColor(color)
        if (bold) view.setTypeface(view.typeface, Typeface.BOLD)
        view.setPadding(dp(4), dp(2), dp(4), dp(2))
        return view
    }

    private fun card(child: View): LinearLayout {
        val holder = LinearLayout(this)
        holder.orientation = LinearLayout.VERTICAL
        holder.background = rounded(colCard, 12)
        holder.setPadding(dp(8), dp(6), dp(8), dp(6))
        holder.addView(child, matchWrap())
        return holder
    }

    private fun cardParams(): LinearLayout.LayoutParams {
        val params = matchWrap()
        params.setMargins(dp(6), dp(3), dp(6), dp(3))
        return params
    }

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

    // ---------- packet log ----------

    // Shows the last Bluetooth packets (newest first), to find out what the earbuds really send.
    private fun showPacketLog() {
        val entries = PacketLog.snapshot()
        val body = TextView(this)
        body.layoutDirection = View.LAYOUT_DIRECTION_LTR
        body.typeface = Typeface.MONOSPACE
        body.textSize = 10f
        body.setPadding(12, 12, 12, 12)
        body.text = if (entries.isEmpty()) {
            "אין חבילות עדיין"
        } else {
            entries.takeLast(30).reversed().joinToString("\n")
        }
        val scroll = ScrollView(this)
        scroll.addView(body)
        AlertDialog.Builder(this)
            .setTitle("חבילות Bluetooth (החדשה למעלה)")
            .setView(scroll)
            .setPositiveButton("סגור", null)
            .setNeutralButton("נקה") { _, _ -> PacketLog.clear() }
            .show()
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
        pages.clear()
        tabButtons.clear()

        val root = LinearLayout(this)
        root.orientation = LinearLayout.VERTICAL
        root.setBackgroundColor(colBg)
        rtl(root)

        // Header: back, device name, reconnect.
        val header = LinearLayout(this)
        header.orientation = LinearLayout.HORIZONTAL
        header.gravity = Gravity.CENTER_VERTICAL
        header.setPadding(dp(4), dp(4), dp(4), 0)
        header.addView(
            chip("חזרה", false) { onBackPressed() },
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT),
        )
        val deviceName = bondedRows.firstOrNull { it.mac.equals(mac, ignoreCase = true) }?.name ?: mac
        val title = makeText(deviceName, 15f, colText, true)
        title.gravity = Gravity.CENTER
        titleView = title
        header.addView(title, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        header.addView(
            chip("חיבור מחדש", false) { startConnection(currentMac) },
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT),
        )
        root.addView(header, matchWrap())

        // Battery line and a one line status (the last log message).
        val battery = makeText("", 12f, colSubText)
        battery.gravity = Gravity.CENTER
        batteryView = battery
        root.addView(battery, matchWrap())
        val miniLog = makeText("", 10f, colSubText)
        miniLog.gravity = Gravity.CENTER
        miniLog.maxLines = 2
        miniStatus = miniLog
        root.addView(miniLog, matchWrap())

        // Tabs. Keys 1-4 switch between them.
        val tabs = LinearLayout(this)
        tabs.orientation = LinearLayout.HORIZONTAL
        tabs.setPadding(dp(4), 0, dp(4), dp(4))
        pageTitles.forEachIndexed { index, label ->
            val tab = chip(label, index == 0) { showPage(index) }
            tab.textSize = 11f
            tabButtons.add(tab)
            val params = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            params.setMargins(dp(2), 0, dp(2), 0)
            tabs.addView(tab, params)
        }
        root.addView(tabs, matchWrap())

        // Pages.
        val content = LinearLayout(this)
        content.orientation = LinearLayout.VERTICAL
        pageTitles.forEach { _ ->
            val page = LinearLayout(this)
            page.orientation = LinearLayout.VERTICAL
            pages.add(page)
            content.addView(page, matchWrap())
        }

        // Buttons page: choose an earbud, then its own list. The right earbud chip is added first so that
        // on the right-to-left screen the left earbud is on the left.
        val buttonsPage = pages[PAGE_BUTTONS]
        val sideRow = LinearLayout(this)
        sideRow.orientation = LinearLayout.HORIZONTAL
        val rightChip = chip("אוזנית ימין", false) { showSide(1) }
        val leftChip = chip("אוזנית שמאל", true) { showSide(0) }
        sideRightButton = rightChip
        sideLeftButton = leftChip
        val sideParams = { LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f) }
        val rightParams = sideParams()
        rightParams.setMargins(dp(6), dp(4), dp(3), dp(4))
        val leftParams = sideParams()
        leftParams.setMargins(dp(3), dp(4), dp(6), dp(4))
        sideRow.addView(rightChip, rightParams)
        sideRow.addView(leftChip, leftParams)
        buttonsPage.addView(sideRow, matchWrap())
        val leftBox = LinearLayout(this)
        leftBox.orientation = LinearLayout.VERTICAL
        val rightBox = LinearLayout(this)
        rightBox.orientation = LinearLayout.VERTICAL
        val otherBox = LinearLayout(this)
        otherBox.orientation = LinearLayout.VERTICAL
        buttonsLeft = leftBox
        buttonsRight = rightBox
        buttonsOther = otherBox
        buttonsPage.addView(leftBox, matchWrap())
        buttonsPage.addView(rightBox, matchWrap())
        buttonsPage.addView(otherBox, matchWrap())

        // More page: all remaining settings by category, then the packet log button and the log.
        val morePage = pages[PAGE_MORE]
        val moreSettings = LinearLayout(this)
        moreSettings.orientation = LinearLayout.VERTICAL
        moreContainer = moreSettings
        morePage.addView(moreSettings, matchWrap())
        morePage.addView(chip("חבילות Bluetooth", false) { showPacketLog() }, cardParams())
        val log = makeText("", 10f, colSubText)
        log.setPadding(dp(10), dp(8), dp(10), dp(8))
        connectionView = log
        morePage.addView(log, matchWrap())

        val scroll = ScrollView(this)
        scroll.addView(content)
        scrollViewMain = scroll
        root.addView(scroll, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))

        setContentView(root)
        showSide(0)
        showPage(PAGE_SOUND)
        renderStatus()
    }

    private fun showPage(index: Int) {
        pages.forEachIndexed { i, page -> page.visibility = if (i == index) View.VISIBLE else View.GONE }
        tabButtons.forEachIndexed { i, tab -> styleChip(tab, i == index) }
        scrollViewMain?.scrollTo(0, 0)
    }

    private fun showSide(side: Int) {
        buttonsLeft?.visibility = if (side == 0) View.VISIBLE else View.GONE
        buttonsRight?.visibility = if (side == 1) View.VISIBLE else View.GONE
        sideLeftButton?.let { styleChip(it, side == 0) }
        sideRightButton?.let { styleChip(it, side == 1) }
    }

    // Keys 1-4 switch between the tabs (handy on a keypad phone).
    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        if (currentScreen == Screen.CONNECTED && pages.isNotEmpty()) {
            val index = keyCode - KeyEvent.KEYCODE_1
            if (index >= 0 && index < pages.size) {
                showPage(index)
                return true
            }
        }
        return super.onKeyDown(keyCode, event)
    }

    // Decides which tab a setting belongs to.
    private fun pageOf(category: String, key: String): Int {
        val k = key.lowercase(Locale.US)
        val c = category.lowercase(Locale.US)
        return when {
            c == "buttonconfiguration" || k.endsWith("press") || k.contains("slide") ||
                k == "resetbuttonstodefault" -> PAGE_BUTTONS
            c == "case" -> PAGE_MORE
            c == "equalizer" || c == "equalizerimportexport" || k.contains("equalizer") ||
                k.startsWith("spatialaudio") -> PAGE_EQ
            c == "soundmodes" || k == "airpressure" || k.contains("noisecancel") || k.contains("windnoise") ||
                k.contains("transparency") || k.contains("ambient") || k.contains("airplane") -> PAGE_SOUND
            else -> PAGE_MORE
        }
    }

    private fun placeControl(page: Int, category: String, key: String, view: View) {
        val holder = card(view)
        if (page == PAGE_BUTTONS) {
            val k = key.lowercase(Locale.US)
            val box = when {
                k.startsWith("left") -> buttonsLeft
                k.startsWith("right") -> buttonsRight
                else -> buttonsOther
            }
            box?.addView(holder, cardParams())
            return
        }
        if (page == PAGE_MORE) {
            val container = moreContainer ?: return
            var section = sections[category]
            if (section == null) {
                section = LinearLayout(this)
                section.orientation = LinearLayout.VERTICAL
                section.addView(makeText(settingName(category), 13f, colSubText, true))
                container.addView(section, matchWrap())
                sections[category] = section
            }
            section.addView(holder, cardParams())
            return
        }
        pages.getOrNull(page)?.addView(holder, cardParams())
    }

    // Shows the battery of the left earbud, the right earbud and the case at the top of the screen.
    private fun updateBattery(items: List<SettingItem>) {
        val view = batteryView ?: return
        var left: String? = null
        var right: String? = null
        var caseValue: String? = null
        for (item in items) {
            val setting = item.setting
            if (setting !is Setting.InformationSetting) continue
            val k = item.key.lowercase(Locale.US)
            if (!k.contains("battery")) continue
            when {
                k.contains("case") -> caseValue = setting.translatedValue
                k.contains("left") -> left = setting.translatedValue
                k.contains("right") -> right = setting.translatedValue
            }
        }
        val text = SpannableStringBuilder()
        fun add(label: String, value: String?) {
            if (value == null) return
            if (text.length > 0) text.append("   ")
            text.append(label).append(" ")
            val start = text.length
            text.append(value)
            val percent = Regex("\\d+").find(value)?.value?.toIntOrNull()
            val color = if (percent != null && percent < 20) colBad else colGood
            text.setSpan(ForegroundColorSpan(color), start, text.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
        add("ימין", right)
        add("שמאל", left)
        add("מארז", caseValue)
        view.text = text
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
                    line("ויתרתי אחרי $MAX_CONNECT_ATTEMPTS ניסיונות. לחץ על 'חיבור מחדש' כדי לנסות שוב.")
                    return@launch
                }

                activeDevice = connected
                prefs.edit().putString("last_mac", mac).apply()
                val model = modelName(connected.model())
                titleView?.text = model
                line("מחובר, דגם: $model")

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
        if (pages.isEmpty()) return
        updateBattery(items)
        for (item in items) {
            val setting = item.setting ?: continue
            // The battery levels are shown at the top of the screen, not as controls.
            if (setting is Setting.InformationSetting && item.key.lowercase(Locale.US).contains("battery")) continue
            // The same id can appear in two categories, so the control is identified by both.
            val id = "${item.category}/${item.key}"
            var updater = controls[id]
            if (updater == null) {
                val built = buildControl(device, item.key, setting)
                if (built == null) {
                    if (loggedSkips.add(id)) line("(דילגתי על ${item.key}: ${setting.javaClass.simpleName})")
                    continue
                }
                placeControl(pageOf(item.category, item.key), item.category, item.key, built.first)
                controls[id] = built.second
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
            is Setting.MultiSelectWithRemoveSetting -> removeListControl(device, key, setting)
            is Setting.ImportStringSetting -> importControl(device, key, setting)
            is Setting.Action -> actionControl(device, key)
            is Setting.InformationSetting -> infoControl(key, HebrewLabels.value(setting.translatedValue)) {
                HebrewLabels.value((it as? Setting.InformationSetting)?.translatedValue ?: "")
            }
            else -> null
        }
    }

    private fun infoControl(key: String, initial: String, textOf: (Any?) -> String): Pair<View, (Any?) -> Unit> {
        val view = makeText("${settingName(key)}: $initial", 12f, colText)
        return Pair(view, { s -> view.text = "${settingName(key)}: ${textOf(s)}" })
    }

    private fun toggleControl(device: Any, key: String, setting: Setting.ToggleSetting): Pair<View, (Any?) -> Unit> {
        val box = CheckBox(this)
        box.text = settingName(key)
        box.textSize = 13f
        box.setTextColor(colText)
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

    // A button that runs a one-time action (for example resetting the earbud buttons), after a confirmation.
    // The core does not say which value an Action expects, so true is sent. If it does not work, the log shows why.
    private fun actionControl(device: Any, key: String): Pair<View, (Any?) -> Unit> {
        val button = chip(settingName(key), false) {
            AlertDialog.Builder(this)
                .setTitle(settingName(key))
                .setMessage("לבצע את הפעולה?")
                .setPositiveButton("כן") { _, _ ->
                    sendValue(device, key, Value.BoolValue(true), settingName(key))
                }
                .setNegativeButton("לא", null)
                .show()
        }
        val updater: (Any?) -> Unit = { }
        return Pair(button, updater)
    }

    // A button that opens a box for pasting text, and sends the text to the earbuds' core (for example a
    // list of equalizer profiles to import).
    private fun importControl(
        device: Any,
        key: String,
        setting: Setting.ImportStringSetting,
    ): Pair<View, (Any?) -> Unit> {
        val button = chip(settingName(key), false) {
            val input = EditText(this)
            input.hint = "הדבק כאן את הטקסט לייבוא"
            val builder = AlertDialog.Builder(this)
            builder.setTitle(settingName(key))
            val message = setting.confirmationMessage
            if (message != null) {
                builder.setMessage("שים לב: ייבוא ידרוס פרופילים קיימים בעלי אותו שם.\n\n$message")
            }
            builder.setView(input)
            builder.setPositiveButton("ייבא") { _, _ ->
                val text = input.text.toString().trim()
                if (text.isEmpty()) {
                    toast("לא הוזן טקסט")
                } else {
                    sendValue(device, key, Value.StringValue(text), settingName(key))
                }
            }
            builder.setNegativeButton("ביטול", null)
            builder.show()
        }
        val updater: (Any?) -> Unit = { }
        return Pair(button, updater)
    }

    // A list with a "remove" button next to every item (for example the devices in dual connections).
    // The list is rebuilt only when it changes, so the focus on a keypad phone does not jump every refresh.
    private fun removeListControl(
        device: Any,
        key: String,
        setting: Setting.MultiSelectWithRemoveSetting,
    ): Pair<View, (Any?) -> Unit> {
        val root = LinearLayout(this)
        root.orientation = LinearLayout.VERTICAL
        root.addView(makeText(settingName(key), 13f, colText, true))
        val rows = LinearLayout(this)
        rows.orientation = LinearLayout.VERTICAL
        root.addView(rows, matchWrap())

        val options = setting.setting.options
        val labels = setting.setting.localizedOptions
        fun labelOf(name: String): String {
            val index = options.indexOf(name)
            return if (index >= 0) labels.getOrElse(index) { name } else name
        }

        var shown: List<String>? = null
        fun render(values: List<String>) {
            if (values == shown) return
            shown = values
            rows.removeAllViews()
            if (values.isEmpty()) {
                rows.addView(makeText("(ריק)", 12f, colSubText), matchWrap())
                return
            }
            values.forEach { name ->
                val row = LinearLayout(this)
                row.orientation = LinearLayout.HORIZONTAL
                row.gravity = Gravity.CENTER_VERTICAL
                val text = makeText(labelOf(name), 12f, colText)
                val remove = chip("הסר", false) {
                    AlertDialog.Builder(this)
                        .setTitle(settingName(key))
                        .setMessage("להסיר את ${labelOf(name)}?")
                        .setPositiveButton("הסר") { _, _ ->
                            sendValue(
                                device,
                                key,
                                MultiSelectWithRemoveCommandInner.Remove(name).toValue(),
                                "${settingName(key)} – הוסר ${labelOf(name)}",
                            )
                        }
                        .setNegativeButton("ביטול", null)
                        .show()
                }
                row.addView(text, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
                row.addView(
                    remove,
                    LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT),
                )
                rows.addView(row, matchWrap())
            }
        }
        render(setting.values)
        val updater: (Any?) -> Unit = { s ->
            if (s is Setting.MultiSelectWithRemoveSetting) render(s.values)
        }
        return Pair(root, updater)
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
        val label = makeText("${settingName(key)}: ${setting.value}", 13f, colText, true)
        val bar = SeekBar(this)
        bar.layoutDirection = View.LAYOUT_DIRECTION_LTR
        bar.max = max
        bar.progress = (setting.value - start) / step
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
        root.addView(makeText(settingName(key), 13f, colText, true))

        val labels = ArrayList<TextView>()
        val bars = ArrayList<SeekBar>()

        fun labelText(i: Int) =
            "${eq.bandHz[i]} Hz: " + String.format(Locale.US, "%.1f", values[i] / scale)

        for (i in 0 until bandCount) {
            val label = makeText(labelText(i), 11f, colSubText)
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

    // A choice between options. A short list is shown as buttons (the chosen one is blue). A long list
    // (more than 4 entries) is one button that opens a list window, like the original app does.
    // With allowNone, an extra "-" entry selects "no value".
    private fun choiceControl(
        device: Any,
        key: String,
        options: List<String>,
        labels: List<String>,
        allowNone: Boolean,
        currentOf: (Any?) -> String?,
        makeValue: (String?) -> Value,
    ): Pair<View, (Any?) -> Unit> {
        val entries = ArrayList<Pair<String?, String>>()
        options.forEachIndexed { i, option ->
            entries.add(Pair(option, optionText(option, labels.getOrElse(i) { option })))
        }
        if (allowNone) entries.add(Pair(null, "-"))

        if (entries.size > 4) {
            var current: String? = null
            val button = chip("", false) {}
            fun refresh() {
                val label = entries.firstOrNull { it.first == current }?.second ?: "-"
                button.text = "${settingName(key)}: $label"
            }
            refresh()
            button.setOnClickListener {
                val items: Array<CharSequence> = entries.map { it.second as CharSequence }.toTypedArray()
                val checked = entries.indexOfFirst { it.first == current }
                AlertDialog.Builder(this)
                    .setTitle(settingName(key))
                    .setSingleChoiceItems(items, checked) { dialog, which ->
                        val option = entries[which].first
                        sendValue(device, key, makeValue(option), "${settingName(key)} = ${entries[which].second}")
                        current = option
                        refresh()
                        dialog.dismiss()
                    }
                    .setNegativeButton("ביטול", null)
                    .show()
            }
            val updater: (Any?) -> Unit = { s ->
                current = currentOf(s)
                refresh()
            }
            return Pair(button, updater)
        }

        val root = LinearLayout(this)
        root.orientation = LinearLayout.VERTICAL
        root.addView(makeText(settingName(key), 13f, colText, true))

        val choiceButtons = ArrayList<ChoiceButton>()
        fun highlight(current: String?) {
            choiceButtons.forEach { styleChip(it.button, it.option == current) }
        }

        entries.chunked(2).forEach { chunk ->
            val row = LinearLayout(this)
            row.orientation = LinearLayout.HORIZONTAL
            chunk.forEach { (option, label) ->
                val button = chip(label, false) {
                    if (!updatingUi) {
                        sendValue(device, key, makeValue(option), "${settingName(key)} = $label")
                        highlight(option)
                    }
                }
                val params = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                params.setMargins(dp(2), dp(2), dp(2), dp(2))
                row.addView(button, params)
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
        root.addView(makeText(settingName(key), 13f, colText, true))

        val boxes = ArrayList<Pair<String, CheckBox>>()
        val options = setting.setting.options
        val labels = setting.setting.localizedOptions
        options.forEachIndexed { i, option ->
            val box = CheckBox(this)
            box.text = optionText(option, labels.getOrElse(i) { option })
            box.textSize = 12f
            box.setTextColor(colText)
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
        // Light theme, set here so that AndroidManifest.xml does not need to change.
        setTheme(android.R.style.Theme_Holo_Light)
        super.onCreate(savedInstanceState)

        val filter = IntentFilter()
        filter.addAction(BluetoothDevice.ACTION_FOUND)
        filter.addAction(BluetoothDevice.ACTION_BOND_STATE_CHANGED)
        filter.addAction(BluetoothAdapter.ACTION_DISCOVERY_FINISHED)
        registerReceiver(scanReceiver, filter)

        showList()
        line("גרסה: v10-ממשק")
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
