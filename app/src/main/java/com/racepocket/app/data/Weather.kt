package com.racepocket.app.data

import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneOffset
import java.time.temporal.ChronoUnit

/** Forecast for the time of one session: weather and temperature at its start, highest rain chance while it runs. */
data class SessionWeather(val code: Int, val temp: Double?, val rain: Int?, val day: Boolean)

/** Open-Meteo: free hourly forecast, up to 16 days ahead (and the recent past). */
object Weather {
    suspend fun forSessions(lat: Double, lon: Double, sessions: List<SessionTime>): Map<SessionKind, SessionWeather>? {
        if (sessions.isEmpty()) return null
        val today = LocalDate.now(ZoneOffset.UTC)
        val from = sessions.minOf { it.start }.atOffset(ZoneOffset.UTC).toLocalDate()
        val to = sessions.maxOf { it.end }.atOffset(ZoneOffset.UTC).toLocalDate()
        if (to.isBefore(today.minusDays(60)) || from.isAfter(today.plusDays(15))) return null
        val start = maxOf(from, today.minusDays(60))
        val end = minOf(to, today.plusDays(15))
        // times in GMT, so they line up with the session instants
        val j = Http.getJson(
            "https://api.open-meteo.com/v1/forecast?latitude=$lat&longitude=$lon" +
                "&hourly=weather_code,temperature_2m,precipitation_probability,is_day" +
                "&timezone=GMT&start_date=$start&end_date=$end"
        ).child("hourly")
        val times = j.child("time").arr().map { LocalDateTime.parse(it.str()).toInstant(ZoneOffset.UTC) }
        if (times.isEmpty()) return null
        val codes = j.child("weather_code").arr()
        val temps = j.child("temperature_2m").arr()
        val rains = j.child("precipitation_probability").arr()
        val days = j.child("is_day").arr()
        val out = HashMap<SessionKind, SessionWeather>()
        for (s in sessions) {
            val first = s.start.truncatedTo(ChronoUnit.HOURS)
            val idx = times.indices.filter { !times[it].isBefore(first) && times[it].isBefore(s.end) }
            val i0 = idx.firstOrNull() ?: continue
            out[s.kind] = SessionWeather(
                code = idx.mapNotNull { codes.getOrNull(it).int() }.maxOrNull() ?: 0, // the worst sky during the session
                temp = temps.getOrNull(i0).dbl(),
                rain = idx.mapNotNull { rains.getOrNull(it).int() }.maxOrNull(),
                day = days.getOrNull(i0).int() != 0
            )
        }
        return out
    }
}
