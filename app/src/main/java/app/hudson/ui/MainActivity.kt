package app.hudson.ui

import android.Manifest
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import app.hudson.data.Graph
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        Graph.init(applicationContext)
        if (savedInstanceState == null) showFavoriteFrom(intent)
        val ask = intent?.getBooleanExtra(EXTRA_ASK_NOTIFICATIONS, false) == true
        setContent {
            val settings by Graph.settings.flow.collectAsStateWithLifecycle(initialValue = null)
            HudsonTheme(matchPhone = settings?.matchPhone == true) {
                HomeScreen(askNotificationsOnStart = ask)
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        showFavoriteFrom(intent)
    }

    /** Opened from a widget: show the station that widget was showing. */
    private fun showFavoriteFrom(intent: Intent?) {
        val index = intent?.getIntExtra(EXTRA_FAVORITE, -1) ?: -1
        if (index < 0) return
        lifecycleScope.launch {
            Graph.init(applicationContext)
            Graph.settings.update { it.copy(appFavorite = index) }
        }
    }

    companion object {
        const val EXTRA_FAVORITE = "favorite_index"
        const val EXTRA_ASK_NOTIFICATIONS = "ask_notifications"
        val NOTIFICATION_PERMISSION = Manifest.permission.POST_NOTIFICATIONS
    }
}
