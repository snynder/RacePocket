package com.racepocket.app.ui

import androidx.compose.material.icons.filled.Sensors
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material.icons.filled.DonutLarge
import androidx.compose.material.icons.filled.Calculate
import androidx.compose.material.icons.filled.SwapHoriz
import androidx.compose.material.icons.filled.Star
import kotlinx.coroutines.launch
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material.icons.filled.EmojiEvents
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Map
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.sp
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.racepocket.app.R
import com.racepocket.app.data.AppSettings
import com.racepocket.app.ui.screens.CircuitScreen
import com.racepocket.app.ui.screens.CircuitsScreen
import com.racepocket.app.ui.screens.CompareScreen
import com.racepocket.app.ui.screens.HomeScreen
import com.racepocket.app.ui.screens.LiveScreen
import com.racepocket.app.ui.screens.MoreScreen
import com.racepocket.app.ui.screens.NotificationsScreen
import com.racepocket.app.ui.screens.RaceScreen
import com.racepocket.app.ui.screens.RadioScreen
import com.racepocket.app.ui.screens.RecordsScreen
import com.racepocket.app.ui.screens.ReplayScreen
import com.racepocket.app.ui.screens.SeasonScreen
import com.racepocket.app.ui.screens.SettingsScreen
import com.racepocket.app.ui.screens.SimulationScreen
import com.racepocket.app.ui.screens.StandingsScreen
import com.racepocket.app.ui.screens.StrategyScreen
import com.racepocket.app.ui.screens.TelemetryScreen

object Routes {
    const val HOME = "home"
    const val STANDINGS = "standings"
    const val SEASON = "season"
    const val CIRCUITS = "circuits"
    const val MORE = "more"
    const val LIVE = "live"
    const val REPLAY = "replay"
    const val SETTINGS = "settings"
    const val RADIO = "radio"
    const val TELEMETRY = "telemetry"
    const val STRATEGY = "strategy"
    const val SIMULATION = "simulation"
    const val COMPARE = "compare"
    const val RECORDS = "records"
    const val NOTIFICATIONS = "notifications"
    fun circuit(id: String, year: Int? = null, round: Int? = null) = "circuit/$id?year=${year ?: -1}&round=${round ?: -1}"
    /** Replay opened on the session that started at [at] (matched by start time); without it, the latest one. */
    fun replay(at: java.time.Instant) = "$REPLAY?at=${at.epochSecond}"
    fun race(year: Int, round: Int, tab: Int? = null) = "race/$year/$round" + (tab?.let { "?tab=$it" } ?: "")
}

/**
 * A section of the app that can sit in the bottom bar (between Home and More, which are fixed) or, when it
 * is not there, in the More menu. [tabLabel] is the short name for the bar; [owns] tells which screens belong to it.
 */
class NavDest(
    val route: String, val label: Int, val tabLabel: Int, val sub: Int, val icon: ImageVector,
    val owns: (String) -> Boolean = { it == route }
)

val NAV_DESTS = listOf(
    NavDest(Routes.STANDINGS, R.string.nav_standings, R.string.nav_standings, R.string.standings_sub, Icons.Filled.EmojiEvents) { it.startsWith(Routes.STANDINGS) },
    NavDest(Routes.SEASON, R.string.nav_season, R.string.nav_season, R.string.season_sub, Icons.Filled.CalendarMonth) { it.startsWith(Routes.SEASON) || it.startsWith("race/") },
    NavDest(Routes.CIRCUITS, R.string.nav_circuits, R.string.nav_circuits, R.string.circuits_sub, Icons.Filled.Map) { it.startsWith(Routes.CIRCUITS) || it.startsWith("circuit/") },
    NavDest(Routes.LIVE, R.string.live_title, R.string.live_title, R.string.live_sub, Icons.Filled.Sensors),
    NavDest(Routes.REPLAY, R.string.replay_title, R.string.replay_title, R.string.replay_sub, Icons.Filled.History) { it.startsWith(Routes.REPLAY) },
    NavDest(Routes.RADIO, R.string.nav_radio, R.string.tab_radio, R.string.radio_sub, Icons.Filled.Mic),
    NavDest(Routes.TELEMETRY, R.string.nav_telemetry, R.string.nav_telemetry, R.string.telemetry_sub, Icons.Filled.Speed),
    NavDest(Routes.STRATEGY, R.string.nav_strategy, R.string.nav_strategy, R.string.strategy_sub, Icons.Filled.DonutLarge),
    NavDest(Routes.SIMULATION, R.string.nav_simulation, R.string.tab_sim, R.string.simulation_sub, Icons.Filled.Calculate),
    NavDest(Routes.COMPARE, R.string.nav_compare, R.string.nav_compare, R.string.compare_sub, Icons.Filled.SwapHoriz),
    NavDest(Routes.RECORDS, R.string.nav_records, R.string.nav_records, R.string.records_sub, Icons.Filled.Star)
)

/** Most shortcuts between Home and More: five items in all is the limit for a readable bar. */
const val MAX_BAR = 3

/** The person's bottom bar shortcuts, in order (unknown or repeated entries dropped). */
fun barDests(s: AppSettings): List<NavDest> =
    s.bottomBar.mapNotNull { k -> NAV_DESTS.firstOrNull { it.route == k } }.distinctBy { it.route }.take(MAX_BAR)

/** Which bottom tab a screen belongs to: its own shortcut if it is in the bar, otherwise More (or Home for Live/Replay). */
private fun tabOf(route: String?, bar: List<NavDest>): String {
    if (route == null) return Routes.HOME
    bar.firstOrNull { it.owns(route) }?.let { return it.route }
    if (route == Routes.MORE || route == Routes.SETTINGS || route == Routes.NOTIFICATIONS) return Routes.MORE
    val d = NAV_DESTS.firstOrNull { it.owns(route) } ?: return Routes.HOME
    // Live and Replay are mostly reached from Home (banner, next race card)
    return if (d.route == Routes.LIVE || d.route == Routes.REPLAY) Routes.HOME else Routes.MORE
}

private data class Tab(val route: String, val label: Int, val icon: ImageVector)

/** Bottom bar: always opens the tab's main screen. Inside a tab (e.g. Replay under More), tapping it goes back to its menu. */
/** Screen asked by a tapped notification; AppRoot opens it on top of Home. */
object DeepLink {
    val route = kotlinx.coroutines.flow.MutableStateFlow<String?>(null)
}

fun NavHostController.goTab(route: String) {
    if (popBackStack(route, inclusive = false)) return
    navigate(route) {
        popUpTo(graph.findStartDestination().id)
        launchSingleTop = true
    }
}

@Composable
fun AppRoot(settings: AppSettings) {
    val nav = rememberNavController()
    val entry by nav.currentBackStackEntryAsState()
    val bar = barDests(settings)
    val current = tabOf(entry?.destination?.route, bar)
    val tabs = listOf(Tab(Routes.HOME, R.string.nav_home, Icons.Filled.Home)) +
        bar.map { Tab(it.route, it.tabLabel, it.icon) } +
        Tab(Routes.MORE, R.string.nav_more, Icons.Filled.Menu)
    val p = pal()
    Scaffold(
        containerColor = p.bg,
        bottomBar = {
            NavigationBar(containerColor = p.card) {
                tabs.forEach { t ->
                    NavigationBarItem(
                        selected = current == t.route,
                        onClick = { nav.goTab(t.route) },
                        icon = { Icon(t.icon, contentDescription = null) },
                        label = { Text(stringResource(t.label), fontSize = 10.sp, maxLines = 1) },
                        colors = NavigationBarItemDefaults.colors(
                            selectedIconColor = p.accentText, selectedTextColor = p.accentText,
                            unselectedIconColor = p.muted, unselectedTextColor = p.muted, indicatorColor = p.card2
                        )
                    )
                }
            }
        }
    ) { pad ->
        Box(Modifier.padding(pad).fillMaxSize()) {
            NavHost(nav, startDestination = Routes.HOME) {
                composable(Routes.HOME) { HomeScreen(nav, settings) }
                composable(Routes.STANDINGS) { StandingsScreen(nav) }
                composable(Routes.SEASON) { SeasonScreen(nav) }
                composable(Routes.CIRCUITS) { CircuitsScreen(nav) }
                composable(Routes.MORE) { MoreScreen(nav, settings) }
                composable(Routes.NOTIFICATIONS) { NotificationsScreen(nav, settings) }
                composable(Routes.LIVE) { LiveScreen(nav, settings) }
                composable(
                    "${Routes.REPLAY}?at={at}",
                    arguments = listOf(navArgument("at") { type = NavType.LongType; defaultValue = -1L })
                ) { e ->
                    ReplayScreen(nav, e.arguments?.getLong("at")?.takeIf { it > 0 }?.let { java.time.Instant.ofEpochSecond(it) })
                }
                composable(Routes.SETTINGS) { SettingsScreen(nav, settings) }
                composable(Routes.RADIO) { RadioScreen(nav, settings) }
                composable(Routes.TELEMETRY) { TelemetryScreen(nav) }
                composable(Routes.STRATEGY) { StrategyScreen(nav) }
                composable(Routes.SIMULATION) { SimulationScreen(nav) }
                composable(Routes.COMPARE) { CompareScreen(nav) }
                composable(Routes.RECORDS) { RecordsScreen(nav) }
                composable(
                    "circuit/{id}?year={year}&round={round}",
                    arguments = listOf(
                        navArgument("id") { type = NavType.StringType },
                        navArgument("year") { type = NavType.IntType; defaultValue = -1 },
                        navArgument("round") { type = NavType.IntType; defaultValue = -1 }
                    )
                ) { e ->
                    CircuitScreen(
                        nav, e.arguments?.getString("id") ?: "",
                        e.arguments?.getInt("year")?.takeIf { it > 0 }, e.arguments?.getInt("round")?.takeIf { it > 0 }
                    )
                }
                composable(
                    "race/{year}/{round}?tab={tab}",
                    arguments = listOf(
                        navArgument("year") { type = NavType.IntType },
                        navArgument("round") { type = NavType.IntType },
                        navArgument("tab") { type = NavType.IntType; defaultValue = 0 }
                    )
                ) { e ->
                    RaceScreen(nav, e.arguments?.getInt("year") ?: 0, e.arguments?.getInt("round") ?: 0, settings, e.arguments?.getInt("tab") ?: 0)
                }
            }
            FirstRunNotifications()
            // a tapped notification: open its screen over Home, so back returns to Home
            val pending by DeepLink.route.collectAsState()
            LaunchedEffect(pending) {
                val r = pending ?: return@LaunchedEffect
                DeepLink.route.value = null
                runCatching {
                    nav.navigate(r) {
                        popUpTo(nav.graph.findStartDestination().id)
                        launchSingleTop = true
                    }
                }
            }
        }
    }
}

/**
 * First launch after a fresh install: ask for the notification permission and, if granted, turn on every
 * session alert and result notice; the person turns off what they do not want. An update never asks again
 * and never touches what was already chosen.
 */
@Composable
private fun FirstRunNotifications() {
    val context = androidx.compose.ui.platform.LocalContext.current
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    fun finish(granted: Boolean) {
        scope.launch {
            com.racepocket.app.data.Repo.settings.edit { p ->
                p[com.racepocket.app.data.Keys.onboarded] = true
                if (granted) {
                    p[com.racepocket.app.data.Keys.notifEnabled] = true
                    p[com.racepocket.app.data.Keys.resultsEnabled] = true
                    com.racepocket.app.data.SessionKind.entries.forEach {
                        p[com.racepocket.app.data.Keys.notif(it)] = true
                        p[com.racepocket.app.data.Keys.results(it)] = true
                    }
                }
            }
            if (granted) com.racepocket.app.notify.Notifier.rescheduleSoon(context)
        }
    }
    val launcher = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.RequestPermission()
    ) { finish(it) }
    LaunchedEffect(Unit) {
        if (com.racepocket.app.data.Repo.settings.onboarded()) return@LaunchedEffect
        val info = context.packageManager.getPackageInfo(context.packageName, 0)
        if (info.firstInstallTime != info.lastUpdateTime) {
            // an update of an app already in use: keep every choice as it is
            com.racepocket.app.data.Repo.settings.edit { it[com.racepocket.app.data.Keys.onboarded] = true }
            return@LaunchedEffect
        }
        if (android.os.Build.VERSION.SDK_INT >= 33) launcher.launch(android.Manifest.permission.POST_NOTIFICATIONS) else finish(true)
    }
}
