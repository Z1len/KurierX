package cz.courierledger.ocr

import android.content.Context
import android.net.Uri
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

data class OcrLine(
    val text: String,
    val left: Int,
    val top: Int,
    val right: Int,
    val bottom: Int,
    val confidence: Float?
) {
    val centerY: Int get() = (top + bottom) / 2
    val centerX: Int get() = (left + right) / 2
    val height: Int get() = (bottom - top).coerceAtLeast(1)
}

data class OcrText(
    val text: String,
    val confidence: Double,
    val lines: List<OcrLine> = emptyList(),
    val elements: List<OcrLine> = emptyList()
)

class OcrEngine(private val context: Context) {
    private val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)

    suspend fun recognize(uri: Uri): OcrText {
        val image = InputImage.fromFilePath(context, uri)
        return suspendCancellableCoroutine { cont ->
            recognizer.process(image)
                .addOnSuccessListener { result ->
                    val elements = result.textBlocks.flatMap { it.lines }.flatMap { it.elements }
                    val confidences = elements.mapNotNull { it.confidence.takeIf { c -> c > 0f } }
                    val confidence = if (confidences.isEmpty()) 0.65 else confidences.average().coerceIn(0.0, 1.0)
                    val lines = result.textBlocks
                        .flatMap { it.lines }
                        .mapNotNull { line ->
                            val box = line.boundingBox ?: return@mapNotNull null
                            OcrLine(
                                text = line.text,
                                left = box.left,
                                top = box.top,
                                right = box.right,
                                bottom = box.bottom,
                                confidence = line.confidence.takeIf { it > 0f }
                            )
                        }
                    val positionedElements = result.textBlocks
                        .flatMap { it.lines }
                        .flatMap { it.elements }
                        .mapNotNull { element ->
                            val box = element.boundingBox ?: return@mapNotNull null
                            OcrLine(
                                text = element.text,
                                left = box.left,
                                top = box.top,
                                right = box.right,
                                bottom = box.bottom,
                                confidence = element.confidence.takeIf { it > 0f }
                            )
                        }
                    cont.resume(OcrText(result.text, confidence, lines, positionedElements))
                }
                .addOnFailureListener { cont.resumeWithException(it) }
        }
    }
}
