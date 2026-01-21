package com.narc.arclient.entity.socket;

public class AgentProgressMessage extends BaseMessage {
    public String stage;    // perception | understanding | decision | response
    public String status;   // processing | completed
    public String summary;  // 处理摘要 (例如 "正在分析图片...")
    public Object data;
}