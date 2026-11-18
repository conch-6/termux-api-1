package com.teny.blindctrl;

import android.content.Context;
import android.content.SharedPreferences;

/** 全局状态与 UI 通知中继。 */
public final class CtrlBus {

    public static final int PORT = 8898;
    /** 画面回传独立端口：和指令分开，画面卡住/断流不影响触摸指令 */
    public static final int PORT_VIDEO = 8899;

    private static final String PREF = "blindctrl";
    private static final String KEY_ENABLED = "enabled";
    public static final String KEY_MASTER_ADDR = "master_addr";

    private static volatile boolean enabled = false;
    private static volatile boolean serverRunning = false;
    private static volatile boolean clientConnected = false;
    private static volatile Listener listener = null;
    private static volatile boolean inited = false;

    public interface Listener { void onStateChanged(); }

    private CtrlBus() {}

    /** 只从 SharedPreferences 读一次。之后 enabled 是唯一运行时来源，
     *  避免 Service.onCreate 再次 init 把用户刚改的开关状态覆盖回去。 */
    public static synchronized void init(Context c) {
        if (inited) return;
        SharedPreferences sp = c.getApplicationContext()
                .getSharedPreferences(PREF, Context.MODE_PRIVATE);
        enabled = sp.getBoolean(KEY_ENABLED, false);
        inited = true;
    }

    public static SharedPreferences prefs(Context c) {
        return c.getApplicationContext().getSharedPreferences(PREF, Context.MODE_PRIVATE);
    }

    public static void setEnabled(Context c, boolean v) {
        enabled = v;
        prefs(c).edit().putBoolean(KEY_ENABLED, v).apply();
        notifyChanged();
    }

    public static boolean isEnabled() { return enabled; }
    public static boolean isAccessibilityConnected() {
        return CtrlAccessibilityService.isConnected();
    }
    public static boolean isServerRunning() { return serverRunning; }
    public static boolean isClientConnected() { return clientConnected; }

    static void setServerRunning(boolean v) { serverRunning = v; notifyChanged(); }
    static void setClientConnected(boolean v) { clientConnected = v; notifyChanged(); }
    static void onAccessibilityChanged(boolean connected) { notifyChanged(); }

    public static void setListener(Listener l) { listener = l; }

    private static void notifyChanged() {
        Listener l = listener;
        if (l != null) l.onStateChanged();
        // 状态变化时把最新 enabled/accessibility 主动推给已连的主控
        CtrlLinkServer.pushState();
    }
}
