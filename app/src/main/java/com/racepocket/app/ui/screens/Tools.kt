package com.racepocket.app.ui.screens

import android.content.Context
import android.media.AudioAttributes
import android.media.MediaPlayer
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import com.racepocket.app.ui.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavHostController
import com.racepocket.app.R
import com.racepocket.app.data.AppSettings
import com.racepocket.app.data.DriverRef
import com.racepocket.app.data.DriverStanding
import com.racepocket.app.data.Flags
import com.racepocket.app.data.Http
import com.racepocket.app.data.Jolpica
import com.racepocket.app.data.OpenF1
import com.racepocket.app.data.Race
import com.racepocket.app.data.StatLine
import com.racepocket.app.data.TI
import com.racepocket.app.data.TeamRef
import com.racepocket.app.data.child
import com.racepocket.app.data.fields
import com.racepocket.app.data.fmtLap
import com.racepocket.app.data.str
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
import com.racepocket.app.ui.RaceControlList
import com.racepocket.app.ui.ScreenHeader
import com.racepocket.app.ui.Segmented
import com.racepocket.app.ui.TeamBar
import com.racepocket.app.ui.hexColor
import com.racepocket.app.ui.openF1SessionName
import com.racepocket.app.ui.pal
import com.racepocket.app.ui.teamColor
import com.racepocket.app.ui.tyreStyle
import com.racepocket.app.ui.valueOrNull
import com.racepocket.app.data.live.RaceMsg
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.File
import java.time.Instant
import java.time.Year
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlin.math.abs
import kotlin.math.max

// ===================== RADIO =====================

data class RadioData(val sessionKey: Int, val radios: List<OpenF1.Radio>, val drivers: Map<Int, OpenF1.Driver>)

class RadioVM : BaseVM() {
    val sel = SessionSelector(viewModelScope)
    val data = MutableStateFlow<Load<RadioData>>(Load.Loading)
    val texts = MutableStateFlow<Map<String, String>>(emptyMap())
    val busy = MutableStateFlow<Set<String>>(emptySet())
    val error = MutableStateFlow<String?>(null)
    var settings: AppSettings = AppSettings()
    private var cacheFile: File? = null

    fun start(context: Context, s: AppSettings) {
        settings = s
        if (cacheFile == null) {
            cacheFile = File(context.filesDir, "transcripts.json")
            texts.value = runCatching { Http.json.parseToJsonElement(cacheFile!!.readText()).fields().mapValues { it.value.str() ?: "" } }.getOrDefault(emptyMap())
            sel.onSession = { open(it) }
            sel.start()
        }
    }

    private fun open(s: OpenF1.Session) {
        load(data) {
            coroutineScope {
                val r = async { OpenF1.radios(s.key) }
                val d = async { OpenF1.drivers(s.key) }
                val base = settings.transcriptsUrl.trim()
                if (base.isNotEmpty()) {
                    val url = (if (base.endsWith("/")) base else "$base/") + "${s.key}.json"
                    runCatching { Http.getJson(url).fields().mapValues { it.value.str() ?: "" } }.getOrNull()?.let { texts.value = texts.value + it }
                }
                RadioData(s.key, r.await(), d.await().associateBy { it.number })
            }
        }
    }

    private fun saveCache() {
        val f = cacheFile ?: return
        runCatching { f.writeText(kotlinx.serialization.json.JsonObject(texts.value.mapValues { kotlinx.serialization.json.JsonPrimitive(it.value) }).toString()) }
    }

    fun transcribe(urls: List<String>) {
        val key = settings.whisperKey.trim()
        if (key.isEmpty()) { error.value = "no_key"; return }
        viewModelScope.launch {
            for (u in urls) {
                if (texts.value.containsKey(u)) continue
                busy.value = busy.value + u
                try {
                    val text = whisper(u, key)
                    texts.value = texts.value + (u to text)
                    saveCache()
                } catch (e: Exception) {
                    error.value = e.message ?: "error"
                } finally {
                    busy.value = busy.value - u
                }
            }
        }
    }

    /** OpenAI Whisper API (optional key in Settings). */
    private suspend fun whisper(url: String, key: String): String = withContext(Dispatchers.IO) {
        val audio = Http.client.newCall(Request.Builder().url(url).header("User-Agent", Http.UA).build()).execute().use { it.body?.bytes() ?: ByteArray(0) }
        val body = MultipartBody.Builder().setType(MultipartBody.FORM)
            .addFormDataPart("model", "whisper-1")
            .addFormDataPart("language", "en")
            .addFormDataPart("file", "radio.mp3", audio.toRequestBody("audio/mpeg".toMediaType()))
            .build()
        val req = Request.Builder().url("https://api.openai.com/v1/audio/transcriptions").header("Authorization", "Bearer $key").post(body).build()
        Http.client.newCall(req).execute().use { r ->
            val txt = r.body?.string() ?: ""
            if (!r.isSuccessful) throw IllegalStateException("HTTP ${r.code}")
            Http.json.parseToJsonElement(txt).child("text").str()?.trim() ?: ""
        }
    }
}

@Composable
fun RadioScreen(nav: NavHostController, settings: AppSettings) {
    val vm: RadioVM = viewModel()
    val context = LocalContext.current
    vm.start(context, settings)
    vm.settings = settings
    val data by vm.data.collectAsState()
    val texts by vm.texts.collectAsState()
    val busy by vm.busy.collectAsState()
    val error by vm.error.collectAsState()
    var filter by rememberSaveable { mutableIntStateOf(-1) }
    var query by rememberSaveable { mutableStateOf("") }
    var playing by remember { mutableStateOf<String?>(null) }
    val player = remember { MediaPlayer() }
    DisposableEffect(Unit) { onDispose { runCatching { player.release() } } }
    val fmt = remember { DateTimeFormatter.ofPattern("HH:mm:ss").withZone(ZoneId.systemDefault()) }
    val p = pal()

    fun play(url: String) {
        runCatching {
            player.reset()
            if (playing == url) { playing = null; return }
            player.setAudioAttributes(AudioAttributes.Builder().setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
            player.setDataSource(url)
            player.setOnPreparedListener { it.start() }
            player.setOnCompletionListener { playing = null }
            player.prepareAsync()
            playing = url
        }
    }

    LazyColumn(contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        item { ScreenHeader(stringResource(R.string.nav_radio), onBack = { nav.popBackStack() }) }
        item { SessionSelectorView(vm.sel) { vm.sel.selectYear(vm.sel.year.value) } }
        if (error == "no_key") item { EmptyBox(stringResource(R.string.whisper_missing)) }
        else if (error != null) item { EmptyBox(stringResource(R.string.tx_err) + " (" + error + ")") }
        when (val d = data) {
            is Load.Loading -> item { LoadingBox() }
            is Load.Err -> item { ErrorBox(d.error, null) }
            is Load.Ok -> {
                val rd = d.value
                if (rd.radios.isEmpty()) item { EmptyBox(stringResource(R.string.no_radios)) }
                else {
                    val nums = rd.radios.map { it.driver }.distinct().sortedBy { rd.drivers[it]?.tla ?: "" }
                    item {
                        PillRow(listOf(stringResource(R.string.all_n, rd.radios.size)) + nums.map { rd.drivers[it]?.tla ?: "#$it" }, if (filter < 0) 0 else nums.indexOf(filter) + 1) { i ->
                            filter = if (i == 0) -1 else nums[i - 1]
                        }
                    }
                    item {
                        OutlinedTextField(value = query, onValueChange = { query = it }, singleLine = true, modifier = Modifier.fillMaxWidth(), label = { Text(stringResource(R.string.search_radio)) })
                    }
                    val visible = rd.radios.filter { (filter < 0 || it.driver == filter) && (query.isBlank() || (texts[it.url] ?: "").contains(query, true)) }
                    item {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Muted(stringResource(R.string.transcript_note), 11.sp, Modifier.weight(1f))
                            val missing = visible.map { it.url }.filter { !texts.containsKey(it) }
                            if (missing.isNotEmpty()) OutlinedButton(onClick = { vm.transcribe(missing) }) { Text(stringResource(R.string.tx_all, missing.size), fontSize = 12.sp) }
                        }
                    }
                    items(visible, key = { it.url }) { r ->
                        val drv = rd.drivers[r.driver]
                        CardBox(padding = 12.dp) {
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                FilledTonalIconButton(onClick = { play(r.url) }) {
                                    Icon(if (playing == r.url) Icons.Filled.Pause else Icons.Filled.PlayArrow, contentDescription = stringResource(R.string.play))
                                }
                                TeamBar(hexColor(drv?.color), 28.dp)
                                Flag(Flags.ioc(drv?.countryCode), 14.sp)
                                Column(Modifier.weight(1f)) {
                                    Text(drv?.tla ?: "#${r.driver}", fontSize = 13.sp, fontWeight = FontWeight.Bold, color = p.text)
                                    Muted(drv?.team ?: "", 10.sp)
                                }
                                Mono(fmt.format(Instant.ofEpochMilli(r.t)), 11.sp, FontWeight.Normal, p.muted)
                            }
                            val t = texts[r.url]
                            when {
                                t != null -> Text(if (t.isBlank()) stringResource(R.string.tx_silent) else "“$t”", fontSize = 13.sp, color = p.text, modifier = Modifier.padding(top = 8.dp), lineHeight = 18.sp)
                                r.url in busy -> Muted(stringResource(R.string.tx_working), 12.sp, Modifier.padding(top = 8.dp))
                                else -> Text(
                                    stringResource(R.string.tx_one), fontSize = 12.sp, fontWeight = FontWeight.Bold, color = p.accentText,
                                    modifier = Modifier.padding(top = 8.dp).clip(RoundedCornerShape(50)).background(p.card2)
                                        .clickable { vm.transcribe(listOf(r.url)) }.padding(horizontal = 12.dp, vertical = 6.dp)
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}


// ===================== TELEMETRY =====================

data class TelData(
    val a: TI.Tel, val b: TI.Tel,
    val lapA: TI.TLap, val lapB: TI.TLap,
    val codeA: String, val codeB: String,
    val colorA: Color, val colorB: Color
)

class TelemetryVM : BaseVM() {
    val years = (Year.now().value downTo 2023).toList()
    val year = MutableStateFlow(years.first())
    val races = MutableStateFlow<List<Race>>(emptyList())
    val race = MutableStateFlow<Race?>(null)
    val session = MutableStateFlow("Qualifying")
    val drivers = MutableStateFlow<Load<List<TI.Driver>>>(Load.Loading)
    val a = MutableStateFlow<TI.Driver?>(null)
    val b = MutableStateFlow<TI.Driver?>(null)
    val data = MutableStateFlow<Load<TelData>?>(null)
    /** Folder name the dataset uses for the selected race (may differ from Jolpica's name). */
    private var tiName = ""

    init { selectYear(years.first()) }

    fun selectYear(y: Int) {
        year.value = y
        viewModelScope.launch {
            val now = Instant.now()
            val list = runCatching { Jolpica.calendar(y) }.getOrDefault(emptyList()).filter { (it.sessions.firstOrNull()?.start ?: now).isBefore(now) }
            races.value = list
            list.lastOrNull()?.let { selectRace(it) }
        }
    }

    fun selectRace(r: Race) { race.value = r; loadDrivers() }
    fun selectSession(s: String) { session.value = s; loadDrivers() }

    private fun loadDrivers() {
        val r = race.value ?: return
        data.value = null
        tiName = ""
        load(drivers) { TI.withEvent(r.name, r.season) { n -> TI.drivers(r.season, n, session.value).also { tiName = n } } }.invokeOnCompletion {
            val list = drivers.value.valueOrNull ?: return@invokeOnCompletion
            a.value = list.getOrNull(0); b.value = list.getOrNull(1)
        }
    }

    fun compare() {
        val r = race.value ?: return
        val da = a.value ?: return
        val db = b.value ?: return
        val ev = tiName.ifEmpty { r.name }
        data.value = Load.Loading
        viewModelScope.launch {
            data.value = try {
                coroutineScope {
                    val la = async { TI.laps(r.season, ev, session.value, da.code).filter { !it.deleted }.minByOrNull { it.time } }
                    val lb = async { TI.laps(r.season, ev, session.value, db.code).filter { !it.deleted }.minByOrNull { it.time } }
                    val lapA = la.await() ?: throw IllegalStateException("no lap")
                    val lapB = lb.await() ?: throw IllegalStateException("no lap")
                    val ta = async { TI.tel(r.season, ev, session.value, da.code, lapA.lap) }
                    val tb = async { TI.tel(r.season, ev, session.value, db.code, lapB.lap) }
                    Load.Ok(TelData(ta.await(), tb.await(), lapA, lapB, da.code, db.code, hexColor(da.color), hexColor(db.color).let { if (da.color == db.color) Color(0xFFFFFFFF) else it }))
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                Load.Err(e)
            }
        }
    }
}

@Composable
fun TelemetryScreen(nav: NavHostController) {
    val vm: TelemetryVM = viewModel()
    val year by vm.year.collectAsState()
    val races by vm.races.collectAsState()
    val race by vm.race.collectAsState()
    val session by vm.session.collectAsState()
    val drivers by vm.drivers.collectAsState()
    val a by vm.a.collectAsState()
    val b by vm.b.collectAsState()
    val data by vm.data.collectAsState()
    val p = pal()
    LazyColumn(contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        item { ScreenHeader(stringResource(R.string.nav_telemetry), onBack = { nav.popBackStack() }) }
        item { Muted(stringResource(R.string.telemetry_lead), 12.sp) }
        item {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Picker(stringResource(R.string.year), vm.years, year, { it.toString() }, Modifier.weight(0.8f)) { vm.selectYear(it) }
                    Picker(stringResource(R.string.grand_prix), races, race, { Fmt.raceName(it.name) }, Modifier.weight(1.6f)) { vm.selectRace(it) }
                }
                Picker(stringResource(R.string.session), TI.SESSIONS, session, { it }, Modifier.fillMaxWidth()) { vm.selectSession(it) }
                val list = drivers.valueOrNull ?: emptyList()
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Picker(stringResource(R.string.driver_a), list, a, { "${it.code} · ${it.team}" }, Modifier.weight(1f)) { vm.a.value = it }
                    Picker(stringResource(R.string.driver_b), list, b, { "${it.code} · ${it.team}" }, Modifier.weight(1f)) { vm.b.value = it }
                }
                if (drivers is Load.Err) EmptyBox(stringResource(R.string.no_session))
                OutlinedButton(onClick = { vm.compare() }, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.compare_laps)) }
            }
        }
        when (val d = data) {
            null -> {}
            is Load.Loading -> item { LoadingBox() }
            is Load.Err -> item { EmptyBox(stringResource(R.string.no_valid_lap)) }
            is Load.Ok -> {
                val t = d.value
                item { LapSummary(t) }
                item { Chart(stringResource(R.string.speed), t, { it.speed }, 60f, 360f) }
                item { Chart(stringResource(R.string.throttle), t, { it.throttle }, 0f, 100f, 70.dp) }
                item { Chart(stringResource(R.string.brake), t, { it.brake }, 0f, 1f, 40.dp) }
                item { Chart(stringResource(R.string.gear), t, { it.gear }, 1f, 8f, 70.dp) }
                item { DeltaChart(t) }
            }
        }
    }
}

@Composable
private fun LapSummary(t: TelData) {
    val p = pal()
    // both cards always the same height: the gap sits beside the lap time, not on a line of its own
    Row(Modifier.height(IntrinsicSize.Min), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        listOf(Triple(t.codeA, t.lapA, t.colorA), Triple(t.codeB, t.lapB, t.colorB)).forEachIndexed { i, (code, lap, color) ->
            val other = if (i == 0) t.lapB else t.lapA
            Column(Modifier.weight(1f).fillMaxHeight().clip(RoundedCornerShape(12.dp)).background(p.card).padding(12.dp)) {
                Box(Modifier.fillMaxWidth().height(3.dp).background(color))
                Text(code, fontSize = 14.sp, fontWeight = FontWeight.Bold, color = p.text, modifier = Modifier.padding(top = 6.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Mono(fmtLap(lap.time), 16.sp, FontWeight.Bold, modifier = Modifier.weight(1f))
                    if (lap.time > other.time) Mono(String.format("+%.3f", lap.time - other.time), 11.sp, FontWeight.Normal, p.accentText)
                }
                listOf(lap.s1 to other.s1, lap.s2 to other.s2, lap.s3 to other.s3).forEachIndexed { k, (mine, theirs) ->
                    val best = mine != null && (theirs == null || mine <= theirs)
                    Row {
                        Muted(stringResource(R.string.sector_n, k + 1), 11.sp, Modifier.weight(1f))
                        Mono(mine?.let { String.format("%.3f", it) } ?: "—", 11.sp, FontWeight.Normal, if (best) p.purple else p.text)
                    }
                }
            }
        }
    }
}

@Composable
private fun Chart(title: String, t: TelData, value: (TI.Tel) -> FloatArray, lo: Float, hi: Float, height: androidx.compose.ui.unit.Dp = 120.dp) {
    val p = pal()
    CardBox(padding = 10.dp) {
        Text(title, fontSize = 12.sp, fontWeight = FontWeight.Bold, color = p.text)
        Canvas(Modifier.fillMaxWidth().height(height).padding(top = 6.dp)) {
            val maxD = max(t.a.distance.lastOrNull { !it.isNaN() } ?: 1f, t.b.distance.lastOrNull { !it.isNaN() } ?: 1f)
            fun path(tel: TI.Tel): Path {
                val ys = value(tel)
                val path = Path()
                var started = false
                for (i in tel.distance.indices) {
                    val d = tel.distance[i]
                    val v = ys.getOrNull(i) ?: continue
                    if (d.isNaN() || v.isNaN()) continue
                    val x = d / maxD * size.width
                    val y = size.height - (v - lo) / (hi - lo) * size.height
                    if (!started) { path.moveTo(x, y); started = true } else path.lineTo(x, y)
                }
                return path
            }
            drawLine(p.divider, Offset(0f, size.height), Offset(size.width, size.height))
            drawPath(path(t.a), t.colorA, style = Stroke(width = 1.6.dp.toPx()))
            drawPath(path(t.b), t.colorB, style = Stroke(width = 1.6.dp.toPx()))
        }
    }
}

@Composable
private fun DeltaChart(t: TelData) {
    val p = pal()
    // time of B at each distance of A, by linear interpolation
    val delta = remember(t) {
        val out = ArrayList<Pair<Float, Float>>()
        var j = 0
        for (i in t.a.distance.indices) {
            val d = t.a.distance[i]
            if (d.isNaN()) continue
            while (j + 1 < t.b.distance.size && t.b.distance[j + 1] < d) j++
            if (j + 1 >= t.b.distance.size) break
            val d0 = t.b.distance[j]
            val d1 = t.b.distance[j + 1]
            val f = if (d1 > d0) (d - d0) / (d1 - d0) else 0f
            val tb = t.b.time[j] + f * (t.b.time[j + 1] - t.b.time[j])
            out += d to (tb - t.a.time[i])
        }
        out
    }
    CardBox(padding = 10.dp) {
        Text(stringResource(R.string.delta_title, t.codeB, t.codeA), fontSize = 12.sp, fontWeight = FontWeight.Bold, color = p.text)
        Canvas(Modifier.fillMaxWidth().height(90.dp).padding(top = 6.dp)) {
            if (delta.isEmpty()) return@Canvas
            val maxD = delta.last().first.coerceAtLeast(1f)
            val range = delta.maxOf { abs(it.second) }.coerceAtLeast(0.05f)
            val mid = size.height / 2
            drawLine(p.divider, Offset(0f, mid), Offset(size.width, mid))
            val path = Path()
            delta.forEachIndexed { k, (d, v) ->
                val x = d / maxD * size.width
                val y = mid - v / range * (size.height / 2)
                if (k == 0) path.moveTo(x, y) else path.lineTo(x, y)
            }
            drawPath(path, t.colorB, style = Stroke(width = 1.8.dp.toPx()))
        }
        Muted(stringResource(R.string.delta_note, t.codeB), 10.sp)
    }
}

// ===================== STRATEGY =====================

data class StrategyData(
    val drivers: Map<Int, OpenF1.Driver>,
    val stints: Map<Int, List<OpenF1.Stint>>,
    val pits: List<OpenF1.Pit>,
    val order: List<Int>,
    val laps: Int,
    val rc: List<OpenF1.RaceControl>
)

class StrategyVM : BaseVM() {
    val sel = SessionSelector(viewModelScope)
    val data = MutableStateFlow<Load<StrategyData>>(Load.Loading)
    init {
        sel.onSession = { s ->
            load(data) {
                coroutineScope {
                    val d = async { OpenF1.drivers(s.key) }
                    val st = async { OpenF1.stints(s.key) }
                    val pt = async { runCatching { OpenF1.pits(s.key) }.getOrDefault(emptyList()) }
                    val res = async { runCatching { OpenF1.sessionResult(s.key) }.getOrDefault(emptyList()) }
                    val rc = async { runCatching { OpenF1.raceControl(s.key) }.getOrDefault(emptyList()) }
                    val stints = st.await().groupBy { it.driver }.mapValues { (_, v) -> v.sortedBy { it.number } }
                    val order = res.await().map { it.driver }.ifEmpty { stints.keys.toList() }
                    StrategyData(d.await().associateBy { it.number }, stints, pt.await(), order, stints.values.flatten().maxOfOrNull { it.lapEnd } ?: 0, rc.await())
                }
            }
        }
        sel.start()
    }
}

@Composable
fun StrategyScreen(nav: NavHostController) {
    val vm: StrategyVM = viewModel()
    val data by vm.data.collectAsState()
    val p = pal()
    val fmt = remember { DateTimeFormatter.ofPattern("HH:mm:ss").withZone(ZoneId.systemDefault()) }
    LazyColumn(contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        item { ScreenHeader(stringResource(R.string.nav_strategy), onBack = { nav.popBackStack() }) }
        item { SessionSelectorView(vm.sel) { vm.sel.selectYear(vm.sel.year.value) } }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                listOf("SOFT" to R.string.tyre_soft, "MEDIUM" to R.string.tyre_medium, "HARD" to R.string.tyre_hard, "INTERMEDIATE" to R.string.tyre_inter, "WET" to R.string.tyre_wet).forEach { (c, label) ->
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        Box(Modifier.size(10.dp).clip(RoundedCornerShape(3.dp)).background(tyreStyle(c).second))
                        Muted(stringResource(label), 10.sp)
                    }
                }
            }
        }
        when (val d = data) {
            is Load.Loading -> item { LoadingBox() }
            is Load.Err -> item { ErrorBox(d.error, null) }
            is Load.Ok -> {
                val s = d.value
                if (s.stints.isEmpty()) item { EmptyBox(stringResource(R.string.no_tyre_data)) }
                else {
                    item {
                        CardBox(padding = 10.dp) {
                            Muted(stringResource(R.string.stints_title, s.laps), 11.sp)
                            s.order.forEach { n ->
                                val list = s.stints[n] ?: return@forEach
                                Row(Modifier.padding(vertical = 3.dp), verticalAlignment = Alignment.CenterVertically) {
                                    Text(s.drivers[n]?.tla ?: "#$n", fontSize = 11.sp, fontWeight = FontWeight.Bold, color = p.text, modifier = Modifier.width(38.dp))
                                    Row(Modifier.weight(1f).height(16.dp), horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                                        list.forEach { st ->
                                            val laps = (st.lapEnd - st.lapStart + 1).coerceAtLeast(1)
                                            Box(Modifier.weight(laps.toFloat()).height(16.dp).clip(RoundedCornerShape(3.dp)).background(tyreStyle(st.compound).second), contentAlignment = Alignment.Center) {
                                                Text("$laps", fontSize = 9.sp, fontWeight = FontWeight.Bold, color = Color(0xFF15151E))
                                            }
                                        }
                                        val used = list.sumOf { (it.lapEnd - it.lapStart + 1).coerceAtLeast(1) }
                                        if (s.laps > used) Box(Modifier.weight((s.laps - used).toFloat()))
                                    }
                                    Muted(stringResource(R.string.stops_n, (list.size - 1).coerceAtLeast(0)), 10.sp, Modifier.width(54.dp).padding(start = 6.dp))
                                }
                            }
                        }
                    }
                    val pits = s.pits.filter { (it.duration ?: 0.0) > 0 }.sortedBy { it.duration }
                    if (pits.isNotEmpty()) item {
                        CardBox(padding = 0.dp) {
                            Text(stringResource(R.string.fastest_stops), fontSize = 14.sp, fontWeight = FontWeight.Bold, color = p.text, modifier = Modifier.padding(14.dp))
                            pits.take(10).forEach { pit ->
                                Divider()
                                Row(Modifier.padding(horizontal = 14.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                                    Text(s.drivers[pit.driver]?.tla ?: "#${pit.driver}", fontSize = 12.sp, fontWeight = FontWeight.Bold, color = p.text, modifier = Modifier.width(48.dp))
                                    Muted(stringResource(R.string.lap_n, pit.lap), 12.sp, Modifier.weight(1f))
                                    Mono(String.format("%.1f s", pit.duration ?: 0.0), 12.sp, FontWeight.Bold)
                                }
                            }
                            Muted(stringResource(R.string.pit_note), 10.sp, Modifier.padding(14.dp))
                        }
                    }
                    item { RaceControlList(s.rc.reversed().map { RaceMsg(fmt.format(Instant.ofEpochMilli(it.t)), it.lap, it.message, it.flag) }, 60) }
                }
            }
        }
    }
}

// ===================== SIMULATION =====================

data class SimEvent(val race: Race, val sprint: Boolean)
data class SimData(val standings: List<DriverStanding>, val events: List<SimEvent>)

class SimulationVM : BaseVM() {
    val data = MutableStateFlow<Load<SimData>>(Load.Loading)
    /** event index -> podium driver ids (P1, P2, P3) */
    val picks = MutableStateFlow<Map<Int, List<String?>>>(emptyMap())
    init { refresh() }
    fun refresh() {
        load(data) {
            val y = Year.now().value
            val now = Instant.now()
            val standings = Jolpica.driverStandings(y)
            val events = Jolpica.calendar(y).flatMap { r ->
                buildList {
                    val sprint = r.sessions.firstOrNull { it.kind == com.racepocket.app.data.SessionKind.SPRINT }
                    if (sprint != null && sprint.start.isAfter(now)) add(SimEvent(r, true))
                    if (!r.isFinished(now)) add(SimEvent(r, false))
                }
            }
            SimData(standings, events)
        }
    }
    fun pick(event: Int, slot: Int, driverId: String?) {
        val cur = (picks.value[event] ?: listOf(null, null, null)).toMutableList()
        cur[slot] = driverId
        picks.value = picks.value + (event to cur)
    }
    fun reset() { picks.value = emptyMap() }
}

private val RACE_PTS = listOf(25, 18, 15)
private val SPRINT_PTS = listOf(8, 7, 6)

@Composable
fun SimulationScreen(nav: NavHostController) {
    val vm: SimulationVM = viewModel()
    val data by vm.data.collectAsState()
    val picks by vm.picks.collectAsState()
    val p = pal()
    LazyColumn(contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        item { ScreenHeader(stringResource(R.string.nav_simulation), onBack = { nav.popBackStack() }) }
        item { Muted(stringResource(R.string.sim_lead), 12.sp) }
        when (val d = data) {
            is Load.Loading -> item { LoadingBox() }
            is Load.Err -> item { ErrorBox(d.error) { vm.refresh() } }
            is Load.Ok -> {
                val s = d.value
                if (s.events.isEmpty() || s.standings.isEmpty()) {
                    item { EmptyBox(stringResource(R.string.sim_done)) }
                } else {
                    val races = s.events.count { !it.sprint }
                    val sprints = s.events.count { it.sprint }
                    val maxLeft = races * 25 + sprints * 8
                    val leader = s.standings.first().points.toDoubleOrNull() ?: 0.0
                    item {
                        CardBox(padding = 0.dp) {
                            Text(stringResource(R.string.remaining, races, sprints, maxLeft), fontSize = 12.sp, color = p.muted, modifier = Modifier.padding(14.dp))
                            s.standings.take(10).forEach { st ->
                                val pts = st.points.toDoubleOrNull() ?: 0.0
                                val alive = pts + maxLeft >= leader
                                Divider()
                                Row(Modifier.padding(horizontal = 14.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    Text(st.pos, fontSize = 13.sp, fontWeight = FontWeight.Bold, color = p.text, modifier = Modifier.width(20.dp))
                                    Flag(st.driver.flag, 14.sp)
                                    Text(st.driver.name, fontSize = 13.sp, color = p.text, modifier = Modifier.weight(1f), maxLines = 1)
                                    Mono(Fmt.points(st.points), 12.sp, FontWeight.Bold)
                                    Text(
                                        stringResource(if (alive) R.string.alive else R.string.out),
                                        fontSize = 10.sp, fontWeight = FontWeight.Bold, color = if (alive) p.green else p.muted, modifier = Modifier.width(78.dp), textAlign = TextAlign.End
                                    )
                                }
                            }
                        }
                    }
                    val contenders = s.standings.take(10)
                    item {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(stringResource(R.string.scenario), fontSize = 16.sp, fontWeight = FontWeight.Bold, color = p.text, modifier = Modifier.weight(1f))
                            Text(stringResource(R.string.reset), color = p.accentText, fontSize = 13.sp, modifier = Modifier.clickable { vm.reset() }.padding(8.dp))
                        }
                    }
                    items(s.events.size) { i ->
                        val e = s.events[i]
                        val chosen = picks[i] ?: listOf(null, null, null)
                        CardBox(padding = 12.dp) {
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                Flag(e.race.flag, 16.sp)
                                Text(Fmt.raceName(e.race.name), fontSize = 13.sp, fontWeight = FontWeight.Bold, color = p.text, modifier = Modifier.weight(1f))
                                Muted(stringResource(if (e.sprint) R.string.s_sprint else R.string.s_race), 11.sp)
                            }
                            Row(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                (0..2).forEach { slot ->
                                    val opts = listOf<DriverStanding?>(null) + contenders
                                    Picker("P${slot + 1}", opts, contenders.firstOrNull { it.driver.id == chosen[slot] }, { it?.driver?.code ?: "—" }, Modifier.weight(1f)) { st ->
                                        vm.pick(i, slot, st?.driver?.id)
                                    }
                                }
                            }
                        }
                    }
                    item {
                        val projected = s.standings.map { st ->
                            var pts = st.points.toDoubleOrNull() ?: 0.0
                            picks.forEach { (i, list) ->
                                val table = if (s.events.getOrNull(i)?.sprint == true) SPRINT_PTS else RACE_PTS
                                list.forEachIndexed { slot, id -> if (id == st.driver.id) pts += table[slot] }
                            }
                            st to pts
                        }.sortedByDescending { it.second }
                        CardBox(padding = 0.dp) {
                            Text(stringResource(R.string.projected), fontSize = 14.sp, fontWeight = FontWeight.Bold, color = p.text, modifier = Modifier.padding(14.dp))
                            projected.take(10).forEachIndexed { i, (st, pts) ->
                                Divider()
                                Row(Modifier.padding(horizontal = 14.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    Text("${i + 1}", fontSize = 13.sp, fontWeight = FontWeight.Bold, color = p.text, modifier = Modifier.width(20.dp))
                                    Flag(st.driver.flag, 14.sp)
                                    Text(st.driver.name, fontSize = 13.sp, color = p.text, modifier = Modifier.weight(1f))
                                    Muted(Fmt.points(st.points) + " →", 11.sp)
                                    Mono(Fmt.points(pts.toString()), 13.sp, FontWeight.Bold)
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

// ===================== COMPARE =====================

class CompareVM : BaseVM() {
    val years = (Year.now().value downTo 1950).toList()
    val mode = MutableStateFlow(0) // 0 drivers, 1 teams, 2 years
    val career = MutableStateFlow(false)
    val year = MutableStateFlow(Year.now().value)
    val drivers = MutableStateFlow<List<DriverRef>>(emptyList())
    val teams = MutableStateFlow<List<TeamRef>>(emptyList())
    val a = MutableStateFlow<String?>(null)
    val b = MutableStateFlow<String?>(null)
    val yearA = MutableStateFlow(Year.now().value)
    val yearB = MutableStateFlow(Year.now().value - 1)
    val result = MutableStateFlow<Load<Pair<StatLine, StatLine>>?>(null)
    val years2 = MutableStateFlow<Load<Pair<Jolpica.YearSummary, Jolpica.YearSummary>>?>(null)

    init { selectYear(year.value) }

    fun selectYear(y: Int) {
        year.value = y
        viewModelScope.launch {
            var d = runCatching { Jolpica.driverStandings(y).map { it.driver } }.getOrDefault(emptyList())
            if (d.isEmpty()) d = runCatching { Jolpica.seasonDrivers(y) }.getOrDefault(emptyList())
            drivers.value = d
            var t = runCatching { Jolpica.constructorStandings(y).map { it.team } }.getOrDefault(emptyList())
            if (t.isEmpty()) t = runCatching { Jolpica.seasonTeams(y) }.getOrDefault(emptyList())
            teams.value = t
            if (mode.value == 0) { a.value = d.getOrNull(0)?.id; b.value = d.getOrNull(1)?.id }
            if (mode.value == 1) { a.value = t.getOrNull(0)?.id; b.value = t.getOrNull(1)?.id }
            run()
        }
    }

    fun setMode(m: Int) {
        mode.value = m
        if (m == 0) { a.value = drivers.value.getOrNull(0)?.id; b.value = drivers.value.getOrNull(1)?.id }
        if (m == 1) { a.value = teams.value.getOrNull(0)?.id; b.value = teams.value.getOrNull(1)?.id }
        run()
    }

    fun run() {
        if (mode.value == 2) {
            val y1 = yearA.value
            val y2 = yearB.value
            years2.value = Load.Loading
            viewModelScope.launch {
                years2.value = try {
                    coroutineScope {
                        val x = async { Jolpica.yearSummary(y1) }
                        val z = async { Jolpica.yearSummary(y2) }
                        Load.Ok(x.await() to z.await())
                    }
                } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (e: Exception) { Load.Err(e) }
            }
            return
        }
        val ia = a.value ?: return
        val ib = b.value ?: return
        val y = year.value
        val car = career.value
        val teamsMode = mode.value == 1
        result.value = Load.Loading
        viewModelScope.launch {
            result.value = try {
                coroutineScope {
                    val x = async { if (teamsMode) (if (car) Jolpica.teamCareerStats(ia) else Jolpica.teamSeasonStats(ia, y)) else (if (car) Jolpica.driverCareerStats(ia) else Jolpica.driverSeasonStats(ia, y)) }
                    val z = async { if (teamsMode) (if (car) Jolpica.teamCareerStats(ib) else Jolpica.teamSeasonStats(ib, y)) else (if (car) Jolpica.driverCareerStats(ib) else Jolpica.driverSeasonStats(ib, y)) }
                    Load.Ok(x.await() to z.await())
                }
            } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (e: Exception) { Load.Err(e) }
        }
    }
}

@Composable
fun CompareScreen(nav: NavHostController) {
    val vm: CompareVM = viewModel()
    val mode by vm.mode.collectAsState()
    val career by vm.career.collectAsState()
    val year by vm.year.collectAsState()
    val drivers by vm.drivers.collectAsState()
    val teams by vm.teams.collectAsState()
    val a by vm.a.collectAsState()
    val b by vm.b.collectAsState()
    val result by vm.result.collectAsState()
    val yearA by vm.yearA.collectAsState()
    val yearB by vm.yearB.collectAsState()
    val years2 by vm.years2.collectAsState()
    val p = pal()
    LazyColumn(contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        item { ScreenHeader(stringResource(R.string.nav_compare), onBack = { nav.popBackStack() }) }
        item { Segmented(listOf(stringResource(R.string.drivers), stringResource(R.string.teams), stringResource(R.string.years)), mode) { vm.setMode(it) } }
        if (mode == 2) {
            item {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Picker(stringResource(R.string.year) + " A", vm.years, yearA, { it.toString() }, Modifier.weight(1f)) { vm.yearA.value = it; vm.run() }
                    Picker(stringResource(R.string.year) + " B", vm.years, yearB, { it.toString() }, Modifier.weight(1f)) { vm.yearB.value = it; vm.run() }
                }
            }
            item {
                when (val r = years2) {
                    null -> LoadingBox()
                    is Load.Loading -> LoadingBox()
                    is Load.Err -> ErrorBox(r.error) { vm.run() }
                    is Load.Ok -> {
                        val (x, z) = r.value
                        CardBox(padding = 0.dp) {
                            YearRow(stringResource(R.string.champion), x.champion?.let { "${it.driver.flag} ${it.driver.name}" } ?: "—", z.champion?.let { "${it.driver.flag} ${it.driver.name}" } ?: "—")
                            YearRow(stringResource(R.string.team_champion), x.teamChampion?.team?.name ?: "—", z.teamChampion?.team?.name ?: "—")
                            YearRow(stringResource(R.string.champion_points), x.champion?.points?.let(Fmt::points) ?: "—", z.champion?.points?.let(Fmt::points) ?: "—")
                            YearRow(stringResource(R.string.champion_wins), "${x.champion?.wins ?: "—"}", "${z.champion?.wins ?: "—"}")
                            YearRow(stringResource(R.string.races), "${x.races}", "${z.races}")
                            YearRow(stringResource(R.string.sprints), "${x.sprints}", "${z.sprints}")
                            YearRow(stringResource(R.string.different_winners), "${x.winners}", "${z.winners}")
                        }
                    }
                }
            }
        } else {
            item {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Box(Modifier.weight(1f)) { Segmented(listOf(stringResource(R.string.season), stringResource(R.string.career)), if (career) 1 else 0) { vm.career.value = it == 1; vm.run() } }
                        Picker(stringResource(R.string.year), vm.years, year, { it.toString() }, Modifier.width(100.dp)) { vm.selectYear(it) }
                    }
                    if (mode == 0) {
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Picker("A", drivers, drivers.firstOrNull { it.id == a }, { "${it.flag} ${it.name}" }, Modifier.weight(1f)) { vm.a.value = it.id; vm.run() }
                            Picker("B", drivers, drivers.firstOrNull { it.id == b }, { "${it.flag} ${it.name}" }, Modifier.weight(1f)) { vm.b.value = it.id; vm.run() }
                        }
                    } else {
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Picker("A", teams, teams.firstOrNull { it.id == a }, { "${it.flag} ${it.name}" }, Modifier.weight(1f)) { vm.a.value = it.id; vm.run() }
                            Picker("B", teams, teams.firstOrNull { it.id == b }, { "${it.flag} ${it.name}" }, Modifier.weight(1f)) { vm.b.value = it.id; vm.run() }
                        }
                    }
                }
            }
            item {
                when (val r = result) {
                    null -> LoadingBox()
                    is Load.Loading -> LoadingBox()
                    is Load.Err -> ErrorBox(r.error) { vm.run() }
                    is Load.Ok -> {
                        val (x, z) = r.value
                        val colorA = if (mode == 0) Color(0xFF6B9CFF) else teamColor(com.racepocket.app.data.Static.teamColor(a))
                        val colorB = if (mode == 0) Color(0xFFFF8000) else teamColor(com.racepocket.app.data.Static.teamColor(b)).let { if (a == b) Color.White else it }
                        CompareTable(x, z, career, mode == 0, colorA, colorB)
                    }
                }
            }
        }
    }
}

@Composable
private fun YearRow(label: String, x: String, z: String) {
    val p = pal()
    Divider()
    Row(Modifier.padding(horizontal = 14.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(x, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = p.text, modifier = Modifier.weight(1f))
        Muted(label, 11.sp, Modifier.weight(1f).padding(horizontal = 4.dp))
        Text(z, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = p.text, modifier = Modifier.weight(1f), textAlign = TextAlign.End)
    }
}

@Composable
private fun CompareTable(x: StatLine, z: StatLine, career: Boolean, drivers: Boolean, colorA: Color, colorB: Color) {
    val p = pal()
    data class M(val label: String, val a: Double?, val b: Double?, val higherIsBetter: Boolean, val decimals: Int = 0)
    val qDuel = if (!career && drivers) x.qualiByRound.keys.intersect(z.qualiByRound.keys).let { rs -> rs.count { x.qualiByRound[it]!! < z.qualiByRound[it]!! } to rs.count { z.qualiByRound[it]!! < x.qualiByRound[it]!! } } else null
    val rDuel = if (!career && drivers) x.finishByRound.keys.intersect(z.finishByRound.keys).let { rs -> rs.count { x.finishByRound[it]!! < z.finishByRound[it]!! } to rs.count { z.finishByRound[it]!! < x.finishByRound[it]!! } } else null
    val rows = listOfNotNull(
        M(stringResource(R.string.points), x.points, z.points, true, 1),
        M(stringResource(R.string.wins), x.wins?.toDouble(), z.wins?.toDouble(), true),
        M(stringResource(R.string.poles), x.poles?.toDouble(), z.poles?.toDouble(), true),
        M(stringResource(R.string.podiums), x.podiums?.toDouble(), z.podiums?.toDouble(), true),
        M(stringResource(R.string.fastest_laps), x.fastest?.toDouble(), z.fastest?.toDouble(), true),
        M(stringResource(R.string.starts), x.starts?.toDouble(), z.starts?.toDouble(), true),
        if (career) M(stringResource(R.string.titles), x.titles?.toDouble(), z.titles?.toDouble(), true) else null,
        if (!career) M(stringResource(R.string.avg_finish), x.avgFinish, z.avgFinish, false, 1) else null,
        if (!career) M(stringResource(R.string.avg_grid), x.avgGrid, z.avgGrid, false, 1) else null,
        if (!career) M(stringResource(R.string.best_finish), x.bestFinish?.toDouble(), z.bestFinish?.toDouble(), false) else null,
        if (!career) M(stringResource(R.string.dnfs), x.dnfs?.toDouble(), z.dnfs?.toDouble(), false) else null,
        qDuel?.let { M(stringResource(R.string.quali_duel), it.first.toDouble(), it.second.toDouble(), true) },
        rDuel?.let { M(stringResource(R.string.race_duel), it.first.toDouble(), it.second.toDouble(), true) },
        if (x.starts != null && z.starts != null && x.points != null && z.points != null)
            M(stringResource(R.string.points_per_race), x.points / x.starts.coerceAtLeast(1), z.points / z.starts.coerceAtLeast(1), true, 2) else null
    )
    CardBox(padding = 12.dp) {
        rows.forEach { m ->
            val av = m.a
            val bv = m.b
            val aWins = av != null && (bv == null || if (m.higherIsBetter) av >= bv else av <= bv)
            val bWins = bv != null && (av == null || if (m.higherIsBetter) bv >= av else bv <= av)
            val maxV = max(av ?: 0.0, bv ?: 0.0).coerceAtLeast(0.0001)
            fun fmt(v: Double?) = v?.let { if (m.decimals == 0) String.format("%.0f", it) else String.format("%.${m.decimals}f", it) } ?: "—"
            Column(Modifier.padding(vertical = 6.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Mono(fmt(av), 14.sp, if (aWins) FontWeight.ExtraBold else FontWeight.Normal, if (aWins) p.text else p.muted, Modifier.weight(1f))
                    Muted(m.label, 11.sp)
                    Mono(fmt(bv), 14.sp, if (bWins) FontWeight.ExtraBold else FontWeight.Normal, if (bWins) p.text else p.muted, Modifier.weight(1f), TextAlign.End)
                }
                Row(Modifier.padding(top = 4.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    Box(Modifier.weight(1f).height(5.dp).clip(RoundedCornerShape(3.dp)).background(p.card2), contentAlignment = Alignment.CenterEnd) {
                        Box(Modifier.fillMaxWidth(((av ?: 0.0) / maxV).toFloat().coerceIn(0f, 1f)).height(5.dp).background(colorA))
                    }
                    Box(Modifier.weight(1f).height(5.dp).clip(RoundedCornerShape(3.dp)).background(p.card2), contentAlignment = Alignment.CenterStart) {
                        Box(Modifier.fillMaxWidth(((bv ?: 0.0) / maxV).toFloat().coerceIn(0f, 1f)).height(5.dp).background(colorB))
                    }
                }
            }
        }
        if (!career && (x.form.isNotEmpty() || z.form.isNotEmpty())) {
            Text(stringResource(R.string.last_5), fontSize = 12.sp, fontWeight = FontWeight.Bold, color = p.text, modifier = Modifier.padding(top = 10.dp))
            listOf(x.form to colorA, z.form to colorB).forEach { (form, c) ->
                Row(Modifier.padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(10.dp).clip(CircleShape).background(c))
                    form.forEach { pos ->
                        Text(
                            pos, fontSize = 12.sp, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center,
                            color = if (pos == "1") Color(0xFF15151E) else p.text,
                            modifier = Modifier.weight(1f).clip(RoundedCornerShape(6.dp)).background(if (pos == "1") Color(0xFFFFD12E) else p.card2).padding(vertical = 5.dp)
                        )
                    }
                }
            }
        }
    }
}
