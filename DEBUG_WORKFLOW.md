# Android 无障碍服务调试流程规范

## 协作规范与边界（必读）

### 1. 打包与发布
- 应用打包通过 **git push 触发 GitHub Actions CI** 完成（`.github/workflows/build-android.yml`）。
- **AI 不参与打包**：推送、触发 CI、安装 APK 等由开发者自行操作。
- 调试排障时，AI 只负责代码修改与日志分析；每次代码变更后的验证闭环依赖开发者的 push → 打包 → 安装。

### 2. adb 调试协作模式
- **AI 没有真机/adb 直接访问权限**，无法执行 `adb` 命令，也不会读取设备侧数据。
- 固定协作流程：
  1. **AI 提供**一条或多条 `adb` 命令（PowerShell 环境）
  2. **开发者执行**，将终端输出**原样完整粘贴**回来（不得截断、不得转述）
  3. **AI 分析**输出，定位问题，给出下一步命令或修复方案
- 命令示例（PowerShell）：
  ```powershell
  # 设备状态
  adb devices
  # 清理日志缓冲区
  adb logcat -c
  # 拉取指定标签日志（不落盘，终端实时打印）
  adb logcat -d -s "QiyuAuto:V" "AoshiA11y:V"
  ```
- **Windows 编码约定**：日志含中文，执行前先切换 UTF-8 代码页，避免乱码：
  ```powershell
  chcp 65001
  ```
  若使用 PowerShell，可改用：`[Console]::OutputEncoding = [System.Text.Encoding]::UTF8`
- **禁止落盘**：日志直接终端打印后粘贴反馈；重定向到文件容易产生编码损坏（乱码），除非 AI 明确要求。

### 3. 已知环境约束
- 目标游戏（傲世西游 `com.tencent.JWX`）的**无障碍通道不可靠**：
  - `dispatchGesture` 可能被系统接受（accepted=true）后既不执行也不回调（vivo/Funtouch 实测）
  - `performAction(ACTION_CLICK)` 可能因游戏自绘渲染无节点树而失效
  - 详见 `QiyuCoordinateAutomation.kt` 类注释
- 真机为 vivo 设备（Funtouch OS），存在系统级无障碍手势限制。

---

## 问题描述

**问题**：应用在自动跳转游戏时焦点切换导致流程终止

**根因**：通过 logcat 定位，焦点丢失事件触发流程停止

**修复方案**：
1. 在 `AoshiAccessibilityService.kt` 中添加焦点丢失容忍机制（初始值：2秒）
2. 在 `startQiyu()` 和 `startTower()` 中设置目标游戏包名 `com.tencent.JWX`（傲世西游）

**修复版本**：2026-08-14

---

## 真机测试前准备

### 1. 连接设备并验证
```powershell
# 检查设备连接状态
adb devices

# 预期输出示例：
# List of devices attached
# <device_id>    device
```

### 2. 确认应用包名
```powershell
# 查看当前安装的目标应用
adb shell pm list packages | Select-String "aoshi"

# 预期输出：
# package:com.aoshi.auto_mobile
```

---

## 调试流程

### 阶段一：部署与启动监控

#### 步骤 1：清理旧日志
```powershell
adb logcat -c
```

#### 步骤 2：启动实时日志监控（关键标签）
```powershell
# 监控无障碍服务核心日志
adb logcat AoshiA11y:D *:S

# 或者监控更全面的日志（包含系统事件）
adb logcat AoshiA11y:D AccessibilityService:I WindowManager:W *:S
```

**执行说明**：此命令会持续输出日志，保持窗口运行，测试过程中观察输出

---

### 阶段二：触发问题场景

#### 步骤 3：在真机上执行操作
1. 打开 Aoshi 应用
2. 启动自动化流程（如奇遇流程）
3. 观察应用跳转到目标游戏时的行为
4. 记录以下关键时间点：
   - 流程启动时间
   - 焦点切换发生时间
   - 流程是否继续执行或终止

---

### 阶段三：日志分析

#### 步骤 4：捕获完整日志到文件
```powershell
# 将最近的日志保存到文件（包含时间戳）
$timestamp = Get-Date -Format "yyyyMMdd_HHmmss"
adb logcat -d > "D:\dz_workspace\aoshi_auto_github\logs\logcat_$timestamp.txt"
```

#### 步骤 5：搜索关键事件
```powershell
# 搜索焦点丢失相关日志
adb logcat -d | Select-String "焦点丢失|FOCUS_LOSS|停止|容忍"

# 搜索流程状态变化
adb logcat -d | Select-String "AoshiA11y.*状态|phase|流程"

# 搜索错误信息
adb logcat -d | Select-String "AoshiA11y.*错误|Error|Exception"
```

**反馈要求**：将上述命令输出结果完整粘贴回复

---

### 阶段四：诊断关键指标

#### 步骤 6：检查焦点事件时序
```powershell
# 提取焦点相关事件的时间戳
adb logcat -d -v time | Select-String "AoshiA11y.*(焦点|FOCUS|Window)" | Select-Object -Last 50
```

#### 步骤 7：验证容忍机制是否生效
需要在日志中确认以下信息：
- `焦点丢失容忍开始` 的时间戳
- `焦点恢复，取消延迟停止` 或 `焦点丢失超时，停止流程` 的输出
- 焦点丢失持续时长是否超过容忍时间（当前 2000ms）

**反馈要求**：提供这些关键日志行及其时间戳

---

### 阶段五：参数调优

#### 步骤 8：根据测试结果调整容忍时间

如果 2 秒不足以覆盖正常的应用切换延迟，需要调整 `FOCUS_LOSS_TOLERANCE_MILLIS` 常量：

**当前值**：`2_000L` (2秒)

**调整建议**：
- 焦点切换耗时 < 2秒：保持当前值
- 焦点切换耗时 2-3秒：调整为 `3_000L` (3秒)
- 焦点切换耗时 3-5秒：调整为 `5_000L` (5秒)
- 焦点切换耗时 > 5秒：需要排查游戏启动性能问题

**修改位置**：
```kotlin
// AoshiAccessibilityService.kt 第 33 行附近
private const val FOCUS_LOSS_TOLERANCE_MILLIS = 2_000L  // 调整此值
```

---

## 验证清单

测试完成后，确认以下各项：

- [ ] 流程启动后能正常跳转到目标游戏
- [ ] 焦点切换时流程未被意外终止
- [ ] 日志中记录了焦点丢失和恢复的完整时序
- [ ] 容忍时间参数适配实际设备性能
- [ ] 流程在焦点恢复后继续正常执行

---

## 常见问题排查

### 问题 1：日志中未出现 "焦点丢失" 相关输出
**可能原因**：
- 无障碍服务未正确启动
- 应用未获得无障碍权限

**验证命令**：
```powershell
# 检查无障碍服务状态
adb shell settings get secure enabled_accessibility_services
```

**预期输出应包含**：`com.aoshi.auto_mobile/com.aoshi.auto_mobile.automation.AoshiAccessibilityService`

---

### 问题 2：焦点频繁丢失恢复
**可能原因**：
- 系统弹窗干扰（通知、权限请求等）
- 目标游戏启动过程中有中间页面

**诊断命令**：
```powershell
# 查看窗口焦点变化历史
adb logcat -d -v time | Select-String "WindowManager.*Focus|ActivityManager.*START"
```

---

### 问题 3：容忍机制未生效，流程仍然停止
**排查步骤**：
1. 确认代码已正确编译打包
2. 检查日志中 `FOCUS_LOSS_TOLERANCE_MILLIS` 的实际值
3. 验证 `delayedFocusLossStop` 是否被正确取消

**详细日志命令**：
```powershell
# 提取容忍机制相关的完整调用链
adb logcat -d -v threadtime | Select-String "focusLoss|delayedFocusLossStop|removeCallbacks"
```

---

## 进一步优化方向

如果容忍机制仍无法完全解决问题，可能需要：

1. **状态机优化**：区分"预期的焦点切换"和"异常的焦点丢失"
2. **白名单机制**：对特定游戏包名的焦点切换不触发停止逻辑
3. **多阶段容忍**：根据当前执行阶段动态调整容忍时间
4. **焦点恢复检测**：主动查询前台应用，而不仅仅依赖事件回调

**需要提供的信息**：
- 多次测试的焦点丢失时长分布
- 不同设备/系统版本的表现差异
- 游戏启动的完整生命周期日志

---

## 日志归档

测试过程中产生的日志文件建议保存在：
```
D:\dz_workspace\aoshi_auto_github\logs\
```

文件命名规范：
- `logcat_<timestamp>.txt` - 完整日志
- `logcat_focus_<timestamp>.txt` - 焦点事件专项日志
- `logcat_error_<timestamp>.txt` - 错误信息专项日志

---

## 提交前检查

修复验证通过后，提交前确认：

1. [ ] 代码中的 `FOCUS_LOSS_TOLERANCE_MILLIS` 已调整到最优值
2. [ ] 相关日志输出清晰可读（包含关键时间戳和状态）
3. [ ] 更新 `README.md` 或相关文档说明此修复
4. [ ] 提交信息应包含问题描述、根因和修复方案

**建议提交信息模板**：
```
fix(accessibility): 添加焦点丢失容忍机制

问题：应用跳转游戏时焦点切换导致自动化流程终止
根因：onAccessibilityEvent 中 TYPE_WINDOW_STATE_CHANGED 事件判断前台应用非目标游戏时立即停止
修复：引入 FOCUS_LOSS_TOLERANCE_MILLIS (Xms) 延迟停止，允许短暂焦点切换

测试：真机验证焦点切换场景下流程正常继续执行
```

---

*文档创建时间：2026-08-14*
*对应修复版本：AoshiAccessibilityService.kt (FOCUS_LOSS_TOLERANCE_MILLIS = 2000L)*
