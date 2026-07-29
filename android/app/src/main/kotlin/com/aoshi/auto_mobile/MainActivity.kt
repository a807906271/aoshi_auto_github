package com.aoshi.auto_mobile

import android.content.Context
import android.content.Intent
import android.provider.Settings
import android.text.TextUtils
import io.flutter.embedding.android.FlutterActivity
import io.flutter.embedding.engine.FlutterEngine
import io.flutter.plugin.common.MethodChannel
import com.aoshi.auto_mobile.automation.AoshiAccessibilityService
import org.json.JSONObject

class MainActivity : FlutterActivity() {

    companion object {
        private const val CHANNEL = "com.aoshi.auto_mobile/automation"
    }

    override fun configureFlutterEngine(flutterEngine: FlutterEngine) {
        super.configureFlutterEngine(flutterEngine)

        MethodChannel(flutterEngine.dartExecutor.binaryMessenger, CHANNEL).setMethodCallHandler { call, result ->
            when (call.method) {
                // 检查无障碍服务是否已启用
                "isAccessibilityEnabled" -> {
                    val enabled = isAccessibilityServiceEnabled()
                    result.success(enabled)
                }

                // 打开无障碍设置页
                "openAccessibilitySettings" -> {
                    val intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
                    startActivity(intent)
                    result.success(true)
                }

                // 启动奇遇流程
                "startQiyu" -> {
                    val service = AoshiAccessibilityService.instance
                    if (service == null) {
                        result.error("SERVICE_NOT_ENABLED", "无障碍服务未启用", null)
                    } else {
                        val response = service.startQiyu()
                        result.success(response.toString())
                    }
                }

                // 启动闯塔流程
                "startTower" -> {
                    val service = AoshiAccessibilityService.instance
                    if (service == null) {
                        result.error("SERVICE_NOT_ENABLED", "无障碍服务未启用", null)
                    } else {
                        val response = service.startTower()
                        result.success(response.toString())
                    }
                }

                // 停止流程
                "stopFlow" -> {
                    val service = AoshiAccessibilityService.instance
                    if (service == null) {
                        result.success(JSONObject().apply {
                            put("success", true)
                            put("message", "无活动服务")
                        }.toString())
                    } else {
                        val response = service.stopFlow()
                        result.success(response.toString())
                    }
                }

                // 获取状态
                "getStatus" -> {
                    val service = AoshiAccessibilityService.instance
                    if (service == null) {
                        result.success(JSONObject().apply {
                            put("isRunning", false)
                            put("currentFlow", "none")
                            put("qiyuPhase", "Idle")
                            put("towerPhase", "Idle")
                        }.toString())
                    } else {
                        result.success(service.getStatus().toString())
                    }
                }

                else -> result.notImplemented()
            }
        }
    }

    /**
     * 检查无障碍服务是否已启用
     */
    private fun isAccessibilityServiceEnabled(): Boolean {
        val expectedComponentName = "${packageName}/${AoshiAccessibilityService::class.java.canonicalName}"
        var accessibilityEnabled = 0

        try {
            accessibilityEnabled = Settings.Secure.getInt(
                contentResolver,
                Settings.Secure.ACCESSIBILITY_ENABLED
            )
        } catch (e: Settings.SettingNotFoundException) {
            return false
        }

        if (accessibilityEnabled == 1) {
            val settingValue = Settings.Secure.getString(
                contentResolver,
                Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
            ) ?: return false

            val splitter = TextUtils.SimpleStringSplitter(':')
            splitter.setString(settingValue)
            
            while (splitter.hasNext()) {
                val componentName = splitter.next()
                if (expectedComponentName.equals(componentName, ignoreCase = true)) {
                    return true
                }
            }
        }

        return false
    }
}
