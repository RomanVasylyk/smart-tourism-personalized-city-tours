package com.example.smarttourism

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import com.example.smarttourism.sync.OfflineSyncScheduler
import com.example.smarttourism.features.planner.RoutePlannerScreen
import com.example.smarttourism.ui.theme.SmartTourismTheme
import org.maplibre.android.MapLibre

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        MapLibre.getInstance(this)
        OfflineSyncScheduler.scheduleOnAppStart(this)

        setContent {
            SmartTourismTheme {
                RoutePlannerScreen()
            }
        }
    }
}
