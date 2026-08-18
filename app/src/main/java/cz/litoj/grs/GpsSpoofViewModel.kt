package cz.litoj.grs

import android.content.ComponentName
import android.graphics.Bitmap
import android.util.Log
import androidx.lifecycle.ViewModel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update

private const val TAG = "GpsSpoofViewModel"

/**
 * UI state for GPS Spoof screen
 */
data class UiState(
    val currentCoordinates: GpsCoordinates? = null,
    val selectedFormat: CoordinateFormat = CoordinateFormat.AUTO,
    val lastRawText: String = "",
    val pendingScan: Boolean = false,
    /** App to launch when coordinates are applied. Null = disabled. */
    val targetApp: ComponentName? = null,
    /**
     * True when the camera should be suspended (e.g. after loading coordinates from a file)
     * so that continuous OCR can't overwrite the loaded values.
     * Lifted by “Scan Now”, the scan shortcut, or re-enabling continuous scan.
     */
    val isCameraSuspended: Boolean = false,
)

/**
 * One-time UI events emitted by the ViewModel.
 */
enum class CoordinateField { LATITUDE, LONGITUDE }

sealed interface GpsEvent {
    /** Mock location failed — show the user an error message. */
    data object MockError : GpsEvent

    /** New coordinates differ too much from current — ask user to confirm. */
    data class PendingCoordinates(
        val coordinates: GpsCoordinates,
        val displayText: String
    ) : GpsEvent

    /**
     * Loaded-image OCR found no coordinates — offer to edit the OCR'd text and rematch.
     * [preview] is the loaded image zoomed into the detected text region with
     * block outlines (null when the image couldn't be decoded).
     */
    data class ImageOcrFailure(val rawText: String?, val preview: Bitmap?) : GpsEvent

    /** Manual coordinate input couldn't be parsed — notify the user. */
    data class InvalidInput(val field: CoordinateField) : GpsEvent
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
    fun emitMockError() {
        _events.trySend(GpsEvent.MockError)
    }

    /**
     * Loaded-image OCR produced text but no coordinates — offer the user a
     * chance to edit the OCR'd text and rematch manually, showing the loaded
     * image zoomed into the detected text region.
     */
    fun onImageOcrFailure(text: String?, preview: Bitmap?) {
        _events.trySend(GpsEvent.ImageOcrFailure(text, preview))
    }

    /**
     * Called when the user confirms edited OCR text from the failure dialog.
     * Parses it and applies coordinates directly (no "too far" guard — the
     * user just manually confirmed these).
     */
    fun onOcrTextEdited(text: String) {
        val normalized = GpsCoordinateParser.normalizeOcrText(text)
        GpsCoordinateParser.parseFromText(normalized)?.let { applyCoordinates(it) }
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

        val result = GpsCoordinateParser.parseFromText(normalized) ?: return false

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
     * Emits [GpsEvent.InvalidInput] when the text can't be parsed.
     */
    fun updateLatitude(text: String) {
        val coords = _uiState.value.currentCoordinates
        val parsedLat = GpsCoordinateParser.parseLatitude(text)
        if (parsedLat != null) {
            _uiState.update {
                it.copy(currentCoordinates = GpsCoordinates(
                    parsedLat,
                    coords?.longitude ?: 0.0,
                    coords?.format ?: CoordinateFormat.DEGREES
                ))
            }
        } else {
            Log.w(TAG, "Failed to parse latitude input: '$text'")
            _events.trySend(GpsEvent.InvalidInput(CoordinateField.LATITUDE))
        }
    }

    /**
     * Update longitude from manual editing. Parses the value and updates coordinates.
     * Emits [GpsEvent.InvalidInput] when the text can't be parsed.
     */
    fun updateLongitude(text: String) {
        val coords = _uiState.value.currentCoordinates
        val parsedLon = GpsCoordinateParser.parseLongitude(text)
        if (parsedLon != null) {
            _uiState.update {
                it.copy(currentCoordinates = GpsCoordinates(
                    coords?.latitude ?: 0.0,
                    parsedLon,
                    coords?.format ?: CoordinateFormat.DEGREES
                ))
            }
        } else {
            Log.w(TAG, "Failed to parse longitude input: '$text'")
            _events.trySend(GpsEvent.InvalidInput(CoordinateField.LONGITUDE))
        }
    }

    /**
     * Update coordinate format selection.
     */
    fun setCoordinateFormat(format: CoordinateFormat) {
        _uiState.update { it.copy(selectedFormat = format) }
    }

    /**
     * Set the camera-suspension flag.
     * Set to true when loading coordinates from a file (so OCR can't overwrite them);
     * set to false when the user explicitly scans again.
     */
    fun setCameraSuspended(value: Boolean) {
        _uiState.update { it.copy(isCameraSuspended = value) }
    }

    /** Set the app to launch when coordinates are applied. Null clears it. */
    fun setTargetApp(component: ComponentName?) {
        _uiState.update { it.copy(targetApp = component) }
    }
}
