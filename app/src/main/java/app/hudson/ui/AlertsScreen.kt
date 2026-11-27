package app.hudson.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
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
    val folds = alerts.size > 2
    Column(modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp)).background(s.block)) {
        alerts.forEachIndexed { i, alert ->
            // The first is always there; the rest unfold together. One short movement, nothing chasing it.
            Unfold(visible = i == 0 || !folds || all) {
                Column {
                    if (i > 0) Box(Modifier.fillMaxWidth().height(1.dp).background(s.rule))
                    AlertRow(alert, flagged = alert.kind == AlertKind.SERVICE)
                }
            }
        }
        if (folds) {
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

/**
 * Opens and closes a piece of content in place: it grows from its top edge while fading in, over
 * a fifth of a second, and closes a little faster. Used wherever something unfolds, so it always
 * moves the same way.
 */
@Composable
internal fun Unfold(visible: Boolean, content: @Composable () -> Unit) {
    AnimatedVisibility(
        visible = visible,
        enter = expandVertically(tween(200, easing = FastOutSlowInEasing), expandFrom = Alignment.Top) + fadeIn(tween(140, delayMillis = 60)),
        exit = shrinkVertically(tween(150, easing = FastOutSlowInEasing), shrinkTowards = Alignment.Top) + fadeOut(tween(90)),
    ) { content() }
}

/** An alert as one line, its heading and time; tap to read what it says. [flagged] marks trouble with trains. */
@Composable
internal fun AlertRow(alert: Alert, flagged: Boolean = false) {
    val s = SwissTheme.colors
    var open by rememberSaveable(alert.id) { mutableStateOf(false) }
    val turn by animateFloatAsState(if (open) -90f else 90f, tween(200, easing = FastOutSlowInEasing), label = "chevron")
    Column(
        Modifier.fillMaxWidth().clickable(onClickLabel = if (open) "Hide details" else "Show details") { open = !open }.padding(horizontal = 16.dp, vertical = 14.dp),
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
            Icon(painterResource(R.drawable.ic_chevron), contentDescription = null, tint = s.muted, modifier = Modifier.size(16.dp).rotate(turn))
        }
        Unfold(visible = open && alert.body.isNotEmpty()) {
            Text(alert.body, style = Type.body.copy(fontSize = 14.sp, lineHeight = 20.sp), color = s.ink, modifier = Modifier.padding(top = 8.dp))
        }
    }
}
