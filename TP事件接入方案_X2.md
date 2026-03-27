# TP事件接入方案（X2）

## 1. 文档目的

本方案用于在 X2 眼镜上将 TP 手势接入现有 SensoryAI 前端交互，目标是：

1. 保持现有拍照识别、字幕、多模态业务逻辑不推翻。
2. 将 TP 事件升级为主输入层，触摸事件降级为兜底。
3. 明确每个手势在不同状态下的行为，避免歧义和误触。

## 2. 适用范围

- 设备：X2（不包含 X3 专属能力）
- 页面：MainActivity 主页面
- 交互对象：模式切换、录音启停、拍照触发、卡片关闭、页面退出

## 3. 改造原则

1. 最小改动：不重构核心识别链路。
2. 单一入口：所有 TP 手势先进入统一分发函数。
3. 状态优先：先判状态，再判动作，最后执行业务。
4. 防误触：节流、防抖、互斥保护必须同时上线。

## 4. 接入事件清单（X2 一期）

一期仅接入以下 6 类：

1. Click（单击）
2. DoubleClick（双击）
3. LongClick（长按）
4. SlideForward（前滑）
5. SlideBackward（后滑）
6. TpSlideContinuous（连续滑动，仅日志与阈值观测，不驱动主业务）

不在一期接入：

1. SlideUpwards / SlideDownwards
2. 双指点击/双指长按

## 5. 语义动作定义

先抽象语义动作，再映射具体手势：

1. ACTION_CONFIRM：确认/执行主操作
2. ACTION_CANCEL：取消/退出当前层
3. ACTION_NEXT_MODE：切到下一个模式
4. ACTION_PREV_MODE：切到上一个模式
5. ACTION_FORCE_CLOSE_CARD：强制关闭卡片
6. ACTION_NOOP：忽略

## 6. 状态机定义

基于当前代码变量定义 5 个状态：

1. IDLE_READY：空闲可触发
2. LISTENING：录音中
3. PROCESSING：处理中（识别/思考）
4. CARD_RESULT：结果卡片可读
5. MULTI_WAIT_QUESTION：多模态拍照后等待提问

状态判定变量：

- isMicEnabled
- isAnalyzing
- cardPhase
- currentMode
- isWaitingForQuestion

## 7. 事件动作矩阵

| 当前状态 | Click | DoubleClick | LongClick | SlideForward | SlideBackward |
|---|---|---|---|---|---|
| IDLE_READY | 触发主操作：PHOTO/MULTI=拍照；SUBTITLE=开麦 | 退出页面 | 无操作或轻提示 | 切下一个模式 | 切上一个模式 |
| LISTENING | 停止录音并进入 PROCESSING | 停止录音并退出页面 | 停止录音（不退出） | 忽略 | 忽略 |
| PROCESSING | 忽略（防重复触发） | 一期建议忽略（保守） | 一期建议忽略（保守） | 忽略 | 忽略 |
| CARD_RESULT | 单击可设为重播TTS或关闭卡片（二选一） | 关闭卡片并退出 | 强制关闭卡片 | 先关闭卡片再切下一个模式 | 先关闭卡片再切上一个模式 |
| MULTI_WAIT_QUESTION | 开麦提问 | 退出页面 | 取消多模态会话并回就绪 | 清理会话后切下一个模式 | 清理会话后切上一个模式 |

实现建议：一期将 CARD_RESULT 的 Click 固定为“关闭卡片”，避免歧义。

## 8. 前滑/后滑方向策略（自然/非自然）

文档已说明用户可设置自然/非自然映射。X2 一期先做可配置映射层，不直接把业务逻辑绑定 Forward/Backward。

建议新增配置项：

- tpNaturalMode（默认 true）

统一函数：

- resolveModeSwitchAction(action, tpNaturalMode)

规则：

- tpNaturalMode=true：Forward=NEXT，Backward=PREV
- tpNaturalMode=false：Forward=PREV，Backward=NEXT

## 9. 代码落点（文件级）

主改动文件：

- app/src/main/java/com/narc/arclient/MainActivity.java

建议新增方法：

1. initTpEventPipeline()
2. dispatchTpAction(action)
3. deriveUiState()
4. handleClickByState(state)
5. handleDoubleClickByState(state)
6. handleLongClickByState(state)
7. handleSlideByState(state, isForward)
8. switchModeWithCleanup(targetMode)
9. triggerPrimaryAction()
10. resolveModeSwitchAction(action, tpNaturalMode)

现有 onTouchEvent 处理策略：

- 保留作为兜底输入
- 主流程由 TP 管道驱动

## 9.1 模式按键焦点最小实现（本项目优先）

考虑当前项目阶段，焦点先仅覆盖“功能切换按键”，不扩展到其他区域。目标是最小改动拿到稳定收益。

**焦点范围（仅此三项）：**

1. 拍照识药模式按钮
2. 实时字幕模式按钮
3. 拍照追问模式按钮

**焦点切换规则：**

1. SlideForward：焦点移动到下一个模式按钮
2. SlideBackward：焦点移动到上一个模式按钮
3. Click：触发当前焦点模式切换（调用既有模式切换流程）
4. DoubleClick：退出页面（不走模式逻辑）
5. LongClick：一期不绑定业务动作，避免误触

**业务状态约束：**

1. 当 isModeLocked=true 时，允许焦点移动，但 Click 不执行模式切换，仅提示“请完成当前操作后再切换模式”
2. 当处于 LISTENING / PROCESSING 时，允许焦点移动，但 Click 不执行模式切换
3. 模式切换成功后，焦点索引必须同步到当前真实模式，避免“焦点与模式不一致”

**视觉反馈（最小实现）：**

1. 仅当前焦点按钮高亮（描边/亮度提升）
2. 非焦点按钮保持常态
3. 模式切换成功时，焦点按钮给一次短促反馈（轻微缩放或闪烁）

**建议新增方法（在 MainActivity 内）：**

1. initModeFocusZone()
2. moveModeFocus(next: Boolean)
3. applyModeFocusVisual(focusIndex)
4. confirmModeFocus()
5. syncFocusWithCurrentMode()

**验收补充（模式焦点专项）：**

1. 前滑/后滑 30 次，焦点顺序稳定、无跳变
2. 锁定状态下 Click 不切模式，仅提示
3. 任意时刻最终焦点与真实模式保持一致
4. DoubleClick 退出不受焦点状态影响

## 10. 防误触与并发保护

建议阈值：

1. Slide 切模式节流：800ms
2. Click 防抖：300ms
3. 事件去重：eventTime + actionType
4. 状态互斥：LISTENING 与 PROCESSING 不允许并发切换
5. 切模式保护：LISTENING/PROCESSING 阶段忽略滑动切模式

## 11. 日志与可观测性

统一日志前缀：

- [TP_IN] 原始事件
- [TP_STATE] 状态判定
- [TP_ACT] 动作决策
- [TP_EXEC] 业务执行结果
- [TP_DROP] 事件被忽略原因

建议每次处理至少记录：

1. action 名称
2. 当前状态
3. 决策动作
4. 是否执行成功
5. 忽略原因（如节流/状态不允许）

## 12. 验收清单

### 12.1 功能验收

1. IDLE_READY：Click 在不同模式行为正确。
2. LISTENING：Click/LongClick 均能停止录音。
3. PROCESSING：任何滑动不切模式。
4. CARD_RESULT：Slide 先关卡再切模式。
5. MULTI_WAIT_QUESTION：Click 可开麦提问。
6. DoubleClick：符合“退出”预期且不留脏状态。
7. 模式焦点专项：仅模式按钮参与焦点，其他区域不参与焦点切换。

### 12.2 稳定性验收

1. 高频滑动不导致模式乱跳。
2. 连续点击不触发重复拍照/重复开麦。
3. 退出页面后无录音残留。
4. 卡片状态与内部状态变量一致。

## 13. 实施排期（建议）

### D1 上午

1. 建立 TP 管道与状态分发骨架
2. 接入日志和节流框架

### D1 下午

1. 完成 Click/DoubleClick/Slide 动作矩阵
2. 联调模式切换与主操作触发

### D2 上午

1. 完成 LongClick 和卡片态规则
2. 增加自然/非自然映射配置

### D2 下午

1. 完成回归测试
2. 调整阈值并形成最终参数

## 14. 一期完成定义（Definition of Done）

满足以下条件即视为 TP 一期完成：

1. 手势行为符合事件矩阵。
2. 状态切换可复现、可解释、可追踪。
3. 无关键误触与重复触发问题。
4. 不引入现有识别链路回归。
5. 验收清单通过率 100%。
