package com.aoshi.auto_mobile.automation

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import org.json.JSONObject

/**
 * 傲世游戏无障碍服务
 * 
 * 职责：
 * - 接收 Flutter 层的启停命令
 * - 执行自动化流程
 * - 回传状态和错误
 */
class AoshiAccessibilityService : AccessibilityService() {

    companion object {
        private const val STEP_INTERVAL_MILLIS = 650L
        private const val SAME_PAGE_INTERVAL_MILLIS = 1800L
        private const val PAGE_TEXT_SAMPLE_SIZE = 18
        private const val PAGE_SIGNATURE_TEXT_SIZE = 28
        private const val QIYU_ENTRY_TRANSITION_TIMEOUT_MILLIS = 30_000L
        private const val QIYU_DIVINATION_TRANSITION_TIMEOUT_MILLIS = 30_000L

        // 单例引用，供 MethodChannel 调用
        @Volatile
        var instance: AoshiAccessibilityService? = null
            private set

        // 流程状态
        @Volatile
        var currentFlow: String? = null
            private set

        @Volatile
        var isRunning: Boolean = false
            private set
    }

    private data class PageSnapshot(
        val signature: String,
        val label: String,
        val textSample: String,
    )

    // 流程引擎
    private lateinit var gameFlows: GameFlows
    
    // 当前状态
    private var qiyuPhase: GameFlows.QiyuPhase = GameFlows.QiyuPhase.WaitStart
    private var towerPhase: GameFlows.TowerPhase = GameFlows.TowerPhase.ResolveBranch

    // 运行时快照：无障碍事件可能密集触发，状态机只消费稳定页面。
    private var lastStepAtMillis: Long = 0L
    private var lastPageSignature: String? = null
    private var lastPageLabel: String = "未知页面"
    private var lastPageTextSample: String = ""
    private var lastEventType: String = "manual"
    private var skippedStepCount: Int = 0
    private var stepCount: Int = 0
    private var lastFlow: String? = null
    private var lastStatus: String = "idle"
    private var lastMessage: String = "待启动"
    private var lastError: String? = null
    private var lastThrottleReason: String? = null
    private var lastElapsedMillis: Long = 0L
    private var qiyuEntryTransitionDeadlineMillis: Long? = null
    private var qiyuDivinationTransitionDeadlineMillis: Long? = null
    private val timeoutHandler = Handler(Looper.getMainLooper())
    private val qiyuEntryTransitionTimeout = Runnable {
        if (isRunning && currentFlow == "qiyu" && qiyuPhase is GameFlows.QiyuPhase.EnterDivination) {
            failQiyu("点击开启奇遇后 30 秒内未进入算卦页")
        }
    }
    private val qiyuDivinationTransitionTimeout = Runnable {
        if (isRunning && currentFlow == "qiyu" && qiyuPhase is GameFlows.QiyuPhase.SelectBox) {
            failQiyu("点击算卦后 30 秒内未进入宝箱页")
        }
    }

    // 状态回调
    private var statusCallback: ((String, JSONObject) -> Unit)? = null

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        gameFlows = GameFlows()
        
        // 配置服务信息
        val info = AccessibilityServiceInfo().apply {
            eventTypes = AccessibilityEvent.TYPES_ALL_MASK
            feedbackType = AccessibilityServiceInfo.FEEDBACK_GENERIC
            flags = AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS or
                    AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS
            notificationTimeout = 100
        }
        serviceInfo = info
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (!isRunning || event == null) return
        if (event.packageName?.toString() == packageName) return

        // 只处理前台游戏窗口的变化；本应用的状态页事件不能推进游戏状态机。
        if (event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED ||
            event.eventType == AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED) {
            
            executeCurrentFlow(event.eventType)
        }
    }

    override fun onInterrupt() {
        // 中断时停止流程
        stopFlow()
    }

    override fun onDestroy() {
        super.onDestroy()
        instance = null
        stopFlow()
    }

    // ===== 公开接口（供 MethodChannel 调用）=====

    /**
     * 检查无障碍服务是否已启用
     */
    fun isEnabled(): Boolean {
        return instance != null
    }

    /**
     * 启动奇遇流程
     */
    fun startQiyu(): JSONObject {
        if (isRunning) {
            return createResult(false, "已有流程在运行中")
        }

        currentFlow = "qiyu"
        lastFlow = "qiyu"
        lastStatus = "running"
        isRunning = true
        qiyuPhase = GameFlows.QiyuPhase.WaitStart
        resetRuntime("奇遇流程已启动", "running")
        gameFlows.resetQiyu()

        // 立即执行第一步
        val result = executeCurrentFlow(null)
        return createResult(true, "奇遇流程已启动", result)
    }

    /**
     * 启动闯塔流程
     */
    fun startTower(): JSONObject {
        if (isRunning) {
            return createResult(false, "已有流程在运行中")
        }

        currentFlow = "tower"
        lastFlow = "tower"
        lastStatus = "running"
        isRunning = true
        towerPhase = GameFlows.TowerPhase.ResolveBranch
        resetRuntime("闯塔流程已启动", "running")
        gameFlows.resetTower()

        val result = executeCurrentFlow(null)
        return createResult(true, "闯塔流程已启动", result)
    }

    /**
     * 停止当前流程
     */
    fun stopFlow(): JSONObject {
        isRunning = false
        currentFlow = null
        lastStatus = "idle"
        qiyuPhase = GameFlows.QiyuPhase.WaitStart
        towerPhase = GameFlows.TowerPhase.ResolveBranch
        resetRuntime("流程已停止", "idle")

        return createResult(true, "流程已停止")
    }

    /**
     * 获取当前状态
     */
    fun getStatus(): JSONObject {
        return createStatusSnapshot()
    }

    /**
     * 设置状态回调
     */
    fun setStatusCallback(callback: (String, JSONObject) -> Unit) {
        statusCallback = callback
    }

    // ===== 私有方法 =====

    /**
     * 执行当前流程的一步
     */
    private fun executeCurrentFlow(eventType: Int? = null): JSONObject {
        val root = rootInActiveWindow ?: return createResult(false, "无法获取窗口节点")
        val pageSnapshot = createPageSnapshot(root)
        val now = System.currentTimeMillis()
        lastEventType = eventTypeName(eventType)
        lastPageLabel = pageSnapshot.label
        lastPageTextSample = pageSnapshot.textSample

        val throttleReason = stepThrottleReason(now, pageSnapshot.signature)
        lastElapsedMillis = if (lastStepAtMillis == 0L) 0L else now - lastStepAtMillis
        lastThrottleReason = throttleReason
        if (throttleReason != null) {
            skippedStepCount += 1
            lastMessage = "等待页面稳定：$throttleReason"
            return createResult(true, lastMessage)
        }

        lastStepAtMillis = now
        lastPageSignature = pageSnapshot.signature
        lastThrottleReason = null
        stepCount += 1

        return when (currentFlow) {
            "qiyu" -> {
                val previousPhase = qiyuPhase
                val (newPhase, message) = gameFlows.executeQiyuStep(root, previousPhase)
                qiyuPhase = newPhase
                syncQiyuEntryTransitionTimeout(previousPhase, newPhase, pageSnapshot.label)
                syncQiyuDivinationTransitionTimeout(previousPhase, newPhase, pageSnapshot.label)
                
                when (newPhase) {
                    is GameFlows.QiyuPhase.Failed -> {
                        clearQiyuTransitionTimeouts()
                        isRunning = false
                        currentFlow = null
                        lastStatus = "failed"
                        lastError = newPhase.reason
                        lastMessage = "奇遇流程失败: ${newPhase.reason}"
                        notifyStatus("failed", newPhase.reason)
                        createResult(false, lastMessage)
                    }
                    else -> {
                        lastStatus = "running"
                        lastError = null
                        lastMessage = message ?: newPhase.javaClass.simpleName
                        notifyStatus("running", lastMessage)
                        createResult(true, lastMessage)
                    }
                }
            }
            "tower" -> {
                val (newPhase, message) = gameFlows.executeTowerStep(root, towerPhase)
                towerPhase = newPhase
                
                when (newPhase) {
                    is GameFlows.TowerPhase.Done -> {
                        isRunning = false
                        currentFlow = null
                        lastStatus = "completed"
                        lastError = null
                        lastMessage = "闯塔流程完成"
                        notifyStatus("completed", newPhase.javaClass.simpleName)
                        createResult(true, lastMessage)
                    }
                    is GameFlows.TowerPhase.Failed -> {
                        isRunning = false
                        currentFlow = null
                        lastStatus = "failed"
                        lastError = newPhase.reason
                        lastMessage = "闯塔流程失败: ${newPhase.reason}"
                        notifyStatus("failed", newPhase.reason)
                        createResult(false, lastMessage)
                    }
                    else -> {
                        lastStatus = "running"
                        lastError = null
                        lastMessage = message ?: newPhase.javaClass.simpleName
                        notifyStatus("running", lastMessage)
                        createResult(true, lastMessage)
                    }
                }
            }
            else -> createResult(false, "无活动流程")
        }
    }

    /**
     * 通知状态变化
     */
    private fun notifyStatus(status: String, detail: String) {
        statusCallback?.invoke(status, createStatusSnapshot().apply {
            put("detail", detail)
        })
    }

    private fun syncQiyuEntryTransitionTimeout(
        previousPhase: GameFlows.QiyuPhase,
        nextPhase: GameFlows.QiyuPhase,
        pageLabel: String,
    ) {
        val isWaitingForDivination = nextPhase is GameFlows.QiyuPhase.EnterDivination
        val enteredDivination = pageLabel == "奇遇算卦"
        when {
            !isWaitingForDivination || enteredDivination -> clearQiyuEntryTransitionTimeout()
            previousPhase is GameFlows.QiyuPhase.WaitStart -> {
                val deadline = System.currentTimeMillis() + QIYU_ENTRY_TRANSITION_TIMEOUT_MILLIS
                qiyuEntryTransitionDeadlineMillis = deadline
                timeoutHandler.removeCallbacks(qiyuEntryTransitionTimeout)
                timeoutHandler.postDelayed(qiyuEntryTransitionTimeout, QIYU_ENTRY_TRANSITION_TIMEOUT_MILLIS)
            }
        }
    }

    private fun clearQiyuEntryTransitionTimeout() {
        qiyuEntryTransitionDeadlineMillis = null
        timeoutHandler.removeCallbacks(qiyuEntryTransitionTimeout)
    }

    private fun syncQiyuDivinationTransitionTimeout(
        previousPhase: GameFlows.QiyuPhase,
        nextPhase: GameFlows.QiyuPhase,
        pageLabel: String,
    ) {
        val isWaitingForBoxPage = nextPhase is GameFlows.QiyuPhase.SelectBox
        val enteredBoxPage = pageLabel == "奇遇宝箱页"
        when {
            !isWaitingForBoxPage || enteredBoxPage -> clearQiyuDivinationTransitionTimeout()
            previousPhase is GameFlows.QiyuPhase.EnterDivination -> {
                val deadline = System.currentTimeMillis() + QIYU_DIVINATION_TRANSITION_TIMEOUT_MILLIS
                qiyuDivinationTransitionDeadlineMillis = deadline
                timeoutHandler.removeCallbacks(qiyuDivinationTransitionTimeout)
                timeoutHandler.postDelayed(
                    qiyuDivinationTransitionTimeout,
                    QIYU_DIVINATION_TRANSITION_TIMEOUT_MILLIS,
                )
            }
        }
    }

    private fun clearQiyuDivinationTransitionTimeout() {
        qiyuDivinationTransitionDeadlineMillis = null
        timeoutHandler.removeCallbacks(qiyuDivinationTransitionTimeout)
    }

    private fun clearQiyuTransitionTimeouts() {
        clearQiyuEntryTransitionTimeout()
        clearQiyuDivinationTransitionTimeout()
    }

    private fun failQiyu(reason: String) {
        clearQiyuTransitionTimeouts()
        qiyuPhase = GameFlows.QiyuPhase.Failed(reason)
        isRunning = false
        currentFlow = null
        lastStatus = "failed"
        lastError = reason
        lastMessage = "奇遇流程失败: $reason"
        notifyStatus("failed", reason)
    }

    private fun createStatusSnapshot(): JSONObject {
        return JSONObject().apply {
            put("status", lastStatus)
            put("isRunning", isRunning)
            put("currentFlow", currentFlow ?: "none")
            put("lastFlow", lastFlow ?: JSONObject.NULL)
            put("activePhase", activePhaseName())
            put("qiyuPhase", qiyuPhase.javaClass.simpleName)
            put("towerPhase", towerPhase.javaClass.simpleName)
            put("stepCount", stepCount)
            put("skippedStepCount", skippedStepCount)
            put("lastEventType", lastEventType)
            put("lastPageLabel", lastPageLabel)
            put("lastPageTextSample", lastPageTextSample)
            put("lastMessage", lastMessage)
            put("lastError", lastError ?: JSONObject.NULL)
            put("lastPageSignature", lastPageSignature ?: JSONObject.NULL)
            put("lastThrottleReason", lastThrottleReason ?: JSONObject.NULL)
            put("lastElapsedMillis", lastElapsedMillis)
            put(
                "qiyuEntryTransitionRemainingMillis",
                qiyuEntryTransitionDeadlineMillis
                    ?.minus(System.currentTimeMillis())
                    ?.coerceAtLeast(0)
                    ?: JSONObject.NULL,
            )
            put("workflowRuntime", gameFlows.runtimeJson(currentFlow ?: lastFlow))
        }
    }

    private fun activePhaseName(): String {
        return when (currentFlow ?: lastFlow) {
            "qiyu" -> qiyuPhase.javaClass.simpleName
            "tower" -> towerPhase.javaClass.simpleName
            else -> "Idle"
        }
    }

    private fun resetRuntime(message: String, status: String) {
        clearQiyuTransitionTimeouts()
        lastStepAtMillis = 0L
        lastPageSignature = null
        lastPageLabel = "未知页面"
        lastPageTextSample = ""
        lastEventType = "manual"
        skippedStepCount = 0
        stepCount = 0
        lastStatus = status
        lastMessage = message
        lastError = null
        lastThrottleReason = null
        lastElapsedMillis = 0L
    }

    private fun stepThrottleReason(now: Long, pageSignature: String): String? {
        val elapsed = now - lastStepAtMillis
        val isSamePage = pageSignature == lastPageSignature
        return when {
            elapsed < STEP_INTERVAL_MILLIS -> "事件间隔 ${elapsed}ms，小于 ${STEP_INTERVAL_MILLIS}ms"
            isSamePage && elapsed < SAME_PAGE_INTERVAL_MILLIS -> "同页签名 ${elapsed}ms 内重复触发"
            else -> null
        }
    }

    private fun createPageSnapshot(root: AccessibilityNodeInfo): PageSnapshot {
        val texts = NodeQuery.collectTexts(root)
        val normalizedTexts = texts
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .take(PAGE_SIGNATURE_TEXT_SIZE)
        val pageLabel = classifyPage(normalizedTexts)
        val textSignature = normalizedTexts.joinToString("|").hashCode()
        val bounds = NodeQuery.bounds(root)?.let { "${it.left},${it.top},${it.right},${it.bottom}" }.orEmpty()
        return PageSnapshot(
            signature = "${currentFlow.orEmpty()}#$pageLabel#$textSignature#$bounds",
            label = pageLabel,
            textSample = normalizedTexts.take(PAGE_TEXT_SAMPLE_SIZE).joinToString(" / "),
        )
    }

    private fun classifyPage(texts: List<String>): String {
        val pageText = texts.joinToString(" ")
        return when {
            pageText.contains("本局得分") -> "奇遇奖励确认"
            listOf("天赋奇遇", "天赐奇遇", "天脉奇遇").any(pageText::contains) -> "奇遇入口"
            pageText.contains("算卦") -> "奇遇算卦"
            pageText.contains("查看宝箱") && pageText.contains("开启宝箱") -> "奇遇宝箱选择"
            pageText.contains("宝箱") -> "奇遇宝箱页"
            pageText.contains("结束闯塔") -> "闯塔完成"
            pageText.contains("领取") && pageText.contains("奖励") -> "闯塔奖励"
            pageText.contains("加成") || pageText.contains("炼骨") || pageText.contains("练骨") -> "闯塔加成选择"
            pageText.contains("跳过") -> "闯塔跳过确认"
            pageText.contains("怒闯") -> "闯塔战斗入口"
            else -> "未知页面"
        }
    }

    private fun eventTypeName(eventType: Int?): String {
        return when (eventType) {
            null -> "manual"
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED -> "window_state_changed"
            AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED -> "window_content_changed"
            else -> "event_$eventType"
        }
    }

    fun getWorkflowSpec(): String {
        return GameFlows.workflowSpecJson().toString()
    }

    /**
     * 构造结果 JSON
     */
    private fun createResult(success: Boolean, message: String, extra: JSONObject? = null): JSONObject {
        return JSONObject().apply {
            put("success", success)
            put("message", message)
            put("status", lastStatus)
            put("isRunning", isRunning)
            put("currentFlow", currentFlow ?: "none")
            put("lastFlow", lastFlow ?: JSONObject.NULL)
            put("activePhase", activePhaseName())
            put("qiyuPhase", qiyuPhase.javaClass.simpleName)
            put("towerPhase", towerPhase.javaClass.simpleName)
            put("stepCount", stepCount)
            put("skippedStepCount", skippedStepCount)
            put("lastEventType", lastEventType)
            put("lastPageLabel", lastPageLabel)
            put("lastPageTextSample", lastPageTextSample)
            put("lastMessage", lastMessage)
            put("lastError", lastError ?: JSONObject.NULL)
            put("lastPageSignature", lastPageSignature ?: JSONObject.NULL)
            put("lastThrottleReason", lastThrottleReason ?: JSONObject.NULL)
            put("lastElapsedMillis", lastElapsedMillis)
            put(
                "qiyuEntryTransitionRemainingMillis",
                qiyuEntryTransitionDeadlineMillis
                    ?.minus(System.currentTimeMillis())
                    ?.coerceAtLeast(0)
                    ?: JSONObject.NULL,
            )
            put("workflowRuntime", gameFlows.runtimeJson(currentFlow ?: lastFlow))
            extra?.let { put("extra", it) }
        }
    }
}
