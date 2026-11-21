package com.racepocket.app.data

import java.time.Instant

/** OpenF1: sessions, laps, tyres, radios, race control since 2023. Free outside live sessions. */
object OpenF1 {
    private const val B = "https://api.openf1.org/v1"

    data class Meeting(val key: Int, val name: String, val country: String, val countryCode: String?, val circuitShort: String, val start: Instant?, val year: Int)
    data class Session(val key: Int, val meetingKey: Int, val name: String, val type: String, val start: Instant?, val end: Instant?)
    data class Driver(val number: Int, val tla: String, val fullName: String, val lastName: String, val team: String, val color: String, val headshot: String?, val countryCode: String?)
    data class Lap(val driver: Int, val lap: Int, val start: Long?, val duration: Double?, val s1: Double?, val s2: Double?, val s3: Double?, val pitOut: Boolean)
    data class Pos(val driver: Int, val t: Long, val position: Int)
    data class Interval(val driver: Int, val t: Long, val gap: String, val interval: String)
    data class Stint(val driver: Int, val number: Int, val lapStart: Int, val lapEnd: Int, val compound: String, val ageAtStart: Int)
    data class Pit(val driver: Int, val lap: Int, val duration: Double?, val t: Long)
    data class RaceControl(val t: Long, val lap: Int?, val message: String, val flag: String?)
    data class Radio(val t: Long, val driver: Int, val url: String)
    data class SessionResult(val position: Int?, val driver: Int, val duration: Double?, val gap: String, val laps: Int?, val dnf: Boolean)

    private fun ms(s: String?): Long? = parseInstant(s)?.toEpochMilli()

    private fun gapText(e: kotlinx.serialization.json.JsonElement?): String {
        val d = e.dbl()
        return when {
            e == null -> ""
            d != null -> fmtGap(d)
            else -> e.str() ?: ""
        }
    }

    suspend fun meetings(year: Int): List<Meeting> =
        Http.getJson("$B/meetings?year=$year").arr().map {
            Meeting(
                key = it.child("meeting_key").int() ?: 0,
                name = it.child("meeting_name").str() ?: "",
                country = it.child("country_name").str() ?: "",
                countryCode = it.child("country_code").str(),
                circuitShort = it.child("circuit_short_name").str() ?: "",
                start = parseInstant(it.child("date_start").str()),
                year = it.child("year").int() ?: year
            )
        }.filter { !it.name.contains("Testing", true) }.sortedBy { it.start }

    suspend fun sessions(meetingKey: Int): List<Session> =
        Http.getJson("$B/sessions?meeting_key=$meetingKey").arr().map {
            Session(
                key = it.child("session_key").int() ?: 0,
                meetingKey = meetingKey,
                name = it.child("session_name").str() ?: "",
                type = it.child("session_type").str() ?: "",
                start = parseInstant(it.child("date_start").str()),
                end = parseInstant(it.child("date_end").str())
            )
        }.sortedBy { it.start }

    /** Every session of a season (used to find a session from its start time). */
    suspend fun sessionsOfYear(year: Int): List<Session> =
        Http.getJson("$B/sessions?year=$year").arr().map {
            Session(
                key = it.child("session_key").int() ?: 0,
                meetingKey = it.child("meeting_key").int() ?: 0,
                name = it.child("session_name").str() ?: "",
                type = it.child("session_type").str() ?: "",
                start = parseInstant(it.child("date_start").str()),
                end = parseInstant(it.child("date_end").str())
            )
        }.sortedBy { it.start }

    suspend fun drivers(sk: Int): List<Driver> =
        Http.getJson("$B/drivers?session_key=$sk").arr().map {
            Driver(
                number = it.child("driver_number").int() ?: 0,
                tla = it.child("name_acronym").str() ?: "",
                fullName = it.child("full_name").str() ?: "",
                lastName = it.child("last_name").str() ?: "",
                team = it.child("team_name").str() ?: "",
                color = it.child("team_colour").str() ?: "8A929C",
                headshot = it.child("headshot_url").str(),
                countryCode = it.child("country_code").str()
            )
        }.distinctBy { it.number }

    /** Headshots of the latest session's drivers, by three-letter code. */
    suspend fun latestHeadshots(): Map<String, String> = runCatching {
        Http.getJson("$B/drivers?session_key=latest").arr().mapNotNull {
            val code = it.child("name_acronym").str() ?: return@mapNotNull null
            val url = it.child("headshot_url").str() ?: return@mapNotNull null
            code to url
        }.toMap()
    }.getOrDefault(emptyMap())

    suspend fun laps(sk: Int): List<Lap> =
        Http.getJson("$B/laps?session_key=$sk").arr().map {
            Lap(
                driver = it.child("driver_number").int() ?: 0,
                lap = it.child("lap_number").int() ?: 0,
                start = ms(it.child("date_start").str()),
                duration = it.child("lap_duration").dbl(),
                s1 = it.child("duration_sector_1").dbl(),
                s2 = it.child("duration_sector_2").dbl(),
                s3 = it.child("duration_sector_3").dbl(),
                pitOut = it.child("is_pit_out_lap").bool()
            )
        }

    suspend fun positions(sk: Int): List<Pos> =
        Http.getJson("$B/position?session_key=$sk").arr().mapNotNull {
            Pos(it.child("driver_number").int() ?: return@mapNotNull null, ms(it.child("date").str()) ?: return@mapNotNull null, it.child("position").int() ?: return@mapNotNull null)
        }

    suspend fun intervals(sk: Int): List<Interval> =
        Http.getJson("$B/intervals?session_key=$sk").arr().mapNotNull {
            Interval(
                it.child("driver_number").int() ?: return@mapNotNull null,
                ms(it.child("date").str()) ?: return@mapNotNull null,
                gapText(it.child("gap_to_leader")),
                gapText(it.child("interval"))
            )
        }

    suspend fun stints(sk: Int): List<Stint> =
        Http.getJson("$B/stints?session_key=$sk").arr().map {
            Stint(
                driver = it.child("driver_number").int() ?: 0,
                number = it.child("stint_number").int() ?: 0,
                lapStart = it.child("lap_start").int() ?: 1,
                lapEnd = it.child("lap_end").int() ?: (it.child("lap_start").int() ?: 1),
                compound = it.child("compound").str() ?: "UNKNOWN",
                ageAtStart = it.child("tyre_age_at_start").int() ?: 0
            )
        }

    suspend fun pits(sk: Int): List<Pit> =
        Http.getJson("$B/pit?session_key=$sk").arr().map {
            Pit(it.child("driver_number").int() ?: 0, it.child("lap_number").int() ?: 0, it.child("pit_duration").dbl() ?: it.child("stop_duration").dbl(), ms(it.child("date").str()) ?: 0L)
        }

    suspend fun raceControl(sk: Int): List<RaceControl> =
        Http.getJson("$B/race_control?session_key=$sk").arr().mapNotNull {
            RaceControl(ms(it.child("date").str()) ?: return@mapNotNull null, it.child("lap_number").int(), it.child("message").str() ?: "", it.child("flag").str())
        }.sortedBy { it.t }

    suspend fun radios(sk: Int): List<Radio> =
        Http.getJson("$B/team_radio?session_key=$sk").arr().mapNotNull {
            Radio(ms(it.child("date").str()) ?: 0L, it.child("driver_number").int() ?: 0, it.child("recording_url").str() ?: return@mapNotNull null)
        }.sortedByDescending { it.t }

    suspend fun sessionResult(sk: Int): List<SessionResult> =
        Http.getJson("$B/session_result?session_key=$sk").arr().map {
            val dur = it.child("duration").let { d -> d.dbl() ?: d.arr().lastOrNull { x -> x.dbl() != null }.dbl() }
            val gap = it.child("gap_to_leader").let { g -> if (g.arr().isNotEmpty()) gapText(g.arr().lastOrNull { x -> x.str() != null }) else gapText(g) }
            SessionResult(
                position = it.child("position").int(),
                driver = it.child("driver_number").int() ?: 0,
                duration = dur,
                gap = gap,
                laps = it.child("number_of_laps").int(),
                dnf = it.child("dnf").bool() || it.child("dns").bool() || it.child("dsq").bool()
            )
        }.sortedBy { it.position ?: 99 }
}
