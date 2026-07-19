package cz.litoj.grs

import android.graphics.Bitmap
import android.util.Log
import androidx.camera.core.ExperimentalGetImage
import androidx.camera.core.ImageProxy
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.Text
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

private const val TAG = "TextRecognizer"

/**
 * Wrapper around ML Kit Text Recognition for processing camera frames.
 * Crops the center scan-box region before running OCR to reduce noise.
 */
class TextRecognizer {

    private val textRecognizer =
        TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)

    /**
     * Process an image frame and extract text from the center scan-box region.
     * Closes the imageProxy when done.
     */
    suspend fun recognizeText(imageProxy: ImageProxy): String? {
        return try {
            val inputImage = imageProxy.toCroppedInputImage()
            val result = processImage(inputImage)
            result?.text
        } catch (e: Exception) {
            Log.e(TAG, "recognizeText error", e)
            null
        } finally {
            imageProxy.close()
        }
    }

    /**
     * Convert ImageProxy to a cropped InputImage.
     * Crops the center region to match the scan box overlay.
     * The scan box is ~85% width and ~120dp height centered on screen.
     */
    @androidx.annotation.OptIn(ExperimentalGetImage::class)
    private fun ImageProxy.toCroppedInputImage(): InputImage {
        val bitmap = this.toBitmap()
        val rotationDegrees = this.imageInfo.rotationDegrees

        Log.d(
            TAG,
            "Original bitmap: ${bitmap.width}x${bitmap.height}, rotation=$rotationDegrees"
        )

        // toBitmap() returns the image in sensor orientation (not rotated for display).
        // For a phone in portrait with 90° rotation, the bitmap is landscape (e.g. 480x640).
        //
        // The scan box overlay is:
        //   - 85% of the DISPLAY width
        //   - 120dp of the DISPLAY height (roughly 15-20% of screen height)
        //
        // In sensor space (before rotation):
        //   - Display width maps to bitmap height (for 90° rotation)
        //   - Display height maps to bitmap width (for 90° rotation)
        //
        // So we crop:
        //   - 85% of bitmap height (display width)
        //   - ~20% of bitmap width (display height, approximating 120dp)

        // The scan box overlay is 85% of display width and 120dp height.
        val (cropW, cropH) = if (rotationDegrees == 90 || rotationDegrees == 270) {
            val h = (bitmap.height * 0.85).toInt()
            val w = (bitmap.width * 0.15).toInt()
            w to h
        } else {
            val w = (bitmap.width * 0.85).toInt()
            val h = (bitmap.height * 0.15).toInt()
            w to h
        }

        val left = ((bitmap.width - cropW) / 2).coerceAtLeast(0)
        val top = ((bitmap.height - cropH) / 2).coerceAtLeast(0)
        val w = cropW.coerceAtMost(bitmap.width - left)
        val h = cropH.coerceAtMost(bitmap.height - top)

        Log.d(
            TAG,
            "Cropping to: left=$left top=$top w=$w h=$h (from ${bitmap.width}x${bitmap.height})"
        )

        val croppedBitmap = Bitmap.createBitmap(bitmap, left, top, w, h)
        return InputImage.fromBitmap(croppedBitmap, rotationDegrees)
    }

    /**
     * Process image with ML Kit and return recognition result
     */
    private suspend fun processImage(image: InputImage): Text? {
        return suspendCancellableCoroutine { continuation ->
            textRecognizer.process(image)
                .addOnSuccessListener { text ->
                    continuation.resume(text)
                }
                .addOnFailureListener { e ->
                    Log.e(TAG, "ML Kit process error", e)
                    if (!continuation.isCompleted) {
                        continuation.resume(null)
                    }
                }
        }
    }

    /**
     * Close the text recognizer and release resources
     */
    fun close() {
        textRecognizer.close()
    }
}
