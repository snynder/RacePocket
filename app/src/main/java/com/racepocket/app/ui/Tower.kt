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
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
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
import com.racepocket.app.data.live.RaceMsg
import com.racepocket.app.data.live.TowerRow

@Composable
fun TowerHeader() {
    val m = pal().muted
    // one line per label, never wrapped: narrow columns would otherwise break words into pieces
    @Composable
    fun H(text: String, mod: Modifier, align: TextAlign) =
        Text(text, fontSize = 10.sp, color = m, modifier = mod, textAlign = align, maxLines = 1, softWrap = false)
    Row(Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        H(stringResource(R.string.pos_short), Modifier.width(22.dp), TextAlign.End)
        Box(Modifier.width(13.dp))
        H(stringResource(R.string.driver), Modifier.width(DRIVER_W), TextAlign.Start)
        H(stringResource(R.string.gap_interval), Modifier.weight(1f), TextAlign.End)
        H(stringResource(R.string.last_best), Modifier.width(84.dp), TextAlign.End)
        H(stringResource(R.string.tyre), Modifier.width(TYRE_W), TextAlign.Center)
        H(stringResource(R.string.tyre_laps), Modifier.width(LAPS_W), TextAlign.Center)
        H(stringResource(R.string.stops_short), Modifier.width(STOPS_W), TextAlign.End)
    }
}

private val DRIVER_W = 54.dp
private val TYRE_W = 38.dp
private val LAPS_W = 40.dp
private val STOPS_W = 24.dp

@Composable
fun TowerLine(r: TowerRow) {
    val p = pal()
    Divider()
    Row(Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 5.dp), verticalAlignment = Alignment.CenterVertically) {
        Text("${r.position}", fontSize = 14.sp, fontWeight = FontWeight.Bold, color = if (r.out) p.muted else p.text, modifier = Modifier.width(22.dp), textAlign = TextAlign.End)
        Box(Modifier.width(13.dp), contentAlignment = Alignment.Center) { TeamBar(hexColor(r.teamColor), 24.dp) }
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
        Box(Modifier.width(TYRE_W), contentAlignment = Alignment.Center) { TyreBadge(r.compound, null) }
        Text(r.tyreAge?.toString() ?: "", fontSize = 12.sp, color = p.text, modifier = Modifier.width(LAPS_W), textAlign = TextAlign.Center, maxLines = 1)
        Text(r.stops?.toString() ?: "", fontSize = 12.sp, color = p.text, modifier = Modifier.width(STOPS_W), textAlign = TextAlign.End)
    }
}

private val Color_Amber = androidx.compose.ui.graphics.Color(0xFFFFB000)

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
