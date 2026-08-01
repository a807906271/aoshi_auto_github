package com.aoshi.auto_mobile.automation

import android.graphics.Rect
import android.view.accessibility.AccessibilityNodeInfo

/**
 * 无障碍节点查询与受控点击 DSL。
 *
 * 约束：
 * - 优先按文本/描述找到可点击节点。
 * - 相对坐标点击必须建立在页面锚点文本已识别的前提下，由状态机控制调用。
 */
object NodeQuery {

    fun findText(root: AccessibilityNodeInfo?, text: String): AccessibilityNodeInfo? {
        if (root == null) return null
        val nodes = mutableListOf<AccessibilityNodeInfo>()
        findNodesByTextRecursive(root, text, nodes)
        return nodes.firstOrNull()
    }

    fun findAnyText(root: AccessibilityNodeInfo?, texts: List<String>): AccessibilityNodeInfo? {
        if (root == null) return null
        for (text in texts) {
            val node = findText(root, text)
            if (node != null) return node
        }
        return null
    }

    fun findContainsText(root: AccessibilityNodeInfo?, text: String): AccessibilityNodeInfo? {
        if (root == null) return null
        val nodes = mutableListOf<AccessibilityNodeInfo>()
        findNodesByTextRecursive(root, text, nodes)
        return nodes.firstOrNull()
    }

    fun findAllByText(root: AccessibilityNodeInfo?, text: String): List<AccessibilityNodeInfo> {
        if (root == null) return emptyList()
        val nodes = mutableListOf<AccessibilityNodeInfo>()
        findNodesByTextRecursive(root, text, nodes)
        return nodes
    }

    fun findAllClickable(root: AccessibilityNodeInfo?): List<AccessibilityNodeInfo> {
        if (root == null) return emptyList()
        val nodes = mutableListOf<AccessibilityNodeInfo>()
        findClickableNodesRecursive(root, nodes)
        return nodes
    }

    fun collectTexts(root: AccessibilityNodeInfo?): List<String> {
        if (root == null) return emptyList()
        val result = mutableListOf<String>()
        collectTextsRecursive(root, result)
        return result.distinct()
    }

    fun pageHasAny(root: AccessibilityNodeInfo?, texts: List<String>): Boolean {
        val allText = collectTexts(root).joinToString(" ")
        return texts.any { allText.contains(it, ignoreCase = true) }
    }

    fun findButton(root: AccessibilityNodeInfo?, labels: List<String>): AccessibilityNodeInfo? {
        return findAnyText(root, labels)?.let { nearestClickable(it) ?: it }
    }

    fun clickNearestClickable(node: AccessibilityNodeInfo?): Boolean {
        return nearestClickable(node)?.performAction(AccessibilityNodeInfo.ACTION_CLICK) ?: false
    }

    fun nearestClickableOrSelf(node: AccessibilityNodeInfo?): AccessibilityNodeInfo? {
        if (node == null) return null
        return nearestClickable(node) ?: node.takeIf { it.isClickable && it.isEnabled }
    }

    fun clickRootOrLargestClickable(root: AccessibilityNodeInfo?): Boolean {
        val node = findAllClickable(root)
            .maxByOrNull { bounds(it)?.let { rect -> rect.width() * rect.height() } ?: 0 }
        return node?.performAction(AccessibilityNodeInfo.ACTION_CLICK) ?: false
    }

    fun click(node: AccessibilityNodeInfo?): Boolean {
        if (node == null || !node.isClickable) return false
        return node.performAction(AccessibilityNodeInfo.ACTION_CLICK)
    }

    fun getNodeText(node: AccessibilityNodeInfo?): String? {
        if (node == null) return null
        return node.text?.toString() ?: node.contentDescription?.toString()
    }

    fun bounds(node: AccessibilityNodeInfo?): Rect? {
        if (node == null) return null
        val rect = Rect()
        node.getBoundsInScreen(rect)
        return rect.takeIf { !it.isEmpty }
    }

    private fun nearestClickable(node: AccessibilityNodeInfo?): AccessibilityNodeInfo? {
        var current = node
        while (current != null) {
            if (current.isClickable && current.isEnabled) return current
            current = current.parent
        }
        return null
    }

    private fun findNodesByTextRecursive(
        node: AccessibilityNodeInfo,
        text: String,
        result: MutableList<AccessibilityNodeInfo>,
    ) {
        val nodeText = node.text?.toString()
        val nodeDesc = node.contentDescription?.toString()
        if (nodeText?.contains(text, ignoreCase = true) == true ||
            nodeDesc?.contains(text, ignoreCase = true) == true
        ) {
            result.add(node)
        }

        for (i in 0 until node.childCount) {
            node.getChild(i)?.let { findNodesByTextRecursive(it, text, result) }
        }
    }

    private fun findClickableNodesRecursive(
        node: AccessibilityNodeInfo,
        result: MutableList<AccessibilityNodeInfo>,
    ) {
        if (node.isClickable && node.isEnabled) result.add(node)
        for (i in 0 until node.childCount) {
            node.getChild(i)?.let { findClickableNodesRecursive(it, result) }
        }
    }

    private fun collectTextsRecursive(node: AccessibilityNodeInfo, result: MutableList<String>) {
        node.text?.toString()?.takeIf { it.isNotBlank() }?.let(result::add)
        node.contentDescription?.toString()?.takeIf { it.isNotBlank() }?.let(result::add)
        for (i in 0 until node.childCount) {
            node.getChild(i)?.let { collectTextsRecursive(it, result) }
        }
    }
}
