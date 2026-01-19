package com.narc.arclient.process.processor;

import android.util.Log;
import com.narc.arclient.entity.RecognizeTask;

/**
 * 空壳处理器：防止旧代码报错
 */
public class SendRemoteProcessor {
    private static final String TAG = "SendRemote";

    public RecognizeTask process(RecognizeTask task) {
        // 旧日志逻辑依赖 getTaskId，现已移除，改为普通日志
        // Log.d(TAG, "Process task...");
        return task;
    }
}