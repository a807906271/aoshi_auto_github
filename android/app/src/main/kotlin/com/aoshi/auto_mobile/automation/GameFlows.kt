package com.aoshi.auto_mobile.automation

import android.view.accessibility.AccessibilityNodeInfo
import java.math.BigDecimal
import java.math.RoundingMode
import org.json.JSONArray
import org.json.JSONObject

/**
 * 游戏自动化流程状态机。
 *
 * 数据流：
 * 页面文本/节点树 -> 页面分类 -> 受控动作 -> 下一次无障碍事件继续推进。
 */
class GameFlows {

    data class PhaseSpec(
        val id: String,
        val label: String,
        val hint: String,
    )

    data class FlowSpec(
        val id: String,
        val name: String,
        val description: String,
        val icon: String,
        val phases: List<PhaseSpec>,
    )

    companion object {
        private val qiyuSpec = FlowSpec(
            id = "qiyu",
            name = "奇遇自动化",
            description = "自动查看宝箱、计算最优选择、开启最佳宝箱",
            icon = "🎁",
            phases = listOf(
                PhaseSpec("WaitStart", "等待奇遇入口", "等待出现开启奇遇按钮"),
                PhaseSpec("EnterDivination", "进入算卦页", "点击开启奇遇后进入算卦"),
                PhaseSpec("SelectBox", "进入宝箱页", "点击算卦后等待宝箱页面"),
                PhaseSpec("InspectBoxes", "逐个记录宝箱规则", "读取每个宝箱的加成规则"),
                PhaseSpec("OpenBest", "选择最优宝箱", "按当前分数计算最优方案"),
                PhaseSpec("FinishRound", "结束本轮", "完成选择后等待结算"),
                PhaseSpec("ConfirmReward", "确认领奖", "点击确定后返回天赋奇遇入口，开始下一轮"),
            ),
        )
        private val towerSpec = FlowSpec(
            id = "tower",
            name = "日常闯塔",
            description = "自动选择加成（排除炼骨）、跳过闯关、领取奖励",
            icon = "🗼",
            phases = listOf(
                PhaseSpec("ResolveBranch", "闯塔分支", "识别普通/精英闯塔、加成或奖励页面"),
                PhaseSpec("EnterBattle", "进入战斗页", "在闯塔页点击怒闯后等待战斗页面"),
                PhaseSpec("RevealSkip", "呼出跳过", "在战斗页执行一次安全点击，等待跳过按钮"),
                PhaseSpec("SkipBattle", "跳过战斗", "点击跳过后等待胜利结算"),
                PhaseSpec("ConfirmSkip", "确认结果", "在胜利页点击确定后识别下一分支"),
                PhaseSpec("Done", "结束闯塔", "检测到结束闯塔或已进入完成态"),
            ),
        )

        fun workflowSpecs(): List<FlowSpec> = listOf(qiyuSpec, towerSpec)

        fun workflowSpecJson(): JSONArray {
            return JSONArray().apply {
                workflowSpecs().forEach { flow ->
                    put(JSONObject().apply {
                        put("id", flow.id)
                        put("name", flow.name)
                        put("description", flow.description)
                        put("icon", flow.icon)
                        put("phases", JSONArray().apply {
                            flow.phases.forEach { phase ->
                                put(JSONObject().apply {
                                    put("id", phase.id)
                                    put("label", phase.label)
                                    put("hint", phase.hint)
                                })
                            }
                        })
                    })
                }
            }
        }
    }

    sealed class QiyuPhase {
        object WaitStart : QiyuPhase()
        object EnterDivination : QiyuPhase()
        object SelectBox : QiyuPhase()
        object InspectBoxes : QiyuPhase()
        object OpenBest : QiyuPhase()
        object FinishRound : QiyuPhase()
        object ConfirmReward : QiyuPhase()
        data class Failed(val reason: String) : QiyuPhase()
    }

    sealed class TowerPhase {
        object ResolveBranch : TowerPhase()
        object EnterBattle : TowerPhase()
        object ChooseBuff : TowerPhase()
        object RevealSkip : TowerPhase()
        object SkipBattle : TowerPhase()
        object ConfirmSkip : TowerPhase()
        object Done : TowerPhase()
        data class Failed(val reason: String) : TowerPhase()
    }

    private data class BoxObservation(
        val index: Int,
        val rules: List<String>,
    )

    private data class QiyuRuntime(
        val observations: List<BoxObservation> = emptyList(),
        val currentScore: Int? = null,
        val pendingBoxIndex: Int? = null,
        val nextBoxIndex: Int = 0,
        val completedRounds: Int = 0,
        val openingOrder: List<Int> = emptyList(),
        val openedBoxIndexes: List<Int> = emptyList(),
        val expectedScoreAfterLastOpen: Int? = null,
        val lastDecision: String? = null,
    )

    private data class TowerRuntime(
        val candidates: List<String> = emptyList(),
        val selected: String? = null,
        val avoided: List<String> = emptyList(),
        val lastDecision: String? = null,
    )

    private var qiyu = QiyuRuntime()
    private var tower = TowerRuntime()

    fun resetQiyu() {
        qiyu = QiyuRuntime()
    }

    fun resetTower() {
        tower = TowerRuntime()
    }

    fun runtimeJson(flowId: String?): JSONObject {
        return when (flowId) {
            "qiyu" -> JSONObject().apply {
                put("currentScore", qiyu.currentScore ?: JSONObject.NULL)
                put("pendingBoxIndex", qiyu.pendingBoxIndex ?: JSONObject.NULL)
                put("nextBoxIndex", qiyu.nextBoxIndex)
                put("completedRounds", qiyu.completedRounds)
                put("openingOrder", JSONArray(qiyu.openingOrder.map { it + 1 }))
                put("openedBoxIndexes", JSONArray(qiyu.openedBoxIndexes.map { it + 1 }))
                put("expectedScoreAfterLastOpen", qiyu.expectedScoreAfterLastOpen ?: JSONObject.NULL)
                put("lastDecision", qiyu.lastDecision ?: JSONObject.NULL)
                put("observations", JSONArray().apply {
                    qiyu.observations.forEach { observation ->
                        put(JSONObject().apply {
                            put("index", observation.index)
                            put("label", "第 ${observation.index + 1} 个宝箱")
                            put("rules", JSONArray(observation.rules))
                        })
                    }
                })
            }
            "tower" -> JSONObject().apply {
                put("candidates", JSONArray(tower.candidates))
                put("selected", tower.selected ?: JSONObject.NULL)
                put("avoided", JSONArray(tower.avoided))
                put("lastDecision", tower.lastDecision ?: JSONObject.NULL)
            }
            else -> JSONObject()
        }
    }

    fun executeQiyuStep(root: AccessibilityNodeInfo?, currentState: QiyuPhase): Pair<QiyuPhase, String?> {
        if (root == null) return QiyuPhase.Failed("无法获取窗口节点") to null

        val pageText = NodeQuery.collectTexts(root).joinToString(" ")
        val rewardConfirm = NodeQuery.findButton(root, listOf("确定"))
        val start = NodeQuery.findButton(root, listOf("开启奇遇", "开始奇遇"))
        val finish = NodeQuery.findButton(root, listOf("完成"))
        val inspect = NodeQuery.findButton(root, listOf("查看宝箱"))
        val open = NodeQuery.findButton(root, listOf("开启宝箱"))

        return when {
            currentState is QiyuPhase.WaitStart -> {
                val entryTitles = listOf("天赋奇遇", "天赐奇遇", "天脉奇遇")
                val isEntryPage = entryTitles.any(pageText::contains)
                if (!isEntryPage || start == null) {
                    QiyuPhase.WaitStart to "等待奇遇入口标题与开启按钮"
                } else if (NodeQuery.clickNearestClickable(start)) {
                    QiyuPhase.EnterDivination to "已点击开启奇遇，等待进入算卦页"
                } else {
                    QiyuPhase.Failed("无法点击开启奇遇") to null
                }
            }

            currentState is QiyuPhase.EnterDivination -> {
                val isDivinationPage = pageText.contains("奇遇") && pageText.contains("算卦")
                val divination = NodeQuery.findButton(root, listOf("算卦"))
                if (!isDivinationPage || divination == null) {
                    QiyuPhase.EnterDivination to "等待算卦页与算卦按钮"
                } else if (NodeQuery.clickNearestClickable(divination)) {
                    QiyuPhase.SelectBox to "已点击算卦，等待进入宝箱页"
                } else {
                    QiyuPhase.Failed("无法点击算卦") to null
                }
            }

            currentState is QiyuPhase.ConfirmReward -> {
                val isRewardDialog = pageText.contains("本局得分") && rewardConfirm != null
                if (!isRewardDialog) {
                    QiyuPhase.ConfirmReward to "等待本局得分奖励弹框"
                } else if (NodeQuery.clickNearestClickable(rewardConfirm)) {
                    val completedRounds = qiyu.completedRounds + 1
                    qiyu = QiyuRuntime(
                        completedRounds = completedRounds,
                        lastDecision = "第 $completedRounds 轮领奖确认，等待返回天赋奇遇入口",
                    )
                    QiyuPhase.WaitStart to "已确认第 $completedRounds 轮奖励，等待下一轮入口"
                } else {
                    QiyuPhase.Failed("无法点击奖励确定按钮") to null
                }
            }

            currentState is QiyuPhase.SelectBox ||
                currentState is QiyuPhase.InspectBoxes ||
                currentState is QiyuPhase.OpenBest -> {
                executeQiyuBoxPage(root, pageText, inspect, open, finish)
            }

            pageText.contains("本局得分") && rewardConfirm != null -> {
                val parsedRules = parseRules(pageText)
                val pendingBoxIndex = qiyu.pendingBoxIndex
                val nextObservations = if (pendingBoxIndex != null && parsedRules.isNotEmpty()) {
                    val currentObservation = BoxObservation(pendingBoxIndex, parsedRules)
                    (qiyu.observations.filterNot { it.index == currentObservation.index } + currentObservation)
                } else {
                    qiyu.observations
                }
                qiyu = qiyu.copy(
                    currentScore = parseCurrentScore(pageText) ?: qiyu.currentScore,
                    observations = nextObservations,
                    pendingBoxIndex = null,
                    lastDecision = when {
                        parsedRules.isNotEmpty() && pendingBoxIndex != null -> "已记录第 ${pendingBoxIndex + 1} 个宝箱规则"
                        else -> qiyu.lastDecision
                    },
                )

                when {
                    remainingCountAfter(pageText, "查看宝箱") > 0 -> inspectNextBox(root, inspect)
                    remainingCountAfter(pageText, "开启宝箱") > 0 -> openBestBox(root, open)
                    finish != null -> {
                        if (NodeQuery.clickNearestClickable(finish)) {
                            QiyuPhase.ConfirmReward to "已点击完成，等待领取奖励"
                        } else {
                            QiyuPhase.Failed("无法点击完成按钮") to null
                        }
                    }
                    else -> QiyuPhase.Failed("宝箱界面缺少可执行动作") to null
                }
            }

            currentState is QiyuPhase.Failed -> currentState to null
            else -> QiyuPhase.Failed("当前页面不是可识别的奇遇流程页面") to null
        }
    }

    fun executeTowerStep(root: AccessibilityNodeInfo?, currentState: TowerPhase): Pair<TowerPhase, String?> {
        if (root == null) return TowerPhase.Failed("无法获取窗口节点") to null

        val pageText = NodeQuery.collectTexts(root).joinToString(" ")
        val endTower = NodeQuery.findButton(root, listOf("结束闯塔", "结束"))
        val reward = NodeQuery.findButton(root, listOf("领取", "领取奖励"))
        val skip = NodeQuery.findButton(root, listOf("跳过"))
        val confirm = NodeQuery.findButton(root, listOf("确定", "确认"))
        val rage = NodeQuery.findButton(root, listOf("怒闯"))

        if (pageText.contains("失败")) {
            return TowerPhase.Failed("闯塔失败，流程终止") to null
        }

        if (currentState is TowerPhase.ResolveBranch) {
            val isTowerEntry = pageText.contains("精英闯塔") || pageText.contains("普通闯塔")
            val isBuffPage = pageText.contains("强化属性") || pageText.contains("加成属性")
            val isRewardPage = pageText.contains("恭喜获得") && reward != null
            return when {
                isBuffPage -> chooseTowerBuff(root)
                isRewardPage -> {
                    if (NodeQuery.clickNearestClickable(reward)) {
                        TowerPhase.ResolveBranch to "已领取闯塔奖励，等待下一分支"
                    } else {
                        TowerPhase.Failed("无法点击领取奖励") to null
                    }
                }
                isTowerEntry && rage != null -> {
                    if (NodeQuery.clickNearestClickable(rage)) {
                        TowerPhase.EnterBattle to "已点击怒闯，等待进入战斗页"
                    } else {
                        TowerPhase.Failed("无法点击怒闯") to null
                    }
                }
                else -> TowerPhase.ResolveBranch to "等待闯塔继续、加成或奖励分支"
            }
        }

        return when {
            currentState is TowerPhase.EnterBattle -> {
                if (NodeQuery.clickRootOrLargestClickable(root)) {
                    TowerPhase.RevealSkip to "已在战斗页触发任意点击，等待跳过按钮出现"
                } else {
                    TowerPhase.Failed("无法在战斗页触发跳过按钮") to null
                }
            }
            currentState is TowerPhase.RevealSkip -> {
                if (skip != null && NodeQuery.clickNearestClickable(skip)) {
                    TowerPhase.SkipBattle to "已点击跳过，等待下一流程页面"
                } else {
                    TowerPhase.RevealSkip to "等待跳过按钮出现"
                }
            }
            currentState is TowerPhase.SkipBattle -> {
                val isVictoryResult = pageText.contains("胜利") && confirm != null
                if (!isVictoryResult) {
                    TowerPhase.SkipBattle to "已跳过战斗，等待胜利结算页"
                } else if (NodeQuery.clickNearestClickable(confirm)) {
                    TowerPhase.ResolveBranch to "已确认闯塔结果，等待继续、加成或奖励分支"
                } else {
                    TowerPhase.Failed("无法点击闯塔结果确定按钮") to null
                }
            }
            endTower != null -> TowerPhase.Done to "检测到结束闯塔"

            reward != null && pageText.contains("奖励") -> {
                if (NodeQuery.clickNearestClickable(reward)) {
                    TowerPhase.ResolveBranch to "已领取闯塔奖励"
                } else {
                    TowerPhase.Failed("无法点击领取奖励") to null
                }
            }

            pageText.contains("加成") || pageText.contains("练骨") || pageText.contains("炼骨") -> {
                chooseTowerBuff(root)
            }

            confirm != null && currentState is TowerPhase.ConfirmSkip -> {
                if (NodeQuery.clickNearestClickable(confirm)) {
                    TowerPhase.ResolveBranch to "已确认跳过结果"
                } else {
                    TowerPhase.Failed("无法点击确定") to null
                }
            }

            skip != null -> {
                if (NodeQuery.clickNearestClickable(skip)) {
                    TowerPhase.ConfirmSkip to "已点击跳过"
                } else {
                    TowerPhase.Failed("无法点击跳过") to null
                }
            }

            rage != null -> {
                if (NodeQuery.clickNearestClickable(rage)) {
                    TowerPhase.RevealSkip to "已点击怒闯"
                } else {
                    TowerPhase.Failed("无法点击怒闯") to null
                }
            }

            currentState is TowerPhase.RevealSkip -> {
                if (NodeQuery.clickRootOrLargestClickable(root)) {
                    TowerPhase.SkipBattle to "已点击可点击页面容器呼出跳过"
                } else {
                    TowerPhase.Failed("无法找到可点击容器呼出跳过按钮") to null
                }
            }

            currentState is TowerPhase.Done -> currentState to null
            currentState is TowerPhase.Failed -> currentState to null
            else -> TowerPhase.Failed("当前页面没有可识别的闯塔动作") to null
        }
    }

    private fun executeQiyuBoxPage(
        root: AccessibilityNodeInfo,
        pageText: String,
        inspectButton: AccessibilityNodeInfo?,
        openButton: AccessibilityNodeInfo?,
        finishButton: AccessibilityNodeInfo?,
    ): Pair<QiyuPhase, String?> {
        val observedScore = parseCurrentScore(pageText)
            ?: return QiyuPhase.Failed("无法读取宝箱页当前分数") to null
        val parsedRules = parseRules(pageText)
        val pendingObservation = qiyu.pendingBoxIndex
        val observations = if (pendingObservation != null && parsedRules.isNotEmpty()) {
            val observation = BoxObservation(pendingObservation, parsedRules)
            qiyu.observations.filterNot { it.index == observation.index } + observation
        } else {
            qiyu.observations
        }
        val expected = qiyu.expectedScoreAfterLastOpen
        val needsReplan = expected != null && expected != observedScore
        qiyu = qiyu.copy(
            currentScore = observedScore,
            observations = observations,
            pendingBoxIndex = null,
            openingOrder = if (needsReplan) emptyList() else qiyu.openingOrder,
            expectedScoreAfterLastOpen = null,
            lastDecision = when {
                needsReplan -> "实际分数 $observedScore 与预算 $expected 不一致，重新规划剩余宝箱"
                pendingObservation != null && parsedRules.isNotEmpty() -> "已记录第 ${pendingObservation + 1} 个宝箱规则"
                else -> qiyu.lastDecision
            },
        )

        val remainingInspects = remainingCountAfter(pageText, "查看宝箱").coerceAtLeast(0)
        val remainingOpens = remainingCountAfter(pageText, "开启宝箱").coerceAtLeast(0)
        return when {
            remainingInspects > 0 -> inspectNextBox(root, inspectButton)
            remainingOpens > 0 -> openNextPlannedBox(root, openButton, remainingOpens)
            finishButton != null && NodeQuery.clickNearestClickable(finishButton) -> {
                QiyuPhase.ConfirmReward to "已完成最优开启序列，等待奖励弹框"
            }
            finishButton == null -> QiyuPhase.Failed("宝箱次数耗尽但未找到完成按钮") to null
            else -> QiyuPhase.Failed("无法点击完成按钮") to null
        }
    }

    private fun openNextPlannedBox(
        root: AccessibilityNodeInfo,
        openButton: AccessibilityNodeInfo?,
        remainingOpens: Int,
    ): Pair<QiyuPhase, String?> {
        val current = qiyu.currentScore ?: return QiyuPhase.Failed("无法读取当前分数") to null
        val available = qiyu.observations.filterNot { it.index in qiyu.openedBoxIndexes }
        val plan = qiyu.openingOrder
            .filter { index -> available.any { it.index == index } }
            .take(remainingOpens)
            .ifEmpty {
                bestOpeningPlan(current, available, remainingOpens).order
            }
        val nextIndex = plan.firstOrNull()
            ?: return QiyuPhase.Failed("没有已查看的宝箱可用于开启") to null
        val observation = available.firstOrNull { it.index == nextIndex }
            ?: return QiyuPhase.Failed("最优计划引用了不存在的宝箱") to null
        val expected = observation.rules.fold(current) { score, rule -> applyQiyuRule(score, rule) }

        if (!selectBoxByIndex(root, nextIndex)) return QiyuPhase.Failed("无法选中计划开启的宝箱") to null
        if (openButton == null || !NodeQuery.clickNearestClickable(openButton)) {
            return QiyuPhase.Failed("无法点击开启宝箱") to null
        }
        qiyu = qiyu.copy(
            openingOrder = plan,
            openedBoxIndexes = qiyu.openedBoxIndexes + nextIndex,
            expectedScoreAfterLastOpen = expected,
            lastDecision = "预算开启第 ${nextIndex + 1} 个宝箱：$current → $expected；剩余步骤 ${plan.size - 1}",
        )
        return QiyuPhase.OpenBest to "已开启第 ${nextIndex + 1} 个宝箱，等待分数校验"
    }

    private data class OpeningPlan(val order: List<Int>, val finalScore: Int)

    private fun bestOpeningPlan(
        current: Int,
        candidates: List<BoxObservation>,
        maxOpens: Int,
    ): OpeningPlan {
        val length = minOf(maxOpens, candidates.size)
        if (length == 0) return OpeningPlan(emptyList(), current)

        var best = OpeningPlan(emptyList(), Int.MIN_VALUE)
        fun visit(score: Int, remaining: List<BoxObservation>, order: List<Int>) {
            if (order.size == length) {
                if (score > best.finalScore) {
                    best = OpeningPlan(order, score)
                }
                return
            }
            remaining.forEach { observation ->
                val nextScore = observation.rules.fold(score) { value, rule -> applyQiyuRule(value, rule) }
                visit(nextScore, remaining.filterNot { it.index == observation.index }, order + observation.index)
            }
        }
        visit(current, candidates, emptyList())
        return best
    }

    private fun inspectNextBox(
        root: AccessibilityNodeInfo,
        inspectButton: AccessibilityNodeInfo?,
    ): Pair<QiyuPhase, String?> {
        val boxIndex = qiyu.nextBoxIndex
        if (!selectBoxByIndex(root, boxIndex)) return QiyuPhase.Failed("无法识别并选中待查看宝箱") to null
        qiyu = qiyu.copy(
            pendingBoxIndex = boxIndex,
            nextBoxIndex = boxIndex + 1,
            lastDecision = "准备查看第 ${boxIndex + 1} 个宝箱",
        )
        return if (inspectButton != null && NodeQuery.clickNearestClickable(inspectButton)) {
            QiyuPhase.InspectBoxes to "已查看第 ${boxIndex + 1} 个宝箱"
        } else {
            QiyuPhase.Failed("无法点击查看宝箱") to null
        }
    }

    private fun openBestBox(
        root: AccessibilityNodeInfo,
        openButton: AccessibilityNodeInfo?,
    ): Pair<QiyuPhase, String?> {
        val current = qiyu.currentScore ?: parseCurrentScore(NodeQuery.collectTexts(root).joinToString(" "))
            ?: return QiyuPhase.Failed("无法读取当前分数") to null
        val scored = qiyu.observations.map { observation ->
            observation to observation.rules.fold(current) { score, rule -> applyQiyuRule(score, rule) }
        }
        val (best, bestScore) = scored.maxByOrNull { it.second }
            ?: return QiyuPhase.Failed("没有可用于计算的宝箱结果") to null

        if (!selectBoxByIndex(root, best.index)) return QiyuPhase.Failed("无法选中最优宝箱") to null
        qiyu = qiyu.copy(lastDecision = "当前分数 $current，选择第 ${best.index + 1} 个宝箱，预计结果 $bestScore")
        return if (openButton != null && NodeQuery.clickNearestClickable(openButton)) {
            QiyuPhase.OpenBest to "已按第 ${best.index + 1} 个宝箱作为当前最优方案开启"
        } else {
            QiyuPhase.Failed("无法点击开启宝箱") to null
        }
    }

    private fun selectBoxByIndex(root: AccessibilityNodeInfo, index: Int): Boolean {
        val boxes = findBoxClickTargets(root)
        val node = boxes.getOrNull(index) ?: return false
        return NodeQuery.clickNearestClickable(node)
    }

    private fun findBoxClickTargets(root: AccessibilityNodeInfo): List<AccessibilityNodeInfo> {
        return NodeQuery.findAllByText(root, "宝箱")
            .filterNot { node ->
                val text = NodeQuery.getNodeText(node).orEmpty()
                text.contains("查看宝箱") || text.contains("开启宝箱")
            }
            .mapNotNull { node -> NodeQuery.nearestClickableOrSelf(node) }
            .distinctBy { node -> NodeQuery.bounds(node)?.let { "${it.left},${it.top},${it.right},${it.bottom}" } }
            .sortedWith(compareBy<AccessibilityNodeInfo> { NodeQuery.bounds(it)?.top ?: Int.MAX_VALUE }
                .thenBy { NodeQuery.bounds(it)?.left ?: Int.MAX_VALUE })
    }

    private fun chooseTowerBuff(root: AccessibilityNodeInfo): Pair<TowerPhase, String?> {
        val allTexts = NodeQuery.collectTexts(root)
        val avoidedLabels = listOf("练骨", "炼骨")
        val avoidCenters = avoidedLabels
            .flatMap { label -> NodeQuery.findAllByText(root, label) }
            .mapNotNull { NodeQuery.bounds(it)?.centerX() }

        val valueNodes = NodeQuery.findAllByText(root, "%")
            .filter { node ->
                NodeQuery.getNodeText(node).orEmpty().matches(Regex("[+＋]\\s*\\d+%"))
            }
            .mapNotNull { node -> NodeQuery.bounds(node)?.let { bounds -> node to bounds } }
            .sortedBy { (_, bounds) -> bounds.left }
        val safeValueNodes = valueNodes.filterNot { (_, bounds) ->
            avoidCenters.any { center -> kotlin.math.abs(center - bounds.centerX()) < bounds.width() * 4 / 5 }
        }
        val selectButtons = NodeQuery.findAllByText(root, "选择")
            .mapNotNull { node -> NodeQuery.nearestClickableOrSelf(node) }
            .distinctBy { node -> NodeQuery.bounds(node)?.let { "${it.left},${it.top}" } }
            .sortedBy { NodeQuery.bounds(it)?.left ?: Int.MAX_VALUE }
        val bestValue = safeValueNodes.maxByOrNull { (node, _) ->
            extractNumber(NodeQuery.getNodeText(node).orEmpty()) ?: Int.MIN_VALUE
        }
        val bestIndex = bestValue?.let { valueNodes.indexOf(it) }
        val button = bestIndex?.let { index -> selectButtons.getOrNull(index) }
        val selectedText = bestValue?.first?.let { NodeQuery.getNodeText(it) }

        tower = tower.copy(
            candidates = allTexts.filter { it.matches(Regex("[+＋]\\s*\\d+%")) },
            selected = selectedText,
            avoided = avoidedLabels.filter { allTexts.any { text -> text.contains(it) } },
            lastDecision = when {
                selectedText != null -> "排除炼骨后按数值选择：$selectedText"
                else -> "没有可用的非炼骨加成"
            },
        )

        return if (button != null && NodeQuery.clickNearestClickable(button)) {
            TowerPhase.ResolveBranch to (tower.lastDecision ?: "已选择安全加成")
        } else {
            TowerPhase.Failed("无法识别可点击的非炼骨加成选择按钮") to null
        }
    }

    private fun parseCurrentScore(pageText: String): Int? {
        return Regex("当前分数\\D*(\\d+)").find(pageText)?.groupValues?.getOrNull(1)?.toIntOrNull()
    }

    private fun remainingCountAfter(pageText: String, label: String): Int {
        val labelIndex = pageText.indexOf(label)
        if (labelIndex < 0) return -1
        val tail = pageText.substring(labelIndex).take(40)
        return Regex("剩余次数\\D*(\\d+)").find(tail)?.groupValues?.getOrNull(1)?.toIntOrNull()
            ?: Regex("(\\d+)\\s*/\\s*\\d+").find(tail)?.groupValues?.getOrNull(1)?.toIntOrNull()
            ?: 0
    }

    private fun parseRules(pageText: String): List<String> {
        val keywords = listOf("偶数", "替换", "首位", "互换", "倒序", "奇数", "当前数", "+", "*", "加")
        return pageText
            .split(" ", "，", "。", "\n")
            .map { it.trim() }
            .filter { text -> text.length >= 2 && keywords.any { text.contains(it) } }
    }

    private fun applyQiyuRule(current: Int, rule: String): Int {
        return when {
            rule.contains("偶数") && rule.contains("*") -> {
                if (current % 2 == 0) multiplyScore(current, extractDecimal(rule)) else current
            }
            rule.contains("偶数") && (rule.contains("+") || rule.contains("加")) -> {
                val delta = extractNumber(rule) ?: return current
                if (current % 2 == 0) current + delta else current
            }
            rule.contains("奇数") && rule.contains("*") -> {
                if (current % 2 != 0) multiplyScore(current, extractDecimal(rule)) else current
            }
            rule.contains("奇数") && (rule.contains("+") || rule.contains("加")) -> {
                val delta = extractNumber(rule) ?: return current
                if (current % 2 != 0) current + delta else current
            }
            rule.contains("1") && rule.contains("9") && rule.contains("替换") -> {
                current.toString().replace('1', '9').toIntOrNull() ?: current
            }
            rule.matches(Regex("被\\d+替换")) -> extractNumber(rule) ?: current
            rule.contains("首位") && (rule.contains("+") || rule.contains("加")) -> {
                addFirstDigit(current, extractNumber(rule) ?: return current)
            }
            rule.contains("首位") && rule.contains("互换") -> swapFirstAndLast(current)
            rule.contains("倒序") -> current.toString().reversed().toIntOrNull() ?: current
            rule.contains("*") -> multiplyScore(current, extractDecimal(rule))
            rule.contains("+") || rule.contains("加") -> current + (extractNumber(rule) ?: return current)
            else -> current
        }
    }

    private fun multiplyScore(current: Int, multiplier: BigDecimal?): Int {
        if (multiplier == null) return current
        val value = BigDecimal.valueOf(current.toLong())
            .multiply(multiplier)
            .setScale(0, RoundingMode.DOWN)
        return when {
            value > BigDecimal.valueOf(Int.MAX_VALUE.toLong()) -> Int.MAX_VALUE
            value < BigDecimal.ZERO -> 0
            else -> value.toInt()
        }
    }

    private fun addFirstDigit(value: Int, delta: Int): Int {
        val digitCount = value.toString().length
        val placeValue = generateSequence(1L) { it * 10L }
            .take(digitCount)
            .last()
        return (value.toLong() + delta.toLong() * placeValue)
            .coerceIn(0L, Int.MAX_VALUE.toLong())
            .toInt()
    }

    private fun swapFirstAndLast(value: Int): Int {
        val digits = value.toString().toMutableList()
        if (digits.size < 2) return value
        val first = digits.first()
        digits[0] = digits.last()
        digits[digits.lastIndex] = first
        return digits.joinToString("").toIntOrNull() ?: value
    }

    private fun extractDecimal(text: String): BigDecimal? {
        return Regex("\\d+(?:\\.\\d+)?")
            .findAll(text)
            .lastOrNull()
            ?.value
            ?.toBigDecimalOrNull()
    }

    private fun extractNumber(text: String): Int? {
        return Regex("\\d+").findAll(text).lastOrNull()?.value?.toIntOrNull()
    }
}
