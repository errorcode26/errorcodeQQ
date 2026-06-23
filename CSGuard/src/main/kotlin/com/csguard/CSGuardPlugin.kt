package com.csguard

import android.app.Activity
import android.app.Application
import android.content.Context
import android.content.ContextWrapper
import android.util.Log
import com.lagradost.cloudstream3.actions.VideoClickAction
import com.lagradost.cloudstream3.actions.VideoClickActionHolder
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin
import java.lang.reflect.Field

@CloudstreamPlugin
class CSGuardPlugin : Plugin() {

    companion object {
        private const val TAG = "CSGuard"
    }

    private var activity: Activity? = null
    private var lifecycleHook: Application.ActivityLifecycleCallbacks? = null

    override fun load(context: Context) {
        activity = context as? Activity

        try {
            TantrumShield.install()
            Log.i(TAG, "✓ TantrumShield active (main thread + background crash shield)")
        } catch (t: Throwable) {
            Log.w(TAG, "TantrumShield install failed: ${t.message}")
        }

        try {
            AllowlistStore.init(context)
            Log.i(TAG, "✓ AllowlistStore initialized")
        } catch (t: Throwable) {
            Log.e(TAG, "AllowlistStore init failed", t)
        }

        try {
            val ok = InstrumentationHook.install { ProviderSanitizer.policy }
            if (ok) {
                Log.i(TAG, "✓ Instrumentation hook active")
            } else {
                Log.w(TAG, "⚠ Instrumentation hook failed — fallback defenses active")
            }
        } catch (t: Throwable) {
            Log.e(TAG, "Instrumentation hook install threw", t)
        }

        try {
            val app = context.applicationContext as? Application
            if (app != null) {
                lifecycleHook = ActivityContextHook { ProviderSanitizer.policy }
                app.registerActivityLifecycleCallbacks(lifecycleHook)
                Log.i(TAG, "✓ ActivityLifecycleCallbacks registered")
            }
        } catch (t: Throwable) {
            Log.w(TAG, "ActivityLifecycleCallbacks registration failed", t)
        }

        try {
            registerMainAPI(URLInterceptorProvider())
            Log.i(TAG, "✓ Registered URLInterceptorProvider")
        } catch (t: Throwable) {
            Log.e(TAG, "Failed to register URLInterceptorProvider", t)
        }

        try {
            sanitizeVideoClickActions()
        } catch (t: Throwable) {
            Log.w(TAG, "VideoClickAction sanitization failed", t)
        }

        try {
            val isStrict = AllowlistStore.isGlobalStrict()
            ProviderSanitizer.policy = GuardPolicy(
                blockKnownAdHosts = true,
                blockAdPaths = true,
                blockAllUnknown = isStrict,
                showToast = false,
                blockPopups = AllowlistStore.isSettingEnabled(AllowlistStore.KEY_BLOCK_POPUPS),
                blockClipboard = AllowlistStore.isSettingEnabled(AllowlistStore.KEY_BLOCK_CLIPBOARD),
                blockBackgroundTasks = AllowlistStore.isSettingEnabled(AllowlistStore.KEY_BLOCK_BACKGROUND),
                sandboxPreferences = AllowlistStore.isSettingEnabled(AllowlistStore.KEY_SANDBOX_PREFS),
                watchdogEnabled = AllowlistStore.isSettingEnabled(AllowlistStore.KEY_WATCHDOG)
            )
            ProviderSanitizer.start(context)
            Log.i(TAG, "✓ ProviderSanitizer started (blockAllUnknown=$isStrict)")
        } catch (t: Throwable) {
            Log.e(TAG, "Failed to start ProviderSanitizer", t)
        }

        try {
            NetworkGuard.install()
            Log.i(TAG, "✓ NetworkGuard installed (OkHttp interceptor active)")
        } catch (t: Throwable) {
            Log.w(TAG, "NetworkGuard install failed (non-critical): ${t.message}")
        }

        openSettings = {
            activity?.let { act ->
                try {
                    GuardSettingsDialog(act, ProviderSanitizer.policy) { newPolicy ->
                        ProviderSanitizer.policy = newPolicy
                        Log.i(TAG, "Policy updated")
                    }.show()
                } catch (t: Throwable) {
                    Log.e(TAG, "Failed to show settings dialog", t)
                }
            }
        }

        Log.i(TAG, "CSGuard loaded — all defenses active")
    }

    override fun beforeUnload() {
        try { ProviderSanitizer.stop() } catch (_: Throwable) {}
        try { InstrumentationHook.uninstall() } catch (_: Throwable) {}
        try {
            val app = activity?.application
            lifecycleHook?.let { app?.unregisterActivityLifecycleCallbacks(it) }
        } catch (_: Throwable) {}
        super.beforeUnload()
    }

    private fun sanitizeVideoClickActions() {
        val actions = VideoClickActionHolder.allVideoClickActions
        val maliciousActionClasses = setOf(
            "com.MaliciousPlugin.browser.BrowserAdAction",
            "com.ad.RedirectAction",
        )
        var removed = 0
        val toRemove = mutableListOf<VideoClickAction>()
        for (action in actions.toList()) {
            val srcPlugin = try { action.sourcePlugin ?: "" } catch (_: Throwable) { "" }
            val className = action::class.java.name
            if (srcPlugin.contains("MaliciousPlugin", ignoreCase = true) ||
                srcPlugin.contains("AdNetwork", ignoreCase = true) ||
                className in maliciousActionClasses
            ) {
                toRemove.add(action)
                Log.w(TAG, "Flagging malicious VideoClickAction: $className (src=$srcPlugin)")
            }
        }
        for (a in toRemove) {
            try { actions.remove(a); removed++ } catch (_: Throwable) {}
        }
        if (removed > 0) Log.i(TAG, "✓ Removed $removed malicious VideoClickAction(s)")
    }
}

class ActivityContextHook(
    private val policyProvider: () -> GuardPolicy
) : Application.ActivityLifecycleCallbacks {

    companion object {
        private const val TAG = "CSGuard"
        private val mBaseField: Field? by lazy {
            try {
                val f = ContextWrapper::class.java.getDeclaredField("mBase")
                f.isAccessible = true
                f
            } catch (t: Throwable) {
                Log.w(TAG, "Could not access ContextWrapper.mBase: ${t.message}")
                null
            }
        }
    }

    override fun onActivityCreated(activity: Activity, savedInstanceState: android.os.Bundle?) {
        wrapActivity(activity)
    }

    override fun onActivityStarted(activity: Activity) {}
    override fun onActivityResumed(activity: Activity) {}
    override fun onActivityPaused(activity: Activity) {}
    override fun onActivityStopped(activity: Activity) {}
    override fun onActivitySaveInstanceState(activity: Activity, outState: android.os.Bundle) {}
    override fun onActivityDestroyed(activity: Activity) {}

    private fun wrapActivity(activity: Activity) {
        val field = mBaseField ?: return
        try {
            val current = field.get(activity) as? Context ?: return
            if (current is GuardedContext) return
            val wrapped = GuardedContext(current, policyProvider)
            field.set(activity, wrapped)
            Log.i(TAG, "✓ Wrapped Activity mBase: ${activity.javaClass.simpleName}")
        } catch (_: Throwable) {}
    }
}
