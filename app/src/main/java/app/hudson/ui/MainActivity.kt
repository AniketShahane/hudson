package app.hudson.ui

import android.Manifest
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val ask = intent?.getBooleanExtra(EXTRA_ASK_NOTIFICATIONS, false) == true
        setContent {
            HudsonTheme {
                HomeScreen(askNotificationsOnStart = ask)
            }
        }
    }

    companion object {
        const val EXTRA_ASK_NOTIFICATIONS = "ask_notifications"
        val NOTIFICATION_PERMISSION = Manifest.permission.POST_NOTIFICATIONS
    }
}
