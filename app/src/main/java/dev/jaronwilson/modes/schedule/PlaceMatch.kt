package dev.jaronwilson.modes.schedule

import dev.jaronwilson.modes.core.model.Place
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Which saved place, if any, you are standing in.
 *
 * Pure arithmetic so it can be tested without a radio: the whole location
 * feature's correctness lives here, and the part that reads the GPS is a thin
 * shell around it.
 */
object PlaceMatch {

    private const val EARTH_RADIUS_M = 6_371_000.0

    /** Great-circle distance between two lat/lng points, in metres. */
    fun distanceMeters(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val dLat = Math.toRadians(lat2 - lat1)
        val dLon = Math.toRadians(lon2 - lon1)
        val a = sin(dLat / 2) * sin(dLat / 2) +
            cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) *
            sin(dLon / 2) * sin(dLon / 2)
        return EARTH_RADIUS_M * 2 * atan2(sqrt(a), sqrt(1 - a))
    }

    /**
     * The enabled place you are inside, if any. When you are inside more than
     * one, the higher priority wins, then the nearer centre, so a small place
     * nested in a big one is the one that speaks.
     */
    fun current(places: List<Place>, lat: Double, lon: Double): Place? =
        places
            .filter { it.enabled }
            .mapNotNull { place ->
                val d = distanceMeters(lat, lon, place.latitude, place.longitude)
                if (d <= place.radiusMeters) place to d else null
            }
            .minWithOrNull(
                compareByDescending<Pair<Place, Double>> { it.first.priority }
                    .thenBy { it.second }
            )
            ?.first
}
