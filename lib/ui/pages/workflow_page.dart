/// 工作流编排页面（占位）
import 'package:flutter/material.dart';

class WorkflowPage extends StatelessWidget {
  const WorkflowPage({super.key});

  @override
  Widget build(BuildContext context) {
    return const Center(
      child: Column(
        mainAxisAlignment: MainAxisAlignment.center,
        children: [
          Icon(Icons.schema, size: 64, color: Colors.blue),
          SizedBox(height: 24),
          Text(
            '工作流编排',
            style: TextStyle(fontSize: 24, fontWeight: FontWeight.bold),
          ),
          SizedBox(height: 16),
          Padding(
            padding: EdgeInsets.symmetric(horizontal: 32),
            child: Text(
              '功能开发中',
              textAlign: TextAlign.center,
              style: TextStyle(color: Colors.grey),
            ),
          ),
        ],
      ),
    );
  }
}
