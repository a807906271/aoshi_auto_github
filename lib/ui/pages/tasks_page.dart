/// 游戏自动化任务页面
import 'package:flutter/material.dart';
import '../../automation/automation_session.dart';
import '../../automation/automation_channel.dart';

/// 游戏自动化任务页面
class TasksPage extends StatefulWidget {
  const TasksPage({super.key});

  @override
  State<TasksPage> createState() => _TasksPageState();
}

class _TasksPageState extends State<TasksPage> {
  final AutomationSession _session = AutomationSession();
  bool _accessibilityEnabled = false;

  @override
  void initState() {
    super.initState();
    _checkAccessibility();
  }

  Future<void> _checkAccessibility() async {
    final enabled = await _session.checkAccessibility();
    if (mounted) {
      setState(() {
        _accessibilityEnabled = enabled;
      });
    }
  }

  Future<void> _startFlow(GameFlow flow) async {
    if (!_accessibilityEnabled) {
      _showAccessibilityDialog();
      return;
    }

    final success = await _session.start(flow);
    if (mounted) {
      if (success) {
        ScaffoldMessenger.of(context).showSnackBar(
          SnackBar(content: Text('${flow.name} 已启动')),
        );
      } else {
        ScaffoldMessenger.of(context).showSnackBar(
          SnackBar(
            content: Text(_session.lastError ?? '启动失败'),
            backgroundColor: Colors.red,
          ),
        );
      }
      setState(() {});
    }
  }

  Future<void> _stopFlow() async {
    await _session.stop();
    if (mounted) {
      setState(() {});
    }
  }

  void _showAccessibilityDialog() {
    showDialog(
      context: context,
      builder: (context) => AlertDialog(
        title: const Text('需要无障碍授权'),
        content: const Text('请先在设置页面开启无障碍服务'),
        actions: [
          TextButton(
            onPressed: () => Navigator.pop(context),
            child: const Text('取消'),
          ),
          ElevatedButton(
            onPressed: () {
              Navigator.pop(context);
              // 切换到设置页
              DefaultTabController.of(context).animateTo(3);
            },
            child: const Text('去设置'),
          ),
        ],
      ),
    );
  }

  Widget _buildFlowCard(GameFlow flow) {
    final isRunning = _session.isRunning && _session.currentFlowId == flow.id;
    final isDisabled = _session.isRunning && _session.currentFlowId != flow.id;

    return Card(
      margin: const EdgeInsets.symmetric(vertical: 8, horizontal: 16),
      child: Padding(
        padding: const EdgeInsets.all(16),
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            Row(
              children: [
                Text(
                  flow.icon,
                  style: const TextStyle(fontSize: 32),
                ),
                const SizedBox(width: 12),
                Expanded(
                  child: Column(
                    crossAxisAlignment: CrossAxisAlignment.start,
                    children: [
                      Text(
                        flow.name,
                        style: const TextStyle(
                          fontSize: 18,
                          fontWeight: FontWeight.bold,
                        ),
                      ),
                      const SizedBox(height: 4),
                      Text(
                        flow.description,
                        style: TextStyle(
                          color: Colors.grey[600],
                          fontSize: 14,
                        ),
                      ),
                    ],
                  ),
                ),
              ],
            ),
            const SizedBox(height: 16),
            Row(
              children: [
                if (isRunning) ...[
                  const SizedBox(
                    width: 16,
                    height: 16,
                    child: CircularProgressIndicator(strokeWidth: 2),
                  ),
                  const SizedBox(width: 8),
                  Text(
                    '执行中...',
                    style: TextStyle(color: Colors.blue[700]),
                  ),
                  const Spacer(),
                  ElevatedButton.icon(
                    onPressed: _stopFlow,
                    icon: const Icon(Icons.stop, size: 18),
                    label: const Text('停止'),
                    style: ElevatedButton.styleFrom(
                      backgroundColor: Colors.red,
                      foregroundColor: Colors.white,
                    ),
                  ),
                ] else ...[
                  Icon(
                    _accessibilityEnabled ? Icons.check_circle : Icons.warning,
                    color: _accessibilityEnabled ? Colors.green : Colors.orange,
                    size: 20,
                  ),
                  const SizedBox(width: 8),
                  Text(
                    _accessibilityEnabled ? '就绪' : '需要授权',
                    style: TextStyle(
                      color: _accessibilityEnabled ? Colors.green[700] : Colors.orange[700],
                    ),
                  ),
                  const Spacer(),
                  ElevatedButton.icon(
                    onPressed: isDisabled ? null : () => _startFlow(flow),
                    icon: const Icon(Icons.play_arrow, size: 18),
                    label: const Text('启动'),
                  ),
                ],
              ],
            ),
          ],
        ),
      ),
    );
  }

  Widget _buildStatusBanner() {
    if (!_accessibilityEnabled) {
      return Container(
        padding: const EdgeInsets.all(12),
        margin: const EdgeInsets.all(16),
        decoration: BoxDecoration(
          color: Colors.orange[50],
          borderRadius: BorderRadius.circular(8),
          border: Border.all(color: Colors.orange[200]!),
        ),
        child: Row(
          children: [
            Icon(Icons.warning, color: Colors.orange[700]),
            const SizedBox(width: 12),
            const Expanded(
              child: Text(
                '无障碍服务未启用，请先在设置页面授权',
                style: TextStyle(fontSize: 14),
              ),
            ),
            TextButton(
              onPressed: () {
                DefaultTabController.of(context).animateTo(3);
              },
              child: const Text('去设置'),
            ),
          ],
        ),
      );
    }
    return const SizedBox.shrink();
  }

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      body: SingleChildScrollView(
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            // 状态提示
            _buildStatusBanner(),

            // 标题
            const Padding(
              padding: EdgeInsets.all(16),
              child: Text(
                '游戏自动化',
                style: TextStyle(
                  fontSize: 20,
                  fontWeight: FontWeight.bold,
                ),
              ),
            ),

            // 使用说明
            Padding(
              padding: const EdgeInsets.symmetric(horizontal: 16),
              child: Container(
                padding: const EdgeInsets.all(12),
                decoration: BoxDecoration(
                  color: Colors.blue[50],
                  borderRadius: BorderRadius.circular(8),
                ),
                child: const Column(
                  crossAxisAlignment: CrossAxisAlignment.start,
                  children: [
                    Text(
                      '使用说明：',
                      style: TextStyle(fontWeight: FontWeight.bold),
                    ),
                    SizedBox(height: 8),
                    Text('1. 手动打开游戏并进入对应功能页面'),
                    Text('2. 点击启动按钮开始自动化'),
                    Text('3. 识别失败时自动停止'),
                  ],
                ),
              ),
            ),

            const SizedBox(height: 16),

            // 流程列表
            ...GameFlows.all.map(_buildFlowCard),

            // 执行状态
            if (_session.lastMessage != null || _session.lastError != null)
              Padding(
                padding: const EdgeInsets.all(16),
                child: Container(
                  padding: const EdgeInsets.all(12),
                  decoration: BoxDecoration(
                    color: _session.lastError != null ? Colors.red[50] : Colors.green[50],
                    borderRadius: BorderRadius.circular(8),
                  ),
                  child: Text(
                    _session.lastError ?? _session.lastMessage ?? '',
                    style: TextStyle(
                      color: _session.lastError != null ? Colors.red[700] : Colors.green[700],
                    ),
                  ),
                ),
              ),

            const SizedBox(height: 32),
          ],
        ),
      ),
    );
  }
}
