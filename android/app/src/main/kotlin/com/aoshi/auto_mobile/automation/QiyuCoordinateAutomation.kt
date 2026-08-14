package com.aoshi.auto_mobile.automation

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Bitmap
import android.graphics.Path
import android.hardware.HardwareBuffer
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.Display
import com.google.android.gms.tasks.Tasks
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.Text
import com.google.mlkit.vision.text.chinese.ChineseTextRecognizerOptions
import com.google.mlkit.vision.text.TextRecognition
import java.math.BigDecimal
import java.math.BigInteger
import java.math.RoundingMode
import org.json.JSONArray
import org.json.JSONObject

/**
 * 奇遇游戏画面坐标档案。所有值以 460 × 1024 基准归一化，运行时按截图尺寸缩放。
 */
object QiyuCoordinateProfile {
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

    // 点击目标坐标。已与 p1/p2 标注对齐：start / inspect / open / slots 维持原值，
    // finish / confirm / divination / R2L 区域按 p2 红框校准。
    val start = Point(0.470, 0.849)             // 开启奇遇（p2 红框一致）
    val divination = Point(0.498, 0.580)        // 算卦（用户实测 adb tap 627 1624 有效，y 自 0.500 下移 0.08）
    val inspect = Point(0.297, 0.894)           // 查看宝箱
    val open = Point(0.691, 0.894)              // 开启宝箱
    val finish = Point(0.839, 0.300)            // 完成
    val confirm = Point(0.503, 0.739)           // 确定（结算弹框）

    // 宝箱页分数区域：实测"当前分数"文字块在顶部标题下，数字（金色大字）在文字正下方。
    // 数字横向范围宽（大分数如"273550分"需留足空间），纵向覆盖数字高度。
    val score = Region(75.0 / 460, 452.0 / 1024, 363.0 / 460, 534.0 / 1024)
    // 底部计数区域：宝箱页实测"剩余次数3"（OCR 常误识别"刻余次数3/利余次数3"）
    // 在底部按钮上方，只需覆盖数字区域（文字"剩余次数"不重要，parseCount 容错）。
    val viewCount = Region(171.0 / 460, 947.0 / 1024, 189.0 / 460, 975.0 / 1024)
    val openCount = Region(348.0 / 460, 947.0 / 1024, 366.0 / 460, 975.0 / 1024)
    val slots = listOf(
        Point(0.240, 0.689), Point(0.492, 0.689), Point(0.734, 0.689),
        Point(0.240, 0.781), Point(0.492, 0.781),
    )
    // 规则 OCR 区域，每个矩形覆盖对应 slot 正下方的规则文字。
    // 实测：规则区顶部距槽位中心约 69-76px，宽约 253-255px，高 45-56px。
    // 为覆盖各种规则文字（"+10"、"奇数×1.5"等），统一宽 260px 高 60px。
    val ruleRegions = listOf(
        Region(172.0 / 460, 1998.0 / 1024, 432.0 / 460, 2058.0 / 1024),  // slot[0] 下方
        Region(490.0 / 460, 1998.0 / 1024, 750.0 / 460, 2058.0 / 1024),  // slot[1] 下方
        Region(795.0 / 460, 1998.0 / 1024, 1055.0 / 460, 2058.0 / 1024), // slot[2] 下方
        Region(172.0 / 460, 2264.0 / 1024, 432.0 / 460, 2324.0 / 1024),  // slot[3] 下方
        Region(490.0 / 460, 2264.0 / 1024, 750.0 / 460, 2324.0 / 1024),  // slot[4] 下方
    )
}

sealed class QiyuRule(val raw: String, val display: String) {
    class Add(raw: String, val amount: BigInteger) : QiyuRule(raw, "+$amount")
    class Multiply(raw: String, val multiplier: BigDecimal) : QiyuRule(raw, "×${multiplier.stripTrailingZeros().toPlainString()}")
    class Replace(raw: String, val value: BigInteger) : QiyuRule(raw, "被${value}替换")
    class ReplaceOneWithNine(raw: String) : QiyuRule(raw, "1被9替换")
    class ConditionalAdd(raw: String, val odd: Boolean, val amount: BigInteger) : QiyuRule(raw, "${if (odd) "奇" else "偶"}数+$amount")
    class ConditionalMultiply(raw: String, val odd: Boolean, val multiplier: BigDecimal) : QiyuRule(raw, "${if (odd) "奇" else "偶"}数×${multiplier.stripTrailingZeros().toPlainString()}")
    class AddFirstDigit(raw: String, val amount: BigInteger) : QiyuRule(raw, "首位+$amount")
    class Reverse(raw: String) : QiyuRule(raw, "逆行排序")
    class SwapEnds(raw: String) : QiyuRule(raw, "首尾调换")
}

object QiyuRuleParser {
    private fun normalize(raw: String): String = raw
        .replace(Regex("[\\s，。:：]"), "")
        .replace('＋', '+').replace('＊', '*').replace('×', '*')
        .replace("倒序", "逆行排序").replace("首尾互换", "首尾调换")
        .replace('O', '0').replace('o', '0')

    fun parse(raw: String): QiyuRule? {
        val text = normalize(raw)
        val number = Regex("\\d+(?:\\.\\d+)?").findAll(text).lastOrNull()?.value
        return when {
            text.contains("1被9替换") -> QiyuRule.ReplaceOneWithNine(raw)
            Regex("被\\d+替换").containsMatchIn(text) -> QiyuRule.Replace(raw, Regex("被(\\d+)替换").find(text)!!.groupValues[1].toBigInteger())
            text.contains("逆行排序") -> QiyuRule.Reverse(raw)
            text.contains("首尾调换") -> QiyuRule.SwapEnds(raw)
            text.contains("首位") && text.contains('+') && number != null -> QiyuRule.AddFirstDigit(raw, number.toBigInteger())
            text.contains("奇数") && text.contains('*') && number != null -> QiyuRule.ConditionalMultiply(raw, true, number.toBigDecimal())
            text.contains("偶数") && text.contains('*') && number != null -> QiyuRule.ConditionalMultiply(raw, false, number.toBigDecimal())
            text.contains("奇数") && text.contains('+') && number != null -> QiyuRule.ConditionalAdd(raw, true, number.toBigInteger())
            text.contains("偶数") && text.contains('+') && number != null -> QiyuRule.ConditionalAdd(raw, false, number.toBigInteger())
            text.startsWith("*") && number != null -> QiyuRule.Multiply(raw, number.toBigDecimal())
            text.startsWith("+") && number != null -> QiyuRule.Add(raw, number.toBigInteger())
            else -> null
        }
    }
}

object QiyuScoreSolver {
    fun apply(current: BigInteger, rule: QiyuRule): BigInteger = when (rule) {
        is QiyuRule.Add -> current + rule.amount
        is QiyuRule.Multiply -> BigDecimal(current).multiply(rule.multiplier).setScale(0, RoundingMode.DOWN).toBigInteger()
        is QiyuRule.Replace -> rule.value
        is QiyuRule.ReplaceOneWithNine -> current.toString().replace('1', '9').toBigInteger()
        is QiyuRule.ConditionalAdd -> if (current.testBit(0) == rule.odd) current + rule.amount else current
        is QiyuRule.ConditionalMultiply -> if (current.testBit(0) == rule.odd) apply(current, QiyuRule.Multiply(rule.raw, rule.multiplier)) else current
        is QiyuRule.AddFirstDigit -> {
            val place = BigInteger.TEN.pow((current.toString().length - 1).coerceAtLeast(0))
            current + rule.amount * place
        }
        is QiyuRule.Reverse -> current.toString().reversed().toBigInteger()
        is QiyuRule.SwapEnds -> current.toString().let { digits ->
            if (digits.length < 2) current else "${digits.last()}${digits.substring(1, digits.length - 1)}${digits.first()}".toBigInteger()
        }
    }

    data class Plan(val order: List<Int>, val finalScore: BigInteger)

    fun bestPlan(score: BigInteger, rules: Map<Int, QiyuRule>, maxOpens: Int): Plan? {
        val length = minOf(maxOpens, rules.size)
        if (length == 0) return Plan(emptyList(), score)
        var best: Plan? = null
        fun visit(value: BigInteger, remaining: List<Int>, order: List<Int>) {
            if (order.size == length) {
                val candidate = Plan(order, value)
                if (best == null || candidate.finalScore > best!!.finalScore ||
                    (candidate.finalScore == best!!.finalScore && isLexicographicallyBefore(candidate.order, best!!.order))
                ) best = candidate
                return
            }
            remaining.forEach { index ->
                visit(apply(value, rules.getValue(index)), remaining - index, order + index)
            }
        }
        visit(score, rules.keys.sorted(), emptyList())
        return best
    }

    private fun isLexicographicallyBefore(left: List<Int>, right: List<Int>): Boolean {
        left.zip(right).firstOrNull { (a, b) -> a != b }?.let { (a, b) -> return a < b }
        return left.size < right.size
    }
}

/**
 * 由无障碍服务持有的奇遇状态机。每步只有一个点击，并在下一次截图确认预期页面后推进。
 *
 * 已知约束：目标游戏的无障碍通道不可靠，已实测确认——
 * 1. dispatchGesture 可能被系统接受后既不执行也不回调（vivo/Funtouch 设备实测：accepted=true
 *    但 onCompleted/onCancelled 均不触发，游戏无响应，无超时保护时状态机静默冻结）。
 * 2. performAction(ACTION_CLICK) 也可能失效（游戏自绘渲染时节点树不可用）。
 * 因此所有点击必须自带"手势回调超时兜底 + 自动重试 + 强制推进后截图验证"，
 * 且坐标档案已通过 adb input tap 对照校准（命中验证以 OCR 文本块坐标为基准）。
 * 若上述兜底仍系统性失效（重试后页面始终不变，说明手势通道被整体拦截），
 * 备选点击通道按序评估：A. 游戏暴露节点树则降级 performAction(ACTION_CLICK)；
 * B. 节点树为空（自绘渲染）则引入 Shizuku input tap 通道（与 adb 点击同链路）。
 */
class QiyuCoordinateAutomation(
    private val service: AccessibilityService,
    private val onStatus: (Runtime) -> Unit,
    private val onFailure: (String) -> Unit,
) {
    companion object {
        private const val TAG = "QiyuAuto"
        // 手势回调兜底：部分系统（vivo/Funtouch）dispatchGesture 接受手势后既不执行也不回调，
        // 若超时保护依赖 onCompleted，状态机将无限静默。超时后先重试一次，再强制推进并截图验证。
        private const val GESTURE_CALLBACK_TIMEOUT_MILLIS = 5_000L
        private const val MAX_GESTURE_RETRIES = 1
        // 点击完成后到下一节点截图验证的预留时间：页面切换/动画需要时间，提前截图会误判页面
        private const val POST_TAP_CAPTURE_DELAY_MILLIS = 1_000L
    }

    enum class Stage { ENTRY, DIVINATION, CHESTS, SELECT_INSPECT, WAIT_INSPECT, SELECT_OPEN, WAIT_OPEN, WAIT_SETTLEMENT, FAILED }

    data class ChestResult(val index: Int, val rawText: String, val rule: QiyuRule?)
    data class Runtime(
        val stage: Stage = Stage.ENTRY,
        val message: String = "等待奇遇入口截图",
        val score: BigInteger? = null,
        val viewRemaining: Int? = null,
        val openRemaining: Int? = null,
        val chests: List<ChestResult> = emptyList(),
        val plan: List<Int> = emptyList(),
        val pendingIndex: Int? = null,
        val completedRounds: Int = 0,
        val rawOcr: String = "",
        val deadlineMillis: Long? = null,
    )

    private data class Frame(val fullText: String, val scoreText: String, val viewText: String, val openText: String, val slotTexts: List<String>)
    private val handler = Handler(Looper.getMainLooper())
    private val recognizer = TextRecognition.getClient(ChineseTextRecognizerOptions.Builder().build())
    private var runtime = Runtime()
    private var captureQueued = false
    private var actionDeadline: Runnable? = null
    private var ocrDeadline: Runnable? = null
    private var gestureTimeout: Runnable? = null
    private var gestureCallbackReceived = false
    private var gestureRetryCount = 0
    private var expectedScore: BigInteger? = null
    private var screenshotRetryCount = 0
    private val maxScreenshotRetries = 3
    // 算卦动画等待计数：点击太极图后页面仍停在算卦页时，先等待重截，多次无效再重试点击
    private var divinationRetryCount = 0

    fun start() = requestFrame()

    fun stop() {
        actionDeadline?.let(handler::removeCallbacks)
        actionDeadline = null
        ocrDeadline?.let(handler::removeCallbacks)
        ocrDeadline = null
        gestureTimeout?.let(handler::removeCallbacks)
        gestureTimeout = null
        recognizer.close()
    }

    fun runtimeJson(): JSONObject = JSONObject().apply {
        put("mode", "coordinate_ocr")
        put("stage", runtime.stage.name)
        put("currentScore", runtime.score?.toString() ?: JSONObject.NULL)
        put("viewRemaining", runtime.viewRemaining ?: JSONObject.NULL)
        put("openRemaining", runtime.openRemaining ?: JSONObject.NULL)
        put("openingOrder", JSONArray(runtime.plan.map { it + 1 }))
        put("completedRounds", runtime.completedRounds)
        put("rawOcr", runtime.rawOcr)
        put("lastDecision", runtime.message)
        put("observations", JSONArray().apply {
            runtime.chests.forEach { chest -> put(JSONObject().apply {
                put("index", chest.index)
                put("label", "第 ${chest.index + 1} 个宝箱")
                put("rawText", chest.rawText)
                put("rule", chest.rule?.display ?: JSONObject.NULL)
                put("state", if (chest.rule == null) "unknown" else "recognized")
            }) }
        })
    }

    private fun update(next: Runtime) {
        runtime = next
        onStatus(runtime)
    }

    private fun fail(reason: String) {
        Log.e(TAG, "fail: $reason")
        update(runtime.copy(stage = Stage.FAILED, message = reason, deadlineMillis = null))
        onFailure(reason)
    }

    private fun requestFrame() {
        if (captureQueued || runtime.stage == Stage.FAILED) return
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            fail("系统版本低于 Android 11，无法通过无障碍服务获取截图")
            return
        }
        Log.d(TAG, "requestFrame: stage=${runtime.stage} 取图中")
        captureQueued = true
        service.takeScreenshot(Display.DEFAULT_DISPLAY, service.mainExecutor,
            object : AccessibilityService.TakeScreenshotCallback {
                override fun onSuccess(screenshot: AccessibilityService.ScreenshotResult) {
                    captureQueued = false
                    screenshotRetryCount = 0  // 成功后重置重试计数
                    val buffer = screenshot.hardwareBuffer
                    val bitmap = Bitmap.wrapHardwareBuffer(buffer, screenshot.colorSpace)?.copy(Bitmap.Config.ARGB_8888, false)
                    buffer.close()
                    if (bitmap == null) {
                        Log.e(TAG, "onSuccess 但 Bitmap.wrapHardwareBuffer 失败")
                        fail("截图转换失败")
                    } else {
                        Log.d(TAG, "截图成功 ${bitmap.width}x${bitmap.height}")
                        recognize(bitmap)
                    }
                }
                override fun onFailure(errorCode: Int) {
                    captureQueued = false
                    Log.e(TAG, "takeScreenshot onFailure: errorCode=$errorCode retryCount=$screenshotRetryCount")
                    
                    // 截图失败时重试，而不是立即终止流程
                    if (screenshotRetryCount < maxScreenshotRetries) {
                        screenshotRetryCount++
                        Log.w(TAG, "截图失败，${500 * screenshotRetryCount}ms 后重试 ($screenshotRetryCount/$maxScreenshotRetries)")
                        handler.postDelayed({
                            if (runtime.stage != Stage.FAILED) {
                                requestFrame()
                            }
                        }, 500L * screenshotRetryCount)
                    } else {
                        fail("无法获取游戏截图，错误码 $errorCode；请确认无障碍服务已授予截图能力且游戏在前台")
                    }
                }
            },
        )
    }

    private fun recognize(bitmap: Bitmap) {
        val images = listOf(bitmap, QiyuCoordinateProfile.score.crop(bitmap), QiyuCoordinateProfile.viewCount.crop(bitmap), QiyuCoordinateProfile.openCount.crop(bitmap)) +
            QiyuCoordinateProfile.ruleRegions.map { it.crop(bitmap) }
        Log.d(TAG, "recognize: 提交 ${images.size} 张图片进行 OCR（全屏 + 分数 + 查看/开启计数 + 5 规则区）")
        val tasks = images.map { recognizer.process(InputImage.fromBitmap(it, 0)) }

        // OCR 超时保护：ML Kit 模型下载/识别挂起时给出明确失败，而不是静默等待
        val ocrTimeout = Runnable {
            Log.e(TAG, "recognize: OCR 超时（20 秒无回调），bitmap=${bitmap.width}x${bitmap.height}，${tasks.size} 个 task 全部未完成")
            images.drop(1).forEach(Bitmap::recycle)
            bitmap.recycle()
            fail("OCR 识别超时：ML Kit 20 秒内未返回结果，请检查 GMS 模型下载状态")
        }
        ocrDeadline = ocrTimeout
        handler.postDelayed(ocrTimeout, 20_000L)

        Tasks.whenAllSuccess<Text>(tasks).addOnSuccessListener { values ->
            ocrDeadline?.let(handler::removeCallbacks)
            ocrDeadline = null
            images.drop(1).forEach(Bitmap::recycle)
            bitmap.recycle()
            val texts = values.map { it.text }
            Log.d(TAG, "recognize: OCR 完成，全屏=${texts[0].replace(Regex("\\s+"), "").take(80)}")
            Log.d(TAG, "recognize: 文本块坐标=" + values[0].textBlocks.take(60).joinToString(" | ") { b ->
                val r = b.boundingBox
                "${b.text.replace(Regex("\\s+"), "")}@[${r?.left},${r?.top}-${r?.right},${r?.bottom}]"
            })
            consume(Frame(texts[0], texts[1], texts[2], texts[3], texts.drop(4)))
        }.addOnFailureListener {
            ocrDeadline?.let(handler::removeCallbacks)
            ocrDeadline = null
            images.drop(1).forEach(Bitmap::recycle)
            bitmap.recycle()
            Log.e(TAG, "recognize: OCR 失败：${it.message ?: "未知错误"}")
            fail("OCR 识别失败：${it.message ?: "未知错误"}")
        }
    }

    private fun consume(frame: Frame) {
        val page = detectPage(frame)
        // 分数只认宝箱页"当前分数"下方的数字。入口/算卦页分数与奇遇流程无关（实测入口 OCR
        // 会误抓"目标分数823800"），一律忽略；全屏 fallback 会把"剩余次数3"误当成分数，禁用。
        val score = if (page == Page.CHESTS) parseScore(frame.scoreText) else null
        val viewCount = parseCount(frame.viewText, "查看") ?: parseCount(frame.fullText, "查看")
        val openCount = parseCount(frame.openText, "开启") ?: parseCount(frame.fullText, "开启")
        Log.d(TAG, "consume: stage=${runtime.stage} page=$page score=$score viewRemaining=$viewCount openRemaining=$openCount")
        val rawOcr = listOf("全屏=${frame.fullText}", "分数=${frame.scoreText}", "查看=${frame.viewText}", "开启=${frame.openText}") +
            frame.slotTexts.mapIndexed { index, text -> "宝箱${index + 1}=$text" }
        val base = runtime.copy(score = score ?: runtime.score, viewRemaining = viewCount ?: runtime.viewRemaining,
            openRemaining = openCount ?: runtime.openRemaining, rawOcr = rawOcr.joinToString("\n"))
        when (runtime.stage) {
            Stage.ENTRY -> if (page == Page.ENTRY) tap(QiyuCoordinateProfile.start, Stage.DIVINATION, "已点击开启奇遇，等待算卦页") else unknown(page)
            Stage.DIVINATION -> if (page == Page.DIVINATION) tap(QiyuCoordinateProfile.divination, Stage.CHESTS, "已点击算卦，等待宝箱页") else unknown(page)
            Stage.CHESTS -> when {
                // 宝箱页强信号就绪，进入宝箱处理
                page == Page.CHESTS && base.score != null && base.viewRemaining != null && base.openRemaining != null -> {
                    divinationRetryCount = 0
                    handleChestPage(base, frame)
                }
                // 宝箱页已识别但计数/分数未解析出（OCR 波动）：等待重截，30 秒 deadline 兜底
                page == Page.CHESTS -> {
                    divinationRetryCount = 0
                    update(base.copy(message = "宝箱页数据解析中（分数/剩余次数未读到），等待后重截"))
                    handler.postDelayed({ requestFrame() }, 800L)
                }
                // 点击太极图后仍停在算卦页：算卦动画未结束，或点击被入场动画吞掉。
                // 先等待重截（动画常见 1~3 秒），连续 3 次仍无效则重试点击太极图。
                page == Page.DIVINATION -> if (divinationRetryCount < 3) {
                    divinationRetryCount++
                    update(base.copy(message = "算卦动画进行中（$divinationRetryCount/3），等待后重截"))
                    handler.postDelayed({ requestFrame() }, 800L)
                } else {
                    divinationRetryCount = 0
                    tap(QiyuCoordinateProfile.divination, Stage.CHESTS, "算卦页未离开，重试点击太极图")
                }
                else -> unknown(page)
            }
            Stage.SELECT_INSPECT -> if (page == Page.CHESTS) tap(QiyuCoordinateProfile.inspect, Stage.WAIT_INSPECT, "已点击查看，等待第 ${(base.pendingIndex ?: 0) + 1} 个宝箱规则") else unknown(page)
            Stage.WAIT_INSPECT -> if (page == Page.CHESTS) recordInspection(base, frame) else unknown(page)
            Stage.SELECT_OPEN -> if (page == Page.CHESTS) openSelectedChest(base) else unknown(page)
            Stage.WAIT_OPEN -> if (page == Page.CHESTS && base.score != null) verifyOpen(base, frame) else unknown(page)
            Stage.WAIT_SETTLEMENT -> if (page == Page.SETTLEMENT) tap(QiyuCoordinateProfile.confirm, Stage.ENTRY, "已确认结算，等待下一轮入口", completedRounds = runtime.completedRounds + 1) else unknown(page)
            Stage.FAILED -> Unit
        }
    }

    private enum class Page { ENTRY, DIVINATION, CHESTS, SETTLEMENT, UNKNOWN }
    private fun detectPage(frame: Frame): Page {
        val text = frame.fullText.replace(Regex("\\s"), "")

        // 第一层：区域强信号。入口页 viewCount/openCount 区域为空，CHESTS 页这两个区域必有计数，
        // 但实测为"剩余次数N"格式（OCR 常误识别"刻余次数3/利余次数3"），不是 N/M，仅作辅助。
        val chestCounter = Regex("\\d+\\s*/\\s*\\d+")
        val hasChestCounter = chestCounter.containsMatchIn(frame.viewText) ||
                              chestCounter.containsMatchIn(frame.openText)
        val hasScore = parseScore(frame.scoreText) != null

        // 第二层：全屏 OCR 容错关键词集合（每页独有强信号优先于共享弱信号）
        val isSettlementText = text.contains("本局得分") ||
                               (text.contains("确定") && text.contains("奖励"))
        val isEntryText = text.contains("开启奇遇") || text.contains("天赋奇遇") ||
                          text.contains("奇遇秘宝") || text.contains("奇遇卷轴") ||
                          text.contains("奇遇入口") || text.contains("天脉奇遇")
        // CHESTS 独有强信号：底部"查看宝箱"+"开启宝箱"按钮同屏（OCR 识别稳定）。
        // 优先级高于共享的"奇遇"标题弱信号，避免宝箱页误判为算卦页。
        val hasChestButtons = text.contains("查看宝箱") && text.contains("开启宝箱")
        // DIVINATION 弱信号：页面顶部"奇遇"标题常被 OCR 误识别（实测输出"奇週"）。
        // 用排除法确认页面归属后即可盲点中央太极图；宝箱页同有"奇遇"标题，必须排除。
        val isDivinationText = text.contains("算卦") || text.contains("占卜") ||
            (text.contains("奇") && !hasChestButtons && !hasChestCounter && !isSettlementText && !isEntryText)

        // 第三层：阶段判定。按特异度从高到低，避免入口页"奇遇秘宝"被误判为 CHESTS。
        // 阶段迁移白名单由 consume() 里 when (runtime.stage) 强约束，无需在此重复。
        return when {
            // SETTLEMENT 独有 "本局得分"，且弹框覆盖当前分数/规则区域
            isSettlementText -> Page.SETTLEMENT
            // CHESTS：底部按钮组合（实测计数为"剩余次数N"而非 N/M，区域计数信号不可靠）
            hasChestButtons -> Page.CHESTS
            // CHESTS 次选：区域计数 N/M + score 数字（老布局兜底）
            hasChestCounter && hasScore -> Page.CHESTS
            // ENTRY：关键词命中 + 没有 CHESTS 强信号（双重否定杜绝入口页误识别为 CHESTS）
            isEntryText && !hasChestCounter -> Page.ENTRY
            // DIVINATION：强关键词，或顶部标题弱信号（排除法确认后盲点太极图）
            isDivinationText -> Page.DIVINATION
            else -> Page.UNKNOWN
        }
    }

    private fun handleChestPage(base: Runtime, frame: Frame) {
        when {
            base.viewRemaining!! > 0 -> selectForInspection(base)
            base.openRemaining!! > 0 -> selectForOpening(base)
            else -> tap(QiyuCoordinateProfile.finish, Stage.WAIT_SETTLEMENT, "已点击完成，等待结算弹框")
        }
    }

    private fun selectForInspection(base: Runtime) {
        val index = base.chests.size
        if (index >= QiyuCoordinateProfile.slots.size) {
            fail("查看次数超过已配置的五个宝箱槽位")
            return
        }
        tap(QiyuCoordinateProfile.slots[index], Stage.SELECT_INSPECT, "已选中第 ${index + 1} 个宝箱，等待执行查看", pendingIndex = index)
    }

    private fun recordInspection(base: Runtime, frame: Frame) {
        val index = base.pendingIndex ?: run { fail("查看结果缺少对应宝箱槽位"); return }
        val raw = frame.slotTexts.getOrNull(index).orEmpty()
        if (raw.isBlank()) {
            update(base.copy(message = "等待第 ${index + 1} 个宝箱规则出现"))
            handler.postDelayed({ requestFrame() }, 650L)
            return
        }
        val rule = QiyuRuleParser.parse(raw)
        if (rule == null) {
            fail("第 ${index + 1} 个宝箱规则无法安全识别：$raw")
            return
        }
        val chests = base.chests.filterNot { it.index == index } + ChestResult(index, raw, rule)
        val next = base.copy(chests = chests.sortedBy { it.index }, pendingIndex = null,
            message = "已记录第 ${index + 1} 个宝箱：${rule.display}")
        update(next)
        requestFrame()
    }

    private fun selectForOpening(base: Runtime) {
        val rules = base.chests.mapNotNull { chest -> chest.rule?.let { chest.index to it } }.toMap()
        if (rules.size < minOf(base.openRemaining!!, base.chests.size)) {
            fail("存在未识别的查看结果，停止开启以避免误操作")
            return
        }
        val plan = QiyuScoreSolver.bestPlan(base.score!!, rules, base.openRemaining!!)
            ?: run { fail("没有可安全开启的已查看宝箱"); return }
        val nextIndex = plan.order.firstOrNull() ?: run {
            tap(QiyuCoordinateProfile.finish, Stage.WAIT_SETTLEMENT, "无可开启宝箱，等待结算")
            return
        }
        tap(QiyuCoordinateProfile.slots[nextIndex], Stage.SELECT_OPEN,
            "最优序列 ${plan.order.joinToString(" → ") { "第 ${it + 1} 个" }}，选中第 ${nextIndex + 1} 个宝箱",
            plan = plan.order, pendingIndex = nextIndex)
    }

    private fun openSelectedChest(base: Runtime) {
        val index = base.pendingIndex ?: run { fail("开启前缺少选中宝箱槽位"); return }
        val rule = base.chests.firstOrNull { it.index == index }?.rule ?: run { fail("选中宝箱缺少已识别规则"); return }
        expectedScore = QiyuScoreSolver.apply(base.score ?: run { fail("开启前缺少分数"); return }, rule)
        tap(QiyuCoordinateProfile.open, Stage.WAIT_OPEN,
            "已点击开启第 ${index + 1} 个宝箱，等待分数从 ${base.score} 变为 $expectedScore")
    }

    private fun verifyOpen(base: Runtime, frame: Frame) {
        val before = runtime.score ?: run { fail("开启前缺少当前分数"); return }
        val after = base.score ?: run { fail("开启后无法读取当前分数"); return }
        if (expectedScore != null && after == before) {
            update(base.copy(message = "等待开启后的分数刷新：$before"))
            handler.postDelayed({ requestFrame() }, 650L)
            return
        }
        if (expectedScore != null && after != expectedScore) {
            fail("开启后分数校验失败：预期 $expectedScore，实际 $after")
            return
        }
        val opened = runtime.pendingIndex ?: run { fail("开启结果缺少对应宝箱槽位"); return }
        val remaining = base.chests.filterNot { it.index == opened }
        update(base.copy(chests = remaining, plan = emptyList(), pendingIndex = null,
            message = "已开启第 ${opened + 1} 个宝箱：$before → $after"))
        expectedScore = null
        requestFrame()
    }

    private fun tap(
        point: QiyuCoordinateProfile.Point,
        nextStage: Stage,
        message: String,
        plan: List<Int> = runtime.plan,
        pendingIndex: Int? = runtime.pendingIndex,
        completedRounds: Int = runtime.completedRounds,
    ) {
        Log.d(TAG, "tap: 发起点击 (${point.x}, ${point.y}) → $nextStage，$message")
        gestureRetryCount = 0
        doTap(point, nextStage, message, plan, pendingIndex, completedRounds)
    }

    private fun doTap(
        point: QiyuCoordinateProfile.Point,
        nextStage: Stage,
        message: String,
        plan: List<Int>,
        pendingIndex: Int?,
        completedRounds: Int,
    ) {
        gestureCallbackReceived = false
        val path = Path().apply {
            val width = service.resources.displayMetrics.widthPixels
            val height = service.resources.displayMetrics.heightPixels
            moveTo((point.x * width).toFloat(), (point.y * height).toFloat())
        }
        val stroke = GestureDescription.StrokeDescription(path, 0, 60)
        val gesture = GestureDescription.Builder().addStroke(stroke).build()
        val accepted = service.dispatchGesture(gesture, object : AccessibilityService.GestureResultCallback() {
            override fun onCompleted(gestureDescription: GestureDescription?) {
                gestureCallbackReceived = true
                cancelGestureTimeout()
                Log.d(TAG, "tap onCompleted: stage=$nextStage")
                completeTap(nextStage, message, plan, pendingIndex, completedRounds)
            }
            override fun onCancelled(gestureDescription: GestureDescription?) {
                gestureCallbackReceived = true
                cancelGestureTimeout()
                fail("坐标点击未被系统接受")
            }
        }, null)
        Log.d(TAG, "tap: dispatchGesture accepted=$accepted stage=$nextStage retry=$gestureRetryCount")
        if (!accepted) {
            fail("系统拒绝坐标点击请求")
            return
        }
        // 回调兜底：手势被系统接受但 onCompleted/onCancelled 均不回调时（vivo 已知问题），
        // 先自动重试一次；仍无回调则强制推进状态并截图验证页面是否已实际变化。
        gestureTimeout?.let(handler::removeCallbacks)
        gestureTimeout = Runnable {
            // 守卫只判断回调是否已收到。不能检查 runtime.stage != nextStage：
            // 回调丢失时 completeTap 从未执行，stage 停留在旧值，该条件恒真会让兜底永远跳过，
            // 状态机退回"静默冻结"（已实测：accepted=true 后无回调，5 秒/10 秒均无任何日志）。
            if (gestureCallbackReceived) return@Runnable
            Log.w(TAG, "tap: 手势回调超时（${GESTURE_CALLBACK_TIMEOUT_MILLIS}ms 无 onCompleted/onCancelled）stage=$nextStage")
            if (gestureRetryCount < MAX_GESTURE_RETRIES) {
                gestureRetryCount++
                Log.w(TAG, "tap: 自动重试第 $gestureRetryCount/$MAX_GESTURE_RETRIES 次点击 (${point.x}, ${point.y})")
                doTap(point, nextStage, message, plan, pendingIndex, completedRounds)
            } else {
                Log.w(TAG, "tap: 重试后手势仍无回调，强制推进 stage=$nextStage 并截图验证")
                completeTap(nextStage, "$message（手势回调丢失，强制推进验证）", plan, pendingIndex, completedRounds)
            }
        }.also { handler.postDelayed(it, GESTURE_CALLBACK_TIMEOUT_MILLIS) }
    }

    private fun cancelGestureTimeout() {
        gestureTimeout?.let(handler::removeCallbacks)
        gestureTimeout = null
    }

    private fun completeTap(
        nextStage: Stage,
        message: String,
        plan: List<Int>,
        pendingIndex: Int?,
        completedRounds: Int,
    ) {
        val deadline = System.currentTimeMillis() + 30_000L
        val next = if (nextStage == Stage.ENTRY && completedRounds > runtime.completedRounds) {
            Runtime(
                stage = nextStage,
                message = message,
                completedRounds = completedRounds,
                deadlineMillis = deadline,
            )
        } else {
            runtime.copy(
                stage = nextStage,
                message = message,
                plan = plan,
                pendingIndex = pendingIndex,
                completedRounds = completedRounds,
                deadlineMillis = deadline,
            )
        }
        update(next)
        scheduleDeadline(nextStage, deadline)
        handler.postDelayed({ requestFrame() }, POST_TAP_CAPTURE_DELAY_MILLIS)
    }

    private fun scheduleDeadline(stage: Stage, deadline: Long) {
        actionDeadline?.let(handler::removeCallbacks)
        actionDeadline = Runnable {
            if (runtime.stage == stage && runtime.deadlineMillis == deadline) {
                fail("${stageLabel(stage)} 等待页面变化超过 30 秒")
            }
        }.also { handler.postDelayed(it, 30_000L) }
    }

    private fun unknown(page: Page) {
        fail(if (page == Page.UNKNOWN) "截图不属于已知奇遇页面，已立即停止" else "页面阶段不符合预期：$page")
    }

    private fun stageLabel(stage: Stage): String = when (stage) {
        Stage.ENTRY -> "奇遇入口"; Stage.DIVINATION -> "算卦页"; Stage.CHESTS -> "宝箱页"
        Stage.SELECT_INSPECT -> "宝箱选中"; Stage.WAIT_INSPECT -> "查看结果"; Stage.SELECT_OPEN -> "开启宝箱选中"
        Stage.WAIT_OPEN -> "开启结果"; Stage.WAIT_SETTLEMENT -> "结算弹框"; Stage.FAILED -> "失败状态"
    }

    private fun parseScore(text: String): BigInteger? {
        val compact = text.replace(Regex("\\s"), "")
        return Regex("(?:当前)?分数[^0-9]*(\\d+)").find(compact)?.groupValues?.getOrNull(1)?.toBigIntegerOrNull()
            ?: Regex("\\d+").findAll(compact).lastOrNull()?.value?.toBigIntegerOrNull()
    }

    private fun parseCount(text: String, label: String): Int? {
        val compact = text.replace(Regex("\\s"), "")
        val afterLabel = compact.substringAfter(label, compact)
        return Regex("(?:剩余(?:次数)?)?[^0-9]*(\\d+)(?:/\\d+)?").find(afterLabel)?.groupValues?.getOrNull(1)?.toIntOrNull()
    }
}
