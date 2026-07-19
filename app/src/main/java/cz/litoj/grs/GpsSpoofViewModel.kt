package cz.litoj.grs

import androidx.lifecycle.ViewModel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update

/**
 * UI state for GPS Spoof screen
 */
data class UiState(
    val currentCoordinates: GpsCoordinates? = null,
    val selectedFormat: CoordinateFormat = CoordinateFormat.AUTO,
    val lastRawText: String = "",
    val pendingScan: Boolean = false,
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
 * Holds coordinate state and parses OCR text. Mocking is handled by the UI layer.
 */
class GpsSpoofViewModel : ViewModel() {

    private val _uiState = MutableStateFlow(UiState())
    val uiState: StateFlow<UiState> = _uiState.asStateFlow()

    private val _events = Channel<GpsEvent>(Channel.BUFFERED)
    val events = _events.receiveAsFlow()

    companion object {
        /** Max allowed difference (in degrees) between current and newly-read coordinates. */
        private const val MAX_COORDINATE_DIFF_DEG = 0.5
    }

    /**
     * Set the pending-scan flag. Set by [MainActivity] when the "Scan & Mock" shortcut
     * is used; observed by [cz.litoj.grs.ui.CameraPreviewSection] to trigger a burst scan.
     */
    fun setPendingScan(value: Boolean) {
        _uiState.update { it.copy(pendingScan = value) }
    }

    /**
     * Emit a mock error event to be shown to the user.
     */
    fun emitMockError(message: String) {
        _events.trySend(GpsEvent.MockError(message))
    }

    /**
     * Called when text is recognized from the camera. Parses coordinates and updates state.
     *
     * @return true if valid coordinates were parsed from the text, false otherwise.
     */
    fun onTextRecognized(text: String): Boolean {
        val normalized = GpsCoordinateParser.normalizeOcrText(text)
        _uiState.update { it.copy(lastRawText = normalized) }

        if (normalized.isBlank()) {
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
     * Apply coordinates as the new current value.
     * Called from OCR parsing or when the user accepts pending coordinates.
     */
    fun applyCoordinates(coords: GpsCoordinates) {
        _uiState.update { it.copy(currentCoordinates = coords) }
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
     * Update latitude from manual editing. Parses the value and updates coordinates.
     */
    fun updateLatitude(text: String) {
        val coords = _uiState.value.currentCoordinates
        val selected = _uiState.value.selectedFormat
        val displayFormat =
            if (selected == CoordinateFormat.AUTO) coords?.format
                ?: CoordinateFormat.DEGREES else selected

        val parsedLat = GpsCoordinateParser.parseLatitude(text, displayFormat)
        if (parsedLat != null) {
            _uiState.update {
                it.copy(currentCoordinates = GpsCoordinates(
                    parsedLat,
                    coords?.longitude ?: 0.0,
                    coords?.format ?: CoordinateFormat.DEGREES
                ))
            }
        }
    }

    /**
     * Update longitude from manual editing. Parses the value and updates coordinates.
     */
    fun updateLongitude(text: String) {
        val coords = _uiState.value.currentCoordinates
        val selected = _uiState.value.selectedFormat
        val displayFormat =
            if (selected == CoordinateFormat.AUTO) coords?.format
                ?: CoordinateFormat.DEGREES else selected

        val parsedLon = GpsCoordinateParser.parseLongitude(text, displayFormat)
        if (parsedLon != null) {
            _uiState.update {
                it.copy(currentCoordinates = GpsCoordinates(
                    coords?.latitude ?: 0.0,
                    parsedLon,
                    coords?.format ?: CoordinateFormat.DEGREES
                ))
            }
        }
    }

    /**
     * Update coordinate format selection.
     */
    fun setCoordinateFormat(format: CoordinateFormat) {
        _uiState.update { it.copy(selectedFormat = format) }
    }
}
