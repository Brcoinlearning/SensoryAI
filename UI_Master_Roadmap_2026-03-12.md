# UI 总路线图（母文档）：一个 AI 总控读全工程，多 AI 分阶段落地（SensoryAI）

**Date**：2026-03-12
**范围**：只覆盖 AR 眼镜端 UI 视觉与信息呈现优化（卡片/字幕/状态条/渲染按钮与光标/Toast）。

文档定位（请务必读）：

- 这份文件的作用是“工程事实 + 分阶段框架 + 交付模板”。
- **不要从本文件复制提示词给 AI**。请只从 UI_Owner_Guide_2026-03-13.md 复制各阶段提示词块；AI 再按提示词自行打开本文件阅读。

---

## 0. 已有材料（直接作为输入，不需要重新产出）

- 你（人类）只读入口与“唯一复制入口”：UI_Owner_Guide_2026-03-13.md
- 执行侧权威入口（AI 自行打开读）：UI_Optimization_Plan_Agent_Onboarding_2026-03-12.md（含证据基线、参考材料、验收原则、任务卡）
- 快速收益执行清单：UI_Quick_Wins_Execution_Plan_2026-03-12.md

---

## 0.1 参考体系与 Skill 优先级（避免用错标准）

本项目是 AR 眼镜 UI，Material Design 可以参考“通用交互成熟度”，但不能覆盖 AR 场景硬约束。

优先级（从高到低）：

- 第一优先级（必须服从）：设备/AR 约束与可读性安全边界 → [AR眼镜的设计规范.txt](AR眼镜的设计规范.txt)
- 第二优先级（必须对齐）：本项目设计系统 token 与风格一致性 → [DESIGN_SYSTEM.md](DESIGN_SYSTEM.md) + [app/src/main/res/values/design_system.xml](app/src/main/res/values/design_system.xml)
- 第三优先级（辅助参考）：google-material-design skill
  - 适用阶段：阶段 2（遮挡/信息密度/结构）、阶段 3（质感精修/动效节奏）
  - 不适用：阶段 1 的 P0（黑块伪影、渲染按钮配色收口）——P0 以“工程事实 + token 收口 + 实机验收”为准

2026-03-13 更新口径（单一事实源）：

- 人类只读入口与唯一复制入口：UI_Owner_Guide_2026-03-13.md
- 执行侧权威口径（规范正文）：UI_Optimization_Plan_Agent_Onboarding_2026-03-12.md 的“项目口径（2026-03-13 更新）”

本文件不再重复口径细节，避免多处维护导致版本分叉。

---

## 1. 关键工程事实（用于决定“怎么改”）

这些是已经从工程里确认的“工程级事实”，后续所有阶段都以此为前提：

### 1.1 主交互按钮与光标属于渲染层（Canvas 绘制），不是纯 XML View

- 渲染入口：在 [app/src/main/java/com/narc/arclient/MainActivity.java](app/src/main/java/com/narc/arclient/MainActivity.java) 内部类 `CustomDrawView` 的 `onDraw()` 调用 `RenderProcessor.getInstance().draw(canvas)`，并 `postInvalidateOnAnimation()` 持续刷新。
- 绘制实现：在 [app/src/main/java/com/narc/arclient/process/processor/RenderProcessor.java](app/src/main/java/com/narc/arclient/process/processor/RenderProcessor.java) 中使用 `canvas.drawCircle()` / `drawArc()` / `drawRoundRect()` 绘制：
  - 麦克风按钮（红填充 + 白环 + 投影）
  - 模式切换按钮（橙/青/紫填充）
  - 指尖光标（含洋红进度）

结论：

- “Ripple drawable”只对 View 生效；渲染层按钮要做按压/悬停反馈，需要在 `RenderProcessor` 绘制逻辑里实现。

### 1.2 颜色割裂是代码硬编码导致（至少一部分）

在 [app/src/main/java/com/narc/arclient/process/processor/RenderProcessor.java](app/src/main/java/com/narc/arclient/process/processor/RenderProcessor.java) 可直接看到多处硬编码色：

- `#FF9500`（橙）
- `#AF52DE`（紫）
- `#FF00FF`（洋红）
- `#FFD600`（黄读条）
  以及红填充/阴影等 paint 组合。

结论：

- “配色收口到青绿体系”需要覆盖渲染层（RenderProcessor）和 View 层（XML + drawable + styles）。

### 1.3 卡片/字幕/Toast 属于 View 层（XML + drawable + animation）

- 布局入口： [app/src/main/res/layout/activity_main.xml](app/src/main/res/layout/activity_main.xml)
- 卡片布局： [app/src/main/res/layout/item_ar_card.xml](app/src/main/res/layout/item_ar_card.xml)（含 `android:elevation`）
- Toast： [app/src/main/res/layout/ar_toast.xml](app/src/main/res/layout/ar_toast.xml)
- 卡片显示层级：在 [app/src/main/java/com/narc/arclient/MainActivity.java](app/src/main/java/com/narc/arclient/MainActivity.java) 有 `cardRoot.bringToFront()`，并使用淡入淡出动画。

---

## 2. 总控分工模型（你要的“一个 AI 读全工程，多 AI 分阶段交付”）

- AI-0（总控/架构/路线图）：只做一次“读全工程 + 输出母文档补齐项”，之后不再参与具体改动。
- AI-1..AI-N（执行/落地）：每个 AI 只负责一个阶段或一个子阶段（例如 1A、1B），严格按“输入/输出/验收”交付。

交接规则（固定）：

- 每个执行 AI 的交付物必须包含：
  - 修改文件列表
  - 改动点清单（每条 1 句话说明目的）
  - 验收包（必须能对照 baseline 截图）
  - 风险点（1–3 条）

---

## 3. AI-0（总控）一次性任务：读全工程并补齐路线图缺口

这一段就是给 AI-0 的任务定义。AI-0 完成后，后续阶段 AI 不需要再“读完整工程”。

### 3.1 AI-0 必交付（输出物清单）

AI-0 实际交付物（A/B/C/D 汇总文件）：[AI-0_UI_Roadmap_Fill_2026-03-12.md](AI-0_UI_Roadmap_Fill_2026-03-12.md)

- 输出物 A：工程 UI 架构图（文本版即可）

  - View 层：哪些组件、层级顺序、谁负责显示/隐藏
  - Render 层：CustomDrawView 与 RenderProcessor 的渲染范围、刷新频率、是否清屏
  - 双眼镜像：mBindingPair/BaseMirrorActivity 的 UI 同步方式（只写结论与涉及的类）
- 输出物 B：P0-黑块伪影定位决策树（最少 6 个分支）

  - 每个分支：验证动作、要改的临时代码开关、预期现象、结论
- 输出物 C：配色收口覆盖面清单

  - Render 层：RenderProcessor 里哪些 paint/状态使用了非青绿体系
  - View 层：哪些 XML/drawable/styles 使用了非设计系统 token
- 输出物 D：阶段拆解到“可直接开改”的任务卡（每张卡 30–120 分钟粒度）

  - 每张任务卡：目标、改动文件、改动点、回归点、验收截图

### 3.2 AI-0 直接可用提示词（复制粘贴给 AI-0）

```text
你是 AI-0（总控）。你要完整阅读该工程的 UI 与渲染链路，然后输出“总路线图补齐项”。

输入材料（必须阅读）：
- UI_Master_Roadmap_2026-03-12.md（当前文件）
- UI_Design_Review_Report_2026-03-12.md
- UI_Optimization_Plan_Agent_Onboarding_2026-03-12.md
- UI_Quick_Wins_Execution_Plan_2026-03-12.md
- DESIGN_SYSTEM.md
- AR眼镜的设计规范.txt

必须深入阅读并提炼结论的代码/资源：
- app/src/main/java/com/narc/arclient/MainActivity.java（CustomDrawView、卡片显示/关闭、层级）
- app/src/main/java/com/narc/arclient/process/processor/RenderProcessor.java（所有颜色与绘制逻辑）
- app/src/main/res/layout/activity_main.xml
- app/src/main/res/layout/item_ar_card.xml
- app/src/main/res/values/design_system.xml

输出（必须交付 4 个输出物 A/B/C/D，结构按母文档第 3.1 节）：
- A. 工程 UI 架构图（文本）
- B. 黑块伪影定位决策树（分支 + 验证动作 + 结论）
- C. 配色收口覆盖面清单（Render vs View）
- D. 任务卡拆分（30–120 分钟粒度）

限制：
- 不要修改任何代码。
- 只写可执行内容，不写抽象建议。
```

---

## 4. 分阶段路线图（每阶段=一个 AI 的工作包）

说明：阶段编号沿用现有计划，减少沟通成本；其中阶段 1 拆成 1A/1B 两个独立工作包，方便你分给两个 AI 并行做。

### 阶段 0：基线与回归闭环（只做一次，1 个 AI）

状态（2026-03-13，X2）：已完成；交付物入口：[Stage0_X2_验收输出_问题证据优先级_必跑Checklist_2026-03-13.md](Stage0_X2_验收输出_问题证据优先级_必跑Checklist_2026-03-13.md)

输入：

- [UI_Design_Review_Report_2026-03-12.md](UI_Design_Review_Report_2026-03-12.md)
- [实机图片/](实机图片/)

任务：

- 建立“验收包目录结构”（不要求新增图片，只把清单定下来）
- 产出一张可打勾验收表（覆盖 P0/P1）

输出：

- 1 份验收表（表格：验收项/场景/如何拍图/Pass 标准/Baseline）

验收：

- 表格能直接用于拍 After 截图对比，不需要再解释。

提示词：请只从 UI_Owner_Guide_2026-03-13.md 复制“阶段 0：基线与回归闭环”的提示词块。

---

### 阶段 1A（P0-1）：黑块伪影定位与修复（1 个 AI）

说明（2026-03-13 更新）：该阶段改为“按需执行”。若 X2 真机无法稳定复现黑块/遮挡残留，可跳过 1A，优先推进 1B（配色收口）与阶段 3（质感/动效），避免占用 P0 时间。

输入：

- [UI_Quick_Wins_Execution_Plan_2026-03-12.md](UI_Quick_Wins_Execution_Plan_2026-03-12.md)
- [实机图片/拍照追问1.png](实机图片/拍照追问1.png)
- [实机图片/拍照追问2.png](实机图片/拍照追问2.png)

必读代码：

- [app/src/main/java/com/narc/arclient/MainActivity.java](app/src/main/java/com/narc/arclient/MainActivity.java)（CustomDrawView、卡片显示/关闭、bringToFront、动画）
- [app/src/main/java/com/narc/arclient/process/processor/RenderProcessor.java](app/src/main/java/com/narc/arclient/process/processor/RenderProcessor.java)（阴影/清屏/绘制范围）
- [app/src/main/res/layout/activity_main.xml](app/src/main/res/layout/activity_main.xml)（overlay_dim/debug_panel 等遮罩）

任务（按顺序执行）：

1) 先做“归因切分”：伪影来自 Render 层还是 View 层
   - 临时开关 A：让 CustomDrawView 不绘制 RenderProcessor（只保留 View 层）
   - 临时开关 B：只绘制 RenderProcessor（隐藏卡片 View 层）
   - 对比现象：黑块是否仍出现
2) Render 层排查（如果黑块来自 Render 层）
   - 检查是否每帧清屏（透明清屏 vs 脏矩形）
   - 检查 `setShadowLayer` 是否触发 GPU 方块伪影（常见于低端/特殊合成链路）
   - 检查 stopRect/阴影 alpha 是否在卡片区域叠加
3) View 层排查（如果黑块来自 View 层）
   - 检查 overlay_dim/debug_panel 是否被误打开或透明度异常
   - 检查卡片淡入淡出动画结束后是否残留（visibility/tint/alpha）
4) 最小修复
   - 修复必须是“可回归”的：不引入新 UI、只消除伪影

输出：

- 修改文件列表
- 伪影根因一句话
- 修复点清单（每条 1 句）
- 验收截图清单（至少覆盖两张 baseline 场景）

Done 标准：

- After 截图中卡片内部不出现任何黑块/遮挡残留（对照 baseline）。

执行提示词（可直接复制）：

```text
你负责阶段 1A（P0-1 黑块伪影）。允许你修改代码与资源，但只能为“消除黑块伪影”服务，不要做其他 UI 美化。

必须先完成归因切分（Render 层 vs View 层），用临时代码开关证明根因来源，再做最小修复。

必读：
- app/src/main/java/com/narc/arclient/MainActivity.java（CustomDrawView、卡片显示/关闭）
- app/src/main/java/com/narc/arclient/process/processor/RenderProcessor.java（阴影/绘制/颜色）
- app/src/main/res/layout/activity_main.xml

交付：
- 修改文件列表
- 根因一句话
- 修复点清单
- 如何验收（对应 baseline：实机图片/拍照追问1.png、实机图片/拍照追问2.png）
```

---

### 阶段 1B（P0-2）：按钮配色收口（Render 层优先，1 个 AI）

输入：

- [UI_Design_Review_Report_2026-03-12.md](UI_Design_Review_Report_2026-03-12.md)
- [DESIGN_SYSTEM.md](DESIGN_SYSTEM.md)
- [app/src/main/res/values/design_system.xml](app/src/main/res/values/design_system.xml)

必改范围（至少覆盖这些硬编码色）：

- [app/src/main/java/com/narc/arclient/process/processor/RenderProcessor.java](app/src/main/java/com/narc/arclient/process/processor/RenderProcessor.java)
  - 模式切换按钮：橙 `#FF9500`、紫 `#AF52DE`
  - 光标进度：洋红 `#FF00FF`
  - 悬停读条：黄 `#FFD600`

任务：

1) 先产出“语义色映射表”（默认/聚焦/按下/禁用/成功/警告/错误/处理中/聆听）
2) 将 RenderProcessor 的色相收口到青绿体系
   - 红色只保留给错误/停止/危险语义（例如 stop）
   - 其他状态用青绿的明度/透明度变化表达
3) 把硬编码色迁移到资源（优先走 design_system.xml 或 colors.xml），避免后续继续散落

输出：

- 语义色映射表（表格）
- 修改文件列表
- Before/After 对照点（用 baseline 截图指认：哪里不再出现紫/橙/洋红）

Done 标准：

- After 截图中不再出现“紫/橙作为主交互底色”；红色只在危险语义出现。

执行提示词（可直接复制）：

```text
你负责阶段 1B（P0-2 配色收口）。允许你改代码与资源。

目标：把交互按钮/光标的颜色体系收口到青绿体系；红色只用于危险语义。优先处理 RenderProcessor 的硬编码色。

必改：
- app/src/main/java/com/narc/arclient/process/processor/RenderProcessor.java
并把颜色迁移到资源（优先 design_system.xml）。

交付：
- 语义色映射表（表格）
- 修改文件列表
- 验收方式：对照实机图片基线，说明 After 哪些颜色消失了（紫/橙/洋红）
```

---

### 阶段 2：遮挡与信息密度优化（1 个 AI）

输入：

- [UI_Optimization_Plan_Agent_Onboarding_2026-03-12.md](UI_Optimization_Plan_Agent_Onboarding_2026-03-12.md)
- baseline： [实机图片/拍照追问2.png](实机图片/拍照追问2.png)

必读：

- [app/src/main/res/layout/item_ar_card.xml](app/src/main/res/layout/item_ar_card.xml)
- [app/src/main/java/com/narc/arclient/MainActivity.java](app/src/main/java/com/narc/arclient/MainActivity.java)（卡片内容与布局、位置策略）

任务：

- 长文本卡片改为“结论先行 + 要点列表 + 分页/继续查看”的结构（优先分页）
- 设置最大高度策略，避免覆盖视野中心过大面积
- 定义字幕与卡片同屏规则（卡片为主时字幕降权或短驻留）

输出：

- 改动清单
- 修改文件列表
- 验收截图清单（同场景 After）

---

### 阶段 3：质感精修（1 个 AI）

输入：

- [DESIGN_SYSTEM.md](DESIGN_SYSTEM.md)
- [app/src/main/res/values/styles.xml](app/src/main/res/values/styles.xml)
- [app/src/main/res/drawable/bg_card_premium.xml](app/src/main/res/drawable/bg_card_premium.xml)
- [app/src/main/res/drawable/bg_subtitle_premium.xml](app/src/main/res/drawable/bg_subtitle_premium.xml)

任务：

- 文字可读性加稳：正文/次要文字对比拉开一档（克制，不做大光晕）
- 容器风格统一：圆角/描边/分割线透明度一致
- 动画节奏统一：时长引用 token（如果已有）

动效护栏（必须遵守）：以 UI_Optimization_Plan_Agent_Onboarding_2026-03-12.md 的“项目口径（2026-03-13 更新）”为准（本文件不再复述）。

输出：

- 修改文件列表
- 验收截图清单（亮背景/复杂背景优先）

---

### 阶段 4：可持续化（1 个 AI）

任务：

- 建立 token → 组件映射表
- 建立发版必跑的 AR 实机 QA 清单（按截图验收）
- 发热/性能（后置但必做）：识别并消减持续渲染/频繁 invalidate 的来源（例如常驻 `postInvalidateOnAnimation()`）；给出“按需刷新/限帧/降开销”的最小方案，并用 X2 真机固定脚本跑 10–15 分钟回归记录（发热/卡顿主观 + 可选 1 项客观指标：温度/CPU/帧率三选一）。

输出：

- 1 份 QA 清单（表格）
- 1 份 token 映射（表格）

---

## 5. 构建与验证（每个执行 AI 都必须给出）

每个执行 AI 的交付里必须包含至少一个可跑的验证命令（示例）：

- `./gradlew :app:assembleDebug`
- `./gradlew test`

并写清楚“期望看到的结果”（例如编译通过、UI 无伪影、颜色收口完成）。

---

## 6. 最短交接口径（每阶段交付都照抄这一段）

```text
交付摘要：
- 完成了什么：
- 没做什么（明确排除项）：
- 修改的文件：
- 如何验收（对照 baseline 截图）：
- 风险点：
```
