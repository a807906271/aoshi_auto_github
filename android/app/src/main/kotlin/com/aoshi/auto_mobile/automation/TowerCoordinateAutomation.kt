package com.aoshi.auto_mobile.automation

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Bitmap
import android.graphics.Path
import android.os.Handler
import android.os.Looper
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject

/**
 * 闯塔游戏画面坐标档案。所有值以 460 × 1024 基准归一化，运行时按截图尺寸缩放。
 */
object TowerCoordinateProfile {
    data class Point(val x: Double, val y: Double)
    data class Region(val left: Double, val top: Double, val right: Double, val bottom: Double) {
        fun crop(bitmap: Bitmap): Bitmap {
            val width = bitmap.width
            val height = bitmap.height
            val x = (left * width).toInt().coerceIn(0, width - 1)
            val y = (top * height).toInt().coerceIn(0, height - 1)
            val rightX = (right * width).toInt().coerceIn(x + 1, width)
            val bottomY = (bottom * height).toInt().coerceIn(y + 1, height)
            return Bitmap.createBitmap(bitmap, x, y, rightX - x, bottomY - y)
        }
    }

    // 点击目标坐标
    val enterBattle = Point(0.720, 0.900)       // 怒闯按钮（闯塔入口页右下角）
    val skipBattle = Point(0.850, 0.100)        // 跳过按钮（战斗页右上角）
    val confirm = Point(0.500, 0.700)           // 确定按钮（结算/奖励页居中）
    val safeClick = Point(0.500, 0.500)         // 安全点击区域（屏幕中央，用于呼出跳过按钮）
    val endTower = Point(0.300, 0.100)          // 结束闯塔按钮（左上角）
    
    // 加成选择槽位（3x2 网格布局，排除炼骨）
    val buffSlots = listOf(
        Point(0.250, 0.400), Point(0.500, 0.400), Point(0.750, 0.400),
        Point(0.250, 0.600), Point(0.500, 0.600), Point(0.750, 0.600)
    )

    // OCR 识别区域
    val fullScreen = Region(0.0, 0.0, 1.0, 1.0)     // 全屏文本识别
    val topBar = Region(0.0, 0.0, 1.0, 0.15)        // 顶部状态栏
    val centerText = Region(0.2, 0.3, 0.8, 0.7)     // 中央文本区域（加成/奖励描述）
    val bottomButtons = Region(0.0, 0.85, 1.0, 1.0) // 底部按钮区域
}

/**
 * 闯塔自动化控制器：使用 PaddleOCR 识别页面状态，自动选择加成、跳过战斗、领取奖励。
 * 
 * 状态机流程：
 * 1. ENTRY -> 识别到"怒闯"/"普通闯塔"/"精英闯塔" -> 点击进入战斗
 * 2. BATTLE -> 识别到战斗场景 -> 点击屏幕中央呼出跳过按钮
 * 3. REVEAL_SKIP -> 识别到"跳过"按钮 -> 点击跳过
 * 4. SKIP_CONFIRM -> 识别到"胜利"结算 -> 点击确定
 * 5. BRANCH -> 识别分支：
 *    - 加成页面 -> 选择非炼骨加成 -> 返回 ENTRY
 *    - 奖励页面 -> 点击领取 -> 返回 ENTRY
 *    - 结束闯塔 -> DONE
 */
class TowerCoordinateAutomation(
    private val service: AccessibilityService,
    private val paddleOcr: PaddleOcrEngine,
    private val onUpdate: (JSONObject) -> Unit
) {
    private val TAG = "TowerAutomation"
    private val handler = Handler(Looper.getMainLooper())
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    
    // 状态机
    private enum class Stage {
        IDLE, ENTRY, BATTLE, REVEAL_SKIP, SKIP_CONFIRM, BRANCH, DONE, FAILED
    }
    
    private data class Runtime(
        val stage: Stage = Stage.IDLE,
        val message: String = "",
        val rawOcr: String = "",
        val completedBattles: Int = 0,
        val failureReason: String? = null
    )
    
    private var runtime = Runtime()
    private var isRunning = false
    private var captureInProgress = false
    private var gestureCallbackReceived = false
    private var gestureTimeout: Runnable? = null
    
    // 重试配置
    private var stageRetryCount = 0
    private val maxStageRetries = 3
    private var gestureRetryCount = 0
    private val maxGestureRetries = 2
    
    fun start() {
        if (isRunning) {
            Log.w(TAG, "start: 闯塔任务已在运行")
            return
        }
        Log.i(TAG, "start: 开始闯塔自动化")
        isRunning = true
        runtime = Runtime(stage = Stage.ENTRY, message = "正在识别闯塔入口页")
        update(runtime)
        requestFrame()
    }
    
    fun stop() {
        if (!isRunning) return
        Log.i(TAG, "stop: 停止闯塔自动化")
        isRunning = false
        handler.removeCallbacksAndMessages(null)
        gestureTimeout?.let(handler::removeCallbacks)
        update(runtime.copy(stage = Stage.IDLE, message = "已停止"))
    }
    
    fun destroy() {
        stop()
        scope.cancel()
    }
    
    private fun update(newRuntime: Runtime) {
        runtime = newRuntime
        val json = JSONObject().apply {
            put("stage", runtime.stage.name)
            put("message", runtime.message)
            put("completedBattles", runtime.completedBattles)
            put("rawOcr", runtime.rawOcr)
            runtime.failureReason?.let { put("failureReason", it) }
        }
        onUpdate(json)
        Log.d(TAG, "update: stage=${runtime.stage} message=${runtime.message}")
    }
    
    private fun requestFrame() {
        if (!isRunning || captureInProgress) return
        captureInProgress = true
        scope.launch {
            try {
                val bitmap = captureScreen()
                if (bitmap != null) {
                    processFrame(bitmap)
                    bitmap.recycle()
                } else {
                    withContext(Dispatchers.Main) {
                        fail("截图失败")
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "requestFrame: 异常", e)
                withContext(Dispatchers.Main) {
                    fail("截图异常: ${e.message}")
                }
            } finally {
                captureInProgress = false
            }
        }
    }
    
    private suspend fun captureScreen(): Bitmap? {
        return withContext(Dispatchers.IO) {
            try {
                // 使用与 QiyuCoordinateAutomation 相同的截图方法
                val display = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R) {
                    service.display
                } else {
                    @Suppress("DEPRECATION")
                    service.getSystemService(android.view.WindowManager::class.java)
                        ?.defaultDisplay
                } ?: return@withContext null
                
                val metrics = service.resources.displayMetrics
                val width = metrics.widthPixels
                val height = metrics.heightPixels
                
                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R) {
                    val hardwareBuffer = android.media.ImageReader.newInstance(
                        width, height,
                        android.graphics.PixelFormat.RGBA_8888, 2
                    ).use { reader ->
                        display.getDisplayedContentSample(
                            reader.width, reader.height,
                            reader.surface, 0
                        )
                        reader.acquireLatestImage()?.use { image ->
                            val planes = image.planes
                            if (planes.isNotEmpty()) {
                                val buffer = planes[0].buffer
                                val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
                                bitmap.copyPixelsFromBuffer(buffer)
                                bitmap
                            } else null
                        }
                    }
                    hardwareBuffer
                } else {
                    // API < 30 fallback: 使用 UiAutomation (需要在 AccessibilityService 中配置)
                    null
                }
            } catch (e: Exception) {
                Log.e(TAG, "captureScreen: 异常", e)
                null
            }
        }
    }
    
    private suspend fun processFrame(bitmap: Bitmap) {
        val fullText = recognizeRegion(bitmap, TowerCoordinateProfile.fullScreen)
        val topText = recognizeRegion(bitmap, TowerCoordinateProfile.topBar)
        val centerText = recognizeRegion(bitmap, TowerCoordinateProfile.centerText)
        val bottomText = recognizeRegion(bitmap, TowerCoordinateProfile.bottomButtons)
        
        withContext(Dispatchers.Main) {
            consume(fullText, topText, centerText, bottomText)
        }
    }
    
    private suspend fun recognizeRegion(bitmap: Bitmap, region: TowerCoordinateProfile.Region): String {
        return withContext(Dispatchers.IO) {
            try {
                val cropped = region.crop(bitmap)
                val result = paddleOcr.recognize(cropped)
                cropped.recycle()
                result.text
            } catch (e: Exception) {
                Log.e(TAG, "recognizeRegion: OCR 异常", e)
                ""
            }
        }
    }
    
    private fun consume(fullText: String, topText: String, centerText: String, bottomText: String) {
        if (!isRunning) return
        
        val normalized = fullText.replace(Regex("\\s"), "")
        val rawOcr = "全屏=$fullText\n顶部=$topText\n中央=$centerText\n底部=$bottomText"
        
        Log.d(TAG, "consume: stage=${runtime.stage} normalized=$normalized")
        
        when (runtime.stage) {
            Stage.ENTRY -> handleEntry(normalized, rawOcr)
            Stage.BATTLE -> handleBattle(normalized, rawOcr)
            Stage.REVEAL_SKIP -> handleRevealSkip(normalized, rawOcr)
            Stage.SKIP_CONFIRM -> handleSkipConfirm(normalized, rawOcr)
            Stage.BRANCH -> handleBranch(normalized, centerText, rawOcr)
            Stage.DONE, Stage.FAILED, Stage.IDLE -> { /* 终态，不处理 */ }
        }
    }
    
    private fun handleEntry(text: String, rawOcr: String) {
        when {
            // 识别到闯塔入口页
            text.contains("怒闯") || text.contains("普通闯塔") || text.contains("精英闯塔") || 
            text.contains("秘境") || text.contains("竞技") -> {
                stageRetryCount = 0
                tap(
                    TowerCoordinateProfile.enterBattle,
                    Stage.BATTLE,
                    "识别到闯塔入口，点击怒闯",
                    rawOcr
                )
            }
            // 重试或失败
            else -> retryOrFail("闯塔入口页", rawOcr)
        }
    }
    
    private fun handleBattle(text: String, rawOcr: String) {
        when {
            // 识别到战斗场景（缺少跳过按钮）
            !text.contains("跳过") && (text.contains("攻击") || text.contains("技能") || 
            text.contains("血量") || text.isEmpty()) -> {
                stageRetryCount = 0
                tap(
                    TowerCoordinateProfile.safeClick,
                    Stage.REVEAL_SKIP,
                    "进入战斗，点击屏幕呼出跳过",
                    rawOcr
                )
            }
            // 已经有跳过按钮
            text.contains("跳过") -> {
                stageRetryCount = 0
                update(runtime.copy(
                    stage = Stage.REVEAL_SKIP,
                    message = "检测到跳过按钮",
                    rawOcr = rawOcr
                ))
                handler.postDelayed({ requestFrame() }, 500)
            }
            else -> retryOrFail("战斗页", rawOcr)
        }
    }
    
    private fun handleRevealSkip(text: String, rawOcr: String) {
        when {
            text.contains("跳过") -> {
                stageRetryCount = 0
                tap(
                    TowerCoordinateProfile.skipBattle,
                    Stage.SKIP_CONFIRM,
                    "点击跳过战斗",
                    rawOcr,
                    completedBattles = runtime.completedBattles + 1
                )
            }
            else -> retryOrFail("跳过按钮", rawOcr)
        }
    }
    
    private fun handleSkipConfirm(text: String, rawOcr: String) {
        when {
            // 识别到胜利结算
            text.contains("胜利") || text.contains("战斗结束") || text.contains("确定") -> {
                stageRetryCount = 0
                tap(
                    TowerCoordinateProfile.confirm,
                    Stage.BRANCH,
                    "战斗胜利，点击确定",
                    rawOcr
                )
            }
            else -> retryOrFail("胜利结算页", rawOcr)
        }
    }
    
    private fun handleBranch(text: String, centerText: String, rawOcr: String) {
        when {
            // 检测到结束闯塔按钮
            text.contains("结束闯塔") || text.contains("退出") -> {
                stageRetryCount = 0
                update(runtime.copy(
                    stage = Stage.DONE,
                    message = "闯塔完成，共完成 ${runtime.completedBattles} 场战斗",
                    rawOcr = rawOcr
                ))
                stop()
            }
            // 奖励页面
            text.contains("奖励") && text.contains("领取") -> {
                stageRetryCount = 0
                tap(
                    TowerCoordinateProfile.confirm,
                    Stage.ENTRY,
                    "领取奖励",
                    rawOcr
                )
            }
            // 加成页面
            text.contains("加成") || text.contains("选择") -> {
                stageRetryCount = 0
                selectBuff(centerText, rawOcr)
            }
            // 已经回到闯塔入口
            text.contains("怒闯") || text.contains("普通闯塔") || text.contains("精英闯塔") -> {
                stageRetryCount = 0
                update(runtime.copy(
                    stage = Stage.ENTRY,
                    message = "返回闯塔入口",
                    rawOcr = rawOcr
                ))
                handler.postDelayed({ requestFrame() }, 800)
            }
            else -> retryOrFail("分支页面（加成/奖励/继续）", rawOcr)
        }
    }
    
    private fun selectBuff(centerText: String, rawOcr: String) {
        // 识别加成选项，排除炼骨
        val normalized = centerText.replace(Regex("\\s"), "")
        
        // 检测是否包含炼骨（需要排除）
        val hasBoneDrill = normalized.contains("炼骨") || normalized.contains("练骨")
        
        if (hasBoneDrill) {
            Log.d(TAG, "selectBuff: 检测到炼骨加成，选择其他加成")
        }
        
        // 简单策略：点击第一个非炼骨槽位（实际应根据 OCR 结果智能选择）
        // TODO: 需要更精确的 OCR 定位每个加成选项的位置
        val targetSlot = if (hasBoneDrill) {
            // 假设炼骨在第一个位置，选择第二个
            TowerCoordinateProfile.buffSlots.getOrNull(1) ?: TowerCoordinateProfile.buffSlots[0]
        } else {
            TowerCoordinateProfile.buffSlots[0]
        }
        
        tap(
            targetSlot,
            Stage.ENTRY,
            "选择加成（排除炼骨）",
            rawOcr
        )
    }
    
    private fun retryOrFail(expectedPageName: String, rawOcr: String) {
        if (stageRetryCount < maxStageRetries) {
            stageRetryCount++
            update(runtime.copy(
                message = "等待$expectedPageName（重试 $stageRetryCount/$maxStageRetries）",
                rawOcr = rawOcr
            ))
            handler.postDelayed({ requestFrame() }, 1500)
        } else {
            stageRetryCount = 0
            fail("未识别到$expectedPageName，已超过最大重试次数")
        }
    }
    
    private fun fail(reason: String) {
        Log.e(TAG, "fail: $reason")
        isRunning = false
        handler.removeCallbacksAndMessages(null)
        update(runtime.copy(
            stage = Stage.FAILED,
            message = "失败: $reason",
            failureReason = reason
        ))
    }
    
    private fun tap(
        point: TowerCoordinateProfile.Point,
        nextStage: Stage,
        message: String,
        rawOcr: String,
        completedBattles: Int = runtime.completedBattles
    ) {
        Log.d(TAG, "tap: 发起点击 (${point.x}, ${point.y}) -> $nextStage, $message")
        gestureRetryCount = 0
        doTap(point, nextStage, message, rawOcr, completedBattles)
    }
    
    private fun doTap(
        point: TowerCoordinateProfile.Point,
        nextStage: Stage,
        message: String,
        rawOcr: String,
        completedBattles: Int
    ) {
        gestureCallbackReceived = false
        val width = service.resources.displayMetrics.widthPixels
        val height = service.resources.displayMetrics.heightPixels
        
        val path = Path().apply {
            moveTo((point.x * width).toFloat(), (point.y * height).toFloat())
        }
        val stroke = GestureDescription.StrokeDescription(path, 0, 60)
        val gesture = GestureDescription.Builder().addStroke(stroke).build()
        
        val accepted = service.dispatchGesture(
            gesture,
            object : AccessibilityService.GestureResultCallback() {
                override fun onCompleted(gestureDescription: GestureDescription?) {
                    gestureCallbackReceived = true
                    cancelGestureTimeout()
                    Log.d(TAG, "tap onCompleted: stage=$nextStage")
                    completeTap(nextStage, message, rawOcr, completedBattles)
                }
                
                override fun onCancelled(gestureDescription: GestureDescription?) {
                    gestureCallbackReceived = true
                    cancelGestureTimeout()
                    fail("点击被系统拒绝")
                }
            },
            null
        )
        
        if (!accepted) {
            fail("dispatchGesture 返回 false")
            return
        }
        
        // 设置超时
        gestureTimeout = Runnable {
            if (!gestureCallbackReceived) {
                if (gestureRetryCount < maxGestureRetries) {
                    gestureRetryCount++
                    Log.w(TAG, "tap: 手势回调超时，重试 $gestureRetryCount/$maxGestureRetries")
                    handler.postDelayed({
                        doTap(point, nextStage, message, rawOcr, completedBattles)
                    }, 500)
                } else {
                    fail("点击回调超时，已超过最大重试次数")
                }
            }
        }.also { handler.postDelayed(it, 5000) }
    }
    
    private fun completeTap(
        nextStage: Stage,
        message: String,
        rawOcr: String,
        completedBattles: Int
    ) {
        update(runtime.copy(
            stage = nextStage,
            message = message,
            rawOcr = rawOcr,
            completedBattles = completedBattles
        ))
        // 等待页面动画后重新截图
        handler.postDelayed({ requestFrame() }, 800)
    }
    
    private fun cancelGestureTimeout() {
        gestureTimeout?.let(handler::removeCallbacks)
        gestureTimeout = null
    }
}
