package com.guardeye

import android.graphics.Bitmap
import android.graphics.Color
import org.tensorflow.lite.Interpreter
import java.io.FileInputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.MappedByteBuffer
import java.nio.channels.FileChannel
import kotlin.math.max

/**
 * YOLOv8n 目标检测 + 颜色分析
 * 检测目标：person / motorcycle + 交警制服（深蓝色）/ 警用摩托车（白蓝色）
 */
class Detector(private val modelPath: String) {

    private var interpreter: Interpreter? = null

    // COCO 类别（COCO 80类）
    private val cocoClasses = listOf(
        "person","bicycle","car","motorcycle","airplane","bus","train","truck","boat",
        "traffic light","fire hydrant","stop sign","parking meter","bench","bird","cat",
        "dog","horse","sheep","cow","elephant","bear","zebra","giraffe","backpack",
        "umbrella","handbag","tie","suitcase","frisbee","skis","snowboard","sports ball",
        "kite","baseball bat","baseball glove","skateboard","surfboard","tennis racket",
        "bottle","wine glass","cup","fork","knife","spoon","bowl","banana","apple",
        "sandwich","orange","broccoli","carrot","hot dog","pizza","donut","cake","chair",
        "couch","potted plant","bed","dining table","toilet","tv","laptop","mouse",
        "remote","keyboard","cell phone","microwave","oven","toaster","sink","refrigerator",
        "book","clock","vase","scissors","teddy bear","hair drier","toothbrush"
    )
    // COCO 类别索引：person=0, motorcycle=3

    companion object {
        private const val INPUT_SIZE = 640
        private const val CONFIDENCE_THRESHOLD = 0.45f
        private const val IOU_THRESHOLD = 0.45f

        // 交警制服主色（RGB）
        private val UNIFORM_DARK_BLUE = intArrayOf(0, 28, 72)      // #001C48
        private val UNIFORM_BLACK = intArrayOf(18, 18, 18)          // #121212
        private val UNIFORM_REFLECTIVE = intArrayOf(220, 220, 220) // 反光条灰白

        // 警用摩托车特征色
        private val POLICE_WHITE = intArrayOf(220, 230, 250)        // 警摩白
        private val POLICE_BLUE = intArrayOf(30, 60, 140)          // 警摩蓝
        private val POLICE_LIGHT_BLUE = intArrayOf(180, 210, 240)  // 浅蓝
    }

    fun load(): Boolean {
        return try {
            val model = loadModelFile(modelPath)
            interpreter = Interpreter(model)
            true
        } catch (e: Exception) {
            e.printStackTrace()
            false
        }
    }

    fun close() {
        interpreter?.close()
        interpreter = null
    }

    private fun loadModelFile(path: String): MappedByteBuffer {
        val file = java.io.File(path)
        val inputStream = FileInputStream(file)
        val fileChannel = inputStream.channel
        return fileChannel.map(FileChannel.MapMode.READ_ONLY, 0, file.length())
    }

    /**
     * 检测结果
     */
    data class Detection(
        val label: String,
        val confidence: Float,
        val box: FloatArray, // [left, top, right, bottom] (0-1 归一化)
        val isPolice: Boolean
    )

    /**
     * 检测 Bitmpap 中是否包含目标
     * @return Detection 列表，或 null（模型未加载）
     */
    fun detect(bitmap: Bitmap): List<Detection>? {
        val interp = interpreter ?: return null

        // 预处理：resize + 归一化 + 转 ByteBuffer
        val resized = Bitmap.createScaledBitmap(bitmap, INPUT_SIZE, INPUT_SIZE, true)
        val imgData = ByteBuffer.allocateDirect(1 * INPUT_SIZE * INPUT_SIZE * 3)
        imgData.order(ByteOrder.nativeOrder())

        val pixels = IntArray(INPUT_SIZE * INPUT_SIZE)
        resized.getPixels(pixels, 0, INPUT_SIZE, 0, 0, INPUT_SIZE, INPUT_SIZE)
        for (pixel in pixels) {
            imgData.put(((pixel shr 16 and 0xFF) / 255f * 255).toInt().toByte())
            imgData.put(((pixel shr 8  and 0xFF) / 255f * 255).toInt().toByte())
            imgData.put(((pixel and 0xFF) / 255f * 255).toInt().toByte())
        }

        // YOLOv8 输出：[1, 84, 8400] (COCO 80类 + 4坐标 + 1置信度)
        val output = Array(1) { Array(84) { FloatArray(8400) } }
        interp.run(imgData, output)

        // 后处理：提取 bounding box + NMS
        val w = bitmap.width.toFloat()
        val h = bitmap.height.toFloat()
        val results = mutableListOf<Detection>()

        // 遍历 8400 个检测点
        for (i in 0 until 8400) {
            val objConf = output[0][4][i]
            if (objConf < CONFIDENCE_THRESHOLD) continue

            // 找出最高置信度的类别
            var maxConf = 0f
            var maxClass = -1
            for (c in 0 until 80) {
                val conf = output[0][c + 5][i]
                if (conf > maxConf) {
                    maxConf = conf
                    maxClass = c
                }
            }
            val score = objConf * maxConf
            if (score < CONFIDENCE_THRESHOLD) continue

            val label = cocoClasses.getOrNull(maxClass) ?: continue

            // 只关注 person 和 motorcycle
            if (label != "person" && label != "motorcycle") continue

            // 解析 box: [cx, cy, w, h] → [x1, y1, x2, y2]
            val cx = output[0][0][i] / INPUT_SIZE
            val cy = output[0][1][i] / INPUT_SIZE
            val bw = output[0][2][i] / INPUT_SIZE
            val bh = output[0][3][i] / INPUT_SIZE
            val x1 = max(0f, cx - bw / 2)
            val y1 = max(0f, cy - bh / 2)
            val x2 = min(1f, cx + bw / 2)
            val y2 = min(1f, cy + bh / 2)

            // 颜色分析：判断是否疑似警用
            val isPolice = analyzePolice(bitmap, label, x1 * w, y1 * h, x2 * w, y2 * h)

            results.add(Detection(label, score, floatArrayOf(x1, y1, x2, y2), isPolice))
        }

        // NMS
        return nms(results)
    }

    /**
     * 颜色分析：判断是否为警用车辆/人员
     */
    private fun analyzePolice(bitmap: Bitmap, label: String, x1: Float, y1: Float, x2: Float, y2: Float): Boolean {
        val w = bitmap.width
        val h = bitmap.height
        val rx1 = (x1 * w).toInt().coerceIn(0, w - 1)
        val ry1 = (y1 * h).toInt().coerceIn(0, h - 1)
        val rx2 = (x2 * w).toInt().coerceIn(0, w - 1)
        val ry2 = (y2 * h).toInt().coerceIn(0, h - 1)

        if (rx2 <= rx1 || ry2 <= ry1) return false

        val region = Bitmap.createBitmap(bitmap, rx1, ry1, rx2 - rx1, ry2 - ry1)
        return when (label) {
            "person" -> analyzeUniformColor(region)
            "motorcycle" -> analyzePoliceVehicle(region)
            else -> false
        }
    }

    private fun analyzeUniformColor(bmp: Bitmap): Boolean {
        var darkBlueCount = 0
        var blackCount = 0
        var reflectiveCount = 0
        var total = 0

        val step = max(1, (bmp.width * bmp.height) / 2000) // 采样不超过 2000 点
        for (y in 0 until bmp.height step step) {
            for (x in 0 until bmp.width step step) {
                val pixel = bmp.getPixel(x, y)
                val r = Color.red(pixel)
                val g = Color.green(pixel)
                val b = Color.blue(pixel)

                if (isCloseTo(r, g, b, UNIFORM_DARK_BLUE, 45)) darkBlueCount++
                else if (isCloseTo(r, g, b, UNIFORM_BLACK, 20)) blackCount++
                else if (isCloseTo(r, g, b, UNIFORM_REFLECTIVE, 60) && b > r && b > g) reflectiveCount++
                total++
            }
        }
        if (total == 0) return false
        val dbr = darkBlueCount.toFloat() / total
        val br = blackCount.toFloat() / total
        val rr = reflectiveCount.toFloat() / total

        // 深蓝/黑色占 15% 以上且有反光条 → 可能是交警制服
        return (dbr > 0.10 || br > 0.20) && rr > 0.03
    }

    private fun analyzePoliceVehicle(bmp: Bitmap): Boolean {
        var whiteCount = 0
        var blueCount = 0
        var total = 0

        val step = max(1, (bmp.width * bmp.height) / 2000)
        for (y in 0 until bmp.height step step) {
            for (x in 0 until bmp.width step step) {
                val pixel = bmp.getPixel(x, y)
                val r = Color.red(pixel)
                val g = Color.green(pixel)
                val b = Color.blue(pixel)

                if (isCloseTo(r, g, b, POLICE_WHITE, 50)) whiteCount++
                else if (isCloseTo(r, g, b, POLICE_BLUE, 45)) blueCount++
                else if (isCloseTo(r, g, b, POLICE_LIGHT_BLUE, 45)) blueCount++
                total++
            }
        }
        if (total == 0) return false
        // 警摩特征：白 + 蓝 同时存在
        return whiteCount > total * 0.15 && blueCount > total * 0.15
    }

    private fun isCloseTo(r: Int, g: Int, b: Int, target: IntArray, threshold: Int): Boolean {
        return kotlin.math.abs(r - target[0]) <= threshold &&
                kotlin.math.abs(g - target[1]) <= threshold &&
                kotlin.math.abs(b - target[2]) <= threshold
    }

    private fun nms(detections: List<Detection>): List<Detection> {
        if (detections.isEmpty()) return emptyList()
        val sorted = detections.sortedByDescending { it.confidence }
        val keep = mutableListOf<Detection>()
        val used = BooleanArray(sorted.size) { false }
        for (i in sorted.indices) {
            if (used[i]) continue
            keep.add(sorted[i])
            for (j in (i + 1) until sorted.size) {
                if (used[j]) continue
                if (iou(sorted[i].box, sorted[j].box) > IOU_THRESHOLD) used[j] = true
            }
        }
        return keep
    }

    private fun iou(a: FloatArray, b: FloatArray): Float {
        val x1 = max(a[0], b[0])
        val y1 = max(a[1], b[1])
        val x2 = min(a[2], b[2])
        val y2 = min(a[3], b[3])
        if (x2 <= x1 || y2 <= y1) return 0f
        val inter = (x2 - x1) * (y2 - y1)
        val areaA = (a[2] - a[0]) * (a[3] - a[1])
        val areaB = (b[2] - b[0]) * (b[3] - b[1])
        return inter / (areaA + areaB - inter)
    }
}
