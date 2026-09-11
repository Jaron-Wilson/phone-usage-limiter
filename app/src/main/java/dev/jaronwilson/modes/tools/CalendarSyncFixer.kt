package dev.jaronwilson.modes.tools

import android.accounts.Account
import android.accounts.AccountManager
import android.content.ContentResolver
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.provider.CalendarContract
import android.util.Log

/**
 * Repairs the case where Android reports a calendar account as enabled while
 * refusing to sync it.
 *
 * Two separate flags govern a sync adapter. `syncAutomatically` is the one the
 * Settings switch shows. `isSyncable` is a second flag the adapter itself owns,
 * and when it is 0 nothing runs no matter what the switch says. The result is a
 * calendar that looks connected, keeps working inside Google Calendar's own app
 * because that syncs through a private path, and leaves the phone's shared
 * calendar store empty, so every other calendar app and widget sees nothing.
 *
 * There is no way to set that flag from a shell. An app can, but only for an
 * account it can see, and an app cannot see a Google account unless the user
 * hands it over. Hence the account chooser: picking an account in it grants
 * this app visibility of that one account, and nothing else.
 */
object CalendarSyncFixer {

    private const val TAG = "CalendarSyncFixer"
    private const val AUTHORITY = CalendarContract.AUTHORITY

    data class SyncState(val isSyncable: Int, val syncAutomatically: Boolean) {
        /** The exact shape of the fault: switch on, adapter blocked. */
        val looksOnButIsBlocked: Boolean get() = syncAutomatically && isSyncable == 0
        val healthy: Boolean get() = syncAutomatically && isSyncable > 0
    }

    data class FixResult(
        val account: String,
        val before: SyncState,
        val after: SyncState,
        val error: String? = null
    ) {
        val changed: Boolean get() = before.isSyncable != after.isSyncable
    }

    /**
     * The system account picker. Choosing an account here is what grants this
     * app permission to touch that account's sync settings.
     */
    fun chooseAccountIntent(): Intent =
        AccountManager.newChooseAccountIntent(
            null,
            null,
            arrayOf("com.google", "com.google.android.gm.exchange"),
            "Pick the account whose calendar should reach this phone",
            null,
            null,
            null
        )

    fun accountFromResult(data: Intent?): Account? {
        val name = data?.getStringExtra(AccountManager.KEY_ACCOUNT_NAME) ?: return null
        val type = data.getStringExtra(AccountManager.KEY_ACCOUNT_TYPE) ?: return null
        return Account(name, type)
    }

    fun state(account: Account): SyncState = SyncState(
        isSyncable = runCatching { ContentResolver.getIsSyncable(account, AUTHORITY) }
            .getOrDefault(-2),
        syncAutomatically = runCatching { ContentResolver.getSyncAutomatically(account, AUTHORITY) }
            .getOrDefault(false)
    )

    /**
     * Turn the adapter back on for one account and ask it to run now.
     * Expedited and manual, because a periodic sync a day away is no use to
     * someone standing there watching.
     */
    fun fix(account: Account): FixResult {
        val before = state(account)
        var error: String? = null
        try {
            ContentResolver.setIsSyncable(account, AUTHORITY, 1)
            ContentResolver.setSyncAutomatically(account, AUTHORITY, true)
            ContentResolver.requestSync(
                account,
                AUTHORITY,
                Bundle().apply {
                    putBoolean(ContentResolver.SYNC_EXTRAS_MANUAL, true)
                    putBoolean(ContentResolver.SYNC_EXTRAS_EXPEDITED, true)
                }
            )
        } catch (t: Throwable) {
            Log.w(TAG, "could not repair sync for this account", t)
            error = t.javaClass.simpleName + ": " + (t.message ?: "no detail")
        }
        return FixResult(account.name, before, state(account), error)
    }

    /** A short sentence for the screen, since the flags mean nothing to anyone. */
    fun describe(result: FixResult): String = when {
        result.error != null ->
            "Could not change it: ${result.error}. Removing and re-adding the " +
                "account in Settings is the fallback."
        result.after.healthy && result.changed ->
            "Fixed. It was switched on but blocked (syncable ${result.before.isSyncable} " +
                "to ${result.after.isSyncable}) and a refresh is running. Events should " +
                "appear within a minute."
        result.after.healthy ->
            "Sync is on for this account (syncable=${result.after.isSyncable}) and a " +
                "refresh has been requested. If the calendar is still empty in a " +
                "minute, try the other accounts: only the one holding your events " +
                "matters, and each has its own flag."
        else ->
            "Asked Android to re-enable it, but the flag did not move. This account " +
                "may need removing and re-adding in Settings."
    }
}
