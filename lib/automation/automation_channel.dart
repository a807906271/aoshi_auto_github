import 'dart:convert';
import 'package:flutter/services.dart';

/// 自动化流程类型
enum FlowType {
  qiyu,
  tower,
}

/// 自动化状态
enum AutomationState {
  idle,
  running,
  paused,
  completed,
  failed,
}

/// 流程状态数据
class FlowStatus {
  final AutomationState state;
  final String? currentFlow;
  final String? phase;
  final String? message;
  final String? error;

  const FlowStatus({
    required this.state,
    this.currentFlow,
    this.phase,
    this.message,
    this.error,
  });

  factory FlowStatus.fromMap(Map<String, dynamic> map) {
    return FlowStatus(
      state: _parseState(map['isRunning'] as bool? ?? false, map['currentFlow'] as String?),
      currentFlow: map['currentFlow'] as String?,
      phase: map['qiyuPhase'] as String? ?? map['towerPhase'] as String?,
      message: null,
      error: null,
    );
  }

  static AutomationState _parseState(bool isRunning, String? flow) {
    if (flow == null || flow == 'none') return AutomationState.idle;
    if (isRunning) return AutomationState.running;
    return AutomationState.paused;
  }
}

/// MethodChannel 通信封装
class AutomationChannel {
  static const _channel = MethodChannel('com.aoshi.auto_mobile/automation');

  /// 检查无障碍服务是否已启用
  Future<bool> isAccessibilityEnabled() async {
    try {
      final result = await _channel.invokeMethod<bool>('isAccessibilityEnabled');
      return result ?? false;
    } on PlatformException catch (e) {
      print('检查无障碍服务失败: ${e.message}');
      return false;
    }
  }

  /// 打开无障碍设置页
  Future<bool> openAccessibilitySettings() async {
    try {
      final result = await _channel.invokeMethod<bool>('openAccessibilitySettings');
      return result ?? false;
    } on PlatformException catch (e) {
      print('打开无障碍设置失败: ${e.message}');
      return false;
    }
  }

  /// 启动奇遇流程
  Future<Map<String, dynamic>> startQiyu() async {
    try {
      final result = await _channel.invokeMethod<String>('startQiyu');
      return _parseResult(result);
    } on PlatformException catch (e) {
      return {'success': false, 'message': e.message ?? '启动失败'};
    }
  }

  /// 启动闯塔流程
  Future<Map<String, dynamic>> startTower() async {
    try {
      final result = await _channel.invokeMethod<String>('startTower');
      return _parseResult(result);
    } on PlatformException catch (e) {
      return {'success': false, 'message': e.message ?? '启动失败'};
    }
  }

  /// 停止流程
  Future<Map<String, dynamic>> stopFlow() async {
    try {
      final result = await _channel.invokeMethod<String>('stopFlow');
      return _parseResult(result);
    } on PlatformException catch (e) {
      return {'success': false, 'message': e.message ?? '停止失败'};
    }
  }

  /// 获取当前状态
  Future<FlowStatus> getStatus() async {
    try {
      final result = await _channel.invokeMethod<String>('getStatus');
      if (result == null) return const FlowStatus(state: AutomationState.idle);
      final map = jsonDecode(result) as Map<String, dynamic>;
      return FlowStatus.fromMap(map);
    } on PlatformException catch (e) {
      print('获取状态失败: ${e.message}');
      return const FlowStatus(state: AutomationState.idle);
    }
  }

  Map<String, dynamic> _parseResult(String? result) {
    if (result == null) return {'success': false, 'message': '无响应'};
    try {
      return jsonDecode(result) as Map<String, dynamic>;
    } catch (e) {
      return {'success': false, 'message': '解析失败: $result'};
    }
  }
}
