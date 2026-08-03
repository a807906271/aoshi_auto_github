package com.aoshi.auto_mobile.automation

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Bitmap
import android.graphics.Path
import android.hardware.HardwareBuffer
import android.os.Build
import android.os.Handler
import android.os.Looper
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

    val start = Point(0.470, 0.849)
    val divination = Point(0.498, 0.654)
    val inspect = Point(0.304, 0.898)
    val open = Point(0.697, 0.898)
    val finish = Point(0.500, 0.952)
    val confirm = Point(0.502, 0.729)

    val score = Region(176.0 / 460, 454.0 / 1024, 292.0 / 460, 545.0 / 1024)
    val viewCount = Region(52.0 / 460, 880.0 / 1024, 190.0 / 460, 960.0 / 1024)
    val openCount = Region(250.0 / 460, 880.0 / 1024, 430.0 / 460, 960.0 / 1024)
    val slots = listOf(
        Point(0.239, 0.698), Point(0.484, 0.698), Point(0.750, 0.698),
        Point(0.333, 0.790), Point(0.511, 0.790),
    )
    val ruleRegions = listOf(
        Region(67.0 / 460, 727.0 / 1024, 153.0 / 460, 763.0 / 1024),
        Region(182.0 / 460, 725.0 / 1024, 263.0 / 460, 763.0 / 1024),
        Region(294.0 / 460, 725.0 / 1024, 396.0 / 460, 763.0 / 1024),
        Region(67.0 / 460, 823.0 / 1024, 176.0 / 460, 855.0 / 1024),
        Region(181.0 / 460, 823.0 / 1024, 290.0 / 460, 855.0 / 1024),
    )
}

sealed class QiyuRule(val raw: String, val display: String) {
    class Add(raw: String, val amount: BigInteger) : QiyuRule(raw, "+$amount")
    class Multiply(raw: String, val multiplier: BigDecimal) : QiyuRule(raw, "×${multiplier.stripTrailingZeros().toPlainString()}")
    class Replace(raw: String, val value: BigInteger) : QiyuRule(raw, "被$value替换")
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
 */
class QiyuCoordinateAutomation(
    private val service: AccessibilityService,
    private val onStatus: (Runtime) -> Unit,
    private val onFailure: (String) -> Unit,
) {
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
    private var expectedScore: BigInteger? = null

    fun start() = requestFrame()

    fun stop() {
        actionDeadline?.let(handler::removeCallbacks)
        actionDeadline = null
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
        update(runtime.copy(stage = Stage.FAILED, message = reason, deadlineMillis = null))
        onFailure(reason)
    }

    private fun requestFrame() {
        if (captureQueued || runtime.stage == Stage.FAILED) return
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            fail("系统版本低于 Android 11，无法通过无障碍服务获取截图")
            return
        }
        captureQueued = true
        service.takeScreenshot(Display.DEFAULT_DISPLAY, service.mainExecutor,
            object : AccessibilityService.TakeScreenshotCallback {
                override fun onSuccess(screenshot: AccessibilityService.ScreenshotResult) {
                    captureQueued = false
                    val buffer = screenshot.hardwareBuffer
                    val bitmap = Bitmap.wrapHardwareBuffer(buffer, screenshot.colorSpace)?.copy(Bitmap.Config.ARGB_8888, false)
                    buffer.close()
                    if (bitmap == null) fail("截图转换失败") else recognize(bitmap)
                }
                override fun onFailure(errorCode: Int) {
                    captureQueued = false
                    fail("无法获取游戏截图，错误码 $errorCode；请确认无障碍服务已授予截图能力")
                }
            },
        )
    }

    private fun recognize(bitmap: Bitmap) {
        val images = listOf(bitmap, QiyuCoordinateProfile.score.crop(bitmap), QiyuCoordinateProfile.viewCount.crop(bitmap), QiyuCoordinateProfile.openCount.crop(bitmap)) +
            QiyuCoordinateProfile.ruleRegions.map { it.crop(bitmap) }
        val tasks = images.map { recognizer.process(InputImage.fromBitmap(it, 0)) }
        Tasks.whenAllSuccess<Text>(tasks).addOnSuccessListener { values ->
            images.drop(1).forEach(Bitmap::recycle)
            bitmap.recycle()
            val texts = values.map { it.text }
            consume(Frame(texts[0], texts[1], texts[2], texts[3], texts.drop(4)))
        }.addOnFailureListener {
            images.drop(1).forEach(Bitmap::recycle)
            bitmap.recycle()
            fail("OCR 识别失败：${it.message ?: "未知错误"}")
        }
    }

    private fun consume(frame: Frame) {
        val page = detectPage(frame)
        val score = parseScore(frame.scoreText) ?: parseScore(frame.fullText)
        val viewCount = parseCount(frame.viewText, "查看") ?: parseCount(frame.fullText, "查看")
        val openCount = parseCount(frame.openText, "开启") ?: parseCount(frame.fullText, "开启")
        val rawOcr = listOf("全屏=${frame.fullText}", "分数=${frame.scoreText}", "查看=${frame.viewText}", "开启=${frame.openText}") +
            frame.slotTexts.mapIndexed { index, text -> "宝箱${index + 1}=$text" }
        val base = runtime.copy(score = score ?: runtime.score, viewRemaining = viewCount ?: runtime.viewRemaining,
            openRemaining = openCount ?: runtime.openRemaining, rawOcr = rawOcr.joinToString("\n"))
        when (runtime.stage) {
            Stage.ENTRY -> if (page == Page.ENTRY) tap(QiyuCoordinateProfile.start, Stage.DIVINATION, "已点击开启奇遇，等待算卦页") else unknown(page)
            Stage.DIVINATION -> if (page == Page.DIVINATION) tap(QiyuCoordinateProfile.divination, Stage.CHESTS, "已点击算卦，等待宝箱页") else unknown(page)
            Stage.CHESTS -> if (page == Page.CHESTS && base.score != null && base.viewRemaining != null && base.openRemaining != null) handleChestPage(base, frame) else unknown(page)
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
        return when {
            text.contains("开启奇遇") || text.contains("天赋奇遇") -> Page.ENTRY
            text.contains("算卦") -> Page.DIVINATION
            text.contains("本局得分") || text.contains("确定") && text.contains("奖励") -> Page.SETTLEMENT
            parseScore(frame.scoreText) != null && (text.contains("查看") || text.contains("宝箱")) -> Page.CHESTS
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
        val path = Path().apply {
            val width = service.resources.displayMetrics.widthPixels
            val height = service.resources.displayMetrics.heightPixels
            moveTo((point.x * width).toFloat(), (point.y * height).toFloat())
        }
        val stroke = GestureDescription.StrokeDescription(path, 0, 60)
        val gesture = GestureDescription.Builder().addStroke(stroke).build()
        val accepted = service.dispatchGesture(gesture, object : AccessibilityService.GestureResultCallback() {
            override fun onCompleted(gestureDescription: GestureDescription?) {
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
                handler.postDelayed({ requestFrame() }, 650L)
            }
            override fun onCancelled(gestureDescription: GestureDescription?) {
                fail("坐标点击未被系统接受")
            }
        }, null)
        if (!accepted) fail("系统拒绝坐标点击请求")
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
