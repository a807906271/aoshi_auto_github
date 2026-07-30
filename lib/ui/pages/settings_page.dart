/// 设置页面
import 'package:flutter/material.dart';
import 'package:flutter_markdown/flutter_markdown.dart';
import '../../automation/automation_channel.dart';

/// 设置页面
class SettingsPage extends StatefulWidget {
  const SettingsPage({super.key});

  @override
  State<SettingsPage> createState() => _SettingsPageState();
}

class _SettingsPageState extends State<SettingsPage> {
  bool _darkMode = false;
  bool _autoSave = true;
  bool _showNotifications = true;
  double _executionTimeout = 30.0;
  String _language = 'zh-CN';
  String _storagePath = '应用数据目录';
  
  // 无障碍服务状态
  bool _accessibilityEnabled = false;
  final AutomationChannel _channel = AutomationChannel();

  final List<String> _languages = [
    'zh-CN',
    'en-US',
    'ja-JP',
    'ko-KR',
  ];

  @override
  void initState() {
    super.initState();
    _checkAccessibility();
  }

  Future<void> _checkAccessibility() async {
    final enabled = await _channel.isAccessibilityEnabled();
    if (mounted) {
      setState(() {
        _accessibilityEnabled = enabled;
      });
    }
  }

  Future<void> _openAccessibilitySettings() async {
    await _channel.openAccessibilitySettings();
    // 延迟检查状态（用户需要手动返回）
    Future.delayed(const Duration(seconds: 2), _checkAccessibility);
  }

  Widget _buildAccessibilitySection() {
    return Column(
      crossAxisAlignment: CrossAxisAlignment.start,
      children: [
        Padding(
          padding: const EdgeInsets.symmetric(vertical: 16, horizontal: 16),
          child: Row(
            children: [
              const Text(
                '无障碍服务',
                style: TextStyle(
                  fontSize: 18,
                  fontWeight: FontWeight.bold,
                  color: Colors.blue,
                ),
              ),
              const SizedBox(width: 8),
              if (_accessibilityEnabled)
                Container(
                  padding: const EdgeInsets.symmetric(horizontal: 8, vertical: 2),
                  decoration: BoxDecoration(
                    color: Colors.green[100],
                    borderRadius: BorderRadius.circular(12),
                  ),
                  child: Text(
                    '已启用',
                    style: TextStyle(color: Colors.green[800], fontSize: 12),
                  ),
                )
              else
                Container(
                  padding: const EdgeInsets.symmetric(horizontal: 8, vertical: 2),
                  decoration: BoxDecoration(
                    color: Colors.orange[100],
                    borderRadius: BorderRadius.circular(12),
                  ),
                  child: Text(
                    '未启用',
                    style: TextStyle(color: Colors.orange[800], fontSize: 12),
                  ),
                ),
            ],
          ),
        ),
        ListTile(
          leading: Icon(
            _accessibilityEnabled ? Icons.accessibility_new : Icons.accessibility,
            color: _accessibilityEnabled ? Colors.green : Colors.orange,
          ),
          title: Text(
            _accessibilityEnabled ? '无障碍服务已授权' : '启用无障碍服务',
            style: const TextStyle(fontWeight: FontWeight.w600),
          ),
          subtitle: Text(
            _accessibilityEnabled 
                ? '游戏自动化功能已就绪'
                : '游戏自动化需要无障碍服务支持',
          ),
          trailing: _accessibilityEnabled
              ? const Icon(Icons.check_circle, color: Colors.green)
              : ElevatedButton(
                  onPressed: _openAccessibilitySettings,
                  child: const Text('去设置'),
                ),
        ),
        const Divider(),
      ],
    );
  }

  Widget _buildSection(String title, List<Widget> children) {
    return Column(
      crossAxisAlignment: CrossAxisAlignment.start,
      children: [
        Padding(
          padding: const EdgeInsets.symmetric(vertical: 16, horizontal: 16),
          child: Text(
            title,
            style: const TextStyle(
              fontSize: 18,
              fontWeight: FontWeight.bold,
              color: Colors.blue,
            ),
          ),
        ),
        ...children,
        const Divider(),
      ],
    );
  }

  Widget _buildSwitchTile({
    required String title,
    required String subtitle,
    required bool value,
    required ValueChanged<bool> onChanged,
  }) {
    return SwitchListTile(
      title: Text(title),
      subtitle: Text(subtitle),
      value: value,
      onChanged: onChanged,
    );
  }

  Widget _buildSliderTile({
    required String title,
    required String subtitle,
    required double value,
    required double min,
    required double max,
    required ValueChanged<double> onChanged,
  }) {
    return ListTile(
      title: Text(title),
      subtitle: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          Text(subtitle),
          Slider(
            value: value,
            min: min,
            max: max,
            divisions: (max - min).toInt(),
            label: '${value.toInt()}秒',
            onChanged: onChanged,
          ),
        ],
      ),
    );
  }

  Widget _buildDropdownTile({
    required String title,
    required String subtitle,
    required String value,
    required List<String> items,
    required ValueChanged<String?> onChanged,
  }) {
    return ListTile(
      title: Text(title),
      subtitle: Text(subtitle),
      trailing: DropdownButton<String>(
        value: value,
        items: items.map((item) {
          return DropdownMenuItem(
            value: item,
            child: Text(item),
          );
        }).toList(),
        onChanged: onChanged,
      ),
    );
  }

  Widget _buildStorageTile() {
    return ListTile(
      leading: const Icon(Icons.folder),
      title: const Text('存储路径'),
      subtitle: Text(_storagePath),
      trailing: OutlinedButton(
        onPressed: () {
          // TODO: 打开存储路径选择
        },
        child: const Text('修改'),
      ),
    );
  }

  Widget _buildAboutSection() {
    return Container(
      padding: const EdgeInsets.all(16),
      decoration: BoxDecoration(
        color: Colors.blue[50],
        borderRadius: BorderRadius.circular(8),
      ),
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          const Text(
            '关于',
            style: TextStyle(
              fontSize: 18,
              fontWeight: FontWeight.bold,
            ),
          ),
          const SizedBox(height: 12),
          const MarkdownBody(
            data: '''
**Aoshi Auto** 是一款游戏自动化助手工具。

- 基于 Android 无障碍服务
- 支持自定义工作流
- 本地执行，数据安全
''',
          ),
        ],
      ),
    );
  }

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      body: ListView(
        children: [
          // 无障碍服务设置
          _buildAccessibilitySection(),

          // 通用设置
          _buildSection('通用设置', [
            _buildSwitchTile(
              title: '深色模式',
              subtitle: '启用深色主题',
              value: _darkMode,
              onChanged: (value) => setState(() => _darkMode = value),
            ),
            _buildDropdownTile(
              title: '语言',
              subtitle: '应用界面语言',
              value: _language,
              items: _languages,
              onChanged: (value) {
                if (value != null) {
                  setState(() => _language = value);
                }
              },
            ),
          ]),

          // 执行设置
          _buildSection('执行设置', [
            _buildSliderTile(
              title: '执行超时',
              subtitle: '自动化任务超时时间',
              value: _executionTimeout,
              min: 10,
              max: 120,
              onChanged: (value) => setState(() => _executionTimeout = value),
            ),
            _buildSwitchTile(
              title: '自动保存',
              subtitle: '执行结果自动保存',
              value: _autoSave,
              onChanged: (value) => setState(() => _autoSave = value),
            ),
            _buildSwitchTile(
              title: '通知提醒',
              subtitle: '执行完成时发送通知',
              value: _showNotifications,
              onChanged: (value) => setState(() => _showNotifications = value),
            ),
          ]),

          // 存储设置
          _buildSection('存储设置', [
            _buildStorageTile(),
          ]),

          // 关于应用
          Padding(
            padding: const EdgeInsets.all(16),
            child: _buildAboutSection(),
          ),

          // 应用信息
          Container(
            padding: const EdgeInsets.all(32),
            alignment: Alignment.center,
            child: Column(
              children: [
                const Text(
                  'Aoshi Auto',
                  style: TextStyle(
                    fontSize: 24,
                    fontWeight: FontWeight.bold,
                    color: Colors.blue,
                  ),
                ),
                const SizedBox(height: 8),
                Text(
                  '版本 0.1.0',
                  style: TextStyle(color: Colors.grey[600]),
                ),
                const SizedBox(height: 16),
                const Text(
                  '个人助手自动化工具',
                  textAlign: TextAlign.center,
                  style: TextStyle(fontSize: 16),
                ),
                const SizedBox(height: 8),
                Text(
                  '© 2024 Aoshi Auto 项目',
                  style: TextStyle(color: Colors.grey[600]),
                ),
              ],
            ),
          ),
        ],
      ),
    );
  }
}
