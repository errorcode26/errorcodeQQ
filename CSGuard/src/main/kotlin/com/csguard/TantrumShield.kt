package com.csguard

import android.os.Handler
import android.os.Looper
import android.util.Log

object TantrumShield {

    private const val TAG = "CSGuard"

    fun install() {
        installMainThreadShield()
        installBackgroundThreadShield()
    }

    private fun installMainThreadShield() {
        Handler(Looper.getMainLooper()).post {
            while (true) {
                try {
                    Looper.loop()
                    break // only exits if Looper.quit() was called legitimately
                } catch (e: Throwable) {
                    if (isPluginCrash(e)) {
                        Log.w(TAG, "TantrumShield: plugin crash intercepted on main thread: ${e.javaClass.simpleName}")
                        // loop continues — main thread survives
                    } else {
                        throw e
                    }
                }
            }
        }
    }

    private fun installBackgroundThreadShield() {
        val original = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            if (isPluginCrash(throwable)) {
                Log.w(TAG, "TantrumShield: plugin crash intercepted on thread '${thread.name}': ${throwable.javaClass.simpleName}")
            } else {
                original?.uncaughtException(thread, throwable)
            }
        }
    }

    fun isPluginCrash(e: Throwable): Boolean {
        val trace = e.stackTrace ?: return false
        for (element in trace) {
            val cls = element.className
            if (cls.startsWith("com.csguard")) continue
            if (cls.startsWith("com.lagradost")) continue
            if (cls.startsWith("android.")) continue
            if (cls.startsWith("java.")) continue
            if (cls.startsWith("javax.")) continue
            if (cls.startsWith("kotlin.")) continue
            if (cls.startsWith("dalvik.")) continue
            if (cls.startsWith("sun.")) continue
            if (cls.startsWith("okhttp3.")) continue
            if (cls.startsWith("okio.")) continue
            return true
        }
        return false
    }
}
