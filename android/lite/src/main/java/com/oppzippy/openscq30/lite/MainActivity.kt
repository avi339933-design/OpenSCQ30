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
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.StateListDrawable
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.Editable
import android.text.TextWatcher
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.Switch
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
        private const val MAX_SEGMENTS = 11

        private const val PAGE_SOUND = 0
        private const val PAGE_EQ = 1
        private const val PAGE_BUTTONS = 2
        private const val PAGE_MORE = 3
    }

    private enum class Screen { LIST, PICKER, CONNECTED }

    // bonded = already paired in Android's Bluetooth. Rows with bonded=false are nearby devices found by scanning.
    private data class Row(val name: String, val mac: String, val model: String?, val bonded: Boolean = true)

    private class SettingItem(val category: String, val key: String, val setting: Any?)

    private class TabItem(val root: LinearLayout, val icon: IconView, val underline: View)

    private class BatteryBlock(val root: LinearLayout, val percent: TextView, val fill: View, val rest: View)

    private class RadioRow(val option: String?, val root: LinearLayout, val dot: View, val label: TextView)

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val prefs by lazy { getSharedPreferences("lite", MODE_PRIVATE) }
    private val uiHandler = Handler(Looper.getMainLooper())

    // false: the Android status bar stays visible, translucent and drawn over the top of the app (the app adds
    // padding under it, so nothing is hidden). true: the status bar is hidden completely, for the most space.
    private val hideStatusBar = false

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
    private var cardsContainer: LinearLayout? = null
    private var lastRowsSignature = ""

    // ----- connected screen: header, tabs and pages -----
    private val pages = ArrayList<LinearLayout>()
    private val tabItems = ArrayList<TabItem>()
    private val batteryBlocks = HashMap<String, BatteryBlock>()
    private var currentPage = PAGE_SOUND
    private var scrollViewMain: ScrollView? = null
    private var titleView: TextView? = null
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
    private val colAccentDark = 0xFF2A7DE1.toInt()
    private val colAccentSoft = 0xFFE6F6FD.toInt()
    private val colBg = 0xFFF2F5FA.toInt()
    private val colCard = 0xFFFFFFFF.toInt()
    private val colStroke = 0xFFE1E7F0.toInt()
    private val colRadioOff = 0xFFB8C2D0.toInt()
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
                // The header shows only the latest short status, not the long "missing translation" list
                // (that list stays in the log on the "more" page).
                miniStatus?.text = statusLines.lastOrNull { !it.startsWith("חסר תרגום") }
                    ?.lineSequence()?.firstOrNull()?.take(60) ?: ""
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

    private fun matchWrap() =
        LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)

    private fun weightParams(weight: Float = 1f) =
        LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, weight)

    private fun wrapParams() =
        LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)

    // ---------- look and feel helpers ----------

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density + 0.5f).toInt()

    // The window is drawn under a translucent status bar (see onCreate), so the top of every screen gets this
    // much padding and nothing is hidden behind the bar. It is 0 when the status bar is hidden completely.
    private fun topInset(): Int {
        if (hideStatusBar) return 0
        val id = resources.getIdentifier("status_bar_height", "dimen", "android")
        return if (id > 0) resources.getDimensionPixelSize(id) else dp(24)
    }

    private fun rounded(fill: Int, radiusDp: Int, stroke: Int = 0, strokeDp: Int = 0): GradientDrawable {
        val drawable = GradientDrawable()
        drawable.setColor(fill)
        drawable.cornerRadius = dp(radiusDp).toFloat()
        if (strokeDp > 0) drawable.setStroke(dp(strokeDp), stroke)
        return drawable
    }

    // The blue gradient used for selected buttons and the level bar, like the original app.
    private fun gradientRounded(radiusDp: Int, stroke: Int = 0, strokeDp: Int = 0): GradientDrawable {
        val drawable = GradientDrawable(
            GradientDrawable.Orientation.LEFT_RIGHT,
            intArrayOf(colAccent, colAccentDark),
        )
        drawable.cornerRadius = dp(radiusDp).toFloat()
        if (strokeDp > 0) drawable.setStroke(dp(strokeDp), stroke)
        return drawable
    }

    private fun ovalDrawable(fill: Int, stroke: Int, strokeDp: Int): GradientDrawable {
        val drawable = GradientDrawable()
        drawable.shape = GradientDrawable.OVAL
        drawable.setColor(fill)
        if (strokeDp > 0) drawable.setStroke(dp(strokeDp), stroke)
        return drawable
    }

    // The focused (keypad) and pressed states get a dark outline, so it is always clear where the focus is.
    private fun chipBackground(selected: Boolean): StateListDrawable {
        val states = StateListDrawable()
        if (selected) {
            states.addState(intArrayOf(android.R.attr.state_focused), gradientRounded(10, colText, 2))
            states.addState(intArrayOf(android.R.attr.state_pressed), gradientRounded(10, colText, 2))
            states.addState(intArrayOf(), gradientRounded(10))
        } else {
            states.addState(intArrayOf(android.R.attr.state_focused), rounded(colChip, 10, colText, 2))
            states.addState(intArrayOf(android.R.attr.state_pressed), rounded(colChip, 10, colText, 2))
            states.addState(intArrayOf(), rounded(colChip, 10))
        }
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
        button.setPadding(dp(8), dp(4), dp(8), dp(4))
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
        holder.background = rounded(colCard, 14, colStroke, 1)
        holder.setPadding(dp(8), dp(6), dp(8), dp(6))
        holder.addView(child, matchWrap())
        return holder
    }

    private fun cardParams(): LinearLayout.LayoutParams {
        val params = matchWrap()
        params.setMargins(dp(8), dp(4), dp(8), dp(4))
        return params
    }

    // A device card on the "my devices" screen: outlined in blue while it has the keypad focus.
    private fun cardBackground(): StateListDrawable {
        val states = StateListDrawable()
        states.addState(intArrayOf(android.R.attr.state_focused), rounded(colAccentSoft, 14, colAccent, 2))
        states.addState(intArrayOf(android.R.attr.state_pressed), rounded(colAccentSoft, 14, colAccent, 2))
        states.addState(intArrayOf(), rounded(colCard, 14, colStroke, 1))
        return states
    }

    // A tappable row (focusable with the keypad), highlighted when selected and outlined when focused.
    private fun rowBackground(selected: Boolean): StateListDrawable {
        val states = StateListDrawable()
        states.addState(intArrayOf(android.R.attr.state_focused), rounded(colAccentSoft, 10, colAccent, 2))
        states.addState(intArrayOf(android.R.attr.state_pressed), rounded(colAccentSoft, 10, colAccent, 2))
        states.addState(
            intArrayOf(),
            if (selected) rounded(colAccentSoft, 10) else ColorDrawable(Color.TRANSPARENT),
        )
        return states
    }

    // One segment of the level bar: a white rounded block when selected, outlined when it has the keypad focus.
    private fun segmentBackground(selected: Boolean): StateListDrawable {
        val states = StateListDrawable()
        val focusFill = if (selected) Color.WHITE else 0x66FFFFFF
        states.addState(intArrayOf(android.R.attr.state_focused), rounded(focusFill, 8, colText, 2))
        states.addState(intArrayOf(android.R.attr.state_pressed), rounded(focusFill, 8, colText, 2))
        states.addState(
            intArrayOf(),
            if (selected) rounded(Color.WHITE, 8) else ColorDrawable(Color.TRANSPARENT),
        )
        return states
    }

    private fun makeRow(): LinearLayout {
        val row = LinearLayout(this)
        row.orientation = LinearLayout.HORIZONTAL
        row.gravity = Gravity.CENTER_VERTICAL
        row.isFocusable = true
        row.isClickable = true
        row.setPadding(dp(8), dp(8), dp(8), dp(8))
        row.background = rowBackground(false)
        return row
    }

    // A focusable icon button for the top bar.
    private fun iconButton(kind: IconKind, onClick: () -> Unit): LinearLayout {
        val holder = LinearLayout(this)
        holder.gravity = Gravity.CENTER
        holder.isFocusable = true
        holder.isClickable = true
        holder.background = rowBackground(false)
        holder.setPadding(dp(8), dp(8), dp(8), dp(8))
        holder.addView(IconView(this, kind, colText), LinearLayout.LayoutParams(dp(24), dp(24)))
        holder.setOnClickListener { onClick() }
        return holder
    }

    private fun styleRadio(radio: RadioRow, selected: Boolean) {
        radio.dot.background = if (selected) {
            ovalDrawable(colAccent, Color.WHITE, 3)
        } else {
            ovalDrawable(Color.WHITE, colRadioOff, 2)
        }
        radio.label.setTextColor(colText)
        radio.label.setTypeface(null, if (selected) Typeface.BOLD else Typeface.NORMAL)
        radio.root.background = rowBackground(selected)
    }

    private fun makeSwitch(checked: Boolean): Switch {
        val toggle = Switch(this)
        toggle.textOn = ""
        toggle.textOff = ""
        toggle.isChecked = checked
        return toggle
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

    // ---------- "my devices" screen ----------

    private fun showList() {
        currentScreen = Screen.LIST
        connectionView = null
        miniStatus = null
        lastRowsSignature = ""

        val showAllButton = chip(if (showAll) "הכל: פעיל" else "הכל: כבוי", showAll) {}
        showAllButton.setOnClickListener {
            showAll = !showAll
            showAllButton.text = if (showAll) "הכל: פעיל" else "הכל: כבוי"
            styleChip(showAllButton, showAll)
            refreshDeviceList()
        }

        val actions = LinearLayout(this)
        actions.orientation = LinearLayout.HORIZONTAL
        actions.setPadding(dp(6), dp(2), dp(6), dp(2))
        fun addAction(button: Button) {
            val params = weightParams()
            params.setMargins(dp(2), 0, dp(2), 0)
            actions.addView(button, params)
        }
        addAction(chip("סרוק", false) { startScan() })
        addAction(chip("רענן", false) { refreshDeviceList() })
        addAction(showAllButton)
        addAction(
            chip("עדכון", false) {
                UpdateManager.showUpdateDialog(this, { disconnect("עדכון") }, { line(it) })
            },
        )

        val container = LinearLayout(this)
        container.orientation = LinearLayout.VERTICAL
        container.setPadding(0, dp(2), 0, dp(8))
        cardsContainer = container
        val scroll = ScrollView(this)
        scroll.addView(container)

        val logo = makeText("soundcore", 20f, colAccent, true)
        logo.gravity = Gravity.CENTER
        logo.setTypeface(logo.typeface, Typeface.BOLD_ITALIC)
        val heading = makeText("המכשירים שלי", 14f, colSubText)
        heading.setPadding(dp(14), dp(2), dp(14), dp(2))

        val status = TextView(this)
        status.textSize = 10f
        status.setTextColor(colSubText)
        status.setPadding(dp(8), dp(4), dp(8), dp(4))
        statusView = status

        val root = LinearLayout(this)
        root.orientation = LinearLayout.VERTICAL
        root.setBackgroundColor(colBg)
        root.setPadding(0, topInset(), 0, 0)
        rtl(root)
        root.addView(logo, matchWrap())
        root.addView(heading, matchWrap())
        root.addView(actions, matchWrap())
        root.addView(scroll, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        root.addView(status, matchWrap())
        setContentView(root)

        renderCards()
        renderStatus()
        refreshDeviceList()
    }

    // Builds one card per device. The cards are rebuilt only when the list really changed, so the keypad focus
    // does not jump while the scan keeps reporting the same devices.
    private fun renderCards() {
        val container = cardsContainer ?: return
        val signature = deviceRows.joinToString("|") { "${it.mac}/${it.name}/${it.model}/${it.bonded}" }
        if (signature == lastRowsSignature) return
        lastRowsSignature = signature
        container.removeAllViews()
        if (deviceRows.isEmpty()) {
            val empty = makeText("אין מכשירים להצגה", 12f, colSubText)
            empty.setPadding(dp(14), dp(10), dp(14), dp(10))
            container.addView(empty, matchWrap())
            return
        }
        deviceRows.forEach { row ->
            val card = LinearLayout(this)
            card.orientation = LinearLayout.HORIZONTAL
            card.gravity = Gravity.CENTER_VERTICAL
            card.isFocusable = true
            card.isClickable = true
            card.background = cardBackground()
            card.setPadding(dp(12), dp(12), dp(12), dp(12))

            card.addView(EarbudsView(this, colAccentSoft, colAccent), LinearLayout.LayoutParams(dp(76), dp(56)))

            val title = if (row.model != null) {
                modelName(row.model)
            } else {
                row.name.ifEmpty { "לא ידוע" }
            }
            val status = when {
                !row.bonded -> "בקרבת מקום – לחץ לזיווג"
                row.model != null -> "לא מחובר"
                else -> "לחץ לבחירת דגם"
            }
            val texts = LinearLayout(this)
            texts.orientation = LinearLayout.VERTICAL
            texts.addView(makeText(title, 15f, colText, true), matchWrap())
            texts.addView(makeText(status, 12f, colSubText), matchWrap())
            if (row.bonded && row.model != null && row.name.isNotEmpty() && row.name != title) {
                texts.addView(makeText(row.name, 10f, colSubText), matchWrap())
            }
            val textParams = weightParams()
            textParams.marginStart = dp(12)
            card.addView(texts, textParams)

            card.setOnClickListener { onDeviceChosen(row) }
            card.setOnLongClickListener {
                if (row.bonded) showDeviceMenu(row)
                true
            }
            container.addView(card, cardParams())
        }
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
        renderCards()
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
        cardsContainer = null

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
        root.setPadding(0, topInset(), 0, 0)
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

    // One battery column: a label with the percentage, and a thin bar under it.
    private fun batteryBlock(label: String): BatteryBlock {
        val root = LinearLayout(this)
        root.orientation = LinearLayout.VERTICAL
        root.setPadding(dp(8), dp(2), dp(8), dp(2))

        val top = LinearLayout(this)
        top.orientation = LinearLayout.HORIZONTAL
        top.addView(makeText(label, 10f, colSubText), weightParams())
        val percent = makeText("", 11f, colText, true)
        top.addView(percent, wrapParams())
        root.addView(top, matchWrap())

        val track = LinearLayout(this)
        track.orientation = LinearLayout.HORIZONTAL
        track.background = rounded(colStroke, 3)
        val fill = View(this)
        val rest = View(this)
        track.addView(fill, LinearLayout.LayoutParams(0, dp(4), 0f))
        track.addView(rest, LinearLayout.LayoutParams(0, dp(4), 100f))
        val trackParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(4))
        trackParams.topMargin = dp(2)
        root.addView(track, trackParams)
        return BatteryBlock(root, percent, fill, rest)
    }

    private fun setBattery(block: BatteryBlock?, value: String?) {
        if (block == null) return
        if (value == null) {
            block.root.visibility = View.INVISIBLE
            return
        }
        block.root.visibility = View.VISIBLE
        val percent = (Regex("\\d+").find(value)?.value?.toIntOrNull() ?: 0).coerceIn(0, 100)
        block.percent.text = value
        block.fill.background = rounded(if (percent < 20) colBad else colGood, 3)
        (block.fill.layoutParams as LinearLayout.LayoutParams).weight = percent.toFloat()
        (block.rest.layoutParams as LinearLayout.LayoutParams).weight = (100 - percent).toFloat()
        block.fill.requestLayout()
        block.rest.requestLayout()
    }

    // An icon tab with a blue line under the selected one.
    private fun makeTab(kind: IconKind, onClick: () -> Unit): TabItem {
        val root = LinearLayout(this)
        root.orientation = LinearLayout.VERTICAL
        root.gravity = Gravity.CENTER_HORIZONTAL
        root.isFocusable = true
        root.isClickable = true
        root.background = rowBackground(false)
        root.setPadding(0, dp(6), 0, dp(2))
        val icon = IconView(this, kind, colSubText)
        val iconParams = LinearLayout.LayoutParams(dp(26), dp(26))
        iconParams.gravity = Gravity.CENTER_HORIZONTAL
        root.addView(icon, iconParams)
        val underline = View(this)
        val lineParams = LinearLayout.LayoutParams(dp(24), dp(3))
        lineParams.gravity = Gravity.CENTER_HORIZONTAL
        lineParams.topMargin = dp(4)
        root.addView(underline, lineParams)
        root.setOnClickListener { onClick() }
        return TabItem(root, icon, underline)
    }

    private fun styleTab(tab: TabItem, selected: Boolean) {
        tab.icon.setTint(if (selected) colAccent else colSubText)
        tab.underline.setBackgroundColor(if (selected) colAccent else Color.TRANSPARENT)
    }

    private fun showConnectedScreen(mac: String) {
        currentScreen = Screen.CONNECTED
        currentMac = mac
        statusView = null
        cardsContainer = null
        controls.clear()
        sections.clear()
        loggedSkips.clear()
        lastRefreshError = ""
        reportedUntranslated = 0
        pages.clear()
        tabItems.clear()
        batteryBlocks.clear()

        val root = LinearLayout(this)
        root.orientation = LinearLayout.VERTICAL
        root.setBackgroundColor(colBg)
        rtl(root)

        // White header: top bar, earbuds picture, batteries, one status line and the tabs. The white continues
        // up under the (translucent) status bar of the phone.
        val header = LinearLayout(this)
        header.orientation = LinearLayout.VERTICAL
        header.setBackgroundColor(colCard)
        header.setPadding(0, topInset(), 0, 0)

        // Top bar: back, device name, reconnect, and the gear that opens the "more" page.
        val topRow = LinearLayout(this)
        topRow.orientation = LinearLayout.HORIZONTAL
        topRow.gravity = Gravity.CENTER_VERTICAL
        topRow.setPadding(dp(4), dp(2), dp(4), 0)
        topRow.addView(iconButton(IconKind.BACK) { onBackPressed() }, wrapParams())
        val deviceName = bondedRows.firstOrNull { it.mac.equals(mac, ignoreCase = true) }?.name ?: mac
        val title = makeText(deviceName, 16f, colText, true)
        title.gravity = Gravity.CENTER
        titleView = title
        topRow.addView(title, weightParams())
        topRow.addView(iconButton(IconKind.REFRESH) { startConnection(currentMac) }, wrapParams())
        topRow.addView(iconButton(IconKind.GEAR) { showPage(PAGE_MORE) }, wrapParams())
        header.addView(topRow, matchWrap())

        // The case in the middle, the left earbud on its left and the right earbud on its right.
        val hero = EarbudsView(this, colAccentSoft, colAccent)
        val heroParams = LinearLayout.LayoutParams(dp(100), dp(72))
        heroParams.gravity = Gravity.CENTER_HORIZONTAL
        heroParams.topMargin = dp(2)
        header.addView(hero, heroParams)

        // Batteries. The first child is on the right, so the order on screen is: left earbud, right earbud, case.
        val batteryRow = LinearLayout(this)
        batteryRow.orientation = LinearLayout.HORIZONTAL
        batteryRow.setPadding(dp(8), dp(6), dp(8), 0)
        listOf("case" to "מארז", "right" to "ימין", "left" to "שמאל").forEach { (id, label) ->
            val block = batteryBlock(label)
            block.root.visibility = View.INVISIBLE
            batteryBlocks[id] = block
            batteryRow.addView(block.root, weightParams())
        }
        header.addView(batteryRow, matchWrap())

        val miniLog = makeText("", 10f, colSubText)
        miniLog.gravity = Gravity.CENTER
        miniLog.maxLines = 1
        miniStatus = miniLog
        header.addView(miniLog, matchWrap())

        // Tabs (icons only). Keys 1-4 switch between the pages; the 4th page ("more") opens with the gear.
        val tabs = LinearLayout(this)
        tabs.orientation = LinearLayout.HORIZONTAL
        tabs.setPadding(dp(8), dp(2), dp(8), 0)
        listOf(IconKind.SOUND, IconKind.EQUALIZER, IconKind.TOUCH).forEachIndexed { index, kind ->
            val tab = makeTab(kind) { showPage(index) }
            tabItems.add(tab)
            tabs.addView(tab.root, weightParams())
        }
        header.addView(tabs, matchWrap())
        root.addView(header, matchWrap())

        val divider = View(this)
        divider.setBackgroundColor(colStroke)
        root.addView(divider, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(1)))

        // Pages.
        val content = LinearLayout(this)
        content.orientation = LinearLayout.VERTICAL
        content.setPadding(0, dp(6), 0, dp(16))
        for (i in 0..PAGE_MORE) {
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
        val rightParams = weightParams()
        rightParams.setMargins(dp(8), dp(4), dp(3), dp(4))
        val leftParams = weightParams()
        leftParams.setMargins(dp(3), dp(4), dp(8), dp(4))
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
        currentPage = index
        pages.forEachIndexed { i, page -> page.visibility = if (i == index) View.VISIBLE else View.GONE }
        tabItems.forEachIndexed { i, tab -> styleTab(tab, i == index) }
        scrollViewMain?.scrollTo(0, 0)
    }

    private fun showSide(side: Int) {
        buttonsLeft?.visibility = if (side == 0) View.VISIBLE else View.GONE
        buttonsRight?.visibility = if (side == 1) View.VISIBLE else View.GONE
        sideLeftButton?.let { styleChip(it, side == 0) }
        sideRightButton?.let { styleChip(it, side == 1) }
    }

    // Keys 1-4 switch between the pages (handy on a keypad phone).
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
                val head = LinearLayout(this)
                head.orientation = LinearLayout.HORIZONTAL
                head.gravity = Gravity.CENTER_VERTICAL
                head.setPadding(dp(12), dp(10), dp(12), dp(2))
                val bar = View(this)
                bar.background = rounded(colAccent, 2)
                val barParams = LinearLayout.LayoutParams(dp(4), dp(14))
                barParams.marginEnd = dp(6)
                head.addView(bar, barParams)
                head.addView(makeText(settingName(category), 13f, colText, true), wrapParams())
                section.addView(head, matchWrap())
                container.addView(section, matchWrap())
                sections[category] = section
            }
            section.addView(holder, cardParams())
            return
        }
        pages.getOrNull(page)?.addView(holder, cardParams())
    }

    // Shows the battery of the left earbud, the right earbud and the case in the header.
    private fun updateBattery(items: List<SettingItem>) {
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
        setBattery(batteryBlocks["right"], right)
        setBattery(batteryBlocks["left"], left)
        setBattery(batteryBlocks["case"], caseValue)
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
            // The battery levels are shown in the header, not as controls.
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

    // Read-only information: the name on one side and the value on the other.
    // The air pressure gets its own light-blue box with a gauge icon, like the original app.
    private fun infoControl(key: String, initial: String, textOf: (Any?) -> String): Pair<View, (Any?) -> Unit> {
        if (key.lowercase(Locale.US).contains("airpressure")) return pressureControl(initial, textOf)
        val row = LinearLayout(this)
        row.orientation = LinearLayout.HORIZONTAL
        row.gravity = Gravity.CENTER_VERTICAL
        row.setPadding(dp(4), dp(4), dp(4), dp(4))
        val name = makeText(settingName(key), 12f, colSubText)
        val value = makeText(initial, 12f, colText, true)
        row.addView(name, weightParams())
        row.addView(value, wrapParams())
        return Pair(row, { s -> value.text = textOf(s) })
    }

    private fun pressureControl(initial: String, textOf: (Any?) -> String): Pair<View, (Any?) -> Unit> {
        val box = LinearLayout(this)
        box.orientation = LinearLayout.HORIZONTAL
        box.gravity = Gravity.CENTER_VERTICAL
        box.background = rounded(colAccentSoft, 12)
        box.setPadding(dp(10), dp(10), dp(10), dp(10))

        val iconHolder = LinearLayout(this)
        iconHolder.gravity = Gravity.CENTER
        iconHolder.background = ovalDrawable(0xFFCDEEFA.toInt(), 0, 0)
        iconHolder.addView(IconView(this, IconKind.GAUGE, colAccent), LinearLayout.LayoutParams(dp(26), dp(26)))
        val iconParams = LinearLayout.LayoutParams(dp(44), dp(44))
        iconParams.marginEnd = dp(10)
        box.addView(iconHolder, iconParams)

        val texts = LinearLayout(this)
        texts.orientation = LinearLayout.VERTICAL
        val value = makeText(initial, 15f, colText, true)
        texts.addView(value, matchWrap())
        texts.addView(makeText("לחץ אוויר נוכחי", 11f, colSubText), matchWrap())
        box.addView(texts, weightParams())
        return Pair(box, { s -> value.text = textOf(s) })
    }

    private fun toggleControl(device: Any, key: String, setting: Setting.ToggleSetting): Pair<View, (Any?) -> Unit> {
        val row = LinearLayout(this)
        row.orientation = LinearLayout.HORIZONTAL
        row.gravity = Gravity.CENTER_VERTICAL
        row.setPadding(dp(4), dp(2), dp(4), dp(2))
        val name = makeText(settingName(key), 13f, colText)
        val toggle = makeSwitch(setting.value)
        toggle.setOnCheckedChangeListener { _, checked ->
            if (!updatingUi) {
                sendValue(
                    device,
                    key,
                    Value.BoolValue(checked),
                    "${settingName(key)} = ${if (checked) "פועל" else "כבוי"}",
                )
            }
        }
        row.addView(name, weightParams())
        row.addView(toggle, wrapParams())
        // Tapping the name toggles the switch too.
        row.isClickable = true
        row.setOnClickListener { toggle.toggle() }
        val updater: (Any?) -> Unit = { s ->
            if (s is Setting.ToggleSetting) toggle.isChecked = s.value
        }
        return Pair(row, updater)
    }

    // A button that runs a one-time action (for example resetting the earbud buttons), after a confirmation.
    // The core does not say which value an Action expects, so true is sent. If it does not work, the log shows why.
    private fun actionControl(device: Any, key: String): Pair<View, (Any?) -> Unit> {
        val button = chip(settingName(key), true) {
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
        val button = chip(settingName(key), true) {
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
                row.addView(text, weightParams())
                row.addView(remove, wrapParams())
                rows.addView(row, matchWrap())
            }
        }
        render(setting.values)
        val updater: (Any?) -> Unit = { s ->
            if (s is Setting.MultiSelectWithRemoveSetting) render(s.values)
        }
        return Pair(root, updater)
    }

    // A level bar for a short number range (up to MAX_SEGMENTS values): a blue gradient bar split into numbered
    // segments, like the original app. Left/right keys move the focus between segments and OK selects one.
    private fun segmentedRange(
        device: Any,
        key: String,
        setting: Setting.I32RangeSetting,
    ): Pair<View, (Any?) -> Unit> {
        val range = setting.setting
        val start = range.start
        val step = if (range.step <= 0) 1 else range.step
        val count = (range.end - start) / step + 1

        val root = LinearLayout(this)
        root.orientation = LinearLayout.VERTICAL
        root.addView(makeText(settingName(key), 13f, colText, true))

        val bar = LinearLayout(this)
        bar.orientation = LinearLayout.HORIZONTAL
        bar.layoutDirection = View.LAYOUT_DIRECTION_LTR
        bar.background = gradientRounded(10)
        bar.setPadding(dp(3), dp(3), dp(3), dp(3))

        val segments = ArrayList<Pair<Int, TextView>>()
        var shown = Int.MIN_VALUE
        fun highlight(current: Int) {
            if (current == shown) return
            shown = current
            segments.forEach { (number, view) ->
                val selected = number == current
                view.background = segmentBackground(selected)
                view.setTextColor(if (selected) colAccentDark else Color.WHITE)
                view.setTypeface(null, if (selected) Typeface.BOLD else Typeface.NORMAL)
            }
        }

        for (i in 0 until count) {
            val number = start + i * step
            val view = TextView(this)
            view.text = "$number"
            view.textSize = 12f
            view.gravity = Gravity.CENTER
            view.minHeight = dp(34)
            view.isFocusable = true
            view.isClickable = true
            view.setOnClickListener {
                if (!updatingUi) {
                    sendValue(device, key, Value.I32Value(number), "${settingName(key)} = $number")
                    highlight(number)
                }
            }
            segments.add(Pair(number, view))
            bar.addView(view, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        }
        highlight(setting.value)
        root.addView(bar, matchWrap())

        val updater: (Any?) -> Unit = { s ->
            if (s is Setting.I32RangeSetting) highlight(s.value)
        }
        return Pair(root, updater)
    }

    // Sliders: with touch the value is sent when the finger is lifted. With a keypad there is no "lifted" event,
    // so the value is sent shortly after the last key press. Short ranges use the level bar instead.
    private fun rangeControl(device: Any, key: String, setting: Setting.I32RangeSetting): Pair<View, (Any?) -> Unit> {
        val range = setting.setting
        val start = range.start
        val step = if (range.step <= 0) 1 else range.step
        val max = (range.end - start) / step
        if (max + 1 <= MAX_SEGMENTS) return segmentedRange(device, key, setting)

        val root = LinearLayout(this)
        root.orientation = LinearLayout.VERTICAL
        val head = LinearLayout(this)
        head.orientation = LinearLayout.HORIZONTAL
        val name = makeText(settingName(key), 13f, colText)
        val valueText = makeText("${setting.value}", 13f, colAccentDark, true)
        head.addView(name, weightParams())
        head.addView(valueText, wrapParams())
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
                valueText.text = "${start + progress * step}"
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
        root.addView(head, matchWrap())
        root.addView(bar, matchWrap())
        val updater: (Any?) -> Unit = { s ->
            if (s is Setting.I32RangeSetting && !dragging && pendingSend == null) {
                bar.progress = (s.value - start) / step
                valueText.text = "${s.value}"
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

        val valueTexts = ArrayList<TextView>()
        val bars = ArrayList<SeekBar>()

        fun valueText(i: Int) = String.format(Locale.US, "%.1f", values[i] / scale)

        for (i in 0 until bandCount) {
            val head = LinearLayout(this)
            head.orientation = LinearLayout.HORIZONTAL
            head.addView(makeText("${eq.bandHz[i]} Hz", 11f, colSubText), weightParams())
            val valueView = makeText(valueText(i), 11f, colAccentDark, true)
            head.addView(valueView, wrapParams())
            val bar = SeekBar(this)
            bar.layoutDirection = View.LAYOUT_DIRECTION_LTR
            bar.max = max - min
            bar.progress = values[i] - min
            bar.isEnabled = !setting.readOnly
            bar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(seekBar: SeekBar, progress: Int, fromUser: Boolean) {
                    values[i] = progress + min
                    valueView.text = valueText(i)
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
            valueTexts.add(valueView)
            bars.add(bar)
            root.addView(head, matchWrap())
            root.addView(bar, matchWrap())
        }

        val updater: (Any?) -> Unit = { s ->
            if (s is Setting.EqualizerSetting && !dragging.any { it } && pendingSend == null) {
                for (i in 0 until bandCount) {
                    values[i] = s.value.getOrElse(i) { 0 }.toInt()
                    bars[i].progress = values[i] - min
                    valueTexts[i].text = valueText(i)
                }
            }
        }
        return Pair(root, updater)
    }

    // A choice between options.
    // - Exactly two options: two side-by-side buttons, the chosen one in the blue gradient.
    // - Up to 4 options: rows with a round selector, like the original app.
    // - A longer list: one row that shows the current choice and opens a list window.
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

        if (!allowNone && entries.size == 2) {
            val pillRoot = LinearLayout(this)
            pillRoot.orientation = LinearLayout.VERTICAL
            pillRoot.addView(makeText(settingName(key), 13f, colSubText, true))
            val pillRow = LinearLayout(this)
            pillRow.orientation = LinearLayout.HORIZONTAL
            val pills = ArrayList<Pair<String?, Button>>()
            fun highlightPills(current: String?) {
                pills.forEach { styleChip(it.second, it.first == current) }
            }
            entries.forEach { (option, label) ->
                val button = chip(label, false) {
                    if (!updatingUi) {
                        sendValue(device, key, makeValue(option), "${settingName(key)} = $label")
                        highlightPills(option)
                    }
                }
                val params = weightParams()
                params.setMargins(dp(3), dp(3), dp(3), dp(3))
                pillRow.addView(button, params)
                pills.add(Pair(option, button))
            }
            pillRoot.addView(pillRow, matchWrap())
            val pillUpdater: (Any?) -> Unit = { s -> highlightPills(currentOf(s)) }
            return Pair(pillRoot, pillUpdater)
        }

        if (entries.size > 4) {
            var current: String? = null
            val row = makeRow()
            val name = makeText(settingName(key), 13f, colText)
            val value = makeText("-", 12f, colAccentDark, true)
            val arrow = makeText("‹", 18f, colSubText)
            row.addView(name, weightParams())
            row.addView(value, wrapParams())
            row.addView(arrow, wrapParams())
            fun refresh() {
                value.text = entries.firstOrNull { it.first == current }?.second ?: "-"
            }
            refresh()
            row.setOnClickListener {
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
            return Pair(row, updater)
        }

        val root = LinearLayout(this)
        root.orientation = LinearLayout.VERTICAL
        root.addView(makeText(settingName(key), 13f, colSubText, true))

        val radios = ArrayList<RadioRow>()
        fun highlight(current: String?) {
            radios.forEach { styleRadio(it, it.option == current) }
        }

        entries.forEach { (option, label) ->
            val row = makeRow()
            val dot = View(this)
            val text = makeText(label, 13f, colText)
            val dotParams = LinearLayout.LayoutParams(dp(18), dp(18))
            dotParams.marginEnd = dp(8)
            row.addView(dot, dotParams)
            row.addView(text, weightParams())
            val radio = RadioRow(option, row, dot, text)
            row.setOnClickListener {
                if (!updatingUi) {
                    sendValue(device, key, makeValue(option), "${settingName(key)} = $label")
                    highlight(option)
                }
            }
            radios.add(radio)
            styleRadio(radio, false)
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

        val switches = ArrayList<Pair<String, Switch>>()
        val options = setting.setting.options
        val labels = setting.setting.localizedOptions
        options.forEachIndexed { i, option ->
            val row = LinearLayout(this)
            row.orientation = LinearLayout.HORIZONTAL
            row.gravity = Gravity.CENTER_VERTICAL
            val name = makeText(optionText(option, labels.getOrElse(i) { option }), 12f, colText)
            val toggle = makeSwitch(option in setting.values)
            toggle.setOnCheckedChangeListener { _, _ ->
                if (!updatingUi) {
                    val selected = switches.filter { it.second.isChecked }.map { it.first }
                    sendValue(device, key, Value.StringVecValue(selected), "${settingName(key)} עודכן")
                }
            }
            switches.add(Pair(option, toggle))
            row.addView(name, weightParams())
            row.addView(toggle, wrapParams())
            root.addView(row, matchWrap())
        }
        val updater: (Any?) -> Unit = { s ->
            if (s is Setting.MultiSelectSetting) {
                switches.forEach { it.second.isChecked = it.first in s.values }
            }
        }
        return Pair(root, updater)
    }

    // ---------- lifecycle ----------

    override fun onCreate(savedInstanceState: Bundle?) {
        // Light theme without the Android title bar (the "OpenSCQ30 Lite" strip), so the app uses the whole
        // screen. Set here so that AndroidManifest.xml does not need to change.
        setTheme(android.R.style.Theme_Holo_Light_NoActionBar)
        super.onCreate(savedInstanceState)

        // Either hide the status bar completely, or draw the app under a translucent status bar.
        window.addFlags(
            if (hideStatusBar) {
                WindowManager.LayoutParams.FLAG_FULLSCREEN
            } else {
                WindowManager.LayoutParams.FLAG_TRANSLUCENT_STATUS
            },
        )

        val filter = IntentFilter()
        filter.addAction(BluetoothDevice.ACTION_FOUND)
        filter.addAction(BluetoothDevice.ACTION_BOND_STATE_CHANGED)
        filter.addAction(BluetoothAdapter.ACTION_DISCOVERY_FINISHED)
        registerReceiver(scanReceiver, filter)

        showList()
        line("גרסה: v14-מסך מלא")
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
                // From the "more" page, back goes to the sound page first.
                if (currentPage == PAGE_MORE) {
                    showPage(PAGE_SOUND)
                    return
                }
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
