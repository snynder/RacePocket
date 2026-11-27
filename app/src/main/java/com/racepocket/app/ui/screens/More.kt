package com.racepocket.app.ui.screens

import android.Manifest
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatDelegate
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Calculate
import androidx.compose.material.icons.filled.DonutLarge
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Sensors
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.SwapHoriz
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import com.racepocket.app.ui.PullRefresh
import com.racepocket.app.ui.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.os.LocaleListCompat
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavHostController
import com.racepocket.app.R
import com.racepocket.app.data.AppSettings
import com.racepocket.app.data.Flags
import com.racepocket.app.data.Jolpica
import com.racepocket.app.data.Keys
import com.racepocket.app.data.Offline
import com.racepocket.app.data.Updater
import com.racepocket.app.data.Repo
import com.racepocket.app.data.SessionKind
import com.racepocket.app.data.Static
import com.racepocket.app.notify.Notifier
import com.racepocket.app.ui.BaseVM
import com.racepocket.app.ui.CardBox
import com.racepocket.app.ui.Divider
import com.racepocket.app.ui.LoadBox
import com.racepocket.app.ui.Load
import com.racepocket.app.ui.Muted
import com.racepocket.app.ui.Picker
import com.racepocket.app.ui.RankRow
import com.racepocket.app.ui.Routes
import com.racepocket.app.ui.ScreenHeader
import com.racepocket.app.ui.SectionTitle
import com.racepocket.app.ui.Segmented
import com.racepocket.app.ui.pal
import com.racepocket.app.ui.sessionName
import com.racepocket.app.ui.teamColor
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch

// ===================== MORE =====================

@Composable
fun MoreScreen(nav: NavHostController) {
    val items = listOf(
        Triple(Icons.Filled.Sensors, R.string.live_title to R.string.live_sub, Routes.LIVE),
        Triple(Icons.Filled.History, R.string.replay_title to R.string.replay_sub, Routes.REPLAY),
        Triple(Icons.Filled.Mic, R.string.nav_radio to R.string.radio_sub, Routes.RADIO),
        Triple(Icons.Filled.Speed, R.string.nav_telemetry to R.string.telemetry_sub, Routes.TELEMETRY),
        Triple(Icons.Filled.DonutLarge, R.string.nav_strategy to R.string.strategy_sub, Routes.STRATEGY),
        Triple(Icons.Filled.Calculate, R.string.nav_simulation to R.string.simulation_sub, Routes.SIMULATION),
        Triple(Icons.Filled.SwapHoriz, R.string.nav_compare to R.string.compare_sub, Routes.COMPARE),
        Triple(Icons.Filled.Star, R.string.nav_records to R.string.records_sub, Routes.RECORDS)
    )
    LazyColumn(contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item { ScreenHeader(stringResource(R.string.nav_more)) }
        item { Muted(stringResource(R.string.analysis).uppercase(), 12.sp) }
        item {
            CardBox(padding = 0.dp) {
                items.forEachIndexed { i, (icon, texts, route) ->
                    if (i > 0) Divider()
                    MenuRow(icon, stringResource(texts.first), stringResource(texts.second)) { nav.navigate(route) }
                }
            }
        }
        item { Muted(stringResource(R.string.app).uppercase(), 12.sp) }
        item {
            CardBox(padding = 0.dp) {
                MenuRow(Icons.Filled.Settings, stringResource(R.string.settings), stringResource(R.string.settings_sub)) { nav.navigate(Routes.SETTINGS) }
            }
        }
        item { Muted(stringResource(R.string.disclaimer), 11.sp) }
    }
}

@Composable
private fun MenuRow(icon: ImageVector, title: String, sub: String, onClick: () -> Unit) {
    val p = pal()
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        Box(Modifier.size(38.dp).clip(RoundedCornerShape(10.dp)).background(p.card2), contentAlignment = Alignment.Center) {
            Icon(icon, contentDescription = null, tint = p.accentText, modifier = Modifier.size(20.dp))
        }
        Column(Modifier.weight(1f)) {
            Text(title, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = p.text)
            Muted(sub, 11.sp)
        }
        Text("›", fontSize = 18.sp, color = p.muted)
    }
}

// ===================== SETTINGS =====================

private val LANGS = listOf(
    "" to null, "pt-BR" to "Português", "en" to "English", "es" to "Español", "fr" to "Français", "de" to "Deutsch",
    "it" to "Italiano", "ja" to "日本語", "zh" to "中文", "ru" to "Русский", "ar" to "العربية"
)

@Composable
fun SettingsScreen(nav: NavHostController, s: AppSettings) {
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val p = pal()
    fun edit(block: (androidx.datastore.preferences.core.MutablePreferences) -> Unit, reschedule: Boolean = false) {
        scope.launch {
            Repo.settings.edit(block)
            if (reschedule) Notifier.rescheduleSoon(context)
        }
    }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        edit({ it[Keys.notifEnabled] = granted }, true)
    }
    val resultsPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        edit({ it[Keys.resultsEnabled] = granted }, true)
    }
    var transcripts by remember { mutableStateOf(s.transcriptsUrl) }
    var whisper by remember { mutableStateOf(s.whisperKey) }
    var advanced by remember { mutableStateOf(false) }

    LazyColumn(contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item { ScreenHeader(stringResource(R.string.settings), onBack = { nav.popBackStack() }) }

        item { Muted(stringResource(R.string.appearance).uppercase(), 12.sp) }
        item {
            CardBox {
                Text(stringResource(R.string.theme), fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = p.text)
                Box(Modifier.padding(top = 10.dp)) {
                    val modes = listOf("system", "dark", "light")
                    Segmented(
                        listOf(stringResource(R.string.theme_system), stringResource(R.string.theme_dark), stringResource(R.string.theme_light)),
                        modes.indexOf(s.theme).coerceAtLeast(0)
                    ) { i -> edit({ it[Keys.theme] = modes[i] }) }
                }
            }
        }
        item {
            CardBox(padding = 0.dp) {
                ToggleRow(stringResource(R.string.uppercase_title), stringResource(R.string.uppercase_sub), s.uppercase) { on -> edit({ it[Keys.uppercase] = on }) }
            }
        }
        item {
            val current = AppCompatDelegate.getApplicationLocales().toLanguageTags()
            val systemLabel = stringResource(R.string.lang_system)
            CardBox {
                Picker(stringResource(R.string.language), LANGS, LANGS.firstOrNull { it.first.equals(current, true) } ?: LANGS.first(), { it.second ?: systemLabel }, Modifier.fillMaxWidth()) { (tag, _) ->
                    AppCompatDelegate.setApplicationLocales(if (tag.isEmpty()) LocaleListCompat.getEmptyLocaleList() else LocaleListCompat.forLanguageTags(tag))
                }
                Box(Modifier.padding(top = 10.dp)) {
                    val countries = (Static.BROADCAST.keys + s.effectiveCountry).distinct().sortedBy { Static.countryName(it) }
                    val auto = stringResource(R.string.auto_detect)
                    Picker(stringResource(R.string.broadcast_country), listOf("") + countries, s.country ?: "", {
                        if (it.isEmpty()) "$auto (${Static.countryName(Static.deviceCountry())})" else "${Flags.emoji(it)} ${Static.countryName(it)}"
                    }, Modifier.fillMaxWidth()) { cc ->
                        edit({ prefs ->
                            if (cc.isEmpty()) {
                                prefs.remove(Keys.country)
                            } else {
                                prefs[Keys.country] = cc
                            }
                            Unit
                        })
                    }
                }
                Muted(stringResource(R.string.lang_note), 11.sp, Modifier.padding(top = 8.dp))
            }
        }

        item { Muted(stringResource(R.string.notifications).uppercase(), 12.sp) }
        item {
            CardBox(padding = 0.dp) {
                ToggleRow(stringResource(R.string.notif_enable), stringResource(R.string.notif_enable_sub), s.notifEnabled) { on ->
                    if (on && Build.VERSION.SDK_INT >= 33) permission.launch(Manifest.permission.POST_NOTIFICATIONS)
                    else edit({ it[Keys.notifEnabled] = on }, true)
                }
                if (s.notifEnabled) {
                    Divider()
                    SessionLevels(s.notif, Keys::notif) { b -> edit(b, true) }
                    Divider()
                    Box(Modifier.padding(14.dp)) {
                        val leads = listOf(5, 15, 30, 60)
                        Picker(stringResource(R.string.notif_lead), leads, s.leadMinutes, { stringResource0(it) }, Modifier.fillMaxWidth()) { m ->
                            edit({ it[Keys.lead] = m }, true)
                        }
                    }
                }
            }
        }

        item { Muted(stringResource(R.string.results_notif_title).uppercase(), 12.sp) }
        item {
            CardBox(padding = 0.dp) {
                ToggleRow(stringResource(R.string.results_notif_enable), stringResource(R.string.results_notif_enable_sub), s.resultsEnabled) { on ->
                    if (on && Build.VERSION.SDK_INT >= 33) resultsPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
                    else edit({ it[Keys.resultsEnabled] = on }, true)
                }
                if (s.resultsEnabled) {
                    Divider()
                    SessionLevels(s.results, Keys::results) { b -> edit(b, true) }
                }
            }
        }

        item { Muted(stringResource(R.string.banner_title).uppercase(), 12.sp) }
        item {
            CardBox(padding = 0.dp) {
                SessionLevels(s.banner, Keys::banner) { b -> edit(b) }
            }
        }

        item { Muted(stringResource(R.string.countdown_title).uppercase(), 12.sp) }
        item {
            CardBox(padding = 0.dp) {
                ToggleRow(stringResource(R.string.countdown_enable), stringResource(R.string.countdown_enable_sub), s.countdownEnabled) { on ->
                    edit({ it[Keys.countdownEnabled] = on })
                }
                if (s.countdownEnabled) {
                    Divider()
                    SessionLevels(s.countdown, Keys::countdown) { b -> edit(b) }
                }
            }
        }

        item { Muted(stringResource(R.string.offline_title).uppercase(), 12.sp) }
        item { OfflineCard() }

        item { Muted(stringResource(R.string.updates_title).uppercase(), 12.sp) }
        item { CardBox { UpdateBox(s, showUpToDate = true) } }

        // technical fields stay out of the way, folded at the end
        item {
            Row(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).clickable { advanced = !advanced }.padding(vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(Modifier.weight(1f)) {
                    Muted(stringResource(R.string.advanced).uppercase(), 12.sp)
                    Muted(stringResource(R.string.advanced_sub), 11.sp)
                }
                Text(if (advanced) "▴" else "▾", fontSize = 16.sp, color = p.muted)
            }
        }
        if (advanced) item { Muted(stringResource(R.string.nav_radio).uppercase(), 12.sp) }
        if (advanced) item {
            CardBox {
                OutlinedTextField(
                    value = transcripts, onValueChange = { transcripts = it }, singleLine = true, modifier = Modifier.fillMaxWidth(),
                    label = { Text(stringResource(R.string.transcripts_url)) }
                )
                Muted(stringResource(R.string.transcripts_url_note), 11.sp, Modifier.padding(top = 6.dp))
                OutlinedTextField(
                    value = whisper, onValueChange = { whisper = it }, singleLine = true, modifier = Modifier.fillMaxWidth().padding(top = 10.dp),
                    label = { Text(stringResource(R.string.whisper_key)) }
                )
                Muted(stringResource(R.string.whisper_key_note), 11.sp, Modifier.padding(top = 6.dp))
                Text(
                    stringResource(R.string.save), color = p.accentText, fontWeight = FontWeight.Bold, fontSize = 14.sp,
                    modifier = Modifier.padding(top = 10.dp).clickable {
                        edit({ it[Keys.transcripts] = transcripts.trim(); it[Keys.whisper] = whisper.trim() })
                    }.padding(6.dp)
                )
            }
        }

        if (advanced) item { Muted(stringResource(R.string.updates_title).uppercase(), 12.sp) }
        if (advanced) item {
            CardBox {
                var repo by remember { mutableStateOf(s.updateRepo) }
                OutlinedTextField(
                    value = repo, onValueChange = { repo = it }, singleLine = true, modifier = Modifier.fillMaxWidth(),
                    label = { Text(stringResource(R.string.update_repo)) },
                    placeholder = { androidx.compose.material3.Text(Updater.repo(s).ifEmpty { "usuario/RacePocket" }) }
                )
                Muted(stringResource(R.string.update_repo_note), 11.sp, Modifier.padding(top = 6.dp))
                Text(
                    stringResource(R.string.save), color = p.accentText, fontWeight = FontWeight.Bold, fontSize = 14.sp,
                    modifier = Modifier.padding(top = 10.dp).clickable { edit({ it[Keys.updateRepo] = repo.trim() }) }.padding(6.dp)
                )
            }
        }
    }
}

@Composable
private fun OfflineCard() {
    val p = pal()
    val st by Offline.state.collectAsState()
    CardBox {
        Muted(stringResource(R.string.offline_sub), 12.sp)
        when (val x = st) {
            is Offline.State.Running -> {
                LinearProgressIndicator(
                    progress = { if (x.total > 0) x.done.toFloat() / x.total else 0f },
                    modifier = Modifier.fillMaxWidth().padding(top = 12.dp), color = p.accent, trackColor = p.card2
                )
                Muted(stringResource(R.string.offline_progress, x.done, x.total), 11.sp, Modifier.padding(top = 6.dp))
            }
            else -> {
                if (x is Offline.State.Done) Muted(
                    if (x.failed == 0) stringResource(R.string.offline_done) else stringResource(R.string.offline_done_partial, x.failed),
                    11.sp, Modifier.padding(top = 8.dp)
                )
                Text(
                    stringResource(R.string.offline_download), color = p.accentText, fontWeight = FontWeight.Bold, fontSize = 14.sp,
                    modifier = Modifier.padding(top = 10.dp).clickable { Offline.downloadSeason() }.padding(6.dp)
                )
            }
        }
    }
}

/** Update status and action. Used in Settings and, when there is a new version, at the top of Home. */
@Composable
fun UpdateBox(s: AppSettings, showUpToDate: Boolean) {
    val p = pal()
    val context = LocalContext.current
    val st by Updater.state.collectAsState()
    // a new version found (available, downloading or ready): show the new one first, then the installed one
    val incoming = when (val x = st) {
        is Updater.State.Available -> x.release
        is Updater.State.Downloading -> x.release
        is Updater.State.Ready -> x.release
        else -> null
    }
    Column {
        if (incoming != null) {
            Text(stringResource(R.string.update_new_title, incoming.version), fontSize = 15.sp, fontWeight = FontWeight.Bold, color = p.text)
            Muted(stringResource(R.string.update_current, Updater.currentVersion), 12.sp, Modifier.padding(top = 2.dp))
        } else {
            Text(stringResource(R.string.version_n, Updater.currentVersion), fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = p.text)
        }
        val r: Pair<String?, Pair<String, () -> Unit>?> = when (val x = st) {
            is Updater.State.Idle -> null to (stringResource(R.string.update_check) to { Updater.check(s) })
            is Updater.State.Checking -> stringResource(R.string.update_checking) to null
            is Updater.State.UpToDate -> (if (showUpToDate) stringResource(R.string.update_none) else null) to (stringResource(R.string.update_check) to { Updater.check(s) })
            is Updater.State.Available -> null to (stringResource(R.string.update_install) to { Updater.download(context, x.release) })
            is Updater.State.Downloading -> stringResource(R.string.update_downloading, x.percent) to null
            is Updater.State.Ready -> stringResource(R.string.update_ready, x.release.version) to (stringResource(R.string.update_install) to { Updater.install(context, x.file); Unit })
            is Updater.State.Failed -> (if (x.reason == "no_repo") stringResource(R.string.update_no_repo) else stringResource(R.string.update_failed) + " (" + x.reason + ")") to
                (stringResource(R.string.update_check) to { Updater.check(s) })
        }
        val msg = r.first
        val action = r.second
        if (st is Updater.State.Downloading) {
            LinearProgressIndicator(
                progress = { (st as Updater.State.Downloading).percent / 100f },
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp), color = p.accent, trackColor = p.card2
            )
        }
        if (msg != null) Muted(msg, 12.sp, Modifier.padding(top = 6.dp))
        if (action != null) {
            Text(
                action.first, color = p.accentText, fontWeight = FontWeight.Bold, fontSize = 14.sp,
                modifier = Modifier.padding(top = 8.dp).clickable { action.second() }.padding(6.dp)
            )
        }
    }
}

private fun stringResource0(minutes: Int): String = "$minutes min"

/** All sessions › free practice (with FP1–FP3 inside) › sprint quali › sprint › quali › race. Shared by alerts and the live banner. */
@Composable
private fun SessionLevels(
    values: Map<SessionKind, Boolean>,
    key: (SessionKind) -> androidx.datastore.preferences.core.Preferences.Key<Boolean>,
    edit: ((androidx.datastore.preferences.core.MutablePreferences) -> Unit) -> Unit
) {
    fun set(kinds: List<SessionKind>, on: Boolean) = edit { pr -> kinds.forEach { pr[key(it)] = on } }
    val all = SessionKind.entries
    ToggleRow(stringResource(R.string.notif_all), stringResource(R.string.notif_all_sub), all.all { values[it] == true }) { on -> set(all, on) }
    val fps = listOf(SessionKind.FP1, SessionKind.FP2, SessionKind.FP3)
    Divider()
    ToggleRow(stringResource(R.string.notif_practice), stringResource(R.string.notif_practice_sub), fps.all { values[it] == true }) { on -> set(fps, on) }
    fps.forEach { k ->
        Divider()
        ToggleRow(sessionName(k), null, values[k] == true, indent = true) { on -> set(listOf(k), on) }
    }
    Divider()
    ToggleRow(stringResource(R.string.s_sprint_quali), stringResource(R.string.notif_sprint_quali_sub), values[SessionKind.SPRINT_QUALI] == true) { on -> set(listOf(SessionKind.SPRINT_QUALI), on) }
    Divider()
    ToggleRow(stringResource(R.string.s_sprint), stringResource(R.string.notif_sprint_race_sub), values[SessionKind.SPRINT] == true) { on -> set(listOf(SessionKind.SPRINT), on) }
    Divider()
    ToggleRow(stringResource(R.string.s_quali), null, values[SessionKind.QUALI] == true) { on -> set(listOf(SessionKind.QUALI), on) }
    Divider()
    ToggleRow(stringResource(R.string.s_race), null, values[SessionKind.RACE] == true) { on -> set(listOf(SessionKind.RACE), on) }
}

@Composable
private fun ToggleRow(title: String, sub: String?, checked: Boolean, indent: Boolean = false, onChange: (Boolean) -> Unit) {
    val p = pal()
    Row(
        Modifier.fillMaxWidth().clickable { onChange(!checked) }.heightIn(min = 52.dp)
            .padding(start = if (indent) 34.dp else 14.dp, end = 14.dp, top = 8.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, fontSize = 14.sp, fontWeight = if (indent) FontWeight.Normal else FontWeight.SemiBold, color = p.text)
            if (sub != null) Muted(sub, 11.sp)
        }
        Switch(
            checked = checked, onCheckedChange = onChange,
            colors = SwitchDefaults.colors(checkedTrackColor = p.accent, checkedThumbColor = Color.White)
        )
    }
}

// ===================== RECORDS =====================

class RecordsVM : BaseVM() {
    val data = MutableStateFlow<Load<Jolpica.Records>>(Load.Loading)
    init { refresh() }
    fun refresh() { load(data) { Jolpica.records() } }
}

@Composable
fun RecordsScreen(nav: NavHostController) {
    val vm: RecordsVM = viewModel()
    val data by vm.data.collectAsState()
    var tab by remember { mutableStateOf(0) }
    val p = pal()
    PullRefresh(vm, { vm.refresh() }) {
        LazyColumn(contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            item { ScreenHeader(stringResource(R.string.nav_records), onBack = { nav.popBackStack() }) }
            item { Muted(stringResource(R.string.records_lead), 12.sp) }
            item {
                LoadBox(data, onRetry = { vm.refresh() }) { r ->
                    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Segmented(listOf(stringResource(R.string.drivers), stringResource(R.string.constructors), stringResource(R.string.champions)), tab) { tab = it }
                        when (tab) {
                            0 -> {
                                RecordList(stringResource(R.string.most_titles)) { r.titles.forEachIndexed { i, (d, n) -> RankRow("${i + 1}", p.card2, d.flag, d.name, null, "$n") } }
                                RecordList(stringResource(R.string.most_wins)) { r.wins.forEachIndexed { i, (d, n) -> RankRow("${i + 1}", p.card2, d.flag, d.name, null, "$n") } }
                                RecordList(stringResource(R.string.most_pole_starts)) { r.poleStarts.forEachIndexed { i, (d, n) -> RankRow("${i + 1}", p.card2, d.flag, d.name, null, "$n") } }
                                RecordList(stringResource(R.string.win_streaks)) { r.streaks.forEachIndexed { i, (d, n, years) -> RankRow("${i + 1}", p.card2, d.flag, d.name, years, "$n") } }
                            }
                            1 -> {
                                RecordList(stringResource(R.string.most_team_titles)) { r.teamTitles.forEachIndexed { i, (t, n) -> RankRow("${i + 1}", teamColor(t.color), t.flag, t.name, null, "$n") } }
                                RecordList(stringResource(R.string.most_team_wins)) { r.teamWins.forEachIndexed { i, (t, n) -> RankRow("${i + 1}", teamColor(t.color), t.flag, t.name, null, "$n") } }
                            }
                            else -> RecordList(stringResource(R.string.champions_by_season)) {
                                r.champions.forEach { c ->
                                    RankRow("${c.year}", teamColor(c.team?.color ?: 0xFF8A929C), c.driver.flag, c.driver.name, listOfNotNull(c.team?.name, c.teamChampion?.let { "🏆 " + it.name }).joinToString(" · "), "")
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
private fun RecordList(title: String, content: @Composable () -> Unit) {
    CardBox(padding = 0.dp) {
        SectionTitle(title)
        Box(Modifier.padding(bottom = 6.dp)) { Column { content() } }
    }
}
