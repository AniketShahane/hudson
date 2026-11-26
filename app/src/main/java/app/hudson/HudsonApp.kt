package app.hudson

import android.app.Application
import androidx.work.Configuration
import app.hudson.data.Graph

/**
 * Kept as light as possible: this runs before a tap on the widget can be answered when the app
 * is not already running. WorkManager is set up on first use, and the notification channel when
 * a card is first posted.
 */
open class HudsonApp : Application(), Configuration.Provider {
    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder().build()

    override fun onCreate() {
        super.onCreate()
        Graph.init(this)
    }
}
