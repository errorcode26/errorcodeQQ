package com.csguard

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.widget.Toast
import android.content.ContextWrapper

class GuardedContext(
    base: Context,
    private val policyProvider: () -> GuardPolicy,
    private val providerName: String? = null
) : ContextWrapper(base) {

    private val policy: GuardPolicy
        get() = policyProvider()

    companion object {
        private const val TAG = "CSGuard"
    }

    override fun startActivity(intent: Intent?) {
        if (policy.shouldBlock(intent, providerName)) {
            handleBlocked(intent)
            return  // ⛔ THE VOID — super.startActivity() is never called
        }
        super.startActivity(intent)
    }

    override fun startActivity(intent: Intent?, options: Bundle?) {
        if (policy.shouldBlock(intent, providerName)) {
            handleBlocked(intent)
            return  // ⛔ THE VOID
        }
        super.startActivity(intent, options)
    }

    override fun startActivities(intents: Array<out Intent>?) {
        intents?.forEach { if (policy.shouldBlock(it, providerName)) handleBlocked(it) }
        val filtered = intents?.filterNot { policy.shouldBlock(it, providerName) }?.toTypedArray()
            ?: return
        if (filtered.isNotEmpty()) super.startActivities(filtered)
    }

    override fun startActivities(intents: Array<out Intent>?, options: Bundle?) {
        intents?.forEach { if (policy.shouldBlock(it, providerName)) handleBlocked(it) }
        val filtered = intents?.filterNot { policy.shouldBlock(it, providerName) }?.toTypedArray()
            ?: return
        if (filtered.isNotEmpty()) super.startActivities(filtered, options)
    }

    private fun handleBlocked(intent: Intent?) {
        val url = intent?.data?.toString() ?: "(no data)"
        Log.w(TAG, "⛔ BLOCKED external browser launch → $url")
        Log.w(TAG, "   action=${intent?.action} flags=0x${intent?.flags?.toString(16)}")
        if (policy.showToast) {
            Handler(Looper.getMainLooper()).post {
                try {
                    Toast.makeText(
                        this,
                        "CSGuard blocked: ${shortUrl(url)}",
                        Toast.LENGTH_LONG
                    ).show()
                } catch (_: Throwable) { /* never throw on UI */ }
            }
        }
    }

    private fun shortUrl(url: String): String =
        if (url.length > 80) url.take(77) + "..." else url

    private val isStrictlyBlocked: Boolean
        get() = providerName != null && (policy.blockAllUnknown || AllowlistStore.blockedProviders().contains(providerName))

    private fun detectCallerProvider(): String? {
        val trace = Thread.currentThread().stackTrace
        for (element in trace) {
            val className = element.className
            if (className.startsWith("com.csguard")) continue
            if (className.startsWith("com.lagradost")) continue
            if (className.startsWith("android.")) continue
            if (className.startsWith("java.")) continue
            if (className.startsWith("kotlin.")) continue
            if (className.startsWith("dalvik.")) continue

            try {
                val providers = com.lagradost.cloudstream3.APIHolder.allProviders
                for (provider in providers) {
                    val pkg = provider.javaClass.`package`?.name ?: continue
                    if (className.startsWith(pkg)) {
                        return provider.name
                    }
                }
            } catch (_: Throwable) {}
        }
        return null
    }

    private fun isCallerBlocked(): Boolean {
        if (isStrictlyBlocked) return true
        if (providerName == null) {
            val caller = detectCallerProvider()
            if (caller != null && (policy.blockAllUnknown || AllowlistStore.blockedProviders().contains(caller))) {
                return true
            }
        }
        return false
    }

    override fun getSystemService(name: String): Any? {
        when (name) {
            Context.WINDOW_SERVICE,
            Context.NOTIFICATION_SERVICE -> {
                if (policy.blockPopups && isCallerBlocked()) return noOpProxy(name)
            }
            Context.CLIPBOARD_SERVICE -> {
                if (policy.blockClipboard && isCallerBlocked()) return noOpProxy(name)
            }
            Context.VIBRATOR_SERVICE,
            Context.LOCATION_SERVICE,
            Context.AUDIO_SERVICE -> {
                if (isCallerBlocked()) return noOpProxy(name)
            }
        }
        return super.getSystemService(name)
    }

    private fun noOpProxy(serviceName: String): Any? {
        return try {
            val real = super.getSystemService(serviceName) ?: return null
            val interfaces = mutableListOf<Class<*>>()
            var klass: Class<*>? = real.javaClass
            while (klass != null && klass != Any::class.java) {
                interfaces.addAll(klass.interfaces)
                klass = klass.superclass
            }
            if (interfaces.isEmpty()) return null
            java.lang.reflect.Proxy.newProxyInstance(
                real.javaClass.classLoader,
                interfaces.distinct().toTypedArray()
            ) { _, method, _ ->
                when (method.returnType) {
                    Void.TYPE -> null
                    Boolean::class.javaPrimitiveType -> false
                    Int::class.javaPrimitiveType -> 0
                    Long::class.javaPrimitiveType -> 0L
                    else -> null
                }
            }
        } catch (_: Throwable) { null }
    }


    override fun sendBroadcast(intent: Intent?) {
        if (policy.blockBackgroundTasks && isCallerBlocked()) return
        super.sendBroadcast(intent)
    }

    override fun startService(service: Intent?): android.content.ComponentName? {
        if (policy.blockBackgroundTasks && isCallerBlocked()) return null
        return super.startService(service)
    }

    override fun bindService(service: Intent, conn: android.content.ServiceConnection, flags: Int): Boolean {
        if (policy.blockBackgroundTasks && isCallerBlocked()) return false
        return super.bindService(service, conn, flags)
    }

    override fun getSharedPreferences(name: String?, mode: Int): android.content.SharedPreferences {
        if (policy.sandboxPreferences && isCallerBlocked()) {
            return super.getSharedPreferences("sandbox_$name", mode)
        }
        return super.getSharedPreferences(name, mode)
    }
}

data class GuardPolicy(
        val blockKnownAdHosts: Boolean = true,
        val blockAdPaths: Boolean = true,
        val blockAllUnknown: Boolean = true,
        val showToast: Boolean = false,
        
        val blockPopups: Boolean = true,
        val blockClipboard: Boolean = true,
        val blockBackgroundTasks: Boolean = true,
        val sandboxPreferences: Boolean = true,
        val watchdogEnabled: Boolean = true
) {
    companion object {
                val DEFAULT get() = STRICT

                val PERMISSIVE = GuardPolicy(
            blockKnownAdHosts = true,
            blockAdPaths = true,
            blockAllUnknown = false,
            showToast = true,
            blockPopups = false,
            blockClipboard = false,
            blockBackgroundTasks = false,
            sandboxPreferences = false,
            watchdogEnabled = false
        )

                val STRICT = GuardPolicy(
            blockKnownAdHosts = true,
            blockAdPaths = true,
            blockAllUnknown = true,
            showToast = false,
            blockPopups = true,
            blockClipboard = true,
            blockBackgroundTasks = true,
            sandboxPreferences = true,
            watchdogEnabled = true
        )

                val STRICT_VERBOSE = GuardPolicy(
            blockKnownAdHosts = true,
            blockAdPaths = true,
            blockAllUnknown = true,
            showToast = true,
            blockPopups = true,
            blockClipboard = true,
            blockBackgroundTasks = true,
            sandboxPreferences = true,
            watchdogEnabled = true
        )
    }

    fun shouldBlock(intent: Intent?, providerName: String? = null): Boolean {
        if (intent == null) return false
        val action = intent.action
        if (action != Intent.ACTION_VIEW) return false

        val uri: Uri = intent.data ?: return false
        val scheme = uri.scheme?.lowercase() ?: return false

if (scheme != "http" && scheme != "https") return false

        val host = uri.host
        val url = uri.toString()

        val isProviderBlocked = providerName != null && AllowlistStore.blockedProviders().contains(providerName)

        if (isProviderBlocked && !AdBlockList.isHostSafe(host)) {
            return true
        }

        if (blockKnownAdHosts && AdBlockList.isHostBlocked(host)) {
            return true
        }

        if (blockAdPaths && AdBlockList.looksLikeAdPath(url)) {
            return true
        }

        if (blockAllUnknown && !AdBlockList.isHostSafe(host)) {
            return true
        }
        return false
    }
}
