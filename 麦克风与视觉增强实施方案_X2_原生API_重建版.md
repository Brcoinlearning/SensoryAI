# 麦克风与视觉增强实施方案（X2，原生API）

## 1. 文档目的

在不引入 TP 改造、不涉及 X3 分支的前提下，基于 Android 原生 API 对现有项目进行两项优化：

1. 麦克风链路优化（收音模式、稳定性、可观测性）
2. 视觉增强（状态栏、卡片、字幕层级与可读性）

## 2. 实施原则

1. 不改现有主业务闭环：拍照/录音/识别/展示流程保持一致。
2. 先可观测再优化：每步都补日志和验收点。
3. 每步可回滚：开关化与幂等收尾。
4. 仅使用原生 API 做核心改造。

---

## 3. 麦克风优化（X2）

### M1 录音创建路径统一

**目标**

统一 AudioRecord 的构建方式，确保参数与行为一致可控。

**原生 API**

1. `AudioRecord.Builder`
2. `AudioFormat.Builder`
3. `MediaRecorder.AudioSource`

**步骤**

1. 统一通过 `AudioRecord.Builder` 创建录音对象。
2. 固定参数：16k、mono、PCM16。
3. 录音启动前记录 `bufferSize`、`state`、`audioSessionId`。

**验收**

1. 启动日志包含固定采样参数。
2. 仅在 `STATE_INITIALIZED` 时进入录音循环。
3. 无随机初始化失败波动。

---

### M2 X2 收音模式参数前置

**目标**

按业务模式切换 X2 收音模式，并在停止时复位。

**原生 API**

1. `AudioManager`
2. `Context.getSystemService(Context.AUDIO_SERVICE)`
3. `AudioManager.setParameters(String)`

**步骤**

1. 定义模式映射：
   - SUBTITLE -> `audio_source_record=translation`
   - MULTI -> `audio_source_record=voiceassistant`
2. 录音开始前先调用 `setParameters(...)`。
3. 录音停止后统一调用 `audio_source_record=off`。
4. 参数失败时降级默认录音，不阻断流程。

**验收**

1. 日志顺序为：模式判定 -> 参数设置 -> 录音启动。
2. 停止后必有 `off` 复位日志。
3. 参数设置失败时功能可用且有明确错误日志。

---

### M3 音频效果器按能力启用

**目标**

在设备支持时启用抑噪/增益/回声处理，提升识别稳定性。

**原生 API**

1. `NoiseSuppressor`
2. `AutomaticGainControl`
3. `AcousticEchoCanceler`

**步骤**

1. 使用 `isAvailable()` 判断支持能力。
2. 基于 `audioSessionId` 创建效果器实例并启用。
3. 停止录音时统一 `release()`。

**验收**

1. 支持设备上有启用日志。
2. 不支持设备不报错、不崩溃。
3. 资源释放后可重复启停。

---

### M4 录音路由与状态可观测

**目标**

掌握实际输入设备和录音配置变化，便于定位问题。

**原生 API**

1. `AudioManager.getDevices(AudioManager.GET_DEVICES_INPUTS)`
2. `AudioManager.AudioRecordingCallback`
3. `AudioRecordingConfiguration`

**步骤**

1. 录音启动时打印当前输入设备列表。
2. 注册 `AudioRecordingCallback` 监听配置变化。
3. 页面销毁或 stop 时反注册 callback。

**验收**

1. 能看到录音配置变化日志。
2. 退出页面后 callback 无泄漏。
3. 录音异常可追溯到设备路由层。

---

### M5 异常与收尾统一

**目标**

确保所有退出路径都释放资源并复位参数。

**原生 API**

1. `AudioRecord.stop()`
2. `AudioRecord.release()`
3. `Thread.join()`

**步骤**

1. 建立统一 `stopAndReset()`：
   - 停读线程
   - stop/release AudioRecord
   - setParameters(off)
2. 手动停录、自动停录、异常停录全部走统一入口。
3. `stopAndReset()` 做幂等保护。

**验收**

1. 连续 20 次启停无占用残留。
2. 页面重进首次录音成功。
3. 无重复结束帧与异常堆栈。

---

## 4. 视觉增强（原生API）

### V1 状态栏三态统一

**目标**

统一 LISTENING / PROCESSING / IDLE 的视觉表达，避免混态覆盖。

**原生 API**

1. `ValueAnimator`
2. `ObjectAnimator`
3. `AnimatorSet`
4. `ViewPropertyAnimator`

**步骤**

1. 建立单一状态驱动函数处理图标、文案、动画。
2. 状态切换先 cancel 上一个 animator。
3. 保证 processing 动画不会被静态图标覆盖。

**验收**

1. 状态切换无动画叠层与闪烁。
2. 图标与文案匹配状态。
3. 高频切换下无错态。

---

### V2 卡片等待态/结果态分离

**目标**

等待态与结果态视觉清晰可区分，并规避竞态回退。

**原生 API**

1. `TransitionManager.beginDelayedTransition(...)`
2. `Fade`
3. `ChangeBounds`
4. `View.setVisibility(...)`

**步骤**

1. 等待态显示 waiting，隐藏正文。
2. 结果态隐藏 waiting，显示正文。
3. 统一切换入口并增加“结果优先”保护。

**验收**

1. 高频触发下不出现结果回退等待态。
2. 卡片切换平滑，无明显闪屏。

---

### V3 层级与景深增强

**目标**

增强信息层次，但避免眩晕和过度效果。

**原生 API**

1. `View.setElevation(...)`
2. `View.setTranslationZ(...)`
3. `ViewOutlineProvider`
4. `RenderEffect`（可用时启用）

**步骤**

1. 固定状态栏、卡片、字幕层级关系。
2. 结果卡片略高于等待态 elevation。
3. 可用设备上给背景加轻量模糊（参数可关）。

**验收**

1. 组件遮挡关系稳定。
2. 可读性提升，无明显眩晕反馈。
3. 不支持 RenderEffect 的设备平稳降级。

---

### V4 字幕与 Toast 防遮挡

**目标**

保证字幕、Toast 与卡片同时存在时不互相遮挡。

**原生 API**

1. `WindowInsets`
2. `ViewGroup.MarginLayoutParams`
3. `Constraint/FrameLayout` 原生布局参数

**步骤**

1. 基于 insets 计算底部安全距离。
2. 固定字幕与 Toast 的垂直间隔规则。
3. 同时显示时执行优先级与偏移策略。

**验收**

1. 同时触发时无重叠。
2. 各分辨率和系统栏状态下布局稳定。

---

### V5 帧稳定与性能守护

**目标**

防止视觉增强导致交互卡顿。

**原生 API**

1. `Choreographer.postFrameCallback(...)`
2. `android.os.Trace`
3. `FrameMetricsAggregator`（可选）

**步骤**

1. 对状态切换与卡片切换打帧耗时采样。
2. 超阈值时下调阴影/模糊强度。
3. 保留性能开关用于快速回退。

**验收**

1. 关键交互无明显掉帧。
2. 帧耗时日志可用于回归对比。

---

## 5. 联合验收清单

1. 麦克风链路：模式映射正确、启停稳定、异常可收尾、off 复位稳定。
2. 视觉链路：三态一致、卡片不串态、字幕/Toast 不遮挡、无明显卡顿。
3. 稳定性：无新增崩溃、无资源泄漏、无不可恢复状态。

## 6. 建议执行顺序

1. 先完成 M1 -> M5（先稳定输入）。
2. 再完成 V1 -> V5（再增强输出）。
3. 每完成一步，立即执行该步验收后再进入下一步。
