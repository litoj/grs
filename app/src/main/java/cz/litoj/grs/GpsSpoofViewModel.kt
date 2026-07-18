package cz.litoj.grs

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import cz.litoj.grs.GpsSpoofViewModel.Companion.MAX_COORDINATE_DIFF_DEG
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
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
    val latitudeText: String = "",
    val longitudeText: String = "",
    val lastRawText: String = "",
)

/**
 * One-time UI events emitted by the ViewModel.
 */
sealed interface GpsEvent {
    /** Mock location failed — show the user an error message. */
    data class MockError(val message: String) : GpsEvent

    /** New coordinates differ too much from current — ask user to confirm. */
    data class PendingCoordinates(
        val coordinates: GpsCoordinates,
        val displayText: String
    ) : GpsEvent
}

/**
 * ViewModel for GPS Spoofing functionality.
 * Automatically mocks location whenever coordinates are available.
 */
class GpsSpoofViewModel : ViewModel() {

    private val _uiState = MutableStateFlow(UiState())
    val uiState: StateFlow<UiState> = _uiState.asStateFlow()

    private val _permissionState = MutableStateFlow(PermissionState())
    val permissionState: StateFlow<PermissionState> =
        _permissionState.asStateFlow()

    private val _events = Channel<GpsEvent>(Channel.BUFFERED)
    val events = _events.receiveAsFlow()

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
     * Called when text is recognized from the camera. Parses coordinates, updates state,
     * and automatically updates the mock location.
     *
     * @return true if valid coordinates were parsed from the text, false otherwise.
     */
    fun onTextRecognized(text: String): Boolean {
        // Always store raw text so user can see what OCR detected
        _uiState.update { it.copy(lastRawText = text) }

        if (text.isBlank()) {
            return false
        }

        val result = GpsCoordinateParser.parseFromText(text) ?: return false

        val current = _uiState.value.currentCoordinates
        if (current != null && isTooFar(current, result)) {
            // Coordinates differ too much — ask user to confirm via one-time event
            val displayText = "${result.latitudeString(result.format)} ${
                result.longitudeString(result.format)
            }"
            _events.trySend(GpsEvent.PendingCoordinates(result, displayText))
            return true
        }

        applyCoordinates(result)
        return true
    }

    /**
     * Apply coordinates as the new current value, update the text fields, and mock location.
     * Called from OCR parsing or when the user accepts pending coordinates.
     */
    fun applyCoordinates(coords: GpsCoordinates) {
        val selected = _uiState.value.selectedFormat
        val displayFormat =
            if (selected == CoordinateFormat.AUTO) coords.format else selected

        _uiState.update {
            it.copy(
                currentCoordinates = coords,
                latitudeText = coords.latitudeString(displayFormat),
                longitudeText = coords.longitudeString(displayFormat),
            )
        }

        // Automatically update mock location
        autoMock(coords)
    }

    /**
     * Check whether the new coordinates differ from the current ones by more than
     * [MAX_COORDINATE_DIFF_DEG] in either latitude or longitude.
     */
    private fun isTooFar(
        current: GpsCoordinates,
        new: GpsCoordinates
    ): Boolean {
        return kotlin.math.abs(current.latitude - new.latitude) > MAX_COORDINATE_DIFF_DEG ||
            kotlin.math.abs(current.longitude - new.longitude) > MAX_COORDINATE_DIFF_DEG
    }

    /**
     * Update latitude text from manual editing. Parses the value and updates coordinates.
     */
    fun updateLatitudeText(text: String) {
        val coords = _uiState.value.currentCoordinates
        val selected = _uiState.value.selectedFormat
        val displayFormat =
            if (selected == CoordinateFormat.AUTO) coords?.format
                ?: CoordinateFormat.DEGREES else selected

        _uiState.update { it.copy(latitudeText = text) }

        val parsedLat = GpsCoordinateParser.parseLatitude(text, displayFormat)
        if (parsedLat != null) {
            val newCoords = GpsCoordinates(
                parsedLat,
                coords?.longitude ?: 0.0,
                coords?.format ?: CoordinateFormat.DEGREES
            )
            _uiState.update { it.copy(currentCoordinates = newCoords) }
            autoMock(newCoords)
        }
    }

    /**
     * Update longitude text from manual editing. Parses the value and updates coordinates.
     */
    fun updateLongitudeText(text: String) {
        val coords = _uiState.value.currentCoordinates
        val selected = _uiState.value.selectedFormat
        val displayFormat =
            if (selected == CoordinateFormat.AUTO) coords?.format
                ?: CoordinateFormat.DEGREES else selected

        _uiState.update { it.copy(longitudeText = text) }

        val parsedLon = GpsCoordinateParser.parseLongitude(text, displayFormat)
        if (parsedLon != null) {
            val newCoords = GpsCoordinates(
                coords?.latitude ?: 0.0,
                parsedLon,
                coords?.format ?: CoordinateFormat.DEGREES
            )
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

        val displayFormat =
            if (format == CoordinateFormat.AUTO) coords.format else format

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
        if (locationMocker == null) {
            locationMocker = LocationMocker(ctx)

            if (locationMocker?.startMocking(
                    coordinates.latitude,
                    coordinates.longitude
                ) == true
            )
                startMockRefresh()
        } else {
            // Already mocking: just update the location
            locationMocker?.updateMockLocation(
                coordinates.latitude,
                coordinates.longitude
            )
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
                locationMocker?.updateMockLocation(
                    coords.latitude,
                    coords.longitude
                )
            }
        }
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
