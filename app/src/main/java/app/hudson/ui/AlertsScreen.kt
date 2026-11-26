package app.hudson.ui

import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.hudson.R
import app.hudson.domain.Alert
import app.hudson.domain.AlertKind
import app.hudson.domain.TimeText
import kotlinx.coroutines.launch

/** What PATH is saying right now, then what ended earlier today. Pull down to check again. */
@Composable
internal fun AlertsScreen(current: List<Alert>, earlier: List<Alert>, fetchedAtMs: Long?, unreachable: Boolean = false, onRefresh: suspend () -> Unit) {
    val s = SwissTheme.colors
    val scope = rememberCoroutineScope()
    var refreshing by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.statusBars).padding(horizontal = 16.dp)) {
        Row(Modifier.fillMaxWidth().padding(top = 8.dp).height(48.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("ALERTS", style = Type.wordmark, color = s.ink, modifier = Modifier.weight(1f).semantics { heading() })
            Text(if (fetchedAtMs == null) "" else TimeText.updated(fetchedAtMs).uppercase(), style = Type.label.copy(fontSize = 11.sp), color = s.muted)
        }
        Spacer(Modifier.height(8.dp))
        PullToRefreshBox(
            isRefreshing = refreshing,
            onRefresh = {
                scope.launch {
                    refreshing = true
                    onRefresh()
                    refreshing = false
                }
            },
            modifier = Modifier.fillMaxSize(),
        ) {
            Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                // Old alerts are not "no alerts": say when PATH could not be reached.
                if (unreachable) Unreachable(haveOlder = fetchedAtMs != null)
                if (current.isEmpty() && !(unreachable && fetchedAtMs == null)) AllClear(known = fetchedAtMs != null, Modifier.arrive(0))
                current.forEachIndexed { i, alert -> AlertCard(alert, Modifier.arrive(i)) }
                if (earlier.isNotEmpty()) {
                    SectionLabel("EARLIER · ENDED OR REPLACED")
                    Column(Modifier.clip(RoundedCornerShape(20.dp)).background(s.block)) {
                        earlier.forEachIndexed { i, alert ->
                            if (i > 0) Box(Modifier.fillMaxWidth().height(1.dp).background(s.rule))
                            AlertRow(alert)
                        }
                    }
                }
                Text(
                    "Alerts are PATH's own, from panynj.gov, for the last 24 hours.",
                    style = Type.body.copy(fontSize = 13.sp), color = s.muted, modifier = Modifier.padding(start = 4.dp, top = 6.dp, bottom = 20.dp),
                )
            }
        }
    }
}

@Composable
private fun Unreachable(haveOlder: Boolean) {
    val s = SwissTheme.colors
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp)).background(s.block).padding(16.dp)) {
        Text("NO SIGNAL", style = Type.label.copy(fontSize = 11.sp), color = s.alert)
        Spacer(Modifier.height(6.dp))
        Text(
            if (haveOlder) "Could not reach PATH just now. These are the last alerts received; pull down to try again."
            else "Could not reach PATH for its alerts. Pull down to try again.",
            style = Type.body.copy(fontSize = 15.sp, lineHeight = 21.sp), color = s.ink,
        )
    }
}

@Composable
private fun AllClear(known: Boolean, modifier: Modifier = Modifier) {
    val s = SwissTheme.colors
    Column(modifier.fillMaxWidth().clip(RoundedCornerShape(28.dp)).background(s.ink).padding(22.dp)) {
        Text(if (known) "NO ALERTS" else "CHECKING", style = Type.title.copy(fontSize = 40.sp, lineHeight = 42.sp), color = s.paper)
        Spacer(Modifier.height(6.dp))
        Text(if (known) "PATH has nothing to report right now." else "Asking PATH for its alerts…", style = Type.body, color = s.paper.copy(alpha = 0.8f))
    }
}

/** One alert in full: what kind, which lines, when PATH sent it, and its words. */
@Composable
internal fun AlertCard(alert: Alert, modifier: Modifier = Modifier) {
    val s = SwissTheme.colors
    Column(modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp)).background(s.block).semantics(mergeDescendants = true) {}.padding(16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (alert.kind == AlertKind.SERVICE) {
                Icon(painterResource(R.drawable.ic_tab_alerts), contentDescription = null, tint = s.alert, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(6.dp))
            }
            Text(alert.kind.label, style = Type.label.copy(fontSize = 11.sp), color = if (alert.kind == AlertKind.SERVICE) s.alert else s.muted)
            Spacer(Modifier.width(10.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(3.dp)) {
                alert.lines.forEach { Box(Modifier.size(width = 14.dp, height = 8.dp).clip(RoundedCornerShape(2.dp)).background(Color(it.color.toInt()))) }
            }
            Spacer(Modifier.weight(1f))
            Text(TimeText.clockAmPm(alert.sentAtMs), style = Type.label.copy(fontSize = 11.sp), color = s.muted)
        }
        Spacer(Modifier.height(8.dp))
        Text(alert.title, style = Type.block.copy(fontSize = 17.sp, lineHeight = 22.sp), color = s.ink)
        if (alert.body.isNotEmpty()) {
            Spacer(Modifier.height(6.dp))
            Text(alert.body, style = Type.body.copy(fontSize = 15.sp, lineHeight = 21.sp), color = s.ink)
        }
    }
}

/** An alert as one line, its heading and time; tap to read what it says. [flagged] marks trouble with trains. */
@Composable
internal fun AlertRow(alert: Alert, flagged: Boolean = false) {
    val s = SwissTheme.colors
    var open by remember { mutableStateOf(false) }
    Column(
        Modifier.fillMaxWidth().clickable(onClickLabel = if (open) "Hide details" else "Show details") { open = !open }
            .animateContentSize(Motion.resize).padding(horizontal = 16.dp, vertical = 14.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (flagged) {
                Icon(painterResource(R.drawable.ic_tab_alerts), contentDescription = "Service alert", tint = s.alert, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(8.dp))
            }
            Text(alert.title, style = Type.bodyBold.copy(fontSize = 15.sp), color = s.ink, modifier = Modifier.weight(1f), maxLines = if (open) 4 else 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
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
