package com.oppzippy.openscq30.lite
 
import android.app.Activity
import android.app.AlertDialog
import android.app.Dialog
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
        private const val TAB_SOUND = 0
        private const val TAB_EQ = 1
        private const val TAB_CONTROLS = 2
        private const val TAB_MORE = 3
        private const val PRESSURE_TIP = "Atm (אטמוספירה)\nלחץ האוויר האטמוספרי הסטנדרטי הוא 1.00. שינויים בלחץ האוויר עלולים להשפיע על ביצועי ביטול הרעשים. אופטימיזציה ללחץ אטמוספרי תשפר את חוויית ביטול הרעשים שלכם."
    }
 
    private enum class Screen { LIST, PICKER, CONNECTED }
 
    // The three pages of the first screen (the bottom bar): devices, log and the app's own settings.
    private enum class ListTab { DEVICES, LOG, PROFILE }
 
    // bonded = already paired in Android's Bluetooth. Rows with bonded=false are nearby devices found by scanning.
    private data class Row(val name: String, val mac: String, val model: String?, val bonded: Boolean = true)
 
    private class SettingItem(val category: String, val key: String, val setting: Any?)
 
    private class RadioItem(val option: String?, val root: LinearLayout, val dot: RadioDot, val label: TextView)
 
    private class ProfileCard(
        val option: String?,
        val root: LinearLayout,
        val icon: OfficialIconView,
        val title: TextView,
        val mark: OfficialIconView,
    )
 
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
 
    // First screen.
    private var statusView: TextView? = null
    private var logView: TextView? = null
    private var cardsContainer: LinearLayout? = null
    private var listBody: LinearLayout? = null
    private var listTopBar: LinearLayout? = null
    private var listTab = ListTab.DEVICES
    private val navIcons = ArrayList<OfficialIconView>()
    private var lastRowsSignature = ""
 
    // Device screen: tabs (sound, equalizer, buttons) and the "more settings" page.
    private var connectionView: TextView? = null
    private var tabContainers: List<LinearLayout> = emptyList()
    private var tabHeaders: List<LinearLayout> = emptyList()
    private var tabStrip: LinearLayout? = null
    private var currentTab = TAB_SOUND
    private var moreMode = false
    private var earbudsView: EarbudsView? = null
    private var titleView: TextView? = null
    private var gearButton: View? = null
    private var deviceTitle = ""
    private var moreCategory: String? = null
    private var ambientBar: AmbientBar? = null
    private val ambientHandled = HashSet<String>()
    private val morePages = HashMap<String, LinearLayout>()
    private var moreMenuViews: List<View> = emptyList()
    private var moreMenuCard: LinearLayout? = null
 
    // The buttons tab: a Left / Right switch and one card for each side.
    private var buttonsLeftCard: LinearLayout? = null
    private var buttonsRightCard: LinearLayout? = null
    private var buttonsOtherCard: LinearLayout? = null
    private var sideLeftPill: Button? = null
    private var sideRightPill: Button? = null
    private var buttonsLockCard: LinearLayout? = null
    private val slideTitled = HashSet<LinearLayout>()
    private var resetKey: String? = null
    private var resetDevice: Any? = null
 
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
 
    private fun dpi(value: Float): Int = UiStyle.dp(this, value)
 
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
            else -> {
                statusView?.text = statusLines.takeLast(3).joinToString("\n")
                logView?.text = statusLines.takeLast(60).joinToString("\n")
            }
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
 
    private fun makeButton(label: String, onClick: () -> Unit): Button = UiStyle.primaryButton(this, label, onClick)
 
    private fun matchWrap() =
        LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
 
    private fun weightParams(weight: Float = 1f) =
        LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, weight)
 
    private fun smallText(text: String, size: Float, color: Int, bold: Boolean = false): TextView {
        val view = TextView(this)
        view.text = text
        view.textSize = size
        view.setTextColor(color)
        if (bold) view.setTypeface(view.typeface, Typeface.BOLD)
        return view
    }
 
    // A focusable icon button (the top bar of the device screen).
    private fun iconButton(kind: OfficialIcon, onClick: () -> Unit): LinearLayout {
        val holder = LinearLayout(this)
        holder.gravity = Gravity.CENTER
        holder.isFocusable = true
        holder.isClickable = true
        holder.background = UiStyle.focusable(this, UiStyle.card, 8f)
        holder.setPadding(dpi(8f), dpi(8f), dpi(8f), dpi(8f))
        holder.addView(OfficialIconView(this, kind, UiStyle.textPrimary), LinearLayout.LayoutParams(dpi(24f), dpi(24f)))
        holder.setOnClickListener { onClick() }
        return holder
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
 
    // ---------- first screen: "my devices" ----------
 
    private fun showList() {
        currentScreen = Screen.LIST
        connectionView = null
        cardsContainer = null
        logView = null
        lastRowsSignature = ""
 
        val root = LinearLayout(this)
        root.orientation = LinearLayout.VERTICAL
        root.setBackgroundColor(UiStyle.background)
        rtl(root)
 
        // Top bar: a bell on one side, the logo in the middle and a headset on the other (like the original app).
        val topBar = LinearLayout(this)
        topBar.orientation = LinearLayout.HORIZONTAL
        topBar.layoutDirection = View.LAYOUT_DIRECTION_LTR
        topBar.gravity = Gravity.CENTER_VERTICAL
        topBar.setPadding(dpi(12f), dpi(8f), dpi(12f), dpi(4f))
        topBar.addView(IconView(this, IconKind.BELL, UiStyle.textPrimary), LinearLayout.LayoutParams(dpi(24f), dpi(24f)))
        val logo = smallText("soundcore", 20f, UiStyle.blue, true)
        logo.setTypeface(logo.typeface, Typeface.BOLD_ITALIC)
        logo.gravity = Gravity.CENTER
        topBar.addView(logo, weightParams())
        topBar.addView(IconView(this, IconKind.HEADSET, UiStyle.textPrimary), LinearLayout.LayoutParams(dpi(24f), dpi(24f)))
        listTopBar = topBar
        root.addView(topBar, matchWrap())
 
        val body = LinearLayout(this)
        body.orientation = LinearLayout.VERTICAL
        listBody = body
        val scroll = ScrollView(this)
        scroll.addView(body)
        root.addView(scroll, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
 
        val status = smallText("", 10f, UiStyle.textSecondary)
        status.setPadding(dpi(12f), dpi(2f), dpi(12f), dpi(4f))
        statusView = status
        root.addView(status, matchWrap())
 
        root.addView(UiStyle.divider(this), UiStyle.dividerParams(this))
        root.addView(buildBottomNav(), matchWrap())
        setContentView(root)
 
        showListTab(ListTab.DEVICES)
        refreshDeviceList()
    }
 
    // The bottom bar: devices, log and the app's own settings.
    private fun buildBottomNav(): LinearLayout {
        val nav = LinearLayout(this)
        nav.orientation = LinearLayout.HORIZONTAL
        nav.layoutDirection = View.LAYOUT_DIRECTION_LTR
        nav.setBackgroundColor(UiStyle.card)
        navIcons.clear()
        listOf(
            Pair(OfficialIcon.NAV_DEVICE, ListTab.DEVICES),
            Pair(OfficialIcon.NAV_DISCOVER, ListTab.LOG),
            Pair(OfficialIcon.NAV_ME, ListTab.PROFILE),
        ).forEach { (kind, tab) ->
            val holder = LinearLayout(this)
            holder.gravity = Gravity.CENTER
            holder.isFocusable = true
            holder.isClickable = true
            holder.background = UiStyle.focusable(this, UiStyle.card, 0f)
            holder.setPadding(0, dpi(9f), 0, dpi(9f))
            val icon = OfficialIconView(this, kind, UiStyle.textSecondary)
            holder.addView(icon, LinearLayout.LayoutParams(dpi(26f), dpi(26f)))
            holder.setOnClickListener { showListTab(tab) }
            navIcons.add(icon)
            nav.addView(holder, weightParams())
        }
        return nav
    }
 
    private fun showListTab(tab: ListTab) {
        listTab = tab
        val body = listBody ?: return
        body.removeAllViews()
        cardsContainer = null
        logView = null
        lastRowsSignature = ""
        listTopBar?.visibility = if (tab == ListTab.PROFILE) View.GONE else View.VISIBLE
        statusView?.visibility = if (tab == ListTab.LOG) View.GONE else View.VISIBLE
        navIcons.forEachIndexed { i, icon -> icon.setTint(if (i == tab.ordinal) UiStyle.blue else UiStyle.textSecondary) }
        when (tab) {
            ListTab.DEVICES -> buildDevicesBody(body)
            ListTab.LOG -> buildLogBody(body)
            ListTab.PROFILE -> buildProfileBody(body)
        }
        renderStatus()
    }
 
    private fun buildDevicesBody(body: LinearLayout) {
        val heading = smallText("המכשירים שלי", 14f, UiStyle.textSecondary)
        heading.setPadding(dpi(14f), dpi(4f), dpi(14f), dpi(2f))
        body.addView(heading, matchWrap())
 
        val container = LinearLayout(this)
        container.orientation = LinearLayout.VERTICAL
        cardsContainer = container
        body.addView(container, matchWrap())
 
        // "Add device" scans for nearby earbuds (they must be in pairing mode).
        val add = LinearLayout(this)
        add.orientation = LinearLayout.HORIZONTAL
        add.gravity = Gravity.CENTER
        add.isFocusable = true
        add.isClickable = true
        add.background = UiStyle.focusable(this, UiStyle.card, 14f)
        add.setPadding(dpi(12f), dpi(14f), dpi(12f), dpi(14f))
        add.addView(IconView(this, IconKind.PLUS, UiStyle.textSecondary), LinearLayout.LayoutParams(dpi(18f), dpi(18f)))
        val addText = smallText("הוספת מכשיר", 14f, UiStyle.textSecondary)
        addText.setPadding(dpi(6f), 0, 0, 0)
        add.addView(addText)
        add.setOnClickListener { startScan() }
        body.addView(add, UiStyle.cardParams(this))
 
        renderCards()
    }
 
    private fun buildLogBody(body: LinearLayout) {
        val log = TextView(this)
        log.textSize = 10f
        log.typeface = Typeface.MONOSPACE
        log.setTextColor(UiStyle.textPrimary)
        log.layoutDirection = View.LAYOUT_DIRECTION_LTR
        log.setPadding(dpi(10f), dpi(10f), dpi(10f), dpi(10f))
        logView = log
        body.addView(log, matchWrap())
    }
 
    // The app's own settings, like the "profile" page of the original app.
    private fun buildProfileBody(body: LinearLayout) {
        val head = LinearLayout(this)
        head.orientation = LinearLayout.VERTICAL
        head.gravity = Gravity.CENTER_HORIZONTAL
        head.background = GradientDrawable(
            GradientDrawable.Orientation.TOP_BOTTOM,
            intArrayOf(UiStyle.blueLight, UiStyle.background),
        )
        head.setPadding(0, dpi(22f), 0, dpi(14f))
        val avatar = LinearLayout(this)
        avatar.gravity = Gravity.CENTER
        avatar.background = UiStyle.rounded(0xFFCFD6DE.toInt(), dpi(28f).toFloat())
        avatar.addView(IconView(this, IconKind.DEVICES, Color.WHITE), LinearLayout.LayoutParams(dpi(30f), dpi(30f)))
        head.addView(avatar, LinearLayout.LayoutParams(dpi(56f), dpi(56f)))
        val name = smallText("OpenSCQ30 Lite", 16f, UiStyle.textPrimary, true)
        name.gravity = Gravity.CENTER
        name.setPadding(0, dpi(8f), 0, 0)
        head.addView(name, matchWrap())
        body.addView(head, matchWrap())
 
        val card = UiStyle.card(this)
 
        val showAllSwitch = Switch(this)
        showAllSwitch.text = "הצג את כל המכשירים (לא רק Anker)"
        showAllSwitch.textSize = 13f
        showAllSwitch.setTextColor(UiStyle.textPrimary)
        showAllSwitch.textOn = ""
        showAllSwitch.textOff = ""
        showAllSwitch.setPadding(dpi(8f), dpi(10f), dpi(8f), dpi(10f))
        showAllSwitch.isChecked = showAll
        showAllSwitch.setOnCheckedChangeListener { _, checked ->
            showAll = checked
            prefs.edit().putBoolean("show_all", checked).apply()
            refreshDeviceList()
        }
        card.addView(showAllSwitch, matchWrap())
 
        fun addRow(title: String, onClick: () -> Unit) {
            card.addView(UiStyle.divider(this), UiStyle.dividerParams(this))
            val row = UiStyle.row(this, title, onClick)
            row.value.visibility = View.GONE
            card.addView(row.root, matchWrap())
        }
        addRow("סרוק מכשירים בסביבה") { startScan() }
        addRow("רענן את רשימת המכשירים") { refreshDeviceList() }
        addRow("עדכון האפליקציה") {
            UpdateManager.showUpdateDialog(this, { disconnect("עדכון") }, { line(it) })
        }
 
        card.addView(UiStyle.divider(this), UiStyle.dividerParams(this))
        val version = LinearLayout(this)
        version.orientation = LinearLayout.HORIZONTAL
        version.setPadding(dpi(8f), dpi(12f), dpi(8f), dpi(12f))
        version.addView(smallText("גרסה", 13f, UiStyle.textPrimary), weightParams())
        version.addView(smallText(APP_VERSION, 13f, UiStyle.textSecondary))
        card.addView(version, matchWrap())
 
        body.addView(card, UiStyle.cardParams(this))
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
            val empty = smallText("אין מכשירים להצגה", 12f, UiStyle.textSecondary)
            empty.setPadding(dpi(14f), dpi(10f), dpi(14f), dpi(10f))
            container.addView(empty, matchWrap())
            return
        }
        deviceRows.forEach { row ->
            val card = LinearLayout(this)
            card.orientation = LinearLayout.HORIZONTAL
            card.gravity = Gravity.CENTER_VERTICAL
            card.isFocusable = true
            card.isClickable = true
            card.background = UiStyle.focusable(this, UiStyle.card, 14f)
            card.setPadding(dpi(12f), dpi(12f), dpi(12f), dpi(12f))
 
            val picture = DevicePictureView(this)
            picture.setModel(row.model ?: "")
            card.addView(picture, LinearLayout.LayoutParams(dpi(80f), dpi(64f)))
 
            val title = if (row.model != null) modelName(row.model) else row.name.ifEmpty { "לא ידוע" }
            val status = when {
                !row.bonded -> "בקרבת מקום – לחץ לזיווג"
                row.model != null -> "לא מחובר"
                else -> "לחץ לבחירת דגם"
            }
            val texts = LinearLayout(this)
            texts.orientation = LinearLayout.VERTICAL
            texts.addView(smallText(title, 16f, UiStyle.textPrimary, true), matchWrap())
            texts.addView(smallText(status, 12f, UiStyle.textSecondary), matchWrap())
            if (row.bonded && row.model != null && row.name.isNotEmpty() && row.name != title) {
                texts.addView(smallText(row.name, 10f, UiStyle.textSecondary), matchWrap())
            }
            val textParams = weightParams()
            textParams.marginStart = dpi(12f)
            card.addView(texts, textParams)
 
            card.setOnClickListener { onDeviceChosen(row) }
            card.setOnLongClickListener {
                if (row.bonded) showDeviceMenu(row)
                true
            }
            container.addView(card, UiStyle.cardParams(this))
        }
        if (currentFocus == null) container.getChildAt(0)?.requestFocus()
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
                    line("לא נמצאו מכשירי Anker (${bondedRows.size} מכשירים מזווגים בסך הכל). לחץ על 'הוספת מכשיר', או הפעל 'הצג את כל המכשירים'.")
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
            // No model yet: if the Bluetooth name of the device clearly matches one model, use it without asking.
            // A wrong guess can be changed with a long press on the device (the menu "שנה דגם").
            val guess = guessModel(row.name)
            if (guess != null) {
                line("זוהה דגם אוטומטית: ${modelName(guess)}")
                onModelChosen(row, guess)
            } else {
                showModelPicker(row)
            }
        }
    }
 
    // The model whose name appears in the device name (the longest one, if it is the only one that long), or null.
    private fun guessModel(deviceName: String): String? {
        val name = deviceName.lowercase(Locale.US).trim()
        if (name.isEmpty()) return null
        val models: List<Pair<String, String>> = try {
            deviceModels()
                .filter { it != "SoundcoreDevelopment" }
                .map { Pair(it, modelName(it).lowercase(Locale.US).trim()) }
        } catch (t: Throwable) {
            return null
        }
        val matches = models.filter { it.second.length >= 5 && (name == it.second || name.contains(it.second)) }
        val best = matches.maxByOrNull { it.second.length } ?: return null
        val sameLength = matches.count { it.second.length == best.second.length }
        return if (sameLength == 1) best.first else null
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
        logView = null
 
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
        root.setBackgroundColor(UiStyle.background)
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
 
    // Device screen, modeled on the original Soundcore app: a white top bar (back, name, headset, settings), the
    // picture with the batteries, three icon tabs (sound, equalizer, buttons) and a "more settings" page behind
    // the settings button.
    private fun showConnectedScreen(mac: String) {
        currentScreen = Screen.CONNECTED
        currentMac = mac
        statusView = null
        logView = null
        cardsContainer = null
        controls.clear()
        sections.clear()
        loggedSkips.clear()
        slideTitled.clear()
        resetKey = null
        resetDevice = null
        lastRefreshError = ""
        reportedUntranslated = 0
        ambientBar = null
        ambientHandled.clear()
        currentTab = TAB_SOUND
        moreMode = false
        deviceTitle = bondedRows.firstOrNull { it.mac.equals(mac, ignoreCase = true) }?.name ?: ""
 
        val title = TextView(this)
        title.textSize = 18f
        title.setTypeface(null, Typeface.BOLD)
        title.setTextColor(UiStyle.textPrimary)
        title.gravity = Gravity.CENTER
        title.maxLines = 1
        title.text = deviceTitle
        titleView = title
 
        val gear = iconButton(OfficialIcon.SETTING) { setMoreMode(true) }
        gearButton = gear
 
        val topBar = LinearLayout(this)
        topBar.orientation = LinearLayout.HORIZONTAL
        topBar.layoutDirection = View.LAYOUT_DIRECTION_LTR
        topBar.gravity = Gravity.CENTER_VERTICAL
        topBar.setBackgroundColor(UiStyle.card)
        topBar.setPadding(dpi(4f), dpi(2f), dpi(4f), dpi(2f))
        topBar.addView(iconButton(OfficialIcon.BACK) { onBackPressed() }, LinearLayout.LayoutParams(dpi(40f), dpi(40f)))
        topBar.addView(title, weightParams())
        topBar.addView(gear, LinearLayout.LayoutParams(dpi(40f), dpi(40f)))
 
        val earbuds = EarbudsView(this)
        earbuds.setBackgroundColor(UiStyle.card)
        earbudsView = earbuds
        val earbudsHeight = dpi(170f)
 
        val strip = LinearLayout(this)
        strip.orientation = LinearLayout.HORIZONTAL
        strip.layoutDirection = View.LAYOUT_DIRECTION_LTR
        strip.setBackgroundColor(UiStyle.card)
        tabStrip = strip
        val headers = ArrayList<LinearLayout>()
        listOf(OfficialIcon.TAB_AMBIENT, OfficialIcon.TAB_EQ, OfficialIcon.TAB_CONTROLS).forEachIndexed { index, kind ->
            val header = LinearLayout(this)
            header.orientation = LinearLayout.VERTICAL
            header.gravity = Gravity.CENTER_HORIZONTAL
            header.isFocusable = true
            header.isClickable = true
            header.background = UiStyle.focusable(this, UiStyle.card, 0f)
            header.setPadding(0, dpi(8f), 0, 0)
            header.addView(OfficialIconView(this, kind, UiStyle.textSecondary), LinearLayout.LayoutParams(dpi(28f), dpi(28f)))
            val underline = LinearLayout.LayoutParams(dpi(8f), dpi(2f))
            underline.topMargin = dpi(8f)
            header.addView(View(this), underline)
            header.setOnClickListener { selectTab(index) }
            strip.addView(header, weightParams())
            headers.add(header)
        }
        tabHeaders = headers
 
        val containers = ArrayList<LinearLayout>()
        for (i in 0..TAB_MORE) {
            val container = LinearLayout(this)
            container.orientation = LinearLayout.VERTICAL
            containers.add(container)
        }
        tabContainers = containers
 
        // Buttons tab: Left / Right switch, then a card for the chosen side and a card for general button settings.
        val buttonsTab = containers[TAB_CONTROLS]
        val sideRow = LinearLayout(this)
        sideRow.orientation = LinearLayout.HORIZONTAL
        sideRow.layoutDirection = View.LAYOUT_DIRECTION_LTR
        val leftPill = UiStyle.pill(this, "שמאל") { showSide(0) }
        val rightPill = UiStyle.pill(this, "ימין") { showSide(1) }
        sideLeftPill = leftPill
        sideRightPill = rightPill
        val pillMargin = dpi(8f)
        val leftParams = weightParams()
        leftParams.setMargins(pillMargin, dpi(6f), dpi(4f), dpi(6f))
        val rightParams = weightParams()
        rightParams.setMargins(dpi(4f), dpi(6f), pillMargin, dpi(6f))
        sideRow.addView(leftPill, leftParams)
        sideRow.addView(rightPill, rightParams)
        buttonsTab.addView(sideRow, matchWrap())
        val leftCard = UiStyle.card(this)
        val rightCard = UiStyle.card(this)
        val otherCard = UiStyle.card(this)
        otherCard.visibility = View.GONE
        buttonsLeftCard = leftCard
        buttonsRightCard = rightCard
        buttonsOtherCard = otherCard
        leftCard.addView(controlsHeader(), matchWrap())
        rightCard.addView(controlsHeader(), matchWrap())
        val lockCard = UiStyle.card(this)
        lockCard.visibility = View.GONE
        buttonsLockCard = lockCard
        buttonsTab.addView(leftCard, UiStyle.cardParams(this))
        buttonsTab.addView(rightCard, UiStyle.cardParams(this))
        buttonsTab.addView(otherCard, UiStyle.cardParams(this))
        buttonsTab.addView(lockCard, UiStyle.cardParams(this))
        showSide(0)
 
        // More settings: a menu with one row per category, then a "reconnect" button.
        val menuCard = UiStyle.card(this)
        val reconnect = makeButton("התחבר מחדש") { startConnection(currentMac) }
        containers[TAB_MORE].addView(menuCard, UiStyle.cardParams(this))
        containers[TAB_MORE].addView(reconnect, UiStyle.cardParams(this))
        moreMenuCard = menuCard
        moreMenuViews = listOf(menuCard, reconnect)
        morePages.clear()
        moreCategory = null
 
        val log = TextView(this)
        log.textSize = 11f
        log.setTextColor(UiStyle.textSecondary)
        log.setPadding(16, 16, 16, 16)
        connectionView = log
 
        val content = LinearLayout(this)
        content.orientation = LinearLayout.VERTICAL
        content.setPadding(0, dpi(4f), 0, dpi(12f))
        containers.forEach { content.addView(it, matchWrap()) }
        content.addView(log, matchWrap())
 
        val scrollView = ScrollView(this)
        scrollView.addView(content)
 
        val root = LinearLayout(this)
        root.orientation = LinearLayout.VERTICAL
        root.setBackgroundColor(UiStyle.background)
        rtl(root)
        root.addView(topBar, matchWrap())
        root.addView(earbuds, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, earbudsHeight))
        root.addView(strip, matchWrap())
        root.addView(UiStyle.divider(this), UiStyle.dividerParams(this))
        root.addView(scrollView, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        setContentView(root)
 
        selectTab(TAB_SOUND)
        renderStatus()
        tabHeaders.getOrNull(TAB_SOUND)?.requestFocus()
    }
 
    // The title of the buttons cards, like the original: "שמע" with a reset icon at the end.
    private fun controlsHeader(): LinearLayout {
        val header = LinearLayout(this)
        header.orientation = LinearLayout.HORIZONTAL
        header.gravity = Gravity.CENTER_VERTICAL
        header.setPadding(dpi(8f), dpi(8f), dpi(8f), dpi(4f))
        header.addView(smallText("שמע", 16f, UiStyle.textPrimary, true), weightParams())
        val reset = LinearLayout(this)
        reset.gravity = Gravity.CENTER
        reset.isFocusable = true
        reset.isClickable = true
        reset.background = UiStyle.focusable(this, UiStyle.card, 14f)
        reset.setPadding(dpi(4f), dpi(4f), dpi(4f), dpi(4f))
        reset.addView(OfficialIconView(this, OfficialIcon.RESET, UiStyle.textPrimary), LinearLayout.LayoutParams(dpi(20f), dpi(20f)))
        reset.setOnClickListener { confirmReset() }
        header.addView(reset, LinearLayout.LayoutParams(dpi(28f), dpi(28f)))
        return header
    }
 
    // The reset icon runs the "reset buttons to default" action of the core, after a confirmation.
    private fun confirmReset() {
        val key = resetKey
        val device = resetDevice
        if (key == null || device == null) {
            toast("האיפוס לא זמין")
            return
        }
        val name = settingName(key)
        AlertDialog.Builder(this)
            .setTitle(name)
            .setMessage("לבצע את הפעולה?")
            .setPositiveButton("כן") { _, _ -> sendValue(device, key, Value.BoolValue(true), name) }
            .setNegativeButton("לא", null)
            .show()
    }
 
    private fun showSide(side: Int) {
        buttonsLeftCard?.visibility = if (side == 0) View.VISIBLE else View.GONE
        buttonsRightCard?.visibility = if (side == 1) View.VISIBLE else View.GONE
        sideLeftPill?.let { UiStyle.setPillSelected(this, it, side == 0) }
        sideRightPill?.let { UiStyle.setPillSelected(this, it, side == 1) }
    }
 
    private fun styleTabHeader(header: LinearLayout, selected: Boolean) {
        val icon = header.getChildAt(0) as OfficialIconView
        val underline = header.getChildAt(1)
        icon.setTint(if (selected) 0xFF333333.toInt() else 0xFF999999.toInt())
        if (selected) {
            underline.background = UiStyle.rounded(0xFF00CBEB.toInt(), dpi(2f).toFloat())
        } else {
            underline.setBackgroundColor(0)
        }
    }
 
    private fun selectTab(index: Int) {
        currentTab = index
        tabHeaders.forEachIndexed { i, header -> styleTabHeader(header, i == index) }
        tabContainers.forEachIndexed { i, container ->
            container.visibility = if (!moreMode && i == index) View.VISIBLE else View.GONE
        }
    }
 
    // The "more settings" page replaces the picture and the tabs (like the original app's second page).
    private fun setMoreMode(on: Boolean) {
        moreMode = on
        earbudsView?.visibility = if (on) View.GONE else View.VISIBLE
        tabStrip?.visibility = if (on) View.GONE else View.VISIBLE
        gearButton?.visibility = if (on) View.INVISIBLE else View.VISIBLE
        tabContainers.forEachIndexed { i, container ->
            val visible = if (on) i == TAB_MORE else i == currentTab
            container.visibility = if (visible) View.VISIBLE else View.GONE
        }
        if (on) {
            showMoreMenu()
        } else {
            moreCategory = null
            titleView?.text = deviceTitle
        }
    }
 
    // The menu of the "more settings" page: one row per category, like the original app's list.
    private fun showMoreMenu() {
        moreCategory = null
        titleView?.text = "הגדרות נוספות"
        moreMenuViews.forEach { it.visibility = View.VISIBLE }
        morePages.values.forEach { it.visibility = View.GONE }
    }
 
    // One category of the "more settings" page, shown alone.
    private fun openMorePage(category: String) {
        moreCategory = category
        titleView?.text = settingName(category)
        moreMenuViews.forEach { it.visibility = View.GONE }
        morePages.forEach { (name, page) -> page.visibility = if (name == category) View.VISIBLE else View.GONE }
    }
 
    private fun addMoreMenuRow(category: String) {
        val menu = moreMenuCard ?: return
        if (menu.childCount > 0) menu.addView(UiStyle.divider(this), UiStyle.dividerParams(this))
        val rowViews = UiStyle.row(this, settingName(category)) { openMorePage(category) }
        rowViews.value.visibility = View.GONE
        menu.addView(rowViews.root, matchWrap())
    }
 
    // Which tab a settings category belongs to; everything else goes to the "more settings" page.
    private fun tabFor(category: String): Int {
        val n = category.lowercase(Locale.US).replace(Regex("[^a-z0-9]"), "")
        return when {
            n.contains("button") -> TAB_CONTROLS
            n.contains("equalizer") -> TAB_EQ
            n.contains("sound") || n.contains("ambient") || n.contains("noise") || n.contains("transparen") -> TAB_SOUND
            else -> TAB_MORE
        }
    }
 
    // Reads the battery settings and gives them to the picture.
    private fun updateEarbuds(items: List<SettingItem>) {
        var leftPercent: Int? = null
        var rightPercent: Int? = null
        var casePercent: Int? = null
        var singlePercent: Int? = null
        var leftCharging = false
        var rightCharging = false
        var caseCharging = false
        for (item in items) {
            val text = (item.setting as? Setting.InformationSetting)?.translatedValue ?: continue
            val key = keyNorm(item.key)
            val percent = Regex("(\\d+)").find(text)?.groupValues?.get(1)?.toIntOrNull()
            val lower = text.lowercase(Locale.US)
            val charging = lower.contains("charg") && !lower.contains("not")
            when (key) {
                "batterylevelleft" -> leftPercent = percent
                "batterylevelright" -> rightPercent = percent
                "casebatterylevel", "batterylevelcase" -> casePercent = percent
                "batterylevel" -> singlePercent = percent
                "ischargingleft", "isleftcharging" -> leftCharging = charging
                "ischargingright", "isrightcharging" -> rightCharging = charging
                "ischargingcase", "iscasecharging" -> caseCharging = charging
            }
        }
        earbudsView?.setBattery(
            leftPercent, rightPercent, casePercent, singlePercent,
            leftCharging, rightCharging, caseCharging,
        )
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
                val modelId = connected.model().toString()
                line("מזהה דגם (לשם קובצי התמונות): $modelId")
                deviceTitle = modelName(modelId)
                if (!moreMode) titleView?.text = deviceTitle
                earbudsView?.setModel(modelId)
 
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
 
    private fun keyNorm(text: String): String = text.lowercase(Locale.US).replace(Regex("[^a-z0-9]"), "")
 
    // Sends several settings in one call, in the given order.
    private fun sendValues(device: Any, pairs: List<SettingIdValuePair>, description: String) {
        scope.launch {
            try {
                (device as OpenScq30Device).setSettingValues(pairs)
                line("נשלח: $description")
            } catch (t: Throwable) {
                if (t is CancellationException) throw t
                line("השליחה נכשלה:\n" + describe(t))
            }
        }
    }
 
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
 
    // The card of a settings category. Categories of the tabs get a small gray header (except the sound tab, which
    // has none in the original app); every other category becomes its own page behind a row in the "more settings"
    // menu.
    private fun sectionCard(category: String): LinearLayout {
        val existing = sections[category]
        if (existing != null) return existing
        val tab = tabFor(category)
        val card = UiStyle.card(this)
        if (tab == TAB_MORE) {
            val page = LinearLayout(this)
            page.orientation = LinearLayout.VERTICAL
            page.visibility = View.GONE
            page.addView(card, UiStyle.cardParams(this))
            tabContainers[TAB_MORE].addView(page, matchWrap())
            morePages[category] = page
            addMoreMenuRow(category)
        } else {
            val container = tabContainers[tab]
            if (tab != TAB_SOUND) {
                container.addView(UiStyle.sectionHeader(this, settingName(category)), matchWrap())
            }
            container.addView(card, UiStyle.cardParams(this))
        }
        sections[category] = card
        return card
    }
 
    // The card a control goes into. Button settings go to the card of their side (left / right) in the buttons tab.
    private fun cardFor(item: SettingItem): LinearLayout {
        val lockKey = keyNorm(item.key)
        if (lockKey.contains("touchlock") || lockKey.contains("controllock")) {
            val lock = buttonsLockCard
            if (lock != null) {
                lock.visibility = View.VISIBLE
                return lock
            }
        }
        if (tabFor(item.category) == TAB_CONTROLS) {
            val k = keyNorm(item.key)
            val target = when {
                k.startsWith("left") -> buttonsLeftCard
                k.startsWith("right") -> buttonsRightCard
                else -> buttonsOtherCard
            }
            if (target != null) {
                if (target === buttonsOtherCard) target.visibility = View.VISIBLE
                return target
            }
        }
        return sectionCard(item.category)
    }
 
    private fun syncSettings(device: Any, items: List<SettingItem>) {
        if (tabContainers.isEmpty() || currentScreen != Screen.CONNECTED) return
        updateEarbuds(items)
        updateAmbientBar(device, items)
        for (item in items) {
            if (ambientBar != null && keyNorm(item.key) in ambientHandled) continue
            val setting = item.setting ?: continue
            // The reset action is the icon in the title of the buttons cards.
            if (setting is Setting.Action && keyNorm(item.key).contains("resetbuttons")) {
                resetKey = item.key
                resetDevice = device
                continue
            }
            // The battery levels are shown in the header.
            if (setting is Setting.InformationSetting && keyNorm(item.key).contains("battery")) continue
            var updater = controls[item.key]
            if (updater == null) {
                val tab = tabFor(item.category)
                val built = buildControl(device, item.key, setting, tab)
                if (built == null) {
                    if (loggedSkips.add(item.key)) line("(דילגתי על ${item.key}: ${setting.javaClass.simpleName})")
                    continue
                }
                val card = cardFor(item)
                if (card.childCount > 0) card.addView(UiStyle.divider(this), UiStyle.dividerParams(this))
                if (tab == TAB_CONTROLS && keyNorm(item.key).contains("slide") && slideTitled.add(card)) {
                    card.addView(UiStyle.sectionHeader(this, "לא ניתן לכוונון"), matchWrap())
                }
                card.addView(built.first, matchWrap())
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
 
    private fun entriesOf(
        options: List<String>,
        labels: List<String>,
        allowNone: Boolean,
        noneLabel: String = "ללא",
    ): ArrayList<Pair<String?, String>> {
        val entries = ArrayList<Pair<String?, String>>()
        options.forEachIndexed { i, option ->
            entries.add(Pair(option, optionText(option, labels.getOrElse(i) { option })))
        }
        if (allowNone) entries.add(Pair(null, noneLabel))
        return entries
    }
 
    // A select: in the buttons tab it is a row with an icon (like the original app), elsewhere it depends on the
    // number of options.
    private fun selectControl(
        device: Any,
        key: String,
        options: List<String>,
        labels: List<String>,
        allowNone: Boolean,
        pressRow: Boolean,
        currentOf: (Any?) -> String?,
        makeValue: (String?) -> Value,
    ): Pair<View, (Any?) -> Unit> =
        if (pressRow) {
            pressRowControl(device, key, options, labels, allowNone, currentOf, makeValue)
        } else {
            choiceControl(device, key, options, labels, allowNone, currentOf, makeValue)
        }
 
    private fun buildControl(device: Any, key: String, setting: Any, tab: Int): Pair<View, (Any?) -> Unit>? {
        val pressRow = tab == TAB_CONTROLS
        return when (setting) {
            is Setting.ToggleSetting -> toggleControl(device, key, setting)
            is Setting.I32RangeSetting -> rangeControl(device, key, setting)
            is Setting.EqualizerSetting -> equalizerControl(device, key, setting)
            is Setting.SelectSetting -> selectControl(
                device, key, setting.setting.options, setting.setting.localizedOptions, false, pressRow,
                { (it as? Setting.SelectSetting)?.value },
                { Value.StringValue(it ?: "") },
            )
            is Setting.OptionalSelectSetting -> selectControl(
                device, key, setting.setting.options, setting.setting.localizedOptions, true, pressRow,
                { (it as? Setting.OptionalSelectSetting)?.value },
                { Value.OptionalStringValue(it) },
            )
            is Setting.PresetEqualizerProfileSelect -> profileCardsControl(
                device, key, setting.select.options, setting.select.localizedOptions,
                { (it as? Setting.PresetEqualizerProfileSelect)?.value },
                { Value.OptionalStringValue(it) },
            )
            is Setting.ModifiableSelectSetting ->
                if (setting.setting.options.isEmpty()) {
                    infoControl(key, "(ריק)") { "(ריק)" }
                } else {
                    selectControl(
                        device, key, setting.setting.options, setting.setting.localizedOptions, true, pressRow,
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
 
    private fun controlTitle(name: String): TextView {
        val view = TextView(this)
        view.text = name
        view.textSize = 16f
        view.setTextColor(UiStyle.textPrimary)
        view.setPadding(8, 8, 8, 4)
        return view
    }
 
    // A line with the name on one side and the current value on the other.
    // The air pressure gets its own light-blue box with a gauge, like the original app.
    private fun infoControl(key: String, initial: String, textOf: (Any?) -> String): Pair<View, (Any?) -> Unit> {
        if (keyNorm(key).contains("airpressure")) return pressureControl(initial, textOf)
        val row = LinearLayout(this)
        row.orientation = LinearLayout.HORIZONTAL
        row.setPadding(8, 12, 8, 12)
        val name = TextView(this)
        name.text = settingName(key)
        name.textSize = 16f
        name.setTextColor(UiStyle.textPrimary)
        val value = TextView(this)
        value.text = initial
        value.textSize = 14f
        value.setTextColor(UiStyle.textSecondary)
        row.addView(name, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        row.addView(value, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        return Pair(row, { s -> value.text = textOf(s) })
    }
 
    private fun pressureControl(initial: String, textOf: (Any?) -> String): Pair<View, (Any?) -> Unit> {
        val box = LinearLayout(this)
        box.orientation = LinearLayout.HORIZONTAL
        box.gravity = Gravity.CENTER_VERTICAL
        box.background = UiStyle.rounded(UiStyle.blueLight, dpi(12f).toFloat())
        box.setPadding(dpi(12f), dpi(16f), dpi(12f), dpi(16f))
 
        // The original: a round light-blue mark with the barometer icon in the brand color.
        val iconHolder = LinearLayout(this)
        iconHolder.gravity = Gravity.CENTER
        iconHolder.background = UiStyle.rounded(0x1900CBEB, dpi(24f).toFloat())
        iconHolder.addView(
            OfficialIconView(this, OfficialIcon.BAROMETRIC, 0xFF00CBEB.toInt()),
            LinearLayout.LayoutParams(dpi(28f), dpi(28f)),
        )
        val iconParams = LinearLayout.LayoutParams(dpi(48f), dpi(48f))
        iconParams.marginEnd = dpi(8f)
        box.addView(iconHolder, iconParams)
 
        val texts = LinearLayout(this)
        texts.orientation = LinearLayout.VERTICAL
        val valueRow = LinearLayout(this)
        valueRow.orientation = LinearLayout.HORIZONTAL
        valueRow.gravity = Gravity.CENTER_VERTICAL
        val value = smallText(initial, 14f, UiStyle.textPrimary)
        valueRow.addView(value)
        valueRow.addView(tipIcon("לחץ האוויר הנוכחי", PRESSURE_TIP), LinearLayout.LayoutParams(dpi(28f), dpi(28f)))
        texts.addView(valueRow, matchWrap())
        texts.addView(smallText("לחץ האוויר הנוכחי", 12f, UiStyle.textSecondary), matchWrap())
        box.addView(texts, weightParams())
        return Pair(box, { s -> value.text = textOf(s) })
    }
 
    // A small "i" icon that opens the explanation of a setting (like the tips of the original app).
    private fun tipIcon(title: String, message: String): View {
        val holder = LinearLayout(this)
        holder.gravity = Gravity.CENTER
        holder.isFocusable = true
        holder.isClickable = true
        holder.background = UiStyle.focusable(this, UiStyle.card, 14f)
        holder.setPadding(dpi(6f), dpi(6f), dpi(6f), dpi(6f))
        holder.addView(
            OfficialIconView(this, OfficialIcon.TIPS, UiStyle.textSecondary),
            LinearLayout.LayoutParams(dpi(16f), dpi(16f)),
        )
        holder.setOnClickListener {
            AlertDialog.Builder(this)
                .setTitle(title)
                .setMessage(message)
                .setPositiveButton("אישור", null)
                .show()
        }
        return holder
    }
 
    // The original explanation of a setting, for the settings that have one.
    private fun tipFor(key: String): String? {
        val k = keyNorm(key)
        return when {
            k.contains("windnoise") -> "הפעלת פונקציה זו יכולה להפחית או למנוע הפרעות רעש הנגרמות מרוח."
            k.contains("easychat") && !k.contains("wait") ->
                "הפעילו שיחה קלה כדי להנמיך את עוצמת המוזיקה מהמכשיר ולהבהיר את הקולות לשיחות קצרות."
            else -> null
        }
    }
 
    private fun toggleControl(device: Any, key: String, setting: Setting.ToggleSetting): Pair<View, (Any?) -> Unit> {
        val tip = tipFor(key)
        val box = Switch(this)
        if (tip == null) box.text = settingName(key)
        box.textSize = 16f
        box.setTextColor(UiStyle.textPrimary)
        box.textOn = ""
        box.textOff = ""
        box.setPadding(8, 10, 8, 10)
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
        if (tip == null) return Pair(box, updater)
        // With a tip: the name, the "i" icon and the switch in one row, like the original.
        val row = LinearLayout(this)
        row.orientation = LinearLayout.HORIZONTAL
        row.gravity = Gravity.CENTER_VERTICAL
        row.setPadding(dpi(8f), 0, dpi(8f), 0)
        row.addView(smallText(settingName(key), 16f, UiStyle.textPrimary), weightParams())
        row.addView(tipIcon(settingName(key), tip), LinearLayout.LayoutParams(dpi(28f), dpi(28f)))
        row.addView(box, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        return Pair(row, updater)
    }
 
    // A button that runs a one-time action (for example resetting the earbud buttons), after a confirmation.
    // The core does not say which value an Action expects, so true is sent. If it does not work, the log shows why.
    private fun actionControl(device: Any, key: String): Pair<View, (Any?) -> Unit> {
        val name = settingName(key)
        val row = UiStyle.row(this, name) {
            AlertDialog.Builder(this)
                .setTitle(name)
                .setMessage("לבצע את הפעולה?")
                .setPositiveButton("כן") { _, _ ->
                    sendValue(device, key, Value.BoolValue(true), name)
                }
                .setNegativeButton("לא", null)
                .show()
        }
        row.value.visibility = View.GONE
        val updater: (Any?) -> Unit = { }
        return Pair(row.root, updater)
    }
 
    // A button that opens a box for pasting text, and sends the text to the earbuds' core (for example a
    // list of equalizer profiles to import).
    private fun importControl(
        device: Any,
        key: String,
        setting: Setting.ImportStringSetting,
    ): Pair<View, (Any?) -> Unit> {
        val name = settingName(key)
        val row = UiStyle.row(this, name) {
            val input = EditText(this)
            input.hint = "הדבק כאן את הטקסט לייבוא"
            val builder = AlertDialog.Builder(this)
            builder.setTitle(name)
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
                    sendValue(device, key, Value.StringValue(text), name)
                }
            }
            builder.setNegativeButton("ביטול", null)
            builder.show()
        }
        row.value.visibility = View.GONE
        val updater: (Any?) -> Unit = { }
        return Pair(row.root, updater)
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
        root.addView(controlTitle(settingName(key)), matchWrap())
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
                rows.addView(smallText("(ריק)", 12f, UiStyle.textSecondary), matchWrap())
                return
            }
            values.forEach { name ->
                val row = LinearLayout(this)
                row.orientation = LinearLayout.HORIZONTAL
                row.gravity = Gravity.CENTER_VERTICAL
                val text = smallText(labelOf(name), 13f, UiStyle.textPrimary)
                val remove = UiStyle.primaryButton(this, "הסר") {
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
                row.addView(remove, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))
                rows.addView(row, matchWrap())
            }
        }
        render(setting.values)
        val updater: (Any?) -> Unit = { s ->
            if (s is Setting.MultiSelectWithRemoveSetting) render(s.values)
        }
        return Pair(root, updater)
    }
 
    // A range with up to 10 steps becomes a bar of numbered segments (like the original's level bar);
    // a longer range stays a slider. Both work with keys: with a keypad the value is sent shortly after the last key.
    private fun rangeControl(device: Any, key: String, setting: Setting.I32RangeSetting): Pair<View, (Any?) -> Unit> {
        val range = setting.setting
        val start = range.start
        val step = if (range.step <= 0) 1 else range.step
        val count = (range.end - start) / step + 1
 
        val root = LinearLayout(this)
        root.orientation = LinearLayout.VERTICAL
        root.addView(controlTitle(settingName(key)), matchWrap())
 
        if (count in 2..10) {
            val bar = SegmentBar(this, (0 until count).map { (start + it * step).toString() })
            bar.setLevelFromDevice((setting.value - start) / step)
            bar.onCommit = { index ->
                val value = start + index * step
                sendValue(device, key, Value.I32Value(value), "${settingName(key)} = $value")
            }
            root.addView(bar, matchWrap())
            val updater: (Any?) -> Unit = { s ->
                if (s is Setting.I32RangeSetting) bar.setLevelFromDevice((s.value - start) / step)
            }
            return Pair(root, updater)
        }
 
        val label = TextView(this)
        label.textSize = 14f
        label.setTextColor(UiStyle.textSecondary)
        label.setPadding(8, 0, 8, 0)
        val bar = SeekBar(this)
        bar.layoutDirection = View.LAYOUT_DIRECTION_LTR
        bar.max = (range.end - start) / step
        bar.progress = (setting.value - start) / step
        label.text = "${setting.value}"
        var dragging = false
        var pendingSend: Runnable? = null
        val sendNow = Runnable {
            pendingSend = null
            val value = start + bar.progress * step
            sendValue(device, key, Value.I32Value(value), "${settingName(key)} = $value")
        }
        bar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar, progress: Int, fromUser: Boolean) {
                label.text = "${start + progress * step}"
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
                label.text = "${s.value}"
            }
        }
        return Pair(root, updater)
    }
 
    // The equalizer as a curve with one point per band (see EqualizerGraphView for how to edit it with the keypad).
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
        val bandNames = (0 until bandCount).map { i ->
            val hz = eq.bandHz[i].toString().toIntOrNull() ?: 0
            if (hz >= 1000) {
                val k = hz / 1000.0
                if (k == k.toInt().toDouble()) "${k.toInt()}k" else String.format(Locale.US, "%.1fk", k)
            } else {
                "$hz"
            }
        }
 
        val root = LinearLayout(this)
        root.orientation = LinearLayout.VERTICAL
        root.addView(controlTitle(settingName(key)), matchWrap())
 
        val graph = EqualizerGraphView(this, bandNames, min, max, scale)
        graph.readOnly = setting.readOnly
        graph.setValues(IntArray(bandCount) { setting.value.getOrElse(it) { 0 }.toInt() })
        graph.onCommit = { newValues ->
            sendValue(device, key, Value.I16VecValue(newValues.map { it.toShort() }), settingName(key))
        }
        root.addView(graph, matchWrap())
 
        val updater: (Any?) -> Unit = { s ->
            if (s is Setting.EqualizerSetting) {
                graph.setValues(IntArray(bandCount) { s.value.getOrElse(it) { 0 }.toInt() })
            }
        }
        return Pair(root, updater)
    }
 
    // ---------- choices ----------
 
    // A sheet that slides up from the bottom with a list of options and a round mark at the chosen one.
    private fun showSheet(title: String, labels: List<String>, checked: Int, onPick: (Int) -> Unit) {
        val dialog = Dialog(this, android.R.style.Theme_Holo_Light_Dialog_NoActionBar)
        val sheet = LinearLayout(this)
        sheet.orientation = LinearLayout.VERTICAL
        rtl(sheet)
        val radius = dpi(18f).toFloat()
        val background = GradientDrawable()
        background.setColor(UiStyle.card)
        background.cornerRadii = floatArrayOf(radius, radius, radius, radius, 0f, 0f, 0f, 0f)
        sheet.background = background
        sheet.setPadding(dpi(12f), dpi(14f), dpi(12f), dpi(12f))
        sheet.addView(smallText(title, 16f, UiStyle.textPrimary, true), matchWrap())
 
        val list = LinearLayout(this)
        list.orientation = LinearLayout.VERTICAL
        var focusRow: View? = null
        labels.forEachIndexed { i, label ->
            val row = LinearLayout(this)
            row.orientation = LinearLayout.HORIZONTAL
            row.gravity = Gravity.CENTER_VERTICAL
            row.isFocusable = true
            row.isClickable = true
            row.background = UiStyle.focusable(this, UiStyle.card, 8f)
            row.setPadding(dpi(8f), dpi(10f), dpi(8f), dpi(10f))
            row.addView(smallText(label, 16f, UiStyle.textPrimary, i == checked), weightParams())
            val dot = RadioDot(this)
            dot.checked = i == checked
            row.addView(dot)
            row.setOnClickListener {
                dialog.dismiss()
                onPick(i)
            }
            list.addView(row, matchWrap())
            if (i == checked || (focusRow == null && i == 0)) focusRow = row
        }
        val scroll = ScrollView(this)
        scroll.addView(list)
        sheet.addView(scroll, matchWrap())
 
        dialog.setContentView(sheet)
        val window = dialog.window
        if (window != null) {
            window.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            window.setGravity(Gravity.BOTTOM)
            window.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        }
        dialog.show()
        focusRow?.requestFocus()
    }
 
    private fun pressIcon(key: String): IconKind {
        val k = keyNorm(key)
        return when {
            k.contains("slide") -> IconKind.SLIDE
            k.contains("triple") -> IconKind.DOT3
            k.contains("double") -> IconKind.DOT2
            k.contains("long") -> IconKind.LINE
            else -> IconKind.DOT1
        }
    }
 
    // A button setting like in the original app: an icon for the kind of press, its name, the current action
    // underneath and an arrow. Opens a sheet with the possible actions.
    private fun pressRowControl(
        device: Any,
        key: String,
        options: List<String>,
        labels: List<String>,
        allowNone: Boolean,
        currentOf: (Any?) -> String?,
        makeValue: (String?) -> Value,
    ): Pair<View, (Any?) -> Unit> {
        val title = settingName(key).substringAfter(" – ")
        val entries = entriesOf(options, labels, allowNone)
        var currentOption: String? = null
 
        val row = LinearLayout(this)
        row.orientation = LinearLayout.HORIZONTAL
        row.gravity = Gravity.CENTER_VERTICAL
        row.isFocusable = true
        row.isClickable = true
        row.background = UiStyle.focusable(this, UiStyle.card, 8f)
        row.setPadding(dpi(8f), dpi(10f), dpi(8f), dpi(10f))
 
        val iconParams = LinearLayout.LayoutParams(dpi(24f), dpi(24f))
        iconParams.marginEnd = dpi(12f)
        row.addView(IconView(this, pressIcon(key), UiStyle.blue), iconParams)
 
        val column = LinearLayout(this)
        column.orientation = LinearLayout.VERTICAL
        column.addView(smallText(title, 16f, UiStyle.textPrimary))
        val subtitle = smallText("", 14f, UiStyle.textSecondary)
        column.addView(subtitle)
        row.addView(column, weightParams())
        row.addView(OfficialIconView(this, OfficialIcon.ARROW_RIGHT, UiStyle.textSecondary), LinearLayout.LayoutParams(dpi(18f), dpi(18f)))
 
        row.setOnClickListener {
            val checked = entries.indexOfFirst { it.first == currentOption }
            showSheet(title, entries.map { it.second }, checked) { which ->
                val option = entries[which].first
                currentOption = option
                subtitle.text = entries[which].second
                sendValue(device, key, makeValue(option), "$title = ${entries[which].second}")
            }
        }
        val updater: (Any?) -> Unit = { s ->
            currentOption = currentOf(s)
            subtitle.text = entries.firstOrNull { it.first == currentOption }?.second ?: ""
        }
        return Pair(row, updater)
    }
 
    // A background for the cards of the equalizer profiles: the chosen one in the blue gradient.
    private fun profileBackground(selected: Boolean): StateListDrawable {
        val radius = dpi(12f).toFloat()
        val stroke = dpi(2f)
        if (!selected) return UiStyle.focusable(this, UiStyle.card, 12f)
        val colors = intArrayOf(0xFF53D6FF.toInt(), 0xFF329DFF.toInt())
        val focused = GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT, colors)
        focused.cornerRadius = radius
        focused.setStroke(stroke, UiStyle.blueDark)
        val normal = GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT, colors)
        normal.cornerRadius = radius
        val states = StateListDrawable()
        states.addState(intArrayOf(android.R.attr.state_focused), focused)
        states.addState(intArrayOf(android.R.attr.state_pressed), focused)
        states.addState(intArrayOf(), normal)
        return states
    }
 
    // The equalizer profiles as a list of cards (like the "Sounds" list of the original app).
    private fun profileCardsControl(
        device: Any,
        key: String,
        options: List<String>,
        labels: List<String>,
        currentOf: (Any?) -> String?,
        makeValue: (String?) -> Value,
    ): Pair<View, (Any?) -> Unit> {
        val title = settingName(key)
        val entries = entriesOf(options, labels, true, "מותאם אישית")
        val root = LinearLayout(this)
        root.orientation = LinearLayout.VERTICAL
        root.addView(controlTitle(title), matchWrap())
 
        val cards = ArrayList<ProfileCard>()
        fun highlight(current: String?) {
            cards.forEach { c ->
                val selected = c.option == current
                c.root.background = profileBackground(selected)
                c.icon.setTint(if (selected) Color.WHITE else UiStyle.blue)
                c.title.setTextColor(if (selected) Color.WHITE else UiStyle.textPrimary)
                c.title.setTypeface(null, if (selected) Typeface.BOLD else Typeface.NORMAL)
                val markRadius = dpi(11f).toFloat()
                c.mark.background = if (selected) {
                    UiStyle.rounded(0x33FFFFFF, markRadius, Color.WHITE, dpi(2f))
                } else {
                    UiStyle.rounded(Color.WHITE, markRadius, UiStyle.track, dpi(2f))
                }
                c.mark.setTint(if (selected) Color.WHITE else Color.TRANSPARENT)
            }
        }
 
        entries.forEach { (option, label) ->
            val card = LinearLayout(this)
            card.orientation = LinearLayout.HORIZONTAL
            card.gravity = Gravity.CENTER_VERTICAL
            card.isFocusable = true
            card.isClickable = true
            card.setPadding(dpi(12f), dpi(12f), dpi(12f), dpi(12f))
            val icon = OfficialIconView(this, OfficialIcon.TAB_EQ, UiStyle.blue)
            val iconParams = LinearLayout.LayoutParams(dpi(24f), dpi(24f))
            iconParams.marginEnd = dpi(10f)
            card.addView(icon, iconParams)
            val text = smallText(label, 16f, UiStyle.textPrimary)
            card.addView(text, weightParams())
            val mark = OfficialIconView(this, OfficialIcon.CHECK, Color.TRANSPARENT)
            card.addView(mark, LinearLayout.LayoutParams(dpi(22f), dpi(22f)))
            card.setOnClickListener {
                if (!updatingUi) {
                    sendValue(device, key, makeValue(option), "$title = $label")
                    highlight(option)
                }
            }
            cards.add(ProfileCard(option, card, icon, text, mark))
            val params = matchWrap()
            params.setMargins(0, dpi(3f), 0, dpi(3f))
            root.addView(card, params)
        }
        highlight(null)
 
        val updater: (Any?) -> Unit = { s -> highlight(currentOf(s)) }
        return Pair(root, updater)
    }
 
    // Up to 2 options (or a refresh rate): a row of rounded buttons. 3 or 4 options: rows with a round mark. More
    // options: a row with the current choice that opens a sheet. With allowNone, "ללא" selects "no value".
    private fun choiceControl(
        device: Any,
        key: String,
        options: List<String>,
        labels: List<String>,
        allowNone: Boolean,
        currentOf: (Any?) -> String?,
        makeValue: (String?) -> Value,
    ): Pair<View, (Any?) -> Unit> {
        val title = settingName(key)
        val entries = entriesOf(options, labels, allowNone)
 
        if (entries.size > 4) {
            var currentOption: String? = null
            var valueView: TextView? = null
            val rowViews = UiStyle.row(this, title) {
                val checked = entries.indexOfFirst { it.first == currentOption }
                showSheet(title, entries.map { it.second }, checked) { which ->
                    val option = entries[which].first
                    currentOption = option
                    valueView?.text = entries[which].second
                    sendValue(device, key, makeValue(option), "$title = ${entries[which].second}")
                }
            }
            valueView = rowViews.value
            val updater: (Any?) -> Unit = { s ->
                currentOption = currentOf(s)
                valueView?.text = entries.firstOrNull { it.first == currentOption }?.second ?: ""
            }
            return Pair(rowViews.root, updater)
        }
 
        val root = LinearLayout(this)
        root.orientation = LinearLayout.VERTICAL
        root.addView(controlTitle(title), matchWrap())
 
        if (entries.size <= 2 || keyNorm(key).contains("refreshrate")) {
            val pills = ArrayList<Pair<String?, Button>>()
            fun highlightPills(current: String?) {
                pills.forEach { UiStyle.setPillSelected(this, it.second, it.first == current) }
            }
            val buttonRow = LinearLayout(this)
            buttonRow.orientation = LinearLayout.HORIZONTAL
            val margin = dpi(3f)
            entries.forEach { (option, label) ->
                val pill = UiStyle.pill(this, label) {
                    if (!updatingUi) {
                        sendValue(device, key, makeValue(option), "$title = $label")
                        highlightPills(option)
                    }
                }
                val params = weightParams()
                params.setMargins(margin, margin, margin, margin)
                buttonRow.addView(pill, params)
                pills.add(Pair(option, pill))
            }
            root.addView(buttonRow, matchWrap())
            val updater: (Any?) -> Unit = { s -> highlightPills(currentOf(s)) }
            return Pair(root, updater)
        }
 
        val items = ArrayList<RadioItem>()
        fun highlight(current: String?) {
            items.forEach {
                val selected = it.option == current
                it.dot.checked = selected
                it.label.setTypeface(null, if (selected) Typeface.BOLD else Typeface.NORMAL)
            }
        }
        entries.forEach { (option, label) ->
            val row = LinearLayout(this)
            row.orientation = LinearLayout.HORIZONTAL
            row.gravity = Gravity.CENTER_VERTICAL
            row.isFocusable = true
            row.isClickable = true
            row.background = UiStyle.focusable(this, UiStyle.card, 8f)
            row.setPadding(dpi(8f), dpi(8f), dpi(8f), dpi(8f))
            val text = smallText(label, 16f, UiStyle.textPrimary)
            val dot = RadioDot(this)
            row.addView(text, weightParams())
            row.addView(dot)
            row.setOnClickListener {
                if (!updatingUi) {
                    sendValue(device, key, makeValue(option), "$title = $label")
                    highlight(option)
                }
            }
            items.add(RadioItem(option, row, dot, text))
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
        root.addView(controlTitle(settingName(key)), matchWrap())
 
        val boxes = ArrayList<Pair<String, CheckBox>>()
        val options = setting.setting.options
        val labels = setting.setting.localizedOptions
        options.forEachIndexed { i, option ->
            val box = CheckBox(this)
            box.text = optionText(option, labels.getOrElse(i) { option })
            box.textSize = 14f
            box.setTextColor(UiStyle.textPrimary)
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
 
    // ---------- ambient sound card ----------
 
    // The first card of the sound tab, like the original app: two options with a round mark.
    // "Ambient sound" shows one bar of 11 segments: transparency 1-5 on the left (1 is the strongest), a person icon
    // for "normal" in the middle and noise canceling 6-10 on the right (10 is the strongest). Under the bar are
    // "transparency" on the left, the current mode in the middle and "noise canceling" on the right.
    // "Airplane mode" shows the current air pressure and, when chosen, how the pressure is updated.
    // Choosing something sends the mode (and the level) to the earbuds together.
    private inner class AmbientBar(
        private val device: Any,
        private val modeKey: String,
        private val ancKey: String,
        private val transKey: String?,
        private val airplaneKey: String?,
        mode: Setting.SelectSetting,
        anc: Setting.I32RangeSetting,
        trans: Setting.I32RangeSetting?,
        airplane: Setting.SelectSetting?,
        pressure: Setting.InformationSetting?,
    ) {
        private val modeOptions: List<String> = mode.setting.options
        private val ancStart = anc.setting.start
        private val ancStep = if (anc.setting.step <= 0) 1 else anc.setting.step
        private val ancCount = (anc.setting.end - ancStart) / ancStep + 1
        private val transStart = trans?.setting?.start ?: 1
        private val transStep = if (trans == null || trans.setting.step <= 0) 1 else trans.setting.step
        private val transCount = if (trans == null) 5 else (trans.setting.end - transStart) / transStep + 1
        private var lastTransLevel = transCount
        private var lastAncLevel = ancCount
        private var lastAmbientOption: String? = null
        private var airplaneSelected = false
 
        // The empty label in the middle is drawn as a small person icon.
        private val bar = SegmentBar(
            this@MainActivity,
            (1..transCount).map { it.toString() } + "" + (transCount + 1..transCount + ancCount).map { it.toString() },
        )
        private val ambientDot = RadioDot(this@MainActivity)
        private val airplaneDot = RadioDot(this@MainActivity)
        private val currentCaption = smallText("", 14f, UiStyle.textSecondary)
        private val barBox = LinearLayout(this@MainActivity)
        private val airplaneBox = LinearLayout(this@MainActivity)
        private val airplaneSubtitle = smallText("", 14f, UiStyle.textSecondary)
        private val airplanePills = ArrayList<Pair<String, Button>>()
        private var pressureUpdater: ((Any?) -> Unit)? = null
        val view: LinearLayout = LinearLayout(this@MainActivity)
 
        init {
            view.orientation = LinearLayout.VERTICAL
 
            // Ambient sound: the round mark, the bar and the captions under it.
            view.addView(
                radioRow(ambientDot, "צליל סביבה", null) {
                    if (airplaneSelected) setMode(lastAmbientOption ?: optionFor("normal"))
                },
                matchWrap(),
            )
            barBox.orientation = LinearLayout.VERTICAL
            val barParams = matchWrap()
            barParams.setMargins(dpi(12f), dpi(16f), dpi(12f), 0)
            barBox.addView(bar, barParams)
            // Under the bar, like the original: the transparency icon on the left, the current mode in the
            // middle and the noise canceling icon on the right.
            val captions = LinearLayout(this@MainActivity)
            captions.orientation = LinearLayout.HORIZONTAL
            captions.layoutDirection = View.LAYOUT_DIRECTION_LTR
            captions.gravity = Gravity.CENTER_VERTICAL
            currentCaption.gravity = Gravity.CENTER
            currentCaption.textSize = 14f
            captions.addView(
                OfficialIconView(this@MainActivity, OfficialIcon.AMBIENT_TRANSPARENCY, UiStyle.textSecondary),
                LinearLayout.LayoutParams(dpi(20f), dpi(20f)),
            )
            captions.addView(currentCaption, weightParams())
            captions.addView(
                OfficialIconView(this@MainActivity, OfficialIcon.AMBIENT_ANC, UiStyle.textSecondary),
                LinearLayout.LayoutParams(dpi(20f), dpi(20f)),
            )
            captions.setPadding(dpi(12f), dpi(8f), dpi(12f), dpi(20f))
            barBox.addView(captions, matchWrap())
            view.addView(barBox, matchWrap())
            bar.onCommit = { index -> send(index) }
 
            view.addView(UiStyle.divider(this@MainActivity), UiStyle.dividerParams(this@MainActivity))
 
            // Airplane mode: the round mark with the current pressure, and the update choice when chosen.
            view.addView(
                radioRow(airplaneDot, "מצב טיסה", airplaneSubtitle) {
                    if (!airplaneSelected) setMode(optionFor("airplane"))
                },
                matchWrap(),
            )
            airplaneBox.orientation = LinearLayout.VERTICAL
            if (airplane != null && airplaneKey != null) {
                val pillRow = LinearLayout(this@MainActivity)
                pillRow.orientation = LinearLayout.HORIZONTAL
                val margin = dpi(3f)
                val localized = airplane.setting.localizedOptions
                airplane.setting.options.forEachIndexed { i, option ->
                    val label = optionText(option, localized.getOrElse(i) { option })
                    val pill = UiStyle.pill(this@MainActivity, label) {
                        sendValue(device, airplaneKey, Value.StringValue(option), label)
                    }
                    val params = weightParams()
                    params.setMargins(margin, margin, margin, margin)
                    pillRow.addView(pill, params)
                    airplanePills.add(Pair(option, pill))
                }
                airplaneBox.addView(pillRow, matchWrap())
            }
            if (pressure != null) {
                val box = pressureControl(HebrewLabels.value(pressure.translatedValue)) {
                    HebrewLabels.value((it as? Setting.InformationSetting)?.translatedValue ?: "")
                }
                pressureUpdater = box.second
                val params = matchWrap()
                params.setMargins(dpi(4f), dpi(6f), dpi(4f), dpi(4f))
                airplaneBox.addView(box.first, params)
            }
            airplaneBox.visibility = View.GONE
            view.addView(airplaneBox, matchWrap())
        }
 
        // A row with a round mark, a title and (optionally) a line of gray text under the title.
        private fun radioRow(dot: RadioDot, title: String, subtitle: TextView?, onClick: () -> Unit): LinearLayout {
            val row = LinearLayout(this@MainActivity)
            row.orientation = LinearLayout.HORIZONTAL
            row.gravity = Gravity.CENTER_VERTICAL
            row.isFocusable = true
            row.isClickable = true
            row.background = UiStyle.focusable(this@MainActivity, UiStyle.card, 8f)
            row.setPadding(dpi(8f), dpi(10f), dpi(8f), dpi(10f))
            row.addView(dot)
            val column = LinearLayout(this@MainActivity)
            column.orientation = LinearLayout.VERTICAL
            column.addView(smallText(title, 16f, UiStyle.textPrimary))
            if (subtitle != null) column.addView(subtitle)
            val params = weightParams()
            params.marginStart = dpi(10f)
            row.addView(column, params)
            row.setOnClickListener { onClick() }
            return row
        }
 
        private fun optionFor(fragment: String): String? =
            modeOptions.firstOrNull { it.lowercase(Locale.US).contains(fragment) }
 
        private fun setMode(option: String?) {
            if (option == null) return
            sendValue(device, modeKey, Value.StringValue(option), "צליל סביבה")
        }
 
        private fun send(index: Int) {
            val pairs = ArrayList<SettingIdValuePair>()
            val description: String
            if (index < transCount) {
                val option = optionFor("transparen") ?: return
                val level = transCount - index
                pairs.add(SettingIdValuePair(modeKey, Value.StringValue(option)))
                if (transKey != null) {
                    pairs.add(SettingIdValuePair(transKey, Value.I32Value(transStart + (level - 1) * transStep)))
                }
                description = "שקיפות, רמה $level"
            } else if (index == transCount) {
                val option = optionFor("normal") ?: return
                pairs.add(SettingIdValuePair(modeKey, Value.StringValue(option)))
                description = "רגיל"
            } else {
                val option = optionFor("noise") ?: return
                val level = index - transCount
                pairs.add(SettingIdValuePair(modeKey, Value.StringValue(option)))
                pairs.add(SettingIdValuePair(ancKey, Value.I32Value(ancStart + (level - 1) * ancStep)))
                description = "ביטול רעשים, רמה $level"
            }
            sendValues(device, pairs, description)
        }
 
        // Moves everything to the state reported by the device.
        fun update(
            mode: Setting.SelectSetting?,
            anc: Setting.I32RangeSetting?,
            trans: Setting.I32RangeSetting?,
            airplane: Setting.SelectSetting?,
            pressure: Setting.InformationSetting?,
        ) {
            if (anc != null) lastAncLevel = ((anc.value - ancStart) / ancStep + 1).coerceIn(1, ancCount)
            if (trans != null) lastTransLevel = ((trans.value - transStart) / transStep + 1).coerceIn(1, transCount)
            val modeValue = mode?.value
            if (modeValue != null) {
                val current = modeValue.lowercase(Locale.US)
                airplaneSelected = current.contains("airplane")
                ambientDot.checked = !airplaneSelected
                airplaneDot.checked = airplaneSelected
                barBox.visibility = if (airplaneSelected) View.GONE else View.VISIBLE
                airplaneBox.visibility = if (airplaneSelected) View.VISIBLE else View.GONE
                airplaneSubtitle.visibility = if (airplaneSelected) View.GONE else View.VISIBLE
                if (!airplaneSelected) lastAmbientOption = modeValue
                when {
                    current.contains("transparen") -> {
                        bar.setLevelFromDevice(transCount - lastTransLevel)
                        currentCaption.text = "שקיפות"
                    }
                    current.contains("normal") -> {
                        bar.setLevelFromDevice(transCount)
                        currentCaption.text = "רגיל"
                    }
                    current.contains("noise") -> {
                        bar.setLevelFromDevice(transCount + lastAncLevel)
                        currentCaption.text = "ביטול רעשים"
                    }
                }
            }
            if (pressure != null) {
                airplaneSubtitle.text = "לחץ האוויר הנוכחי: " + HebrewLabels.value(pressure.translatedValue)
                pressureUpdater?.invoke(pressure)
            }
            if (airplane != null) {
                val chosen = airplane.value
                airplanePills.forEach { UiStyle.setPillSelected(this@MainActivity, it.second, it.first == chosen) }
            }
        }
    }
 
    private fun updateAmbientBar(device: Any, items: List<SettingItem>) {
        fun find(vararg names: String): SettingItem? = items.firstOrNull { keyNorm(it.key) in names }
        val modeItem = find("ambientsoundmode")
        val ancItem = find("manualnoisecanceling", "manualnoisecancelling")
        val transItem = find("manualtransparency")
        val airplaneItem = find("airplanemode")
        val pressureItem = find("airpressure")
        val mode = modeItem?.setting as? Setting.SelectSetting
        val anc = ancItem?.setting as? Setting.I32RangeSetting
        val trans = transItem?.setting as? Setting.I32RangeSetting
        val airplane = airplaneItem?.setting as? Setting.SelectSetting
        val pressure = pressureItem?.setting as? Setting.InformationSetting
        if (ambientBar == null) {
            if (modeItem == null || ancItem == null || mode == null || anc == null) return
            val created = AmbientBar(
                device, modeItem.key, ancItem.key, transItem?.key,
                if (airplane != null) airplaneItem?.key else null,
                mode, anc, trans, airplane, pressure,
            )
            ambientBar = created
            ambientHandled.add(keyNorm(modeItem.key))
            ambientHandled.add(keyNorm(ancItem.key))
            if (transItem != null) ambientHandled.add(keyNorm(transItem.key))
            if (airplane != null && airplaneItem != null) ambientHandled.add(keyNorm(airplaneItem.key))
            if (pressure != null && pressureItem != null) ambientHandled.add(keyNorm(pressureItem.key))
            val card = sectionCard(modeItem.category)
            if (card.childCount > 0) card.addView(UiStyle.divider(this), UiStyle.dividerParams(this))
            card.addView(created.view, matchWrap())
        }
        ambientBar?.update(mode, anc, trans, airplane, pressure)
    }
 
    // ---------- lifecycle ----------
 
    private val APP_VERSION = "v19-ממשק רשמי"
 
    override fun onCreate(savedInstanceState: Bundle?) {
        // A light theme without the system title bar (the app draws its own top bars).
        setTheme(android.R.style.Theme_Holo_Light_NoActionBar)
        super.onCreate(savedInstanceState)
        showAll = prefs.getBoolean("show_all", false)
 
        val filter = IntentFilter()
        filter.addAction(BluetoothDevice.ACTION_FOUND)
        filter.addAction(BluetoothDevice.ACTION_BOND_STATE_CHANGED)
        filter.addAction(BluetoothAdapter.ACTION_DISCOVERY_FINISHED)
        registerReceiver(scanReceiver, filter)
 
        showList()
        line("גרסה: $APP_VERSION")
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
                if (moreMode && moreCategory != null) {
                    showMoreMenu()
                } else if (moreMode) {
                    setMoreMode(false)
                } else {
                    autoConnectDone = true
                    resumeMac = null
                    disconnect("חזרה")
                    showList()
                }
            }
            Screen.PICKER -> showList()
            Screen.LIST -> {
                if (listTab != ListTab.DEVICES) showListTab(ListTab.DEVICES) else super.onBackPressed()
            }
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
 
