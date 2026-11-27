package com.racepocket.app.data

import android.content.Context
import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

private val Context.dataStore by preferencesDataStore("settings")

data class AppSettings(
    val theme: String = "system",
    val country: String? = null,
    val notifEnabled: Boolean = false,
    val notif: Map<SessionKind, Boolean> = SessionKind.entries.associateWith { it == SessionKind.QUALI || it == SessionKind.RACE || it == SessionKind.SPRINT || it == SessionKind.SPRINT_QUALI },
    val leadMinutes: Int = 15,
    val banner: Map<SessionKind, Boolean> = SessionKind.entries.associateWith { true },
    val resultsEnabled: Boolean = false,
    val results: Map<SessionKind, Boolean> = SessionKind.entries.associateWith { it == SessionKind.QUALI || it == SessionKind.RACE || it == SessionKind.SPRINT || it == SessionKind.SPRINT_QUALI },
    val countdownEnabled: Boolean = true,
    val countdown: Map<SessionKind, Boolean> = SessionKind.entries.associateWith { true },
    val transcriptsUrl: String = "",
    val whisperKey: String = "",
    val uppercase: Boolean = true,
    val updateRepo: String = "",
    val liveMapCollapsed: Boolean = false,
    /** Routes of the bottom bar shortcuts between Home and More (up to 3, in order). */
    val bottomBar: List<String> = listOf("standings", "season", "circuits")
) {
    val effectiveCountry: String get() = country ?: Static.deviceCountry()
}

object Keys {
    val theme = stringPreferencesKey("theme")
    val country = stringPreferencesKey("country")
    val notifEnabled = booleanPreferencesKey("notif_enabled")
    val lead = intPreferencesKey("lead_minutes")
    val transcripts = stringPreferencesKey("transcripts_url")
    val whisper = stringPreferencesKey("whisper_key")
    val uppercase = booleanPreferencesKey("uppercase")
    val updateRepo = stringPreferencesKey("update_repo")
    val liveMapCollapsed = booleanPreferencesKey("live_map_collapsed")
    /** The first-launch notification question was handled (asked, or skipped for an update). */
    val onboarded = booleanPreferencesKey("onboarded")
    val bottomBar = stringPreferencesKey("bottom_bar")
    fun notif(k: SessionKind) = booleanPreferencesKey("notif_" + k.name)
    fun banner(k: SessionKind) = booleanPreferencesKey("banner_" + k.name)
    val resultsEnabled = booleanPreferencesKey("results_enabled")
    fun results(k: SessionKind) = booleanPreferencesKey("results_" + k.name)
    val countdownEnabled = booleanPreferencesKey("countdown_enabled")
    fun countdown(k: SessionKind) = booleanPreferencesKey("countdown_" + k.name)
}

class SettingsRepo(private val context: Context) {
    val flow: Flow<AppSettings> = context.dataStore.data.map { p -> read(p) }

    private fun read(p: Preferences): AppSettings {
        val d = AppSettings()
        return AppSettings(
            theme = p[Keys.theme] ?: d.theme,
            country = p[Keys.country],
            notifEnabled = p[Keys.notifEnabled] ?: d.notifEnabled,
            notif = SessionKind.entries.associateWith { p[Keys.notif(it)] ?: d.notif.getValue(it) },
            leadMinutes = p[Keys.lead] ?: d.leadMinutes,
            banner = SessionKind.entries.associateWith { p[Keys.banner(it)] ?: true },
            resultsEnabled = p[Keys.resultsEnabled] ?: d.resultsEnabled,
            results = SessionKind.entries.associateWith { p[Keys.results(it)] ?: d.results.getValue(it) },
            countdownEnabled = p[Keys.countdownEnabled] ?: d.countdownEnabled,
            countdown = SessionKind.entries.associateWith { p[Keys.countdown(it)] ?: d.countdown.getValue(it) },
            transcriptsUrl = p[Keys.transcripts] ?: d.transcriptsUrl,
            whisperKey = p[Keys.whisper] ?: d.whisperKey,
            uppercase = p[Keys.uppercase] ?: d.uppercase,
            updateRepo = p[Keys.updateRepo] ?: d.updateRepo,
            liveMapCollapsed = p[Keys.liveMapCollapsed] ?: d.liveMapCollapsed,
            bottomBar = p[Keys.bottomBar]?.split(',')?.filter { it.isNotBlank() } ?: d.bottomBar
        )
    }

    suspend fun current(): AppSettings = flow.first()

    suspend fun onboarded(): Boolean = context.dataStore.data.first()[Keys.onboarded] == true

    suspend fun edit(block: (MutablePreferences) -> Unit) {
        context.dataStore.edit { block(it) }
    }
}

object Repo {
    lateinit var settings: SettingsRepo
        private set

    fun init(context: Context) {
        settings = SettingsRepo(context.applicationContext)
    }
}
