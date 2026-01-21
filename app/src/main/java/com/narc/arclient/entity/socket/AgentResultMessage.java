package com.narc.arclient.entity.socket;

public class AgentResultMessage extends BaseMessage {
    public Object data; // 智能体最终响应内容
    public String session_id;
}