# Android 无障碍服务调试流程规范

## 协作规范与边界（必读）

### 1. 打包与发布
- 应用打包通过 **git push 触发 GitHub Actions CI** 完成（`.github/workflows/build-android.yml`）。
- **AI 参与全链路**：AI 可直接 commit / push 触发 CI、轮询构建状态、下载 Artifact，并通过 adb 安装到真机（`adb install -r`）。
- 调试排障时，AI 负责代码修改、构建触发、装机与日志分析，形成「改代码 → 推送 → CI 出包 → adb 装机 → 实测 → 读日志」的闭环。
- 开发者只需参与：账号级授权（GitHub 凭证、设备 USB 调试授权）、以及必须人工完成的物理/账号操作（游戏内前置页面、支付/实名等）。
- 若 AI 侧网络不可达 GitHub（代理 CONNECT 失败等），退回由开发者手工 push，其余环节仍由 AI 执行。

### 2. adb 调试协作模式
- **AI 具备真机 adb 直接访问权限**：可直接执行 `adb` 命令、读取设备侧数据、抓取日志与截图并自行分析，**不需要开发者手工粘贴终端输出**。
- 固定协作流程：
  1. **AI 自行执行** `adb` 命令并读取结果（`adb devices` / `adb shell` / `adb logcat` / `adb exec-out screencap` 等）
  2. **AI 自主分析**输出，定位问题，给出修复方案或代码变更
  3. **开发者只在需要物理动作时配合**：手动打开游戏并进入指定页面、扫码/授权、在设备上点击确认弹窗等
- 常用命令（Bash / Git Bash 环境）：
  ```bash
  # 设备与授权状态
  adb devices -l
  # 清理日志缓冲区
  adb logcat -c
  # 拉取指定标签日志（AI 直接读取）
  adb logcat -d -s "AoshiA11y:V" "QiyuAuto:V"
  # 截图并落到本机 logs/ 供 AI 查看
  adb exec-out screencap -p > logs/screen_$(date +%H%M%S).png
  # 界面控件树（Flutter 自绘页面可能为空，此时改用截图）
  adb shell uiautomator dump /sdcard/ui.xml && adb shell cat /sdcard/ui.xml
  ```
- **编码约定**：日志中含中文，AI 侧以 UTF-8 读取（`adb logcat` 输出本身是 UTF-8 字节流，重定向落盘后由 AI 直接读取，不经过 Windows 控制台，因此不会出现乱码）。**仅在需要人工在终端查看时**才执行 `chcp 65001`，PowerShell 下可用 `[Console]::OutputEncoding = [System.Text.Encoding]::UTF8`。
- **日志落盘**：允许且推荐 AI 将日志重定向到 `logs/`（文件名带时间戳）便于回溯；人工查看时若出现乱码，改用文件读取而非刷新终端。

### 2.1 设备与环境基线（2026-11-06 实测）
- 设备：vivo V2415A（PD2415），Android 15 / SDK 35，arm64-v8a，屏幕 1260×2800 @560dpi。
- adb：`D:\adb\platform-tools\adb.exe`（已在 PATH，v1.0.41 / 37.0.1）。
- 应用：`com.aoshi.auto_mobile`，versionName 0.1.0 / versionCode 1，minSdk 26 / targetSdk 34。
- 无障碍服务组件：`com.aoshi.auto_mobile/.automation.AoshiAccessibilityService`。
- 启用/禁用服务（保留其它已启用服务，勿直接覆盖为单一值）：
  ```bash
  # 查询
  adb shell settings get secure enabled_accessibility_services
  # 追加启用（注意用冒号分隔，保留已有项）
  adb shell settings put secure enabled_accessibility_services "<原有项>:com.aoshi.auto_mobile/com.aoshi.auto_mobile.automation.AoshiAccessibilityService"
  adb shell settings put secure accessibility_enabled 1
  # 验证是否真的绑定成功
  adb shell dumpsys accessibility | grep -i "Aoshi Auto"
  ```
- **已知风险位**：`dumpsys accessibility` 显示 `capabilities=161`，缺少 `CAN_REQUEST_TAKE_SCREENSHOT(0x40)`，而 `res/xml` 已声明 `canTakeScreenshot="true"`。奇遇/闯塔流程依赖 `takeScreenshot()`，此能力缺失会导致截图失败（详见下文「截图能力」章节）。

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
```bash
# 检查设备连接状态
adb devices -l

# 预期输出示例：
# List of devices attached
# <device_id>    device product:... model:...
```

### 2. 确认应用包名
```bash
# 查看当前安装的目标应用
adb shell pm list packages | grep -i aoshi

# 预期输出：
# package:com.aoshi.auto_mobile
```

---

## 调试流程

### 阶段一：部署与启动监控

#### 步骤 1：清理旧日志
```bash
adb logcat -c
```

#### 步骤 2：启动实时日志监控（关键标签）
```bash
# 监控无障碍服务核心日志
adb logcat -s "AoshiA11y:V"

# 或者监控更全面的日志（包含系统事件）
adb logcat "AoshiA11y:V" "AccessibilityService:I" "WindowManager:W" "*:S"
```

**执行说明**：持续输出型命令放后台/单独会话运行。AI 侧更推荐「先 `adb logcat -c` 清空 → 跑场景 → `adb logcat -d` 一次性取回」的切片方式，便于精确定位。

---

### 阶段二：触发问题场景

#### 步骤 3：执行操作（AI 可直接驱动，人只在必要处介入）
1. 打开 Aoshi 应用 —— AI 可执行 `adb shell am start -W -n com.aoshi.auto_mobile/.MainActivity`
2. 启动自动化流程（如奇遇流程）—— AI 可截图定位「启动」按钮后 `adb shell input tap X Y`；**游戏需停留在正确页面的前置动作由开发者完成**
3. 观察应用跳转到目标游戏时的行为
4. 记录以下关键时间点：
   - 流程启动时间
   - 焦点切换发生时间
   - 流程是否继续执行或终止

---

### 阶段三：日志分析

#### 步骤 4：捕获完整日志到文件
```bash
# 将日志保存到文件（AI 直接读取，不经 Windows 控制台，无乱码）
mkdir -p D:/dz_workspace/aoshi_auto_github/logs
adb logcat -d > "D:/dz_workspace/aoshi_auto_github/logs/logcat_$(date +%Y%m%d_%H%M%S).txt"
```

#### 步骤 5：搜索关键事件
```bash
LOG="D:/dz_workspace/aoshi_auto_github/logs/logcat_latest.txt"

# 搜索焦点丢失相关日志
grep -E "焦点丢失|FOCUS_LOSS|停止|容忍" "$LOG"

# 搜索流程状态变化
grep -E "AoshiA11y.*(状态|phase|流程)" "$LOG"

# 搜索错误信息
grep -E "AoshiA11y.*(错误|Error|Exception)" "$LOG"
```

---

### 阶段四：诊断关键指标

#### 步骤 6：检查焦点事件时序
```bash
# 提取焦点相关事件的时间戳（最后 50 条）
adb logcat -d -v time | grep -E "AoshiA11y.*(焦点|FOCUS|Window)" | tail -50
```

#### 步骤 7：验证容忍机制是否生效
需要在日志中确认以下信息：
- `焦点丢失容忍开始` 的时间戳
- `焦点恢复，取消延迟停止` 或 `焦点丢失超时，停止流程` 的输出
- 焦点丢失持续时长是否超过容忍时间（当前 2000ms）

**取证要求**：AI 从日志文件中提取上述关键行的原始文本与时间戳，作为判断依据写入结论，不只给结论不给证据。

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
```bash
# 检查无障碍服务状态（settings 里「有」不等于「已绑定」，必须两边都查）
adb shell settings get secure enabled_accessibility_services
adb shell dumpsys accessibility | grep -i "Aoshi Auto"
```

**预期输出应包含**：`com.aoshi.auto_mobile/com.aoshi.auto_mobile.automation.AoshiAccessibilityService`

---

### 问题 2：焦点频繁丢失恢复
**可能原因**：
- 系统弹窗干扰（通知、权限请求等）
- 目标游戏启动过程中有中间页面

**诊断命令**：
```bash
# 查看窗口焦点变化历史
adb logcat -d -v time | grep -E "WindowManager.*Focus|ActivityManager.*START"
```

---

### 问题 3：容忍机制未生效，流程仍然停止
**排查步骤**：
1. 确认代码已正确编译打包
2. 检查日志中 `FOCUS_LOSS_TOLERANCE_MILLIS` 的实际值
3. 验证 `delayedFocusLossStop` 是否被正确取消

**详细日志命令**：
```bash
# 提取容忍机制相关的完整调用链
adb logcat -d -v threadtime | grep -E "focusLoss|delayedFocusLossStop|removeCallbacks"
```

---

## 进一步优化方向

如果容忍机制仍无法完全解决问题，可能需要：

1. **状态机优化**：区分"预期的焦点切换"和"异常的焦点丢失"
2. **白名单机制**：对特定游戏包名的焦点切换不触发停止逻辑
3. **多阶段容忍**：根据当前执行阶段动态调整容忍时间
4. **焦点恢复检测**：主动查询前台应用，而不仅仅依赖事件回调

**AI 自行取证的数据**（无需开发者提供）：
- 多次测试的焦点丢失时长分布（`adb logcat -d -v time` 时间戳差值统计）
- 不同设备/系统版本的表现差异（同机型复测 + `getprop` 记录环境）
- 游戏启动的完整生命周期日志（`ActivityManager` / `WindowManager` 切片）

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
