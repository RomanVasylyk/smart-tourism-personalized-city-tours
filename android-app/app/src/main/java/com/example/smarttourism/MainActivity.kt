package com.example.smarttourism

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import com.example.smarttourism.ui.RoutePlannerScreen
import com.example.smarttourism.ui.theme.SmartTourismTheme
import org.maplibre.android.MapLibre

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        MapLibre.getInstance(this)

        setContent {
            SmartTourismTheme {
                RoutePlannerScreen()
            }
        }
    }
}
