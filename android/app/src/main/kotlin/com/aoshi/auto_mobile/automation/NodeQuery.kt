package com.aoshi.auto_mobile.automation

import android.view.accessibility.AccessibilityNodeInfo

/**
 * 节点查询 DSL - 基于无障碍文本查找节点
 */
object NodeQuery {

    /**
     * 按精确文本查找节点
     */
    fun findText(root: AccessibilityNodeInfo?, text: String): AccessibilityNodeInfo? {
        if (root == null) return null
        
        val nodes = mutableListOf<AccessibilityNodeInfo>()
        findNodesByTextRecursive(root, text, nodes)
        return nodes.firstOrNull()
    }

    /**
     * 按多个文本任一匹配查找
     */
    fun findAnyText(root: AccessibilityNodeInfo?, texts: List<String>): AccessibilityNodeInfo? {
        if (root == null) return null
        
        for (text in texts) {
            val node = findText(root, text)
            if (node != null) return node
        }
        return null
    }

    /**
     * 按内容描述查找
     */
    fun findByContentDesc(root: AccessibilityNodeInfo?, desc: String): AccessibilityNodeInfo? {
        if (root == null) return null
        
        val nodes = root.findAccessibilityNodeInfosByText(desc)
        return nodes.firstOrNull { 
            it.contentDescription?.toString()?.contains(desc, ignoreCase = true) == true 
        }
    }

    /**
     * 查找包含指定文本的节点（模糊匹配）
     */
    fun findContainsText(root: AccessibilityNodeInfo?, text: String): AccessibilityNodeInfo? {
        if (root == null) return null
        
        val nodes = root.findAccessibilityNodeInfosByText(text)
        return nodes.firstOrNull {
            it.text?.toString()?.contains(text, ignoreCase = true) == true ||
            it.contentDescription?.toString()?.contains(text, ignoreCase = true) == true
        }
    }

    /**
     * 向上查找最近的可点击父节点并执行点击
     */
    fun clickNearestClickable(node: AccessibilityNodeInfo?): Boolean {
        if (node == null) return false
        
        var current: AccessibilityNodeInfo? = node
        while (current != null) {
            if (current.isClickable) {
                return current.performAction(AccessibilityNodeInfo.ACTION_CLICK)
            }
            current = current.parent
        }
        return false
    }

    /**
     * 直接点击节点（如果可点击）
     */
    fun click(node: AccessibilityNodeInfo?): Boolean {
        if (node == null || !node.isClickable) return false
        return node.performAction(AccessibilityNodeInfo.ACTION_CLICK)
    }

    /**
     * 获取节点文本内容
     */
    fun getNodeText(node: AccessibilityNodeInfo?): String? {
        if (node == null) return null
        return node.text?.toString() ?: node.contentDescription?.toString()
    }

    /**
     * 递归查找包含指定文本的所有节点
     */
    fun findAllByText(root: AccessibilityNodeInfo?, text: String): List<AccessibilityNodeInfo> {
        if (root == null) return emptyList()
        
        val nodes = mutableListOf<AccessibilityNodeInfo>()
        findNodesByTextRecursive(root, text, nodes)
        return nodes
    }

    /**
     * 查找所有可点击的节点
     */
    fun findAllClickable(root: AccessibilityNodeInfo?): List<AccessibilityNodeInfo> {
        if (root == null) return emptyList()
        
        val nodes = mutableListOf<AccessibilityNodeInfo>()
        findClickableNodesRecursive(root, nodes)
        return nodes
    }

    // ===== 私有递归方法 =====

    private fun findNodesByTextRecursive(
        node: AccessibilityNodeInfo, 
        text: String, 
        result: MutableList<AccessibilityNodeInfo>
    ) {
        val nodeText = node.text?.toString()
        val nodeDesc = node.contentDescription?.toString()
        
        if (nodeText?.contains(text, ignoreCase = true) == true ||
            nodeDesc?.contains(text, ignoreCase = true) == true) {
            result.add(node)
        }
        
        for (i in 0 until node.childCount) {
            val child = node.getChild(i)
            if (child != null) {
                findNodesByTextRecursive(child, text, result)
            }
        }
    }

    private fun findClickableNodesRecursive(
        node: AccessibilityNodeInfo, 
        result: MutableList<AccessibilityNodeInfo>
    ) {
        if (node.isClickable) {
            result.add(node)
        }
        
        for (i in 0 until node.childCount) {
            val child = node.getChild(i)
            if (child != null) {
                findClickableNodesRecursive(child, result)
            }
        }
    }
}
