package com.teny.blindctrl;

import android.util.Log;

import org.json.JSONObject;

/**
 * 把一行手势指令(JSON)派发到无障碍引擎。HTTP 时代 handleCmd 的复用体。
 * 返回「是否已入队到无障碍主线程」。被控端 fire-and-forget：不等待结果、不回包。
 */
public final class CmdDispatcher {

    private static final String TAG = "BlindCtrl";

    private CmdDispatcher() {}

    private static void require(JSONObject c, String... keys) {
        for (String k : keys) {
            if (!c.has(k)) throw new IllegalArgumentException("缺少字段 " + k);
        }
    }

    public static boolean dispatch(String line) {
        try {
            JSONObject c = new JSONObject(line);
            String t = c.optString("t", "");
            if (!CtrlBus.isEnabled()) return false;
            if (!CtrlBus.isAccessibilityConnected()) return false;
            // 先校验字段，避免缺 x/y 时按 0 打到屏幕左上角
            switch (t) {
                case "down": case "move": case "up": case "long": case "tap": case "lstart":
                    require(c, "x", "y");
                    break;
                case "swipe":
                    require(c, "x1", "y1", "x2", "y2");
                    break;
                case "lend":
                    // 长按结束信号不带坐标
                    break;
                default:
                    return false;
            }
            switch (t) {
                case "down":
                    return CtrlAccessibilityService.touchDown(
                            (float) c.optDouble("x"), (float) c.optDouble("y"));
                case "move":
                    return CtrlAccessibilityService.touchMove(
                            (float) c.optDouble("x"), (float) c.optDouble("y"));
                case "up":
                    return CtrlAccessibilityService.touchUp(
                            (float) c.optDouble("x"), (float) c.optDouble("y"));
                case "swipe":
                    return CtrlAccessibilityService.swipe(
                            (float) c.optDouble("x1"), (float) c.optDouble("y1"),
                            (float) c.optDouble("x2"), (float) c.optDouble("y2"),
                            c.optLong("dur", 300));
                case "long":
                    return CtrlAccessibilityService.longPress(
                            (float) c.optDouble("x"), (float) c.optDouble("y"),
                            c.optLong("dur", 800));
                case "tap":
                    // 点击默认 60ms，限制在 20..200ms 的合理短区间
                    return CtrlAccessibilityService.tap(
                            (float) c.optDouble("x"), (float) c.optDouble("y"),
                            Math.max(20, Math.min(200, c.optLong("dur", 60))));
                case "lstart":
                    // 长按开始：带按下坐标，被控端起自维持按住手势
                    return CtrlAccessibilityService.longHoldStart(
                            (float) c.optDouble("x"), (float) c.optDouble("y"));
                case "lend":
                    // 长按结束：不带坐标，被控端收到才抬手
                    return CtrlAccessibilityService.longHoldEnd();
                default:
                    return false;
            }
        } catch (Exception e) {
            Log.w(TAG, "指令解析失败: " + e.getMessage());
            return false;
        }
    }
}
