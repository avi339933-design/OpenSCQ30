package com.oppzippy.openscq30.lite

import android.app.Activity
import android.os.Build
import android.os.Bundle
import android.widget.ScrollView
import android.widget.TextView
import com.oppzippy.openscq30.lib.bindings.initNativeLogging
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

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        textView = TextView(this)
        textView.textSize = 16f
        textView.setPadding(24, 24, 24, 24)
        val scrollView = ScrollView(this)
        scrollView.addView(textView)
        setContentView(scrollView)

        line("Android SDK: ${Build.VERSION.SDK_INT}")
        line("ABI: ${Build.CPU_ABI}")

        try {
            System.loadLibrary("openscq30_android")
            initNativeLogging()
            line("OK: native lib + bindings (JNA) loaded")
        } catch (t: Throwable) {
            line("FAIL native/JNA: $t")
            return
        }

        scope.launch {
            try {
                val backend = AndroidRfcommConnectionBackendImpl(applicationContext, scope)
                val devices = backend.devices()
                line("Paired devices: ${devices.size}")
                devices.forEach { line("- ${it.name} (${it.macAddress})") }
            } catch (t: Throwable) {
                line("FAIL devices: $t")
            }
        }
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }
}
