package app.hudson.data

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationManager
import android.os.SystemClock
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume

/**
 * Roughly where the phone is, to offer the stations near you. Deliberately modest: one fix at a
 * time and only while the Stations screen is open, a recent fix reused when there is one, and no
 * Google Play services. Nothing is stored or sent anywhere.
 *
 * Built to fail politely. Each way it can go wrong has its own answer ([Result]), so the screen
 * can say what is the matter and how to fix it instead of spinning or guessing.
 */
object Locator {
    /** Both are asked for; Android lets the person grant only the approximate one, and that is enough. */
    val PERMISSIONS = arrayOf(Manifest.permission.ACCESS_COARSE_LOCATION, Manifest.permission.ACCESS_FINE_LOCATION)
    private const val FRESH_MS = 5 * 60_000L
    private const val USABLE_MS = 60 * 60_000L
    private const val WAIT_MS = 10_000L

    /** How far off an approximate fix can be when the phone does not say (Android blurs it to a few square kilometres). */
    private const val APPROXIMATE_M = 2_000f

    data class Fix(val lat: Double, val lon: Double, val accuracyMetres: Double, val ageMs: Long)

    sealed interface Result {
        data class Found(val fix: Fix) : Result
        /** The app has not been allowed to see location. */
        data object NotAllowed : Result
        /** Location is switched off for the whole phone. */
        data object SwitchedOff : Result
        /** Allowed and switched on, but no fix came in time (indoors, underground, just rebooted). */
        data object NoFix : Result
    }

    fun allowed(context: Context): Boolean = PERMISSIONS.any { context.checkSelfPermission(it) == PackageManager.PERMISSION_GRANTED }

    private fun precise(context: Context): Boolean = context.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED

    fun switchedOn(context: Context): Boolean =
        context.getSystemService(LocationManager::class.java)?.let { runCatching { it.isLocationEnabled }.getOrDefault(false) } ?: false

    private fun Location.toFix(context: Context): Fix? {
        // A phone that knows nothing sometimes answers 0,0; that is the Gulf of Guinea, not a fix.
        if (latitude == 0.0 && longitude == 0.0) return null
        val age = ((SystemClock.elapsedRealtimeNanos() - elapsedRealtimeNanos) / 1_000_000).coerceAtLeast(0)
        val reported = if (hasAccuracy()) accuracy else APPROXIMATE_M
        // With only the approximate permission the position is blurred however confident it claims to be.
        val accuracy = if (precise(context)) reported else maxOf(reported, APPROXIMATE_M)
        return Fix(latitude, longitude, accuracy.toDouble(), age)
    }

    /** The most recent position the phone already has, however it got it; costs no power. */
    @SuppressLint("MissingPermission")
    fun lastKnown(context: Context): Fix? {
        if (!allowed(context)) return null
        val manager = context.getSystemService(LocationManager::class.java) ?: return null
        return runCatching { manager.getProviders(true) }.getOrDefault(emptyList())
            .mapNotNull { p -> runCatching { manager.getLastKnownLocation(p) }.getOrNull()?.toFix(context) }
            .filter { it.ageMs <= USABLE_MS }
            .minByOrNull { it.ageMs }
    }

    /**
     * A position to work with. A fix under five minutes old is used as it is. Otherwise one fresh
     * fix is asked for; if none comes, an older one (up to an hour) is better than nothing, and
     * the caller can see its age.
     */
    @SuppressLint("MissingPermission")
    suspend fun locate(context: Context): Result {
        if (!allowed(context)) return Result.NotAllowed
        val manager = context.getSystemService(LocationManager::class.java) ?: return Result.NoFix
        if (!switchedOn(context)) return Result.SwitchedOff
        val known = lastKnown(context)
        if (known != null && known.ageMs <= FRESH_MS) return Result.Found(known)
        val provider = listOf(LocationManager.FUSED_PROVIDER, LocationManager.NETWORK_PROVIDER, LocationManager.GPS_PROVIDER)
            .firstOrNull { runCatching { manager.isProviderEnabled(it) }.getOrDefault(false) }
        val fresh = if (provider == null) null else withTimeoutOrNull(WAIT_MS) {
            suspendCancellableCoroutine<Fix?> { continuation ->
                val signal = android.os.CancellationSignal()
                continuation.invokeOnCancellation { signal.cancel() }
                runCatching {
                    manager.getCurrentLocation(provider, signal, context.mainExecutor) { location ->
                        if (continuation.isActive) continuation.resume(location?.toFix(context))
                    }
                }.onFailure { if (continuation.isActive) continuation.resume(null) }
            }
        }
        return (fresh ?: known)?.let { Result.Found(it) } ?: Result.NoFix
    }
}
