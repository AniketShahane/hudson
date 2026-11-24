package app.hudson

import android.app.Application
import app.hudson.data.Graph
import app.hudson.notify.LiveNotifier

open class HudsonApp : Application() {
    override fun onCreate() {
        super.onCreate()
        Graph.init(this)
        LiveNotifier.ensureChannel(this)
    }
}
