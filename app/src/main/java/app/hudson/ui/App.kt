package app.hudson.ui

import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.ContentTransform
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import androidx.navigation3.runtime.NavKey
import androidx.navigation3.runtime.entryProvider
import androidx.navigation3.runtime.rememberNavBackStack
import androidx.navigation3.ui.NavDisplay
import app.hudson.R
import app.hudson.core.Station
import app.hudson.data.Graph
import app.hudson.data.Locator
import app.hudson.data.Settings
import app.hudson.domain.Alerts
import app.hudson.domain.Favorite
import app.hudson.notify.LiveNotifier
import app.hudson.widget.Widgets
import app.hudson.work.LiveSession
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import androidx.savedstate.serialization.SavedStateConfiguration
import kotlinx.serialization.Serializable
import kotlinx.serialization.modules.SerializersModule
import kotlinx.serialization.modules.polymorphic
import kotlinx.serialization.modules.subclass

/**
 * How the places are written down when Android puts the app away and brings it back. Each is
 * named here outright, so nothing depends on looking classes up by name at run time, which the
 * release build's code shrinker would break.
 */
private val navKeys = SavedStateConfiguration {
    serializersModule = SerializersModule {
        polymorphic(NavKey::class) {
            subclass(TabsKey::class, TabsKey.serializer())
            subclass(StationKey::class, StationKey.serializer())
            subclass(SettingsKey::class, SettingsKey.serializer())
            subclass(AddKey::class, AddKey.serializer())
        }
    }
}

/** Where you can be in the app. The three tabs are one place; everything else is pushed on top of it. */
@Serializable data object TabsKey : NavKey
@Serializable data class StationKey(val code: String) : NavKey
@Serializable data object SettingsKey : NavKey
@Serializable data object AddKey : NavKey

enum class Tab(val label: String, val icon: Int) {
    TRAINS("FAVORITES", R.drawable.ic_stat_train),
    STATIONS("STATIONS", R.drawable.ic_tab_stations),
}

/** While the app is open: trains every half minute, alerts every few. Nothing here runs in the background. */
private const val TRAINS_EVERY_MS = 30_000L
private const val ALERTS_EVERY_MS = 3 * 60_000L

/**
 * The whole app. Favorites and Stations sit behind a bottom bar; a station's board,
 * Settings and adding a favorite slide over them, and the back gesture peels them away under
 * your thumb.
 */
@Composable
fun HudsonRoot(askNotificationsOnStart: Boolean = false) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val settings by Graph.settings.flow.collectAsStateWithLifecycle(initialValue = null)
    val feed by Graph.feed.flow.collectAsStateWithLifecycle()
    val alertsSnapshot by Graph.alerts.flow.collectAsStateWithLifecycle()
    val backStack = rememberNavBackStack(navKeys, TabsKey)
    var tabName by rememberSaveable { mutableStateOf(Tab.TRAINS.name) }
    val tab = Tab.valueOf(tabName)

    val lifecycle = LocalLifecycleOwner.current.lifecycle
    LaunchedEffect(Unit) {
        Graph.feed.cached()
        Graph.alerts.cached()
        lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            launch {
                while (true) {
                    Graph.feed.refresh(maxAgeMs = 10_000)
                    delay(TRAINS_EVERY_MS)
                }
            }
            while (true) {
                Graph.alerts.refresh(maxAgeMs = 60_000)
                delay(ALERTS_EVERY_MS)
            }
        }
    }
    val now by produceState(System.currentTimeMillis()) {
        while (true) {
            delay(1_000)
            value = System.currentTimeMillis()
        }
    }
    val current = remember(alertsSnapshot) { Alerts.current(alertsSnapshot) }

    fun notificationsAllowed() =
        ContextCompat.checkSelfPermission(context, MainActivity.NOTIFICATION_PERMISSION) == PackageManager.PERMISSION_GRANTED
    // What became of the last attempt to start tracking, so a tap is never met with silence.
    var liveResult by remember { mutableStateOf<LiveNotifier.Result?>(null) }
    fun startTracking() = scope.launch {
        val st = settings ?: return@launch
        liveResult = LiveSession.start(context, st.appFavorite)
    }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) startTracking()
        else {
            // Once refused, Android stops showing its dialog; the switch is then only in Settings.
            liveResult = LiveNotifier.Result.BLOCKED
            LiveNotifier.openNotificationSettings(context)
        }
    }
    LaunchedEffect(askNotificationsOnStart) {
        if (askNotificationsOnStart && !notificationsAllowed()) permission.launch(MainActivity.NOTIFICATION_PERMISSION)
    }

    fun save(transform: (Settings) -> Settings) = scope.launch {
        Graph.settings.update(transform)
        Widgets.repaintAll(context)
    }

    // Stations near you. Only looked for on the Stations tab, and only once location has been allowed.
    var near by remember { mutableStateOf<Locator.Result?>(null) }
    var locating by remember { mutableStateOf(false) }
    var refused by rememberSaveable { mutableStateOf(false) }
    fun locate() = scope.launch {
        if (locating) return@launch
        locating = true
        // Show what the phone already knows at once, then replace it if a fresher fix arrives.
        if (near !is Locator.Result.Found) Locator.lastKnown(context)?.let { near = Locator.Result.Found(it) }
        near = Locator.locate(context)
        locating = false
    }
    val locationPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { granted ->
        if (granted.values.any { it }) locate()
        else {
            refused = true
            near = Locator.Result.NotAllowed
        }
    }
    // The one button does whatever is needed next: ask, send to the right settings page, or look again.
    val onNear: () -> Unit = {
        when {
            !Locator.allowed(context) && refused -> openAppSettings(context)
            !Locator.allowed(context) -> locationPermission.launch(Locator.PERMISSIONS)
            !Locator.switchedOn(context) -> openLocationSettings(context)
            else -> locate()
        }
    }
    // Coming to the Stations tab, or back to the app from Settings, with location allowed: look (again).
    LaunchedEffect(tab) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            if (tab == Tab.STATIONS && Locator.allowed(context)) locate()
        }
    }

    val s = SwissTheme.colors
    Box(Modifier.fillMaxSize().background(s.paper)) {
        val st = settings ?: return@Box
        NavDisplay(
            backStack = backStack,
            onBack = { backStack.removeLastOrNull() },
            // Going deeper comes in from the right; coming back, the page underneath is already there.
            transitionSpec = { push() },
            popTransitionSpec = { pop() },
            predictivePopTransitionSpec = { pop() },
            entryProvider = entryProvider {
                entry<TabsKey> {
                    Column(Modifier.fillMaxSize()) {
                        Box(Modifier.weight(1f)) {
                            // Tabs are siblings, not a journey: one fades through to the next.
                            AnimatedContent(
                                targetState = tab,
                                transitionSpec = { (fadeIn(tween(220, delayMillis = 70)) + scaleIn(tween(220, delayMillis = 70), initialScale = 0.97f)) togetherWith fadeOut(tween(90)) },
                                label = "tab",
                            ) { shown ->
                                when (shown) {
                                    Tab.TRAINS -> Home(
                                        settings = st, feed = feed, now = now,
                                        onPick = { i -> save { it.copy(appFavorite = i) } },
                                        onTrack = {
                                            if (notificationsAllowed()) startTracking()
                                            else permission.launch(MainActivity.NOTIFICATION_PERMISSION)
                                        },
                                        onStop = { liveResult = null; scope.launch { LiveSession.stop(context) } },
                                        liveResult = liveResult,
                                        onSettings = { backStack.add(SettingsKey) },
                                        alerts = current,
                                    )
                                    Tab.STATIONS -> StationsScreen(
                                        feed = feed, now = now, alerts = current,
                                        near = near, locating = locating, onNear = onNear,
                                        onStation = { backStack.add(StationKey(it.code)) },
                                    )
                                }
                            }
                        }
                        BottomBar(tab, onTab = { tabName = it.name })
                    }
                }
                entry<StationKey> { key ->
                    val station = Station.of(key.code) ?: Station.EXCHANGE_PLACE
                    StationScreen(
                        station = station, feed = feed, now = now, alerts = Alerts.at(station, current), favorites = st.favorites,
                        onBack = { backStack.removeLastOrNull() },
                        onAdd = { direction ->
                            save { it.copy(favorites = it.favorites + Favorite.create(station, direction, emptyList(), "f" + System.currentTimeMillis())) }
                        },
                    )
                }
                entry<SettingsKey> {
                    SettingsScreen(
                        settings = st,
                        onBack = { backStack.removeLastOrNull() },
                        onAdd = { backStack.add(AddKey) },
                        onReorder = { list ->
                            save { cur ->
                                val selectedId = cur.favorites.getOrNull(Math.floorMod(cur.appFavorite, cur.favorites.size.coerceAtLeast(1)))?.id
                                cur.copy(favorites = list, appFavorite = list.indexOfFirst { it.id == selectedId }.coerceAtLeast(0))
                            }
                        },
                        onRemove = { id -> save { cur -> cur.copy(favorites = cur.favorites.filterNot { it.id == id }, appFavorite = 0) } },
                        onMinutes = { m -> save { it.copy(liveMinutes = m) } },
                        onMatchPhone = { on -> save { it.copy(matchPhone = on) } },
                    )
                }
                entry<AddKey> {
                    AddScreen(
                        onClose = { backStack.removeLastOrNull() },
                        onSave = { f ->
                            save { it.copy(favorites = it.favorites + f) }
                            backStack.removeLastOrNull()
                        },
                    )
                }
            },
        )
    }
}

private fun push(): ContentTransform =
    (slideInHorizontally(Motion.move()) { it / 5 } + fadeIn(Motion.enter)) togetherWith
        (slideOutHorizontally(Motion.move()) { -it / 8 } + fadeOut(Motion.exit))

private fun pop(): ContentTransform =
    (slideInHorizontally(Motion.move()) { -it / 8 } + fadeIn(Motion.enter)) togetherWith
        (slideOutHorizontally(Motion.move()) { it / 5 } + fadeOut(Motion.exit) + scaleOut(tween(220), targetScale = 0.96f))

/** Two places, one tap each. The marker slides to the one you are in. */
@Composable
private fun BottomBar(tab: Tab, onTab: (Tab) -> Unit) {
    val s = SwissTheme.colors
    Column(Modifier.fillMaxWidth().background(s.paper)) {
        Box(Modifier.fillMaxWidth().height(1.dp).background(s.rule))
        BoxWithConstraints(Modifier.fillMaxWidth().windowInsetsPadding(WindowInsets.navigationBars).height(68.dp).padding(horizontal = 12.dp)) {
            val each = maxWidth / Tab.entries.size
            val at by animateDpAsState(each * tab.ordinal, spring(dampingRatio = 0.78f, stiffness = 420f), label = "tab marker")
            Box(Modifier.offset(x = at).width(each).fillMaxHeight().padding(horizontal = 6.dp, vertical = 8.dp).clip(RoundedCornerShape(16.dp)).background(s.ink))
            Row(Modifier.fillMaxSize()) {
                Tab.entries.forEach { item ->
                    val selected = item == tab
                    val tint by animateColorAsState(if (selected) s.paper else s.muted, Motion.color, label = "tab tint")
                    Row(
                        Modifier.weight(1f).fillMaxHeight()
                            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) { onTab(item) }
                            .semantics {
                                role = Role.Tab
                                this.selected = selected
                                contentDescription = item.label.lowercase().replaceFirstChar { it.uppercase() }
                            },
                        horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(painterResource(item.icon), contentDescription = null, tint = tint, modifier = Modifier.size(22.dp))
                        Text(item.label, style = Type.label.copy(fontSize = 12.sp), color = tint, modifier = Modifier.padding(start = 10.dp))
                    }
                }
            }
        }
    }
}

internal fun openAppSettings(context: android.content.Context) {
    runCatching {
        context.startActivity(
            android.content.Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS, android.net.Uri.fromParts("package", context.packageName, null))
                .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK),
        )
    }
}

internal fun openLocationSettings(context: android.content.Context) {
    runCatching { context.startActivity(android.content.Intent(android.provider.Settings.ACTION_LOCATION_SOURCE_SETTINGS).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)) }
}
