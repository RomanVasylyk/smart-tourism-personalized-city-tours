package com.example.smarttourism.features.planner.location

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.example.smarttourism.MainActivity
import com.example.smarttourism.R
import com.example.smarttourism.features.planner.domain.model.RoutePoint
import com.example.smarttourism.features.planner.state.RouteTrackingMaxAccuracyMeters

class RouteTrackingService : Service() {
    private var stopTracking: (() -> Unit)? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        RouteLocationFeed.clear()
    }

    override fun onDestroy() {
        stopTracking?.invoke()
        stopTracking = null
        super.onDestroy()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val foregroundType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION
        } else {
            0
        }
        runCatching {
            ServiceCompat.startForeground(this, NotificationId, buildNotification(), foregroundType)
        }.onSuccess {
            startTrackingIfNeeded()
        }.onFailure {
            stopSelf()
        }
        return START_NOT_STICKY
    }

    private fun startTrackingIfNeeded() {
        if (stopTracking != null) {
            return
        }
        var isStarting = true
        var failedToStart = false
        val stop = startRouteLocationTracking(
            context = this,
            onLocation = { location ->
                if (!location.hasAccuracy() || location.accuracy <= RouteTrackingMaxAccuracyMeters) {
                    RouteLocationFeed.publish(
                        RouteTrackingUpdate.Location(
                            RoutePoint(lat = location.latitude, lon = location.longitude)
                        )
                    )
                }
            },
            onError = { message ->
                if (isStarting) {
                    failedToStart = true
                }
                RouteLocationFeed.publish(RouteTrackingUpdate.Error(message))
            }
        )
        isStarting = false
        if (!failedToStart) {
            stopTracking = stop
        }
    }

    private fun buildNotification(): Notification {
        val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        notificationManager.createNotificationChannel(
            NotificationChannel(
                ChannelId,
                getString(R.string.route_tracking_title),
                NotificationManager.IMPORTANCE_LOW
            )
        )
        val openAppIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        return NotificationCompat.Builder(this, ChannelId)
            .setSmallIcon(R.drawable.ic_launcher_monochrome)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(getString(R.string.route_tracking_state_active))
            .setContentIntent(openAppIntent)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .build()
    }

    companion object {
        private const val ChannelId = "route-tracking"
        private const val NotificationId = 1001

        fun start(context: Context) {
            runCatching {
                ContextCompat.startForegroundService(
                    context,
                    Intent(context, RouteTrackingService::class.java)
                )
            }
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, RouteTrackingService::class.java))
        }
    }
}
