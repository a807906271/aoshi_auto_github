package com.aoshi.auto_mobile.automation

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import org.json.JSONArray
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
        private const val TAG = "AoshiA11y"
        private const val STEP_INTERVAL_MILLIS = 650L
        private const val SAME_PAGE_INTERVAL_MILLIS = 1800L
        private const val PAGE_TEXT_SAMPLE_SIZE = 18
        private const val PAGE_SIGNATURE_TEXT_SIZE = 28
        private const val QIYU_ENTRY_TRANSITION_TIMEOUT_MILLIS = 30_000L
        private const val QIYU_DIVINATION_TRANSITION_TIMEOUT_MILLIS = 30_000L
        private const val FLOW_START_DELAY_MILLIS = 3_000L
        private const val SNAPSHOT_LOG_LIMIT = 3
        private const val FOCUS_LOSS_TOLERANCE_MILLIS = 2_000L  // 焦点丢失容忍时间

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

    private data class ForegroundSnapshot(
        val packageName: String,
        val pageLabel: String,
        val eventType: String,
        val capturedAtMillis: Long,
    )

    // 流程引擎
    private lateinit var gameFlows: GameFlows
    private var qiyuCoordinateAutomation: QiyuCoordinateAutomation? = null
    
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
    private var debugCollectedTexts: String = ""
    private var qiyuEntryTransitionDeadlineMillis: Long? = null
    private var qiyuDivinationTransitionDeadlineMillis: Long? = null
    private var targetGamePackageName: String? = null
    private var lastActiveWindowPackageName: String? = null
    private val foregroundSnapshots = mutableListOf<ForegroundSnapshot>()
    private var flowStartDeadlineMillis: Long? = null
    private var delayedFlowStart: Runnable? = null
    private var focusLossDetectedAtMillis: Long? = null
    private var delayedFocusLossStop: Runnable? = null
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
        Log.i(TAG, "onServiceConnected: 服务已连接")
        instance = this
        gameFlows = GameFlows()

        // 在系统已加载的 ServiceInfo 基础上补充运行时配置，
        // 保留 manifest 中的 canTakeScreenshot / canRetrieveWindowContent / settingsActivity。
        serviceInfo = serviceInfo.apply {
            eventTypes = AccessibilityEvent.TYPES_ALL_MASK
            flags = flags or AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS
            notificationTimeout = 100
            // 必须显式声明手势注入能力（XML 同源声明双保险）：
            // vivo/Funtouch 上未声明 canPerformGestures 时 dispatchGesture 返回 accepted
            // 但静默不执行不回调（已实测），声明后与 adb input tap 同链路生效。
            canPerformGestures = true
        }
        // 必须显式提交，否则系统仍然按 manifest 的旧 ServiceInfo 运行：
        // - takeScreenshot() 在 Android 14 上会因为 serviceInfo 没刷新而 onFailure
        // - FLAG_REPORT_VIEW_IDS、TYPES_ALL_MASK 等运行时叠加不会生效
        try {
            setServiceInfo(serviceInfo)
            // canTakeScreenshot 这个 Kotlin 属性来自 API 34；
            // 用反射读取，避开 compileSdk 绑定，避免 CI 在老 Flutter 上 unresolved。
            val canTakeScreenshot = try {
                val m = AccessibilityServiceInfo::class.java.getMethod("getCanTakeScreenshot")
                (m.invoke(serviceInfo) as? Boolean) ?: false
            } catch (_: Throwable) {
                false
            }
            Log.i(
                TAG,
                "setServiceInfo ok: eventTypes=0x${Integer.toHexString(serviceInfo.eventTypes)}, " +
                    "flags=0x${Integer.toHexString(serviceInfo.flags)}, " +
                    "timeout=${serviceInfo.notificationTimeout}, " +
                    "canTakeScreenshot=$canTakeScreenshot",
            )
        } catch (t: Throwable) {
            Log.e(TAG, "setServiceInfo failed", t)
        }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null) return
        Log.d(TAG, "onAccessibilityEvent: type=${event.eventType} pkg=${event.packageName} flow=$currentFlow isRunning=$isRunning")
        if (!isRunning || flowStartDeadlineMillis != null) return
        // 奇遇流程使用坐标自动化，不依赖无障碍事件驱动状态机
        // 但仍需要焦点丢失检测，所以不在此处 return
        if (event.packageName?.toString() == packageName) return
        
        val eventPackageName = event.packageName?.toString()
        
        // 焦点丢失容忍机制
        if (
            targetGamePackageName != null &&
            eventPackageName != null &&
            eventPackageName != targetGamePackageName
        ) {
            // 弹窗/悬浮窗（如企业微信顶部弹窗）会以非目标包名产生事件（type=64 窗口变化等），
            // 但前台焦点可能仍在游戏。事件包名≠目标时先核对当前真正聚焦的应用窗口，
            // 仍聚焦目标游戏则忽略该事件，避免误停流程（已实测：企业微信弹窗误触发焦点丢失）。
            if (isTargetGameFocused()) return

            // 检测到焦点离开目标应用
            if (focusLossDetectedAtMillis == null) {
                // 首次检测到焦点丢失，记录时间并启动延时停止
                focusLossDetectedAtMillis = System.currentTimeMillis()
                Log.w(TAG, "onAccessibilityEvent: 检测到焦点离开目标应用 pkg=$eventPackageName target=$targetGamePackageName, 将在 ${FOCUS_LOSS_TOLERANCE_MILLIS}ms 后停止流程")
                
                delayedFocusLossStop = Runnable {
                    if (isRunning && focusLossDetectedAtMillis != null) {
                        Log.e(TAG, "onAccessibilityEvent: 焦点丢失超时，停止流程")
                        stopFlow()
                    }
                }
                timeoutHandler.postDelayed(delayedFocusLossStop!!, FOCUS_LOSS_TOLERANCE_MILLIS)
            }
            return
        }
        
        // 焦点切回目标应用，取消延时停止
        if (focusLossDetectedAtMillis != null) {
            val lostDuration = System.currentTimeMillis() - focusLossDetectedAtMillis!!
            Log.i(TAG, "onAccessibilityEvent: 焦点已切回目标应用 pkg=$eventPackageName, 焦点丢失时长=${lostDuration}ms")
            delayedFocusLossStop?.let { timeoutHandler.removeCallbacks(it) }
            focusLossDetectedAtMillis = null
            delayedFocusLossStop = null
        }

        // 奇遇流程使用坐标自动化，不依赖事件驱动状态机
        if (currentFlow == "qiyu") return

        // 只处理已锁定游戏窗口的变化；本应用及其他应用的事件不能推进游戏状态机。
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
        Log.i(TAG, "startQiyu: 用户请求启动奇遇流程")
        currentFlow = "qiyu"
        lastFlow = "qiyu"
        lastStatus = "running"
        isRunning = true
        qiyuPhase = GameFlows.QiyuPhase.WaitStart
        targetGamePackageName = "com.tencent.JWX"  // 设置目标游戏包名（傲世西游）
        resetRuntime("请立即切换到游戏的奇遇入口界面，4秒后自动开始识别", "running")
        gameFlows.resetQiyu()
        qiyuCoordinateAutomation?.stop()
        qiyuCoordinateAutomation = QiyuCoordinateAutomation(
            service = this,
            onStatus = { runtime ->
                qiyuPhase = toQiyuPhase(runtime.stage)
                lastMessage = runtime.message
                lastStatus = "running"
                lastError = null
                notifyStatus("running", runtime.message)
            },
            onFailure = { reason -> failQiyu(reason) },
        )

        // 预留切回游戏前台的时间；准备期间的窗口事件不进入状态机。
        scheduleFlowStart("qiyu")
        Log.i(TAG, "startQiyu: 目标游戏包名已设置为 $targetGamePackageName")
        return createResult(true, "奇遇流程将在 3 秒后开始", getStatus())
    }

    /**
     * 启动闯塔流程
     */
    fun startTower(): JSONObject {
        if (isRunning) {
            return createResult(false, "已有流程在运行中")
        }
        Log.i(TAG, "startTower: 用户请求启动闯塔流程")
        currentFlow = "tower"
        lastFlow = "tower"
        lastStatus = "running"
        isRunning = true
        towerPhase = GameFlows.TowerPhase.ResolveBranch
        targetGamePackageName = "com.tencent.JWX"  // 设置目标游戏包名（傲世西游）
        resetRuntime("请在 3 秒内切回游戏，随后开始识别", "running")
        gameFlows.resetTower()

        scheduleFlowStart("tower")
        Log.i(TAG, "startTower: 目标游戏包名已设置为 $targetGamePackageName")
        return createResult(true, "闯塔流程将在 3 秒后开始", getStatus())
    }

    /**
     * 停止当前流程
     */
    fun stopFlow(): JSONObject {
        qiyuCoordinateAutomation?.stop()
        qiyuCoordinateAutomation = null
        isRunning = false
        currentFlow = null
        lastStatus = "idle"
        targetGamePackageName = null  // 清除目标游戏包名
        qiyuPhase = GameFlows.QiyuPhase.WaitStart
        towerPhase = GameFlows.TowerPhase.ResolveBranch
        
        // 清理焦点丢失相关状态
        delayedFocusLossStop?.let { timeoutHandler.removeCallbacks(it) }
        focusLossDetectedAtMillis = null
        delayedFocusLossStop = null
        
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
     * 核对当前真正聚焦的应用窗口是否仍是目标游戏。
     * 弹窗/悬浮窗事件（非目标包名）到来时，若聚焦窗口仍属于目标游戏则视为未丢失焦点。
     * vivo 上 getWindows 异常/窗口无 root 时返回 false（按事件包名保守判定），不抛异常。
     */
    private fun isTargetGameFocused(): Boolean {
        val target = targetGamePackageName ?: return false
        return try {
            windows.firstOrNull { window ->
                window.type == AccessibilityWindowInfo.TYPE_APPLICATION &&
                    (window.isActive || window.isFocused)
            }?.let { window ->
                (window.root?.packageName?.toString() ?: window.title?.toString())
                    ?.startsWith(target) ?: false
            } ?: false
        } catch (_: Throwable) {
            false
        }
    }

    private fun scheduleFlowStart(flow: String) {
        clearDelayedFlowStart()
        flowStartDeadlineMillis = System.currentTimeMillis() + FLOW_START_DELAY_MILLIS
        delayedFlowStart = Runnable {
            flowStartDeadlineMillis = null
            delayedFlowStart = null
            if (isRunning && currentFlow == flow) {
                if (flow == "qiyu") {
                    // 奇遇流程：再等待1秒确保游戏界面稳定后再开始截图
                    lastMessage = "等待游戏界面稳定..."
                    notifyStatus("running", lastMessage)
                    timeoutHandler.postDelayed({
                        if (isRunning && currentFlow == "qiyu") {
                            qiyuCoordinateAutomation?.start()
                        }
                    }, 1000)
                } else {
                    executeCurrentFlow(null)
                }
            }
        }.also { timeoutHandler.postDelayed(it, FLOW_START_DELAY_MILLIS) }
    }

    private fun clearDelayedFlowStart() {
        delayedFlowStart?.let(timeoutHandler::removeCallbacks)
        delayedFlowStart = null
        flowStartDeadlineMillis = null
        
        // 同时清理焦点丢失检测状态
        delayedFocusLossStop?.let { timeoutHandler.removeCallbacks(it) }
        focusLossDetectedAtMillis = null
        delayedFocusLossStop = null
    }

    /**
     * 执行当前流程的一步
     */
    private fun executeCurrentFlow(eventType: Int? = null): JSONObject {
        val root = rootInActiveWindow ?: return createResult(false, "无法获取窗口节点")
        if (root.packageName?.toString() == packageName) {
            lastEventType = eventTypeName(eventType)
            lastMessage = "等待游戏返回前台"
            return createResult(true, lastMessage)
        }

        val pageSnapshot = createPageSnapshot(root)
        val now = System.currentTimeMillis()
        val eventTypeName = eventTypeName(eventType)
        recordForegroundSnapshot(
            packageName = root.packageName?.toString(),
            pageLabel = pageSnapshot.label,
            eventType = eventTypeName,
            capturedAtMillis = now,
        )
        lastEventType = eventTypeName
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
        qiyuCoordinateAutomation?.stop()
        qiyuCoordinateAutomation = null
        clearQiyuTransitionTimeouts()
        qiyuPhase = GameFlows.QiyuPhase.Failed(reason)
        isRunning = false
        currentFlow = null
        lastStatus = "failed"
        lastError = reason
        lastMessage = "奇遇流程失败: $reason"
        notifyStatus("failed", reason)
    }

    private fun recordForegroundSnapshot(
        packageName: String?,
        pageLabel: String,
        eventType: String,
        capturedAtMillis: Long,
    ) {
        val activePackageName = packageName ?: return
        if (activePackageName == lastActiveWindowPackageName) return

        lastActiveWindowPackageName = activePackageName
        foregroundSnapshots += ForegroundSnapshot(
            packageName = activePackageName,
            pageLabel = pageLabel,
            eventType = eventType,
            capturedAtMillis = capturedAtMillis,
        )
        if (foregroundSnapshots.size > SNAPSHOT_LOG_LIMIT) {
            foregroundSnapshots.removeAt(0)
        }
    }

    private fun foregroundSnapshotsJson(): JSONArray {
        return JSONArray().apply {
            foregroundSnapshots.forEach { snapshot ->
                put(JSONObject().apply {
                    put("packageName", snapshot.packageName)
                    put("pageLabel", snapshot.pageLabel)
                    put("eventType", snapshot.eventType)
                    put("capturedAtMillis", snapshot.capturedAtMillis)
                })
            }
        }
    }

    private fun createStatusSnapshot(): JSONObject {
        return JSONObject().apply {
            put("status", lastStatus)
            put("isRunning", isRunning)
            put("currentFlow", currentFlow ?: "none")
            put("lastFlow", lastFlow ?: JSONObject.NULL)
            put("activePhase", activePhaseName())
            put("qiyuPhase", qiyuPhaseName())
            put("towerPhase", towerPhaseName())
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
            put("foregroundSnapshots", foregroundSnapshotsJson())
            put("debugCollectedTexts", debugCollectedTexts)
            put(
                "flowStartRemainingMillis",
                flowStartDeadlineMillis
                    ?.minus(System.currentTimeMillis())
                    ?.coerceAtLeast(0)
                    ?: JSONObject.NULL,
            )
            put(
                "qiyuEntryTransitionRemainingMillis",
                qiyuEntryTransitionDeadlineMillis
                    ?.minus(System.currentTimeMillis())
                    ?.coerceAtLeast(0)
                    ?: JSONObject.NULL,
            )
            put("workflowRuntime", qiyuRuntimeJson())
        }
    }

    private fun activePhaseName(): String {
        return when (currentFlow ?: lastFlow) {
            "qiyu" -> qiyuPhaseName()
            "tower" -> towerPhaseName()
            else -> "Idle"
        }
    }

    private fun toQiyuPhase(stage: QiyuCoordinateAutomation.Stage): GameFlows.QiyuPhase = when (stage) {
        QiyuCoordinateAutomation.Stage.ENTRY -> GameFlows.QiyuPhase.WaitStart
        QiyuCoordinateAutomation.Stage.DIVINATION -> GameFlows.QiyuPhase.EnterDivination
        QiyuCoordinateAutomation.Stage.CHESTS,
        QiyuCoordinateAutomation.Stage.SELECT_INSPECT -> GameFlows.QiyuPhase.SelectBox
        QiyuCoordinateAutomation.Stage.WAIT_INSPECT -> GameFlows.QiyuPhase.InspectBoxes
        QiyuCoordinateAutomation.Stage.SELECT_OPEN,
        QiyuCoordinateAutomation.Stage.WAIT_OPEN -> GameFlows.QiyuPhase.OpenBest
        QiyuCoordinateAutomation.Stage.WAIT_SETTLEMENT -> GameFlows.QiyuPhase.ConfirmReward
        QiyuCoordinateAutomation.Stage.FAILED -> GameFlows.QiyuPhase.Failed("截图流程失败")
        else -> GameFlows.QiyuPhase.WaitStart
    }

    private fun qiyuRuntimeJson(): JSONObject {
        return qiyuCoordinateAutomation?.runtimeJson() ?: gameFlows.runtimeJson(currentFlow ?: lastFlow)
    }

    private fun qiyuPhaseName(): String = when (qiyuPhase) {
        GameFlows.QiyuPhase.WaitStart -> "WaitStart"
        GameFlows.QiyuPhase.EnterDivination -> "EnterDivination"
        GameFlows.QiyuPhase.SelectBox -> "SelectBox"
        GameFlows.QiyuPhase.InspectBoxes -> "InspectBoxes"
        GameFlows.QiyuPhase.OpenBest -> "OpenBest"
        GameFlows.QiyuPhase.FinishRound -> "FinishRound"
        GameFlows.QiyuPhase.ConfirmReward -> "ConfirmReward"
        is GameFlows.QiyuPhase.Failed -> "Failed"
        else -> "Idle"
    }

    private fun towerPhaseName(): String = when (towerPhase) {
        GameFlows.TowerPhase.ResolveBranch -> "ResolveBranch"
        GameFlows.TowerPhase.EnterBattle -> "EnterBattle"
        GameFlows.TowerPhase.ChooseBuff -> "ChooseBuff"
        GameFlows.TowerPhase.RevealSkip -> "RevealSkip"
        GameFlows.TowerPhase.SkipBattle -> "SkipBattle"
        GameFlows.TowerPhase.ConfirmSkip -> "ConfirmSkip"
        GameFlows.TowerPhase.Done -> "Done"
        is GameFlows.TowerPhase.Failed -> "Failed"
        else -> "Idle"
    }

    private fun resetRuntime(message: String, status: String) {
        clearDelayedFlowStart()
        clearQiyuTransitionTimeouts()
        lastStepAtMillis = 0L
        lastPageSignature = null
        lastPageLabel = "未知页面"
        lastPageTextSample = ""
        lastActiveWindowPackageName = null
        foregroundSnapshots.clear()
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
        
        // 保存调试信息，供 Flutter 显示
        debugCollectedTexts = texts.take(50).joinToString(" | ")
        
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
            listOf("天赋奇遇", "天赐奇遇", "天脉奇遇", "开启奇遇").any(pageText::contains) -> "奇遇入口"
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
            put("qiyuPhase", qiyuPhaseName())
            put("towerPhase", towerPhaseName())
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
            put("foregroundSnapshots", foregroundSnapshotsJson())
            put("debugCollectedTexts", debugCollectedTexts)
            put(
                "flowStartRemainingMillis",
                flowStartDeadlineMillis
                    ?.minus(System.currentTimeMillis())
                    ?.coerceAtLeast(0)
                    ?: JSONObject.NULL,
            )
            put(
                "qiyuEntryTransitionRemainingMillis",
                qiyuEntryTransitionDeadlineMillis
                    ?.minus(System.currentTimeMillis())
                    ?.coerceAtLeast(0)
                    ?: JSONObject.NULL,
            )
            put("workflowRuntime", qiyuRuntimeJson())
            extra?.let { put("extra", it) }
        }
    }
}
