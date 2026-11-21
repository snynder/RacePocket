package com.racepocket.app

import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import com.racepocket.app.data.AppSettings
import com.racepocket.app.data.Repo
import com.racepocket.app.notify.Notifier
import com.racepocket.app.ui.AppRoot
import com.racepocket.app.ui.DeepLink
import com.racepocket.app.ui.RaceTheme
import com.racepocket.app.ui.LocalUppercase

class MainActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.auto(Color.TRANSPARENT, Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.auto(Color.TRANSPARENT, Color.TRANSPARENT)
        )
        if (savedInstanceState == null) openFrom(intent)
        setContent {
            val settings by Repo.settings.flow.collectAsState(initial = AppSettings())
            RaceTheme(settings.theme) {
                CompositionLocalProvider(LocalUppercase provides settings.uppercase) {
                    AppRoot(settings)
                }
            }
        }
    }

    /** App already open: a tapped notification arrives here. */
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        openFrom(intent)
    }

    private fun openFrom(intent: Intent?) {
        intent?.getStringExtra(Notifier.EXTRA_ROUTE)?.let { DeepLink.route.value = it }
        intent?.removeExtra(Notifier.EXTRA_ROUTE)
    }
}
