package cz.litoj.grs

import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GpsSpoofViewModelTest {

    private fun viewModel() = GpsSpoofViewModel()

    // --- onTextRecognized ---

    @Test
    fun `onTextRecognized with valid coordinates updates state`() {
        val vm = viewModel()
        val result = vm.onTextRecognized("N 50.123456 E 14.456789")

        assertTrue(result)
        val coords = vm.uiState.value.currentCoordinates
        assertNotNull(coords)
        assertEquals(50.123456, coords!!.latitude, 0.0001)
        assertEquals(14.456789, coords.longitude, 0.0001)
    }

    @Test
    fun `onTextRecognized with invalid text returns false`() {
        val vm = viewModel()
        val result = vm.onTextRecognized("no coordinates here")

        assertFalse(result)
        assertNull(vm.uiState.value.currentCoordinates)
    }

    @Test
    fun `onTextRecognized with blank text returns false`() {
        val vm = viewModel()
        val result = vm.onTextRecognized("   ")

        assertFalse(result)
        assertNull(vm.uiState.value.currentCoordinates)
    }

    @Test
    fun `onTextRecognized updates lastRawText`() {
        val vm = viewModel()
        vm.onTextRecognized("N 50.123 E 14.456")

        assertEquals("N 50.123 E 14.456", vm.uiState.value.lastRawText)
    }

    // --- Coordinates too far ---

    @Test
    fun `onTextRecognized too far from current does not apply coordinates`() {
        val vm = viewModel()
        vm.applyCoordinates(GpsCoordinates(50.0, 14.0))

        // New coordinates differ by > 0.5 degrees
        vm.onTextRecognized("N 51.0 E 15.0")

        // State should NOT have changed (pending confirmation)
        assertEquals(50.0, vm.uiState.value.currentCoordinates!!.latitude, 0.0001)
        assertEquals(14.0, vm.uiState.value.currentCoordinates!!.longitude, 0.0001)
    }

    @Test
    fun `onTextRecognized close to current applies directly`() {
        val vm = viewModel()
        vm.applyCoordinates(GpsCoordinates(50.0, 14.0))

        // Within 0.5 degree threshold
        vm.onTextRecognized("N 50.1 E 14.1")

        assertEquals(50.1, vm.uiState.value.currentCoordinates!!.latitude, 0.0001)
        assertEquals(14.1, vm.uiState.value.currentCoordinates!!.longitude, 0.0001)
    }

    // --- applyCoordinates ---

    @Test
    fun `applyCoordinates updates currentCoordinates`() {
        val vm = viewModel()
        val coords = GpsCoordinates(50.123, 14.456)

        vm.applyCoordinates(coords)

        assertEquals(coords, vm.uiState.value.currentCoordinates)
    }

    // --- setPendingScan ---

    @Test
    fun `setPendingScan updates flag`() {
        val vm = viewModel()

        vm.setPendingScan(true)
        assertTrue(vm.uiState.value.pendingScan)

        vm.setPendingScan(false)
        assertFalse(vm.uiState.value.pendingScan)
    }

    // --- setCoordinateFormat ---

    @Test
    fun `setCoordinateFormat updates selectedFormat`() {
        val vm = viewModel()

        vm.setCoordinateFormat(CoordinateFormat.DEGREES_MINUTES)
        assertEquals(CoordinateFormat.DEGREES_MINUTES, vm.uiState.value.selectedFormat)

        vm.setCoordinateFormat(CoordinateFormat.DEGREES_MINUTES_SECONDS)
        assertEquals(CoordinateFormat.DEGREES_MINUTES_SECONDS, vm.uiState.value.selectedFormat)
    }

    // --- setCameraSuspended ---

    @Test
    fun `setCameraSuspended updates flag`() {
        val vm = viewModel()

        vm.setCameraSuspended(true)
        assertTrue(vm.uiState.value.isCameraSuspended)

        vm.setCameraSuspended(false)
        assertFalse(vm.uiState.value.isCameraSuspended)
    }

    // --- updateLatitude ---

    @Test
    fun `updateLatitude with valid input updates coordinates`() {
        val vm = viewModel()
        vm.applyCoordinates(GpsCoordinates(50.0, 14.0, CoordinateFormat.DEGREES))

        vm.updateLatitude("N 51.5")

        assertEquals(51.5, vm.uiState.value.currentCoordinates!!.latitude, 0.0001)
        assertEquals(14.0, vm.uiState.value.currentCoordinates!!.longitude, 0.0001)
    }

    @Test
    fun `updateLatitude with invalid input does not change coordinates`() {
        val vm = viewModel()
        vm.applyCoordinates(GpsCoordinates(50.0, 14.0, CoordinateFormat.DEGREES))

        vm.updateLatitude("not a coordinate")

        assertEquals(50.0, vm.uiState.value.currentCoordinates!!.latitude, 0.0001)
    }

    @Test
    fun `updateLatitude preserves longitude and format`() {
        val vm = viewModel()
        vm.applyCoordinates(GpsCoordinates(50.0, 14.456, CoordinateFormat.DEGREES))

        vm.updateLatitude("N 51.0")

        assertEquals(14.456, vm.uiState.value.currentCoordinates!!.longitude, 0.0001)
        assertEquals(CoordinateFormat.DEGREES, vm.uiState.value.currentCoordinates!!.format)
    }

    @Test
    fun `updateLatitude with negative value applies south`() {
        val vm = viewModel()
        vm.applyCoordinates(GpsCoordinates(50.0, 14.0, CoordinateFormat.DEGREES))

        vm.updateLatitude("S 51.5")

        assertEquals(-51.5, vm.uiState.value.currentCoordinates!!.latitude, 0.0001)
    }

    // --- updateLongitude ---

    @Test
    fun `updateLongitude with valid input updates coordinates`() {
        val vm = viewModel()
        vm.applyCoordinates(GpsCoordinates(50.0, 14.0, CoordinateFormat.DEGREES))

        vm.updateLongitude("E 15.5")

        assertEquals(50.0, vm.uiState.value.currentCoordinates!!.latitude, 0.0001)
        assertEquals(15.5, vm.uiState.value.currentCoordinates!!.longitude, 0.0001)
    }

    @Test
    fun `updateLongitude with invalid input does not change coordinates`() {
        val vm = viewModel()
        vm.applyCoordinates(GpsCoordinates(50.0, 14.0, CoordinateFormat.DEGREES))

        vm.updateLongitude("not a coordinate")

        assertEquals(14.0, vm.uiState.value.currentCoordinates!!.longitude, 0.0001)
    }

    @Test
    fun `updateLongitude preserves latitude and format`() {
        val vm = viewModel()
        vm.applyCoordinates(GpsCoordinates(50.123, 14.0, CoordinateFormat.DEGREES))

        vm.updateLongitude("E 15.0")

        assertEquals(50.123, vm.uiState.value.currentCoordinates!!.latitude, 0.0001)
        assertEquals(CoordinateFormat.DEGREES, vm.uiState.value.currentCoordinates!!.format)
    }

    @Test
    fun `updateLongitude with negative value applies west`() {
        val vm = viewModel()
        vm.applyCoordinates(GpsCoordinates(50.0, 14.0, CoordinateFormat.DEGREES))

        vm.updateLongitude("W 15.5")

        assertEquals(-15.5, vm.uiState.value.currentCoordinates!!.longitude, 0.0001)
    }

    // --- onOcrTextEdited ---

    @Test
    fun `onOcrTextEdited with valid text applies coordinates`() {
        val vm = viewModel()

        vm.onOcrTextEdited("N 50.123456 E 14.456789")

        assertEquals(50.123456, vm.uiState.value.currentCoordinates!!.latitude, 0.0001)
        assertEquals(14.456789, vm.uiState.value.currentCoordinates!!.longitude, 0.0001)
    }

    @Test
    fun `onOcrTextEdited with invalid text does not change state`() {
        val vm = viewModel()
        vm.applyCoordinates(GpsCoordinates(50.0, 14.0))

        vm.onOcrTextEdited("no coordinates here")

        assertEquals(50.0, vm.uiState.value.currentCoordinates!!.latitude, 0.0001)
        assertEquals(14.0, vm.uiState.value.currentCoordinates!!.longitude, 0.0001)
    }

    // --- Event emission ---

    @Test
    fun `emitMockError emits MockError event`() = runBlocking {
        val vm = viewModel()

        vm.emitMockError()

        val event = vm.events.first()
        assertTrue(event is GpsEvent.MockError)
    }

    @Test
    fun `updateLatitude with invalid input emits InvalidInput event`() = runBlocking {
        val vm = viewModel()
        vm.applyCoordinates(GpsCoordinates(50.0, 14.0, CoordinateFormat.DEGREES))

        vm.updateLatitude("not a coordinate")

        val event = vm.events.first()
        assertTrue(event is GpsEvent.InvalidInput)
    }

    @Test
    fun `updateLongitude with invalid input emits InvalidInput event`() = runBlocking {
        val vm = viewModel()
        vm.applyCoordinates(GpsCoordinates(50.0, 14.0, CoordinateFormat.DEGREES))

        vm.updateLongitude("not a coordinate")

        val event = vm.events.first()
        assertTrue(event is GpsEvent.InvalidInput)
    }

    // --- Formatting (pure numbers, no hemisphere letters) ---

    @Test
    fun `latitudeString DEGREES format outputs signed decimal`() {
        val north = GpsCoordinates(50.123456, 14.0)
        assertEquals("50.123456", north.latitudeString(CoordinateFormat.DEGREES))

        val south = GpsCoordinates(-50.123456, 14.0)
        assertEquals("-50.123456", south.latitudeString(CoordinateFormat.DEGREES))
    }

    @Test
    fun `longitudeString DEGREES format outputs signed decimal`() {
        val east = GpsCoordinates(50.0, 14.456789)
        assertEquals("14.456789", east.longitudeString(CoordinateFormat.DEGREES))

        val west = GpsCoordinates(50.0, -14.456789)
        assertEquals("-14.456789", west.longitudeString(CoordinateFormat.DEGREES))
    }

    @Test
    fun `latitudeString DM format outputs absolute value`() {
        val north = GpsCoordinates(50.16750, 14.0)
        assertEquals("50°10.0500'", north.latitudeString(CoordinateFormat.DEGREES_MINUTES))

        val south = GpsCoordinates(-50.16750, 14.0)
        assertEquals("50°10.0500'", south.latitudeString(CoordinateFormat.DEGREES_MINUTES))
    }

    @Test
    fun `longitudeString DMS format outputs absolute value`() {
        val east = GpsCoordinates(50.0, 14.48750)
        assertEquals("14°29'15.00\"", east.longitudeString(CoordinateFormat.DEGREES_MINUTES_SECONDS))

        val west = GpsCoordinates(50.0, -14.48750)
        assertEquals("14°29'15.00\"", west.longitudeString(CoordinateFormat.DEGREES_MINUTES_SECONDS))
    }
}
