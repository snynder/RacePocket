package com.racepocket.app.ui.screens

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import com.racepocket.app.ui.PullRefresh
import com.racepocket.app.ui.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavHostController
import com.racepocket.app.R
import com.racepocket.app.data.AppSettings
import com.racepocket.app.data.Flags
import com.racepocket.app.data.Jolpica
import com.racepocket.app.data.Keys
import com.racepocket.app.data.OpenF1
import com.racepocket.app.data.Race
import com.racepocket.app.data.Repo
import com.racepocket.app.data.TrackGeometry
import com.racepocket.app.data.Tracks
import com.racepocket.app.data.fmtGap
import com.racepocket.app.data.fmtLap
import com.racepocket.app.data.live.LiveParser
import com.racepocket.app.data.live.LiveTimingClient
import com.racepocket.app.data.live.LiveView
import com.racepocket.app.data.live.RaceMsg
import com.racepocket.app.data.live.TowerRow
import com.racepocket.app.ui.BaseVM
import com.racepocket.app.ui.CardBox
import com.racepocket.app.ui.ErrorBox
import com.racepocket.app.ui.Flag
import com.racepocket.app.ui.FlagPill
import com.racepocket.app.ui.Fmt
import com.racepocket.app.ui.Load
import com.racepocket.app.ui.LoadingBox
import com.racepocket.app.ui.Mono
import com.racepocket.app.ui.Muted
import com.racepocket.app.ui.Picker
import com.racepocket.app.ui.PillRow
import com.racepocket.app.ui.RaceControlList
import com.racepocket.app.ui.Routes
import com.racepocket.app.ui.ScreenHeader
import com.racepocket.app.ui.TowerHeader
import com.racepocket.app.ui.TowerLine
import com.racepocket.app.ui.TrackLegend
import com.racepocket.app.ui.TrackMap
import com.racepocket.app.ui.openF1SessionName
import com.racepocket.app.ui.pal
import com.racepocket.app.ui.sessionName
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.Year
import java.time.ZoneId
import java.time.format.DateTimeFormatter

// ===================== LIVE =====================

class LiveVM : BaseVM() {
    private val client = LiveTimingClient(viewModelScope)
    val status = client.status
    val view: StateFlow<LiveView> = client.data.map { LiveParser.parse(it) }.flowOn(Dispatchers.Default)
        .stateIn(viewModelScope, SharingStarted.Eagerly, LiveView())
    val track = MutableStateFlow<TrackGeometry?>(null)
    val waited = MutableStateFlow(false)
    val calendar = MutableStateFlow<List<Race>>(emptyList())
    private var trackFor: String? = null
    private var waitJob: Job? = null

    init {
        viewModelScope.launch { calendar.value = runCatching { Jolpica.calendar(Year.now().value) }.getOrDefault(emptyList()) }
        viewModelScope.launch {
            view.collect { v ->
                if (v.meeting.isNotBlank() && v.meeting != trackFor) {
                    trackFor = v.meeting
                    track.value = runCatching { Tracks.get(v.meeting, v.year ?: Year.now().value) }.getOrNull()
                }
            }
        }
    }

    fun start() {
        client.connect()
        waitJob?.cancel()
        waited.value = false
        waitJob = viewModelScope.launch { delay(9000); waited.value = true }
    }

    fun stop() = client.disconnect()
    fun retry() { stop(); start() }
}

@Composable
fun LiveScreen(nav: NavHostController, settings: AppSettings) {
    val vm: LiveVM = viewModel()
    DisposableEffect(Unit) {
        vm.start()
        onDispose { vm.stop() }
    }
    val status by vm.status.collectAsState()
    val v by vm.view.collectAsState()
    val track by vm.track.collectAsState()
    val waited by vm.waited.collectAsState()
    val calendar by vm.calendar.collectAsState()
    val p = pal()

    PullRefresh(vm, { vm.retry() }) {
        LazyColumn(contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            item { ScreenHeader(stringResource(R.string.live_title), onBack = { nav.popBackStack() }) }
            val s = status
            when {
                s is LiveTimingClient.Status.Failed -> item {
                    CardBox {
                        Text(stringResource(R.string.live_feed_fail), color = p.text, fontSize = 14.sp)
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 10.dp)) {
                            OutlinedButton(onClick = { vm.retry() }) { Text(stringResource(R.string.retry)) }
                            OutlinedButton(onClick = { nav.navigate(Routes.REPLAY) }) { Text(stringResource(R.string.open_replay)) }
                        }
                    }
                }
                !v.hasTiming && !waited -> item {
                    Column {
                        LoadingBox()
                        Muted(stringResource(R.string.live_connecting), 12.sp, Modifier.align(Alignment.CenterHorizontally))
                    }
                }
                !v.hasTiming -> item { NoSessionCard(calendar) { nav.navigate(Routes.REPLAY) } }
                else -> {
                    item { LiveHeader(v) }
                    track?.let { t -> item { LiveMapCard(t, settings.liveMapCollapsed) } }
                    item {
                        CardBox(padding = 0.dp) {
                            TowerHeader()
                            v.rows.forEach { TowerLine(it) }
                        }
                    }
                    item { RaceControlList(v.messages) }
                    item { OutlinedButton(onClick = { nav.navigate(Routes.REPLAY) }) { Text(stringResource(R.string.open_replay)) } }
                }
            }
        }
    }
}

@Composable
private fun LiveHeader(v: LiveView) {
    val p = pal()
    CardBox {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            val on = v.status == "Started"
            Text(
                when {
                    on -> "● " + stringResource(R.string.live_badge)
                    v.partBreak -> stringResource(R.string.quali_part_end, v.partLabel ?: "")
                    else -> statusLabel(v.status)
                },
                fontSize = 11.sp, fontWeight = FontWeight.Bold, color = if (on) p.accentText else p.muted,
                modifier = Modifier.clip(RoundedCornerShape(6.dp)).background(if (on) p.liveBg else p.card2).padding(horizontal = 8.dp, vertical = 3.dp)
            )
            if (!v.partBreak) v.partLabel?.let { QualiPartPill(it, active = !v.over) }
            Flag(Flags.ioc(v.countryCode), 14.sp)
            // no strike-through here: that is only for the Home banner and the weekend list
            Text(
                "${Fmt.raceName(v.meeting)} · ${openF1SessionName(v.session)}", fontSize = 14.sp, fontWeight = FontWeight.SemiBold, maxLines = 2,
                color = p.text
            )
        }
        // the flag gets its own line between the title and the lap count, so the source fits on one line
        Box(Modifier.padding(top = 8.dp)) { FlagPill(v.trackStatus) }
        Row(Modifier.padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            if (v.lap != null) Mono(stringResource(R.string.lap_of, v.lap, v.totalLaps ?: 0), 13.sp, FontWeight.Bold)
            else sessionClock(v)?.let { Mono(it, 13.sp, FontWeight.Bold) }
            Muted(stringResource(R.string.live_source_f1), 10.sp)
        }
    }
}

/**
 * Time left in the session. F1 sends Remaining only when the clock starts, stops or jumps, with
 * Extrapolating = true while it runs, so the countdown between those readings is done here.
 */
@Composable
private fun sessionClock(v: LiveView): String? {
    var now by remember { mutableStateOf(Instant.now()) }
    LaunchedEffect(v.clockRunning, v.clockUtc) {
        if (!v.clockRunning) return@LaunchedEffect
        while (true) {
            now = Instant.now()
            delay(1000)
        }
    }
    val base = v.remaining ?: return null
    val parts = base.split(":").map { it.toLongOrNull() ?: return base }
    if (parts.size != 3) return base
    val utc = v.clockUtc
    if (!v.clockRunning || utc == null) return base
    val left = (parts[0] * 3600 + parts[1] * 60 + parts[2] - java.time.Duration.between(utc, now).seconds).coerceAtLeast(0)
    return "%02d:%02d:%02d".format(left / 3600, left % 3600 / 60, left % 60)
}

/** Track map that can be folded down to a single line; the choice is remembered. */
@Composable
private fun LiveMapCard(t: TrackGeometry, collapsed: Boolean) {
    val p = pal()
    val scope = rememberCoroutineScope()
    CardBox(padding = 8.dp) {
        Row(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp))
                .clickable { scope.launch { Repo.settings.edit { it[Keys.liveMapCollapsed] = !collapsed } } }
                .padding(horizontal = 6.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(stringResource(R.string.circuit), fontSize = 12.sp, fontWeight = FontWeight.Bold, color = p.muted, modifier = Modifier.weight(1f))
            Icon(
                if (collapsed) Icons.Filled.KeyboardArrowDown else Icons.Filled.KeyboardArrowUp,
                contentDescription = stringResource(if (collapsed) R.string.expand else R.string.collapse), tint = p.muted
            )
        }
        AnimatedVisibility(visible = !collapsed) {
            Column {
                TrackMap(t, Tracks.zonesFor(t.name))
                Box(Modifier.padding(6.dp)) { TrackLegend(Tracks.zonesFor(t.name) != null, t.exactSectors) }
            }
        }
    }
}

/** Q1 / Q2 / Q3 (or SQ1..SQ3) tag. */
@Composable
fun QualiPartPill(label: String, active: Boolean = true) {
    val p = pal()
    Text(
        label, fontSize = 11.sp, fontWeight = FontWeight.ExtraBold, color = if (active) Color.White else p.muted,
        modifier = Modifier.clip(RoundedCornerShape(6.dp)).background(if (active) p.accent else p.card2).padding(horizontal = 7.dp, vertical = 3.dp)
    )
}

@Composable
private fun statusLabel(s: String): String = when (s) {
    "Finished", "Finalised", "Ends" -> stringResource(R.string.session_finished)
    "Aborted" -> stringResource(R.string.session_aborted)
    else -> stringResource(R.string.session_waiting)
}

@Composable
private fun NoSessionCard(calendar: List<Race>, onReplay: () -> Unit) {
    val p = pal()
    val now = Instant.now()
    val next = calendar.flatMap { r -> r.sessions.map { r to it } }.firstOrNull { it.second.start.isAfter(now) }
    CardBox {
        Text(stringResource(R.string.live_none), color = p.text, fontSize = 14.sp)
        if (next != null) {
            Row(Modifier.padding(top = 10.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Flag(next.first.flag, 18.sp)
                Column {
                    Muted(stringResource(R.string.next_session), 11.sp)
                    Text("${sessionName(next.second.kind)} · ${Fmt.raceName(next.first.name)}", fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = p.text)
                    Mono(Fmt.weekdayTime(next.second.start), 12.sp, FontWeight.Normal, p.muted)
                }
            }
        }
        OutlinedButton(onClick = onReplay, modifier = Modifier.padding(top = 10.dp)) { Text(stringResource(R.string.open_replay)) }
    }
}

// ===================== SESSION PICKER (OpenF1) =====================

class SessionSelector(private val scope: CoroutineScope, private val kinds: Set<String>? = null, private val prefer: String = "Race") {
    val years = (Year.now().value downTo 2023).toList()
    val year = MutableStateFlow(years.first())
    val meetings = MutableStateFlow<Load<List<OpenF1.Meeting>>>(Load.Loading)
    val meeting = MutableStateFlow<OpenF1.Meeting?>(null)
    val sessions = MutableStateFlow<List<OpenF1.Session>>(emptyList())
    val session = MutableStateFlow<OpenF1.Session?>(null)
    var onSession: (OpenF1.Session) -> Unit = {}
    private var job: Job? = null

    fun start() { if (meetings.value !is Load.Ok) selectYear(year.value) }

    fun selectYear(y: Int) {
        year.value = y
        job?.cancel()
        job = scope.launch {
            meetings.value = Load.Loading
            try {
                val now = Instant.now()
                val ms = OpenF1.meetings(y).filter { (it.start ?: now).isBefore(now) }
                meetings.value = Load.Ok(ms)
                ms.lastOrNull()?.let { selectMeeting(it) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                meetings.value = Load.Err(e)
            }
        }
    }

    fun selectMeeting(m: OpenF1.Meeting) {
        meeting.value = m
        sessions.value = emptyList()
        session.value = null
        scope.launch {
            val now = Instant.now()
            val ss = runCatching { OpenF1.sessions(m.key) }.getOrDefault(emptyList())
                .filter { (it.end ?: now).isBefore(now) && (kinds == null || it.name in kinds) }
            sessions.value = ss
            (ss.lastOrNull { it.name == prefer } ?: ss.lastOrNull())?.let { selectSession(it) }
        }
    }

    fun selectSession(s: OpenF1.Session) {
        session.value = s
        onSession(s)
    }
}

@Composable
fun SessionSelectorView(sel: SessionSelector, onRetry: () -> Unit) {
    val year by sel.year.collectAsState()
    val meetings by sel.meetings.collectAsState()
    val meeting by sel.meeting.collectAsState()
    val sessions by sel.sessions.collectAsState()
    val session by sel.session.collectAsState()
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Picker(stringResource(R.string.year), sel.years, year, { it.toString() }, Modifier.weight(0.8f)) { sel.selectYear(it) }
            Picker(stringResource(R.string.grand_prix), (meetings as? Load.Ok<List<OpenF1.Meeting>>)?.value ?: emptyList(), meeting, { Fmt.raceName(it.name) }, Modifier.weight(1.6f)) { sel.selectMeeting(it) }
        }
        Picker(stringResource(R.string.session), sessions, session, { it.name }, Modifier.fillMaxWidth()) { sel.selectSession(it) }
        val m = meetings
        if (m is Load.Err) ErrorBox(m.error, onRetry)
    }
}

// ===================== REPLAY =====================

class ReplayData(
    val start: Long,
    val end: Long,
    val drivers: Map<Int, OpenF1.Driver>,
    val laps: Map<Int, List<OpenF1.Lap>>,
    val positions: Map<Int, List<OpenF1.Pos>>,
    val intervals: Map<Int, List<OpenF1.Interval>>,
    val stints: Map<Int, List<OpenF1.Stint>>,
    val rc: List<OpenF1.RaceControl>,
    val totalLaps: Int,
    val isRace: Boolean
) {
    private val fmt = DateTimeFormatter.ofPattern("HH:mm:ss").withZone(ZoneId.systemDefault())

    private fun <T> lastBefore(list: List<T>?, t: Long, time: (T) -> Long): T? {
        if (list.isNullOrEmpty()) return null
        var lo = 0
        var hi = list.size - 1
        var ans = -1
        while (lo <= hi) {
            val mid = (lo + hi) / 2
            if (time(list[mid]) <= t) { ans = mid; lo = mid + 1 } else hi = mid - 1
        }
        return if (ans >= 0) list[ans] else null
    }

    data class Frame(val rows: List<TowerRow>, val messages: List<RaceMsg>, val lap: Int, val elapsed: Long)

    fun frame(t: Long): Frame {
        val nums = (drivers.keys + positions.keys).distinct()
        val bestOverall = laps.values.flatten().filter { (it.start ?: Long.MAX_VALUE) + ((it.duration ?: 0.0) * 1000).toLong() <= t && it.duration != null }
            .minByOrNull { it.duration!! }?.driver
        val raw = nums.map { n ->
            val d = drivers[n]
            val done = laps[n].orEmpty().filter { l -> l.start != null && l.duration != null && l.start + (l.duration * 1000).toLong() <= t }
            val lastLap = done.maxByOrNull { it.lap }
            val best = done.minByOrNull { it.duration!! }
            val cur = (lastLap?.lap ?: 0) + 1
            val iv = lastBefore(intervals[n], t) { it.t }
            val stint = stints[n].orEmpty().lastOrNull { it.lapStart <= cur }
            val pos = lastBefore(positions[n], t) { it.t }?.position ?: 99
            TowerRow(
                position = pos, number = "$n", tla = d?.tla ?: "$n", name = d?.lastName ?: "", teamColor = d?.color ?: "8A929C",
                gap = iv?.gap ?: "", interval = iv?.interval ?: "",
                last = fmtLap(lastLap?.duration).takeIf { lastLap != null } ?: "",
                best = fmtLap(best?.duration).takeIf { best != null } ?: "",
                lastPersonalBest = lastLap != null && lastLap == best, bestOverall = bestOverall == n,
                compound = stint?.compound, tyreAge = stint?.let { it.ageAtStart + (cur - it.lapStart) },
                stops = if (isRace) (stints[n].orEmpty().count { it.lapStart <= cur } - 1).coerceAtLeast(0) else null,
                inPit = false, out = false
            ) to best?.duration
        }.sortedBy { it.first.position }
        val leaderBest = raw.mapNotNull { it.second }.minOrNull()
        val rows = if (isRace) raw.map { it.first } else {
            // practice and qualifying: gaps to the fastest lap so far
            var prev: Double? = null
            raw.sortedBy { it.second ?: Double.MAX_VALUE }.mapIndexed { i, (r, b) ->
                val row = r.copy(
                    position = if (b == null) r.position else i + 1,
                    gap = if (b != null && leaderBest != null && i > 0) fmtGap(b - leaderBest) else "",
                    interval = if (b != null && prev != null) fmtGap(b - prev!!) else ""
                )
                prev = b
                row
            }.sortedBy { it.position }
        }
        val msgs = rc.filter { it.t <= t }.takeLast(60).reversed().map { RaceMsg(fmt.format(Instant.ofEpochMilli(it.t)), it.lap, it.message, it.flag) }
        val lap = rows.firstOrNull()?.number?.toIntOrNull()?.let { n -> laps[n].orEmpty().count { l -> l.start != null && l.start <= t } } ?: 0
        return Frame(rows, msgs, lap.coerceAtMost(totalLaps.coerceAtLeast(1)), t - start)
    }

    companion object {
        suspend fun load(s: OpenF1.Session): ReplayData = coroutineScope {
            val isRace = s.type == "Race"
            val drivers = async { OpenF1.drivers(s.key) }
            val laps = async { OpenF1.laps(s.key) }
            val pos = async { OpenF1.positions(s.key) }
            val iv = async { if (isRace) OpenF1.intervals(s.key) else emptyList() }
            val st = async { OpenF1.stints(s.key) }
            val rc = async { OpenF1.raceControl(s.key) }
            val l = laps.await()
            val starts = l.mapNotNull { it.start }
            val ends = l.mapNotNull { x -> x.start?.let { it + ((x.duration ?: 0.0) * 1000).toLong() } }
            ReplayData(
                start = s.start?.toEpochMilli() ?: starts.minOrNull() ?: 0L,
                end = s.end?.toEpochMilli() ?: ends.maxOrNull() ?: 0L,
                drivers = drivers.await().associateBy { it.number },
                laps = l.groupBy { it.driver },
                positions = pos.await().sortedBy { it.t }.groupBy { it.driver },
                intervals = iv.await().sortedBy { it.t }.groupBy { it.driver },
                stints = st.await().sortedBy { it.lapStart }.groupBy { it.driver },
                rc = rc.await(),
                totalLaps = l.maxOfOrNull { it.lap } ?: 0,
                isRace = isRace
            )
        }
    }
}

class ReplayVM : BaseVM() {
    val sel = SessionSelector(viewModelScope)
    val data = MutableStateFlow<Load<ReplayData>>(Load.Loading)
    val time = MutableStateFlow(0L)
    val playing = MutableStateFlow(false)
    val speed = MutableStateFlow(30)
    val frame: StateFlow<ReplayData.Frame?> = combine(data, time) { d, t -> (d as? Load.Ok<ReplayData>)?.value?.frame(t) }
        .flowOn(Dispatchers.Default).stateIn(viewModelScope, SharingStarted.Eagerly, null)
    private var loop: Job? = null

    init {
        sel.onSession = { s -> open(s) }
        sel.start()
    }

    private fun open(s: OpenF1.Session) {
        playing.value = false
        load(data) { ReplayData.load(s).also { time.value = it.end } }
    }

    fun seek(f: Float) {
        val d = (data.value as? Load.Ok<ReplayData>)?.value ?: return
        playing.value = false
        time.value = d.start + ((d.end - d.start) * f).toLong()
    }

    fun toggle() {
        val d = (data.value as? Load.Ok<ReplayData>)?.value ?: return
        if (playing.value) { playing.value = false; return }
        if (time.value >= d.end) time.value = d.start
        playing.value = true
        loop?.cancel()
        loop = viewModelScope.launch {
            while (playing.value) {
                delay(250)
                val nt = time.value + speed.value * 250L
                if (nt >= d.end) { time.value = d.end; playing.value = false } else time.value = nt
            }
        }
    }
}

@Composable
fun ReplayScreen(nav: NavHostController) {
    val vm: ReplayVM = viewModel()
    val data by vm.data.collectAsState()
    val frame by vm.frame.collectAsState()
    val time by vm.time.collectAsState()
    val playing by vm.playing.collectAsState()
    val speed by vm.speed.collectAsState()
    val p = pal()
    LazyColumn(contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item { ScreenHeader(stringResource(R.string.replay_title), onBack = { nav.popBackStack() }) }
        item { SessionSelectorView(vm.sel) { vm.sel.selectYear(vm.sel.year.value) } }
        when (val d = data) {
            is Load.Loading -> item { LoadingBox() }
            is Load.Err -> item { ErrorBox(d.error, null) }
            is Load.Ok -> {
                val rd = d.value
                val f = frame
                item {
                    CardBox {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Mono(if (rd.isRace) stringResource(R.string.lap_of, f?.lap ?: 0, rd.totalLaps) else elapsed(f?.elapsed ?: 0), 14.sp, FontWeight.Bold, modifier = Modifier.weight(1f))
                            Mono("${elapsed(time - rd.start)} / ${elapsed(rd.end - rd.start)}", 12.sp, FontWeight.Normal, p.muted)
                        }
                        val frac = if (rd.end > rd.start) ((time - rd.start).toFloat() / (rd.end - rd.start)).coerceIn(0f, 1f) else 0f
                        Slider(
                            value = frac, onValueChange = { vm.seek(it) },
                            colors = SliderDefaults.colors(thumbColor = Color.White, activeTrackColor = p.accent, inactiveTrackColor = p.card2)
                        )
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            FilledIconButton(onClick = { vm.toggle() }, colors = IconButtonDefaults.filledIconButtonColors(containerColor = p.accent)) {
                                Icon(if (playing) Icons.Filled.Pause else Icons.Filled.PlayArrow, contentDescription = stringResource(if (playing) R.string.pause else R.string.play), tint = Color.White)
                            }
                            val speeds = listOf(1, 10, 30, 60)
                            PillRow(speeds.map { "${it}×" }, speeds.indexOf(speed)) { vm.speed.value = speeds[it] }
                        }
                    }
                }
                if (f != null) {
                    item {
                        CardBox(padding = 0.dp) {
                            TowerHeader()
                            f.rows.forEach { TowerLine(it) }
                        }
                    }
                    item { RaceControlList(f.messages, 30) }
                }
            }
        }
    }
}

private fun elapsed(ms: Long): String {
    val s = (ms / 1000).coerceAtLeast(0)
    return if (s >= 3600) String.format("%d:%02d:%02d", s / 3600, (s % 3600) / 60, s % 60) else String.format("%02d:%02d", s / 60, s % 60)
}
