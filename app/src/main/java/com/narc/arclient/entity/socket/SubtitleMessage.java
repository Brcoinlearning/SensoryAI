package com.narc.arclient.entity.socket;

public class SubtitleMessage extends BaseMessage {
    public String text;
    public boolean is_partial; // true=部分结果(正在变), false=最终结果(已确定)
    public double timestamp; // 支持浮点数时间戳（秒.毫秒格式）
}