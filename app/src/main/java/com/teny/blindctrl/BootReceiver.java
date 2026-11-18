package com.teny.blindctrl;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Build;
import android.util.Log;

/**
 * 开机后把被控端前台服务拉回来：手机重启过、又开过总开关时，不用手动打开 APP。
 * 只在「总开关 = 开」时才起；用户关了开关、或系统不让自启动，就完全不动作。
 * 只处理解锁后发出的开机广播，不碰 LOCKED_BOOT_COMPLETED（那会儿凭据加密存储还读不了）。
 */
public class BootReceiver extends BroadcastReceiver {

    private static final String TAG = "BlindCtrl";

    @Override
    public void onReceive(Context c, Intent i) {
        String action = i != null ? i.getAction() : null;
        if (!Intent.ACTION_BOOT_COMPLETED.equals(action)
                && !"android.intent.action.QUICKBOOT_POWERON".equals(action)) {
            return;
        }
        try {
            // 直接读偏好，不依赖 CtrlBus 的静态缓存：开机是全新进程，init 未必跑过
            SharedPreferences sp = c.getApplicationContext()
                    .getSharedPreferences("blindctrl", Context.MODE_PRIVATE);
            if (!sp.getBoolean("enabled", false)) return;

            Intent s = new Intent(c, CtrlForegroundService.class)
                    .setAction(CtrlForegroundService.ACTION_START);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                c.startForegroundService(s);
            } else {
                c.startService(s);
            }
        } catch (Exception e) {
            // 个别系统会拦后台起服务，拦了就什么都不做，等用户手动打开 APP
            Log.w(TAG, "开机拉起服务被拦", e);
        }
    }
}
