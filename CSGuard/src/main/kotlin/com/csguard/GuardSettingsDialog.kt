package com.csguard

import android.app.AlertDialog
import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Switch
import android.widget.TextView
import com.lagradost.cloudstream3.APIHolder
import com.lagradost.cloudstream3.MainAPI

class GuardSettingsDialog(
    private val context: Context,
    private val current: GuardPolicy,
    private val onApply: (GuardPolicy) -> Unit
) {
    fun show() {
        val container = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(48, 48, 48, 48)
            setBackgroundColor(Color.parseColor("#121212")) // Sleek Dark Mode
        }

        container.addView(TextView(context).apply {
            text = "🛡 CSGuard Protection"
            textSize = 24f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.WHITE)
            setPadding(0, 0, 0, 16)
        })

        container.addView(TextView(context).apply {
            text = "Select which providers are forbidden from launching external browser intents. CSGuard will silently void their ads."
            textSize = 14f
            setTextColor(Color.parseColor("#B0B0B0"))
            setPadding(0, 0, 0, 32)
        })

        var isGlobalStrict = current.blockAllUnknown

        val masterRow = createRow("Global Strict Mode (Block All Unknown)", isGlobalStrict) { checked ->
            isGlobalStrict = checked
        }
        container.addView(masterRow)

        container.addView(TextView(context).apply {
            text = "Per-Provider Blocks"
            textSize = 18f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.parseColor("#E0E0E0"))
            setPadding(0, 32, 0, 16)
        })

        val blockedSet = AllowlistStore.blockedProviders().toMutableSet()

        val providers = try {
            val getApisMethod = APIHolder::class.java.methods.find {
                it.name == "getAllProviders" || it.name == "getApis" || it.name == "apis"
            }
            val apis = if (getApisMethod != null) {
                getApisMethod.isAccessible = true
                getApisMethod.invoke(APIHolder) as? List<MainAPI> ?: emptyList()
            } else {
                APIHolder.allProviders
            }
            apis.sortedBy { it.name }
        } catch (_: Throwable) { emptyList() }

        if (providers.isEmpty()) {
            container.addView(TextView(context).apply {
                text = "No providers loaded yet."
                setTextColor(Color.RED)
            })
        } else {
            providers.forEach { provider ->
                val row = createRow(provider.name, blockedSet.contains(provider.name)) { checked ->
                    if (checked) blockedSet.add(provider.name) else blockedSet.remove(provider.name)
                }
                container.addView(row)
            }
        }

        container.addView(TextView(context).apply {
            text = "Advanced Sandbox Controls"
            textSize = 18f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.parseColor("#E0E0E0"))
            setPadding(0, 32, 0, 16)
        })

        var blockPopups = current.blockPopups
        var blockClipboard = current.blockClipboard
        var blockBg = current.blockBackgroundTasks
        var sandboxPrefs = current.sandboxPreferences
        var watchdog = current.watchdogEnabled

        container.addView(createRow("Block UI Popups & Toasts", blockPopups) { blockPopups = it })
        container.addView(createRow("Block Clipboard Access", blockClipboard) { blockClipboard = it })
        container.addView(createRow("Block Background Services", blockBg) { blockBg = it })
        container.addView(createRow("Sandbox Plugin Data (Anti-Theft)", sandboxPrefs) { sandboxPrefs = it })
        container.addView(createRow("Anti-Tamper Watchdog (Reflection)", watchdog) { watchdog = it })

        val scroll = ScrollView(context).apply {
            addView(container)
        }

        AlertDialog.Builder(context, android.R.style.Theme_DeviceDefault_Dialog_Alert)
            .setView(scroll)
            .setPositiveButton("Save Settings") { _, _ ->

                val currentBlocks = AllowlistStore.blockedProviders()
                currentBlocks.forEach { AllowlistStore.removeBlockedProvider(it) }
                blockedSet.forEach { AllowlistStore.addBlockedProvider(it) }
                
                AllowlistStore.setGlobalStrict(isGlobalStrict)
                AllowlistStore.setSettingEnabled(AllowlistStore.KEY_BLOCK_POPUPS, blockPopups)
                AllowlistStore.setSettingEnabled(AllowlistStore.KEY_BLOCK_CLIPBOARD, blockClipboard)
                AllowlistStore.setSettingEnabled(AllowlistStore.KEY_BLOCK_BACKGROUND, blockBg)
                AllowlistStore.setSettingEnabled(AllowlistStore.KEY_SANDBOX_PREFS, sandboxPrefs)
                AllowlistStore.setSettingEnabled(AllowlistStore.KEY_WATCHDOG, watchdog)

                val newPolicy = GuardPolicy(
                    blockKnownAdHosts = true,
                    blockAdPaths = true,
                    blockAllUnknown = isGlobalStrict,
                    showToast = current.showToast,
                    blockPopups = blockPopups,
                    blockClipboard = blockClipboard,
                    blockBackgroundTasks = blockBg,
                    sandboxPreferences = sandboxPrefs,
                    watchdogEnabled = watchdog
                )
                onApply(newPolicy)
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun createRow(title: String, isChecked: Boolean, onToggle: (Boolean) -> Unit): LinearLayout {
        val row = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(32, 32, 32, 32)
            
            val shape = GradientDrawable()
            shape.shape = GradientDrawable.RECTANGLE
            shape.cornerRadius = 24f
            shape.setColor(Color.parseColor("#1E1E1E"))
            background = shape

            val params = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
            params.setMargins(0, 0, 0, 16)
            layoutParams = params
        }

        val textLayout = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            gravity = Gravity.CENTER_VERTICAL
        }

        val titleView = TextView(context).apply {
            text = title
            textSize = 16f
            setTextColor(Color.WHITE)
            typeface = Typeface.DEFAULT_BOLD
        }

        textLayout.addView(titleView)

        val toggle = Switch(context).apply {
            this.isChecked = isChecked
            setOnCheckedChangeListener { _, checked -> onToggle(checked) }
        }

        row.addView(textLayout)
        row.addView(toggle)
        return row
    }
}
