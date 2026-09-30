package com.oppzippy.openscq30.lite

import android.app.Activity
import android.os.Build
import android.os.Bundle
import android.widget.ScrollView
import android.widget.TextView
import com.oppzippy.openscq30.lib.bindings.LanguageIdentifier
import com.oppzippy.openscq30.lib.bindings.initNativeI18n
import com.oppzippy.openscq30.lib.bindings.initNativeLogging
import com.oppzippy.openscq30.lib.bindings.newSession
import com.oppzippy.openscq30.lib.wrapper.PairedDevice
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

class MainActivity : Activity() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private lateinit var textView: TextView
    private val log = StringBuilder()

    private fun line(text: String) {
        log.append(text).append("\n")
        textView.text = log.toString()
    }

    private fun describe(error: Throwable): String {
        val out = StringBuilder()
        var current: Throwable? = error
        var depth = 0
        while (current != null && depth < 4) {
            out.append("[").append(depth).append("] ")
                .append(current.javaClass.name)
                .append(": ")
                .append(current.message)
                .append("\n")
            if (current.cause == null) {
                current.stackTrace.take(3).forEach { out.append("   at ").append(it.toString()).append("\n") }
            }
            current = current.cause
            depth += 1
        }
        return out.toString()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        textView = TextView(this)
        textView.textSize = 12f
        textView.setPadding(16, 16, 16, 16)
        val scrollView = ScrollView(this)
        scrollView.addView(textView)
        setContentView(scrollView)

        line("Android SDK: ${Build.VERSION.SDK_INT}")

        try {
            System.loadLibrary("openscq30_android")
            System.loadLibrary("jnidispatch")
            initNativeLogging()
            initNativeI18n(listOf(LanguageIdentifier("en", null, null, emptyList())))
            line("OK: native + bindings + i18n")
        } catch (t: Throwable) {
            line("FAIL init:\n" + describe(t))
            return
        }

        scope.launch {
            try {
                val backends = connectionBackends(applicationContext, scope)
                val devices = AndroidRfcommConnectionBackendImpl(applicationContext, scope).devices()
                line("Paired bluetooth devices: ${devices.size}")
                devices.forEach { line("- ${it.name} (${it.macAddress})") }

                val target = devices.firstOrNull { it.name.contains("Liberty", ignoreCase = true) }
                if (target == null) {
                    line("No Liberty device found")
                    return@launch
                }
                val mac = target.macAddress
                line("Target: ${target.name}")

                val session = newSession(File(filesDir, "openscq30.db").absolutePath)
                line("Session OK")

                var device = try {
                    session.connectWithBackends(backends, mac)
                } catch (t: Throwable) {
                    line("Connect 1 failed:\n" + describe(t))
                    null
                }

                if (device == null) {
                    try {
                        session.pair(PairedDevice(macAddress = mac, model = "SoundcoreA3954", isDemo = false))
                        line("Pair OK (model guess SoundcoreA3954)")
                        device = try {
                            session.connectWithBackends(backends, mac)
                        } catch (t: Throwable) {
                            line("Connect 2 failed:\n" + describe(t))
                            null
                        }
                    } catch (t: Throwable) {
                        line("Pair failed:\n" + describe(t))
                    }
                }

                if (device == null) {
                    return@launch
                }

                line("CONNECTED, model=${device.model()}")
                device.categories().forEach { category ->
                    line("[$category]")
                    device.settingsInCategory(category).forEach { id ->
                        line("  $id = ${device.setting(id).toString().take(100)}")
                    }
                }
            } catch (t: Throwable) {
                line("FAIL:\n" + describe(t))
            }
        }
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }
}
