package com.aoshi.auto_mobile.automation

import android.content.Context
import android.graphics.Bitmap
import android.util.Log
import ai.onnxruntime.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.opencv.android.Utils
import org.opencv.core.*
import org.opencv.imgproc.Imgproc
import java.io.BufferedReader
import java.io.InputStreamReader
import java.nio.FloatBuffer

/**
 * PaddleOCR v6 ONNX Runtime 引擎
 * 模型文件需放置在 assets/paddle_models/ 目录：
 * - ch_PP-OCRv6_det_infer.onnx (~3MB)
 * - ch_PP-OCRv6_rec_infer.onnx (~11MB)
 * - ppocr_keys_v1.txt (字典文件)
 */
class PaddleOcrEngine(private val context: Context) {
    companion object {
        private const val TAG = "PaddleOCR"
        private const val DET_MODEL = "paddle_models/ch_PP-OCRv6_det_infer.onnx"
        private const val REC_MODEL = "paddle_models/ch_PP-OCRv6_rec_infer.onnx"
        private const val DICT_FILE = "paddle_models/ppocr_keys_v1.txt"
        
        // 检测模型参数
        private const val DET_INPUT_SIZE = 960
        private const val DET_THRESHOLD = 0.3f
        
        // 识别模型参数
        private const val REC_HEIGHT = 48
        private const val REC_WIDTH = 320
    }

    data class OcrResult(
        val text: String,
        val confidence: Float,
        val box: List<Point>  // 四个角点坐标
    )

    private var ortEnv: OrtEnvironment? = null
    private var detSession: OrtSession? = null
    private var recSession: OrtSession? = null
    private var dictionary: List<String> = emptyList()
    private var initialized = false

    suspend fun initialize() = withContext(Dispatchers.IO) {
        try {
            Log.d(TAG, "initialize: 开始加载 PaddleOCR 模型")
            
            // 初始化 ONNX Runtime 环境
            ortEnv = OrtEnvironment.getEnvironment()
            
            // 加载检测模型
            context.assets.open(DET_MODEL).use { inputStream ->
                val modelBytes = inputStream.readBytes()
                detSession = ortEnv!!.createSession(modelBytes, OrtSession.SessionOptions())
                Log.d(TAG, "initialize: 检测模型加载成功 (${modelBytes.size} bytes)")
            }
            
            // 加载识别模型
            context.assets.open(REC_MODEL).use { inputStream ->
                val modelBytes = inputStream.readBytes()
                recSession = ortEnv!!.createSession(modelBytes, OrtSession.SessionOptions())
                Log.d(TAG, "initialize: 识别模型加载成功 (${modelBytes.size} bytes)")
            }
            
            // 加载字典
            dictionary = context.assets.open(DICT_FILE).use { inputStream ->
                BufferedReader(InputStreamReader(inputStream, Charsets.UTF_8))
                    .readLines()
                    .map { it.trim() }
                    .filter { it.isNotEmpty() }
            }
            Log.d(TAG, "initialize: 字典加载成功 (${dictionary.size} 个字符)")
            
            initialized = true
            Log.d(TAG, "initialize: PaddleOCR 初始化完成")
        } catch (e: Exception) {
            Log.e(TAG, "initialize: PaddleOCR 初始化失败", e)
            release()
            throw e
        }
    }

    fun isInitialized() = initialized

    suspend fun recognize(bitmap: Bitmap): List<OcrResult> = withContext(Dispatchers.IO) {
        if (!initialized) {
            throw IllegalStateException("PaddleOCR 未初始化，请先调用 initialize()")
        }
        
        try {
            // 1. 文本检测
            val boxes = detectTextBoxes(bitmap)
            if (boxes.isEmpty()) {
                Log.d(TAG, "recognize: 未检测到文本区域")
                return@withContext emptyList()
            }
            
            // 2. 文本识别
            val results = boxes.mapNotNull { box ->
                try {
                    val croppedBitmap = cropRotatedRect(bitmap, box)
                    val (text, confidence) = recognizeText(croppedBitmap)
                    croppedBitmap.recycle()
                    
                    if (text.isNotEmpty() && confidence > 0.5f) {
                        OcrResult(text, confidence, box)
                    } else null
                } catch (e: Exception) {
                    Log.w(TAG, "recognize: 识别文本框失败", e)
                    null
                }
            }
            
            Log.d(TAG, "recognize: 检测到 ${boxes.size} 个文本框，成功识别 ${results.size} 个")
            results
        } catch (e: Exception) {
            Log.e(TAG, "recognize: OCR 识别失败", e)
            emptyList()
        }
    }

    private fun detectTextBoxes(bitmap: Bitmap): List<List<Point>> {
        // 转换为 OpenCV Mat
        val mat = Mat()
        Utils.bitmapToMat(bitmap, mat)
        Imgproc.cvtColor(mat, mat, Imgproc.COLOR_RGBA2BGR)
        
        // 调整尺寸保持长边为 DET_INPUT_SIZE
        val scale = DET_INPUT_SIZE.toFloat() / maxOf(mat.width(), mat.height())
        val newWidth = (mat.width() * scale).toInt()
        val newHeight = (mat.height() * scale).toInt()
        Imgproc.resize(mat, mat, Size(newWidth.toDouble(), newHeight.toDouble()))
        
        // 归一化到 [0, 1]
        mat.convertTo(mat, CvType.CV_32FC3, 1.0 / 255.0)
        
        // 转换为 NCHW 格式的 FloatBuffer
        val inputBuffer = FloatBuffer.allocate(1 * 3 * newHeight * newWidth)
        val channels = ArrayList<Mat>(3)
        Core.split(mat, channels)
        
        for (c in 0..2) {
            val channelData = FloatArray(newWidth * newHeight)
            channels[c].get(0, 0, channelData)
            inputBuffer.put(channelData)
        }
        inputBuffer.rewind()
        
        // 执行推理
        val inputTensor = OnnxTensor.createTensor(ortEnv!!, inputBuffer, longArrayOf(1, 3, newHeight.toLong(), newWidth.toLong()))
        val outputs = detSession!!.run(mapOf("x" to inputTensor))
        
        // 解析输出（简化版：后处理需要形态学操作和轮廓检测）
        // TODO: 完整实现需要解析概率图并进行后处理
        val boxes = mutableListOf<List<Point>>()
        
        outputs.close()
        inputTensor.close()
        mat.release()
        channels.forEach { it.release() }
        
        return boxes
    }

    private fun recognizeText(bitmap: Bitmap): Pair<String, Float> {
        // 转换为 OpenCV Mat
        val mat = Mat()
        Utils.bitmapToMat(bitmap, mat)
        Imgproc.cvtColor(mat, mat, Imgproc.COLOR_RGBA2GRAY)
        
        // 调整尺寸为固定高度，宽度按比例缩放
        val scale = REC_HEIGHT.toFloat() / mat.height()
        val newWidth = minOf((mat.width() * scale).toInt(), REC_WIDTH)
        Imgproc.resize(mat, mat, Size(newWidth.toDouble(), REC_HEIGHT.toDouble()))
        
        // Padding 到固定宽度
        if (newWidth < REC_WIDTH) {
            Core.copyMakeBorder(mat, mat, 0, 0, 0, REC_WIDTH - newWidth, Core.BORDER_CONSTANT, Scalar(0.0))
        }
        
        // 归一化
        mat.convertTo(mat, CvType.CV_32FC1, 1.0 / 255.0)
        mat.convertTo(mat, CvType.CV_32FC1, 2.0, -1.0)  // 标准化到 [-1, 1]
        
        // 转换为 NCHW 格式 (1, 1, 48, 320)
        val inputBuffer = FloatBuffer.allocate(1 * 1 * REC_HEIGHT * REC_WIDTH)
        val data = FloatArray(REC_HEIGHT * REC_WIDTH)
        mat.get(0, 0, data)
        inputBuffer.put(data)
        inputBuffer.rewind()
        
        // 执行推理
        val inputTensor = OnnxTensor.createTensor(ortEnv!!, inputBuffer, longArrayOf(1, 1, REC_HEIGHT.toLong(), REC_WIDTH.toLong()))
        val outputs = recSession!!.run(mapOf("x" to inputTensor))
        
        // 解析输出（CTC 解码）
        // TODO: 完整实现需要 CTC 解码和 beam search
        val text = ""
        val confidence = 0.0f
        
        outputs.close()
        inputTensor.close()
        mat.release()
        
        return text to confidence
    }

    private fun cropRotatedRect(source: Bitmap, box: List<Point>): Bitmap {
        // TODO: 实现旋转矩形裁剪
        return source
    }

    fun release() {
        detSession?.close()
        recSession?.close()
        detSession = null
        recSession = null
        initialized = false
        Log.d(TAG, "release: PaddleOCR 资源已释放")
    }
}
