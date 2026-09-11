package dev.jaronwilson.modes.apply

import android.app.AutomaticZenRule
import android.app.NotificationManager
import android.content.ComponentName
import android.content.Context
import android.net.Uri
import android.os.Build
import android.service.notification.Condition
import android.service.notification.ZenPolicy
import android.util.Log
import dev.jaronwilson.modes.core.model.Mode
import dev.jaronwilson.modes.core.repo.SettingsStore

/**
 * Owns one AutomaticZenRule per mode.
 *
 * Using zen rules rather than [NotificationManager.setInterruptionFilter] means
 * the modes show up in the system's own Modes/Do Not Disturb screen, survive
 * reboots, and let Android apply the visual effects (grayscale, dimmed
 * wallpaper) that make a phone genuinely less magnetic. Those effects need
 * Android 15 or newer; on older releases everything else still works.
 */
class ZenController(
    private val context: Context,
    private val settings: SettingsStore
) {
    private val nm: NotificationManager =
        context.getSystemService(NotificationManager::class.java)

    val hasPolicyAccess: Boolean
        get() = runCatching { nm.isNotificationPolicyAccessGranted }.getOrDefault(false)

    private fun conditionId(modeId: String): Uri =
        Uri.parse("condition://dev.jaronwilson.modes/mode/$modeId")

    private val configActivity =
        ComponentName(context, "dev.jaronwilson.modes.ui.MainActivity")

    /**
     * Make sure every mode that wants DND has a rule registered, and return the
     * modeId -> ruleId map. Rules are created once and reused.
     */
    suspend fun sync(modes: List<Mode>): Map<String, String> {
        if (!hasPolicyAccess) return emptyMap()
        val stored = settings.zenRuleIds().toMutableMap()
        val existing = runCatching { nm.automaticZenRules }.getOrDefault(emptyMap())

        for (mode in modes) {
            if (mode.interruptionFilter == 0) continue
            val known = stored[mode.id]
            if (known != null && existing.containsKey(known)) {
                runCatching { nm.updateAutomaticZenRule(known, buildRule(mode)) }
                    .onFailure { Log.w(TAG, "update rule for ${mode.id} failed", it) }
                continue
            }
            val id = runCatching { nm.addAutomaticZenRule(buildRule(mode)) }
                .onFailure { Log.w(TAG, "add rule for ${mode.id} failed", it) }
                .getOrNull()
            if (id != null) stored[mode.id] = id
        }
        settings.setZenRuleIds(stored)
        return stored
    }

    /** Turn on the rule for [activeModeId] and turn off every other one. */
    suspend fun activate(activeModeId: String, modes: List<Mode>) {
        if (!hasPolicyAccess) return
        val ids = sync(modes)
        val active = modes.firstOrNull { it.id == activeModeId }

        for ((modeId, ruleId) in ids) {
            val shouldBeOn = modeId == activeModeId && (active?.interruptionFilter ?: 0) != 0
            val state = if (shouldBeOn) Condition.STATE_TRUE else Condition.STATE_FALSE
            val summary = if (shouldBeOn) "${active?.name} is running" else "Not active"
            runCatching {
                nm.setAutomaticZenRuleState(
                    ruleId,
                    Condition(conditionId(modeId), summary, state)
                )
            }.onFailure { Log.w(TAG, "set state for $modeId failed", it) }
        }
    }

    /** Drop every rule we created. Used when the user turns the feature off. */
    suspend fun clearAll() {
        val stored = settings.zenRuleIds()
        stored.values.forEach { runCatching { nm.removeAutomaticZenRule(it) } }
        settings.setZenRuleIds(emptyMap())
    }

    private fun buildRule(mode: Mode): AutomaticZenRule {
        val policy = ZenPolicy.Builder()
            .allowCalls(mode.allowCallsFrom)
            .allowMessages(mode.allowMessagesFrom)
            .allowRepeatCallers(mode.allowRepeatCallers)
            .allowConversations(ZenPolicy.CONVERSATION_SENDERS_NONE)
            .allowAlarms(true)
            .allowMedia(true)
            .allowSystem(false)
            .allowReminders(false)
            .allowEvents(false)
            .showBadges(false)
            .showInAmbientDisplay(!mode.suppressAmbientDisplay)
            .showInNotificationList(true)
            .build()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.VANILLA_ICE_CREAM) {
            val effects = android.service.notification.ZenDeviceEffects.Builder()
                .setShouldDisplayGrayscale(mode.grayscale)
                .setShouldDimWallpaper(mode.dimWallpaper)
                .setShouldSuppressAmbientDisplay(mode.suppressAmbientDisplay)
                .build()
            return AutomaticZenRule.Builder(mode.name, conditionId(mode.id))
                .setConfigurationActivity(configActivity)
                .setZenPolicy(policy)
                .setDeviceEffects(effects)
                .setInterruptionFilter(mode.interruptionFilter)
                .setTriggerDescription("Managed by Modes")
                .setEnabled(true)
                .build()
        }

        @Suppress("DEPRECATION")
        return AutomaticZenRule(
            mode.name,
            null,
            configActivity,
            conditionId(mode.id),
            policy,
            mode.interruptionFilter,
            true
        )
    }

    private companion object {
        const val TAG = "ZenController"
    }
}
