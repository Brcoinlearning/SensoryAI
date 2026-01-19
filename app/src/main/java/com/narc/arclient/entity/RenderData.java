package com.narc.arclient.entity;

public class RenderData {
    private float tipX;
    private float tipY;
    private float progress;       // 识别物体的进度
    private boolean isTriggered;  // 识别物体触发
    private boolean isOpenPalm;   // 摊手关闭
    private String category;

    // 👇 新增：麦克风按钮相关
    private boolean isMicHovered;   // 是否悬停在按钮上 (用于高亮)
    private float micProgress;      // 按钮悬停进度 (0~1)
    private boolean isMicTriggered; // 按钮是否触发 (点击)

    public RenderData(float tipX, float tipY, float progress, boolean isTriggered,
                      boolean isOpenPalm, String category,
                      boolean isMicHovered, float micProgress, boolean isMicTriggered) {
        this.tipX = tipX;
        this.tipY = tipY;
        this.progress = progress;
        this.isTriggered = isTriggered;
        this.isOpenPalm = isOpenPalm;
        this.category = category;

        this.isMicHovered = isMicHovered;
        this.micProgress = micProgress;
        this.isMicTriggered = isMicTriggered;
    }

    public float getTipX() { return tipX; }
    public float getTipY() { return tipY; }
    public float getProgress() { return progress; }
    public boolean isTriggered() { return isTriggered; }
    public boolean isOpenPalm() { return isOpenPalm; }
    public String getCategory() { return category; }

    // 👇 新增 Getter
    public boolean isMicHovered() { return isMicHovered; }
    public float getMicProgress() { return micProgress; }
    public boolean isMicTriggered() { return isMicTriggered; }
}