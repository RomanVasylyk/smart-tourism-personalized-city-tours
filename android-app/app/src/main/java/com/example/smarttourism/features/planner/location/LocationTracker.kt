package com.example.smarttourism.features.planner.location

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Build
import android.os.Looper
import androidx.core.content.ContextCompat
import com.example.smarttourism.R
import com.example.smarttourism.features.planner.state.RouteTrackingMinDistanceMeters
import com.example.smarttourism.features.planner.state.RouteTrackingMinTimeMs

internal fun fetchCurrentLocation(
    context: Context,
    onSuccess: (Double, Double) -> Unit,
    onError: (String) -> Unit
) {
    try {
        val locationManager = context.getSystemService(Context.LOCATION_SERVICE) as? LocationManager
        if (locationManager == null) {
            onError(context.getString(R.string.error_location_service_unavailable))
            return
        }

        val hasFinePermission = ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.ACCESS_FINE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED
        val hasCoarsePermission = ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.ACCESS_COARSE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED

        if (!hasFinePermission && !hasCoarsePermission) {
            onError(context.getString(R.string.error_location_permission_missing))
            return
        }

        val enabledProviders = buildList {
            if (hasFinePermission && locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER)) {
                add(LocationManager.GPS_PROVIDER)
            }
            if (locationManager.isProviderEnabled(LocationManager.NETWORK_PROVIDER)) {
                add(LocationManager.NETWORK_PROVIDER)
            }
        }.distinct()

        val fallbackProviders = buildList {
            addAll(enabledProviders)
            if (hasFinePermission) {
                add(LocationManager.GPS_PROVIDER)
            }
            add(LocationManager.NETWORK_PROVIDER)
        }.distinct()

        val lastKnownLocation = fallbackProviders
            .mapNotNull { provider ->
                runCatching {
                    locationManager.getLastKnownLocation(provider)
                }.getOrNull()
            }
            .maxByOrNull { it.time }

        val provider = enabledProviders.firstOrNull()
        if (provider == null) {
            if (lastKnownLocation != null) {
                onSuccess(lastKnownLocation.latitude, lastKnownLocation.longitude)
            } else {
                onError(context.getString(R.string.error_no_location_provider_enabled))
            }
            return
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            locationManager.getCurrentLocation(provider, null, context.mainExecutor) { location ->
                when {
                    location != null -> onSuccess(location.latitude, location.longitude)
                    lastKnownLocation != null -> onSuccess(lastKnownLocation.latitude, lastKnownLocation.longitude)
                    else -> onError(context.getString(R.string.error_current_location_unavailable))
                }
            }
            return
        }

        @Suppress("DEPRECATION")
        locationManager.requestSingleUpdate(
            provider,
            object : LocationListener {
                override fun onLocationChanged(location: Location) {
                    onSuccess(location.latitude, location.longitude)
                    locationManager.removeUpdates(this)
                }

                override fun onProviderDisabled(provider: String) {
                    if (lastKnownLocation != null) {
                        onSuccess(lastKnownLocation.latitude, lastKnownLocation.longitude)
                    } else {
                        onError(context.getString(R.string.error_no_location_provider_enabled))
                    }
                    locationManager.removeUpdates(this)
                }
            },
            Looper.getMainLooper()
        )
    } catch (securityException: SecurityException) {
        onError(context.getString(R.string.error_location_permission_missing))
    }
}

internal fun startRouteLocationTracking(
    context: Context,
    onLocation: (Location) -> Unit,
    onError: (String) -> Unit
): () -> Unit {
    try {
        val locationManager = context.getSystemService(Context.LOCATION_SERVICE) as? LocationManager
        if (locationManager == null) {
            onError(context.getString(R.string.error_location_service_unavailable))
            return {}
        }

        val hasFinePermission = ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.ACCESS_FINE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED
        val hasCoarsePermission = ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.ACCESS_COARSE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED

        if (!hasFinePermission && !hasCoarsePermission) {
            onError(context.getString(R.string.error_location_permission_missing))
            return {}
        }

        val providers = buildList {
            if (hasFinePermission && locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER)) {
                add(LocationManager.GPS_PROVIDER)
            }
            if (locationManager.isProviderEnabled(LocationManager.NETWORK_PROVIDER)) {
                add(LocationManager.NETWORK_PROVIDER)
            }
        }.distinct()

        if (providers.isEmpty()) {
            onError(context.getString(R.string.error_no_location_provider_enabled))
            return {}
        }

        val listener = object : LocationListener {
            override fun onLocationChanged(location: Location) {
                onLocation(location)
            }

            override fun onProviderDisabled(provider: String) {
                val hasEnabledProvider = providers.any { enabledProvider ->
                    runCatching {
                        locationManager.isProviderEnabled(enabledProvider)
                    }.getOrDefault(false)
                }

                if (!hasEnabledProvider) {
                    onError(context.getString(R.string.error_no_location_provider_enabled))
                }
            }
        }

        providers.forEach { provider ->
            locationManager.requestLocationUpdates(
                provider,
                RouteTrackingMinTimeMs,
                RouteTrackingMinDistanceMeters,
                listener,
                Looper.getMainLooper()
            )
        }

        providers
            .mapNotNull { provider ->
                runCatching {
                    locationManager.getLastKnownLocation(provider)
                }.getOrNull()
            }
            .maxByOrNull { location -> location.time }
            ?.let(onLocation)

        val stopTracking = {
            locationManager.removeUpdates(listener)
        }

        return stopTracking
    } catch (securityException: SecurityException) {
        onError(context.getString(R.string.error_location_permission_missing))
        return {}
    }
}
