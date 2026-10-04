package com.oppzippy.openscq30.lite
 
import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.Settings
import java.io.File
import java.text.DateFormat
import java.util.Date
import java.util.Locale
 
/**
 * Updates the app from an APK file that was copied to the phone (for example to the Download folder).
 *
 * Only APK files of THIS app (same package name) are offered. The new APK must be signed with the same key as the
 * installed one (the fixed debug keystore of the build), otherwise Android refuses to install it over the old one.
 * On Android 4.4 the installer is started with a plain file:// address, so no FileProvider is needed.
 */
object UpdateManager {
    private class Candidate(val file: File, val versionName: String, val versionCode: Int)
 
    // Folders that are searched (not recursive): the root of the storage, Download folders and an SD card.
    private fun searchFolders(): List<File> {
        val folders = LinkedHashMap<String, File>()
        fun add(folder: File?) {
            if (folder == null) return
            val key = try {
                folder.canonicalPath
            } catch (_: Throwable) {
                folder.absolutePath
            }
            if (!folders.containsKey(key)) folders[key] = folder
        }
 
        val root = Environment.getExternalStorageDirectory()
        add(root)
        if (root != null) {
            add(File(root, "Download"))
            add(File(root, "download"))
            add(File(root, "Downloads"))
        }
        add(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS))
        listOf(
            "/storage/sdcard1",
            "/storage/sdcard1/Download",
            "/storage/extSdCard",
            "/mnt/sdcard2",
            "/mnt/extSdCard",
        ).forEach { add(File(it)) }
        return folders.values.toList()
    }
 
    private fun findApks(activity: Activity): List<Candidate> {
        val result = ArrayList<Candidate>()
        val seen = HashSet<String>()
        for (folder in searchFolders()) {
            val files: Array<File>? = try {
                folder.listFiles()
            } catch (_: Throwable) {
                null
            }
            if (files == null) continue
            for (file in files) {
                if (!file.isFile || !file.name.lowercase(Locale.US).endsWith(".apk")) continue
                if (!seen.add(file.absolutePath)) continue
                val info = try {
                    activity.packageManager.getPackageArchiveInfo(file.absolutePath, 0)
                } catch (_: Throwable) {
                    null
                }
                if (info == null || info.packageName != activity.packageName) continue
                result.add(Candidate(file, info.versionName ?: "?", info.versionCode))
            }
        }
        result.sortByDescending { it.file.lastModified() }
        return result.take(10)
    }
 
    private fun sizeText(bytes: Long): String = String.format(Locale.US, "%.1f MB", bytes / 1048576.0)
 
    // Shows the APK files found on the phone. beforeInstall is called right before the installer starts
    // (the app uses it to disconnect from the earbuds). log shows a message in the app's log.
    fun showUpdateDialog(activity: Activity, beforeInstall: () -> Unit, log: (String) -> Unit) {
        val candidates = findApks(activity)
        if (candidates.isEmpty()) {
            AlertDialog.Builder(activity)
                .setTitle("עדכון מקובץ")
                .setMessage(
                    "לא נמצא קובץ APK של האפליקציה. " +
                        "העתק את הקובץ החדש לתיקיית Download בטלפון ונסה שוב.",
                )
                .setPositiveButton("אישור", null)
                .show()
            return
        }
        val format = DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT)
        val labels = Array<CharSequence>(candidates.size) { i ->
            val c = candidates[i]
            c.file.name + "\n" + sizeText(c.file.length()) + " | " +
                format.format(Date(c.file.lastModified())) + " | " + c.versionName + " (" + c.versionCode + ")"
        }
        AlertDialog.Builder(activity)
            .setTitle("בחר קובץ לעדכון")
            .setItems(labels) { _, which -> confirmInstall(activity, candidates[which], beforeInstall, log) }
            .setNegativeButton("ביטול", null)
            .show()
    }
 
    private fun confirmInstall(
        activity: Activity,
        candidate: Candidate,
        beforeInstall: () -> Unit,
        log: (String) -> Unit,
    ) {
        AlertDialog.Builder(activity)
            .setTitle("להתקין את העדכון?")
            .setMessage(
                candidate.file.name + "\n" +
                    "האפליקציה תיסגר בזמן ההתקנה. שיוכי הדגמים וההגדרות נשמרים.",
            )
            .setPositiveButton("התקן") { _, _ -> startInstall(activity, candidate.file, beforeInstall, log) }
            .setNegativeButton("ביטול", null)
            .show()
    }
 
    // "Unknown sources" must be on to install an APK that does not come from the store.
    private fun unknownSourcesAllowed(activity: Activity): Boolean = try {
        if (Build.VERSION.SDK_INT >= 17) {
            Settings.Global.getInt(activity.contentResolver, Settings.Global.INSTALL_NON_MARKET_APPS, 0) == 1
        } else {
            Settings.Secure.getInt(activity.contentResolver, Settings.Secure.INSTALL_NON_MARKET_APPS, 0) == 1
        }
    } catch (_: Throwable) {
        true
    }
 
    private fun startInstall(
        activity: Activity,
        file: File,
        beforeInstall: () -> Unit,
        log: (String) -> Unit,
    ) {
        if (unknownSourcesAllowed(activity)) {
            launchInstaller(activity, file, beforeInstall, log)
            return
        }
        AlertDialog.Builder(activity)
            .setTitle("מקורות לא ידועים כבויים")
            .setMessage("כדי להתקין עדכון צריך להפעיל 'מקורות לא ידועים' בהגדרות האבטחה של הטלפון.")
            .setPositiveButton("פתח הגדרות") { _, _ ->
                try {
                    activity.startActivity(Intent(Settings.ACTION_SECURITY_SETTINGS))
                } catch (t: Throwable) {
                    log("לא ניתן לפתוח את ההגדרות: ${t.message}")
                }
            }
            .setNegativeButton("נסה בכל זאת") { _, _ -> launchInstaller(activity, file, beforeInstall, log) }
            .show()
    }
 
    private fun launchInstaller(
        activity: Activity,
        file: File,
        beforeInstall: () -> Unit,
        log: (String) -> Unit,
    ) {
        if (!file.exists()) {
            log("הקובץ לא נמצא: ${file.name}")
            return
        }
        beforeInstall()
        try {
            val intent = Intent(Intent.ACTION_VIEW)
            intent.setDataAndType(Uri.fromFile(file), "application/vnd.android.package-archive")
            activity.startActivity(intent)
            log("מתקין את ${file.name}...")
        } catch (t: Throwable) {
            log("התקנת העדכון נכשלה: ${t.message}")
        }
    }
}
 
