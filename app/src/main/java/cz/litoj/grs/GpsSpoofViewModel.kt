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
     * Apply coordinates as the new current value and update the text fields.
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
}
