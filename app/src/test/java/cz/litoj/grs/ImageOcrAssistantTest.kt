package cz.litoj.grs

import android.graphics.Bitmap
import android.graphics.Bitmap.Config
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.mockito.Mockito.`when`
import org.mockito.Mockito.mock

class ImageOcrAssistantTest {

    private lateinit var tempDir: java.io.File
    private lateinit var context: android.content.Context

    @Before
    fun setUp() {
        tempDir = java.io.File(java.lang.System.getProperty("java.io.tmpdir"), "grs_test_${System.currentTimeMillis()}")
        tempDir.mkdirs()

        context = mock(android.content.Context::class.java)
        `when`(context.cacheDir).thenReturn(tempDir)
    }

    @After
    fun tearDown() {
        tempDir.deleteRecursively()
    }

    // --- computeSampleSize ---

    @Test
    fun `computeSampleSize returns 1 when image already smaller than maxDimension`() {
        val assistant = ImageOcrAssistant(
            context = context,
            onTextRecognized = { false },
            textRecognizer = mock(TextRecognizer::class.java),
            targetDimensionOverride = 1000,
        )

        assertEquals(1, assistant.computeSampleSize(100, 100, 200))
        assertEquals(1, assistant.computeSampleSize(200, 100, 200))
        assertEquals(1, assistant.computeSampleSize(100, 200, 200))
    }

    @Test
    fun `computeSampleSize returns power-of-2 sample size for larger images`() {
        val assistant = ImageOcrAssistant(
            context = context,
            onTextRecognized = { false },
            textRecognizer = mock(TextRecognizer::class.java),
            targetDimensionOverride = 1000,
        )

        assertEquals(4, assistant.computeSampleSize(4000, 3000, 1000))
        assertEquals(2, assistant.computeSampleSize(2000, 2000, 1000))
        assertEquals(8, assistant.computeSampleSize(8000, 8000, 1000))
    }

    // --- applyOrientation ---

    @Test
    fun `applyOrientation normal orientation returns same bitmap`() {
        val assistant = ImageOcrAssistant(
            context = context,
            onTextRecognized = { false },
            textRecognizer = mock(TextRecognizer::class.java),
            targetDimensionOverride = 1000,
        )

        val bitmap = mock(Bitmap::class.java)
        val result = assistant.applyOrientation(
            bitmap,
            android.media.ExifInterface.ORIENTATION_NORMAL
        )
        assertSame(bitmap, result)
    }

    @Test
    fun `applyOrientation unknown orientation returns same bitmap`() {
        val assistant = ImageOcrAssistant(
            context = context,
            onTextRecognized = { false },
            textRecognizer = mock(TextRecognizer::class.java),
            targetDimensionOverride = 1000,
        )

        val bitmap = mock(Bitmap::class.java)
        val result = assistant.applyOrientation(
            bitmap,
            -1 // unknown orientation value falls through to else branch
        )
        assertSame(bitmap, result)
    }
}
