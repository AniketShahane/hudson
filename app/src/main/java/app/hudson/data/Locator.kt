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
 * Roughly where the phone is, to offer the nearest station. Deliberately modest: the approximate
 * permission only, one fix at a time and only while the Stations screen is open, a recent fix
 * reused when there is one, and no Google Play services. Nothing is stored or sent anywhere.
 */
object Locator {
    const val PERMISSION = Manifest.permission.ACCESS_COARSE_LOCATION
    private const val FRESH_MS = 10 * 60_000L
    private const val WAIT_MS = 8_000L

    fun allowed(context: Context): Boolean = context.checkSelfPermission(PERMISSION) == PackageManager.PERMISSION_GRANTED

    /** Latitude and longitude, or null when not allowed, switched off, or no fix arrives in time. */
    @SuppressLint("MissingPermission")
    suspend fun approximate(context: Context): Pair<Double, Double>? {
        if (!allowed(context)) return null
        val manager = context.getSystemService(LocationManager::class.java) ?: return null
        val provider = listOf(LocationManager.FUSED_PROVIDER, LocationManager.NETWORK_PROVIDER, LocationManager.PASSIVE_PROVIDER)
            .firstOrNull { runCatching { manager.isProviderEnabled(it) }.getOrDefault(false) } ?: return null
        fun Location.pair() = latitude to longitude
        runCatching { manager.getLastKnownLocation(provider) }.getOrNull()
            ?.takeIf { SystemClock.elapsedRealtimeNanos() - it.elapsedRealtimeNanos < FRESH_MS * 1_000_000 }
            ?.let { return it.pair() }
        return withTimeoutOrNull(WAIT_MS) {
            suspendCancellableCoroutine { continuation ->
                val signal = android.os.CancellationSignal()
                continuation.invokeOnCancellation { signal.cancel() }
                runCatching {
                    manager.getCurrentLocation(provider, signal, context.mainExecutor) { location ->
                        if (continuation.isActive) continuation.resume(location?.pair())
                    }
                }.onFailure { if (continuation.isActive) continuation.resume(null) }
            }
        }
    }
}
