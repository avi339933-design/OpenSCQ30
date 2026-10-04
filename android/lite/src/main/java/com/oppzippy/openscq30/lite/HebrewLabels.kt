package com.oppzippy.openscq30.lite
 
import java.util.Locale
 
/**
 * All Hebrew translations of setting names, categories, option labels and values.
 *
 * Keys are lower-case letters and digits only (see norm()), so "Noise Canceling", "noiseCanceling" and
 * "NOISE_CANCELING" are the same key. To translate something new, add a line to [translations].
 * Anything missing here is shown as it comes from the core, and is listed in the log as "חסר תרגום".
 */
object HebrewLabels {
    private fun norm(text: String): String = text.lowercase(Locale.US).replace(Regex("[^a-z0-9]"), "")
 
    private val translations: Map<String, String> = mapOf(
        // categories
        "general" to "כללי",
        "equalizer" to "איקוולייזר",
        "buttons" to "כפתורים",
        "buttonconfiguration" to "הגדרת כפתורים",
        "deviceinformation" to "מידע על המכשיר",
        "soundmodes" to "מצבי סאונד",
        "battery" to "סוללה",
        "audio" to "שמע",
        "connection" to "חיבור",
        "information" to "מידע",
        "case" to "מארז",
        "miscellaneous" to "שונות",
        "equalizerimportexport" to "ייבוא וייצוא איקוולייזר",
        // sound modes
        "ambientsoundmode" to "מצב סאונד סביבתי",
        "noisecancelingmode" to "מצב ביטול רעשים",
        "transparencymode" to "מצב שקיפות",
        "manualtransparency" to "עוצמת שקיפות ידנית",
        "windnoisesuppression" to "דיכוי רעש רוח",
        "windnoisedetected" to "זוהה רעש רוח",
        "transparencymodeincycle" to "שקיפות במחזור הכפתורים",
        "noisecancelingmodeincycle" to "ביטול רעשים במחזור הכפתורים",
        "normalmodeincycle" to "מצב רגיל במחזור הכפתורים",
        "noisecancelingmodetype" to "סוג ביטול הרעשים",
        "adaptivenoisecancelling" to "ביטול רעשים אדפטיבי",
        "manualnoisecancelling" to "ביטול רעשים ידני",
        // battery and device
        "lowbatteryprompt" to "התראת סוללה חלשה",
        "batterylevelleft" to "סוללה – אוזנית שמאל",
        "batterylevelright" to "סוללה – אוזנית ימין",
        "batterylevelcase" to "סוללה – מארז",
        "casebatterylevel" to "סוללה – מארז",
        "isleftcharging" to "אוזנית שמאל נטענת",
        "isrightcharging" to "אוזנית ימין נטענת",
        "ischargingleft" to "אוזנית שמאל נטענת",
        "ischargingright" to "אוזנית ימין נטענת",
        "firmwareversion" to "גרסת קושחה",
        "firmwareversionleft" to "גרסת קושחה – שמאל",
        "firmwareversionright" to "גרסת קושחה – ימין",
        "casefirmwareversion" to "גרסת קושחה – מארז",
        "serialnumber" to "מספר סידורי",
        "caseserialnumber" to "מספר סידורי – מארז",
        "dualconnections" to "חיבור כפול",
        "dualconnectionsdevices" to "מכשירים בחיבור כפול",
        "airpressure" to "לחץ אוויר",
        "twsstatus" to "מצב TWS",
        "hostdevice" to "אוזנית ראשית",
        // sound and misc
        "presetequalizerprofile" to "פרופיל איקוולייזר מובנה",
        "customequalizerprofile" to "פרופיל איקוולייזר מותאם",
        "volumeadjustments" to "איקוולייזר",
        "limithighvolume" to "הגבלת ווליום גבוה",
        "limithighvolumedblimit" to "מגבלת דציבלים להגבלת וולום",
        "limithighvolumerefreshrate" to "קצב רענון הגבלת וולום",
        "spatialaudio" to "סאונד מרחבי",
        "spatialaudiomusicmode" to "מצב מוזיקה בסאונד מרחבי",
        "autopoweroff" to "כיבוי אוטומטי",
        "touchtone" to "צליל מגע",
        "wearingtone" to "צליל הרכבה",
        "wearingdetection" to "זיהוי הרכבה",
        "gamingmode" to "מצב גיימינג",
        "easychat" to "שיחה קלה (Easy Chat)",
        "easychatwaittime" to "זמן המתנה של שיחה קלה",
        "soundleakcompensation" to "פיצוי דליפת סאונד",
        "ldac" to "LDAC",
        "caselanguage" to "שפת המארז",
        "exportcustomequalizerprofiles" to "ייצוא פרופילי איקוולייזר מותאמים",
        "exportcustomequalizerprofilesoutput" to "פלט ייצוא פרופילי איקוולייזר מותאמים",
        "importcustomequalizerprofiles" to "ייבוא פרופילי איקוולייזר מותאמים",
        "resetbuttonstodefault" to "איפוס כפתורים לברירת מחדל",
        // buttons
        "leftsinglepress" to "אוזנית שמאל – לחיצה בודדת",
        "leftdoublepress" to "אוזנית שמאל – לחיצה כפולה",
        "lefttriplepress" to "אוזנית שמאל – לחיצה משולשת",
        "leftlongpress" to "אוזנית שמאל – לחיצה ארוכה",
        "leftslideup" to "אוזנית שמאל – החלקה למעלה",
        "leftslidedown" to "אוזנית שמאל – החלקה למטה",
        "rightsinglepress" to "אוזנית ימין – לחיצה בודדת",
        "rightdoublepress" to "אוזנית ימין – לחיצה כפולה",
        "righttriplepress" to "אוזנית ימין – לחיצה משולשת",
        "rightlongpress" to "אוזנית ימין – לחיצה ארוכה",
        "rightslideup" to "אוזנית ימין – החלקה למעלה",
        "rightslidedown" to "אוזנית ימין – החלקה למטה",
        // options
        "noisecanceling" to "ביטול רעשים",
        "transparency" to "שקיפות",
        "normal" to "רגיל",
        "airplanemode" to "מצב טיסה",
        "adaptive" to "אדפטיבי",
        "manual" to "ידני",
        "custom" to "מותאם אישית",
        "fulltransparency" to "שקיפות מלאה",
        "fully" to "שקיפות מלאה",
        "vocal" to "מצב קול",
        "vocalmode" to "מצב קול",
        "indoor" to "בתוך הבית",
        "outdoor" to "בחוץ",
        "transport" to "תחבורה",
        "off" to "כבוי",
        "on" to "פועל",
        "disabled" to "מושבת",
        "gaming" to "גיימינג",
        "headtracking" to "מעקב ראש",
        "fixed" to "קבוע",
        "realtime" to "זמן אמת",
        "chinese" to "סינית",
        "english" to "אנגלית",
        "japanese" to "יפנית",
        "remotecamera" to "מצלמה מרחוק",
        "finddevice" to "איתור מכשיר",
        // button actions
        "previoussong" to "שיר קודם",
        "nextsong" to "שיר הבא",
        "volumeup" to "הגברת וולום",
        "volumedown" to "הנמכת וולום",
        "playpause" to "ניגון / השהיה",
        "voiceassistant" to "עוזר קולי",
        // equalizer presets
        "soundcoresignature" to "Soundcore (ברירת מחדל)",
        "acoustic" to "אקוסטי",
        "bassbooster" to "מגביר בס",
        "bassreducer" to "מפחית בס",
        "classical" to "קלאסי",
        "podcast" to "פודקאסט",
        "dance" to "ריקוד",
        "deep" to "עמוק",
        "electronic" to "אלקטרוני",
        "flat" to "שטוח",
        "hiphop" to "היפ הופ",
        "jazz" to "ג'אז",
        "latin" to "לטיני",
        "lounge" to "לאונג'",
        "piano" to "פסנתר",
        "pop" to "פופ",
        "rb" to "R&B",
        "rnb" to "R&B",
        "rock" to "רוק",
        "smallspeakers" to "רמקולים קטנים",
        "spokenword" to "דיבור",
        "treblebooster" to "מגביר גבוהים",
        "treblereducer" to "מפחית גבוהים",
    )
 
    // Option labels that the core gives in their own language (norm() would turn them into an empty key).
    private val nativeNames: Map<String, String> = mapOf(
        "中文" to "סינית",
        "日本語" to "יפנית",
    )
 
    // Values of information settings (shown after the name, like "אוזנית שמאל נטענת: לא בטעינה").
    private val values: Map<String, String> = mapOf(
        "connected" to "מחובר",
        "disconnected" to "מנותק",
        "charging" to "בטעינה",
        "notcharging" to "לא בטעינה",
        "left" to "שמאל",
        "right" to "ימין",
    )
 
    private val secondsRegex = Regex("^(\\d+) seconds?$", RegexOption.IGNORE_CASE)
    private val minutesRegex = Regex("^(\\d+) minutes?$", RegexOption.IGNORE_CASE)
    private val hoursRegex = Regex("^(\\d+) hours?$", RegexOption.IGNORE_CASE)
 
    // "15 seconds" -> "15 שניות", "1 minute" -> "דקה", "30 minutes" -> "30 דקות"
    private fun timeText(text: String): String? {
        val t = text.trim()
        secondsRegex.find(t)?.let {
            val n = it.groupValues[1].toInt()
            return if (n == 1) "שנייה" else "$n שניות"
        }
        minutesRegex.find(t)?.let {
            val n = it.groupValues[1].toInt()
            return if (n == 1) "דקה" else "$n דקות"
        }
        hoursRegex.find(t)?.let {
            val n = it.groupValues[1].toInt()
            return if (n == 1) "שעה" else "$n שעות"
        }
        return null
    }
 
    // Name of a setting or a category, or null if there is no translation yet.
    fun name(key: String): String? = translations[norm(key)]
 
    // Label of an option (a button or a check box), or null if there is no translation yet.
    fun option(option: String, localized: String): String? =
        translations[norm(option)]
            ?: translations[norm(localized)]
            ?: nativeNames[localized.trim()]
            ?: timeText(localized)
            ?: timeText(option)
 
    // A value shown after a setting name; unknown values stay as they are.
    fun value(text: String): String = values[norm(text)] ?: timeText(text) ?: text
}
 
