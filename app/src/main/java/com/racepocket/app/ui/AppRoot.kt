package com.racepocket.app.ui

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
    fun circuit(id: String, year: Int? = null, round: Int? = null) = "circuit/$id?year=${year ?: -1}&round=${round ?: -1}"
    /** Replay opened on the session that started at [at] (matched by start time); without it, the latest one. */
    fun replay(at: java.time.Instant) = "$REPLAY?at=${at.epochSecond}"
    fun race(year: Int, round: Int, tab: Int? = null) = "race/$year/$round" + (tab?.let { "?tab=$it" } ?: "")
}

private data class Tab(val route: String, val label: Int, val icon: ImageVector)

private val TABS = listOf(
    Tab(Routes.HOME, R.string.nav_home, Icons.Filled.Home),
    Tab(Routes.STANDINGS, R.string.nav_standings, Icons.Filled.EmojiEvents),
    Tab(Routes.SEASON, R.string.nav_season, Icons.Filled.CalendarMonth),
    Tab(Routes.CIRCUITS, R.string.nav_circuits, Icons.Filled.Map),
    Tab(Routes.MORE, R.string.nav_more, Icons.Filled.Menu)
)

/** Which bottom tab a screen belongs to. */
private fun tabOf(route: String?): String = when {
    route == null -> Routes.HOME
    route.startsWith(Routes.STANDINGS) -> Routes.STANDINGS
    route.startsWith(Routes.SEASON) || route.startsWith("race/") -> Routes.SEASON
    route.startsWith(Routes.CIRCUITS) || route.startsWith("circuit/") -> Routes.CIRCUITS
    route in listOf(Routes.MORE, Routes.SETTINGS, Routes.RADIO, Routes.TELEMETRY, Routes.STRATEGY, Routes.SIMULATION, Routes.COMPARE, Routes.RECORDS) -> Routes.MORE
    else -> Routes.HOME
}

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
    val current = tabOf(entry?.destination?.route)
    val p = pal()
    Scaffold(
        containerColor = p.bg,
        bottomBar = {
            NavigationBar(containerColor = p.card) {
                TABS.forEach { t ->
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
                composable(Routes.MORE) { MoreScreen(nav) }
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
