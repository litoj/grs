package cz.litoj.grs

import android.content.Context
import android.location.LocationManager
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.mockito.Mockito.`when`
import org.mockito.Mockito.mock

class LocationMockerTest {

    private lateinit var context: Context
    private lateinit var locationManager: LocationManager

    @Before
    fun setUp() {
        context = mock(Context::class.java)
        locationManager = mock(LocationManager::class.java)
        `when`(context.getSystemService(Context.LOCATION_SERVICE)).thenReturn(locationManager)
    }

    @After
    fun tearDown() {
        // no-op
    }

    // --- startMocking validation ---

    @Test
    fun `startMocking with latitude above 90 returns false`() {
        val mocker = LocationMocker(context)
        assertFalse(mocker.startMocking(91.0, 14.0))
        assertFalse(mocker.startMocking(-91.0, 14.0))
    }

    @Test
    fun `startMocking with longitude above 180 returns false`() {
        val mocker = LocationMocker(context)
        assertFalse(mocker.startMocking(50.0, 181.0))
        assertFalse(mocker.startMocking(50.0, -181.0))
    }

    @Test
    fun `startMocking with boundary values returns true`() {
        val mocker = LocationMocker(context)
        assertTrue(mocker.startMocking(90.0, 180.0))
        assertTrue(mocker.startMocking(-90.0, -180.0))
        assertTrue(mocker.startMocking(0.0, 0.0))
        mocker.stopMocking()
    }

    // --- startMocking success path ---

    @Test
    fun `startMocking with valid coordinates returns true`() {
        val mocker = LocationMocker(context)
        assertTrue(mocker.startMocking(50.0, 14.0))
        mocker.stopMocking()
    }

    @Test
    fun `startMocking when already mocking returns true without re-adding`() {
        val mocker = LocationMocker(context)
        assertTrue(mocker.startMocking(50.0, 14.0))
        assertTrue(mocker.startMocking(51.0, 15.0)) // no-op, returns true
        mocker.stopMocking()
    }

    // --- stopMocking / updateMockLocation ---

    @Test
    fun `updateMockLocation when not mocking returns false`() {
        val mocker = LocationMocker(context)
        assertFalse(mocker.updateMockLocation(50.0, 14.0))
    }

    @Test
    fun `stopMocking prevents further updates`() {
        val mocker = LocationMocker(context)
        mocker.startMocking(50.0, 14.0)
        assertTrue(mocker.updateMockLocation(50.0, 14.0))
        mocker.stopMocking()
        assertFalse(mocker.updateMockLocation(50.0, 14.0))
    }

    @Test
    fun `cleanup stops mocking`() {
        val mocker = LocationMocker(context)
        mocker.startMocking(50.0, 14.0)
        mocker.cleanup()
        assertFalse(mocker.updateMockLocation(50.0, 14.0))
    }
}
