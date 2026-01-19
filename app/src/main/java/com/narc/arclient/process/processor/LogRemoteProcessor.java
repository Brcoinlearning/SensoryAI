package com.narc.arclient.process.processor;

import com.narc.arclient.entity.RecognizeTask;

/**
 * 空壳处理器：旧逻辑已废弃
 */
public class LogRemoteProcessor {

    public RecognizeTask process(RecognizeTask task) {
        // 直接透传，不做任何处理
        return task;
    }
}