package app.hudson.widget

import android.content.Context
import android.content.res.Configuration

/**
 * The widget drawn in the phone's own Material You colours (taken from the wallpaper) instead of
 * the train's line colour. The line colour is then only an accent: a bar down the card's edge.
 */
data class PosterPalette(
    /** The card. */
    val ground: Int,
    val ink: Int,
    /** The strip, the list of later trains, and the card when there are no trains. */
    val panel: Int,
    val panelInk: Int,
    /** The minutes on the strip. */
    val accent: Int,
)

object PhoneColors {
    fun isNight(context: Context): Boolean =
        context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK == Configuration.UI_MODE_NIGHT_YES

    /** Light card in light mode, dark card in dark mode; the tones are Material 3's container roles. */
    fun palette(context: Context, night: Boolean = isNight(context)): PosterPalette = if (night) {
        PosterPalette(
            ground = context.getColor(android.R.color.system_accent1_700),
            ink = context.getColor(android.R.color.system_accent1_100),
            panel = context.getColor(android.R.color.system_neutral1_900),
            panelInk = context.getColor(android.R.color.system_neutral1_50),
            accent = context.getColor(android.R.color.system_accent1_200),
        )
    } else {
        PosterPalette(
            ground = context.getColor(android.R.color.system_accent1_100),
            ink = context.getColor(android.R.color.system_accent1_900),
            panel = context.getColor(android.R.color.system_neutral1_50),
            panelInk = context.getColor(android.R.color.system_neutral1_900),
            accent = context.getColor(android.R.color.system_accent1_600),
        )
    }
}
