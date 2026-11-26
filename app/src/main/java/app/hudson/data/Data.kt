package app.hudson.data

import android.content.Context
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import app.hudson.data.feed.AlertsSnapshot
import app.hudson.data.feed.FeedSnapshot
import app.hudson.data.feed.PathAlerts
import app.hudson.data.feed.RidePathFeed
import app.hudson.domain.Favorite
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit

/** Where a raw feed comes from. Swapped for a fixed sample in tests. */
fun interface FeedSource {
    suspend fun fetch(): String
}

class HttpFeedSource(private val client: OkHttpClient, private val url: String = RidePathFeed.URL) : FeedSource {
    override suspend fun fetch(): String = withContext(Dispatchers.IO) {
        val request = Request.Builder()
            .url(url)
            .header("Accept", "application/json")
            .header("Cache-Control", "no-cache")
            .build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw IOException("PATH feed answered ${response.code}")
            response.body.string()
        }
    }

    companion object {
        fun client(): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(8, TimeUnit.SECONDS)
            .readTimeout(10, TimeUnit.SECONDS)
            .callTimeout(15, TimeUnit.SECONDS)
            .build()
    }
}

/**
 * The last good copy of the feed, kept on disk so a widget redraw, a reboot or a dead zone
 * underground still has trains to show. One fetch serves every station.
 */
class FeedRepository(context: Context, @Volatile var source: FeedSource) {
    private val file = File(context.filesDir, "ridepath.json")
    private val state = MutableStateFlow<FeedSnapshot?>(null)

    /** The copy in memory, for screens and widgets that should redraw the moment a fetch lands. */
    val flow: StateFlow<FeedSnapshot?> = state

    suspend fun cached(): FeedSnapshot? = state.value ?: withContext(Dispatchers.IO) {
        if (!file.exists()) null
        else runCatching { RidePathFeed.parse(file.readText(), file.lastModified()) }.getOrNull()?.also { state.value = it }
    }

    /** Fetches unless the copy we have is younger than [maxAgeMs]. */
    suspend fun refresh(maxAgeMs: Long = 0): Result<FeedSnapshot> {
        val now = System.currentTimeMillis()
        cached()?.let { if (now - it.fetchedAtMs < maxAgeMs) return Result.success(it) }
        return runCatching {
            val body = source.fetch()
            val snapshot = RidePathFeed.parse(body, now) // throws on a broken body, so a bad answer never replaces a good copy
            withContext(Dispatchers.IO) {
                val tmp = File(file.parentFile, "ridepath.tmp")
                tmp.writeText(body)
                tmp.setLastModified(now)
                if (!tmp.renameTo(file)) { file.writeText(body); file.setLastModified(now) }
            }
            state.value = snapshot
            snapshot
        }
    }
}

/**
 * PATH's alerts, kept the same way as the trains: the last good copy on disk, shown at once and
 * refreshed while the app is open. Nothing fetches alerts in the background.
 */
class AlertsRepository(context: Context, @Volatile var source: FeedSource) {
    private val file = File(context.filesDir, "alerts.json")
    private val state = MutableStateFlow<AlertsSnapshot?>(null)
    val flow: StateFlow<AlertsSnapshot?> = state

    suspend fun cached(): AlertsSnapshot? = state.value ?: withContext(Dispatchers.IO) {
        if (!file.exists()) null
        else runCatching { PathAlerts.parse(file.readText(), file.lastModified()) }.getOrNull()?.also { state.value = it }
    }

    suspend fun refresh(maxAgeMs: Long = 0): Result<AlertsSnapshot> {
        val now = System.currentTimeMillis()
        cached()?.let { if (now - it.fetchedAtMs < maxAgeMs) return Result.success(it) }
        return runCatching {
            val body = source.fetch()
            val snapshot = PathAlerts.parse(body, now)
            withContext(Dispatchers.IO) {
                file.writeText(body)
                file.setLastModified(now)
            }
            state.value = snapshot
            snapshot
        }
    }
}

data class Settings(
    val favorites: List<Favorite> = Favorite.defaults,
    /** Which favorite the app shows. Each widget keeps its own. */
    val appFavorite: Int = 0,
    val liveMinutes: Int = 30,
    val liveUntilMs: Long = 0,
    val liveFavorite: Int = 0,
    /** Draw the app and widget in the phone's Material You colours rather than the line colours. */
    val matchPhone: Boolean = false,
)

private val Context.store by preferencesDataStore(name = "settings")

class SettingsStore(private val context: Context) {
    private object Keys {
        val favorites = stringPreferencesKey("favorites")
        val appFavorite = intPreferencesKey("app_favorite")
        val liveMinutes = intPreferencesKey("live_minutes")
        val liveUntil = longPreferencesKey("live_until")
        val liveFavorite = intPreferencesKey("live_favorite")
        val matchPhone = booleanPreferencesKey("match_phone")
    }

    private fun Preferences.toSettings() = Settings(
        favorites = Favorite.decode(this[Keys.favorites]),
        appFavorite = this[Keys.appFavorite] ?: 0,
        liveMinutes = this[Keys.liveMinutes] ?: 30,
        liveUntilMs = this[Keys.liveUntil] ?: 0,
        liveFavorite = this[Keys.liveFavorite] ?: 0,
        matchPhone = this[Keys.matchPhone] ?: false,
    )

    val flow: Flow<Settings> = context.store.data.map { it.toSettings() }

    suspend fun current(): Settings = flow.first()

    suspend fun update(transform: (Settings) -> Settings) {
        context.store.edit { prefs ->
            val next = transform(prefs.toSettings())
            prefs[Keys.favorites] = Favorite.encode(next.favorites)
            prefs[Keys.appFavorite] = next.appFavorite
            prefs[Keys.liveMinutes] = next.liveMinutes
            prefs[Keys.liveUntil] = next.liveUntilMs
            prefs[Keys.liveFavorite] = next.liveFavorite
            prefs[Keys.matchPhone] = next.matchPhone
        }
    }
}

/** The app's few long-lived objects. */
object Graph {
    lateinit var settings: SettingsStore
        private set
    lateinit var feed: FeedRepository
        private set
    lateinit var alerts: AlertsRepository
        private set

    fun init(context: Context, source: FeedSource? = null, alertSource: FeedSource? = null) {
        if (::settings.isInitialized) return
        val app = context.applicationContext
        val client by lazy { HttpFeedSource.client() }
        settings = SettingsStore(app)
        feed = FeedRepository(app, source ?: HttpFeedSource(client))
        alerts = AlertsRepository(app, alertSource ?: HttpFeedSource(client, PathAlerts.URL))
    }
}
