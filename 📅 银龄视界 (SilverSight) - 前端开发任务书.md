明白，既然你专注于前端开发，那我们就抛开后端的具体实现，专注于**Android 客户端 (AR 眼镜端)** 的工作。

这份任务书是基于你提供的 `SensoryAI` 代码库定制的。你的核心任务是将现有的“手势画框 Demo”改造成一个**支持指尖交互和实时字幕的 AR 应用**。

------

# 📅 银龄视界 (SilverSight) - 前端开发任务书

开发环境： Android Studio, RayNeo AR SDK

核心类库： MediaPipe (手势), gRPC (网络), Android AudioRecord

代码基础： SensoryAI 项目 (MainActivity, RecognizeProcessor, RenderProcessor)

------

### 🟢 第一阶段：视觉交互核心 (指尖追踪与悬停)

**目标：** 不再画方框，而是让用户用食指作为“鼠标”，悬停 1 秒触发拍照。

#### 任务 1.1：改造识别逻辑 (`RecognizeProcessor.java`)

- **当前逻辑：** 计算手掌的矩形框 (`Rectangle`)。

- **修改目标：** 提取食指指尖坐标。

- **具体步骤：**

  1. 在 `process()` 方法中，获取 MediaPipe 结果的第 8 个关键点（食指指尖）：

     Java

     ```
     NormalizedLandmark indexTip = landmarks.get(8);
     float x = indexTip.x();
     float y = indexTip.y();
     ```

  2. **实现悬停算法 (Dwell Algorithm)：**

     - 在 `RecognizeProcessor` 类中增加成员变量记录 `lastX`, `lastY`, `startTime`。
     - 计算当前帧指尖与上一帧的距离。如果 `distance < 阈值` (例如 0.05)，则认为在“悬停”。
     - 如果悬停时间 > 1000ms，设置一个标志位 `isTriggered = true`。

  3. 修改 `RenderData` 类，增加 `tipX`, `tipY`, `progress` (悬停进度 0~1) 字段，去掉 `Rectangle`。

#### 任务 1.2：改造渲染逻辑 (`RenderProcessor.java`)

- **当前逻辑：** 画红色关键点 (`drawLandmarks`) 和矩形框。
- **修改目标：** 绘制动态准星 (Reticle)。
- **具体步骤：**
  1. 删除 `drawLandmarks` 方法。
  2. 在 `Canvas` 上绘制一个圆环：
     - 圆心：`(tipX * screenWidth, tipY * screenHeight)`
     - 样式：白色细线圆圈 + 绿色进度条圆弧 (根据 `progress` 绘制)。
  3. **双目适配（关键）：**
     - 雷鸟 X2 是双目显示。你需要将这个圆环在左右两个半屏上都画一次。
     - 左眼 X：`tipX * (screenWidth / 2)`
     - 右眼 X：`tipX * (screenWidth / 2) + (screenWidth / 2)`

#### 任务 1.3：触发拍照与请求

- **逻辑：** 当 `RecognizeProcessor` 检测到 `isTriggered == true` 时。
- **具体步骤：**
  1. 调用 `ICameraManager` 获取当前的高清帧（不是 MediaPipe 的缩略图，最好是高清原图）。
  2. 将图片转为 `byte[]`。
  3. 调用 gRPC 接口发送图片（暂时 Mock 一个假的返回结果，比如延时 1 秒后返回字符串“阿莫西林”）。
  4. **震动反馈：** 调用 Android `Vibrator` 震动 50ms，提示用户识别开始。

------

### 🔵 第二阶段：UI 信息展示 (AR卡片)

**目标：** 识别成功后，在指尖位置显示漂亮的信息卡片。

#### 任务 2.1：定义 AR 卡片布局

- **文件：** `res/layout/item_ar_card.xml` (新建)
- **内容：**
  - `TextView` (药品名)：大号、加粗、黄色。
  - `TextView` (用法)：中号、白色。
  - 背景：半透明黑色圆角矩形 (`drawable/bg_card_rounded.xml`)。

#### 任务 2.2：在主界面动态渲染

- **文件：** `MainActivity.java`
- **修改目标：** `updateView` 方法。
- **具体步骤：**
  1. 在布局中添加一个隐藏的 `<include layout="@layout/item_ar_card" />`。
  2. 当 `RenderProcessor` 传回识别结果（如“阿莫西林”）时：
     - 设置卡片可见 (`VISIBLE`)。
     - 更新卡片文字。
     - **位置跟随：** 设置卡片的 `setX()` 和 `setY()`，让它显示在指尖坐标的右上方。

#### 任务 2.3：手势关闭 (挥手即去)

- **文件：** `RecognizeProcessor.java`
- **具体步骤：**
  1. 检查 `gestureRecognizerResult.gestures()` 的分类名称。
  2. 如果检测到 **"Open_Palm"** (张开手掌) 且置信度 > 0.8：
     - 发送一个 `ClearEvent` 到前端。
  3. `MainActivity` 收到事件后，将 AR 卡片设为 `GONE`，重置所有状态。

------

### 🟠 第三阶段：实时字幕 (听觉辅助)

**目标：** 开启麦克风，实时显示文字。

#### 任务 3.1：音频采集工具类

- **新建类：** `com.narc.arclient.audio.AudioRecorder`
- **功能：**
  - 配置 `AudioRecord`: SampleRate 16000, Mono, PCM_16BIT.
  - 提供 `start()` 方法：启动线程，不断读取 `byte[] buffer`。
  - 提供 `setCallback()`：将读取到的 buffer 抛出去。

#### 任务 3.2：字幕 UI 布局

- **文件：** `res/layout/activity_main.xml`
- **修改：**
  - 在底部（Bottom）添加一个 `TextView` (`id: subtitle_view`)。
  - 属性：`layout_gravity="bottom|center_horizontal"`, `background="#AA000000"` (半透明黑底), `maxLines="2"`.
  - **双目适配注意：** 如果是左右分屏模式，你可能需要放**两个** TextView，分别在左半屏底部和右半屏底部，内容保持一致。

#### 任务 3.3：网络发送与显示

- **新建类：** `AudioStreamProcessor`
- **具体步骤：**
  1. 在 `AudioRecorder` 的回调中，拿到音频数据。
  2. 通过 gRPC 的 `StreamAudio` 接口发送出去。
  3. 监听 gRPC 的 `onNext(Response)` 回调。
  4. `runOnUiThread` 更新 `subtitle_view` 的文字。

------

### 📝 前端开发核对清单 (Checklist)

- [ ] **清理：** 删除了 `RenderProcessor` 中画红点和画方框的代码。
- [ ] **MediaPipe：** 能在 Log 中看到食指指尖 (Index Tip) 的 `x, y` 坐标在变化。
- [ ] **准星：** 屏幕上有一个小圆圈跟随手指移动（左右眼都有）。
- [ ] **悬停：** 手指按住不动 1 秒，眼镜会震动一下。
- [ ] **卡片：** 震动后，指尖旁边能弹出一个测试用的“阿莫西林”卡片。
- [ ] **清屏：** 挥手（张开手掌），卡片消失。
- [ ] **录音：** 说话时，App 不崩溃，且能在 Logcat 看到“Audio buffer read size: 3200”之类的日志。
- [ ] **字幕：** 屏幕底部能显示测试文字（双目都要有）。

这份任务书完全基于你现在的前端代码架构，你可以直接照着这个顺序开始写代码了。先做**第一阶段的 1.1 和 1.2**，这是最能看到效果的！             