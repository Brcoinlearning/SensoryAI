# UI 后续优化计划（任务清单 + Agent 交接指南 + 验收标准）

文档定位：

- 这份文件是“执行侧交接/验收框架”：参考材料入口、总体验收原则、分阶段任务与验收、任务卡（Task Cards）。
- **不要从本文件复制提示词给 AI**。请只从 UI_Owner_Guide_2026-03-13.md 复制各阶段提示词块；AI 再按提示词自行打开本文件阅读。

**目标**：在不偏离 AR 场景约束（低遮挡、低 APL、复杂背景可读、语义色一致）的前提下，让 UI 更美观、更“成品感”。  
**证据基线**：以现有 4 张实机截图为 baseline，并以报告结论作为问题清单来源。  

项目口径（2026-03-13 更新）：

- 验收设备：当前只验收 X2；迁移能力通过 token 化与不写死尺寸策略保留。
- 优先级：先做“更精美 + 更像成品 + 动效节奏”；黑块伪影排查按需执行；发热/性能优化后置到阶段 4。
- 动效护栏：除 Listening/Processing 外禁止无意义常驻循环；任何循环必须有停止条件。

## 参考材料（新 Agent 必读）

### 1) 实机证据与问题列表

- 视觉评审报告（实机证据版）：[UI_Design_Review_Report_2026-03-12.md](UI_Design_Review_Report_2026-03-12.md)
- 实机截图目录：[实机图片/](实机图片/)
  - [实机图片/实时字幕.png](实机图片/实时字幕.png)
  - [实机图片/拍照追问1.png](实机图片/拍照追问1.png)
  - [实机图片/拍照追问2.png](实机图片/拍照追问2.png)
  - [实机图片/药品识别.png](实机图片/药品识别.png)

### 2) 设计系统与约束（设计方向）

- 设计系统文档：[DESIGN_SYSTEM.md](DESIGN_SYSTEM.md)
- 设备/AR 规范（约束与最佳实践）：[AR眼镜的设计规范.txt](AR眼镜的设计规范.txt)

### 3) UI 实现落地点（研发侧关键文件）

- 设计 token（颜色/间距/字号/最大宽度/动画时长）：[app/src/main/res/values/design_system.xml](app/src/main/res/values/design_system.xml)
- 文本样式（Subtitle/Toast/Card/Status）：[app/src/main/res/values/styles.xml](app/src/main/res/values/styles.xml)
- 主题（背景、主色等）：
  - [app/src/main/res/values/themes.xml](app/src/main/res/values/themes.xml)
  - [app/src/main/res/values-night/themes.xml](app/src/main/res/values-night/themes.xml)
- 主布局（状态条/字幕/Toast/卡片容器）：[app/src/main/res/layout/activity_main.xml](app/src/main/res/layout/activity_main.xml)
- 卡片布局：[app/src/main/res/layout/item_ar_card.xml](app/src/main/res/layout/item_ar_card.xml)
- Toast 布局：[app/src/main/res/layout/ar_toast.xml](app/src/main/res/layout/ar_toast.xml)
- 背景 drawable（卡片/字幕/状态条/按钮 selector）：[app/src/main/res/drawable/](app/src/main/res/drawable/)
- 动画资源（淡入淡出/旋转/脉冲）：[app/src/main/res/anim/](app/src/main/res/anim/)
- 自定义 View：
  - 字幕 View：[app/src/main/java/com/narc/arclient/ui/SubtitleStreamView.java](app/src/main/java/com/narc/arclient/ui/SubtitleStreamView.java)
  - AR Toast View：[app/src/main/java/com/narc/arclient/ui/ArToastView.java](app/src/main/java/com/narc/arclient/ui/ArToastView.java)
- 主逻辑（状态更新、卡片内容、模式切换等）：[app/src/main/java/com/narc/arclient/MainActivity.java](app/src/main/java/com/narc/arclient/MainActivity.java)

---

## 总体验收原则（所有阶段通用）

- **一致性**：交互控件（按钮、焦点环、状态 icon）必须属于同一套视觉语言（同一主色体系、同一描边/圆角/阴影规则）。
- **无伪影**：任何卡片/字幕/状态容器内部不允许出现黑块、遮挡残留、合成 artifacts。
- **可读性**：在亮背景/复杂纹理背景下，正文仍可稳定阅读（不发灰、不糊、不过度光晕）。
- **遮挡控制**：长内容不能覆盖视野中心的大面积区域；优先“结论先行 + 细节收纳”。
- **语义色规则**：红色仅用于错误/危险/停止类语义；成功/完成类使用 success；处理中/聆听使用主体系强调色。

---

## 阶段 0：项目对齐与基线建立（0.5 天）

状态（2026-03-13，X2）：已完成；交付物入口：[Stage0_X2_验收输出_问题证据优先级_必跑Checklist_2026-03-13.md](Stage0_X2_验收输出_问题证据优先级_必跑Checklist_2026-03-13.md)

### 需要完成的任务

1. 建立“验收基线截图集”
   - 选定 4 个场景：实时字幕、聆听中卡片、长结果卡片、药品信息卡片
   - 对每个场景补齐 1 张“亮背景/室外或强光环境”截图（如果可获得）
2. 建立“问题-证据-优先级”表
   - 直接引用评审报告中的 Top Issues（颜色割裂、黑块伪影、遮挡过大）

### 新 Agent 如何理解相关信息

- 先读：[UI_Design_Review_Report_2026-03-12.md](UI_Design_Review_Report_2026-03-12.md)
- 再看截图：[实机图片/](实机图片/)
- 最后对照设计系统：[DESIGN_SYSTEM.md](DESIGN_SYSTEM.md)

### 验收标准

- 形成一个“阶段内可验证”的 checklist（至少包含：颜色一致性/无伪影/可读性/遮挡控制 4 项）
- 每项都有对应截图示例（Before/After 预留位即可）

---

## 阶段 1：P0 收口（1–2 天，观感提升最大）

### 需要完成的任务

1. 消除卡片内部黑色方块伪影（Critical）
   - 定位来源（渲染叠加、遮罩层、debug overlay、双眼镜像合成等）
   - 修复后复测 4 个场景
  - （按需：仅当 X2 真机可复现伪影/遮挡残留时执行，否则跳过，优先做配色与质感/动效）
2. 统一交互按钮/焦点环配色到青绿体系（Critical）
   - 定义交互态：默认 / 聚焦 / 按下 / 禁用 / 危险
   - 将紫色/橙色/非语义红替换为设计系统主色体系

### 新 Agent 如何理解相关信息

- 伪影相关：重点读
  - [app/src/main/res/layout/activity_main.xml](app/src/main/res/layout/activity_main.xml)
  - [app/src/main/res/drawable/](app/src/main/res/drawable/)
  - [app/src/main/java/com/narc/arclient/MainActivity.java](app/src/main/java/com/narc/arclient/MainActivity.java)
- 交互控件颜色相关：重点读
  - [app/src/main/res/values/design_system.xml](app/src/main/res/values/design_system.xml)
  - [DESIGN_SYSTEM.md](DESIGN_SYSTEM.md)

### 验收标准

- **伪影**：在以下截图对应的场景中，卡片内部不再出现黑块/遮挡残留
  - [实机图片/拍照追问1.png](实机图片/拍照追问1.png)
  - [实机图片/拍照追问2.png](实机图片/拍照追问2.png)
- **配色一致性**：交互按钮不再出现紫/橙；红色仅用于危险/错误语义
  - 验收方式：拍同场景 After 截图，与 baseline 对比

---

## 阶段 2：遮挡与信息密度优化（2–4 天）

### 需要完成的任务

1. 长结果卡片信息结构重排（High）
   - 结论句前置并高亮（1 行）
   - 细节压缩为 3–5 条要点
   - 超出内容采用分页或“继续查看”（优先分页）
2. 长卡片最大高度/最大宽度策略（High）
   - 设定最大高度，避免覆盖视野中心过大面积
3. 字幕与中心卡片并发规则（Medium）
   - 定义：何时只显示字幕、何时只显示卡片、何时同屏但字幕降权

### 新 Agent 如何理解相关信息

- 卡片内容与结构：
  - [app/src/main/res/layout/item_ar_card.xml](app/src/main/res/layout/item_ar_card.xml)
  - [app/src/main/java/com/narc/arclient/MainActivity.java](app/src/main/java/com/narc/arclient/MainActivity.java)
- 字幕展示策略：
  - [app/src/main/java/com/narc/arclient/ui/SubtitleStreamView.java](app/src/main/java/com/narc/arclient/ui/SubtitleStreamView.java)
  - [app/src/main/res/layout/activity_main.xml](app/src/main/res/layout/activity_main.xml)

### 验收标准

- **遮挡**：长结果场景中，卡片覆盖面积显著下降；结论 1 秒内可读
  - baseline： [实机图片/拍照追问2.png](实机图片/拍照追问2.png)
  - 验收：提供同场景 After 截图（同距离/相似背景）
- **并发**：同屏时用户视线焦点明确（卡片为主时字幕不抢戏）
  - 验收：提供一张“卡片+字幕同屏”的 After 截图

---

## 阶段 3：质感精修（2–3 天）

### 需要完成的任务

1. 文本可读性加稳（Medium）
   - 正文/次要文字对比度拉开一档
   - 阴影策略克制但有效（避免光晕）
2. 控件风格统一（Medium）
   - 圆角、描边粗细、分割线透明度、容器高光策略统一
3. 动效一致性复核（Low）
   - 淡入淡出、旋转、脉冲的节奏与强度统一（不喧宾夺主）
  - （护栏：除聆听/处理中外禁止无限循环；循环必须可停止）

### 新 Agent 如何理解相关信息

- 文本与容器样式：
  - [app/src/main/res/values/styles.xml](app/src/main/res/values/styles.xml)
  - [app/src/main/res/drawable/bg_card_premium.xml](app/src/main/res/drawable/bg_card_premium.xml)
  - [app/src/main/res/drawable/bg_subtitle_premium.xml](app/src/main/res/drawable/bg_subtitle_premium.xml)
- 动画资源：
  - [app/src/main/res/anim/](app/src/main/res/anim/)

### 验收标准

- **可读性**：在亮背景/复杂纹理背景截图中，正文无明显发灰/发虚
- **统一性**：卡片、字幕、状态 pill、按钮看起来属于同一套系统

---

## 阶段 4：设计系统可持续化（1–2 天）

### 需要完成的任务

1. 建立“token → 组件”映射表（文档化）
   - 颜色（主色/强调/成功/警告/错误）
   - 字号、行距、字间距
   - 间距、圆角、描边、阴影、最大宽度
2. 建立 AR 实机视觉 QA 清单（发版必跑）
   - 颜色语义、遮挡、可读性、伪影、边缘裁切、并发信息竞争

3. 发热/性能优化（后置但必须）
  - 识别持续渲染/持续动画/频繁 invalidate 来源（优先核查 Canvas 渲染链路是否常驻刷新）
  - 选择最小策略：按需刷新（状态变化才刷新）或限帧（例如 30fps），并明确回归点
  - X2 真机固定脚本跑 10–15 分钟，记录发热/卡顿主观 + 可选 1 项客观指标（温度/CPU/帧率）

### 新 Agent 如何理解相关信息

- 设计系统：
  - [DESIGN_SYSTEM.md](DESIGN_SYSTEM.md)
  - [app/src/main/res/values/design_system.xml](app/src/main/res/values/design_system.xml)

### 验收标准

- 新增或改动 UI 时，所有颜色/尺寸/间距优先来自 token 文件
- 每次发版能用 QA 清单对照并产出 4 张关键场景验收截图

---

## 建议交付物（每阶段都应产出）

Task Cards（30–120 分钟粒度，可直接分配给执行 AI）：

- 1B-0（30–60m）：语义色映射表（默认/聚焦/按下/禁用/成功/警告/错误/处理中/聆听；红色只用于危险/错误）
- 1B-1（60–120m）：RenderProcessor 硬编码色迁移到 token（优先 design_system.xml；阴影色/alpha 默认不迁移）
- 3-0（30–60m）：动效清单与护栏表（组件→触发→时长 token→停止条件）
- 3-1（60–120m）：卡片动效（淡入淡出+轻微 scale/translate）与容器统一（圆角/描边/分割线/文字对比）
- 3-2（60–120m）：Toast 动效（小幅位移+alpha，克制）与样式统一
- 4-0（60–120m）：发热/性能：按需刷新/限帧/降开销 + 10–15 分钟真机回归记录

- 交付模板：按 [UI_Master_Roadmap_2026-03-12.md](UI_Master_Roadmap_2026-03-12.md) 的“最短交接口径”填写交付摘要
- 并附：Before/After 对比截图（至少覆盖 4 个关键场景）+ 验收 checklist 勾选结果
