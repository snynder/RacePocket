package com.racepocket.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.draw.clip
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.racepocket.app.R
import com.racepocket.app.data.live.DriverDetail
import com.racepocket.app.data.live.RaceMsg
import com.racepocket.app.data.live.TowerRow

/** Column titles; a tap shows what each abbreviation means. */
@Composable
fun TowerHeader() {
    val m = pal().muted
    var legend by remember { mutableStateOf(false) }
    // one line per label, never wrapped: narrow columns would otherwise break words into pieces
    @Composable
    fun H(text: String, mod: Modifier, align: TextAlign) =
        Text(text, fontSize = 10.sp, color = m, modifier = mod, textAlign = align, maxLines = 1, softWrap = false)
    if (legend) {
        Text(
            stringResource(R.string.tower_legend), fontSize = 11.sp, color = pal().text,
            modifier = Modifier.fillMaxWidth().clickable { legend = false }.background(pal().card2).padding(horizontal = 12.dp, vertical = 10.dp)
        )
    }
    Row(Modifier.fillMaxWidth().clickable { legend = !legend }.padding(horizontal = 10.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        H(stringResource(R.string.pos_short), Modifier.width(POS_W), TextAlign.Center)
        Box(Modifier.width(BAR_W))
        H(stringResource(R.string.driver), Modifier.width(DRIVER_W), TextAlign.Start)
        H(stringResource(R.string.gap_interval), Modifier.weight(1f), TextAlign.End)
        H(stringResource(R.string.last_best), Modifier.width(84.dp), TextAlign.End)
        H(stringResource(R.string.tyre), Modifier.padding(start = TYRE_GAP).width(TYRE_W), TextAlign.Center)
        H(stringResource(R.string.tyre_laps), Modifier.width(LAPS_W), TextAlign.Center)
        H(stringResource(R.string.stops_short), Modifier.width(STOPS_W), TextAlign.End)
    }
}

private val POS_W = 24.dp
private val BAR_W = 10.dp
// wide enough for the long surnames (VERSTAPPEN, HULKENBERG, BORTOLETO) at 10sp
private val DRIVER_W = 76.dp
/** Space between Last/Best and the tyre badge, so the two do not look glued together. */
private val TYRE_GAP = 6.dp
private val TYRE_W = 30.dp
private val LAPS_W = 32.dp // fits "Voltas" in the header
private val STOPS_W = 22.dp
/** Top and bottom padding of a tower row: with the natural line height a row ends up ~44dp, still easy to tap and read. */
private val ROW_PAD = 8.dp

@Composable
fun TowerLine(r: TowerRow, onClick: (() -> Unit)? = null) {
    val p = pal()
    Divider()
    Row(
        Modifier.fillMaxWidth().then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(horizontal = 10.dp, vertical = ROW_PAD),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text("${r.position}", fontSize = 14.sp, fontWeight = FontWeight.Bold, color = if (r.out) p.muted else p.text, modifier = Modifier.width(POS_W), textAlign = TextAlign.Center)
        Box(Modifier.width(BAR_W), contentAlignment = Alignment.Center) { TeamBar(hexColor(r.teamColor), 24.dp) }
        // a surname that does not fit is never cut: the row keeps only the three-letter code,
        // which then sits alone, centred on the team colour bar
        val tag = when {
            r.out -> stringResource(R.string.out_tag)
            r.inPit -> stringResource(R.string.in_pit)
            else -> r.name.takeIf { it.isNotBlank() && fitsWidth(upText(it), 10.sp, DRIVER_W) }
        }
        Column(Modifier.width(DRIVER_W)) {
            Text(r.tla, fontSize = 13.sp, fontWeight = FontWeight.Bold, color = if (r.out) p.muted else p.text)
            if (tag != null) Text(tag, fontSize = 10.sp, color = if (r.inPit || r.out) Color_Amber else p.muted, maxLines = 1, softWrap = false)
        }
        Column(Modifier.weight(1f), horizontalAlignment = Alignment.End) {
            Mono(if (r.position == 1) stringResource(R.string.leader) else r.gap, 12.sp, FontWeight.Normal)
            Mono(if (r.position == 1) "" else r.interval, 10.sp, FontWeight.Normal, p.muted)
        }
        Column(Modifier.width(84.dp), horizontalAlignment = Alignment.End) {
            Mono(r.last.ifBlank { "—" }, 12.sp, FontWeight.Normal, if (r.lastPersonalBest) p.green else p.text)
            Mono(r.best.ifBlank { "—" }, 10.sp, FontWeight.Normal, if (r.bestOverall) p.purple else p.muted)
        }
        Box(Modifier.padding(start = TYRE_GAP).width(TYRE_W), contentAlignment = Alignment.Center) { TyreBadge(r.compound, null) }
        Text(r.tyreAge?.toString() ?: "", fontSize = 12.sp, color = p.text, modifier = Modifier.width(LAPS_W), textAlign = TextAlign.Center, maxLines = 1)
        Text(r.stops?.toString() ?: "", fontSize = 12.sp, color = p.text, modifier = Modifier.width(STOPS_W), textAlign = TextAlign.End)
    }
}

private val Color_Amber = androidx.compose.ui.graphics.Color(0xFFFFB000)

/** Bottom sheet with one driver's sectors, tyre stints and recent laps (Live and Replay). */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun DriverSheet(d: DriverDetail, onDismiss: () -> Unit) {
    val p = pal()
    val r = d.row
    ModalBottomSheet(onDismissRequest = onDismiss, containerColor = p.card) {
        Column(
            Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp).padding(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                TeamBar(hexColor(r.teamColor), 36.dp)
                Column(Modifier.weight(1f)) {
                    Text("P${r.position} · ${r.tla}", fontSize = 18.sp, fontWeight = FontWeight.ExtraBold, color = p.text)
                    Muted(listOf("#${r.number}", r.name).filter { it.isNotBlank() }.joinToString(" · "), 12.sp)
                }
                Column(horizontalAlignment = Alignment.End) {
                    Mono(r.last.ifBlank { "—" }, 14.sp, FontWeight.Bold, if (r.lastPersonalBest) p.green else p.text)
                    Mono(r.best.ifBlank { "—" }, 11.sp, FontWeight.Normal, if (r.bestOverall) p.purple else p.muted)
                }
            }
            if (d.lastSectors.any { it.isNotBlank() } || d.bestSectors.any { it.isNotBlank() }) {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Muted(stringResource(R.string.sectors), 11.sp)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        (0..2).forEach { i ->
                            Column(
                                Modifier.weight(1f).clip(RoundedCornerShape(8.dp)).background(p.bg).padding(vertical = 8.dp),
                                horizontalAlignment = Alignment.CenterHorizontally
                            ) {
                                Muted("S${i + 1}", 10.sp)
                                Mono(d.lastSectors.getOrNull(i).orEmpty().ifBlank { "—" }, 14.sp, FontWeight.Bold)
                                Mono(d.bestSectors.getOrNull(i).orEmpty().ifBlank { "—" }, 11.sp, FontWeight.Normal, p.purple)
                            }
                        }
                    }
                    Muted(stringResource(R.string.sectors_note), 10.sp)
                }
            }
            if (d.stints.isNotEmpty()) {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Muted(stringResource(R.string.stints), 11.sp)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        d.stints.forEach { s ->
                            Row(
                                Modifier.clip(RoundedCornerShape(8.dp)).background(p.bg).padding(horizontal = 8.dp, vertical = 6.dp),
                                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                TyreBadge(s.compound, null)
                                Text(stringResource(R.string.n_laps, s.laps ?: 0), fontSize = 12.sp, color = p.text)
                            }
                        }
                    }
                }
            }
            Column {
                Muted(stringResource(R.string.recent_laps), 11.sp)
                if (d.laps.isEmpty()) Muted(stringResource(R.string.no_laps_yet), 12.sp, Modifier.padding(top = 6.dp))
                d.laps.take(20).forEach { l ->
                    Row(Modifier.fillMaxWidth().padding(vertical = 5.dp), verticalAlignment = Alignment.CenterVertically) {
                        Mono("V${l.lap}", 12.sp, FontWeight.Normal, p.muted, Modifier.width(48.dp))
                        Mono(l.time, 13.sp, FontWeight.Normal, if (l.time == r.best) p.green else p.text, Modifier.weight(1f))
                        if (l.pit) Text(stringResource(R.string.in_pit), fontSize = 10.sp, fontWeight = FontWeight.Bold, color = Color_Amber)
                    }
                }
            }
        }
    }
}

@Composable
fun RaceControlList(msgs: List<RaceMsg>, max: Int = 40) {
    val p = pal()
    CardBox {
        Text(stringResource(R.string.race_control), fontSize = 14.sp, fontWeight = FontWeight.Bold, color = p.text)
        if (msgs.isEmpty()) Muted(stringResource(R.string.no_messages), 12.sp, Modifier.padding(top = 6.dp))
        Column(verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 8.dp)) {
            msgs.take(max).forEach { m ->
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Mono(listOfNotNull(m.lap?.let { "V$it" }, m.time.takeIf { it.isNotBlank() }).joinToString(" · "), 10.sp, FontWeight.Normal, p.muted)
                    Text(m.text, fontSize = 11.sp, color = p.text, lineHeight = 15.sp)
                }
            }
        }
    }
}

@Composable
fun FlagPill(status: String) {
    val (text, color) = when (status) {
        "1" -> stringResource(R.string.flag_green) to androidx.compose.ui.graphics.Color(0xFF1F7A3E)
        "2" -> stringResource(R.string.flag_yellow) to androidx.compose.ui.graphics.Color(0xFFB58900)
        "4" -> stringResource(R.string.safety_car) to androidx.compose.ui.graphics.Color(0xFFB58900)
        "5" -> stringResource(R.string.flag_red) to androidx.compose.ui.graphics.Color(0xFFC0392B)
        "6", "7" -> stringResource(R.string.vsc) to androidx.compose.ui.graphics.Color(0xFFB58900)
        else -> return
    }
    Text(
        text, fontSize = 11.sp, fontWeight = FontWeight.Bold, color = androidx.compose.ui.graphics.Color.White,
        modifier = Modifier
            .background(color, RoundedCornerShape(50))
            .padding(horizontal = 8.dp, vertical = 3.dp)
    )
}

/** Whether [text] fits on one line in [width] at [size], with the app's text style (letter spacing included). */
@Composable
private fun fitsWidth(text: String, size: androidx.compose.ui.unit.TextUnit, width: androidx.compose.ui.unit.Dp): Boolean {
    val measurer = rememberTextMeasurer()
    val style = androidx.compose.material3.LocalTextStyle.current.copy(fontSize = size)
    val px = with(LocalDensity.current) { width.roundToPx() }
    return remember(text, style, px) {
        measurer.measure(text, style, maxLines = 1, softWrap = false).size.width <= px
    }
}
