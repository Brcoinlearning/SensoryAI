# 银龄视界 - AR眼镜界面设计系统

## 设计哲学
> "Perfection is achieved not when there is nothing more to add, but when there is nothing left to take away." - Antoine de Saint-Exupéry

本设计系统专为雷鸟X2 AR眼镜打造，遵循**瑞士极简主义**美学理念，追求：
- **Pure** - 纯粹的视觉语言
- **Precise** - 精确的空间布局
- **Premium** - 高端的质感体验

---

## 🎨 核心配色方案

### 主色调 - 深邃蓝绿色系
选择蓝绿色系是基于AR眼镜显示特性的优化：
- ✅ **优先使用绿色通道** - 降低功耗
- ✅ **避免大面积白色** - 减少APL占比
- ✅ **谨慎使用红色** - 仅用于警示

```
Primary Colors:
├─ Deep Teal      #0A1F2E  背景主色
├─ Teal           #00A896  主题色
├─ Teal Light     #02C39A  强调色
└─ Mint           #00F5D0  高亮色

Surface Colors:
├─ Base           #0D1B2A  基础表面
├─ Elevated       #1B263B  悬浮表面
└─ Overlay        #415A77  叠加层

Text Colors:
├─ Primary        #E0E1DD  主文字
├─ Secondary      #A3B2C0  次要文字
├─ Tertiary       #6B7A8F  三级文字
└─ Accent         #00F5D0  强调文字
```

### 功能性颜色
```
Success    #05CE78  成功状态（绿色通道优化）
Warning    #F2B134  警告状态
Error      #D62828  错误状态（仅警示时使用）
Info       #00A896  信息提示
```

---

## 📐 空间系统

### 安全边距（符合AR眼镜规范）
```
水平安全边距: 30dp
垂直安全边距: 20dp
```

### 间距系统
```
XS   4dp   微小间距
SM   8dp   小间距
MD   16dp  标准间距
LG   24dp  大间距
XL   32dp  特大间距
XXL  48dp  超大间距
```

### 圆角规范
```
Small   8dp   小元素
Medium  12dp  标准卡片
Large   16dp  大卡片
XLarge  24dp  特殊容器
```

### 边框宽度（最小2dp符合AR显示要求）
```
Thin    2dp
Medium  3dp
Thick   4dp
```

---

## 🔤 字体系统

### 字号规范（最小16sp）
```
XS   16sp  最小字号（AR规范要求）
SM   18sp  辅助文字
MD   20sp  正文
LG   24sp  小标题
XL   28sp  标题
XXL  32sp  大标题
```

### 字体选择
- **主字体**: HarmonyOS Sans SC (系统内嵌)
- **字重**: Regular (400), Medium (500), Bold (700)
- **字间距**: 0.01-0.02em 提升可读性
- **行距**: 1.4倍行高

---

## 🎯 图标系统

### 图标尺寸
```
XS  16dp  小图标
SM  20dp  按钮图标
MD  24dp  标准图标
LG  32dp  大图标
XL  48dp  特大图标
```

### 核心图标
- **ic_microphone** - 麦克风（线条设计）
- **ic_microphone_active** - 激活状态（实心填充）
- **ic_processing** - 处理中（旋转动画）
- **ic_assistant** - 智能助手

---

## 🎬 动画系统

### 动画时长
```
Fast    150ms  快速反馈
Normal  250ms  标准过渡
Slow    350ms  强调动画
```

### 核心动画
1. **card_fade_in** - 卡片优雅淡入
   - Alpha: 0 → 1
   - Scale: 0.92 → 1.0
   - TranslateY: 20dp → 0
   
2. **card_fade_out** - 卡片优雅淡出
   - Alpha: 1 → 0
   - Scale: 1.0 → 0.92
   - TranslateY: 0 → -20dp
   
3. **rotate_processing** - 处理中旋转
   - Rotate: 0° → 360°
   - Duration: 1200ms
   - Repeat: Infinite
   
4. **pulse** - 脉冲效果（激活状态）
   - Scale: 1.0 ↔ 1.1
   - Alpha: 1.0 ↔ 0.6
   - Duration: 800ms

---

## 🏗️ 组件规范

### AR卡片（Premium Card）
```xml
背景: 毛玻璃效果 + 精致边框
内边距: 24dp (上下) / 24dp (左右)
圆角: 16dp
边框: 2dp (#415A77 30%透明度)
阴影: 4dp elevation
```

**结构:**
```
┌─────────────────────────┐
│ [Icon] 标题             │  图标 + 标题 (24sp)
│ ─────────────────────   │  分割线 (2dp)
│ 内容文本...              │  内容 (20sp)
│                         │
└─────────────────────────┘
```

### 字幕气泡（Subtitle Bubble）
```xml
背景: 优雅半透明
内边距: 16dp (上下) / 16dp (左右)
圆角: 12dp
边框: 2dp (#415A77 10%透明度)
最大宽度: 300dp
最大行数: 3行
```

### 状态指示器（Status Bar）
```xml
位置: 顶部居中
内边距: 8dp (上下) / 16dp (左右)
圆角: 底部12dp
背景: #0D1B2A
```

**结构:**
```
┌─────────────────┐
│ [Icon] 状态文字  │
└─────────────────┘
```

---

## 💎 最佳实践

### DO ✅
- 使用图标替代emoji表情
- 保持16sp最小字号
- 使用绿色通道优先的颜色
- 添加优雅的过渡动画
- 保持安全边距 (30dp/20dp)
- 使用2dp最小边框宽度

### DON'T ❌
- 避免大面积白色背景
- 避免低于16sp的字号
- 避免不必要的emoji装饰
- 避免过于频繁的红色使用
- 避免忽略安全边距
- 避免低于2dp的线条

---

## 🔋 功耗优化建议

### APL（Average Picture Level）控制
```
持续显示: < 13%
非持续显示: < 25%
```

### 优化策略
1. **背景强制使用纯黑色** (#000000)
2. **禁止全屏白色画面**
3. **优先使用绿色通道颜色**
4. **APL超过13%时降低非重点UI亮度**
5. **多用绿色通道，少用红色通道**

### 性能建议
```
逻辑线程: ≤ 4个
刷新率: ≤ 30fps
```

---

## 📱 响应式适配

### 雷鸟X2规格
```
分辨率: 640×480
FoV: 对角线25°
显示距离建议: 1.7m以上
```

### 布局原则
1. **信息密度可控** - 避免视野遮挡
2. **步骤引导明确** - 每个操作都有反馈
3. **遵循合目距离** - 减少视觉疲劳
4. **避免遮挡视野** - 使用局部信息卡片

---

## 🎓 使用示例

### 状态更新
```java
// 带图标的状态更新
updateStatus("正在聆听", R.drawable.ic_microphone_active);

// 添加动画效果
if (ivStatusIcon != null) {
    Animation pulse = AnimationUtils.loadAnimation(this, R.anim.pulse);
    ivStatusIcon.startAnimation(pulse);
}
```

### 卡片显示
```java
// 显示卡片（带淡入动画）
startCardSequence();

// 设置内容
setCardText("智能助手", "分析完成", 
    getResources().getColor(R.color.status_success, null));

// 关闭卡片（带淡出动画）
closeCard();
```

---

## 📚 参考资料

- [雷鸟AR眼镜设计规范](AR眼镜的设计规范.txt)
- Swiss Design Principles
- Material Design (精简版)
- iOS Human Interface Guidelines (AR部分)

---

**设计版本**: 1.0.0  
**更新日期**: 2026年1月  
**设计师**: GitHub Copilot  
**适用设备**: 雷鸟X2 AR眼镜
