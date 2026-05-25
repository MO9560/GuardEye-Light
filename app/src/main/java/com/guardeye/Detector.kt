package com.guardeye

import android.content.Context
import android.graphics.Bitmap
import org.tensorflow.lite.Interpreter
import org.tensorflow.lite.support.common.FileUtil
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * YOLOv8n detector using TensorFlow Lite.
 * Thread-safe — runs on a dedicated background thread.
 */
class Detector(private val context: Context) {

    // Confidence threshold for reporting a detection
    var threshold: Float = 0.5f

    private var interpreter: Interpreter? = null
    private var isReady = false

    /** COCO class names relevant for surveillance */
    private val cocoNames = listOf(
        "person","bicycle","car","motorbike","aeroplane","bus","train","truck",
        "boat","traffic light","fire hydrant","stop sign","parking meter",
        "bench","bird","cat","dog","horse","sheep","cow","elephant","bear",
        "zebra","giraffe","backpack","umbrella","handbag","tie","suitcase",
        "frisbee","skis","snowboard","sports ball","kite","baseball bat",
        "baseball glove","skateboard","surfboard","tennis racket","bottle",
        "wine glass","cup","fork","knife","spoon","bowl","banana","apple",
        "sandwich","orange","broccoli","carrot","hot dog","pizza","donut",
        "cake","chair","sofa","pottedplant","bed","diningtable","toilet",
        "tvmonitor","laptop","mouse","remote","keyboard","cell phone",
        "microwave","oven","toaster","sink","refrigerator","book","clock",
        "vase","scissors","teddy bear","hair drier","toothbrush"
    )

    /** Alert-worthy classes (police, ambulance, fire, person) */
    private val alertClasses = setOf(
        "person","bicycle","car","motorbike","bus","truck","ambulance",
        "fire hydrant","stop sign"
    )

    /** Load model from assets/filesDir.
     *  If yolov8n.tflite is in assets, copy to filesDir on first run. */
    fun load(): Boolean {
        return try {
            val modelFile = File(context.filesDir, "yolov8n.tflite")
            if (!modelFile.exists()) {
                // Copy from assets
                context.assets.open("yolov8n.tflite").use { input ->
                    modelFile.outputStream().use { output ->
                        input.copyTo(output)
                    }
                }
            }
            val options = Interpreter.Options().apply {
                numThreads = 4
            }
            interpreter = Interpreter(modelFile, options)
            isReady = true
            true
        } catch (e: Exception) {
            e.printStackTrace()
            false
        }
    }

    fun isReady() = isReady

    /** Run detection on a bitmap. Returns list of [label, confidence] pairs above threshold. */
    fun detect(bitmap: Bitmap): List<Pair<String, Float>> {
        val interp = interpreter ?: return emptyList()

        // YOLOv8n input: 640x640 RGB
        val inputSize = 640
        val inputBuffer = ByteBuffer.allocateDirect(1 * inputSize * inputSize * 3)
            .order(ByteOrder.nativeOrder())

        val scaled = Bitmap.createScaledBitmap(bitmap, inputSize, inputSize, true)
        for (y in 0 until inputSize) {
            for (x in 0 until inputSize) {
                val px = scaled.getPixel(x, y)
                inputBuffer.put((px shr 16 and 0xFF).toByte())    // R
                inputBuffer.put((px shr 8  and 0xFF).toByte())     // G
                inputBuffer.put((px and 0xFF).toByte())           // B
            }
        }
        inputBuffer.rewind()

        // YOLOv8n output shape: [1, 84, 8400] (84 = 4(box) + 80(classes))
        val outputBuffer = Array(1) { Array(84) { FloatArray(8400) } }
        interp.run(inputBuffer, outputBuffer)

        // Parse outputs (numDetections, label, confidence)
        val results = mutableListOf<Pair<String, Float>>()
        // Each of the 8400 columns: [x, y, w, h, class0_score, class1_score, ...]
        for (i in 0 until 8400) {
            var maxConf = 0f
            var bestClass = 0
            // Classes start at index 4
            for (c in 0 until 80) {
                val score = outputBuffer[0][4 + c][i]
                if (score > maxConf) {
                    maxConf = score
                    bestClass = c
                }
            }
            if (maxConf >= threshold && bestClass < cocoNames.size) {
                results.add(cocoNames[bestClass] to maxConf)
            }
        }

        // Sort by confidence descending
        return results.sortedByDescending { it.second }
    }

    /** Returns true if any detection is an alert-worthy class */
    fun hasAlert(results: List<Pair<String, Float>>): Boolean {
        return results.any { (label, _) -> label in alertClasses }
    }

    fun release() {
        interpreter?.close()
        interpreter = null
        isReady = false
    }
}
