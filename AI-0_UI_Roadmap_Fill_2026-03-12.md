# AI-0（总控）交付：总路线图补齐项（SensoryAI / AR 眼镜端 UI）

**Date**：2026-03-12  
**范围**：只覆盖 AR 眼镜端 UI 视觉与信息呈现优化（卡片/字幕/状态条/渲染按钮与光标/Toast）。  
**约束**：本交付为“只读分析 + 可执行任务拆解”，不修改任何代码。

2026-03-13 口径更新（请后续执行 AI 以此为准）：

- 验收设备：当前只验收 X2。
- 优先级：优先做“更精美 + 更像成品 + 动效节奏”。
- P0-1 黑块伪影（阶段 1A）改为按需：若 X2 真机未复现可先跳过，优先推进阶段 1B + 阶段 3。
- 动效护栏：除 Listening/Processing 外禁止无意义常驻循环；任何循环必须有停止条件。

---

## 交付摘要（对照交接框架）

- 完成了什么：输出 A/B/C/D 四个必交付物（架构图、黑块决策树、配色覆盖清单、30–120m 任务卡）。
- 没做什么（明确排除项）：未修改任何工程文件；未跑构建/单测；未新增任何资源或 token。
- 修改的文件：无（只读交付）。
- 如何验收（对照 baseline 截图）：
  - 伪影方向：按 B 的“归因切分”先证明 Render vs View，再在同场景补拍 After 截图对照（拍照追问1/2）。
  - 配色方向：对照 baseline（拍照追问1、药品识别），确认 After 不再出现紫/橙/洋红/黄等割裂色。
- 风险点：
  - A3 的层级推论需要执行侧用 1 次“只看 Render / 只看 View”开关验证；不要仅凭推论开大改。
  - `setShadowLayer` 相关验证可能引入性能/观感波动，应先做临时开关验证再决定长期方案。
  - token 化时要避免把“阴影 alpha/黑色投影”强行语义化，先收口主色体系即可。

---

## A. 工程 UI 架构图（文本版）

### A1. 分层总览（View 层 vs Render 层）

- **View 层（XML + 自定义 View）**
  - 布局入口：[app/src/main/res/layout/activity_main.xml](app/src/main/res/layout/activity_main.xml)
  - 卡片（结果/进度）：include [app/src/main/res/layout/item_ar_card.xml](app/src/main/res/layout/item_ar_card.xml)
  - 顶部状态条：`status_indicator`（背景 [app/src/main/res/drawable/bg_status_bar.xml](app/src/main/res/drawable/bg_status_bar.xml)）
  - 实时字幕：自定义 View [app/src/main/java/com/narc/arclient/ui/SubtitleStreamView.java](app/src/main/java/com/narc/arclient/ui/SubtitleStreamView.java)
  - AR Toast：自定义 View [app/src/main/java/com/narc/arclient/ui/ArToastView.java](app/src/main/java/com/narc/arclient/ui/ArToastView.java) + 布局 [app/src/main/res/layout/ar_toast.xml](app/src/main/res/layout/ar_toast.xml)
  - 样式/token：
    - token：[app/src/main/res/values/design_system.xml](app/src/main/res/values/design_system.xml)
    - text appearance：[app/src/main/res/values/styles.xml](app/src/main/res/values/styles.xml)

- **Render 层（Canvas 连续绘制）**
  - 渲染入口：`MainActivity.CustomDrawView.onDraw()` → `RenderProcessor.getInstance().draw(canvas)`，并 `postInvalidateOnAnimation()` 持续刷新
    - [app/src/main/java/com/narc/arclient/MainActivity.java](app/src/main/java/com/narc/arclient/MainActivity.java)
  - 绘制实现：
    - [app/src/main/java/com/narc/arclient/process/processor/RenderProcessor.java](app/src/main/java/com/narc/arclient/process/processor/RenderProcessor.java)

### A2. 关键类与职责（可用于后续 AI 定位改动点）

- **`MainActivity`（UI 编排 + 状态机 + 双眼同步入口）**
  - 继承 `BaseMirrorActivity<ActivityMainBinding>`：View 层通过 `mBindingPair.updateView(...)` 更新（用于“合目镜像/双眼同步”）
  - 典型 UI 更新点：
    - 状态条：`updateStatus(msg, iconRes)`（同时更新 `tv_status` 与 `iv_status_icon` tint）
    - 字幕：`updateSubtitle(text, isFinal)` → `binding.subtitleView.updateSubtitle(...)`
    - Toast：`showArToast(message, icon)` → `binding.arToastView.show(...)`
    - 卡片：`startCardSequence()` / `closeCard()` / `setCardText(title, content, color)`
  - Render 数据注入：`updateView(RenderData, RecognizeTask)` 会把 `RenderData` 喂给 `RenderProcessor`，同时根据手势/模式驱动卡片位置与显示逻辑

- **`SubtitleStreamView`（字幕：流式打字 + 自动淡出）**
  - 背景：`bg_subtitle_premium`
  - 关键视觉策略：partial 文本用 `text_secondary`，final 用 `text_primary`，并带轻微 `setShadowLayer(2f, 0f, 1f, 0x80000000)`

- **`ArToastView`（底部浮窗 Toast：淡入淡出 + 自动隐藏）**
  - 背景使用 `bg_subtitle_premium`（与字幕同风格）

- **`RenderProcessor`（渲染：按钮/光标/进度/关闭读条）**
  - `draw(canvas)` 内部 **显式把 canvas 拆成左右两半**：
    - `drawEye(canvas, 0, halfW, h, true)`（左眼）
    - `drawEye(canvas, halfW, halfW, h, false)`（右眼）
  - 交互检测：仅在 `isLeftEye && !isLocked` 分支做按钮碰撞/悬停计时，触发回调 `OnMicStatusListener` / `OnModeSwitchListener`
  - 绘制元素（每眼）：
    - 麦克风按钮（圆形 + 白环 + 阴影 + 录音态 stop 方块）
    - 模式切换按钮（圆形 + 不同模式底色 + 文本/emoji）
    - 指尖光标（白描边 + 柔和光晕 + 进度弧线 + 张手关闭读条）

### A3. UI 层级（Z-Order）结论（与“黑块伪影”强相关）

从代码调用顺序看：

1. View 层通过 `BaseMirrorActivity` / `setContentView`（由 SDK 内部完成）承载 `activity_main.xml`。
2. 随后 `MainActivity.initCustomView()` 使用 `addContentView(customDrawView, MATCH_PARENT)` 把 `CustomDrawView` 叠到 Window 内容之上。

**重要推论**：
- `CustomDrawView` 很可能位于 **所有 XML View 之上**。
- 即使卡片 `bringToFront()`，也只能在同一 `root_eye_container` 内部置顶，**无法盖住 `CustomDrawView`**。

这意味着：
- “看起来出现在卡片内部的黑块”，可能实际来自 Render 层（因为 Render 层覆盖在卡片上）。

### A4. 双眼镜像（合目）机制：工程结论

- **View 层**：
  - `activity_main.xml` 注释写明“单套组件，BaseMirrorActivity 会负责双眼镜像”。
  - `MainActivity` 的字幕/卡片/状态条/Toast 更新均走 `mBindingPair.updateView(...)`，可认为 SDK 将同一 binding 同步到左右眼 View。

- **Render 层**：
  - `RenderProcessor.draw(canvas)` 已经按左右半屏分别绘制（drawEye），因此 Render 层“双眼”不依赖 `mBindingPair`。

---

## B. P0-黑块伪影定位决策树（≥6 分支；每分支含验证动作/临时开关/预期现象/结论）

> 目标：先“归因切分”证明问题来自 Render 还是 View，再做最小修复。黑块伪影 baseline 见：
> - [实机图片/拍照追问1.png](实机图片/拍照追问1.png)
> - [实机图片/拍照追问2.png](实机图片/拍照追问2.png)

### B0. 先记录现象特征（不改代码）

- 黑块是否：
  - 跟随手指移动？（像 cursor/按钮阴影）
  - 固定在卡片内部某些位置？（像 View 的阴影/遮罩残留）
  - 只在动画期间出现？（fade in/out 合成问题）
  - 只在录音/分析/锁定时出现？（RenderProcessor 的 stopRect / lock 状态相关）

### B1. 第一步：归因切分（Render vs View）

#### 分支 1：临时开关 A —— 禁用 Render 绘制（只保留 View）
- **验证动作**：在 `MainActivity.CustomDrawView.onDraw()` 加一个临时布尔开关（例如 `DEBUG_DISABLE_RENDER`）
- **临时代码开关位置**：[app/src/main/java/com/narc/arclient/MainActivity.java](app/src/main/java/com/narc/arclient/MainActivity.java)
- **预期现象**：
  - 若黑块依旧出现：更偏向 View 层/合成层问题
  - 若黑块消失：更偏向 Render 层问题（尤其考虑 `setShadowLayer` / 清屏）
- **结论**：确定“是否必须进 Render 排查”

#### 分支 2：临时开关 B —— 隐藏 View 层内容（只看 Render）
- **验证动作**：在同场景中把 `include_ar_card` / `subtitle_view` / `status_indicator` 全部 `GONE`（可在代码里临时强制，也可在 XML 临时改）
- **验证点文件**：[app/src/main/res/layout/activity_main.xml](app/src/main/res/layout/activity_main.xml)
- **预期现象**：
  - 若黑块仍出现：Render 层为主嫌疑
  - 若黑块只在卡片出现：View 卡片相关（elevation/背景/动画/遮罩）
- **结论**：确认“黑块是否依赖卡片 View 出现”

> 进入后续分支时，以 B1 的结论决定走 Render 路径或 View 路径。

---

### B2. Render 路径（若 B1 指向 Render）

#### 分支 3：清屏/残影验证 —— 每帧是否存在“上帧脏像素残留”
- **验证动作**：在 `CustomDrawView.onDraw()` 开头临时加入透明清屏（示意：`canvas.drawColor(Color.TRANSPARENT, PorterDuff.Mode.CLEAR)`）
- **临时代码开关位置**：[app/src/main/java/com/narc/arclient/MainActivity.java](app/src/main/java/com/narc/arclient/MainActivity.java)
- **预期现象**：
  - 若黑块显著减少/消失：高度怀疑“透明层未正确清空 + 连续绘制造成残影/脏矩形”
- **结论**：最小修复方向是“明确清屏”或“让 CustomDrawView 有稳定背景清除”

#### 分支 4：ShadowLayer GPU 伪影验证 —— `setShadowLayer` 是否在特定设备合成链路触发方块
- **验证动作**：临时关闭 RenderProcessor 里所有 `setShadowLayer(...)`（尤其按钮阴影/高光相关 paint）
- **验证点文件**：[app/src/main/java/com/narc/arclient/process/processor/RenderProcessor.java](app/src/main/java/com/narc/arclient/process/processor/RenderProcessor.java)
- **预期现象**：
  - 若黑块消失但阴影也没了：根因大概率是 GPU/驱动对 shadow layer 的渲染缺陷
- **结论**：最小修复可选：
  - 用“非 shadowLayer”的方式模拟阴影（半透明扩圈/模糊 bitmap）
  - 或对 `CustomDrawView`/特定元素启用 software layer（代价：性能）

#### 分支 5：软件渲染验证 —— 强制 `CustomDrawView` 使用 software layer
- **验证动作**：临时 `customDrawView.setLayerType(LAYER_TYPE_SOFTWARE, null)`
- **验证点文件**：[app/src/main/java/com/narc/arclient/MainActivity.java](app/src/main/java/com/narc/arclient/MainActivity.java)
- **预期现象**：
  - 若黑块消失：进一步证明是硬件加速路径（shadow/alpha/clip）导致
- **结论**：后续修复优先尝试“去 shadowLayer/改阴影实现”，避免长期 software layer

---

### B3. View 路径（若 B1 指向 View）

#### 分支 6：卡片 elevation 阴影验证 —— `android:elevation` 是否触发黑块
- **验证动作**：临时把卡片 elevation 置 0（`item_ar_card.xml` 当前为 `@dimen/elevation_md`）
- **验证点文件**：[app/src/main/res/layout/item_ar_card.xml](app/src/main/res/layout/item_ar_card.xml)
- **预期现象**：
  - 若黑块消失：怀疑某些设备对 elevation/shadow 的合成存在 artifact
- **结论**：最小修复方向：降低/移除 elevation，改用 drawable 边框/轻微高光表达层级

#### 分支 7：卡片背景 layer-list/渐变验证 —— 半透明渐变是否导致合成块
- **验证动作**：临时把卡片背景从 `bg_card_premium` 换成纯色 shape（无 gradient、无多层叠加）
- **验证点文件**：[app/src/main/res/drawable/bg_card_premium.xml](app/src/main/res/drawable/bg_card_premium.xml)
- **预期现象**：
  - 若黑块消失：怀疑 layer-list + 透明渐变在该渲染路径下出现 banding/块状合成
- **结论**：最小修修复方向：简化 layer-list，或把渐变颜色收口成 token 并降低层数

#### 分支 8：调试遮罩/面板误开验证 —— `debug_panel` / overlay 是否在真机被误显示
- **验证动作**：在真机流程中打印/监控 `R.id.debug_panel` 的 visibility；或临时在非 emulator 路径强制 `GONE`
- **验证点文件**：
  - 布局：[app/src/main/res/layout/activity_main.xml](app/src/main/res/layout/activity_main.xml)
  - 逻辑：[app/src/main/java/com/narc/arclient/MainActivity.java](app/src/main/java/com/narc/arclient/MainActivity.java)
- **预期现象**：
  - 若黑块区域与 debug_panel 背景（`@color/overlay_dim`）一致：说明有误触发/状态残留
- **结论**：最小修复方向：保证 release 路径彻底不可见/不可点击，并移除任何“误置顶”逻辑

---

## C. 配色收口覆盖面清单（Render vs View）

> 目标：将“按钮/光标/状态提示”的颜色体系收口到设计系统青绿体系；红色仅用于危险/停止/错误语义。

### C1. Render 层（RenderProcessor）非 token/硬编码色清单（必须覆盖）

文件：[app/src/main/java/com/narc/arclient/process/processor/RenderProcessor.java](app/src/main/java/com/narc/arclient/process/processor/RenderProcessor.java)

- **指尖进度弧线**：`#FF00FF`（洋红，`paintCursorProgress`）
- **锁定态进度色**：`#AAAAAA`（灰，锁定时覆盖 `paintCursorProgress`）
- **张手关闭读条**：`#FF3B30`（红，`paintCloseProgress`）
- **麦克风/停止按钮主填充**：`#FF3B30`（红，`paintRedFill`）
- **悬停触发读条**：`#FFD600`（黄，`paintBtnHover`）
- **模式切换按钮底色**：
  - `#FF9500`（橙，拍照识药）
  - `#00C7BE`（青，实时字幕）
  - `#AF52DE`（紫，拍照追问）
- **阴影/投影相关**：多处 `setShadowLayer(..., 0x33_000000)` + `paintBtnShadow` 用 `Color.BLACK`（这些也会影响“黑块伪影”）

> 结论：Render 层是“颜色割裂”的核心来源之一；且 shadow/黑色 alpha 也是“黑块伪影”的高概率触发点。

### C2. View 层（XML/Java）可能造成“颜色割裂或不可控”的点

- **MainActivity 中的硬编码色**
  - `updateView(...)` 中对视觉识别成功分支使用了 `Color.GREEN`（非 token）
    - 文件：[app/src/main/java/com/narc/arclient/MainActivity.java](app/src/main/java/com/narc/arclient/MainActivity.java)

- **字幕阴影色硬编码**
  - `SubtitleStreamView` 使用 `textView.setShadowLayer(..., 0x80000000)`（非 token，但语义明确：文字阴影）
    - 文件：[app/src/main/java/com/narc/arclient/ui/SubtitleStreamView.java](app/src/main/java/com/narc/arclient/ui/SubtitleStreamView.java)

- **卡片/字幕背景里的硬编码渐变色（透明度叠加）**
  - `bg_card_premium.xml`：`#10415A77/#08415A77/#00415A77`
  - `bg_subtitle_premium.xml`：`#18415A77/#00415A77`

- **疑似历史遗留（未发现引用，但建议确认）**
  - [app/src/main/res/values/colors.xml](app/src/main/res/values/colors.xml) 中仍有 `purple_*` / `teal_*`（模板色）
  - [app/src/main/res/drawable/bg_card_rounded.xml](app/src/main/res/drawable/bg_card_rounded.xml)、[app/src/main/res/drawable/bg_subtitle_bubble.xml](app/src/main/res/drawable/bg_subtitle_bubble.xml)、[app/src/main/res/drawable/bg_text_tag.xml](app/src/main/res/drawable/bg_text_tag.xml) 使用了硬编码半透明黑/白

> 结论：View 层总体已经较多走 `design_system.xml` token，但仍存在零散硬编码色与历史遗留资源，需在“配色收口”阶段一并确认。

---

## D. 阶段拆解为“可直接开改”的任务卡（30–120 分钟粒度）

> 说明：任务卡按“先定位/最小改动修复 P0，再做 P1+”顺序排列；每张卡都写了目标、改动文件、改动点、回归点、验收截图。

### D0（通用）验收与回归（每个执行 AI 都先做）

**卡 D0-1（45–60min）建立验收表与截图清单**
- 目标：形成可打勾的验收表（P0/P1），并明确 After 截图命名/目录
- 文件：不改代码，仅新增/更新文档（由执行 AI 产出）
- 回归点：N/A
- 验收截图：四场景 baseline（实时字幕/拍照追问1/拍照追问2/药品识别）

---

### D1A（P0-1）黑块伪影：定位→最小修复

**卡 D1A-1（30–45min）加入“归因切分”临时开关：Render off / View off**
- 目标：用开关证明黑块来自 Render 还是 View（必须先做）
- 改动文件（后续执行 AI 改）：
  - [app/src/main/java/com/narc/arclient/MainActivity.java](app/src/main/java/com/narc/arclient/MainActivity.java)
  - [app/src/main/res/layout/activity_main.xml](app/src/main/res/layout/activity_main.xml)（可选）
- 改动点：
  - `CustomDrawView.onDraw()` 增加 `DEBUG_DISABLE_RENDER`
  - 增加一个临时路径隐藏 card/subtitle/status/toast（可通过一键函数）
- 回归点：按钮/光标是否仍正常显示（Render on 时）
- 验收截图：对照 [实机图片/拍照追问1.png](实机图片/拍照追问1.png)、[实机图片/拍照追问2.png](实机图片/拍照追问2.png)

**卡 D1A-2（45–90min）Render 清屏验证：透明层残影排查**
- 目标：排除/确认“上帧残留导致块状伪影”
- 改动文件：
  - [app/src/main/java/com/narc/arclient/MainActivity.java](app/src/main/java/com/narc/arclient/MainActivity.java)
- 改动点：在 `CustomDrawView.onDraw()` 开头加入可开关的透明清屏
- 回归点：检查是否引入闪烁/性能下降
- 验收截图：同 baseline 两场景

**卡 D1A-3（60–120min）Render shadowLayer 验证与最小替代**
- 目标：确认 `setShadowLayer` 是否触发黑块；若是，给出最小替代策略（不做“美化”）
- 改动文件：
  - [app/src/main/java/com/narc/arclient/process/processor/RenderProcessor.java](app/src/main/java/com/narc/arclient/process/processor/RenderProcessor.java)
- 改动点：
  - 临时开关禁用所有 `setShadowLayer`
  - 若确认根因：把阴影替换为“低 alpha 扩圈/描边”
- 回归点：按钮可辨识性是否下降
- 验收截图：同 baseline 两场景

**卡 D1A-4（45–90min）View 卡片 elevation 验证**
- 目标：确认 `android:elevation` 是否导致黑块
- 改动文件：
  - [app/src/main/res/layout/item_ar_card.xml](app/src/main/res/layout/item_ar_card.xml)
- 改动点：临时把 elevation 置 0 或降低一档
- 回归点：卡片层级是否仍清晰
- 验收截图：同 baseline 两场景

**卡 D1A-5（60–90min）卡片背景 layer-list 简化验证**
- 目标：确认 layer-list/渐变叠加是否导致块状合成
- 改动文件：
  - [app/src/main/res/drawable/bg_card_premium.xml](app/src/main/res/drawable/bg_card_premium.xml)
- 改动点：临时替换为纯色 + 描边（无渐变）
- 回归点：卡片质感变化是否可接受（此阶段只看“伪影是否消失”）
- 验收截图：同 baseline 两场景

---

### D1B（P0-2）配色收口：Render 优先 + 资源化

**卡 D1B-1（45–60min）产出语义色映射表（默认/聚焦/按下/禁用/成功/警告/错误/处理中/聆听）**
- 目标：建立后续所有颜色替换的“唯一真相表”
- 输入：
  - [DESIGN_SYSTEM.md](DESIGN_SYSTEM.md)
  - [app/src/main/res/values/design_system.xml](app/src/main/res/values/design_system.xml)
- 输出：表格（执行 AI 的交付物）
- 验收：表格能覆盖 Render 与 View 的主要状态（至少包含按钮/光标/状态条/Toast/字幕），且每个语义色都能指向具体 token。

**卡 D1B-2（60–120min）替换 RenderProcessor 的橙/紫/洋红/黄为 token（第一轮：先不做状态细分）**
- 目标：先“消灭紫/橙/洋红/黄”这四类割裂色（P0 观感收口最大）
- 改动文件：
  - [app/src/main/java/com/narc/arclient/process/processor/RenderProcessor.java](app/src/main/java/com/narc/arclient/process/processor/RenderProcessor.java)
  - [app/src/main/res/values/design_system.xml](app/src/main/res/values/design_system.xml)（如需要新增少量 token）
- 改动点：
  - 模式按钮底色：全部收口到青绿体系（用明度/透明度区分模式）
  - 指尖进度：从洋红改为青绿强调色
  - 悬停读条：从黄改为青绿强调色（或更浅的 mint）
- 回归点：不同模式仍可区分（通过文字/图标/描边，而非色相）
- 验收截图：对照 baseline，说明 After 哪些颜色“消失”（紫/橙/洋红/黄）

**卡 D1B-3（30–60min）View 层硬编码色清理：Color.GREEN → token**
- 目标：避免 View 层继续产生“另一套色语义”
- 改动文件：
  - [app/src/main/java/com/narc/arclient/MainActivity.java](app/src/main/java/com/narc/arclient/MainActivity.java)
- 改动点：将 `Color.GREEN` 替换为 `R.color.status_success` 或语义表指定 token
- 回归点：识别成功卡片的标题色仍清晰

---

### D2（遮挡与信息密度）卡片分页/最大高度/同屏规则

**卡 D2-1（60–120min）卡片最大高度策略（先不做分页，只做不遮挡）**
- 目标：避免长结果卡片覆盖视野中心过大面积
- 改动文件：
  - [app/src/main/res/layout/item_ar_card.xml](app/src/main/res/layout/item_ar_card.xml)
  - [app/src/main/java/com/narc/arclient/MainActivity.java](app/src/main/java/com/narc/arclient/MainActivity.java)（如需配合测量/定位）
- 改动点：限制内容区域高度（例如 `maxHeight`/滚动容器），保证结论段可见
- 回归点：短内容卡片不受影响
- 验收截图：对照 [实机图片/拍照追问2.png](实机图片/拍照追问2.png)

**卡 D2-2（60–120min）同屏规则：卡片为主时字幕降权**
- 目标：解决“底部字幕与中心卡片抢注意力”
- 改动文件：
  - [app/src/main/java/com/narc/arclient/MainActivity.java](app/src/main/java/com/narc/arclient/MainActivity.java)
  - [app/src/main/java/com/narc/arclient/ui/SubtitleStreamView.java](app/src/main/java/com/narc/arclient/ui/SubtitleStreamView.java)（如需暴露降权接口）
- 改动点：当 `include_ar_card` 可见且处于关键状态时，字幕减少停留/降低 alpha
- 回归点：实时字幕模式下不应被误伤
- 验收截图：同场景对照（卡片可见时字幕降权；无卡片时字幕正常）。

---

### D3（质感精修）文本对比与容器一致性

**卡 D3-1（45–90min）文本层级再拉开一档（正文 vs 次要文字）**
- 目标：提升复杂背景下可读性（克制，不做大光晕）
- 改动文件：
  - [app/src/main/res/values/design_system.xml](app/src/main/res/values/design_system.xml)
  - [app/src/main/res/values/styles.xml](app/src/main/res/values/styles.xml)
- 改动点：微调 `text_secondary` 或 subtitle/secondary 的应用策略
- 回归点：整体气质不变（仍然“低噪”）
- 验收截图：至少 2 张（复杂纹理背景 + 亮背景），对照 baseline 确认正文与次要文字区分更稳定。

**卡 D3-2（45–90min）容器风格统一：边框/分割线透明度一致性检查**
- 目标：卡片/字幕/状态条边框语言一致
- 改动文件：
  - [app/src/main/res/drawable/bg_card_premium.xml](app/src/main/res/drawable/bg_card_premium.xml)
  - [app/src/main/res/drawable/bg_subtitle_premium.xml](app/src/main/res/drawable/bg_subtitle_premium.xml)
  - [app/src/main/res/drawable/bg_status_bar.xml](app/src/main/res/drawable/bg_status_bar.xml)
- 改动点：边框透明度统一引用 token（如可行）
- 验收截图：同一场景下卡片/字幕/状态条同时出现时，边框风格一致且不“各一套”。

---

### D4（可持续化）token 映射与发版 QA

**卡 D4-1（60–120min）建立 token → 组件映射表（文档）**
- 目标：后续改动不再“手写颜色/尺寸”
- 输入：
  - [app/src/main/res/values/design_system.xml](app/src/main/res/values/design_system.xml)
  - [app/src/main/res/values/styles.xml](app/src/main/res/values/styles.xml)
  - [app/src/main/java/com/narc/arclient/process/processor/RenderProcessor.java](app/src/main/java/com/narc/arclient/process/processor/RenderProcessor.java)
- 输出：表格（token、语义、用在哪些组件、允许的状态）
- 验收：表格可直接用于代码 review（能定位到资源名/样式名/绘制分支）。

**卡 D4-2（60–120min）建立 AR 实机发版 QA 清单（文档）**
- 目标：每次发版必跑：颜色语义/遮挡/可读性/伪影/边缘裁切/同屏竞争
- 输出：表格（验收项/场景/如何拍图/Pass 标准/Baseline）
- 验收：QA 清单能在 10–15 分钟内跑完，并能产出 4 张关键场景 After 截图。

---

## 构建与验证（交付要求提醒）

每个执行 AI 交付必须提供至少一个可跑命令，并说明期望结果：
- `./gradlew :app:assembleDebug`（期望：编译通过）
- `./gradlew test`（期望：单测通过，如存在）
