# UI 快速提效执行清单（P0/P1，最小改动=最大观感提升）

文档定位：

- 这份文件是“P0/P1 快速收益执行清单”（当你想最快看到观感升级时用）。
- **不要从本文件复制提示词给 AI**。请只从 UI_Owner_Guide_2026-03-13.md 复制各阶段提示词块；AI 再按提示词自行打开本文件阅读。

**目标**：用最少改动让实机观感“一眼升级”，并且不偏离 AR 场景约束（低遮挡、低 APL、复杂背景可读、语义色一致）。  
**证据基线**：以 [UI_Optimization_Plan_Agent_Onboarding_2026-03-12.md](UI_Optimization_Plan_Agent_Onboarding_2026-03-12.md) 的“参考材料 > 实机证据与问题列表”为准（此处不再重复）。

---

## 0. 先做可行性判断（30 分钟）

### 0.1 判断“按钮是否是 View”还是“渲染绘制”

**为什么**：如果主要交互按钮是 RenderProcessor 绘制的（Canvas 画圆按钮），新增 Android Ripple drawable 将不会生效。

**要看哪些文件**
- 交互与渲染入口：[app/src/main/java/com/narc/arclient/MainActivity.java](app/src/main/java/com/narc/arclient/MainActivity.java)
- 渲染实现：在工程内搜索 RenderProcessor.draw（不在本清单里列路径，避免误指；以搜索结果为准）

**判定标准**
- 若按钮来自 layout XML（Button/ImageButton/MaterialButton）：可用 Ripple
- 若按钮由 Canvas 绘制（drawCircle/drawBitmap 等）：Ripple 不适用，应改为“焦点环/按压态”渲染反馈

---

## P0（必须优先：成品感收口，1–2 天）

### P0-1 消除卡片内部黑色方块伪影（Critical）

说明（2026-03-13 更新）：该项改为按需执行。若 X2 真机未复现黑块伪影/遮挡残留，可跳过 P0-1，优先推进 P0-2（配色收口）与阶段 3 的质感/动效任务。

**现象（证据）**
- 卡片内部出现多处黑色小方块/遮挡残留：
  - [实机图片/拍照追问1.png](实机图片/拍照追问1.png)
  - [实机图片/拍照追问2.png](实机图片/拍照追问2.png)

**任务清单**
1. 复现：用同一流程触发“聆听中卡片/长结果卡片”，确认黑块出现条件（是否仅在某模式、仅在某动画过程中、仅在双眼镜像时出现）。
2. 定位层级：按“谁在卡片之上/之内”排查（遮罩层、debug overlay、合成层、镜像容器）。
3. 修复：移除残留遮罩/修正 alpha/修正 z-order；确保 release 也不出现。

**需要读的文件**
- 卡片/字幕/状态容器布局：[app/src/main/res/layout/activity_main.xml](app/src/main/res/layout/activity_main.xml)
- 卡片布局：[app/src/main/res/layout/item_ar_card.xml](app/src/main/res/layout/item_ar_card.xml)
- 卡片背景：[app/src/main/res/drawable/bg_card_premium.xml](app/src/main/res/drawable/bg_card_premium.xml)
- 主逻辑（何时显示/动画/bringToFront）：[app/src/main/java/com/narc/arclient/MainActivity.java](app/src/main/java/com/narc/arclient/MainActivity.java)

**验收标准**
- 对照同场景 After 截图：卡片内部无任何黑块/遮挡残留（至少覆盖两张 baseline 场景：拍照追问1、拍照追问2）。

---

### P0-2 统一交互按钮颜色体系（Critical）

**现象（证据）**
- 紫色麦克风按钮、橙色相机按钮、高饱和红按钮与青绿体系割裂：
  - [实机图片/拍照追问1.png](实机图片/拍照追问1.png)
  - [实机图片/药品识别.png](实机图片/药品识别.png)

**任务清单**
1. 建立“语义色映射表”：默认/聚焦/按下/禁用/成功/警告/错误/处理中/聆听。
2. 收回非语义色：
   - 紫色、橙色不再用于主交互按钮底色
   - 红色仅用于错误/停止/危险语义
3. 将按钮与焦点环统一到青绿体系（用明度/描边/外环动画表达状态，而不是换色相）。

**需要读的文件**
- 设计 token：[app/src/main/res/values/design_system.xml](app/src/main/res/values/design_system.xml)
- 设计系统说明：[DESIGN_SYSTEM.md](DESIGN_SYSTEM.md)
- 实机问题对照：[UI_Design_Review_Report_2026-03-12.md](UI_Design_Review_Report_2026-03-12.md)

**验收标准**
- After 截图中不再出现紫/橙按钮底色。
- 红色仅出现在错误/危险语义；普通交互不使用高饱和红。

---

## P1（高视觉冲击但需克制：2–4 天）

### P1-1 卡片“微发光 + 精致阴影边框”（建议幅度很小）

**为什么**：AR 里过强泛光会显廉价且影响文字清晰；但“轻微发光”能显著提升科技感。

**任务清单**
1. 只对卡片容器做“极轻微外发光”（alpha 6%–10% 以内起步）。
2. 做 A/B：同场景拍 2 张（发光 on/off），以实机为准决定强度。

**需要读的文件**
- 卡片背景：[app/src/main/res/drawable/bg_card_premium.xml](app/src/main/res/drawable/bg_card_premium.xml)
- 字幕背景（避免风格分裂）：[app/src/main/res/drawable/bg_subtitle_premium.xml](app/src/main/res/drawable/bg_subtitle_premium.xml)

**验收标准**
- 卡片更“浮”，但文字边缘不发虚、不光晕。
- 在亮背景/纹理背景下不出现明显色阶。

---

### P1-2 增强可读性：高对比强调色 + 主色上的文字色（谨慎引入）

**任务清单**
1. 新增 token（可选）：accent_high_visibility、text_on_primary。
2. 写清楚使用边界：只给 CTA/结论句/焦点态，不给普通正文。

**需要读的文件**
- token：[app/src/main/res/values/design_system.xml](app/src/main/res/values/design_system.xml)
- 文本样式：[app/src/main/res/values/styles.xml](app/src/main/res/values/styles.xml)

**验收标准**
- 关键信息更清晰，但不会出现“全屏都在高亮”的噪声。

动效护栏（必须遵守）：以 UI_Optimization_Plan_Agent_Onboarding_2026-03-12.md 的“项目口径（2026-03-13 更新）”为准（此处不再重复）。

---

### P1-3 Toast 入场/退场更自然（小幅 slide + alpha，使用 token 时长）

**任务清单**
1. 新建 toast 动画（translate 幅度建议 12–20dp，而不是 100%）。
2. 时长必须引用 token（anim_duration_fast/normal/slow），避免动画风格碎片化。

**需要读的文件**
- 动画目录：[app/src/main/res/anim/](app/src/main/res/anim/)
- Toast View：[app/src/main/java/com/narc/arclient/ui/ArToastView.java](app/src/main/java/com/narc/arclient/ui/ArToastView.java)

**验收标准**
- Toast 出现/消失更“系统级”，不突兀，不抢戏。

---

### P1-4 状态 pill 呼吸动画（仅在“聆听/处理中”启用）

**任务清单**
1. 新建轻微 alpha 呼吸动画（1.0→0.8，1500ms，infinite）。
2. 仅在两类状态启用：正在聆听、处理中；完成/就绪必须停止。

**需要读的文件**
- 状态条布局：[app/src/main/res/layout/activity_main.xml](app/src/main/res/layout/activity_main.xml)
- 状态更新逻辑：[app/src/main/java/com/narc/arclient/MainActivity.java](app/src/main/java/com/narc/arclient/MainActivity.java)

**验收标准**
- 用户能感知状态在“活着”，但不会被持续动画分心。

---

## Ripple（水波纹）建议采用“条件启用”策略

### 什么时候做（推荐）
- 仅当目标按钮是 Android View（layout 里的 Button/MaterialButton）时做。

### 什么时候不做（推荐）
- 如果主要交互按钮来自渲染层（Canvas 绘制），Ripple drawable 不会生效；应改为：
  - 焦点环轻扩散（150ms）
  - 按下态亮度/描边变化（150ms）

---

## 交付与验收（避免多处维护同一模板）

- 交付摘要模板：以 [UI_Master_Roadmap_2026-03-12.md](UI_Master_Roadmap_2026-03-12.md) 的“最短交接口径”为准。
- 验收基线与 checklist：以 [UI_Optimization_Plan_Agent_Onboarding_2026-03-12.md](UI_Optimization_Plan_Agent_Onboarding_2026-03-12.md) 的“证据基线/总体验收原则”为准。
- QuickWins 额外要求：如果引入新 token，必须写清楚“使用边界”（避免后续滥用）。
