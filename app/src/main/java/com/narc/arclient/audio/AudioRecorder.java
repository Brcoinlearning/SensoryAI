package com.narc.arclient.audio;

import android.annotation.SuppressLint;
import android.content.Context;
import android.media.AudioFormat;
import android.media.AudioRecord;
import android.media.MediaRecorder;
import android.util.Log;

import com.narc.arclient.network.WebSocketManager;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

public class AudioRecorder {
    private static final String TAG = "AudioRecorder";
    // 标准配置：16k采样率，单声道，16位深度 (协议要求 PCM16)
    private static final int SAMPLE_RATE = 16000;
    private static final int CHANNEL_CONFIG = AudioFormat.CHANNEL_IN_MONO;
    private static final int AUDIO_FORMAT = AudioFormat.ENCODING_PCM_16BIT;

    private AudioRecord audioRecord;
    private boolean isRecording = false;
    private Thread recordingThread;
    private int bufferSize;
    private FileOutputStream audioOut;
    private long audioBytesWritten = 0;
    private String audioFilePath = null;
    private Context appContext;

    private static AudioRecorder instance;

    public static synchronized AudioRecorder getInstance() {
        if (instance == null) {
            instance = new AudioRecorder();
        }
        return instance;
    }

    @SuppressLint("MissingPermission") // 权限在 Activity 中检查
    public void start(Context context) {
        if (isRecording)
            return;

        if (context == null) {
            Log.e(TAG, "Context 为空，无法创建本地音频文件");
            return;
        }
        appContext = context.getApplicationContext();

        bufferSize = AudioRecord.getMinBufferSize(SAMPLE_RATE, CHANNEL_CONFIG, AUDIO_FORMAT);
        audioRecord = new AudioRecord(MediaRecorder.AudioSource.MIC, SAMPLE_RATE, CHANNEL_CONFIG, AUDIO_FORMAT,
                bufferSize);

        if (audioRecord.getState() != AudioRecord.STATE_INITIALIZED) {
            Log.e(TAG, "AudioRecord 初始化失败");
            return;
        }

        // 1. 连接 WebSocket
        WebSocketManager.getInstance().connect();

        // 1.5 创建本地音频文件 (WAV)
        try {
            File dir = appContext.getExternalFilesDir("audio_debug");
            if (dir != null && !dir.exists()) {
                dir.mkdirs();
            }
            String ts = new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(new Date());
            File audioFile = new File(dir, "audio_" + ts + ".wav");
            audioFilePath = audioFile.getAbsolutePath();
            audioOut = new FileOutputStream(audioFile);
            writeWavHeader(audioOut, SAMPLE_RATE, CHANNEL_CONFIG, AUDIO_FORMAT);
            Log.d(TAG, "本地录音文件: " + audioFilePath);
        } catch (Exception e) {
            Log.e(TAG, "创建本地音频文件失败", e);
        }

        audioRecord.startRecording();
        isRecording = true;

        // 2. 开启线程读取音频并发送
        recordingThread = new Thread(() -> {
            byte[] buffer = new byte[bufferSize];
            while (isRecording) {
                int readResult = audioRecord.read(buffer, 0, bufferSize);
                if (readResult > 0) {
                    // 发送给服务器
                    WebSocketManager.getInstance().sendAudio(buffer, readResult);

                    // 保存到本地文件
                    if (audioOut != null) {
                        try {
                            audioOut.write(buffer, 0, readResult);
                            audioBytesWritten += readResult;
                        } catch (IOException ioe) {
                            Log.e(TAG, "本地录音写入失败", ioe);
                        }
                    }
                }
            }
        });
        recordingThread.start();
        Log.d(TAG, "开始录音并推流...");
    }

    public void stop() {
        if (!isRecording)
            return;

        isRecording = false;
        try {
            if (recordingThread != null) {
                recordingThread.join();
                recordingThread = null;
            }
            if (audioRecord != null) {
                audioRecord.stop();
                audioRecord.release();
                audioRecord = null;
            }
        } catch (Exception e) {
            Log.e(TAG, "停止录音异常", e);
        }

        // 3. 写回 WAV 头并关闭文件
        if (audioOut != null) {
            try {
                updateWavHeader(audioFilePath, audioBytesWritten, SAMPLE_RATE, CHANNEL_CONFIG, AUDIO_FORMAT);
                audioOut.close();
                Log.d(TAG, "本地录音完成，文件: " + audioFilePath);
            } catch (Exception e) {
                Log.e(TAG, "关闭本地音频文件失败", e);
            } finally {
                audioOut = null;
                audioBytesWritten = 0;
                audioFilePath = null;
            }
        }

        // 4. 发送结束信号
        WebSocketManager.getInstance().sendFinish();
        Log.d(TAG, "录音停止，发送结束帧");
    }

    // ============= WAV 相关辅助方法 =============
    private void writeWavHeader(FileOutputStream out, int sampleRate, int channelConfig, int audioFormat)
            throws IOException {
        int channels = (channelConfig == AudioFormat.CHANNEL_IN_MONO) ? 1 : 2;
        int bitsPerSample = (audioFormat == AudioFormat.ENCODING_PCM_16BIT) ? 16 : 8;
        byte[] header = new byte[44];

        // RIFF/WAVE 头
        header[0] = 'R';
        header[1] = 'I';
        header[2] = 'F';
        header[3] = 'F';
        // file size placeholder (4-7)
        header[8] = 'W';
        header[9] = 'A';
        header[10] = 'V';
        header[11] = 'E';
        // fmt chunk
        header[12] = 'f';
        header[13] = 'm';
        header[14] = 't';
        header[15] = ' ';
        header[16] = 16;
        header[17] = 0;
        header[18] = 0;
        header[19] = 0; // PCM chunk size
        header[20] = 1;
        header[21] = 0; // PCM format
        header[22] = (byte) channels;
        header[23] = 0;
        header[24] = (byte) (sampleRate & 0xff);
        header[25] = (byte) ((sampleRate >> 8) & 0xff);
        header[26] = (byte) ((sampleRate >> 16) & 0xff);
        header[27] = (byte) ((sampleRate >> 24) & 0xff);
        int byteRate = sampleRate * channels * bitsPerSample / 8;
        header[28] = (byte) (byteRate & 0xff);
        header[29] = (byte) ((byteRate >> 8) & 0xff);
        header[30] = (byte) ((byteRate >> 16) & 0xff);
        header[31] = (byte) ((byteRate >> 24) & 0xff);
        int blockAlign = channels * bitsPerSample / 8;
        header[32] = (byte) (blockAlign & 0xff);
        header[33] = (byte) ((blockAlign >> 8) & 0xff);
        header[34] = (byte) bitsPerSample;
        header[35] = 0;
        // data chunk
        header[36] = 'd';
        header[37] = 'a';
        header[38] = 't';
        header[39] = 'a';
        // data size placeholder (40-43)

        out.write(header, 0, 44);
    }

    private void updateWavHeader(String filePath, long audioDataBytes, int sampleRate, int channelConfig,
            int audioFormat) throws IOException {
        if (filePath == null)
            return;
        int channels = (channelConfig == AudioFormat.CHANNEL_IN_MONO) ? 1 : 2;
        int bitsPerSample = (audioFormat == AudioFormat.ENCODING_PCM_16BIT) ? 16 : 8;
        int byteRate = sampleRate * channels * bitsPerSample / 8;
        long dataSize = audioDataBytes;
        long riffSize = dataSize + 36;

        // Use RandomAccessFile to patch header
        try (java.io.RandomAccessFile raf = new java.io.RandomAccessFile(filePath, "rw")) {
            // ChunkSize
            raf.seek(4);
            raf.write(new byte[] {
                    (byte) (riffSize & 0xff),
                    (byte) ((riffSize >> 8) & 0xff),
                    (byte) ((riffSize >> 16) & 0xff),
                    (byte) ((riffSize >> 24) & 0xff)
            });
            // Subchunk2Size
            raf.seek(40);
            raf.write(new byte[] {
                    (byte) (dataSize & 0xff),
                    (byte) ((dataSize >> 8) & 0xff),
                    (byte) ((dataSize >> 16) & 0xff),
                    (byte) ((dataSize >> 24) & 0xff)
            });
            // ByteRate
            raf.seek(28);
            raf.write(new byte[] {
                    (byte) (byteRate & 0xff),
                    (byte) ((byteRate >> 8) & 0xff),
                    (byte) ((byteRate >> 16) & 0xff),
                    (byte) ((byteRate >> 24) & 0xff)
            });
            // BlockAlign
            int blockAlign = channels * bitsPerSample / 8;
            raf.seek(32);
            raf.write(new byte[] {
                    (byte) (blockAlign & 0xff),
                    (byte) ((blockAlign >> 8) & 0xff)
            });
        }
    }
}