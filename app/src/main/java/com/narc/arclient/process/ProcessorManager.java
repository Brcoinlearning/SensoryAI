package com.narc.arclient.process;

import static android.content.ContentValues.TAG;
import static com.narc.arclient.enums.ProcessorEnums.MONITOR_FREQUENCY;

import android.content.Context;
import android.os.BatteryManager;
import android.util.Log;

import com.narc.arclient.MainActivity;
// [已删除] import com.narc.arclient.network.RemoteRecognizeServiceStub;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.security.SecureRandom;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

public class ProcessorManager {
    public static final ThreadPoolExecutor normalExecutor = new ThreadPoolExecutor(3, 10, 20, TimeUnit.SECONDS, new LinkedBlockingQueue<>());
    public static final ThreadPoolExecutor imageCopyExecutor = new ThreadPoolExecutor(1, 1, 0, TimeUnit.SECONDS, new LinkedBlockingQueue<>());
    public static final ScheduledThreadPoolExecutor scheduledExecutor = new ScheduledThreadPoolExecutor(1);

    private static ProcessorManager processorManager;

    public static String deviceSerialNumber;

    public static final AtomicInteger TASK_ID = new AtomicInteger(1);

    static {
        final String CHARACTERS = "ABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789";
        final int SERIAL_LENGTH = 6;
        final SecureRandom RANDOM = new SecureRandom();

        StringBuilder serial = new StringBuilder(SERIAL_LENGTH);
        for (int i = 0; i < SERIAL_LENGTH; i++) {
            int index = RANDOM.nextInt(CHARACTERS.length());
            serial.append(CHARACTERS.charAt(index));
        }
        deviceSerialNumber = serial.toString();
    }

    private MainActivity mainActivity;

    public static void init(MainActivity mainActivity) {
        processorManager = new ProcessorManager(mainActivity);
    }

    private ProcessorManager(MainActivity mainActivity) {
        this.mainActivity = mainActivity;
        this.monitor = new Monitor();
    }

    private Monitor monitor;

    private class Monitor implements Runnable {
        private Monitor() {
            startMonitor();
        }

        private void startMonitor() {
            int monitorPeriod = 1000 / MONITOR_FREQUENCY;
            scheduledExecutor.scheduleWithFixedDelay(this, monitorPeriod, monitorPeriod, TimeUnit.MILLISECONDS);
        }

        @Override
        public void run() {
            monitorSystemResources();
            monitorThreadPool();
        }

        private void monitorThreadPool() {
            // 只是打印日志，保留
            int normalExecutorQueueSize = normalExecutor.getQueue().size();
            int imageCopyExecutorQueueSize = imageCopyExecutor.getQueue().size();
            Log.d(TAG, String.format("normalExecutor queue size: %d imageCopyExecutor queue size: %d", normalExecutorQueueSize, imageCopyExecutorQueueSize));
        }

        private void monitorSystemResources() {
            BatteryManager batteryManager = (BatteryManager) mainActivity.getSystemService(Context.BATTERY_SERVICE);
            String batteryLevel = batteryManager.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY) + "";
            String cpuUsage = null;
            String memUsage = null;

            try {
                String[] cmd = {
                        "/system/bin/top", "-n", "1"
                };
                Process process = Runtime.getRuntime().exec(cmd);
                BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()));
                String line;
                while ((line = reader.readLine()) != null) {
                    if (line.contains("com.narc")) {
                        String[] parts = line.split("\\s+");
                        // 注意：这里解析可能会因为不同手机系统 top 命令输出格式不同而报错，如果报错建议try-catch整个块
                        if (parts.length > 10) {
                            cpuUsage = String.format("%.2f", Double.parseDouble(parts[9]));
                            memUsage = String.format("%.2f", Double.parseDouble(parts[10]));
                            Log.d(TAG, String.format("CPU usage: %s MEM usage: %s battery level: %s", cpuUsage, memUsage, batteryLevel));

                            // [已删除] RemoteRecognizeServiceStub.getInstance().systemStateReport(...)
                        }
                        break;
                    }
                }
            } catch (Exception e) {
                Log.e(TAG, "Error getting CPU usage and MEM usage", e);
            }
        }
    }

    public static ProcessorManager getInstance() {
        return processorManager;
    }
}