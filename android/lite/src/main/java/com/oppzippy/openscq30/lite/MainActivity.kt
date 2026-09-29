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

    private fun describe(error: Throwable): String {
        val out = StringBuilder()
        var current: Throwable? = error
        var depth = 0
        while (current != null && depth < 6) {
            out.append("[").append(depth).append("] ")
                .append(current.javaClass.name)
                .append(": ")
                .append(current.message)
                .append("\n")
            val last = current.cause == null
            if (last) {
                current.stackTrace.take(4).forEach { out.append("   at ").append(it.toString()).append("\n") }
            }
            current = current.cause
            depth += 1
        }
        return out.toString()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        textView = TextView(this)
        textView.textSize = 13f
        textView.setPadding(16, 16, 16, 16)
        val scrollView = ScrollView(this)
        scrollView.addView(textView)
        setContentView(scrollView)

        line("Android SDK: ${Build.VERSION.SDK_INT}")
        line("ABI: ${Build.CPU_ABI}")

        try {
            System.loadLibrary("openscq30_android")
            line("OK: openscq30_android loaded")
        } catch (t: Throwable) {
            line("FAIL loadLibrary:\n" + describe(t))
            return
        }

        try {
            Class.forName("com.sun.jna.Native")
            line("OK: JNA Native class loaded")
        } catch (t: Throwable) {
            line("FAIL JNA class:\n" + describe(t))
            return
        }

        try {
            initNativeLogging()
            line("OK: bindings work")
        } catch (t: Throwable) {
            line("FAIL bindings:\n" + describe(t))
            return
        }

        scope.launch {
            try {
                val backend = AndroidRfcommConnectionBackendImpl(applicationContext, scope)
                val devices = backend.devices()
                line("Paired devices: ${devices.size}")
                devices.forEach { line("- ${it.name} (${it.macAddress})") }
            } catch (t: Throwable) {
                line("FAIL devices:\n" + describe(t))
            }
        }
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }
}
