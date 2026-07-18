package cz.litoj.grs

import android.content.Context
import android.location.LocationManager
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * State representing permission status for required permissions
 */
data class PermissionState(
    val cameraPermissionGranted: Boolean = false,
    val locationPermissionGranted: Boolean = false,
    val backgroundLocationPermissionGranted: Boolean = false,
    val mockLocationPermissionGranted: Boolean = false,
)

/**
 * UI state for GPS Spoof screen
 */
data class UiState(
    val currentCoordinates: GpsCoordinates? = null,
    val selectedFormat: CoordinateFormat = CoordinateFormat.AUTO,
    val detectedFormat: CoordinateFormat = CoordinateFormat.DEGREES,
    val isMockingLocation: Boolean = false,
    val lastDetectedText: String? = null,
    val latitudeText: String = "",
    val longitudeText: String = "",
    val mockError: String? = null,
    val scanCount: Int = 0,
    val lastRawText: String = "",
    val isGpsEnabled: Boolean = true,
    /** Coordinates that differ too much from current ones; awaiting user confirmation. */
    val pendingCoordinates: GpsCoordinates? = null,
    /** Formatted text for the pending-coordinates snackbar (e.g. "50.123 14.456"). */
    val pendingDisplayText: String = "",
    /** Format detected for the pending coordinates. */
    val pendingFormat: CoordinateFormat = CoordinateFormat.DEGREES,
)

/**
 * ViewModel for GPS Spoofing functionality.
 * Automatically mocks location whenever coordinates are available.
 */
class GpsSpoofViewModel : ViewModel() {

    private val _uiState = MutableStateFlow(UiState())
    val uiState: StateFlow<UiState> = _uiState.asStateFlow()

    private val _permissionState = MutableStateFlow(PermissionState())
    val permissionState: StateFlow<PermissionState> = _permissionState.asStateFlow()

    private var locationMocker: LocationMocker? = null
    private var appContext: Context? = null

    /** Periodic mock refresh job — keeps the mock alive even when the camera is closed. */
    private var mockRefreshJob: Job? = null

    companion object {
        /** Interval for periodic mock location refresh, in milliseconds. */
        private const val MOCK_REFRESH_INTERVAL_MS = 1000L
        /** Max allowed difference (in degrees) between current and newly-read coordinates. */
        private const val MAX_COORDINATE_DIFF_DEG = 0.5
    }

    /**
     * Store the application context for mocking. Call this from the Activity.
     */
    fun setAppContext(context: Context) {
        appContext = context.applicationContext
    }

    /**
     * Check whether the GPS provider is enabled and update [UiState.isGpsEnabled].
     */
    fun checkGpsEnabled() {
        val ctx = appContext ?: return
        val lm = ctx.getSystemService(Context.LOCATION_SERVICE) as LocationManager
        val enabled = lm.isProviderEnabled(LocationManager.GPS_PROVIDER)
        _uiState.update { it.copy(isGpsEnabled = enabled) }
    }

    /**
     * Called when text is recognized from the camera. Parses coordinates, updates state,
     * and automatically updates the mock location.
     *
     * @return true if valid coordinates were parsed from the text, false otherwise.
     */
    fun onTextRecognized(text: String): Boolean {
        if (text.isBlank()) {
            // Clear stale OCR text so the preview line hides
            _uiState.update { it.copy(lastRawText = "") }
            return false
        }

        // Always store raw text so user can see what OCR detected
        _uiState.update { it.copy(lastRawText = text) }

        val result = GpsCoordinateParser.parseFromText(text)
        if (result == null) {
            android.util.Log.d("GpsSpoofViewModel", "No coordinates found in: '${text.take(100)}'")
            return false
        }

        android.util.Log.d("GpsSpoofViewModel", "Parsed: ${result.coordinates} (${result.detectedFormat})")

        val current = _uiState.value.currentCoordinates
        if (current != null && isTooFar(current, result.coordinates)) {
            // Coordinates differ too much — store as pending, ask user to confirm
            android.util.Log.d("GpsSpoofViewModel", "Too far from current ($current), pending: ${result.coordinates}")
            val pendingDisplay = "${result.coordinates.latitudeString(result.detectedFormat)} ${result.coordinates.longitudeString(result.detectedFormat)}"
            _uiState.update {
                it.copy(
                    pendingCoordinates = result.coordinates,
                    pendingDisplayText = pendingDisplay,
                    pendingFormat = result.detectedFormat,
                )
            }
            return true
        }

        val format = _uiState.value.selectedFormat
        val displayFormat = if (format == CoordinateFormat.AUTO) result.detectedFormat else format

        _uiState.update {
            it.copy(
                currentCoordinates = result.coordinates,
                detectedFormat = result.detectedFormat,
                lastDetectedText = text,
                latitudeText = result.coordinates.latitudeString(displayFormat),
                longitudeText = result.coordinates.longitudeString(displayFormat),
                scanCount = it.scanCount + 1,
                pendingCoordinates = null,
                pendingDisplayText = "",
            )
        }

        // Automatically update mock location
        autoMock(result.coordinates)
        return true
    }

    /**
     * Accept the pending coordinates that were too far from the current ones.
     * Applies them as the new current coordinates and updates the mock location.
     */
    fun acceptPendingCoordinates() {
        val pending = _uiState.value.pendingCoordinates ?: return
        val pendingFmt = _uiState.value.pendingFormat
        val format = _uiState.value.selectedFormat
        val displayFormat = if (format == CoordinateFormat.AUTO) pendingFmt else format

        _uiState.update {
            it.copy(
                currentCoordinates = pending,
                detectedFormat = pendingFmt,
                latitudeText = pending.latitudeString(displayFormat),
                longitudeText = pending.longitudeString(displayFormat),
                scanCount = it.scanCount + 1,
                pendingCoordinates = null,
                pendingDisplayText = "",
            )
        }
        autoMock(pending)
    }

    /**
     * Dismiss the pending coordinates without applying them.
     */
    fun dismissPendingCoordinates() {
        _uiState.update {
            it.copy(pendingCoordinates = null, pendingDisplayText = "")
        }
    }

    /**
     * Check whether the new coordinates differ from the current ones by more than
     * [MAX_COORDINATE_DIFF_DEG] in either latitude or longitude.
     */
    private fun isTooFar(current: GpsCoordinates, new: GpsCoordinates): Boolean {
        return kotlin.math.abs(current.latitude - new.latitude) > MAX_COORDINATE_DIFF_DEG ||
            kotlin.math.abs(current.longitude - new.longitude) > MAX_COORDINATE_DIFF_DEG
    }

    /**
     * Update latitude text from manual editing. Parses the value and updates coordinates.
     */
    fun updateLatitudeText(text: String) {
        val coords = _uiState.value.currentCoordinates
        val format = _uiState.value.selectedFormat
        val displayFormat = if (format == CoordinateFormat.AUTO) _uiState.value.detectedFormat else format

        _uiState.update { it.copy(latitudeText = text) }

        val parsedLat = GpsCoordinateParser.parseLatitude(text, displayFormat)
        if (parsedLat != null) {
            val newCoords = GpsCoordinates(parsedLat, coords?.longitude ?: 0.0)
            _uiState.update { it.copy(currentCoordinates = newCoords) }
            autoMock(newCoords)
        }
    }

    /**
     * Update longitude text from manual editing. Parses the value and updates coordinates.
     */
    fun updateLongitudeText(text: String) {
        val coords = _uiState.value.currentCoordinates
        val format = _uiState.value.selectedFormat
        val displayFormat = if (format == CoordinateFormat.AUTO) _uiState.value.detectedFormat else format

        _uiState.update { it.copy(longitudeText = text) }

        val parsedLon = GpsCoordinateParser.parseLongitude(text, displayFormat)
        if (parsedLon != null) {
            val newCoords = GpsCoordinates(coords?.latitude ?: 0.0, parsedLon)
            _uiState.update { it.copy(currentCoordinates = newCoords) }
            autoMock(newCoords)
        }
    }

    /**
     * Update coordinate format selection and reformat the text fields
     */
    fun setCoordinateFormat(format: CoordinateFormat) {
        val coords = _uiState.value.currentCoordinates ?: run {
            _uiState.update { it.copy(selectedFormat = format) }
            return
        }

        val displayFormat = if (format == CoordinateFormat.AUTO) _uiState.value.detectedFormat else format

        _uiState.update {
            it.copy(
                selectedFormat = format,
                latitudeText = coords.latitudeString(displayFormat),
                longitudeText = coords.longitudeString(displayFormat),
            )
        }
    }

    /**
     * Automatically start or update the mock location with the given coordinates.
     * If mocking hasn't started yet, start it. If already mocking, just update the location.
     */
    private fun autoMock(coordinates: GpsCoordinates) {
        val ctx = appContext ?: return
        val mocker = locationMocker ?: LocationMocker(ctx).also { locationMocker = it }

        if (!_uiState.value.isMockingLocation) {
            // First time: start mocking
            android.util.Log.d("GpsSpoofViewModel", "autoMock: starting mock at ${coordinates.latitude}, ${coordinates.longitude}")
            val success = mocker.startMocking(coordinates.latitude, coordinates.longitude)
            _uiState.update {
                it.copy(
                    isMockingLocation = success,
                    mockError = if (success) null else "Set this app as mock location app in Developer Options",
                )
            }
            if (success) {
                startMockRefresh()
            }
        } else {
            // Already mocking: just update the location
            mocker.updateMockLocation(coordinates.latitude, coordinates.longitude)
        }
    }

    /**
     * Start a periodic refresh of the mock location.
     * This ensures that when microG's fused service briefly polls GPS, it receives
     * a fresh mock location (setTestProviderLocation only delivers to active listeners).
     */
    private fun startMockRefresh() {
        mockRefreshJob?.cancel()
        mockRefreshJob = viewModelScope.launch {
            while (isActive) {
                delay(MOCK_REFRESH_INTERVAL_MS)
                val coords = _uiState.value.currentCoordinates ?: continue
                val mocker = locationMocker ?: continue
                if (_uiState.value.isMockingLocation) {
                    mocker.updateMockLocation(coords.latitude, coords.longitude)
                }
            }
        }
    }

    /**
     * Stop mocking location
     */
    fun stopMocking() {
        mockRefreshJob?.cancel()
        mockRefreshJob = null
        locationMocker?.stopMocking()
        _uiState.update { it.copy(isMockingLocation = false) }
    }

    /**
     * Update permission states
     */
    fun updatePermissionStates(
        cameraGranted: Boolean,
        locationGranted: Boolean,
        backgroundLocationGranted: Boolean = false,
        mockLocationGranted: Boolean = false,
    ) {
        _permissionState.update {
            it.copy(
                cameraPermissionGranted = cameraGranted,
                locationPermissionGranted = locationGranted,
                backgroundLocationPermissionGranted = backgroundLocationGranted,
                mockLocationPermissionGranted = mockLocationGranted,
            )
        }
    }

    override fun onCleared() {
        super.onCleared()
        mockRefreshJob?.cancel()
        locationMocker?.cleanup()
    }
}
