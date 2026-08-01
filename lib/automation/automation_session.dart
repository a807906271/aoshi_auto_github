export 'automation_channel.dart';

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
  FlowStatus? _lastStatus;
  List<WorkflowFlowSpec> _workflowSpec = _fallbackWorkflowSpec;

  static final List<WorkflowFlowSpec> _fallbackWorkflowSpec = [
    WorkflowFlowSpec(
      id: GameFlows.qiyu.id,
      name: GameFlows.qiyu.name,
      description: GameFlows.qiyu.description,
      icon: GameFlows.qiyu.icon,
      phases: const [
        WorkflowPhaseSpec(id: 'WaitStart', label: '等待奇遇入口', hint: '等待出现开启奇遇按钮'),
        WorkflowPhaseSpec(id: 'EnterDivination', label: '进入算卦页', hint: '点击开启奇遇后进入算卦'),
        WorkflowPhaseSpec(id: 'SelectBox', label: '选择待查看宝箱', hint: '选中一个宝箱并准备查看'),
        WorkflowPhaseSpec(id: 'InspectBoxes', label: '逐个记录宝箱规则', hint: '读取每个宝箱的加成规则'),
        WorkflowPhaseSpec(id: 'OpenBest', label: '选择最优宝箱', hint: '按当前分数计算最优方案'),
        WorkflowPhaseSpec(id: 'FinishRound', label: '结束本轮', hint: '完成选择后等待结算'),
        WorkflowPhaseSpec(id: 'ConfirmReward', label: '确认领奖', hint: '点击确定后返回天赋奇遇入口，开始下一轮'),
      ],
    ),
    WorkflowFlowSpec(
      id: GameFlows.tower.id,
      name: GameFlows.tower.name,
      description: GameFlows.tower.description,
      icon: GameFlows.tower.icon,
      phases: const [
        WorkflowPhaseSpec(id: 'ResolveBranch', label: '进入闯塔战斗', hint: '在普通闯塔或精英闯塔页面点击怒闯'),
        WorkflowPhaseSpec(id: 'ChooseBuff', label: '选择加成', hint: '选择最高收益加成，并排除炼骨/练骨'),
        WorkflowPhaseSpec(id: 'RevealSkip', label: '呼出跳过', hint: '通过怒闯或容器点击露出跳过按钮'),
        WorkflowPhaseSpec(id: 'SkipBattle', label: '跳过战斗', hint: '点击跳过开始结算'),
        WorkflowPhaseSpec(id: 'ConfirmSkip', label: '确认结果', hint: '点击确定确认跳过结果'),
        WorkflowPhaseSpec(id: 'Done', label: '结束闯塔', hint: '检测到结束闯塔或已进入完成态'),
      ],
    ),
  ];

  AutomationState get state => _state;
  String? get currentFlowId => _currentFlowId;
  String? get lastMessage => _lastMessage;
  String? get lastError => _lastError;
  FlowStatus? get lastStatus => _lastStatus;
  List<WorkflowFlowSpec> get workflowSpec => _workflowSpec;
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

    _lastStatus = FlowStatus.fromMap(result);
    if (result['success'] == true) {
      _lastMessage = result['message'] as String?;
      _state = AutomationState.running;
      return true;
    } else {
      _state = AutomationState.failed;
      _lastError = result['message'] as String?;
      return false;
    }
  }

  /// 停止当前流程
  Future<void> stop() async {
    final result = await _channel.stopFlow();
    _lastStatus = FlowStatus.fromMap(result);
    _state = AutomationState.idle;
    _currentFlowId = null;
    _lastMessage = result['message'] as String?;
    _lastError = null;
  }

  /// 刷新流程目录与状态
  Future<void> refresh() async {
    final spec = await _channel.getWorkflowSpec();
    if (spec.isNotEmpty) {
      _workflowSpec = spec;
    }

    final status = await _channel.getStatus();
    _lastStatus = status;
    _state = status.state;
    _currentFlowId = status.visibleFlow;
    _lastMessage = status.message;
    _lastError = status.error;
    if (status.state == AutomationState.failed) {
      _lastError = status.error ?? status.activePhase;
    }
  }
}
