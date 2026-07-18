package cz.litoj.grs

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertNotNull
import org.junit.Test

class GpsCoordinateParserTest {

    // --- Direction letter consistency tests ---

    @Test
    fun `both suffix - letter before coordinate`() {
        val result = GpsCoordinateParser.parseFromText("N 50.123456 E 14.456789")
        assertNotNull(result)
        assertEquals(50.123456, result!!.coordinates.latitude, 0.0001)
        assertEquals(14.456789, result.coordinates.longitude, 0.0001)
    }

    @Test
    fun `both prefix - letter after coordinate`() {
        val result = GpsCoordinateParser.parseFromText("50.123456 N 14.456789 E")
        assertNotNull(result)
        assertEquals(50.123456, result!!.coordinates.latitude, 0.0001)
        assertEquals(14.456789, result.coordinates.longitude, 0.0001)
    }

    @Test
    fun `mixed positions - lat suffix, lon prefix - should return null`() {
        val result = GpsCoordinateParser.parseFromText("N 50.123 14.456 E")
        assertNull(result)
    }

    @Test
    fun `mixed positions - lat prefix, lon suffix - should return null`() {
        val result = GpsCoordinateParser.parseFromText("50.123 N E 14.456")
        assertNull(result)
    }

    // --- Missing direction letter tests ---

    @Test
    fun `missing E-W letter - should return null`() {
        val result = GpsCoordinateParser.parseFromText("N 50.123")
        assertNull(result)
    }

    @Test
    fun `no direction letters at all - should return null`() {
        val result = GpsCoordinateParser.parseFromText("50.123 14.456")
        assertNull(result)
    }

    // --- Sign handling tests ---

    @Test
    fun `south and west signs applied`() {
        val result = GpsCoordinateParser.parseFromText("S 50.123 W 14.456")
        assertNotNull(result)
        assertEquals(-50.123, result!!.coordinates.latitude, 0.0001)
        assertEquals(-14.456, result.coordinates.longitude, 0.0001)
    }

    @Test
    fun `south and west signs applied - prefix format`() {
        val result = GpsCoordinateParser.parseFromText("50.123 S 14.456 W")
        assertNotNull(result)
        assertEquals(-50.123, result!!.coordinates.latitude, 0.0001)
        assertEquals(-14.456, result.coordinates.longitude, 0.0001)
    }

    // --- Multi-part coordinate tests ---

    @Test
    fun `DM without decimal minutes - suffix`() {
        val result = GpsCoordinateParser.parseFromText("N 50°10' E 14°29'")
        assertNotNull(result)
        assertEquals(50.1667, result!!.coordinates.latitude, 0.001)
        assertEquals(14.4833, result.coordinates.longitude, 0.001)
    }

    @Test
    fun `DM without decimal minutes - prefix`() {
        val result = GpsCoordinateParser.parseFromText("50°10'N 14°29'E")
        assertNotNull(result)
        assertEquals(50.1667, result!!.coordinates.latitude, 0.001)
        assertEquals(14.4833, result.coordinates.longitude, 0.001)
    }

    @Test
    fun `DMS format - suffix`() {
        val result = GpsCoordinateParser.parseFromText("N 50°10'30\" E 14°29'15\"")
        assertNotNull(result)
        assertEquals(50.175, result!!.coordinates.latitude, 0.0001)
        assertEquals(14.4875, result.coordinates.longitude, 0.0001)
    }

    @Test
    fun `DMS format - prefix`() {
        val result = GpsCoordinateParser.parseFromText("50°10'30\"N 14°29'15\"E")
        assertNotNull(result)
        assertEquals(50.175, result!!.coordinates.latitude, 0.0001)
        assertEquals(14.4875, result.coordinates.longitude, 0.0001)
    }

    @Test
    fun `DMS with decimal seconds - suffix`() {
        val result = GpsCoordinateParser.parseFromText("N 50°10'30.5\" E 14°29'15.5\"")
        assertNotNull(result)
        assertEquals(50.17514, result!!.coordinates.latitude, 0.0001)
        assertEquals(14.48764, result.coordinates.longitude, 0.0001)
    }

    @Test
    fun `DM with decimal minutes - suffix`() {
        val result = GpsCoordinateParser.parseFromText("N 50°10.050 E 14°29.123")
        assertNotNull(result)
        assertEquals(50.16750, result!!.coordinates.latitude, 0.0001)
        assertEquals(14.48538, result.coordinates.longitude, 0.0001)
    }

    @Test
    fun `DM with decimal minutes - prefix`() {
        val result = GpsCoordinateParser.parseFromText("50°10.050'N 14°29.123'E")
        assertNotNull(result)
        assertEquals(50.16750, result!!.coordinates.latitude, 0.0001)
        assertEquals(14.48538, result.coordinates.longitude, 0.0001)
    }

    @Test
    fun `multi-part mixed positions should return null`() {
        val result = GpsCoordinateParser.parseFromText("N 50°10' 14°29'E")
        assertNull(result)
    }

    // --- Comma-separated format tests ---

    @Test
    fun `comma-separated without direction letters returns null`() {
        val result = GpsCoordinateParser.parseFromText("50.123456, 14.456789")
        assertNull(result)
    }

    @Test
    fun `comma-separated with direction letters works`() {
        val result = GpsCoordinateParser.parseFromText("N 50.123456, E 14.456789")
        assertNotNull(result)
        assertEquals(50.123456, result!!.coordinates.latitude, 0.0001)
        assertEquals(14.456789, result.coordinates.longitude, 0.0001)
    }

    // --- Direction letter embedded in words ---

    @Test
    fun `direction letters in words are ignored`() {
        // "inReach" contains "N" and "E" but they should not be treated as direction letters
        // because they're part of a word
        val result = GpsCoordinateParser.parseFromText("N 50.123456 E 14.456789 inReach GARMIN")
        assertNotNull(result)
        assertEquals(50.123456, result!!.coordinates.latitude, 0.0001)
        assertEquals(14.456789, result.coordinates.longitude, 0.0001)
    }

    @Test
    fun `text before coordinates`() {
        val result = GpsCoordinateParser.parseFromText("Location: N 50.123456 E 14.456789")
        assertNotNull(result)
        assertEquals(50.123456, result!!.coordinates.latitude, 0.0001)
        assertEquals(14.456789, result.coordinates.longitude, 0.0001)
    }

    @Test
    fun `text after coordinates`() {
        val result = GpsCoordinateParser.parseFromText("N 50.123456 E 14.456789 confirmed")
        assertNotNull(result)
        assertEquals(50.123456, result!!.coordinates.latitude, 0.0001)
        assertEquals(14.456789, result.coordinates.longitude, 0.0001)
    }

    @Test
    fun `text before and after coordinates`() {
        val result = GpsCoordinateParser.parseFromText("GPS: N 50.123456 E 14.456789 on map")
        assertNotNull(result)
        assertEquals(50.123456, result!!.coordinates.latitude, 0.0001)
        assertEquals(14.456789, result.coordinates.longitude, 0.0001)
    }

    // --- Flipped coordinate order tests ---

    @Test
    fun `flipped order - both suffix - E before N`() {
        val result = GpsCoordinateParser.parseFromText("E 14.456789 N 50.123456")
        assertNotNull(result)
        assertEquals(50.123456, result!!.coordinates.latitude, 0.0001)
        assertEquals(14.456789, result.coordinates.longitude, 0.0001)
    }

    @Test
    fun `flipped order - both prefix - E before N`() {
        val result = GpsCoordinateParser.parseFromText("14.456789 E 50.123456 N")
        assertNotNull(result)
        assertEquals(50.123456, result!!.coordinates.latitude, 0.0001)
        assertEquals(14.456789, result.coordinates.longitude, 0.0001)
    }

    @Test
    fun `flipped order - south and west - both suffix`() {
        val result = GpsCoordinateParser.parseFromText("W 14.456 S 50.123")
        assertNotNull(result)
        assertEquals(-50.123, result!!.coordinates.latitude, 0.0001)
        assertEquals(-14.456, result.coordinates.longitude, 0.0001)
    }

    @Test
    fun `flipped order - south and west - both prefix`() {
        val result = GpsCoordinateParser.parseFromText("14.456 W 50.123 S")
        assertNotNull(result)
        assertEquals(-50.123, result!!.coordinates.latitude, 0.0001)
        assertEquals(-14.456, result.coordinates.longitude, 0.0001)
    }

    // --- OCR error handling ---

    @Test
    fun `OCR errors in digits are corrected`() {
        // O instead of 0
        val result = GpsCoordinateParser.parseFromText("N 5O.123456 E 14.456789")
        assertNotNull(result)
        assertEquals(50.123456, result!!.coordinates.latitude, 0.0001)
        assertEquals(14.456789, result.coordinates.longitude, 0.0001)
    }
}
