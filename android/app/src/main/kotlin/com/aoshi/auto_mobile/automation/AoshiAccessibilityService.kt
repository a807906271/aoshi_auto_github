package com.aoshi.auto_mobile.automation

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.content.Intent
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

    // 流程引擎
    private lateinit var gameFlows: GameFlows
    
    // 当前状态
    private var qiyuPhase: GameFlows.QiyuPhase = GameFlows.QiyuPhase.Idle
    private var towerPhase: GameFlows.TowerPhase = GameFlows.TowerPhase.Idle

    // 状态回调
    private var statusCallback: ((String, JSONObject) -> Unit)? = null

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        gameFlows = GameFlows(applicationContext)
        
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

        // 只处理前台应用的窗口变化
        if (event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED ||
            event.eventType == AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED) {
            
            executeCurrentFlow()
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
        isRunning = true
        qiyuPhase = GameFlows.QiyuPhase.Idle

        // 立即执行第一步
        val result = executeCurrentFlow()
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
        isRunning = true
        towerPhase = GameFlows.TowerPhase.Idle

        val result = executeCurrentFlow()
        return createResult(true, "闯塔流程已启动", result)
    }

    /**
     * 停止当前流程
     */
    fun stopFlow(): JSONObject {
        isRunning = false
        currentFlow = null
        qiyuPhase = GameFlows.QiyuPhase.Idle
        towerPhase = GameFlows.TowerPhase.Idle

        return createResult(true, "流程已停止")
    }

    /**
     * 获取当前状态
     */
    fun getStatus(): JSONObject {
        return JSONObject().apply {
            put("isRunning", isRunning)
            put("currentFlow", currentFlow ?: "none")
            put("qiyuPhase", qiyuPhase.javaClass.simpleName)
            put("towerPhase", towerPhase.javaClass.simpleName)
        }
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
    private fun executeCurrentFlow(): JSONObject {
        val root = rootInActiveWindow ?: return createResult(false, "无法获取窗口节点")

        return when (currentFlow) {
            "qiyu" -> {
                val (newPhase, message) = gameFlows.executeQiyuStep(root, qiyuPhase)
                qiyuPhase = newPhase
                
                when (newPhase) {
                    is GameFlows.QiyuPhase.Done -> {
                        isRunning = false
                        currentFlow = null
                        notifyStatus("completed", newPhase.javaClass.simpleName)
                        createResult(true, "奇遇流程完成")
                    }
                    is GameFlows.QiyuPhase.Failed -> {
                        isRunning = false
                        currentFlow = null
                        notifyStatus("failed", newPhase.reason)
                        createResult(false, "奇遇流程失败: ${newPhase.reason}")
                    }
                    else -> {
                        notifyStatus("running", newPhase.javaClass.simpleName)
                        createResult(true, message ?: "执行中")
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
                        notifyStatus("completed", newPhase.javaClass.simpleName)
                        createResult(true, "闯塔流程完成")
                    }
                    is GameFlows.TowerPhase.Failed -> {
                        isRunning = false
                        currentFlow = null
                        notifyStatus("failed", newPhase.reason)
                        createResult(false, "闯塔流程失败: ${newPhase.reason}")
                    }
                    else -> {
                        notifyStatus("running", newPhase.javaClass.simpleName)
                        createResult(true, message ?: "执行中")
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
        statusCallback?.invoke(status, JSONObject().apply {
            put("detail", detail)
            put("flow", currentFlow ?: "none")
        })
    }

    /**
     * 构造结果 JSON
     */
    private fun createResult(success: Boolean, message: String, extra: JSONObject? = null): JSONObject {
        return JSONObject().apply {
            put("success", success)
            put("message", message)
            extra?.let { put("extra", it) }
        }
    }
}
