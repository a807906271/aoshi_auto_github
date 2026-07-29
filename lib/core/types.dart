/// 核心数据类型定义
/// 简化版本，确保 GitHub Actions 构建成功

/// 数据类型枚举
enum DataType {
  text,
  number,
  list,
  dict,
  file,
}

/// 数据项 - 函数式数据流的基本单元
class DataItem {
  final String id;
  final DataType type;
  final dynamic content;
  final Map<String, dynamic> metadata;

  const DataItem({
    required this.id,
    required this.type,
    required this.content,
    this.metadata = const {},
  });

  @override
  String toString() {
    return 'DataItem(id: $id, type: $type)';
  }
}

/// 任务定义
class TaskDefinition {
  final String name;
  final List<DataItem> inputs;
  final String? transform;
  final List<String> dependencies;

  const TaskDefinition({
    required this.name,
    required this.inputs,
    this.transform,
    this.dependencies = const [],
  });

  @override
  String toString() {
    return 'TaskDefinition(name: $name, inputs: ${inputs.length})';
  }
}

/// 简单的数据处理管道
class SimplePipeline {
  final String name;
  final List<Function> stages;

  SimplePipeline(this.name) : stages = [];

  SimplePipeline addStage(Function stage) {
    stages.add(stage);
    return this;
  }

  DataItem run(DataItem input) {
    dynamic result = input.content;
    
    for (final stage in stages) {
      result = stage(result);
    }
    
    return DataItem(
      id: '${input.id}_processed',
      type: input.type,
      content: result,
      metadata: {
        ...input.metadata,
        'pipeline': name,
      },
    );
  }
}

/// 示例函数库
class FunctionLibrary {
  static final Map<String, Function> functions = {
    'toUpper': (String text) => text.toUpperCase(),
    'toLower': (String text) => text.toLowerCase(),
    'trim': (String text) => text.trim(),
    'add': (dynamic x, dynamic y) {
      if (x is num && y is num) return x + y;
      return x;
    },
    'multiply': (dynamic x, dynamic y) {
      if (x is num && y is num) return x * y;
      return x;
    },
  };

  static Function? getFunction(String name) {
    return functions[name];
  }
}