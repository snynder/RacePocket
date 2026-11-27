package com.racepocket.app.ui.screens

import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.NotificationsOff
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import com.racepocket.app.ui.PullRefresh
import com.racepocket.app.ui.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavHostController
import com.racepocket.app.R
import com.racepocket.app.data.AppSettings
import com.racepocket.app.data.CircuitHistory
import com.racepocket.app.data.DriverStanding
import com.racepocket.app.data.Flags
import com.racepocket.app.data.Jolpica
import com.racepocket.app.data.OpenF1
import com.racepocket.app.data.Race
import com.racepocket.app.data.ResultRow
import com.racepocket.app.data.SeasonLeaders
import com.racepocket.app.data.SessionKind
import com.racepocket.app.data.SessionTime
import com.racepocket.app.data.SessionWeather
import com.racepocket.app.data.live.LiveParser
import com.racepocket.app.data.live.LiveTimingClient
import com.racepocket.app.data.live.LiveView
import com.racepocket.app.data.Static
import com.racepocket.app.data.TeamStanding
import com.racepocket.app.data.TrackGeometry
import com.racepocket.app.data.Tracks
import com.racepocket.app.data.Updater
import com.racepocket.app.data.Weather
import com.racepocket.app.ui.BaseVM
import com.racepocket.app.ui.CardBox
import com.racepocket.app.ui.Avatar
import com.racepocket.app.ui.Divider
import com.racepocket.app.ui.ErrorBox
import com.racepocket.app.ui.Flag
import com.racepocket.app.ui.Fmt
import com.racepocket.app.ui.Load
import com.racepocket.app.ui.LoadBox
import com.racepocket.app.ui.LoadingBox
import com.racepocket.app.ui.Mono
import com.racepocket.app.ui.Muted
import com.racepocket.app.ui.RankRow
import com.racepocket.app.ui.Routes
import com.racepocket.app.ui.SectionTitle
import com.racepocket.app.ui.StatTile
import com.racepocket.app.ui.TrackMap
import com.racepocket.app.ui.goTab
import com.racepocket.app.ui.pal
import com.racepocket.app.ui.sessionName
import com.racepocket.app.ui.teamColor
import com.racepocket.app.ui.valueOrNull
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import com.racepocket.app.data.FreshFetch
import androidx.compose.ui.text.style.TextDecoration
import java.time.Duration
import java.time.Instant
import java.time.Year

data class HomeCore(
    val year: Int,
    val races: List<Race>,
    val next: Race?,
    val last: Race?,
    val lastResults: List<ResultRow>,
    val drivers: List<DriverStanding>,
    val teams: List<TeamStanding>,
    val prevDrivers: Map<String, Int> = emptyMap(),
    val prevTeams: Map<String, Int> = emptyMap()
)

data class NextExtra(val history: CircuitHistory?, val weather: Map<SessionKind, SessionWeather>?, val lastPodium: List<ResultRow>)

class HomeVM : BaseVM() {
    val core = MutableStateFlow<Load<HomeCore>>(Load.Loading)
    val extra = MutableStateFlow<Load<NextExtra>>(Load.Loading)
    val leaders = MutableStateFlow<Load<SeasonLeaders>>(Load.Loading)
    val track = MutableStateFlow<TrackGeometry?>(null)
    val photos = MutableStateFlow<Map<String, String>>(emptyMap())

    val liveView = MutableStateFlow<LiveView?>(null)
    private var liveClient: LiveTimingClient? = null
    private var liveJob: Job? = null

    init { refresh() }

    /** Connects to the F1 live feed only while a session is on, to know if it is green, SC/VSC or red flag. */
    fun watchLive(on: Boolean) {
        if (on && liveClient == null) {
            val c = LiveTimingClient(viewModelScope)
            liveClient = c
            c.connect()
            liveJob = viewModelScope.launch {
                c.data.collect { if (it.isNotEmpty()) liveView.value = runCatching { LiveParser.parse(it) }.getOrNull() }
            }
        } else if (!on && liveClient != null) {
            liveJob?.cancel()
            liveClient?.disconnect()
            liveClient = null
            liveView.value = null
        }
    }

    override fun onCleared() {
        liveClient?.disconnect()
    }

    fun refresh() {
        // refreshing is true during a pull to refresh: the weather fetched after the core then skips the cache too
        val fresh = refreshing.value
        load(core) {
            val now = Instant.now()
            var year = Year.now().value
            var races = Jolpica.calendar(year)
            if (races.isEmpty()) { year -= 1; races = Jolpica.calendar(year) }
            var next = races.firstOrNull { !it.isFinished(now) }
            val upcomingYear = if (next == null) runCatching { Jolpica.calendar(year + 1) }.getOrDefault(emptyList()) else emptyList()
            if (next == null) next = upcomingYear.firstOrNull()
            var last: Race? = null
            var lastResults: List<ResultRow> = emptyList()
            for (r in races.filter { it.isFinished(now) }.reversed().take(2)) {
                val (_, res) = Jolpica.raceResults(r.season, r.round)
                if (res.isNotEmpty()) { last = r; lastResults = res; break }
            }
            var drivers = Jolpica.driverStandings(year)
            var teams = Jolpica.constructorStandings(year)
            if (drivers.isEmpty()) { drivers = Jolpica.driverStandings(year - 1); teams = Jolpica.constructorStandings(year - 1) }
            val standYear = if (Jolpica.driverStandings(year).isEmpty()) year - 1 else year
            val pd = runCatching { Jolpica.previousPositions(standYear, teams = false) }.getOrDefault(emptyMap())
            val pt = runCatching { Jolpica.previousPositions(standYear, teams = true) }.getOrDefault(emptyMap())
            HomeCore(year, races + upcomingYear, next, last, lastResults, drivers, teams, pd, pt)
        }.invokeOnCompletion { afterCore(fresh) }
    }

    private fun afterCore(fresh: Boolean = false) {
        val c = core.value.valueOrNull ?: return
        viewModelScope.launch { photos.value = OpenF1.latestHeadshots() }
        load(leaders) { Jolpica.seasonLeaders(c.year) }
        val next = c.next
        if (next == null) {
            extra.value = Load.Ok(NextExtra(null, null, emptyList()))
            return
        }
        load(extra) {
            val history = runCatching { Jolpica.circuitHistory(next.circuitId) }.getOrNull()
            val weather = runCatching {
                if (fresh) withContext(FreshFetch()) { Weather.forSessions(next.lat, next.lon, next.sessions) }
                else Weather.forSessions(next.lat, next.lon, next.sessions)
            }.getOrNull()
            val lw = history?.lastWinner
            val podium = if (lw != null) runCatching { Jolpica.raceResults(lw.season, lw.round).second.take(3) }.getOrDefault(emptyList()) else emptyList()
            NextExtra(history, weather, podium)
        }
        viewModelScope.launch {
            track.value = runCatching { Tracks.get(next.name, minOf(next.season, Year.now().value)) }.getOrNull()
        }
    }
}

/** The session on now (scheduled window, with a margin), whatever the banner settings. */
fun currentSession(races: List<Race>, now: Instant): Pair<Race, SessionTime>? {
    for (r in races) for (s in r.sessions) {
        if (now.isAfter(s.start.minusSeconds(10 * 60)) && now.isBefore(s.end.plusSeconds(30 * 60))) return r to s
    }
    return null
}

enum class DotColor { GREEN, YELLOW, RED, GREY }

data class LiveDotState(val color: DotColor, val label: String?)

/** Whether the session is actually running, and its flag, from the live feed when available. */
@Composable
fun liveDotState(s: SessionTime, v: LiveView?, now: Instant): LiveDotState? {
    val running = when {
        v == null -> !now.isBefore(s.start) && now.isBefore(s.end)
        v.partBreak -> true // end of Q1/Q2: qualifying is still on
        v.over -> false
        v.status == "Started" || v.status == "Aborted" -> true
        else -> !now.isBefore(s.start) && now.isBefore(s.end)
    }
    if (!running) return null
    val part = v?.partLabel
    if (v != null && v.partBreak) return LiveDotState(DotColor.GREY, stringResource(R.string.quali_part_end, part ?: ""))
    val state = when {
        v?.trackStatus == "5" || v?.status == "Aborted" -> LiveDotState(DotColor.RED, stringResource(R.string.flag_red))
        v?.trackStatus == "4" -> LiveDotState(DotColor.YELLOW, stringResource(R.string.safety_car))
        v?.trackStatus == "6" || v?.trackStatus == "7" -> LiveDotState(DotColor.YELLOW, stringResource(R.string.vsc))
        else -> LiveDotState(DotColor.GREEN, null)
    }
    // "Q2" or "Q2 · Safety car"
    return if (part == null) state else state.copy(label = listOfNotNull(part, state.label).joinToString(" · "))
}

@Composable
fun BlinkingDot(color: DotColor, size: androidx.compose.ui.unit.Dp = 9.dp) {
    val t = rememberInfiniteTransition(label = "dot")
    val alpha by t.animateFloat(1f, 0.15f, infiniteRepeatable(tween(650), RepeatMode.Reverse), label = "alpha")
    val c = when (color) {
        DotColor.GREEN -> Color(0xFF2ECC71)
        DotColor.YELLOW -> Color(0xFFFFC400)
        DotColor.RED -> Color(0xFFFF2D2D)
        DotColor.GREY -> Color(0xFF8A929C)
    }
    Box(Modifier.size(size).alpha(alpha).clip(CircleShape).background(c))
}

/** The next session to count down to, among the ones the user picked in the countdown settings. */
fun nextCountdown(races: List<Race>, settings: AppSettings, now: Instant): Pair<Race, SessionTime>? {
    if (!settings.countdownEnabled) return null
    return races.flatMap { r -> r.sessions.map { r to it } }
        .filter { settings.countdown[it.second.kind] == true && it.second.start.isAfter(now) }
        .minByOrNull { it.second.start }
}

/** The session happening now, if any, and if the user wants the banner for it. */
fun liveSession(races: List<Race>, settings: AppSettings, now: Instant): Pair<Race, SessionKind>? {
    for (r in races) for (s in r.sessions) {
        if (settings.banner[s.kind] != true) continue
        val from = s.start.minusSeconds(10 * 60)
        val to = s.end.plusSeconds(20 * 60)
        if (now.isAfter(from) && now.isBefore(to)) return r to s.kind
    }
    return null
}

@Composable
fun HomeScreen(nav: NavHostController, settings: AppSettings) {
    val vm: HomeVM = viewModel()
    val core by vm.core.collectAsState()
    val extra by vm.extra.collectAsState()
    val leaders by vm.leaders.collectAsState()
    val track by vm.track.collectAsState()
    val photos by vm.photos.collectAsState()
    val liveView by vm.liveView.collectAsState()
    var now by remember { mutableStateOf(Instant.now()) }
    LaunchedEffect(Unit) {
        while (true) { delay(15_000); now = Instant.now() }
    }
    val p = pal()
    val current = core.valueOrNull?.let { currentSession(it.races, now) }
    LaunchedEffect(current != null) { vm.watchLive(current != null) }
    val dot = current?.let { liveDotState(it.second, liveView, now) }
    val update by Updater.state.collectAsState()
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(lifecycle) {
        val obs = LifecycleEventObserver { _, e -> if (e == Lifecycle.Event.ON_RESUME) Updater.check(settings, silent = true) }
        lifecycle.addObserver(obs)
        onDispose { lifecycle.removeObserver(obs) }
    }

    PullRefresh(vm, { vm.refresh() }) {
        LazyColumn(contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            item {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        buildAnnotatedString {
                            withStyle(SpanStyle(color = p.accent)) { append("Race") }
                            append("Pocket")
                        },
                        fontSize = 24.sp, fontWeight = FontWeight.ExtraBold, color = p.text, modifier = Modifier.weight(1f)
                    )
                    IconButton(onClick = { nav.navigate(Routes.NOTIFICATIONS) }) {
                        Icon(
                            if (settings.notifEnabled || settings.resultsEnabled) Icons.Filled.Notifications else Icons.Filled.NotificationsOff,
                            contentDescription = stringResource(R.string.notifications), tint = p.muted
                        )
                    }
                    IconButton(onClick = { nav.navigate(Routes.SETTINGS) }) {
                        Icon(Icons.Filled.Settings, contentDescription = stringResource(R.string.settings), tint = p.muted)
                    }
                }
            }
            if (update is Updater.State.Available || update is Updater.State.Downloading || update is Updater.State.Ready) {
                item { CardBox(color = pal().liveBg) { UpdateBox(settings, showUpToDate = false) } }
            }
            when (val c = core) {
                is Load.Loading -> item { LoadingBox() }
                is Load.Err -> item { ErrorBox(c.error) { vm.refresh() } }
                is Load.Ok -> {
                    val data = c.value
                    val live = liveSession(data.races, settings, now)
                    // past its start and no longer running: the session is over
                    val liveStart = live?.let { l -> l.first.sessions.firstOrNull { it.kind == l.second }?.start }
                    val running = live != null && dot != null
                    val over = live != null && dot == null && liveStart != null && now.isAfter(liveStart)
                    // the countdown sits where the live banner goes, and gives way to it while a session is on
                    val countdown = if (running) null else nextCountdown(data.races, settings, now)
                    when {
                        running -> item { LiveBanner(live!!.first, live.second, dot, false) { nav.navigate(Routes.LIVE) } }
                        countdown != null -> item {
                            CountdownBanner(countdown.first, countdown.second) {
                                nav.navigate(Routes.circuit(countdown.first.circuitId, countdown.first.season, countdown.first.round))
                            }
                        }
                        live != null -> item { LiveBanner(live.first, live.second, dot, over) { nav.navigate(Routes.LIVE) } }
                    }

                    // from the Monday of race week the weekend's race leaves this list (it is in the cards below)
                    val today = now.atZone(java.time.ZoneId.systemDefault()).toLocalDate()
                    val upcoming = data.races.filter {
                        val raceWeek = it.firstDay.with(java.time.temporal.TemporalAdjusters.previousOrSame(java.time.DayOfWeek.MONDAY))
                        !it.isFinished(now) && today.isBefore(raceWeek)
                    }.take(8)
                    if (upcoming.isNotEmpty()) {
                        item { SectionTitle(stringResource(R.string.upcoming), stringResource(R.string.calendar), onAction = { nav.goTab(Routes.SEASON) }) }
                        item {
                            LazyRow(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                items(upcoming, key = { "${it.season}-${it.round}" }) { r ->
                                    UpcomingCard(r, r == data.next) { nav.navigate(Routes.circuit(r.circuitId, r.season, r.round)) }
                                }
                            }
                        }
                    }

                    data.next?.let { next ->
                        val curKind = current?.takeIf { it.first.season == next.season && it.first.round == next.round }?.second?.kind
                        item {
                            NextRaceCard(
                                next, now, extra, track, settings, curKind, dot,
                                // a finished session opens its result in Replay; one still to come or running opens Live
                                onSession = { s, done -> nav.navigate(if (done) Routes.replay(s.start) else Routes.LIVE) }
                            ) { nav.navigate(Routes.circuit(next.circuitId, next.season, next.round)) }
                        }
                    }

                    data.last?.let { last ->
                        item { LastRaceCard(last, data.lastResults, photos) { nav.navigate(Routes.race(last.season, last.round)) } }
                    }

                    item { SectionTitle(stringResource(R.string.season_numbers, data.year)) }
                    item {
                        LoadBox(leaders, onRetry = { vm.refresh() }) { l -> LeadersGrid(l) }
                    }

                    if (data.drivers.isNotEmpty()) item {
                        CardBox(padding = 0.dp) {
                            Box(Modifier.padding(horizontal = 14.dp, vertical = 10.dp)) { SectionTitle(stringResource(R.string.drivers_top10)) }
                            data.drivers.take(10).forEach { d ->
                                Divider()
                                RankRow(
                                    d.pos, teamColor(d.team?.color ?: 0xFF8A929C), d.driver.flag, d.driver.name, d.team?.name, Fmt.points(d.points),
                                    delta = posDelta(d.pos, data.prevDrivers[d.driver.id]), showDelta = data.prevDrivers.isNotEmpty()
                                )
                            }
                            SeeAllFooter(stringResource(R.string.see_full_drivers)) { nav.goTab(Routes.STANDINGS) }
                        }
                    }
                    if (data.teams.isNotEmpty()) item {
                        CardBox(padding = 0.dp) {
                            Box(Modifier.padding(horizontal = 14.dp, vertical = 10.dp)) { SectionTitle(stringResource(R.string.constructors)) }
                            data.teams.take(11).forEach { t ->
                                Divider()
                                RankRow(
                                    t.pos, teamColor(t.team.color), t.team.flag, t.team.name, null, Fmt.points(t.points),
                                    delta = posDelta(t.pos, data.prevTeams[t.team.id]), showDelta = data.prevTeams.isNotEmpty()
                                )
                            }
                            SeeAllFooter(stringResource(R.string.see_full_teams)) { nav.goTab(Routes.STANDINGS) }
                        }
                    }

                }
            }
        }
    }
}

@Composable
private fun SeeAllFooter(text: String, onClick: () -> Unit) {
    val p = pal()
    Divider()
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 14.dp, vertical = 13.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(text, fontSize = 13.sp, fontWeight = FontWeight.Bold, color = p.accentText, modifier = Modifier.weight(1f))
        Text("›", fontSize = 18.sp, fontWeight = FontWeight.Bold, color = p.accentText)
    }
}

@Composable
private fun LiveBanner(race: Race, kind: SessionKind, dot: LiveDotState?, over: Boolean, onClick: () -> Unit) {
    val p = pal()
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(p.liveBg)
            .border(1.dp, p.accent.copy(alpha = 0.5f), RoundedCornerShape(14.dp)).clickable(onClick = onClick).padding(12.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        if (!over) BlinkingDot(dot?.color ?: DotColor.GREEN, 11.dp)
        Column(Modifier.weight(1f)) {
            Text(
                if (over) stringResource(R.string.session_finished) else stringResource(R.string.live_now) + (dot?.label?.let { " · $it" } ?: ""),
                fontSize = 11.sp, fontWeight = FontWeight.ExtraBold, color = if (over) p.muted else p.accentText
            )
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Flag(race.flag, 14.sp)
                Text(
                    "${sessionName(kind)} · ${Fmt.raceName(race.name)}", fontSize = 14.sp, fontWeight = FontWeight.SemiBold, maxLines = 1,
                    color = if (over) p.muted else p.text, textDecoration = if (over) TextDecoration.LineThrough else null
                )
            }
        }
        Text(stringResource(R.string.see_timing), fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = p.accentText)
    }
}

@Composable
private fun CountdownBanner(race: Race, session: SessionTime, onClick: () -> Unit) {
    val p = pal()
    var now by remember { mutableStateOf(Instant.now()) }
    LaunchedEffect(session.start) {
        while (true) { now = Instant.now(); delay(1000) }
    }
    val d = Duration.between(now, session.start).coerceAtLeast(Duration.ZERO)
    val clock = String.format("%02d:%02d:%02d", d.toHours() % 24, d.toMinutes() % 60, d.seconds % 60)
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(p.card)
            .border(1.dp, p.divider, RoundedCornerShape(14.dp)).clickable(onClick = onClick).padding(12.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Column(Modifier.weight(1f)) {
            Text(stringResource(R.string.next_session).uppercase(), fontSize = 11.sp, fontWeight = FontWeight.ExtraBold, color = p.accentText)
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Flag(race.flag, 14.sp)
                Text("${sessionName(session.kind)} · ${Fmt.raceName(race.name)}", fontSize = 14.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, color = p.text)
            }
            Mono(Fmt.weekdayTime(session.start), 11.sp, FontWeight.Normal, p.muted)
        }
        Column(horizontalAlignment = Alignment.End) {
            if (d.toDays() > 0) Muted(pluralDays(d.toDays()), 11.sp)
            Mono(clock, 18.sp, FontWeight.Bold)
        }
    }
}

@Composable
private fun pluralDays(n: Long): String = "$n " + stringResource(R.string.days)

@Composable
private fun UpcomingCard(r: Race, isNext: Boolean, onClick: () -> Unit) {
    val p = pal()
    Column(
        Modifier.width(196.dp).clip(RoundedCornerShape(14.dp)).background(p.card)
            .border(1.dp, if (isNext) p.accent else p.divider, RoundedCornerShape(14.dp)).clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(1.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Flag(r.flag, 18.sp)
            Muted(stringResource(R.string.round_n, r.round), 10.sp, Modifier.weight(1f))
            Mono(Fmt.range(r.firstDay, r.lastDay), 11.sp, FontWeight.Bold)
        }
        Text(Fmt.raceName(r.name), fontSize = 14.sp, fontWeight = FontWeight.Bold, color = p.text, maxLines = 1)
        Muted(r.locality, 11.sp, maxLines = 1)
        Text(
            stringResource(R.string.s_race) + " · " + (r.raceStart?.let { Fmt.weekdayTime(it) } ?: Fmt.weekdayDayMonth(r.date)),
            fontSize = 11.sp, color = p.muted, maxLines = 1
        )
    }
}

@Composable
private fun NextRaceCard(
    next: Race, now: Instant, extra: Load<NextExtra>, track: TrackGeometry?, settings: AppSettings,
    liveKind: SessionKind?, dot: LiveDotState?, onSession: (SessionTime, Boolean) -> Unit, onCircuit: () -> Unit
) {
    val p = pal()
    fun isDone(s: SessionTime) = !(s.kind == liveKind && dot != null) && now.isAfter(s.end)
    // the latest finished session of the weekend: the Results button opens it (hidden until FP1 ends)
    val lastDone = next.sessions.lastOrNull { isDone(it) }
    CardBox(padding = 0.dp) {
        Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Flag(next.flag, 34.sp)
            Column(Modifier.weight(1f)) {
                // only the round and the name share their space with the Results button:
                // the circuit line below gets the full width, so it does not wrap
                Row(verticalAlignment = Alignment.Top) {
                    Column(Modifier.weight(1f)) {
                        Text(stringResource(R.string.next_race_round, next.round).uppercase(), fontSize = 11.sp, fontWeight = FontWeight.Bold, color = p.accentText)
                        Text(Fmt.raceName(next.name), fontSize = 21.sp, fontWeight = FontWeight.ExtraBold, color = p.text)
                    }
                    if (lastDone != null) {
                        Text(
                            stringResource(R.string.results), fontSize = 12.sp, fontWeight = FontWeight.Bold, color = p.accentText, maxLines = 1, softWrap = false,
                            modifier = Modifier.padding(start = 8.dp).minimumInteractiveComponentSize().clip(RoundedCornerShape(8.dp)).clickable { onSession(lastDone, true) }
                                .background(p.card2).padding(horizontal = 10.dp, vertical = 6.dp)
                        )
                    }
                }
                Muted("${next.circuitName} · ${next.locality}", 12.sp)
            }
        }
        next.raceStart?.let { start ->
            val d = Duration.between(now, start).coerceAtLeast(Duration.ZERO)
            Row(
                Modifier.padding(horizontal = 14.dp).fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(p.bg).padding(12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(Modifier.weight(1f)) {
                    Muted(stringResource(R.string.race_start), 11.sp)
                    Mono(Fmt.weekdayTime(start), 13.sp, FontWeight.Bold)
                    Muted(stringResource(R.string.device_time), 10.sp)
                }
                listOf(d.toDays() to R.string.days, d.toHours() % 24 to R.string.hours, d.toMinutes() % 60 to R.string.minutes_short).forEach { (v, label) ->
                    Column(
                        Modifier.padding(start = 6.dp).clip(RoundedCornerShape(8.dp)).background(p.card2).padding(horizontal = 8.dp, vertical = 6.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Mono(String.format("%02d", v), 18.sp, FontWeight.Bold)
                        Muted(stringResource(label), 9.sp)
                    }
                }
            }
        }
        // sessions of the weekend in the device time zone, each with the forecast for its time
        val ex = extra.valueOrNull
        val weather = ex?.weather.orEmpty()
        Column(Modifier.padding(horizontal = 14.dp, vertical = 10.dp)) {
            next.sessions.forEach { s ->
                val isLive = s.kind == liveKind && dot != null
                val done = isDone(s)
                val strike = if (done) TextDecoration.LineThrough else null
                Row(
                    Modifier.fillMaxWidth().clip(RoundedCornerShape(6.dp)).clickable { onSession(s, done) }.padding(vertical = 3.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    if (isLive) {
                        BlinkingDot(dot!!.color)
                        Spacer(Modifier.width(6.dp))
                    }
                    Text(
                        sessionName(s.kind) + (if (isLive) dot?.label?.let { " · $it" } ?: "" else ""),
                        fontSize = 12.sp, color = if (done) p.muted else p.text, modifier = Modifier.weight(1f), textDecoration = strike,
                        fontWeight = if (s.kind == SessionKind.RACE || isLive) FontWeight.Bold else FontWeight.Normal
                    )
                    Mono(Fmt.weekdayTime(s.start), if (weather.isNotEmpty()) 11.sp else 12.sp, FontWeight.Normal, p.muted, decoration = strike)
                    // same width on every line, so the forecasts line up
                    if (weather.isNotEmpty()) SessionWeatherCell(weather[s.kind], done, Modifier.padding(start = 8.dp).width(84.dp))
                }
            }
            if (ex != null && weather.isEmpty()) Muted(stringResource(R.string.weather_soon), 11.sp, Modifier.padding(top = 6.dp))
        }
        if (track != null) {
            TrackMap(track, Tracks.zonesFor(track.name), Modifier.padding(top = 8.dp))
        }
        val h = ex?.history
        val facts = buildList {
            add(Triple(stringResource(R.string.length), Static.LENGTH_KM[next.circuitId]?.let { String.format("%.3f km", it) } ?: "—", ""))
            add(Triple(stringResource(R.string.corners), (track?.corners?.size?.takeIf { it > 0 } ?: Static.TRACK_TURNS[next.circuitId])?.toString() ?: "—", ""))
            add(Triple(stringResource(R.string.laps), h?.lastWinner?.laps?.takeIf { it > 0 }?.toString() ?: "—", ""))
            add(Triple(stringResource(R.string.gps_held), h?.gpsHeld?.toString() ?: "—", ""))
            add(Triple(stringResource(R.string.first_gp), h?.firstGp?.toString() ?: "—", ""))
            if (h != null) {
                val king = h.top(h.winners, 1).firstOrNull()
                if (king != null) add(Triple(stringResource(R.string.most_wins_here), "${king.first.driver.code} (${king.second})", king.first.driver.flag + "|"))
                val pole = h.lastPole
                if (pole != null) add(Triple(stringResource(R.string.last_pole), pole.driver.code, pole.driver.flag + "|" + (pole.time ?: "")))
                val win = h.lastWinner
                if (win != null) add(Triple(stringResource(R.string.last_winner), win.driver.code, win.driver.flag + "|" + win.season))
                val fl = h.lastFastest
                if (fl != null) add(Triple(stringResource(R.string.fastest_lap), fl.driver.code, fl.driver.flag + "|" + (fl.time ?: "")))
            }
        }
        if (extra is Load.Loading) LoadingBox()
        Column(Modifier.padding(horizontal = 14.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            facts.chunked(3).forEach { row ->
                Row(Modifier.height(IntrinsicSize.Min), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    row.forEach { (label, value, sub) ->
                        val flag = sub.substringBefore("|", "")
                        val subText = if (sub.contains("|")) sub.substringAfter("|") else sub
                        StatTile(label, value, Modifier.weight(1f).fillMaxHeight(), subText, flag, color = p.bg)
                    }
                    repeat(3 - row.size) { Spacer(Modifier.weight(1f)) }
                }
            }
        }
        if (ex != null && ex.lastPodium.isNotEmpty()) {
            Column(Modifier.padding(horizontal = 14.dp)) {
                Muted(stringResource(R.string.last_edition_podium), 11.sp)
                Spacer(Modifier.height(6.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    ex.lastPodium.forEachIndexed { i, r ->
                        Row(
                            Modifier.weight(1f).clip(RoundedCornerShape(8.dp)).background(p.bg).padding(8.dp),
                            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            Text("${i + 1}", fontWeight = FontWeight.Bold, fontSize = 12.sp, color = MEDALS[i])
                            Flag(r.driver.flag, 12.sp)
                            Text(r.driver.code, fontWeight = FontWeight.Bold, fontSize = 12.sp, color = p.text)
                        }
                    }
                }
            }
        }
        // broadcasters for the selected country
        val cc = settings.effectiveCountry
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Divider()
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 6.dp)) {
                Muted(stringResource(R.string.broadcast), 12.sp)
                Flag(Flags.emoji(cc), 15.sp)
                Muted(Static.countryName(cc), 12.sp)
            }
            Text((Static.BROADCAST[cc] ?: Static.FALLBACK_BROADCAST).joinToString(" · "), fontSize = 15.sp, fontWeight = FontWeight.ExtraBold, color = p.text)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Muted(stringResource(R.string.change_country_hint), 11.sp, Modifier.weight(1f))
                Text(stringResource(R.string.view_circuit), fontSize = 13.sp, fontWeight = FontWeight.Bold, color = p.accentText, modifier = Modifier.minimumInteractiveComponentSize().clickable(onClick = onCircuit).padding(8.dp))
            }
        }
    }
}

private val MEDALS = listOf(Color(0xFFFFD12E), Color(0xFFC9C9D3), Color(0xFFD08A4C))

/** Sky, temperature at the start and rain chance of one session; empty when there is no forecast yet. */
@Composable
private fun SessionWeatherCell(w: SessionWeather?, done: Boolean, modifier: Modifier = Modifier) {
    val p = pal()
    Row(modifier.alpha(if (done) 0.55f else 1f), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.End) {
        if (w == null) return@Row
        Text(weatherEmoji(w.code, w.day), fontSize = 13.sp)
        Mono("${w.temp?.let { Math.round(it) } ?: "-"}°", 11.sp, FontWeight.Bold, modifier = Modifier.padding(start = 3.dp))
        Text(stringResource(R.string.rain_pct, w.rain ?: 0), fontSize = 10.sp, color = Color(0xFF6CB6FF), maxLines = 1, modifier = Modifier.padding(start = 4.dp))
    }
}

fun weatherEmoji(code: Int, day: Boolean = true): String = when {
    !day && code <= 2 -> "🌙"
    code == 0 -> "☀️"
    code <= 2 -> "🌤️"
    code == 3 -> "☁️"
    code <= 48 -> "🌫️"
    code <= 57 -> "🌦️"
    code <= 67 -> "🌧️"
    code <= 77 -> "🌨️"
    code <= 82 -> "🌦️"
    else -> "⛈️"
}

@Composable
private fun LeadersGrid(l: SeasonLeaders) {
    val p = pal()
    val cards = listOf(
        stringResource(R.string.wins) to l.wins, stringResource(R.string.poles) to l.poles,
        stringResource(R.string.podiums) to l.podiums, stringResource(R.string.fastest_laps) to l.fastest
    )
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        cards.chunked(2).forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                row.forEach { (label, list) ->
                    Column(Modifier.weight(1f).clip(RoundedCornerShape(12.dp)).background(p.card).padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Muted(label, 11.sp)
                        val first = list.firstOrNull()
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Flag(first?.driver?.flag ?: "", 14.sp)
                            Text(first?.driver?.last ?: "—", fontSize = 14.sp, fontWeight = FontWeight.ExtraBold, color = p.text, modifier = Modifier.weight(1f).padding(start = 6.dp), maxLines = 1)
                            Mono("${first?.count ?: 0}", 18.sp, FontWeight.Bold)
                        }
                        Muted(list.drop(1).joinToString(" · ") { "${it.driver.last} ${it.count}" }, 11.sp, maxLines = 1)
                    }
                }
            }
        }
    }
}

@Composable
private fun LastRaceCard(race: Race, results: List<ResultRow>, photos: Map<String, String>, onOpen: () -> Unit) {
    val p = pal()
    CardBox(padding = 0.dp, onClick = onOpen) {
        Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Flag(race.flag, 28.sp)
            Column(Modifier.weight(1f)) {
                Muted("${stringResource(R.string.last_race).uppercase()} · ${stringResource(R.string.round_n, race.round).uppercase()} · ${Fmt.dayMonth(race.date)}", 11.sp)
                Text(Fmt.raceName(race.name), fontSize = 17.sp, fontWeight = FontWeight.Bold, color = p.text)
            }
            Text(stringResource(R.string.details), fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = p.accentText)
        }
        val podium = results.take(3)
        if (podium.size == 3) {
            Row(Modifier.padding(horizontal = 14.dp).fillMaxWidth(), verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(1, 0, 2).forEach { i ->
                    val r = podium[i]
                    val size = listOf(78.dp, 64.dp, 58.dp)[i]
                    val step = listOf(84.dp, 58.dp, 42.dp)[i]
                    Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Avatar(photos[r.driver.code], r.driver.first.take(1) + r.driver.last.take(1), size, teamColor(r.team.color))
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                            Flag(r.driver.flag, 12.sp)
                            Text(r.driver.code, fontSize = 13.sp, fontWeight = FontWeight.Bold, color = p.text)
                        }
                        Box(
                            Modifier.fillMaxWidth().height(step).clip(RoundedCornerShape(topStart = 8.dp, topEnd = 8.dp)).background(p.card2),
                            contentAlignment = Alignment.TopCenter
                        ) {
                            Text("${i + 1}", fontSize = 18.sp, fontWeight = FontWeight.ExtraBold, color = MEDALS[i], modifier = Modifier.padding(top = 6.dp))
                        }
                    }
                }
            }
        }
        Spacer(Modifier.height(8.dp))
        results.take(10).forEach { r ->
            Divider()
            RankRow(r.posText, teamColor(r.team.color), r.driver.flag, r.driver.name, r.team.name, resultTime(r))
        }
    }
}

@Composable
fun resultTime(r: ResultRow): String = when {
    r.time != null -> r.time
    r.status == "Finished" -> ""
    r.status.startsWith("+") || r.status == "Lapped" -> r.status
    r.classified -> r.status
    r.status.contains("start", true) -> "DNS"
    r.status.contains("disq", true) -> "DSQ"
    else -> "DNF"
}
