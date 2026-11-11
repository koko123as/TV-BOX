package com.koko.cfjs;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.os.Build;
import android.os.IBinder;
import android.os.PowerManager;

import androidx.annotation.Nullable;

/**
 * Foreground service that keeps the process alive while the HTTP API (and optionally the tunnel) runs,
 * so spiders work with no Activity open. Stopping it stops the tunnel and the API and releases the
 * spider loaders; nothing of upstream is touched.
 */
public class SpiderService extends Service {

    public static final String ACTION_START = "com.koko.cfjs.START";
    public static final String ACTION_STOP = "com.koko.cfjs.STOP";
    private static final String CHANNEL = "cfjs";
    private static final int ID = 19978;
    private static volatile boolean running;
    private PowerManager.WakeLock wakeLock;

    public static boolean isRunning() {
        return running;
    }

    public static void start(Context context) {
        Intent intent = new Intent(context, SpiderService.class).setAction(ACTION_START);
        if (Build.VERSION.SDK_INT >= 26) context.startForegroundService(intent);
        else context.startService(intent);
    }

    public static void stop(Context context) {
        context.startService(new Intent(context, SpiderService.class).setAction(ACTION_STOP));
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && ACTION_STOP.equals(intent.getAction())) {
            stopSelf();
            return START_NOT_STICKY;
        }
        foreground();
        running = true;
        acquire();
        new Thread(this::boot, "cfjs-boot").start();
        return START_STICKY;
    }

    private void boot() {
        BridgeServer.start();
        String url = Prefs.configUrl();
        if (!Engine.get().loaded() && !url.isEmpty()) {
            try {
                Engine.get().load(url);
            } catch (Exception e) {
                BridgeLog.w("config load failed: " + e.getMessage());
            }
        }
    }

    private void foreground() {
        NotificationManager manager = getSystemService(NotificationManager.class);
        if (Build.VERSION.SDK_INT >= 26) manager.createNotificationChannel(new NotificationChannel(CHANNEL, "Spider 服务", NotificationManager.IMPORTANCE_LOW));
        PendingIntent open = PendingIntent.getActivity(this, 0, new Intent(this, MainActivity.class), PendingIntent.FLAG_IMMUTABLE);
        Notification.Builder builder = Build.VERSION.SDK_INT >= 26 ? new Notification.Builder(this, CHANNEL) : new Notification.Builder(this);
        Notification notification = builder.setSmallIcon(android.R.drawable.stat_sys_upload_done).setContentTitle("Spider 服务运行中").setContentText("HTTP API 127.0.0.1:" + BridgeServer.PORT).setContentIntent(open).setOngoing(true).build();
        if (Build.VERSION.SDK_INT >= 34) startForeground(ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE);
        else startForeground(ID, notification);
    }

    private void acquire() {
        if (wakeLock != null) return;
        PowerManager power = getSystemService(PowerManager.class);
        wakeLock = power.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "cfjs:service");
        wakeLock.setReferenceCounted(false);
        wakeLock.acquire();
    }

    @Override
    public void onDestroy() {
        running = false;
        TunnelManager.stop();
        BridgeServer.shutdown();
        Engine.get().shutdown();
        if (wakeLock != null && wakeLock.isHeld()) wakeLock.release();
        wakeLock = null;
        BridgeLog.i("service stopped, resources released");
        super.onDestroy();
    }

    @Nullable
    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}
