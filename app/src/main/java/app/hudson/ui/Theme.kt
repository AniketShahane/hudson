package app.hudson.ui

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.AnimationSpec
import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialExpressiveTheme
import androidx.compose.material3.MotionScheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import app.hudson.R

/** Swiss transit: paper and ink, PATH's line colours, one heavy grotesque for anything you read first. */
@Immutable
data class Swiss(
    val paper: Color,
    val ink: Color,
    /** Secondary text. */
    val muted: Color,
    /** Rows, outlines. */
    val rule: Color,
    val block: Color,
    val alert: Color,
    /**
     * The next-train card when the app follows the phone's colours; null means the card takes the
     * train's line colour, as on the poster.
     */
    val hero: Color? = null,
    val onHero: Color? = null,
)

private val Light = Swiss(paper = Color(0xFFF2EFE8), ink = Color(0xFF141414), muted = Color(0xFF5E5A53), rule = Color(0xFFD9D4C9), block = Color(0xFFE6E1D6), alert = Color(0xFFD93A30))
private val Dark = Swiss(paper = Color(0xFF0F0F0F), ink = Color(0xFFF2EFE8), muted = Color(0xFFA8A398), rule = Color(0xFF2A2A2A), block = Color(0xFF1C1C1C), alert = Color(0xFFFF6B5E))

val LocalSwiss = staticCompositionLocalOf { Light }

object SwissTheme {
    val colors: Swiss
        @Composable @ReadOnlyComposable get() = LocalSwiss.current
}

val Display = FontFamily(Font(R.font.archivo_black))

object Type {
    val wordmark = TextStyle(fontFamily = Display, fontSize = 28.sp, letterSpacing = (-0.02).em)
    val title = TextStyle(fontFamily = Display, fontSize = 26.sp, lineHeight = 30.sp)
    val block = TextStyle(fontFamily = Display, fontSize = 15.sp, letterSpacing = 0.01.em)
    val time = TextStyle(fontFamily = Display, fontSize = 22.sp, fontFeatureSettings = "tnum")
    val label = TextStyle(fontWeight = FontWeight.Bold, fontSize = 12.sp, letterSpacing = 0.16.em)
    val body = TextStyle(fontWeight = FontWeight.Medium, fontSize = 16.sp, lineHeight = 22.sp)
    val bodyBold = TextStyle(fontWeight = FontWeight.Bold, fontSize = 16.sp)
    val giant = TextStyle(fontFamily = Display, fontSize = 230.sp, lineHeight = 230.sp, letterSpacing = (-0.05).em, fontFeatureSettings = "tnum")
}

/** The same six roles filled from the phone's Material You colours (its wallpaper). */
private fun swissFrom(cs: ColorScheme) = Swiss(
    paper = cs.surface,
    ink = cs.onSurface,
    muted = cs.onSurfaceVariant,
    rule = cs.outlineVariant,
    block = cs.surfaceContainerHigh,
    alert = cs.error,
    hero = cs.primaryContainer,
    onHero = cs.onPrimaryContainer,
)

/**
 * One set of timings for the whole app, so every change moves the same way: things that travel
 * use a soft spring, things that appear fade in a touch later than things that leave fade out.
 */
object Motion {
    fun <T> move(): FiniteAnimationSpec<T> = spring(dampingRatio = 0.9f, stiffness = 380f)
    val enter: FiniteAnimationSpec<Float> = tween(220, delayMillis = 50)
    val exit: FiniteAnimationSpec<Float> = tween(140)
    val color: AnimationSpec<Color> = tween(320)
    val resize: FiniteAnimationSpec<IntSize> = spring(dampingRatio = 0.9f, stiffness = 380f)
}

/** Eases every colour to its new value, so switching theme (or dark mode) is a fade, not a flash. */
@Composable
private fun Swiss.eased(): Swiss {
    @Composable fun ease(c: Color, label: String) = animateColorAsState(c, Motion.color, label = label).value
    val heroEased = ease(hero ?: paper, "hero")
    val onHeroEased = ease(onHero ?: ink, "onHero")
    return Swiss(
        paper = ease(paper, "paper"), ink = ease(ink, "ink"), muted = ease(muted, "muted"), rule = ease(rule, "rule"),
        block = ease(block, "block"), alert = ease(alert, "alert"),
        hero = if (hero != null) heroEased else null, onHero = if (onHero != null) onHeroEased else null,
    )
}

@Composable
fun HudsonTheme(matchPhone: Boolean = false, content: @Composable () -> Unit) {
    val dark = isSystemInDarkTheme()
    val phone = if (matchPhone) LocalContext.current.let { if (dark) dynamicDarkColorScheme(it) else dynamicLightColorScheme(it) } else null
    val s = (phone?.let(::swissFrom) ?: if (dark) Dark else Light).eased()
    val scheme = if (phone != null) {
        // Ink stays the primary so buttons and selected blocks keep the poster's black-on-paper logic.
        phone.copy(primary = s.ink, onPrimary = s.paper, outline = s.ink, surfaceContainerLow = s.block)
    } else if (dark) {
        darkColorScheme(primary = s.ink, onPrimary = s.paper, background = s.paper, surface = s.paper, onSurface = s.ink, onBackground = s.ink, surfaceContainerLow = s.block, outline = s.ink)
    } else {
        lightColorScheme(primary = s.ink, onPrimary = s.paper, background = s.paper, surface = s.paper, onSurface = s.ink, onBackground = s.ink, surfaceContainerLow = s.block, outline = s.ink)
    }
    CompositionLocalProvider(LocalSwiss provides s) {
        MaterialExpressiveTheme(
            colorScheme = scheme,
            motionScheme = MotionScheme.expressive(),
            shapes = Shapes(small = RoundedCornerShape(12.dp), medium = RoundedCornerShape(16.dp), large = RoundedCornerShape(28.dp)),
            content = content,
        )
    }
}
