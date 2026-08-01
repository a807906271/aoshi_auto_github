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

/// Android 侧固化的流程阶段定义
class WorkflowPhaseSpec {
  final String id;
  final String label;
  final String hint;

  const WorkflowPhaseSpec({
    required this.id,
    required this.label,
    required this.hint,
  });

  factory WorkflowPhaseSpec.fromMap(Map<String, dynamic> map) {
    return WorkflowPhaseSpec(
      id: map['id'] as String? ?? '',
      label: map['label'] as String? ?? '',
      hint: map['hint'] as String? ?? '',
    );
  }
}

/// Android 侧固化的流程目录定义
class WorkflowFlowSpec {
  final String id;
  final String name;
  final String description;
  final String icon;
  final List<WorkflowPhaseSpec> phases;

  const WorkflowFlowSpec({
    required this.id,
    required this.name,
    required this.description,
    required this.icon,
    required this.phases,
  });

  factory WorkflowFlowSpec.fromMap(Map<String, dynamic> map) {
    final phases = _asList(map['phases']);
    return WorkflowFlowSpec(
      id: map['id'] as String? ?? '',
      name: map['name'] as String? ?? '',
      description: map['description'] as String? ?? '',
      icon: map['icon'] as String? ?? '',
      phases: phases
          .map(_asStringMap)
          .whereType<Map<String, dynamic>>()
          .map(WorkflowPhaseSpec.fromMap)
          .where((phase) => phase.id.isNotEmpty)
          .toList(growable: false),
    );
  }
}

List<dynamic> _asList(Object? value) {
  return value is List ? value : const [];
}

Map<String, dynamic>? _asStringMap(Object? value) {
  if (value is Map<String, dynamic>) return value;
  if (value is Map) return value.map((key, value) => MapEntry(key.toString(), value));
  return null;
}

/// 流程状态数据
class FlowStatus {
  final AutomationState state;
  final String? currentFlow;
  final String? lastFlow;
  final String? activePhase;
  final String? qiyuPhase;
  final String? towerPhase;
  final String? message;
  final String? error;
  final int stepCount;
  final int skippedStepCount;
  final String? lastEventType;
  final String? pageLabel;
  final String? pageTextSample;
  final String? pageSignature;
  final String? throttleReason;
  final int elapsedMillis;
  final Map<String, dynamic> workflowRuntime;

  const FlowStatus({
    required this.state,
    this.currentFlow,
    this.lastFlow,
    this.activePhase,
    this.qiyuPhase,
    this.towerPhase,
    this.message,
    this.error,
    this.stepCount = 0,
    this.skippedStepCount = 0,
    this.lastEventType,
    this.pageLabel,
    this.pageTextSample,
    this.pageSignature,
    this.throttleReason,
    this.elapsedMillis = 0,
    this.workflowRuntime = const {},
  });

  factory FlowStatus.fromMap(Map<String, dynamic> map) {
    final currentFlow = map['currentFlow'] as String?;
    return FlowStatus(
      state: _parseState(
        map['status'] as String?,
        map['isRunning'] as bool? ?? false,
        currentFlow,
      ),
      currentFlow: currentFlow,
      lastFlow: _nullableString(map['lastFlow']),
      activePhase: map['activePhase'] as String?,
      qiyuPhase: map['qiyuPhase'] as String?,
      towerPhase: map['towerPhase'] as String?,
      message: map['lastMessage'] as String?,
      error: _nullableString(map['lastError']),
      stepCount: map['stepCount'] as int? ?? 0,
      skippedStepCount: map['skippedStepCount'] as int? ?? 0,
      lastEventType: _nullableString(map['lastEventType']),
      pageLabel: _nullableString(map['lastPageLabel']),
      pageTextSample: _nullableString(map['lastPageTextSample']),
      pageSignature: _nullableString(map['lastPageSignature']),
      throttleReason: _nullableString(map['lastThrottleReason']),
      elapsedMillis: map['lastElapsedMillis'] as int? ?? 0,
      workflowRuntime: _asStringMap(map['workflowRuntime']) ?? const {},
    );
  }

  String? get visibleFlow {
    final flow = currentFlow == 'none' ? null : currentFlow;
    return flow ?? lastFlow;
  }

  static AutomationState _parseState(String? status, bool isRunning, String? flow) {
    return switch (status) {
      'running' => AutomationState.running,
      'completed' => AutomationState.completed,
      'failed' => AutomationState.failed,
      'idle' => AutomationState.idle,
      _ when isRunning => AutomationState.running,
      _ when flow == null || flow == 'none' => AutomationState.idle,
      _ => AutomationState.paused,
    };
  }

  static String? _nullableString(Object? value) {
    if (value == null) return null;
    final text = value.toString();
    return text == 'null' ? null : text;
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

  /// 获取 Android 侧固化的流程目录
  Future<List<WorkflowFlowSpec>> getWorkflowSpec() async {
    try {
      final result = await _channel.invokeMethod<String>('getWorkflowSpec');
      if (result == null) return const [];
      final list = jsonDecode(result) as List<dynamic>;
      return list
          .map(_asStringMap)
          .whereType<Map<String, dynamic>>()
          .map(WorkflowFlowSpec.fromMap)
          .where((flow) => flow.id.isNotEmpty)
          .toList(growable: false);
    } on PlatformException catch (e) {
      print('获取流程目录失败: ${e.message}');
      return const [];
    } catch (e) {
      print('解析流程目录失败: $e');
      return const [];
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
