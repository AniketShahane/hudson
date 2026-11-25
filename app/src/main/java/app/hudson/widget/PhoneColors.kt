package app.hudson.widget

import android.content.Context
import android.content.res.Configuration
import app.hudson.R

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
    /** False hides the line-colour bars: used when painting a layer the launcher will tint. */
    val lines: Boolean = true,
)

object PhoneColors {
    fun isNight(context: Context): Boolean =
        context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK == Configuration.UI_MODE_NIGHT_YES

    /**
     * The colours as they resolve right now, for painting one flat picture (tests and previews).
     * The widget itself does not use this: it sends the launcher the resource ids and uncoloured
     * layers ([PosterRenderer.renderLayers]) so the colours are always the phone's current ones.
     */
    fun palette(context: Context, night: Boolean = isNight(context)): PosterPalette {
        val config = Configuration(context.resources.configuration).apply {
            uiMode = (uiMode and Configuration.UI_MODE_NIGHT_MASK.inv()) or if (night) Configuration.UI_MODE_NIGHT_YES else Configuration.UI_MODE_NIGHT_NO
        }
        val themed = context.createConfigurationContext(config)
        return PosterPalette(
            ground = themed.getColor(R.color.phone_ground),
            ink = themed.getColor(R.color.phone_ink),
            panel = themed.getColor(R.color.phone_panel),
            panelInk = themed.getColor(R.color.phone_panel_ink),
            accent = themed.getColor(R.color.phone_accent),
        )
    }
}
