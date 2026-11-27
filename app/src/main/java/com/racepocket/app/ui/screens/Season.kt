package com.racepocket.app.ui.screens

import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.OutlinedButton
import com.racepocket.app.ui.PullRefresh
import com.racepocket.app.ui.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavHostController
import com.racepocket.app.R
import com.racepocket.app.data.AppSettings
import com.racepocket.app.data.CircuitHistory
import com.racepocket.app.data.DriverStanding
import com.racepocket.app.data.Flags
import com.racepocket.app.data.HistEntry
import com.racepocket.app.data.Jolpica
import com.racepocket.app.data.OpenF1
import com.racepocket.app.data.PitRow
import com.racepocket.app.data.QualiRow
import com.racepocket.app.data.Race
import com.racepocket.app.data.ResultRow
import com.racepocket.app.data.Static
import com.racepocket.app.data.TeamStanding
import com.racepocket.app.data.TrackGeometry
import com.racepocket.app.data.Tracks
import com.racepocket.app.data.fmtLap
import com.racepocket.app.ui.BaseVM
import com.racepocket.app.ui.CardBox
import com.racepocket.app.ui.Divider
import com.racepocket.app.ui.EmptyBox
import com.racepocket.app.ui.ErrorBox
import com.racepocket.app.ui.Flag
import com.racepocket.app.ui.Fmt
import com.racepocket.app.ui.Load
import com.racepocket.app.ui.LoadBox
import com.racepocket.app.ui.LoadingBox
import com.racepocket.app.ui.Mono
import com.racepocket.app.ui.Muted
import com.racepocket.app.ui.Picker
import com.racepocket.app.ui.PillRow
import com.racepocket.app.ui.RankRow
import com.racepocket.app.ui.Routes
import com.racepocket.app.ui.ScreenHeader
import com.racepocket.app.ui.SectionTitle
import com.racepocket.app.ui.Segmented
import com.racepocket.app.ui.StatTile
import com.racepocket.app.ui.TeamBar
import com.racepocket.app.ui.TrackLegend
import com.racepocket.app.ui.TrackMap
import com.racepocket.app.ui.pal
import com.racepocket.app.ui.teamColor
import com.racepocket.app.ui.valueOrNull
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.Year
import kotlin.math.abs

private val YEARS = (Year.now().value downTo 1950).toList()

// ===================== STANDINGS =====================

data class StandingsData(val teams: List<TeamStanding>, val drivers: List<DriverStanding>, val prevTeams: Map<String, Int>, val prevDrivers: Map<String, Int>)

/** Places gained since the previous round (positive = up). */
fun posDelta(pos: String, prev: Int?): Int? = pos.toIntOrNull()?.let { now -> prev?.let { it - now } }

class StandingsVM : BaseVM() {
    val year = MutableStateFlow(Year.now().value)
    val data = MutableStateFlow<Load<StandingsData>>(Load.Loading)
    init { select(year.value) }
    fun select(y: Int) {
        year.value = y
        load(data) {
            val t = runCatching { Jolpica.constructorStandings(y) }.getOrDefault(emptyList())
            val d = Jolpica.driverStandings(y)
            val pt = runCatching { Jolpica.previousPositions(y, teams = true) }.getOrDefault(emptyMap())
            val pd = runCatching { Jolpica.previousPositions(y, teams = false) }.getOrDefault(emptyMap())
            StandingsData(t, d, pt, pd)
        }
    }
}

@Composable
fun StandingsScreen(nav: NavHostController) {
    val vm: StandingsVM = viewModel()
    val year by vm.year.collectAsState()
    val data by vm.data.collectAsState()
    val p = pal()
    PullRefresh(vm, { vm.select(vm.year.value) }) {
        LazyColumn(contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            item {
                ScreenHeader(stringResource(R.string.nav_standings)) {
                    Picker(stringResource(R.string.season), YEARS, year, { it.toString() }, Modifier.width(110.dp)) { vm.select(it) }
                }
            }
            item {
                LoadBox(data, onRetry = { vm.select(year) }) { (teams, drivers, prevTeams, prevDrivers) ->
                    Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                        if (teams.isEmpty() && drivers.isEmpty()) EmptyBox(stringResource(R.string.no_standings))
                        if (teams.isNotEmpty()) CardBox(padding = 0.dp) {
                            StandingsHeader(stringResource(R.string.constructors))
                            val max = teams.maxOf { it.points.toDoubleOrNull() ?: 0.0 }.coerceAtLeast(1.0)
                            teams.forEach { t ->
                                Divider()
                                Column {
                                    RankRow(
                                        t.pos, teamColor(t.team.color), t.team.flag, t.team.name, null, Fmt.points(t.points), extra = "${t.wins}",
                                        delta = posDelta(t.pos, prevTeams[t.team.id]), showDelta = prevTeams.isNotEmpty()
                                    )
                                    Box(Modifier.padding(start = if (prevTeams.isNotEmpty()) 96.dp else 60.dp, end = 14.dp, bottom = 8.dp).fillMaxWidth().height(4.dp).clip(RoundedCornerShape(2.dp)).background(p.card2)) {
                                        Box(Modifier.fillMaxWidth(((t.points.toDoubleOrNull() ?: 0.0) / max).toFloat()).height(4.dp).background(teamColor(t.team.color)))
                                    }
                                }
                            }
                        }
                        if (drivers.isNotEmpty()) CardBox(padding = 0.dp) {
                            StandingsHeader(stringResource(R.string.drivers))
                            drivers.forEach { d ->
                                Divider()
                                RankRow(
                                    d.pos, teamColor(d.team?.color ?: 0xFF8A929C), d.driver.flag, d.driver.name, d.team?.name, Fmt.points(d.points), extra = "${d.wins}",
                                    delta = posDelta(d.pos, prevDrivers[d.driver.id]), showDelta = prevDrivers.isNotEmpty()
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun StandingsHeader(title: String) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 10.dp), verticalAlignment = Alignment.Bottom) {
        Text(title, fontSize = 17.sp, fontWeight = FontWeight.Bold, color = pal().text, modifier = Modifier.weight(1f))
        Muted(stringResource(R.string.wins_short), 10.sp, Modifier.width(36.dp))
        Muted(stringResource(R.string.pts), 10.sp, Modifier.width(36.dp))
    }
}

// ===================== SEASON =====================

class SeasonVM : BaseVM() {
    val year = MutableStateFlow(Year.now().value)
    val data = MutableStateFlow<Load<Pair<List<Race>, Map<Int, ResultRow>>>>(Load.Loading)
    init { select(year.value) }
    fun select(y: Int) {
        year.value = y
        load(data) { Jolpica.calendar(y) to runCatching { Jolpica.seasonWinners(y) }.getOrDefault(emptyMap()) }
    }
}

@Composable
fun SeasonScreen(nav: NavHostController) {
    val vm: SeasonVM = viewModel()
    val year by vm.year.collectAsState()
    val data by vm.data.collectAsState()
    val p = pal()
    PullRefresh(vm, { vm.select(vm.year.value) }) {
        LazyColumn(contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            item {
                ScreenHeader(stringResource(R.string.nav_season)) {
                    Picker(stringResource(R.string.season), YEARS, year, { it.toString() }, Modifier.width(110.dp)) { vm.select(it) }
                }
            }
            when (val d = data) {
                is Load.Loading -> item { LoadingBox() }
                is Load.Err -> item { ErrorBox(d.error) { vm.select(year) } }
                is Load.Ok -> {
                    val (races, winners) = d.value
                    if (races.isEmpty()) item { EmptyBox(stringResource(R.string.calendar_soon)) }
                    val now = Instant.now()
                    val nextRound = races.firstOrNull { !it.isFinished(now) }?.round
                    item { Muted(stringResource(R.string.season_summary, races.size, winners.size), 12.sp) }
                    item {
                        CardBox(padding = 0.dp) {
                            races.forEachIndexed { i, r ->
                                if (i > 0) Divider()
                                val w = winners[r.round]
                                Row(
                                    Modifier.fillMaxWidth().background(if (r.round == nextRound) p.liveBg else p.card)
                                        // a race already run opens its result; one still to come, its circuit page
                                        .clickable { nav.navigate(if (r.isFinished(now)) Routes.race(r.season, r.round) else Routes.circuit(r.circuitId, r.season, r.round)) }
                                        .padding(horizontal = 12.dp, vertical = 10.dp),
                                    verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)
                                ) {
                                    Mono(String.format("%02d", r.round), 11.sp, FontWeight.Normal, p.muted)
                                    Flag(r.flag, 22.sp)
                                    Column(Modifier.weight(1f)) {
                                        Text(Fmt.raceName(r.name), fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = p.text)
                                        Mono(
                                            Fmt.range(r.firstDay, r.lastDay) + if (w == null && r.raceStart != null) " · " + Fmt.weekdayTime(r.raceStart) else "",
                                            11.sp, FontWeight.Normal, p.muted
                                        )
                                    }
                                    when {
                                        w != null -> Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                            Flag(w.driver.flag, 12.sp)
                                            Text(w.driver.code, fontSize = 12.sp, fontWeight = FontWeight.Bold, color = p.text)
                                        }
                                        r.round == nextRound -> Text(stringResource(R.string.next_tag), fontSize = 11.sp, fontWeight = FontWeight.Bold, color = p.accentText)
                                        r.isSprint -> Muted(stringResource(R.string.s_sprint), 10.sp)
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

// ===================== CIRCUITS =====================

class CircuitsVM : BaseVM() {
    val year = Year.now().value
    val calendar = MutableStateFlow<Load<List<Race>>>(Load.Loading)
    val all = MutableStateFlow<Load<List<Triple<String, String, String>>>>(Load.Loading)
    val maps = MutableStateFlow<Map<String, TrackGeometry>>(emptyMap())
    val progress = MutableStateFlow<String?>(null)
    init { refresh() }
    fun refresh() {
        load(calendar) { Jolpica.calendar(year).ifEmpty { Jolpica.calendar(year - 1) } }.invokeOnCompletion {
            val list = calendar.value.valueOrNull ?: return@invokeOnCompletion
            maps.value = list.mapNotNull { r -> Tracks.cached(r.name)?.let { r.name to it } }.toMap()
        }
    }
    fun loadAll() { if (all.value !is Load.Ok) load(all) { Jolpica.allCircuits() } }
    fun downloadAll() {
        val list = calendar.value.valueOrNull ?: return
        if (progress.value != null) return
        viewModelScope.launch {
            list.forEachIndexed { i, r ->
                progress.value = "${i + 1}/${list.size}"
                if (Tracks.cached(r.name) == null) {
                    runCatching { Tracks.get(r.name, minOf(r.season, year)) }.getOrNull()?.let { g -> maps.value = maps.value + (r.name to g) }
                }
            }
            progress.value = null
        }
    }
}

@Composable
fun CircuitsScreen(nav: NavHostController) {
    val vm: CircuitsVM = viewModel()
    val cal by vm.calendar.collectAsState()
    val all by vm.all.collectAsState()
    val maps by vm.maps.collectAsState()
    val progress by vm.progress.collectAsState()
    var tab by rememberSaveable { mutableIntStateOf(0) }
    val p = pal()
    PullRefresh(vm, { vm.refresh() }) {
        LazyColumn(contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            item { ScreenHeader(stringResource(R.string.nav_circuits)) }
            item { Segmented(listOf(stringResource(R.string.calendar_year, vm.year), stringResource(R.string.all_since_1950)), tab) { tab = it; if (it == 1) vm.loadAll() } }
            if (tab == 0) {
                item {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Muted(stringResource(R.string.maps_note), 11.sp, Modifier.weight(1f))
                        OutlinedButton(onClick = { vm.downloadAll() }) {
                            Text(progress?.let { stringResource(R.string.downloading, it) } ?: stringResource(R.string.download_maps), fontSize = 12.sp)
                        }
                    }
                }
                when (val c = cal) {
                    is Load.Loading -> item { LoadingBox() }
                    is Load.Err -> item { ErrorBox(c.error) { vm.refresh() } }
                    is Load.Ok -> items(c.value.chunked(2)) { pair ->
                        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            pair.forEach { r ->
                                Column(
                                    Modifier.weight(1f).clip(RoundedCornerShape(14.dp)).background(p.card)
                                        .clickable { nav.navigate(Routes.circuit(r.circuitId, r.season, r.round)) }
                                ) {
                                    Box(Modifier.fillMaxWidth().height(112.dp).background(p.bg), contentAlignment = Alignment.Center) {
                                        val g = maps[r.name]
                                        if (g != null) TrackMap(g, null, Modifier.padding(4.dp), compact = true) else Flag(r.flag, 40.sp)
                                    }
                                    Column(Modifier.padding(10.dp)) {
                                        Text(r.circuitName, fontSize = 13.sp, fontWeight = FontWeight.Bold, color = p.text, maxLines = 1, softWrap = false, overflow = TextOverflow.Ellipsis)
                                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                                            Flag(r.flag, 12.sp)
                                            Muted(r.country, 11.sp, maxLines = 1)
                                        }
                                        Mono(Static.LENGTH_KM[r.circuitId]?.let { String.format("%.3f km", it) } ?: "", 11.sp, FontWeight.Normal, p.muted)
                                    }
                                }
                            }
                            if (pair.size == 1) Box(Modifier.weight(1f))
                        }
                    }
                }
            } else {
                when (val a = all) {
                    is Load.Loading -> item { LoadingBox() }
                    is Load.Err -> item { ErrorBox(a.error) { vm.loadAll() } }
                    is Load.Ok -> item {
                        CardBox(padding = 0.dp) {
                            a.value.forEachIndexed { i, (id, name, country) ->
                                if (i > 0) Divider()
                                Row(
                                    Modifier.fillMaxWidth().clickable { nav.navigate(Routes.circuit(id)) }.padding(horizontal = 14.dp, vertical = 11.dp),
                                    verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)
                                ) {
                                    Flag(Flags.country(country), 18.sp)
                                    Column(Modifier.weight(1f)) {
                                        Text(name, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = p.text)
                                        Muted(country, 11.sp)
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

// ===================== CIRCUIT DETAIL =====================

data class CircuitData(val name: String, val locality: String, val country: String, val race: Race?, val history: CircuitHistory)

class CircuitVM(private val id: String, private val year: Int?, private val round: Int?) : BaseVM() {
    val data = MutableStateFlow<Load<CircuitData>>(Load.Loading)
    val track = MutableStateFlow<TrackGeometry?>(null)
    init { refresh() }
    fun refresh() {
        load(data) {
            val y = year
            val race = if (y != null) runCatching { Jolpica.calendar(y).firstOrNull { it.round == round || (round == null && it.circuitId == id) } }.getOrNull() else null
            val info = if (race == null) Jolpica.circuitInfo(id) else null
            val h = Jolpica.circuitHistory(id)
            CircuitData(race?.circuitName ?: info?.first ?: id, race?.locality ?: info?.second ?: "", race?.country ?: info?.third ?: "", race, h)
        }.invokeOnCompletion {
            val d = data.value.valueOrNull ?: return@invokeOnCompletion
            val latest = d.history.winners.maxByOrNull { it.season * 100 + it.round }
            val event = d.race?.name ?: latest?.raceName ?: return@invokeOnCompletion
            val y = minOf(d.race?.season ?: latest?.season ?: Year.now().value, Year.now().value)
            if (y < 2018) return@invokeOnCompletion
            viewModelScope.launch { track.value = runCatching { Tracks.get(event, y) }.getOrNull() }
        }
    }
}

@Composable
fun CircuitScreen(nav: NavHostController, id: String, year: Int?, round: Int?) {
    val vm: CircuitVM = viewModel(key = "circuit-$id-$year-$round") { CircuitVM(id, year, round) }
    val data by vm.data.collectAsState()
    val track by vm.track.collectAsState()
    val p = pal()
    PullRefresh(vm, { vm.refresh() }) {
        LazyColumn(contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            when (val d = data) {
                is Load.Loading -> {
                    item { ScreenHeader("", onBack = { nav.popBackStack() }) }
                    item { LoadingBox() }
                }
                is Load.Err -> {
                    item { ScreenHeader("", onBack = { nav.popBackStack() }) }
                    item { ErrorBox(d.error) { vm.refresh() } }
                }
                is Load.Ok -> {
                    val c = d.value
                    val h = c.history
                    item {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            ScreenHeader(c.name, onBack = { nav.popBackStack() }, subtitle = listOf(c.locality, c.country).filter { it.isNotBlank() }.joinToString(", ")) {
                                Flag(Flags.country(c.country), 28.sp)
                            }
                        }
                    }
                    c.race?.let { r ->
                        item {
                            CardBox {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Column(Modifier.weight(1f)) {
                                        Text(Fmt.raceName(r.name) + " ${r.season}", fontSize = 14.sp, fontWeight = FontWeight.Bold, color = p.text)
                                        Mono(Fmt.range(r.firstDay, r.lastDay), 12.sp, FontWeight.Normal, p.muted)
                                    }
                                    if (r.isFinished()) OutlinedButton(onClick = { nav.navigate(Routes.race(r.season, r.round)) }) { Text(stringResource(R.string.see_result), fontSize = 12.sp) }
                                }
                                if (!r.isFinished()) Column(Modifier.padding(top = 8.dp)) {
                                    val now = java.time.Instant.now()
                                    r.sessions.forEach { s ->
                                        val done = now.isAfter(s.end)
                                        val strike = if (done) androidx.compose.ui.text.style.TextDecoration.LineThrough else null
                                        Row(Modifier.fillMaxWidth().padding(vertical = 3.dp)) {
                                            Text(com.racepocket.app.ui.sessionName(s.kind), fontSize = 12.sp, color = if (done) p.muted else p.text, modifier = Modifier.weight(1f), textDecoration = strike)
                                            Mono(Fmt.weekdayTime(s.start), 12.sp, FontWeight.Normal, p.muted, decoration = strike)
                                        }
                                    }
                                }
                            }
                        }
                    }
                    track?.let { t ->
                        item {
                            CardBox(padding = 8.dp) {
                                TrackMap(t, Tracks.zonesFor(t.name))
                                Box(Modifier.padding(6.dp)) { TrackLegend(Tracks.zonesFor(t.name) != null, t.exactSectors) }
                                Muted(stringResource(R.string.map_from, t.year), 10.sp, Modifier.padding(start = 6.dp))
                            }
                        }
                    }
                    item {
                        val tiles = listOf(
                            Triple(stringResource(R.string.length), Static.LENGTH_KM[id]?.let { String.format("%.3f km", it) } ?: "—", ""),
                            Triple(stringResource(R.string.corners), (track?.corners?.size?.takeIf { it > 0 } ?: Static.TRACK_TURNS[id])?.toString() ?: "—", ""),
                            Triple(stringResource(R.string.laps), h.lastWinner?.laps?.takeIf { it > 0 }?.toString() ?: "—", ""),
                            Triple(stringResource(R.string.first_gp), h.firstGp?.toString() ?: "—", ""),
                            Triple(stringResource(R.string.gps_held), h.gpsHeld.toString(), ""),
                            Triple(stringResource(R.string.last_winner), h.lastWinner?.let { "${it.driver.code} (${it.season})" } ?: "—", "")
                        )
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            tiles.chunked(3).forEach { row ->
                                Row(Modifier.height(IntrinsicSize.Min), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    row.forEach { (l, v, s) -> StatTile(l, v, Modifier.weight(1f).fillMaxHeight(), s) }
                                }
                            }
                        }
                    }
                    item {
                        Row(Modifier.height(IntrinsicSize.Min), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            RecordTile(stringResource(R.string.record_quali), h.qualiRecord, Modifier.weight(1f).fillMaxHeight())
                            RecordTile(stringResource(R.string.record_race), h.raceRecord, Modifier.weight(1f).fillMaxHeight())
                        }
                    }
                    Static.DEMANDS[id]?.let { dem -> item { DemandsCard(dem) } }
                    item { TopList(stringResource(R.string.most_wins_here), h.top(h.winners)) }
                    if (h.poles.isNotEmpty()) item { TopList(stringResource(R.string.most_poles_here), h.top(h.poles)) }
                    item { HistoryTable(h) { s, rd -> nav.navigate(Routes.race(s, rd)) } }
                }
            }
        }
    }
}

@Composable
private fun RecordTile(label: String, e: HistEntry?, modifier: Modifier) {
    val p = pal()
    Column(modifier.clip(RoundedCornerShape(12.dp)).background(p.card).padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Muted(label.uppercase(), 10.sp)
        Mono(e?.time ?: "—", 18.sp, FontWeight.Bold, p.purple)
        if (e != null) Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(5.dp)) {
            Flag(e.driver.flag, 12.sp)
            Text(e.driver.name, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = p.text, maxLines = 1, softWrap = false, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
            Text("(${e.season})", fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = p.text, maxLines = 1, softWrap = false)
        }
    }
}

@Composable
private fun DemandsCard(d: Map<String, Int?>) {
    val p = pal()
    val names = mapOf(
        "df" to R.string.dem_df, "br" to R.string.dem_br, "tr" to R.string.dem_tr, "la" to R.string.dem_la,
        "ab" to R.string.dem_ab, "st" to R.string.dem_st, "ev" to R.string.dem_ev, "gr" to R.string.dem_gr
    )
    CardBox {
        Text(stringResource(R.string.demands), fontSize = 15.sp, fontWeight = FontWeight.Bold, color = p.text)
        Column(Modifier.padding(top = 8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Static.DEMAND_KEYS.filter { d[it] != null }.forEach { k ->
                val v = d[k] ?: 0
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(stringResource(names.getValue(k)), fontSize = 12.sp, color = p.text, modifier = Modifier.weight(1f))
                    Row(horizontalArrangement = Arrangement.spacedBy(3.dp)) {
                        (1..5).forEach { i -> Box(Modifier.width(18.dp).height(6.dp).clip(RoundedCornerShape(3.dp)).background(if (i <= v) p.accent else p.card2)) }
                    }
                }
            }
        }
        Muted(stringResource(R.string.demands_note), 10.sp, Modifier.padding(top = 8.dp))
    }
}

@Composable
private fun TopList(title: String, list: List<Pair<HistEntry, Int>>) {
    val p = pal()
    val max = list.maxOfOrNull { it.second }?.coerceAtLeast(1) ?: 1
    CardBox {
        Text(title, fontSize = 15.sp, fontWeight = FontWeight.Bold, color = p.text)
        Column(Modifier.padding(top = 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            list.forEach { (e, n) ->
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Flag(e.driver.flag, 14.sp)
                    Text(e.driver.name, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = p.text, modifier = Modifier.weight(1f), maxLines = 1)
                    Box(Modifier.width(110.dp).height(8.dp).clip(RoundedCornerShape(4.dp)).background(p.card2)) {
                        Box(Modifier.fillMaxWidth(n.toFloat() / max).height(8.dp).background(p.accent))
                    }
                    Mono("$n", 13.sp, FontWeight.Bold, modifier = Modifier.width(22.dp), align = TextAlign.End)
                }
            }
        }
    }
}

@Composable
private fun HistoryTable(h: CircuitHistory, onOpen: (Int, Int) -> Unit) {
    val p = pal()
    val poles = h.poles.associateBy { it.season }
    val fastest = h.fastest.associateBy { it.season }
    CardBox(padding = 0.dp) {
        Text(stringResource(R.string.winners_poles), fontSize = 15.sp, fontWeight = FontWeight.Bold, color = p.text, modifier = Modifier.padding(14.dp))
        Row(Modifier.padding(horizontal = 14.dp, vertical = 4.dp)) {
            Muted(stringResource(R.string.year), 10.sp, Modifier.width(44.dp))
            Muted(stringResource(R.string.winner), 10.sp, Modifier.weight(1f))
            Muted(stringResource(R.string.pole), 10.sp, Modifier.weight(1f))
            Muted(stringResource(R.string.fastest_lap), 10.sp, Modifier.width(72.dp))
        }
        h.winners.sortedByDescending { it.season * 100 + it.round }.take(40).forEach { w ->
            Divider()
            Row(
                Modifier.fillMaxWidth().clickable { onOpen(w.season, w.round) }.padding(horizontal = 14.dp, vertical = 9.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Mono("${w.season}", 12.sp, FontWeight.Normal, p.muted, Modifier.width(44.dp))
                Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                    TeamBar(teamColor(w.team.color), 16.dp); Flag(w.driver.flag, 11.sp)
                    Text(w.driver.code, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = p.text)
                }
                Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                    poles[w.season]?.let { pe ->
                        TeamBar(teamColor(pe.team.color), 16.dp); Flag(pe.driver.flag, 11.sp)
                        Text(pe.driver.code, fontSize = 12.sp, color = p.text)
                    }
                }
                Mono(fastest[w.season]?.time ?: "", 11.sp, FontWeight.Normal, modifier = Modifier.width(72.dp))
            }
        }
    }
}

// ===================== RACE DETAIL =====================

data class RaceData(
    val race: Race?,
    val results: List<ResultRow>,
    val quali: List<QualiRow>,
    val sprint: List<ResultRow>,
    val pits: List<PitRow>
)

class RaceVM(private val year: Int, private val round: Int) : BaseVM() {
    val data = MutableStateFlow<Load<RaceData>>(Load.Loading)
    val track = MutableStateFlow<TrackGeometry?>(null)
    val photos = MutableStateFlow<Map<String, String>>(emptyMap())
    val practice = MutableStateFlow<Load<List<Pair<String, List<Pair<OpenF1.SessionResult, OpenF1.Driver?>>>>>>(Load.Loading)
    private var practiceStarted = false

    init { refresh() }

    fun refresh() {
        load(data) {
            val (raceFromResults, results) = Jolpica.raceResults(year, round)
            val race = raceFromResults ?: Jolpica.calendar(year).firstOrNull { it.round == round }
            val quali = runCatching { Jolpica.qualifying(year, round) }.getOrDefault(emptyList())
            val sprint = if (race?.isSprint == true) runCatching { Jolpica.sprintResults(year, round) }.getOrDefault(emptyList()) else emptyList()
            val pits = runCatching { Jolpica.pitStops(year, round) }.getOrDefault(emptyList())
            RaceData(race, results, quali, sprint, pits)
        }.invokeOnCompletion {
            val r = data.value.valueOrNull?.race ?: return@invokeOnCompletion
            viewModelScope.launch { if (year >= 2018) track.value = runCatching { Tracks.get(r.name, minOf(year, Year.now().value)) }.getOrNull() }
            viewModelScope.launch {
                if (year >= 2018) photos.value = runCatching { com.racepocket.app.data.TI.drivers(year, r.name, "Race").mapNotNull { d -> d.photo?.let { d.code to it } }.toMap() }.getOrDefault(emptyMap())
            }
        }
    }

    fun loadPractice() {
        if (practiceStarted) return
        practiceStarted = true
        load(practice) {
            val r = data.value.valueOrNull?.race ?: return@load emptyList()
            if (year < 2023) return@load emptyList()
            val meeting = OpenF1.meetings(year).minByOrNull { m -> abs((m.start?.toEpochMilli() ?: 0L) - (r.sessions.firstOrNull()?.start?.toEpochMilli() ?: 0L)) }
                ?: return@load emptyList()
            val sessions = OpenF1.sessions(meeting.key).filter { it.name.startsWith("Practice") || it.name == "Sprint Qualifying" || it.name == "Sprint Shootout" }
            sessions.map { s ->
                val drivers = OpenF1.drivers(s.key).associateBy { it.number }
                s.name to OpenF1.sessionResult(s.key).map { it to drivers[it.driver] }
            }
        }
    }
}

@Composable
fun RaceScreen(nav: NavHostController, year: Int, round: Int, settings: AppSettings, initialTab: Int = 0) {
    val vm: RaceVM = viewModel(key = "race-$year-$round") { RaceVM(year, round) }
    val data by vm.data.collectAsState()
    val track by vm.track.collectAsState()
    val practice by vm.practice.collectAsState()
    var tab by rememberSaveable { mutableIntStateOf(initialTab) }
    // opened straight on the practice tab (from a notification): load it once the race is known
    val raceLoaded = data is Load.Ok
    androidx.compose.runtime.LaunchedEffect(raceLoaded) { if (raceLoaded && tab == 4) vm.loadPractice() }
    val p = pal()
    PullRefresh(vm, { vm.refresh() }) {
        LazyColumn(contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            when (val d = data) {
                is Load.Loading -> {
                    item { ScreenHeader("", onBack = { nav.popBackStack() }) }
                    item { LoadingBox() }
                }
                is Load.Err -> {
                    item { ScreenHeader("", onBack = { nav.popBackStack() }) }
                    item { ErrorBox(d.error) { vm.refresh() } }
                }
                is Load.Ok -> {
                    val rd = d.value
                    val r = rd.race
                    item {
                        ScreenHeader(
                            Fmt.raceName(r?.name ?: ""), onBack = { nav.popBackStack() },
                            subtitle = r?.let { "${stringResource(R.string.round_n, it.round).uppercase()} · ${Fmt.range(it.firstDay, it.lastDay)} · $year" }
                        ) { Flag(r?.flag ?: "", 28.sp) }
                    }
                    track?.let { t ->
                        item {
                            CardBox(padding = 8.dp, onClick = { r?.let { nav.navigate(Routes.circuit(it.circuitId, it.season, it.round)) } }) {
                                TrackMap(t, Tracks.zonesFor(t.name))
                                Row(Modifier.padding(6.dp), verticalAlignment = Alignment.CenterVertically) {
                                    Muted("${r?.circuitName ?: ""} · ${Static.LENGTH_KM[r?.circuitId]?.let { String.format("%.3f km", it) } ?: ""}", 12.sp, Modifier.weight(1f))
                                    Text(stringResource(R.string.circuit) + " ›", fontSize = 12.sp, fontWeight = FontWeight.Bold, color = p.accentText)
                                }
                            }
                        }
                    }
                    if (rd.results.isNotEmpty()) item { Highlights(rd) }
                    item {
                        val tabs = buildList {
                            add(0 to stringResource(R.string.tab_race)); add(1 to stringResource(R.string.tab_quali))
                            if (rd.sprint.isNotEmpty()) add(2 to stringResource(R.string.s_sprint))
                            add(3 to stringResource(R.string.tab_grid))
                            if (year >= 2023) add(4 to stringResource(R.string.tab_practice))
                            if (rd.pits.isNotEmpty()) add(5 to stringResource(R.string.tab_pits))
                        }
                        PillRow(tabs.map { it.second }, tabs.indexOfFirst { it.first == tab }.coerceAtLeast(0)) { i -> tab = tabs[i].first; if (tab == 4) vm.loadPractice() }
                    }
                    when (tab) {
                        0 -> compactItem { if (rd.results.isEmpty()) EmptyBox(stringResource(R.string.results_soon)) else ResultsTable(rd.results, true) }
                        1 -> compactItem { if (rd.quali.isEmpty()) EmptyBox(stringResource(R.string.results_soon)) else QualiTable(rd.quali) }
                        2 -> compactItem { ResultsTable(rd.sprint, false) }
                        3 -> compactItem {
                            val grid = rd.results.filter { it.grid > 0 }.sortedBy { it.grid }
                            if (grid.isEmpty()) EmptyBox(stringResource(R.string.results_soon)) else CardBox(padding = 0.dp) {
                                grid.forEach { g -> Divider(); RankRow("${g.grid}", teamColor(g.team.color), g.driver.flag, g.driver.name, g.team.name, "") }
                            }
                        }
                        4 -> compactItem {
                            LoadBox(practice, onRetry = null) { list ->
                                if (list.isEmpty()) EmptyBox(stringResource(R.string.no_session))
                                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                                    list.forEach { (name, rows) ->
                                        CardBox(padding = 0.dp) {
                                            Text(com.racepocket.app.ui.openF1SessionName(name), fontSize = 15.sp, fontWeight = FontWeight.Bold, color = p.text, modifier = Modifier.padding(14.dp))
                                            rows.forEach { (res, drv) ->
                                                Divider()
                                                RankRow(
                                                    res.position?.toString() ?: "-", com.racepocket.app.ui.hexColor(drv?.color), Flags.ioc(drv?.countryCode),
                                                    drv?.fullName ?: "#${res.driver}", drv?.team, fmtLap(res.duration), extra = res.gap.takeIf { it.isNotBlank() }
                                                )
                                            }
                                        }
                                    }
                                }
                            }
                        }
                        5 -> compactItem {
                            val names = rd.results.associate { it.driver.id to it.driver }
                            CardBox(padding = 0.dp) {
                                rd.pits.sortedBy { it.duration.toDoubleOrNull() ?: 999.0 }.forEach { pit ->
                                    val drv = names[pit.driverId]
                                    Divider()
                                    Row(Modifier.padding(horizontal = 14.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                        Flag(drv?.flag ?: "", 14.sp)
                                        Text(drv?.code ?: pit.driverId, fontSize = 13.sp, fontWeight = FontWeight.Bold, color = p.text, modifier = Modifier.width(44.dp))
                                        Muted(stringResource(R.string.lap_n_stop_n, pit.lap, pit.stop), 12.sp, Modifier.weight(1f))
                                        Mono(pit.duration + " s", 12.sp, FontWeight.Bold)
                                    }
                                }
                            }
                        }
                    }
                    item {
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            listOf(R.string.nav_strategy to Routes.STRATEGY, R.string.nav_telemetry to Routes.TELEMETRY, R.string.nav_radio to Routes.RADIO, R.string.replay_title to Routes.REPLAY).forEach { (label, route) ->
                                OutlinedButton(onClick = { nav.navigate(route) }, modifier = Modifier.weight(1f), contentPadding = PaddingValues(horizontal = 4.dp)) {
                                    Text(stringResource(label), fontSize = 11.sp, maxLines = 1)
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun Highlights(rd: RaceData) {
    val winner = rd.results.firstOrNull()
    val pole = rd.quali.firstOrNull()
    val fl = rd.results.firstOrNull { it.fastestRank == 1 }
    val gainer = rd.results.filter { it.grid > 0 && it.classified }.maxByOrNull { it.grid - (it.position ?: 0) }
    val tiles = listOfNotNull(
        winner?.let { Triple(stringResource(R.string.winner), it.driver, it.time ?: "") },
        pole?.let { Triple(stringResource(R.string.pole), it.driver, it.best ?: "") },
        fl?.let { Triple(stringResource(R.string.fastest_lap), it.driver, (it.fastestTime ?: "") + (it.fastestLap?.let { l -> " · V$l" } ?: "")) },
        gainer?.takeIf { it.grid - (it.position ?: 0) > 0 }?.let { Triple(stringResource(R.string.most_gained), it.driver, "P${it.grid} → P${it.position}") }
    )
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        tiles.chunked(2).forEach { row ->
            Row(Modifier.height(IntrinsicSize.Min), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                row.forEach { (label, drv, sub) -> StatTile(label.uppercase(), drv.last, Modifier.weight(1f).fillMaxHeight(), sub, drv.flag) }
                if (row.size == 1) Box(Modifier.weight(1f))
            }
        }
    }
}

@Composable
fun ResultsTable(rows: List<ResultRow>, showFastest: Boolean) {
    val p = pal()
    CardBox(padding = 0.dp) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp)) {
            Muted(stringResource(R.string.pos_short), 10.sp, Modifier.width(24.dp))
            Muted(stringResource(R.string.driver), 10.sp, Modifier.weight(1f).padding(start = 30.dp))
            Muted(stringResource(R.string.grid), 10.sp, Modifier.width(36.dp))
            Muted(stringResource(R.string.time), 10.sp, Modifier.width(84.dp))
            Muted(stringResource(R.string.pts), 10.sp, Modifier.width(26.dp))
        }
        rows.forEach { r ->
            Divider()
            Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 7.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(r.posText, fontSize = 13.sp, fontWeight = FontWeight.Bold, color = p.text, modifier = Modifier.width(24.dp), textAlign = TextAlign.End)
                Row(Modifier.width(30.dp), horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
                    TeamBar(teamColor(r.team.color)); Box(Modifier.width(4.dp)); Flag(r.driver.flag, 12.sp)
                }
                Column(Modifier.weight(1f)) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                        Text(r.driver.name, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = p.text, maxLines = 1)
                        if (showFastest && r.fastestRank == 1) Text(
                            "VMR", fontSize = 9.sp, fontWeight = FontWeight.ExtraBold, color = p.bg,
                            modifier = Modifier.clip(RoundedCornerShape(4.dp)).background(p.purple).padding(horizontal = 4.dp, vertical = 1.dp)
                        )
                    }
                    Muted(r.team.name, 10.sp)
                }
                Column(Modifier.width(36.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("${r.grid}", fontSize = 11.sp, color = p.text)
                    val delta = if (r.grid > 0 && r.position != null) r.grid - r.position!! else 0
                    if (delta != 0) Text(if (delta > 0) "▲$delta" else "▼${-delta}", fontSize = 9.sp, color = if (delta > 0) p.green else p.accentText)
                }
                Mono(resultTime(r), 11.sp, FontWeight.Normal, modifier = Modifier.width(84.dp), align = TextAlign.End)
                Mono(Fmt.points(r.points).takeIf { it != "0" } ?: "", 12.sp, FontWeight.Bold, modifier = Modifier.width(26.dp), align = TextAlign.End)
            }
        }
    }
}

@Composable
private fun QualiTable(rows: List<QualiRow>) {
    val p = pal()
    CardBox(padding = 0.dp) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp)) {
            Muted(stringResource(R.string.pos_short), 10.sp, Modifier.width(24.dp))
            Muted(stringResource(R.string.driver), 10.sp, Modifier.weight(1f).padding(start = 8.dp))
            Muted("Q1", 10.sp, Modifier.width(66.dp)); Muted("Q2", 10.sp, Modifier.width(66.dp)); Muted("Q3", 10.sp, Modifier.width(66.dp))
        }
        rows.forEach { q ->
            Divider()
            Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 7.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(q.pos, fontSize = 13.sp, fontWeight = FontWeight.Bold, color = p.text, modifier = Modifier.width(24.dp), textAlign = TextAlign.End)
                Row(Modifier.weight(1f).padding(start = 8.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                    TeamBar(teamColor(q.team.color)); Flag(q.driver.flag, 12.sp)
                    Text(q.driver.code, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = p.text)
                }
                Mono(q.q1 ?: "", 11.sp, FontWeight.Normal, p.muted, Modifier.width(66.dp))
                Mono(q.q2 ?: "", 11.sp, FontWeight.Normal, p.muted, Modifier.width(66.dp))
                Mono(q.q3 ?: "", 11.sp, FontWeight.Bold, if (q.pos == "1") p.purple else p.text, Modifier.width(66.dp))
            }
        }
    }
}

/** A list item whose texts use the tight table spacing (results, qualifying, grid, practice, pits). */
private fun androidx.compose.foundation.lazy.LazyListScope.compactItem(content: @Composable () -> Unit) =
    item { com.racepocket.app.ui.CompactText(content) }
