package com.racepocket.app.data.live

import com.racepocket.app.data.Http
import com.racepocket.app.data.bool
import com.racepocket.app.data.child
import com.racepocket.app.data.fields
import com.racepocket.app.data.int
import com.racepocket.app.data.items
import com.racepocket.app.data.mergeJson
import com.racepocket.app.data.parseInstant
import com.racepocket.app.data.parseLapTime
import com.racepocket.app.data.path
import com.racepocket.app.data.str
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * F1's free live timing feed (SignalR Core), the same one the Pit Wall site uses.
 * The phone connects directly, so there is no CORS and no server in between.
 * Car positions (GPS) are not part of the free feed.
 */
class LiveTimingClient(private val scope: CoroutineScope) {
    sealed interface Status {
        data object Idle : Status
        data object Connecting : Status
        data object Connected : Status
        data class Failed(val reason: String) : Status
    }

    private val _status = MutableStateFlow<Status>(Status.Idle)
    val status: StateFlow<Status> = _status.asStateFlow()
    private val _data = MutableStateFlow<Map<String, JsonElement>>(emptyMap())
    val data: StateFlow<Map<String, JsonElement>> = _data.asStateFlow()

    private val topics = HashMap<String, JsonElement>()
    private val lock = Any()
    @Volatile private var dirty = false
    @Volatile private var opened = false
    @Volatile private var closedByUser = false
    private var ws: WebSocket? = null
    private val jobs = java.util.concurrent.CopyOnWriteArrayList<Job>()

    private val TOPICS = listOf("TimingData", "TimingAppData", "DriverList", "RaceControlMessages", "TrackStatus", "LapCount", "SessionInfo", "SessionStatus", "ExtrapolatedClock", "TimingStats", "SessionData")
    private val RS = '\u001e'

    fun connect() {
        if (_status.value is Status.Connecting || _status.value is Status.Connected) return
        closedByUser = false
        attempts = 0
        jobs += scope.launch { open() }
        jobs += scope.launch {
            while (isActive) {
                delay(700)
                if (dirty) {
                    dirty = false
                    _data.value = synchronized(lock) { HashMap(topics) }
                }
            }
        }
    }

    fun disconnect() {
        closedByUser = true
        jobs.forEach { it.cancel() }
        jobs.clear()
        ws?.close(1000, null)
        ws = null
        opened = false
        synchronized(lock) { topics.clear() }
        _data.value = emptyMap()
        _status.value = Status.Idle
    }

    /** Connection token plus the load balancer cookies (AWSALB). */
    private data class Negotiated(val id: String?, val cookie: String?)

    private suspend fun negotiate(): Negotiated? = withContext(Dispatchers.IO) {
        runCatching {
            val req = Request.Builder()
                .url("https://livetiming.formula1.com/signalrcore/negotiate?negotiateVersion=1")
                .post("".toRequestBody("text/plain".toMediaType()))
                .header("User-Agent", Http.UA)
                .build()
            Http.client.newCall(req).execute().use { r ->
                if (!r.isSuccessful) return@use null
                val j = Http.json.parseToJsonElement(r.body?.string() ?: "{}")
                val cookie = r.headers("Set-Cookie").map { it.substringBefore(';') }.filter { it.isNotBlank() }
                Negotiated(
                    j.child("connectionToken").str() ?: j.child("connectionId").str(),
                    cookie.joinToString("; ").ifBlank { null }
                )
            }
        }.getOrNull()
    }

    /** Failed attempts in a row before the first frame arrives; reset once connected. */
    @Volatile private var attempts = 0

    private fun failOrRetry(reason: String) {
        if (closedByUser) return
        attempts++
        if (attempts < 4) {
            _status.value = Status.Connecting
            jobs += scope.launch { delay(2000L * attempts); if (!closedByUser) open() }
        } else {
            _status.value = Status.Failed(reason)
        }
    }

    private suspend fun open() {
        _status.value = Status.Connecting
        opened = false
        val n = negotiate()
        val url = "wss://livetiming.formula1.com/signalrcore" + (n?.id?.let { "?id=" + java.net.URLEncoder.encode(it, "UTF-8") } ?: "")
        // The feed sits behind an AWS load balancer: without its sticky cookie the socket can land on
        // a server that does not know the connection token and gets a 404.
        val req = Request.Builder().url(url).header("User-Agent", Http.UA)
            .apply { n?.cookie?.let { header("Cookie", it) } }
            .build()
        val socket = Http.client.newWebSocket(req, listener)
        ws = socket
        jobs += scope.launch {
            delay(12_000)
            if (!opened && !closedByUser && ws === socket) {
                ws = null // so the onFailure from cancel() below is ignored
                socket.cancel()
                failOrRetry("timeout")
            }
        }
    }

    private fun send(ws: WebSocket, obj: JsonElement) {
        ws.send(obj.toString() + RS)
    }

    private val listener = object : WebSocketListener() {
        override fun onOpen(webSocket: WebSocket, response: Response) {
            send(webSocket, buildJsonObject { put("protocol", "json"); put("version", 1) })
        }

        override fun onMessage(webSocket: WebSocket, text: String) {
            for (raw in text.split(RS)) {
                if (raw.isBlank()) continue
                val m = runCatching { Http.json.parseToJsonElement(raw) }.getOrNull() as? JsonObject ?: continue
                val type = m["type"].int()
                when {
                    type == null && !opened -> {
                        // handshake accepted: subscribe and keep the connection alive
                        send(webSocket, buildJsonObject {
                            put("type", 1); put("target", "Subscribe"); put("invocationId", "0")
                            put("arguments", JsonArray(listOf(JsonArray(TOPICS.map { JsonPrimitive(it) }))))
                        })
                        jobs += scope.launch {
                            while (isActive) {
                                delay(15_000)
                                send(webSocket, buildJsonObject { put("type", 6) })
                            }
                        }
                    }
                    type == 3 && m["invocationId"].str() == "0" -> {
                        if (m["error"] != null && m["error"].str() != null) {
                            _status.value = Status.Failed(m["error"].str() ?: "error")
                            return
                        }
                        opened = true
                        attempts = 0
                        synchronized(lock) {
                            topics.clear()
                            m["result"].fields().forEach { (k, v) -> topics[k] = v }
                        }
                        dirty = true
                        _status.value = Status.Connected
                    }
                    type == 1 && m["target"].str() == "feed" -> {
                        val args = m["arguments"].items()
                        val topic = args.getOrNull(0).str() ?: continue
                        val payload = args.getOrNull(1) ?: continue
                        synchronized(lock) {
                            if (topic == "RaceControlMessages") {
                                val cur = topics[topic].child("Messages").items()
                                val add = payload.child("Messages").items()
                                topics[topic] = JsonObject(mapOf("Messages" to JsonArray(cur + add)))
                            } else {
                                topics[topic] = mergeJson(topics[topic], payload)
                            }
                        }
                        dirty = true
                    }
                    type == 7 -> webSocket.close(1000, null)
                }
            }
        }

        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
            if (closedByUser || webSocket !== ws) return
            if (!opened) {
                failOrRetry(response?.code?.let { "HTTP $it" } ?: t.message ?: "ws")
            } else {
                scope.launch { delay(3000); if (!closedByUser) open() }
            }
        }

        // The server ended the socket: answer the close so onClosed runs and we reconnect.
        // Without this the socket stays half-open and the screen silently stops updating.
        override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
            webSocket.close(1000, null)
        }

        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
            if (!closedByUser && opened && webSocket === ws) scope.launch { delay(3000); if (!closedByUser) open() }
        }
    }
}

data class TowerRow(
    val position: Int,
    val number: String,
    val tla: String,
    val name: String,
    val teamColor: String,
    val gap: String,
    val interval: String,
    val last: String,
    val best: String,
    val lastPersonalBest: Boolean,
    val bestOverall: Boolean,
    val compound: String?,
    val tyreAge: Int?,
    val stops: Int?,
    val inPit: Boolean,
    val out: Boolean
)

/** What the driver sheet shows: the tower row plus sectors, tyre stints and recent laps. */
data class DriverDetail(
    val row: TowerRow,
    /** S1..S3 of the latest lap; blank when not known yet. */
    val lastSectors: List<String>,
    val bestSectors: List<String>,
    val stints: List<StintInfo>,
    /** Newest first. */
    val laps: List<LapInfo>
)

data class StintInfo(val compound: String?, val laps: Int?)
data class LapInfo(val lap: Int, val time: String, val pit: Boolean = false)

data class RaceMsg(val time: String, val lap: Int?, val text: String, val flag: String?)

data class LiveView(
    val hasTiming: Boolean = false,
    val meeting: String = "",
    val session: String = "",
    val sessionType: String = "",
    val status: String = "",
    val lap: Int? = null,
    val totalLaps: Int? = null,
    val remaining: String? = null,
    /** When the feed took the [remaining] reading; with [clockRunning] the app counts down from there. */
    val clockUtc: java.time.Instant? = null,
    /** ExtrapolatedClock.Extrapolating: the clock is running and F1 only resends it when it stops or jumps. */
    val clockRunning: Boolean = false,
    val trackStatus: String = "",
    val rows: List<TowerRow> = emptyList(),
    val messages: List<RaceMsg> = emptyList(),
    val year: Int? = null,
    val countryCode: String? = null,
    /** Qualifying part from TimingData.SessionPart (1..3), when the feed sends it. */
    val part: Int? = null,
    /** How many times the session went to "Finished" (SessionData.StatusSeries): one per qualifying part. */
    val finishedCount: Int = 0
) {
    val isQuali: Boolean get() = sessionType == "Qualifying" || session.contains("Qualifying") || session.contains("Shootout")
    private val isSprintQuali: Boolean get() = session.contains("Sprint") || session.contains("Shootout")

    /**
     * In qualifying the feed sends "Finished" at the end of Q1 and Q2 too. It is only a break if fewer than
     * three parts have finished (StatusSeries), or, without that history, if SessionPart is still below 3.
     */
    val partBreak: Boolean get() = isQuali && status == "Finished" &&
        (if (finishedCount > 0) finishedCount < 3 else (part ?: 3) < 3)

    /** The session is really over (not just the end of Q1/Q2). */
    val over: Boolean get() = status == "Finalised" || status == "Ends" || (status == "Finished" && !partBreak)

    /** Current qualifying part (1..3): the one running, or the one that just ended during a break. */
    val currentPart: Int? get() {
        if (!isQuali) return null
        val fromSeries = when {
            finishedCount == 0 -> null
            status == "Finished" -> finishedCount
            else -> finishedCount + 1
        }
        return (fromSeries ?: part)?.coerceIn(1, 3)
    }

    /** "Q1".."Q3" or "SQ1".."SQ3"; null outside qualifying. */
    val partLabel: String? get() = currentPart?.let { (if (isSprintQuali) "SQ" else "Q") + it }
}

object LiveParser {
    /** The snapshot sends series as arrays and the updates as {"index": item}; accept both. */
    private fun seriesValues(e: JsonElement?): List<JsonElement> = when (e) {
        is JsonArray -> e.toList()
        is JsonObject -> e.entries.sortedBy { it.key.toIntOrNull() ?: Int.MAX_VALUE }.map { it.value }
        else -> emptyList()
    }

    private val timeFmt = DateTimeFormatter.ofPattern("HH:mm:ss").withZone(ZoneId.systemDefault())

    /** Sheet data for one driver; [history] is the lap times this phone saw (the feed only keeps the last one). */
    fun detail(t: Map<String, JsonElement>, row: TowerRow, history: Map<Int, String>?): DriverDetail {
        val line = t["TimingData"].child("Lines").fields()[row.number]
        return DriverDetail(
            row = row,
            lastSectors = line.child("Sectors").items().map { it.child("Value").str().orEmpty() },
            bestSectors = t["TimingStats"].child("Lines").fields()[row.number].child("BestSectors").items().map { it.child("Value").str().orEmpty() },
            stints = t["TimingAppData"].child("Lines").fields()[row.number].child("Stints").items()
                .map { StintInfo(it.child("Compound").str(), it.child("TotalLaps").int()) },
            laps = history.orEmpty().entries.sortedByDescending { it.key }.map { LapInfo(it.key, it.value) }
        )
    }

    fun parse(t: Map<String, JsonElement>): LiveView {
        val info = t["SessionInfo"]
        val drivers = t["DriverList"].fields()
        val lines = t["TimingData"].child("Lines").fields()
        val app = t["TimingAppData"].child("Lines").fields()
        val raw = lines.mapNotNull { (num, l) ->
            val pos = l.child("Position").int() ?: return@mapNotNull null
            val d = drivers[num]
            val st = app[num].child("Stints").items().lastOrNull()
            TowerRow(
                position = pos,
                number = num,
                tla = d.child("Tla").str() ?: num,
                name = d.child("LastName").str() ?: "",
                teamColor = d.child("TeamColour").str() ?: "8A929C",
                gap = l.child("GapToLeader").str() ?: l.path("Stats").items().lastOrNull().child("TimeDiffToFastest").str() ?: "",
                interval = l.path("IntervalToPositionAhead", "Value").str() ?: l.path("Stats").items().lastOrNull().child("TimeDifftoPositionAhead").str() ?: "",
                last = l.path("LastLapTime", "Value").str() ?: "",
                best = l.path("BestLapTime", "Value").str() ?: "",
                lastPersonalBest = l.path("LastLapTime", "PersonalFastest").bool(),
                bestOverall = false,
                compound = st.child("Compound").str(),
                tyreAge = st.child("TotalLaps").int(),
                stops = l.child("NumberOfPitStops").int(),
                inPit = l.child("InPit").bool(),
                out = l.child("Retired").bool() || l.child("Stopped").bool()
            )
        }.sortedBy { it.position }
        val fastest = raw.mapNotNull { r -> parseLapTime(r.best)?.let { r.number to it } }.minByOrNull { it.second }?.first
        val rows = raw.map { it.copy(bestOverall = it.number == fastest) }
        val msgs = t["RaceControlMessages"].child("Messages").items().map {
            RaceMsg(
                time = parseInstant(it.child("Utc").str())?.let { i -> timeFmt.format(i) } ?: "",
                lap = it.child("Lap").int(),
                text = it.child("Message").str() ?: "",
                flag = it.child("Flag").str()
            )
        }.reversed()
        return LiveView(
            hasTiming = rows.isNotEmpty(),
            meeting = info.path("Meeting", "Name").str() ?: "",
            session = info.child("Name").str() ?: "",
            sessionType = info.child("Type").str() ?: "",
            status = t["SessionStatus"].child("Status").str() ?: info.child("SessionStatus").str() ?: "",
            lap = t["LapCount"].child("CurrentLap").int(),
            totalLaps = t["LapCount"].child("TotalLaps").int(),
            remaining = t["ExtrapolatedClock"].child("Remaining").str(),
            clockUtc = parseInstant(t["ExtrapolatedClock"].child("Utc").str()),
            clockRunning = t["ExtrapolatedClock"].child("Extrapolating").bool(),
            trackStatus = t["TrackStatus"].child("Status").str() ?: "",
            rows = rows,
            messages = msgs,
            year = info.child("StartDate").str()?.take(4)?.toIntOrNull(),
            countryCode = info.path("Meeting", "Country", "Code").str(),
            part = t["TimingData"].child("SessionPart").int(),
            finishedCount = seriesValues(t["SessionData"].child("StatusSeries")).count { it.child("SessionStatus").str() == "Finished" }
        )
    }
}
