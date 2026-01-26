package com.narc.arclient.utils;

import android.content.Context;
import android.media.AudioManager;
import android.speech.tts.TextToSpeech;
import android.util.Log;
import java.util.Locale;

/**
 * 文字转语音管理器
 */
public class TTSManager {
    private static final String TAG = "TTSManager";
    private static TTSManager instance;
    private TextToSpeech tts;
    private boolean isInitialized = false;
    private final Context context;
    private final AudioManager audioManager;
    private boolean hasValidTTS = false;

    private TTSManager(Context context) {
        this.context = context.getApplicationContext();
        this.audioManager = (AudioManager) context.getSystemService(Context.AUDIO_SERVICE);
        initializeTTS();
    }

    public static synchronized TTSManager getInstance(Context context) {
        if (instance == null) {
            instance = new TTSManager(context);
        }
        return instance;
    }

    private void initializeTTS() {
        try {
            tts = new TextToSpeech(context, status -> {
                if (status == TextToSpeech.SUCCESS) {
                    try {
                        int result = tts.setLanguage(Locale.SIMPLIFIED_CHINESE);
                        if (result == TextToSpeech.LANG_MISSING_DATA ||
                                result == TextToSpeech.LANG_NOT_SUPPORTED) {
                            tts.setLanguage(Locale.getDefault());
                        }
                        tts.setSpeechRate(1.0f);
                        tts.setPitch(1.0f);
                        isInitialized = true;
                        hasValidTTS = true;
                        Log.d(TAG, "✅ TTS初始化成功");
                    } catch (Exception e) {
                        Log.w(TAG, "⚠️ TTS配置失败");
                        hasValidTTS = false;
                    }
                } else {
                    Log.w(TAG, "⚠️ TTS不可用");
                    hasValidTTS = false;
                }
            });
        } catch (Exception e) {
            Log.w(TAG, "⚠️ TTS创建失败");
            hasValidTTS = false;
        }
    }

    public void speak(String text) {
        if (text == null || text.isEmpty()) {
            return;
        }

        // 尝试使用TTS
        if (hasValidTTS && tts != null && isInitialized) {
            try {
                if (tts.isSpeaking()) {
                    tts.stop();
                }
                tts.speak(text, TextToSpeech.QUEUE_FLUSH, null);
                Log.d(TAG, "🔊 TTS朗读: " + text);
                return;
            } catch (Exception e) {
                Log.w(TAG, "TTS朗读异常");
            }
        }

        // 降级方案：仅记录日志，不播放提示音
        Log.d(TAG, "📢 [朗读]: " + text);
    }

    /**
     * 朗读并播放提示音（用于关键事件：启动、关闭等）
     */
    public void speakWithSound(String text) {
        speak(text);
        // 播放提示音
        playSystemSound();
    }

    private void playSystemSound() {
        try {
            if (audioManager != null) {
                audioManager.playSoundEffect(AudioManager.FX_KEY_CLICK, 1.0f);
            }
        } catch (Exception e) {
            Log.w(TAG, "系统提示音播放失败");
        }
    }

    public void speakAsync(String text) {
        new Thread(() -> speak(text)).start();
    }

    public void stop() {
        if (tts != null && tts.isSpeaking()) {
            tts.stop();
        }
    }

    public void release() {
        if (tts != null) {
            try {
                tts.stop();
                tts.shutdown();
            } catch (Exception e) {
                Log.w(TAG, "释放TTS异常");
            }
            tts = null;
            isInitialized = false;
        }
    }

    public boolean isSpeaking() {
        return tts != null && tts.isSpeaking();
    }

    public boolean isInitialized() {
        return isInitialized;
    }

    public boolean isReady() {
        return hasValidTTS || audioManager != null;
    }
}
