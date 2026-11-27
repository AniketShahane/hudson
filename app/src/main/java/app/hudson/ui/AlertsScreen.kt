package app.hudson.ui

import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.hudson.R
import app.hudson.domain.Alert
import app.hudson.domain.AlertKind
import app.hudson.domain.TimeText

/**
 * PATH's alerts for one place, kept small: each is a single line until tapped, and with more than
 * two only the first shows until you ask for the rest, so the trains are never pushed off the
 * screen. Alerts live with the station they are about; there is no separate alerts screen.
 */
@Composable
internal fun AlertsBlock(alerts: List<Alert>, modifier: Modifier = Modifier) {
    val s = SwissTheme.colors
    var all by rememberSaveable { mutableStateOf(false) }
    val shown = if (all || alerts.size <= 2) alerts else alerts.take(1)
    Column(modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp)).background(s.block).animateContentSize(Motion.resize)) {
        shown.forEachIndexed { i, alert ->
            if (i > 0) Box(Modifier.fillMaxWidth().height(1.dp).background(s.rule))
            AlertRow(alert, flagged = alert.kind == AlertKind.SERVICE)
        }
        if (alerts.size > 2) {
            Box(Modifier.fillMaxWidth().height(1.dp).background(s.rule))
            Box(
                Modifier.fillMaxWidth().heightIn(min = 44.dp).clickable(onClickLabel = if (all) "Show fewer alerts" else "Show all alerts") { all = !all }.padding(horizontal = 16.dp),
                contentAlignment = Alignment.CenterStart,
            ) {
                Text(if (all) "SHOW FEWER" else "${alerts.size - 1} MORE", style = Type.label.copy(fontSize = 11.sp), color = s.muted)
            }
        }
    }
}

/** An alert as one line, its heading and time; tap to read what it says. [flagged] marks trouble with trains. */
@Composable
internal fun AlertRow(alert: Alert, flagged: Boolean = false) {
    val s = SwissTheme.colors
    var open by rememberSaveable(alert.id) { mutableStateOf(false) }
    Column(
        Modifier.fillMaxWidth().clickable(onClickLabel = if (open) "Hide details" else "Show details") { open = !open }
            .animateContentSize(Motion.resize).padding(horizontal = 16.dp, vertical = 14.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (flagged) {
                Icon(painterResource(R.drawable.ic_tab_alerts), contentDescription = "Service alert", tint = s.alert, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(8.dp))
            }
            Text(alert.title, style = Type.bodyBold.copy(fontSize = 15.sp), color = s.ink, modifier = Modifier.weight(1f), maxLines = if (open) 4 else 1, overflow = TextOverflow.Ellipsis)
            Spacer(Modifier.width(10.dp))
            Text(TimeText.clockAmPm(alert.sentAtMs), style = Type.label.copy(fontSize = 10.sp), color = s.muted)
            Spacer(Modifier.width(6.dp))
            Icon(painterResource(R.drawable.ic_chevron), contentDescription = null, tint = s.muted, modifier = Modifier.size(16.dp).rotate(if (open) -90f else 90f))
        }
        if (open && alert.body.isNotEmpty()) {
            Spacer(Modifier.height(8.dp))
            Text(alert.body, style = Type.body.copy(fontSize = 14.sp, lineHeight = 20.sp), color = s.ink)
        }
    }
}
