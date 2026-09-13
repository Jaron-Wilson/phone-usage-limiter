package dev.jaronwilson.modes.schedule

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationManager
import android.os.Build
import android.os.CancellationSignal
import android.util.Log
import androidx.core.content.ContextCompat
import dev.jaronwilson.modes.core.repo.ModeRepository
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume

/**
 * Turns where the phone is into which place, if any, it is standing in, and
 * writes that to settings for the resolver to read.
 *
 * This is the one part of the location feature that touches a radio. It stays
 * a thin shell over [PlaceMatch]: read one fix, match it, store the answer.
 * Nothing here decides precedence or draws anything.
 *
 * Location without the GPS burning is good to a block, which is why places have
 * a generous radius. The answer is stamped with a deadline so a mode set by
 * being somewhere fades on its own if the phone stops getting fixes, rather
 * than pinning you to a place you have left.
 */
object LocationGate {

    /** How long a place match stays trusted without a fresh fix. */
    private const val FRESH_FOR_MS = 30 * 60 * 1000L

    fun hasPermission(context: Context): Boolean =
        ContextCompat.checkSelfPermission(
            context, Manifest.permission.ACCESS_FINE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED ||
            ContextCompat.checkSelfPermission(
                context, Manifest.permission.ACCESS_COARSE_LOCATION
            ) == PackageManager.PERMISSION_GRANTED

    /**
     * Read one location fix, match it to a saved place, and store the result.
     * A no-op when there are no places or no permission, so it is always safe
     * to call.
     */
    suspend fun refresh(context: Context, repo: ModeRepository) {
        val places = runCatching { repo.placeDao.getAll() }.getOrDefault(emptyList())
        if (places.none { it.enabled }) {
            repo.settings.setPlaceOverride(null, "", 0L)
            return
        }
        if (!hasPermission(context)) return

        val fix = currentFix(context) ?: return
        val place = PlaceMatch.current(places, fix.latitude, fix.longitude)
        if (place == null) {
            repo.settings.setPlaceOverride(null, "", 0L)
        } else {
            repo.settings.setPlaceOverride(
                place.modeId, place.name, System.currentTimeMillis() + FRESH_FOR_MS
            )
        }
    }

    /** One location fix for the UI, e.g. to save where you are standing now. */
    suspend fun oneFix(context: Context): Location? =
        if (hasPermission(context)) currentFix(context) else null

    private suspend fun currentFix(context: Context): Location? {
        val lm = context.getSystemService(Context.LOCATION_SERVICE) as? LocationManager
            ?: return null
        val provider = when {
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
                lm.isProviderEnabled(LocationManager.FUSED_PROVIDER) -> LocationManager.FUSED_PROVIDER
            lm.isProviderEnabled(LocationManager.GPS_PROVIDER) -> LocationManager.GPS_PROVIDER
            lm.isProviderEnabled(LocationManager.NETWORK_PROVIDER) -> LocationManager.NETWORK_PROVIDER
            else -> return lm.lastKnownLocationSafe()
        }
        return withTimeoutOrNull(15_000L) {
            suspendCancellableCoroutine { cont ->
                val signal = CancellationSignal()
                cont.invokeOnCancellation { signal.cancel() }
                try {
                    val exec = ContextCompat.getMainExecutor(context)
                    lm.getCurrentLocation(provider, signal, exec) { loc ->
                        if (cont.isActive) cont.resume(loc)
                    }
                } catch (e: SecurityException) {
                    Log.w("LocationGate", "no permission for a fix", e)
                    if (cont.isActive) cont.resume(null)
                }
            }
        } ?: lm.lastKnownLocationSafe()
    }

    private fun LocationManager.lastKnownLocationSafe(): Location? = try {
        listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER)
            .mapNotNull { runCatching { getLastKnownLocation(it) }.getOrNull() }
            .maxByOrNull { it.time }
    } catch (e: SecurityException) {
        null
    }
}
