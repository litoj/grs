package cz.litoj.grs

import android.content.Context
import android.content.SharedPreferences
import androidx.lifecycle.LifecycleOwner
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.mockito.ArgumentMatchers.anyBoolean
import org.mockito.ArgumentMatchers.anyInt
import org.mockito.ArgumentMatchers.anyString
import org.mockito.Mockito.`when`
import org.mockito.Mockito.mock
import org.mockito.Mockito.times
import org.mockito.Mockito.verify

class CameraReaderServiceTest {

    private lateinit var context: Context
    private lateinit var sharedPreferences: SharedPreferences
    private lateinit var editor: SharedPreferences.Editor
    private lateinit var lifecycleOwner: LifecycleOwner
    private lateinit var textRecognizer: TextRecognizer

    @Before
    fun setUp() {
        context = mock(Context::class.java)
        sharedPreferences = mock(SharedPreferences::class.java)
        editor = mock(SharedPreferences.Editor::class.java)
        lifecycleOwner = mock(LifecycleOwner::class.java)
        textRecognizer = mock(TextRecognizer::class.java)

        `when`(context.getSharedPreferences(anyString(), anyInt())).thenReturn(sharedPreferences)
        `when`(sharedPreferences.edit()).thenReturn(editor)
        `when`(editor.putBoolean(anyString(), anyBoolean())).thenReturn(editor)
    }

    @After
    fun tearDown() {
        // no-op
    }

    private fun newService(): CameraReaderService {
        return CameraReaderService(
            context = context,
            lifecycleOwner = lifecycleOwner,
            onTextRecognized = { false },
            textRecognizer = textRecognizer,
        )
    }

    // --- setAutoScan ---

    @Test
    fun `setAutoScan true enables auto scan and persists preference`() {
        val service = newService()
        service.setAutoScan(true)

        assertTrue(service.autoScan.value)
        verify(editor).putBoolean("auto_scan", true)
        verify(editor).apply()
    }

    @Test
    fun `setAutoScan false disables auto scan and resets scan state`() {
        val service = newService()
        service.setAutoScan(true)
        service.setAutoScan(false)

        assertFalse(service.autoScan.value)
        assertFalse(service.scanState.value)
        verify(editor).putBoolean("auto_scan", false)
        verify(editor, times(2)).apply()
    }

    // --- toggleAutoScan ---

    @Test
    fun `toggleAutoScan flips autoScan value`() {
        val service = newService()

        assertFalse(service.autoScan.value)

        service.toggleAutoScan()
        assertTrue(service.autoScan.value)

        service.toggleAutoScan()
        assertFalse(service.autoScan.value)
    }

    // --- startScanning ---

    @Test
    fun `startScanning sets scanState to true`() {
        val service = newService()

        service.startScanning()
        assertTrue(service.scanState.value)
    }

    // --- autoScan initial value from preferences ---

    @Test
    fun `autoScan defaults to true when no preference stored`() {
        `when`(sharedPreferences.getBoolean("auto_scan", true)).thenReturn(true)

        val service = newService()
        assertTrue(service.autoScan.value)
    }

    @Test
    fun `autoScan reads stored preference`() {
        `when`(sharedPreferences.getBoolean("auto_scan", true)).thenReturn(false)

        val service = newService()
        assertFalse(service.autoScan.value)
    }
}
