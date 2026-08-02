import 'dart:async';

import 'package:flutter/material.dart';
import '../../automation/automation_session.dart';

class WorkflowPage extends StatefulWidget {
  const WorkflowPage({super.key});

  @override
  State<WorkflowPage> createState() => _WorkflowPageState();
}

class _WorkflowPageState extends State<WorkflowPage> {
  final AutomationSession _session = AutomationSession();
  FlowStatus? _snapshot;
  Timer? _pollingTimer;
  bool _loading = true;
  bool _busy = false;
  bool _refreshing = false;
  String? _pendingFlowId;

  @override
  void initState() {
    super.initState();
    _refresh();
  }

  Future<void> _refresh({bool showLoading = true}) async {
    if (_refreshing) return;
    _refreshing = true;
    if (showLoading) {
      setState(() {
        _loading = true;
      });
    }
    try {
      await _session.refresh();
      if (!mounted) return;
      setState(() {
        _snapshot = _session.lastStatus;
        _loading = false;
      });
      _syncPolling();
    } finally {
      _refreshing = false;
    }
  }

  void _syncPolling() {
    final shouldPoll = _snapshot?.state == AutomationState.running;
    if (!shouldPoll) {
      _pollingTimer?.cancel();
      _pollingTimer = null;
      return;
    }

    _pollingTimer ??= Timer.periodic(
      const Duration(milliseconds: 900),
      (_) => _refresh(showLoading: false),
    );
  }

  @override
  void dispose() {
    _pollingTimer?.cancel();
    super.dispose();
  }

  Future<void> _startFlow(WorkflowFlowSpec flow) async {
    final gameFlow = _toGameFlow(flow);
    if (gameFlow == null) {
      _showMessage('暂不支持该流程：${flow.name}', isError: true);
      return;
    }

    setState(() {
      _busy = true;
      _pendingFlowId = flow.id;
    });

    final enabled = await _session.checkAccessibility();
    if (!enabled) {
      setState(() {
        _busy = false;
        _pendingFlowId = null;
      });
      _showAccessibilityDialog();
      return;
    }

    final success = await _session.start(gameFlow);
    await _session.refresh();
    if (!mounted) return;

    setState(() {
      _snapshot = _session.lastStatus;
      _busy = false;
      _pendingFlowId = null;
      _loading = false;
    });
    _syncPolling();

    if (success) {
      _showMessage('${flow.name} 已启动');
    } else {
      _showMessage(_session.lastError ?? '启动失败', isError: true);
    }
  }

  Future<void> _stopFlow() async {
    setState(() {
      _busy = true;
    });

    await _session.stop();
    await _session.refresh();
    if (!mounted) return;

    setState(() {
      _snapshot = _session.lastStatus;
      _busy = false;
      _pendingFlowId = null;
      _loading = false;
    });
    _syncPolling();

    _showMessage('流程已停止');
  }

  GameFlow? _toGameFlow(WorkflowFlowSpec flow) {
    return switch (flow.id) {
      'qiyu' => GameFlows.qiyu,
      'tower' => GameFlows.tower,
      _ => null,
    };
  }

  void _showMessage(String message, {bool isError = false}) {
    if (!mounted) return;
    ScaffoldMessenger.of(context).showSnackBar(
      SnackBar(
        content: Text(message),
        backgroundColor: isError ? Colors.red : null,
      ),
    );
  }

  void _showAccessibilityDialog() {
    showDialog(
      context: context,
      builder: (context) => AlertDialog(
        title: const Text('需要无障碍授权'),
        content: const Text('请先在设置页开启无障碍服务，再回到工作流页启动自动化。'),
        actions: [
          TextButton(
            onPressed: () => Navigator.pop(context),
            child: const Text('知道了'),
          ),
          ElevatedButton(
            onPressed: () {
              Navigator.pop(context);
              DefaultTabController.of(context).animateTo(3);
            },
            child: const Text('去设置'),
          ),
        ],
      ),
    );
  }

  String _stateLabel(AutomationState state) {
    return switch (state) {
      AutomationState.idle => '空闲',
      AutomationState.running => '运行中',
      AutomationState.paused => '暂停',
      AutomationState.completed => '已完成',
      AutomationState.failed => '失败',
    };
  }

  Color _stateColor(AutomationState state) {
    return switch (state) {
      AutomationState.idle => Colors.grey,
      AutomationState.running => Colors.blue,
      AutomationState.paused => Colors.orange,
      AutomationState.completed => Colors.green,
      AutomationState.failed => Colors.red,
    };
  }

  Widget _buildFlowCard(WorkflowFlowSpec flow) {
    final activeFlow = _snapshot?.visibleFlow;
    final isActive = activeFlow == flow.id;
    final phase = flow.id == 'qiyu' ? _snapshot?.qiyuPhase : _snapshot?.towerPhase;
    final state = isActive ? (_snapshot?.state ?? AutomationState.idle) : AutomationState.idle;

    return Card(
      elevation: isActive ? 3 : 1,
      shape: RoundedRectangleBorder(
        borderRadius: BorderRadius.circular(16),
        side: BorderSide(
          color: isActive ? _stateColor(state) : Colors.transparent,
          width: 1,
        ),
      ),
      child: Padding(
        padding: const EdgeInsets.all(16),
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            Row(
              children: [
                Text(flow.icon, style: const TextStyle(fontSize: 30)),
                const SizedBox(width: 12),
                Expanded(
                  child: Column(
                    crossAxisAlignment: CrossAxisAlignment.start,
                    children: [
                      Text(
                        flow.name,
                        style: const TextStyle(
                          fontSize: 18,
                          fontWeight: FontWeight.w700,
                        ),
                      ),
                      const SizedBox(height: 4),
                      Text(
                        flow.description,
                        style: TextStyle(color: Colors.grey[700]),
                      ),
                    ],
                  ),
                ),
                Chip(
                  label: Text(_stateLabel(state)),
                  backgroundColor: _stateColor(state).withOpacity(0.12),
                  labelStyle: TextStyle(color: _stateColor(state)),
                ),
              ],
            ),
            const SizedBox(height: 12),
            _buildPhaseTimeline(flow, phase, isActive: isActive),
            const SizedBox(height: 14),
            _buildFlowActions(flow, isActive: isActive, state: state),
          ],
        ),
      ),
    );
  }

  Widget _buildPhaseTimeline(
    WorkflowFlowSpec flow,
    String? phase, {
    required bool isActive,
  }) {
    final items = flow.phases;
    final activeColor = isActive
        ? _stateColor(_snapshot?.state ?? AutomationState.idle)
        : Colors.grey;

    return Column(
      crossAxisAlignment: CrossAxisAlignment.start,
      children: [
        const Text('阶段流转', style: TextStyle(fontWeight: FontWeight.w600)),
        const SizedBox(height: 8),
        Wrap(
          spacing: 8,
          runSpacing: 8,
          children: items.map((item) {
            final selected = item.id == phase;
            return Container(
              width: 164,
              padding: const EdgeInsets.symmetric(horizontal: 12, vertical: 10),
              decoration: BoxDecoration(
                color: selected ? activeColor.withOpacity(0.15) : Colors.grey.shade100,
                borderRadius: BorderRadius.circular(14),
                border: Border.all(
                  color: selected ? activeColor : Colors.grey.shade300,
                ),
              ),
              child: Column(
                crossAxisAlignment: CrossAxisAlignment.start,
                mainAxisSize: MainAxisSize.min,
                children: [
                  Text(
                    item.label,
                    style: TextStyle(
                      fontSize: 13,
                      color: selected ? activeColor : Colors.grey[800],
                      fontWeight: selected ? FontWeight.w700 : FontWeight.w500,
                    ),
                  ),
                  const SizedBox(height: 4),
                  Text(
                    item.hint,
                    maxLines: 2,
                    overflow: TextOverflow.ellipsis,
                    style: TextStyle(
                      fontSize: 11,
                      color: selected ? activeColor.withOpacity(0.85) : Colors.grey[600],
                    ),
                  ),
                ],
              ),
            );
          }).toList(),
        ),
        if (phase != null)
          Padding(
            padding: const EdgeInsets.only(top: 10),
            child: Text(
              _phaseHint(items, phase),
              style: TextStyle(color: Colors.grey[600], fontSize: 12),
            ),
          ),
      ],
    );
  }

  String _phaseHint(List<WorkflowPhaseSpec> items, String phase) {
    for (final item in items) {
      if (item.id == phase) return item.hint;
    }
    return '';
  }

  Widget _buildFlowActions(
    WorkflowFlowSpec flow, {
    required bool isActive,
    required AutomationState state,
  }) {
    final runningAnotherFlow = _snapshot?.state == AutomationState.running && !isActive;
    final canStart = !_busy && !runningAnotherFlow && state != AutomationState.running;
    final canStop = !_busy && isActive && state == AutomationState.running;

    return Row(
      children: [
        Icon(
          isActive ? Icons.radio_button_checked : Icons.radio_button_unchecked,
          size: 18,
          color: isActive ? _stateColor(state) : Colors.grey,
        ),
        const SizedBox(width: 8),
        Expanded(
          child: Text(
            isActive
                ? '当前由该流程接管 Android 无障碍状态机'
                : '未接管，保留为可启动流程',
            style: TextStyle(color: Colors.grey[700], fontSize: 12),
          ),
        ),
        if (canStop)
          ElevatedButton.icon(
            onPressed: _stopFlow,
            icon: const Icon(Icons.stop, size: 18),
            label: const Text('停止'),
            style: ElevatedButton.styleFrom(
              backgroundColor: Colors.red,
              foregroundColor: Colors.white,
            ),
          )
        else
          ElevatedButton.icon(
            onPressed: canStart ? () => _startFlow(flow) : null,
            icon: _busy && (_pendingFlowId == flow.id || isActive)
                ? const SizedBox(
                    width: 16,
                    height: 16,
                    child: CircularProgressIndicator(strokeWidth: 2),
                  )
                : const Icon(Icons.play_arrow, size: 18),
            label: Text(runningAnotherFlow ? '等待当前流程' : '启动'),
          ),
      ],
    );
  }

  String _flowLabel(String? flowId) {
    if (flowId == null) return 'none';
    for (final flow in _session.workflowSpec) {
      if (flow.id == flowId) return '${flow.icon} ${flow.name}';
    }
    return flowId;
  }

  Widget _buildRuntimePanel(FlowStatus snapshot) {
    final runtime = snapshot.workflowRuntime;
    if (runtime.isEmpty) return const SizedBox.shrink();

    final decision = runtime['lastDecision']?.toString();
    final observations = runtime['observations'];
    final candidates = runtime['candidates'];
    final avoided = runtime['avoided'];

    Widget textList(String title, List<dynamic> items) {
      if (items.isEmpty) return const SizedBox.shrink();
      return Padding(
        padding: const EdgeInsets.only(top: 10),
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            Text(title, style: const TextStyle(fontWeight: FontWeight.w600)),
            const SizedBox(height: 6),
            ...items.take(6).map(
                  (item) => Padding(
                    padding: const EdgeInsets.only(bottom: 4),
                    child: Text('• $item', style: TextStyle(color: Colors.grey[700], fontSize: 12)),
                  ),
                ),
          ],
        ),
      );
    }

    final observationLines = observations is List
        ? observations.map((item) {
            final map = item is Map ? item : const {};
            final label = map['label']?.toString() ?? '宝箱';
            final rules = map['rules'] is List ? (map['rules'] as List).join('，') : '-';
            return '$label：$rules';
          }).toList(growable: false)
        : const <String>[];

    return Container(
      width: double.infinity,
      margin: const EdgeInsets.only(top: 8),
      padding: const EdgeInsets.all(12),
      decoration: BoxDecoration(
        color: Colors.blueGrey.withOpacity(0.06),
        borderRadius: BorderRadius.circular(12),
      ),
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          const Text('状态机决策依据', style: TextStyle(fontWeight: FontWeight.w700)),
          if (decision != null && decision != 'null') ...[
            const SizedBox(height: 8),
            Text(decision, style: const TextStyle(fontWeight: FontWeight.w500)),
          ],
          textList('奇遇宝箱观测', observationLines),
          textList('闯塔候选加成', candidates is List ? candidates : const []),
          textList('已排除项', avoided is List ? avoided : const []),
        ],
      ),
    );
  }

  Widget _buildForegroundSnapshotLog(FlowStatus snapshot) {
    final entries = snapshot.foregroundSnapshots;
    if (entries.isEmpty) return const SizedBox.shrink();

    String formatTime(int capturedAtMillis) {
      final time = DateTime.fromMillisecondsSinceEpoch(capturedAtMillis);
      String pad(int value) => value.toString().padLeft(2, '0');
      return '${pad(time.hour)}:${pad(time.minute)}:${pad(time.second)}';
    }

    return Padding(
      padding: const EdgeInsets.only(top: 4),
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          const Text('最近前台切换', style: TextStyle(fontWeight: FontWeight.w600)),
          const SizedBox(height: 8),
          ...entries.reversed.map(
            (entry) => Padding(
              padding: const EdgeInsets.only(bottom: 6),
              child: Text(
                '${formatTime(entry.capturedAtMillis)}  ${entry.packageName}\n'
                '页面：${entry.pageLabel ?? '未知页面'} · 来源：${entry.eventType ?? '-'}',
                style: TextStyle(color: Colors.grey[700], fontSize: 12),
              ),
            ),
          ),
        ],
      ),
    );
  }

  Widget _buildSnapshotPanel() {
    final snapshot = _snapshot;
    if (snapshot == null) {
      return const SizedBox.shrink();
    }

    Widget line(String label, String value) {
      return Padding(
        padding: const EdgeInsets.only(bottom: 8),
        child: Row(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            SizedBox(
              width: 92,
              child: Text(label, style: TextStyle(color: Colors.grey[600])),
            ),
            Expanded(
              child: Text(value, style: const TextStyle(fontWeight: FontWeight.w500)),
            ),
          ],
        ),
      );
    }

    return Card(
      margin: const EdgeInsets.fromLTRB(16, 0, 16, 16),
      child: Padding(
        padding: const EdgeInsets.all(16),
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            const Text('当前状态快照', style: TextStyle(fontSize: 16, fontWeight: FontWeight.w700)),
            const SizedBox(height: 12),
            line('状态', _stateLabel(snapshot.state)),
            line('当前流程', _flowLabel(snapshot.visibleFlow)),
            line('活动阶段', snapshot.activePhase ?? 'Idle'),
            line('识别页面', snapshot.pageLabel ?? '-'),
            line('上次消息', snapshot.message ?? '-'),
            line('执行步数', '${snapshot.stepCount}'),
            line('错误', snapshot.error ?? '-'),
            if (snapshot.debugCollectedTexts?.isNotEmpty ?? false) ...[
              const Divider(height: 24),
              const Text('调试：收集到的文本', style: TextStyle(fontSize: 14, fontWeight: FontWeight.w600)),
              const SizedBox(height: 8),
              Container(
                padding: const EdgeInsets.all(12),
                decoration: BoxDecoration(
                  color: Colors.grey[100],
                  borderRadius: BorderRadius.circular(8),
                ),
                child: SelectableText(
                  snapshot.debugCollectedTexts!,
                  style: const TextStyle(fontSize: 12, fontFamily: 'monospace'),
                ),
              ),
            ],
            _buildForegroundSnapshotLog(snapshot),
            _buildRuntimePanel(snapshot),
          ],
        ),
      ),
    );
  }

  @override
  Widget build(BuildContext context) {
    final flows = _session.workflowSpec;
    return RefreshIndicator(
      onRefresh: _refresh,
      child: CustomScrollView(
        physics: const AlwaysScrollableScrollPhysics(),
        slivers: [
          SliverToBoxAdapter(
            child: Padding(
              padding: const EdgeInsets.fromLTRB(16, 16, 16, 12),
              child: Column(
                crossAxisAlignment: CrossAxisAlignment.start,
                children: [
                  Row(
                    children: [
                      const Expanded(
                        child: Text(
                          '工作流状态机',
                          style: TextStyle(fontSize: 24, fontWeight: FontWeight.w800),
                        ),
                      ),
                      IconButton(
                        onPressed: _refresh,
                        icon: const Icon(Icons.refresh),
                      ),
                    ],
                  ),
                  const SizedBox(height: 6),
                  Text(
                    '以 Android 侧固化状态机为唯一真源，Flutter 只负责展示与引导。',
                    style: TextStyle(color: Colors.grey[700]),
                  ),
                ],
              ),
            ),
          ),
          SliverToBoxAdapter(child: _buildSnapshotPanel()),
          if (_loading)
            const SliverFillRemaining(
              hasScrollBody: false,
              child: Center(child: CircularProgressIndicator()),
            )
          else
            SliverList.separated(
              itemCount: flows.length,
              separatorBuilder: (_, __) => const SizedBox(height: 12),
              itemBuilder: (context, index) => Padding(
                padding: const EdgeInsets.symmetric(horizontal: 16),
                child: _buildFlowCard(flows[index]),
              ),
            ),
          const SliverToBoxAdapter(child: SizedBox(height: 24)),
        ],
      ),
    );
  }
}
