package cz.litoj.grs

import android.util.Log
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.Text
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

private const val TAG = "TextRecognizer"

/**
 * Wrapper around ML Kit Text Recognition.
 * Runs OCR on a pre-cropped [InputImage] and returns the recognized text.
 */
class TextRecognizer {

    private val textRecognizer =
        TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)

    /**
     * Run text recognition on the given image.
     */
    suspend fun recognizeText(image: InputImage): String? {
        return try {
            processImage(image)?.text
        } catch (e: Exception) {
            Log.e(TAG, "recognizeText error", e)
            null
        }
    }

    /**
     * Process image with ML Kit and return recognition result.
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
     * Close the text recognizer and release resources.
     */
    fun close() {
        textRecognizer.close()
    }
}
