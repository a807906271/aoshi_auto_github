import 'automation_channel.dart';

/// 游戏流程定义
class GameFlow {
  final String id;
  final String name;
  final String description;
  final String icon;
  final FlowType type;

  const GameFlow({
    required this.id,
    required this.name,
    required this.description,
    required this.icon,
    required this.type,
  });
}

/// 预定义游戏流程
class GameFlows {
  static const qiyu = GameFlow(
    id: 'qiyu',
    name: '奇遇自动化',
    description: '自动查看宝箱、计算最优选择、开启最佳宝箱',
    icon: '🎁',
    type: FlowType.qiyu,
  );

  static const tower = GameFlow(
    id: 'tower',
    name: '日常闯塔',
    description: '自动选择加成（排除炼骨）、跳过闯关、领取奖励',
    icon: '🗼',
    type: FlowType.tower,
  );

  static const List<GameFlow> all = [qiyu, tower];
}

/// 自动化会话管理
class AutomationSession {
  final AutomationChannel _channel;
  
  AutomationState _state = AutomationState.idle;
  String? _currentFlowId;
  String? _lastMessage;
  String? _lastError;

  AutomationState get state => _state;
  String? get currentFlowId => _currentFlowId;
  String? get lastMessage => _lastMessage;
  String? get lastError => _lastError;
  
  bool get isRunning => _state == AutomationState.running;
  bool get canStart => _state == AutomationState.idle || _state == AutomationState.completed || _state == AutomationState.failed;

  AutomationSession({AutomationChannel? channel})
      : _channel = channel ?? AutomationChannel();

  /// 检查无障碍授权
  Future<bool> checkAccessibility() async {
    return await _channel.isAccessibilityEnabled();
  }

  /// 打开无障碍设置
  Future<void> openAccessibilitySettings() async {
    await _channel.openAccessibilitySettings();
  }

  /// 启动指定流程
  Future<bool> start(GameFlow flow) async {
    if (!canStart) {
      _lastError = '当前有流程正在运行';
      return false;
    }

    final enabled = await checkAccessibility();
    if (!enabled) {
      _lastError = '请先开启无障碍服务';
      return false;
    }

    _state = AutomationState.running;
    _currentFlowId = flow.id;
    _lastError = null;

    final result = await switch (flow.type) {
      FlowType.qiyu => _channel.startQiyu(),
      FlowType.tower => _channel.startTower(),
    };

    if (result['success'] == true) {
      _lastMessage = result['message'] as String?;
      return true;
    } else {
      _state = AutomationState.failed;
      _lastError = result['message'] as String?;
      return false;
    }
  }

  /// 停止当前流程
  Future<void> stop() async {
    await _channel.stopFlow();
    _state = AutomationState.idle;
    _currentFlowId = null;
  }

  /// 刷新状态
  Future<void> refresh() async {
    final status = await _channel.getStatus();
    _state = status.state;
    if (status.state == AutomationState.failed) {
      _lastError = status.error ?? status.phase;
    }
  }
}
