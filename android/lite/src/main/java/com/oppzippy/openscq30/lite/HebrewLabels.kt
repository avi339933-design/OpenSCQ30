package com.oppzippy.openscq30.lite
 
import java.util.Locale
 
/**
 * All Hebrew translations of setting names, categories, option labels and values.
 *
 * The wording follows the official Soundcore app's Hebrew texts (for example "נרתיק הטעינה" for the case and
 * "אוזנייה שמאלית" for the left earbud).
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
        "equalizer" to "אקולייזר",
        "buttons" to "בקרים",
        "buttonconfiguration" to "הגדרת בקרים",
        "deviceinformation" to "פרטי המכשיר",
        "soundmodes" to "מצבי סאונד",
        "battery" to "סוללה",
        "audio" to "שמע",
        "connection" to "חיבור",
        "information" to "מידע",
        "case" to "נרתיק הטעינה",
        "casesettings" to "הגדרות נרתיק הטעינה",
        "miscellaneous" to "שונות",
        "equalizerimportexport" to "ייבוא וייצוא אקולייזר",
        // sound modes
        "ambientsoundmode" to "מצב צליל סביבה",
        "noisecancelingmode" to "מצב ביטול רעשים",
        "transparencymode" to "מצב שקיפות",
        "manualtransparency" to "עוצמת שקיפות ידנית",
        "windnoisesuppression" to "הפחתת רעשי רוח",
        "windnoisereduction" to "הפחתת רעשי רוח",
        "windnoise" to "הפחתת רעשי רוח",
        "windnoisedetected" to "זוהה רעש רוח",
        "transparencymodeincycle" to "שקיפות במחזור הבקרים",
        "noisecancelingmodeincycle" to "ביטול רעשים במחזור הבקרים",
        "normalmodeincycle" to "מצב רגיל במחזור הבקרים",
        "noisecancelingmodetype" to "סוג ביטול הרעשים",
        "adaptivenoisecancelling" to "ביטול רעשים אדפטיבי",
        "manualnoisecancelling" to "ביטול רעשים ידני",
        "ambientsound" to "צליל סביבה",
        "customnoisecanceling" to "ביטול רעשים מותאם אישית",
        "customnoisecancelling" to "ביטול רעשים מותאם אישית",
        "noisecancelinglevel" to "רמת ביטול רעשים",
        "transparencylevel" to "עוצמת שקיפות",
        "realtimenoisedetection" to "זיהוי רעשים בזמן אמת",
        "commuter" to "מצב נוסעים רב-אופני",
        "commutermode" to "מצב נוסעים רב-אופני",
        "multimodalcommuter" to "מצב נוסעים רב-אופני",
        "loudnoiseadaptive" to "התאמה לרעש חזק",
        "adaptiveloudnoise" to "התאמה לרעש חזק",
        "podcastmode" to "מצב פודקאסט",
        // battery and device
        "lowbatteryprompt" to "הודעת סוללה חלשה",
        "batterylevel" to "סוללה",
        "batterylevelleft" to "סוללה – אוזנייה שמאלית",
        "batterylevelright" to "סוללה – אוזנייה ימנית",
        "batterylevelcase" to "סוללה – נרתיק הטעינה",
        "casebatterylevel" to "סוללה – נרתיק הטעינה",
        "isleftcharging" to "אוזנייה שמאלית נטענת",
        "isrightcharging" to "אוזנייה ימנית נטענת",
        "ischargingleft" to "אוזנייה שמאלית נטענת",
        "ischargingright" to "אוזנייה ימנית נטענת",
        "firmwareversion" to "גרסת קושחה",
        "firmwareversionleft" to "גרסת קושחה – אוזנייה שמאלית",
        "firmwareversionright" to "גרסת קושחה – אוזנייה ימנית",
        "casefirmwareversion" to "גרסת קושחה – נרתיק הטעינה",
        "serialnumber" to "מספר סידורי",
        "caseserialnumber" to "מספר סידורי של נרתיק הטעינה",
        "dualconnections" to "חיבורים כפולים",
        "dualconnectionsdevices" to "מכשירים מחוברים",
        "airpressure" to "לחץ אוויר",
        "barometer" to "ברומטר",
        "atmospheric" to "לחץ אטמוספרי",
        "currentairpressure" to "לחץ האוויר הנוכחי",
        "airpressureupdate" to "עדכון לחץ אוויר",
        "airpressureupdatemode" to "מצב עדכון לחץ אוויר",
        "twsstatus" to "מצב TWS",
        "hostdevice" to "אוזנייה ראשית",
        // sound and misc
        "presetequalizerprofile" to "פרופיל אקולייזר מובנה",
        "customequalizerprofile" to "פרופיל אקולייזר מותאם",
        "volumeadjustments" to "אקולייזר",
        "limithighvolume" to "הגבלת ווליום גבוה",
        "limithighvolumedblimit" to "מגבלת דציבלים להגבלת וולום",
        "limithighvolumerefreshrate" to "קצב רענון הגבלת וולום",
        "spatialaudio" to "שמע מרחבי",
        "spatialaudiomusicmode" to "מצב מוזיקה בשמע מרחבי",
        "musicmodestatic" to "מצב מוזיקה (סטטי)",
        "staticmusicmode" to "מצב מוזיקה (סטטי)",
        "musicmodetracking" to "מצב מוזיקה (מעקב ראש)",
        "headtrackingmusicmode" to "מצב מוזיקה (מעקב ראש)",
        "autopoweroff" to "כיבוי אוטומטי",
        "touchtone" to "צליל מגע",
        "wearingtone" to "צליל הרכבה",
        "wearingdetection" to "זיהוי הרכבה",
        "gamingmode" to "מצב משחק",
        "easychat" to "שיחה קלה",
        "easychatwaittime" to "זמן המתנה של שיחה קלה",
        "soundleakcompensation" to "פיצוי דליפת צליל",
        "adaptivesoundleakagecompensation" to "פיצוי אדפטיבי על דליפת צליל",
        "ldac" to "LDAC",
        "caselanguage" to "שפת נרתיק הטעינה",
        "defaultdisplay" to "תצוגת ברירת מחדל",
        "functionmenu" to "תפריט פונקציות",
        "exportcustomequalizerprofiles" to "ייצוא פרופילי אקולייזר מותאמים",
        "exportcustomequalizerprofilesoutput" to "פלט ייצוא פרופילי אקולייזר מותאמים",
        "importcustomequalizerprofiles" to "ייבוא פרופילי אקולייזר מותאמים",
        "resetbuttonstodefault" to "איפוס בקרים לברירת מחדל",
        "controllock" to "נעילת בקרים",
        "controllocked" to "נעילת בקרים",
        // buttons
        "leftsinglepress" to "אוזנייה שמאלית – לחיצה אחת",
        "leftdoublepress" to "אוזנייה שמאלית – לחיצה כפולה",
        "lefttriplepress" to "אוזנייה שמאלית – לחיצה משולשת",
        "leftlongpress" to "אוזנייה שמאלית – לחיצה ארוכה",
        "leftslideup" to "אוזנייה שמאלית – החלקה למעלה",
        "leftslidedown" to "אוזנייה שמאלית – החלקה למטה",
        "rightsinglepress" to "אוזנייה ימנית – לחיצה אחת",
        "rightdoublepress" to "אוזנייה ימנית – לחיצה כפולה",
        "righttriplepress" to "אוזנייה ימנית – לחיצה משולשת",
        "rightlongpress" to "אוזנייה ימנית – לחיצה ארוכה",
        "rightslideup" to "אוזנייה ימנית – החלקה למעלה",
        "rightslidedown" to "אוזנייה ימנית – החלקה למטה",
        // options
        "noisecanceling" to "ביטול רעשים",
        "noisecancelling" to "ביטול רעשים",
        "anc" to "ביטול רעשים",
        "transparency" to "שקיפות",
        "transparent" to "שקיפות",
        "ambient" to "צליל סביבה",
        "normal" to "רגיל",
        "none" to "ללא",
        "airplanemode" to "מצב טיסה",
        "adaptive" to "אדפטיבי",
        "manual" to "ידני",
        "automatic" to "אוטומטי",
        "auto" to "אוטומטי",
        "automaticupdate" to "עדכון אוטומטי",
        "manualupdate" to "עדכון ידני",
        "custom" to "מותאם אישית",
        "fulltransparency" to "שקיפות מלאה",
        "fully" to "שקיפות מלאה",
        "vocal" to "מצב קול",
        "vocalmode" to "מצב קול",
        "indoor" to "בפנים",
        "outdoor" to "בחוץ",
        "transport" to "תחבורה",
        "off" to "כבוי",
        "on" to "פועל",
        "disabled" to "מושבת",
        "gaming" to "מצב משחק",
        "headtracking" to "מעקב ראש",
        "fixed" to "קבוע",
        "realtime" to "זמן אמת",
        "chinese" to "סינית",
        "english" to "אנגלית",
        "japanese" to "יפנית",
        "remotecamera" to "מצלמה מרחוק",
        "finddevice" to "איתור המכשיר",
        // button actions
        "previoussong" to "השיר הקודם",
        "nextsong" to "השיר הבא",
        "volumeup" to "הגברת עוצמה",
        "volumedown" to "הנמכת עוצמה",
        "playpause" to "ניגון/השהיה",
        "voiceassistant" to "עוזר קולי",
        "answerhangup" to "מענה/ניתוק",
        "hangupreject" to "ניתוק/דחיית שיחה",
        "rejectcall" to "ניתוק/דחיית שיחה",
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
 
    // Values of information settings (shown after the name, like "אוזנייה שמאלית נטענת: לא בטעינה").
    private val values: Map<String, String> = mapOf(
        "connected" to "מחובר",
        "disconnected" to "מנותק",
        "charging" to "בטעינה",
        "notcharging" to "לא בטעינה",
        "left" to "שמאל",
        "right" to "ימין",
    )
 
    // Long and short forms of a time: "15 seconds" / "10s", "1 minute" / "1m", "2 hours" / "2h".
    private val secondsRegex = Regex("^(\\d+)\\s*(?:seconds?|secs?|s)$", RegexOption.IGNORE_CASE)
    private val minutesRegex = Regex("^(\\d+)\\s*(?:minutes?|mins?|m)$", RegexOption.IGNORE_CASE)
    private val hoursRegex = Regex("^(\\d+)\\s*(?:hours?|hrs?|h)$", RegexOption.IGNORE_CASE)
 
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
 
