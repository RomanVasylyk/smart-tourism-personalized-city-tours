package com.example.smarttourism

import android.content.Context
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import com.example.smarttourism.core.i18n.AppLanguageStore
import com.example.smarttourism.sync.OfflineSyncScheduler
import com.example.smarttourism.features.planner.ui.RoutePlannerScreen
import com.example.smarttourism.ui.theme.SmartTourismTheme
import dagger.hilt.android.AndroidEntryPoint
import org.maplibre.android.MapLibre

@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    override fun attachBaseContext(newBase: Context) {
        val language = AppLanguageStore.load(newBase)
        AppLanguageStore.applyToResources(newBase.applicationContext, language)
        super.attachBaseContext(AppLanguageStore.wrapContext(newBase, language))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        installSplashScreen()
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        MapLibre.getInstance(this)
        OfflineSyncScheduler.scheduleOnAppStart(this)

        setContent {
            val selectedLanguage = AppLanguageStore.load(this)
            SmartTourismTheme {
                RoutePlannerScreen(
                    selectedLanguage = selectedLanguage,
                    onLanguageSelected = { language ->
                        AppLanguageStore.save(this, language)
                        recreate()
                    }
                )
            }
        }
    }
}
