package cz.litoj.grs

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationManager
import android.location.provider.ProviderProperties
import android.os.Build
import android.os.SystemClock
import android.util.Log
import androidx.core.content.ContextCompat

/**
 * Handles mocking of GPS location across multiple providers.
 *
 * Mocks [LocationManager.GPS_PROVIDER] and [LocationManager.NETWORK_PROVIDER].
 * Does NOT mock the "fused" provider — microG's FusedLocationProviderService must stay
 * attached so that apps using FusedLocationProviderClient receive aggregated mock locations.
 *
 * Note: Requires the app to be selected as the "Mock location app" in Developer Options.
 */
class LocationMocker(private val context: Context) {

    private val locationManager: LocationManager by lazy {
        context.getSystemService(Context.LOCATION_SERVICE) as LocationManager
    }

    /** Tracks which providers actually have a test provider registered. */
    private val activeProviders = mutableSetOf<String>()

    private var isMocking = false

    companion object {
        private const val TAG = "LocationMocker"

        // ProviderProperties constants for API < 31 (ProviderProperties requires API 31+)
        private const val LEGACY_POWER_USAGE_HIGH = 3
        private const val LEGACY_POWER_USAGE_LOW = 1
        private const val LEGACY_ACCURACY_FINE = 1
        private const val LEGACY_ACCURACY_COARSE = 2
    }

    /**
     * Validate that coordinates are within valid ranges.
     */
    private fun isValidCoordinate(lat: Double, lon: Double): Boolean =
        (lat in -90.0..90.0) && (lon in -180.0..180.0)

    /**
     * Check if we have the required location permissions
     */
    fun hasLocationPermissions(): Boolean {
        return ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.ACCESS_FINE_LOCATION,
        ) == PackageManager.PERMISSION_GRANTED
    }

    /**
     * Start mocking location with the given coordinates.
     * Returns false if the app is not selected as the mock location app in Developer Options.
     */
    @SuppressLint("MissingPermission")
    fun startMocking(latitude: Double, longitude: Double): Boolean {
        if (isMocking) return true

        if (!isValidCoordinate(latitude, longitude)) {
            Log.w(TAG, "startMocking: invalid coordinates lat=$latitude lon=$longitude")
            return false
        }

        Log.d(TAG, "startMocking: lat=$latitude lon=$longitude")
        Log.d(TAG, "hasLocationPermissions=${hasLocationPermissions()}")

        return try {
            addTestProviders()
            if (activeProviders.isEmpty()) {
                Log.e(TAG, "startMocking: no test providers could be added")
                return false
            }
            // Set isMocking BEFORE updating so updateMockLocation doesn't bail out
            isMocking = true
            updateMockLocation(latitude, longitude)
            Log.d(TAG, "startMocking: SUCCESS (providers=$activeProviders)")
            true
        } catch (e: SecurityException) {
            Log.e(TAG, "startMocking: SecurityException - app not selected as mock location app", e)
            isMocking = false
            cleanupProviders()
            false
        } catch (e: Exception) {
            Log.e(TAG, "startMocking: error", e)
            isMocking = false
            cleanupProviders()
            false
        }
    }

    /**
     * Update the current mock location on all active providers.
     */
    @SuppressLint("MissingPermission")
    fun updateMockLocation(latitude: Double, longitude: Double): Boolean {
        if (!isMocking) return false

        if (!isValidCoordinate(latitude, longitude)) {
            Log.w(TAG, "updateMockLocation: invalid coordinates lat=$latitude lon=$longitude")
            return false
        }

        Log.d(TAG, "updateMockLocation: lat=$latitude lon=$longitude")

        return try {
            val now = System.currentTimeMillis()
            val elapsedNanos = SystemClock.elapsedRealtimeNanos()

            for (provider in activeProviders) {
                try {
                    val mockLocation = createMockLocation(
                        latitude,
                        longitude,
                        provider,
                        now,
                        elapsedNanos,
                    )
                    locationManager.setTestProviderLocation(
                        provider,
                        mockLocation
                    )
                } catch (e: Exception) {
                    Log.w(TAG, "Failed to set location on $provider", e)
                }
            }
            true
        } catch (e: Exception) {
            Log.e(TAG, "updateMockLocation error", e)
            false
        }
    }

    /**
     * Create a mock [Location] for the given provider.
     */
    private fun createMockLocation(
        latitude: Double,
        longitude: Double,
        provider: String,
        time: Long,
        elapsedNanos: Long,
    ): Location = Location(provider).apply {
        this.latitude = latitude
        this.longitude = longitude
        altitude = 0.0
        this.time = time
        elapsedRealtimeNanos = elapsedNanos
        accuracy = 2f
        bearing = 0f
        speed = 0f
    }

    /**
     * Add test providers for GPS, network, and fused.
     * Each provider is added independently — a failure on one doesn't block the others.
     */
    @SuppressLint("MissingPermission")
    private fun addTestProviders() {
        val powerHigh = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            ProviderProperties.POWER_USAGE_HIGH
        } else {
            LEGACY_POWER_USAGE_HIGH
        }
        val powerLow = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            ProviderProperties.POWER_USAGE_LOW
        } else {
            LEGACY_POWER_USAGE_LOW
        }
        val accuracyFine = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            ProviderProperties.ACCURACY_FINE
        } else {
            LEGACY_ACCURACY_FINE
        }
        val accuracyCoarse =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                ProviderProperties.ACCURACY_COARSE
            } else {
                LEGACY_ACCURACY_COARSE
            }

        // GPS provider: high power, fine accuracy
        addSingleTestProvider(
            provider = LocationManager.GPS_PROVIDER,
            requiresCell = false,
            powerUsage = powerHigh,
            accuracy = accuracyFine,
        )

        // Network provider: low power, coarse accuracy
        addSingleTestProvider(
            provider = LocationManager.NETWORK_PROVIDER,
            requiresCell = true,
            powerUsage = powerLow,
            accuracy = accuracyCoarse,
        )

        // NOTE: Do NOT mock the "fused" provider. microG's FusedLocationProviderService
        // must stay attached to it so that apps using FusedLocationProviderClient receive
        // the mock (aggregated from GPS/NETWORK) via IPC.
        //
        // However, we must clean up any stale "fused" test provider left from a previous
        // session (e.g. after an app update). removeTestProvider restores the original provider.
        try {
            locationManager.removeTestProvider("fused")
            Log.d(TAG, "Removed stale fused test provider")
        } catch (_: Exception) {
            // No stale fused test provider to remove, that's fine
        }
    }

    /**
     * Add a single test provider, wrapping in try-catch so a failure on one provider
     * (e.g. "fused" conflicting with microG) doesn't prevent the others from working.
     */
    @SuppressLint("MissingPermission")
    private fun addSingleTestProvider(
        provider: String,
        requiresCell: Boolean,
        powerUsage: Int,
        accuracy: Int,
    ) {
        try {
            // Remove any existing test provider first
            locationManager.removeTestProvider(provider)
        } catch (_: Exception) {
            // Provider might not exist yet, that's ok
        }

        try {
            locationManager.addTestProvider(
                provider,
                true,  // requiresNetwork
                false,  // requiresSatellite
                requiresCell,
                false,  // hasMonetaryCost
                false,  // supportsAltitude
                false,  // supportsSpeed
                false,  // supportsBearing
                powerUsage,
                accuracy,
            )
            locationManager.setTestProviderEnabled(provider, true)
            activeProviders.add(provider)
            Log.d(TAG, "Added test provider: $provider")
        } catch (e: Exception) {
            Log.w(TAG, "Could not add test provider: $provider", e)
        }
    }

    /**
     * Stop mocking location and remove all test providers.
     */
    fun stopMocking() {
        if (isMocking) {
            cleanupProviders()
            isMocking = false
        }
    }

    /**
     * Remove all active test providers.
     */
    private fun cleanupProviders() {
        for (provider in activeProviders) {
            try {
                locationManager.setTestProviderEnabled(provider, false)
                locationManager.removeTestProvider(provider)
            } catch (_: Exception) {
                // Ignore errors when removing
            }
        }
        activeProviders.clear()
    }

    /**
     * Clean up resources
     */
    fun cleanup() {
        stopMocking()
    }
}
