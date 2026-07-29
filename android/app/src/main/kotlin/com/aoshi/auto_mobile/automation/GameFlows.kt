package com.aoshi.auto_mobile.automation

import android.content.Context
import android.graphics.Rect
import android.view.accessibility.AccessibilityNodeInfo
import org.json.JSONObject

/**
 * 游戏流程状态机
 * 定义奇遇和日常闯塔的自动化流程
 */
class GameFlows(private val context: Context) {

    // ===== 状态定义 =====
    
    sealed class FlowState {
        object Idle : FlowState()
        object Running : FlowState()
        object Paused : FlowState()
        data class Completed(val result: JSONObject) : FlowState()
        data class Failed(val reason: String) : FlowState()
    }

    sealed class FlowType {
        object Qiyu : FlowType()
        object Tower : FlowType()
    }

    // ===== 奇遇流程 =====

    sealed class QiyuPhase {
        object Idle : QiyuPhase()
        object Start : QiyuPhase()           // 点击"开始奇遇"
        object Enter : QiyuPhase()           // 进入奇遇界面
        object BalanceCounts : QiyuPhase()   // 读取分数和次数
        object Inspect : QiyuPhase()         // 查看宝箱
        object Calculate : QiyuPhase()       // 计算最优选择
        object OpenBest : QiyuPhase()        // 打开最优宝箱
        object Done : QiyuPhase()
        data class Failed(val reason: String) : QiyuPhase()
    }

    /**
     * 奇遇流程状态机
     * 返回：下一步动作描述，或 null 表示流程结束
     */
    fun executeQiyuStep(root: AccessibilityNodeInfo?, currentState: QiyuPhase): Pair<QiyuPhase, String?> {
        if (root == null) return QiyuPhase.Failed("无法获取窗口节点") to null

        return when (currentState) {
            is QiyuPhase.Idle -> {
                // 查找"开始奇遇"按钮
                val startBtn = NodeQuery.findAnyText(root, listOf("开始奇遇", "开始", "奇遇"))
                if (startBtn != null && NodeQuery.clickNearestClickable(startBtn)) {
                    QiyuPhase.Enter to "已点击开始奇遇"
                } else {
                    QiyuPhase.Failed("未找到「开始奇遇」按钮") to null
                }
            }

            is QiyuPhase.Enter -> {
                // 验证已进入奇遇界面
                val scoreNode = NodeQuery.findContainsText(root, "分")
                if (scoreNode != null) {
                    QiyuPhase.BalanceCounts to "已进入奇遇界面"
                } else {
                    QiyuPhase.Failed("未检测到奇遇界面") to null
                }
            }

            is QiyuPhase.BalanceCounts -> {
                // 读取当前分数和查看/开启次数
                // TODO: 解析具体数值
                QiyuPhase.Inspect to "已读取分数信息"
            }

            is QiyuPhase.Inspect -> {
                // 查找"查看宝箱"按钮
                val inspectBtn = NodeQuery.findText(root, "查看宝箱")
                if (inspectBtn != null && NodeQuery.clickNearestClickable(inspectBtn)) {
                    QiyuPhase.Calculate to "已点击查看宝箱"
                } else {
                    // 可能已经全部查看完毕，进入选择阶段
                    QiyuPhase.OpenBest to "无更多宝箱可查看"
                }
            }

            is QiyuPhase.Calculate -> {
                // 解析宝箱结果文本
                // TODO: 实现数值计算逻辑
                QiyuPhase.OpenBest to "计算完成"
            }

            is QiyuPhase.OpenBest -> {
                // 点击最优宝箱
                // TODO: 根据计算结果选择
                QiyuPhase.Done to "流程完成"
            }

            is QiyuPhase.Done -> currentState to null
            is QiyuPhase.Failed -> currentState to null
        }
    }

    // ===== 日常闯塔流程 =====

    sealed class TowerPhase {
        object Idle : TowerPhase()
        object Challenge : TowerPhase()      // 闯关中
        object ChooseBuff : TowerPhase()     // 选择加成
        object Reward : TowerPhase()         // 领取奖励
        object ChooseNextStage : TowerPhase() // 选择下一关
        object Done : TowerPhase()
        data class Failed(val reason: String) : TowerPhase()
    }

    /**
     * 闯塔流程状态机
     */
    fun executeTowerStep(root: AccessibilityNodeInfo?, currentState: TowerPhase): Pair<TowerPhase, String?> {
        if (root == null) return TowerPhase.Failed("无法获取窗口节点") to null

        return when (currentState) {
            is TowerPhase.Idle -> {
                // 检测当前是否在闯塔界面
                val towerIndicator = NodeQuery.findAnyText(root, listOf("闯塔", "层", "关卡"))
                if (towerIndicator != null) {
                    TowerPhase.Challenge to "检测到闯塔界面"
                } else {
                    TowerPhase.Failed("未检测到闯塔界面") to null
                }
            }

            is TowerPhase.Challenge -> {
                // 检测是否有可跳过的元素
                val skipBtn = NodeQuery.findAnyText(root, listOf("跳过", "继续", "确定"))
                if (skipBtn != null && NodeQuery.clickNearestClickable(skipBtn)) {
                    currentState to "已点击跳过"
                } else {
                    // 检测是否进入加成选择
                    val buffIndicator = NodeQuery.findContainsText(root, "加成")
                    if (buffIndicator != null) {
                        TowerPhase.ChooseBuff to "进入加成选择"
                    } else {
                        currentState to "等待闯关完成"
                    }
                }
            }

            is TowerPhase.ChooseBuff -> {
                // 查找所有加成选项
                val buffNodes = NodeQuery.findAllByText(root, "加成")
                // TODO: 排除"炼骨"，选择数值最大的
                
                if (buffNodes.isNotEmpty()) {
                    // 暂时点击第一个可用的
                    if (NodeQuery.clickNearestClickable(buffNodes.first())) {
                        TowerPhase.Challenge to "已选择加成"
                    } else {
                        TowerPhase.Failed("无法点击加成选项") to null
                    }
                } else {
                    TowerPhase.Failed("未找到加成选项") to null
                }
            }

            is TowerPhase.Reward -> {
                val rewardBtn = NodeQuery.findText(root, "领取奖励")
                if (rewardBtn != null && NodeQuery.clickNearestClickable(rewardBtn)) {
                    TowerPhase.Challenge to "已领取奖励"
                } else {
                    TowerPhase.Failed("未找到领取奖励按钮") to null
                }
            }

            is TowerPhase.ChooseNextStage -> {
                // TODO: 读取关卡怪物数量，选择最少的
                TowerPhase.Challenge to "已选择下一关"
            }

            is TowerPhase.Done -> currentState to null
            is TowerPhase.Failed -> currentState to null
        }
    }
}
