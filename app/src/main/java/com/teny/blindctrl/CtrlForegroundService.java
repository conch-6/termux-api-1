package com.teny.blindctrl;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.net.wifi.WifiManager;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.PowerManager;
import android.util.Log;

import androidx.core.app.NotificationCompat;

import com.termux.api.R;

/**
 * 常驻前台服务：托住被控端常连 TCP 服务，保证后台仍可被主控连上。
 *
 * 保活加固（鸿蒙/EMUI 这类会掐后台长连接 socket 的系统）：
 *  - 先 startForeground 再管端口：安卓要求 startForegroundService 后几秒内必须进前台，
 *    顺序反了会直接崩；也杜绝「通知常驻却没监听」的僵尸；
 *  - 除 PARTIAL_WAKE_LOCK 保 CPU 外，额外拿 WifiLock 保 WiFi 射频不被后台休眠掐掉；
 *  - 每 5s 看门狗自检：端口/accept 线程还活着就什么都不做（绝不顶掉已连的主控），
 *    被系统掐死才重建 server；bind 失败也留着通知由下一轮重试，不再自杀；
 *  - 划掉任务卡(onTaskRemoved)不 stopSelf，靠常驻通知 + START_STICKY 继续活着；
 *  - 通知标题/正文全空：用户明确要求「文字能不写就不写」，实测常驻正常。
 */
public class CtrlForegroundService extends Service {

    private static final String TAG = "BlindCtrl";
    private static final String CHANNEL = "blindctrl_keepalive";
    private static final int NOTI_ID = 1;
    private static final long WAKE_MS = 60 * 60 * 1000L;
    private static final long WAKE_REARM_MS = 50 * 60 * 1000L;
    private static final long GUARD_MS = 5000;     // 看门狗自检间隔
    private static final long FG_RETRY_MS = 1500;  // startForeground 失败后的重试间隔

    public static final String ACTION_START = "com.teny.blindctrl.START";
    public static final String ACTION_STOP = "com.teny.blindctrl.STOP";

    private CtrlLinkServer linkServer;
    private PowerManager.WakeLock wakeLock;
    private WifiManager.WifiLock wifiLock;
    private boolean started = false;      // 是否已成功进前台，用于幂等
    private volatile boolean stopped = false;  // stopAll 后置位，看门狗不再空转
    private final Handler handler = new Handler(Looper.getMainLooper());

    private final Runnable wakeRearm = new Runnable() {
        @Override public void run() {
            if (wakeLock != null) {
                try {
                    if (wakeLock.isHeld()) wakeLock.release();
                    wakeLock.acquire(WAKE_MS);
                } catch (Exception ignored) {}
                handler.postDelayed(this, WAKE_REARM_MS);
            }
        }
    };

    /** 看门狗：总开关开着就自检；端口活着静默跳过，死了才重建。
     *  不额外要求 started，否则首次 startForeground 失败时看门狗会永远不接管。
     *  stopAll 置 stopped 后直接收工，不再无限自投递空转。 */
    private final Runnable guard = new Runnable() {
        @Override public void run() {
            if (stopped) return;
            if (CtrlBus.isEnabled()) ensureForeground();
            if (!stopped) handler.postDelayed(this, GUARD_MS);
        }
    };

    @Override
    public void onCreate() {
        super.onCreate();
        CtrlBus.init(this);
        createChannel();
        // 尽早把前台通知挂出来：安卓要求 startForegroundService 后 5s 内必须 startForeground，
        // 放在 onCreate 最前面能避开个别系统在 onStartCommand 被延迟时直接判超时崩溃。
        // 只有总开关开着才挂，避免「开关已关、服务却被 ACTION_STOP 拉起」时闪一下通知。
        if (CtrlBus.isEnabled()) {
            try { startForeground(NOTI_ID, buildNotification()); }
            catch (Exception e) { Log.w(TAG, "onCreate startForeground 失败", e); }
        }
        armGuard();
    }

    /** 去重地挂上看门狗：stopAll 会摘掉它，服务复用/重新启动时再挂回来。 */
    private void armGuard() {
        handler.removeCallbacks(guard);
        handler.postDelayed(guard, GUARD_MS);
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        String action = intent != null ? intent.getAction() : null;
        if (ACTION_STOP.equals(action)) {
            stopAll();
            return START_NOT_STICKY;
        }
        // 关了总开关时绝不重新拉起服务/端口/通知
        if (!CtrlBus.isEnabled()) {
            stopAll();
            return START_NOT_STICKY;
        }
        // 关了又立刻开、服务实例还没被销毁时会被复用：必须把 stopped 复位，
        // 否则 ensureForeground/看门狗会以为已经停了、再也不拉起端口。
        stopped = false;
        ensureForeground();
        armGuard();   // 服务被系统重启后 onStartCommand 再跑时，重新挂上看门狗
        // bind/前台失败也不自杀：通知常驻，看门狗会隔 5s 再试
        return START_STICKY;
    }

    /**
     * 保证「前台通知 + 端口监听」都到位。幂等，可被 onStartCommand 与看门狗反复调用：
     *  - server 还活着 → 只 re-arm 前台通知与各种锁，绝不重建、不顶连接；
     *  - server 死了/没起 → 重新 bind 一个新 server；失败留通知，等下次重试。
     */
    private void ensureForeground() {
        if (stopped || !CtrlBus.isEnabled()) return;   // 双保险：绝不绕过总开关、停了就别再拉
        // 先保住进程优先级，再管端口：必须紧贴 startForegroundService 调用，且反复 re-arm
        // 能在部分 OEM 悄悄降级前台状态时把优先级抢回来
        try {
            startForeground(NOTI_ID, buildNotification());
        } catch (Exception e) {
            Log.w(TAG, "startForeground 失败，稍后重试", e);
            handler.postDelayed(() -> { if (!stopped && CtrlBus.isEnabled()) ensureForeground(); },
                    FG_RETRY_MS);
            return;
        }
        acquireWakeLock();
        acquireWifiLock();

        if (linkServer == null || !linkServer.isAlive()) {
            // 旧实例已经死了，关掉残留 socket 再重建
            if (linkServer != null) {
                try { linkServer.stop(); } catch (Exception ignored) {}
                linkServer = null;
                CtrlLinkServer.setInstance(null);
            }
            try {
                CtrlLinkServer srv = new CtrlLinkServer(CtrlBus.PORT, this);
                CtrlLinkServer.setInstance(srv);
                srv.start();
                linkServer = srv;
            } catch (Exception e) {
                Log.w(TAG, "端口重建失败，稍后重试", e);
                if (linkServer != null) {
                    try { linkServer.stop(); } catch (Exception ignored) {}
                    linkServer = null;
                    CtrlLinkServer.setInstance(null);
                }
            }
        }
        boolean alive = linkServer != null && linkServer.isAlive();
        // 只在状态真的变了才广播，避免看门狗每 5s 无谓推一次状态给主控
        if (CtrlBus.isServerRunning() != alive) CtrlBus.setServerRunning(alive);
        started = true;
    }

    private void stopAll() {
        stopped = true;
        handler.removeCallbacks(wakeRearm);
        handler.removeCallbacks(guard);
        releaseWakeLock();
        releaseWifiLock();
        // 无条件移除通知：onCreate 可能已先挂过通知、却还没走到 ensureForeground 置 started，
        // 此时若跳过 stopForeground 就会留下一条关不掉的常驻通知
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            stopForeground(STOP_FOREGROUND_REMOVE);
        } else {
            stopForeground(true);
        }
        started = false;
        if (linkServer != null) {
            linkServer.stop();
            linkServer = null;
            CtrlLinkServer.setInstance(null);
        }
        CtrlBus.setServerRunning(false);
        stopSelf();
    }

    private void acquireWakeLock() {
        if (wakeLock != null) return;
        try {
            PowerManager pm = (PowerManager) getSystemService(Context.POWER_SERVICE);
            wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "BlindCtrl:keepalive");
            wakeLock.setReferenceCounted(false);
            wakeLock.acquire(WAKE_MS);
            handler.removeCallbacks(wakeRearm);
            handler.postDelayed(wakeRearm, WAKE_REARM_MS);
        } catch (Exception e) {
            Log.w(TAG, "WakeLock 获取失败", e);
            wakeLock = null;
        }
    }

    private void releaseWakeLock() {
        if (wakeLock != null) {
            try {
                if (wakeLock.isHeld()) wakeLock.release();
            } catch (Exception ignored) {}
            wakeLock = null;
        }
    }

    /** 保 WiFi 射频不休眠：PARTIAL_WAKE_LOCK 只保 CPU，不保后台长连接。 */
    private void acquireWifiLock() {
        if (wifiLock != null) return;
        try {
            WifiManager wm = (WifiManager) getApplicationContext()
                    .getSystemService(Context.WIFI_SERVICE);
            if (wm == null) return;
            int mode = Build.VERSION.SDK_INT >= Build.VERSION_CODES.HONEYCOMB_MR2
                    ? WifiManager.WIFI_MODE_FULL_HIGH_PERF : WifiManager.WIFI_MODE_FULL;
            wifiLock = wm.createWifiLock(mode, "BlindCtrl:wifi");
            wifiLock.setReferenceCounted(false);
            wifiLock.acquire();
        } catch (Exception e) {
            Log.w(TAG, "WifiLock 获取失败", e);
            wifiLock = null;
        }
    }

    private void releaseWifiLock() {
        if (wifiLock != null) {
            try { if (wifiLock.isHeld()) wifiLock.release(); } catch (Exception ignored) {}
            wifiLock = null;
        }
    }

    private Notification buildNotification() {
        Intent i = new Intent(this, MainActivity.class);
        PendingIntent pi = PendingIntent.getActivity(this, 0, i,
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        // 文字能不写就不写：标题/正文全空，只留一条最小的常驻通知
        return new NotificationCompat.Builder(this, CHANNEL)
                .setSmallIcon(R.drawable.ic_noti)
                .setContentTitle("")
                .setContentText("")
                .setContentIntent(pi)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .build();
    }

    private void createChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationManager nm =
                    (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
            NotificationChannel ch = new NotificationChannel(CHANNEL, "鲸控",
                    NotificationManager.IMPORTANCE_LOW);
            nm.createNotificationChannel(ch);
        }
    }

    /** 从任务列表划掉也不主动退出：常驻通知 + START_STICKY 让服务继续活着。 */
    @Override
    public void onTaskRemoved(Intent rootIntent) {
        // 什么都不做，绝不 stopSelf
    }

    @Override
    public void onDestroy() {
        handler.removeCallbacks(guard);
        stopAll();
        super.onDestroy();
        Log.i(TAG, "前台服务 onDestroy，START_STICKY 等系统重启");
    }

    @Override
    public IBinder onBind(Intent intent) { return null; }
}
