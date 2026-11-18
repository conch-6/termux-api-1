package com.teny.blindctrl;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.GestureDescription;
import android.graphics.Path;
import android.graphics.Point;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.Display;
import android.view.WindowManager;
import android.view.accessibility.AccessibilityEvent;

/**
 * 通过无障碍服务向屏幕注入手势。支持滑动、长按与点击，不读取屏幕内容。
 *
 * 实时拖动用官方续手势(continueStroke)，采用「等上一段完成回调后再派发下一段」的
 * 串行链：down 派发 willContinue=true 的第 0 段，move 只更新目标坐标，
 * 每段 onCompleted 后续一段 MOVE(或零位移保活段)，up/超时接一段 willContinue=false 收尾抬手。
 *
 * 设计要点（针对静态审查 + AOSP 源码查证修复）：
 *  - 所有状态只在主线程(handler)读写，外部指令统一 post 进来，无锁竞争。
 *  - 收尾段直接 dispatchGesture，不经过任何 seq 门禁/延迟 post，保证必达、pointer 一定抬起。
 *  - 打断(新 down / swipe / long)时先把在途拖动收尾，等收尾回调再启动新手势，严格串行。
 *  - 续链在 onCompleted 里串行派发下一段：AOSP MotionEventInjectorTest 证明此合法时序
 *    pointer 全程不抬，系统无续段超时；CONT_MAX_MS 只是客户端自己的失联保险(主控心跳会续期)。
 *  - 每段 Path 的 moveTo 必须与上一段终点像素级相等(否则系统插 ACTION_CANCEL 抬手)，
 *    故零位移用真正零长度 lineTo，绝不补 1px。末段 willContinue=false 才发 ACTION_UP。
 *  - 长按持有(lstart/lend)与拖动共用续链，但 holdLong 时不检查失联超时、续段一律原地
 *    零长度保活，由被控端自己一直按到 lend 才抬手，与主控心跳/连接无关（不实时流）。
 */
public class CtrlAccessibilityService extends AccessibilityService {

    private static final String TAG = "BlindCtrl";
    private static final long CONT_MAX_MS = 1500; // 超过这么久没收到坐标就自动抬手
    private static final long SEG_MS = 60;        // 每段续手势时长
    private static final long HOLD_MAX_MS = 5 * 60 * 1000L; // 长按持有兜底上限：lend 彻底丢失时别永远按着

    private static volatile CtrlAccessibilityService sInstance = null;

    private final Handler handler = new Handler(Looper.getMainLooper());

    // ===== 以下状态只在主线程(handler)访问 =====
    private GestureDescription.StrokeDescription lastStroke = null; // 最近派发的 willContinue 段
    private int curX = 0, curY = 0;   // 手指当前已派发到的位置
    private int tgtX = 0, tgtY = 0;   // 网页给出的最新目标
    private int upX = 0, upY = 0;     // 抬手点
    private boolean inGesture = false; // 拖动手指是否按下
    private boolean holdLong = false; // 长按持有：被控端自驱动续链一直按，不依赖主控心跳
    private long holdStartAt = 0;     // 长按持有起点，用于 HOLD_MAX_MS 兜底
    private int holdX = 0, holdY = 0; // 长按按下的屏幕坐标（取消后原地重起用）
    private boolean wantUp = false;    // 已请求抬手
    private boolean wantInterrupt = false; // 打断请求：优先收尾，再跑 pendingStart
    private boolean awaiting = false;  // 已派发一段、正等 onCompleted/onCancelled
    private long contDeadline = 0;
    private Runnable pendingStart = null; // 收尾完成后要启动的新手势（打断串行）

    // volatile：HTTP 工作线程经 screenSize() 读
    private volatile int screenW = 1080;
    private volatile int screenH = 2400;

    public static boolean isConnected() { return sInstance != null; }

    @Override
    protected void onServiceConnected() {
        super.onServiceConnected();
        sInstance = this;
        measureScreen();
        Log.i(TAG, "无障碍已连接，屏幕基准 " + screenW + "x" + screenH);
        CtrlBus.onAccessibilityChanged(true);
    }

    @Override
    public void onDestroy() {
        // onDestroy 在主线程。尽力收尾，避免服务退出时 pointer 还按着。
        finishInFlightOnDestroy();
        sInstance = null;
        CtrlBus.onAccessibilityChanged(false);
        super.onDestroy();
    }

    private void finishInFlightOnDestroy() {
        try {
            if (inGesture && lastStroke != null) {
                Path seg = segPath(curX, curY, curX, curY);
                GestureDescription.StrokeDescription term =
                        lastStroke.continueStroke(seg, 0, SEG_MS, false);
                dispatchGesture(build(term), null, null);
            }
        } catch (Exception e) {
            Log.w(TAG, "onDestroy 收尾异常", e);
        }
        inGesture = false; holdLong = false; wantUp = false; wantInterrupt = false;
        awaiting = false; lastStroke = null; pendingStart = null;
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) { /* 不关心任何事件 */ }
    @Override
    public void onInterrupt() { /* 不关心 */ }

    /** 取当前真实屏幕宽高：横屏时 p.x=2400/p.y=1080，直接用，不再 min/max 强行转成竖屏。
     *  触摸板是竖屏但鲸控没有画面，语义是「按比例铺满当前整块屏幕」，
     *  所以横竖屏都各自把 0..1080 / 0..2400 线性映射到当前真实宽 / 高即可。 */
    private void measureScreen() {
        try {
            WindowManager wm = (WindowManager) getSystemService(WINDOW_SERVICE);
            Display d = wm.getDefaultDisplay();
            Point p = new Point();
            d.getRealSize(p);
            if (p.x > 0 && p.y > 0) {
                screenW = p.x;
                screenH = p.y;
                return;
            }
        } catch (Exception e) {
            Log.w(TAG, "取屏幕尺寸失败，用默认 1080x2400", e);
        }
        screenW = 1080;
        screenH = 2400;
    }

    /** 主控逻辑坐标 1080x2400 等比映射到当前真实屏幕（x→宽, y→高）。
     *  用归一化比例 rx/ry 再乘当前宽高，横竖屏都能铺满，不会像旧版 min/max 那样
     *  横屏时 y 落到 1080 屏外、x 只占 45% 宽。 */
    private Point map(float x, float y) {
        float rx = Math.max(0, Math.min(1, x / 1080f));
        float ry = Math.max(0, Math.min(1, y / 2400f));
        return new Point(Math.round(rx * screenW), Math.round(ry * screenH));
    }

    /**
     * 构造一段 MOVE 路径。起点(fromX,fromY)必须精确等于上一段的终点，
     * 否则 AOSP prepareToContinueOldGesture 会判「points mismatch」并插入 ACTION_CANCEL。
     * 目标与起点重合时用真正的零长度 lineTo（AOSP 会取 moveTo 点、产生有效采样），
     * 绝不能补 1px：那会让本段终点变成 (fromX+1,fromY)，与 curX/curY 和下一段 moveTo 错开，
     * 整条续手势链当场断裂。
     */
    private Path segPath(int fromX, int fromY, int toX, int toY) {
        Path p = new Path();
        p.moveTo(fromX, fromY);
        p.lineTo(toX, toY);
        return p;
    }

    private GestureDescription build(GestureDescription.StrokeDescription s) {
        return new GestureDescription.Builder().addStroke(s).build();
    }

    /* ===================== 外部入口（HTTP 工作线程调用）===================== */

    public static boolean swipe(float x1, float y1, float x2, float y2, long duration) {
        CtrlAccessibilityService s = sInstance;
        if (s == null) return false;
        s.handler.post(() -> s.requestStart(
                () -> s.startIndependentSwipe(x1, y1, x2, y2, duration)));
        return true;
    }

    public static boolean longPress(float x, float y, long duration) {
        CtrlAccessibilityService s = sInstance;
        if (s == null) return false;
        s.handler.post(() -> s.requestStart(
                () -> s.startIndependentLong(x, y, duration)));
        return true;
    }

    /** 长按开始：被控端起一个自维持的按住手势，只此一条信号，此后不实时流。 */
    public static boolean longHoldStart(float x, float y) {
        CtrlAccessibilityService s = sInstance;
        if (s == null) return false;
        s.handler.post(() -> s.requestStart(() -> s.startLongHold(x, y)));
        return true;
    }

    /** 长按结束：不带坐标，被控端收到才抬手。 */
    public static boolean longHoldEnd() {
        CtrlAccessibilityService s = sInstance;
        if (s == null) return false;
        s.handler.post(() -> s.onLongHoldEnd());
        return true;
    }

    public static boolean tap(float x, float y, long duration) {
        CtrlAccessibilityService s = sInstance;
        if (s == null) return false;
        s.handler.post(() -> s.requestStart(
                () -> s.startIndependentTap(x, y, duration)));
        return true;
    }

    public static boolean touchDown(float x, float y) {
        CtrlAccessibilityService s = sInstance;
        if (s == null) return false;
        s.handler.post(() -> s.requestStart(() -> s.startDrag(x, y)));
        return true;
    }

    public static boolean touchMove(float x, float y) {
        CtrlAccessibilityService s = sInstance;
        if (s == null) return false;
        s.handler.post(() -> s.onMove(x, y));
        return true;
    }

    public static boolean touchUp(float x, float y) {
        CtrlAccessibilityService s = sInstance;
        if (s == null) return false;
        s.handler.post(() -> s.onUp(x, y));
        return true;
    }

    /* ===================== 主线程：状态机 ===================== */

    /**
     * 请求启动一个新手势。若正有拖动/独立手势在途，先记下待启动动作，
     * 等当前 gesture 收尾回调后再启动，严格串行，避免并发 gesture 被系统拒绝。
     */
    private void requestStart(Runnable start) {
        pendingStart = start; // 覆盖：连续打断只保留最后一条
        if (holdLong && !wantUp) {  // 长按持有期间：新手势一律排队，不打断按住（等 lend 再执行）
            return;
        }
        if (inGesture && lastStroke != null) {
            wantInterrupt = true;
            if (!awaiting) pump(); // 没段在飞，立即收尾
        } else if (awaiting) {
            // 独立手势在飞，等 terminalCallback 回调 pump
        } else {
            Runnable r = pendingStart;
            pendingStart = null;
            r.run();
        }
    }

    /** 启动一次独立滑动（完整单段手势，willContinue=false）。 */
    private void startIndependentSwipe(float x1, float y1, float x2, float y2, long duration) {
        measureScreen();
        Point a = map(x1, y1), b = map(x2, y2);
        Path path = new Path();
        path.moveTo(a.x, a.y);
        path.lineTo(b.x, b.y);
        long dur = Math.max(50, Math.min(3000, duration));
        GestureDescription.StrokeDescription stroke =
                new GestureDescription.StrokeDescription(path, 0, dur, false);
        awaiting = true;
        try {
            dispatchGesture(build(stroke), terminalCallback(), null);
        } catch (Exception e) {
            Log.w(TAG, "swipe 派发失败", e);
            awaiting = false;
            pump();
        }
    }

    /** 启动一次独立长按（原地保持，willContinue=false）。 */
    private void startIndependentLong(float x, float y, long duration) {
        measureScreen();
        Point a = map(x, y);
        Path path = segPath(a.x, a.y, a.x, a.y);
        long dur = Math.max(400, Math.min(5000, duration));
        GestureDescription.StrokeDescription stroke =
                new GestureDescription.StrokeDescription(path, 0, dur, false);
        awaiting = true;
        try {
            dispatchGesture(build(stroke), terminalCallback(), null);
        } catch (Exception e) {
            Log.w(TAG, "long 派发失败", e);
            awaiting = false;
            pump();
        }
    }

    /** 启动长按持有：派发第 0 段(willContinue=true)，后续由 chainCallback 自驱动续
     *  零长度保活段，不检查 CONT_MAX_MS 超时，直到 lend 才收尾抬手。 */
    private void startLongHold(float x, float y) {
        measureScreen();
        wantInterrupt = false;
        Point a = map(x, y);
        curX = tgtX = holdX = a.x; curY = tgtY = holdY = a.y;
        inGesture = true; holdLong = true; wantUp = false;
        holdStartAt = System.currentTimeMillis();
        contDeadline = System.currentTimeMillis() + CONT_MAX_MS; // holdLong 时 pump 不检查，仅占位
        Path p0 = segPath(curX, curY, curX, curY);
        GestureDescription.StrokeDescription s0 =
                new GestureDescription.StrokeDescription(p0, 0, SEG_MS, true);
        lastStroke = s0;
        awaiting = true;
        try {
            dispatchGesture(build(s0), holdChainCallback(), null);
        } catch (Exception e) {
            Log.w(TAG, "长按持有派发失败", e);
            inGesture = false; holdLong = false; lastStroke = null; awaiting = false;
            pump();
        }
    }

    /** 启动一次独立点击（零长度 stroke，短时长，willContinue=false）。
     *  零长度 stroke(moveTo == lineTo) + 短时长(~60ms) + willContinue=false 是
     *  无障碍服务注入点击的标准做法：系统按下(ACTION_DOWN)后立即抬起(ACTION_UP)，
     *  startIndependentLong 已证明该写法在 800ms 下可用，这里只是把时长缩到点击量级。 */
    private void startIndependentTap(float x, float y, long duration) {
        measureScreen();
        Point a = map(x, y);
        Path path = segPath(a.x, a.y, a.x, a.y);
        long dur = Math.max(20, Math.min(200, duration));
        GestureDescription.StrokeDescription stroke =
                new GestureDescription.StrokeDescription(path, 0, dur, false);
        awaiting = true;
        try {
            dispatchGesture(build(stroke), terminalCallback(), null);
        } catch (Exception e) {
            Log.w(TAG, "tap 派发失败", e);
            awaiting = false;
            pump();
        }
    }

    /** 启动实时拖动：派发第 0 段(willContinue=true)，后续由 chainCallback 续链。 */
    private void startDrag(float x, float y) {
        measureScreen();
        wantInterrupt = false;
        Point a = map(x, y);
        curX = tgtX = a.x; curY = tgtY = a.y;
        inGesture = true; holdLong = false; wantUp = false;
        holdStartAt = 0;
        contDeadline = System.currentTimeMillis() + CONT_MAX_MS;
        Path p0 = segPath(curX, curY, curX, curY);
        GestureDescription.StrokeDescription s0 =
                new GestureDescription.StrokeDescription(p0, 0, SEG_MS, true);
        lastStroke = s0;
        awaiting = true;
        try {
            dispatchGesture(build(s0), chainCallback(), null);
        } catch (Exception e) {
            Log.w(TAG, "down 派发失败", e);
            inGesture = false; lastStroke = null; awaiting = false;
            pump();
        }
    }

    /** move：只更新目标坐标与超时时间，不私自结束手势；由续链回调自然推进。
     *  若当前没有手势在跑（被控端因超时/取消已抬手，例如主控重连后补发 down 之前的空窗），
     *  把这个 move 当成新的按下重建手势，续链自然接上，避免主控以为还在按、被控却早已松手。 */
    private void onMove(float x, float y) {
        if (holdLong && !wantUp) return;  // 长按持有期间不收 move，避免把自驱动链转成拖动
        if (!inGesture) {
            if (wantUp) return;
            // 没有手势时把首个 move 当一次新的 down 起点
            touchDownInternal(x, y);
            return;
        }
        if (wantUp) return;
        Point a = map(x, y);
        tgtX = a.x; tgtY = a.y;
        contDeadline = System.currentTimeMillis() + CONT_MAX_MS;
        if (!awaiting) pump();
    }

    /** 不经 requestStart 串行门禁，直接起一段拖动（供 onMove 在无手势时补建用）。 */
    private void touchDownInternal(float x, float y) {
        if (inGesture || awaiting) return;
        startDrag(x, y);
    }

    /** up：标记抬手，由续链回调接 willContinue=false 收尾。 */
    private void onUp(float x, float y) {
        if (holdLong) { onLongHoldEnd(); return; }  // 长按持有只认 lend
        if (!inGesture) return;
        wantUp = true;
        Point a = map(x, y);
        upX = a.x; upY = a.y;
        if (!awaiting) pump();
    }

    /** lend：长按松手，标记抬手，由续链回调接 willContinue=false 收尾。
     *  不带坐标，原地收尾即可（长按中可能有极微小位移，忽略）。 */
    private void onLongHoldEnd() {
        if (!holdLong) return;   // 重复 lend 是空操作
        holdLong = false;        // 先关持有，取消/重起窗口里的 pending 动作都会失效
        if (inGesture && lastStroke != null) {
            wantUp = true;       // 有段在飞：下一回调接 willContinue=false 收尾抬手
            upX = curX; upY = curY;
            if (!awaiting) pump();
        } else {
            // 系统已取消/无段在飞，pointer 本就没按着，只要别再自动重起即可
            wantUp = false;
        }
    }

    /**
     * 串行链推进：只能在主线程、且没有段在飞(awaiting=false)时调用。
     * 决定下一段：继续跟随 / 正常收尾抬手(打断、wantUp 或超时) / 执行被挂起的新手势。
     * 收尾段在这里直接 dispatchGesture，必达，不受任何 seq 门禁影响。
     */
    private void pump() {
        if (awaiting) return;
        if (inGesture && lastStroke != null) {
            // 长按持有不受失联超时约束，由被控端自己一直按到 lend 才抬手；
            // 仅当 lend 信号彻底丢失时，用 5 分钟纯本地兜底抬手，避免永远按着
            boolean timeout = holdLong
                    ? (System.currentTimeMillis() - holdStartAt > HOLD_MAX_MS)
                    : (System.currentTimeMillis() > contDeadline);
            if (wantInterrupt || wantUp || timeout) {
                Path seg = (wantUp && !wantInterrupt)
                        ? segPath(curX, curY, upX, upY)
                        : segPath(curX, curY, curX, curY);
                GestureDescription.StrokeDescription term;
                try {
                    term = lastStroke.continueStroke(seg, 0, SEG_MS, false);
                } catch (Exception e) {
                    Log.w(TAG, "收尾段构造失败", e);
                    term = null;
                }
                wantInterrupt = false; wantUp = false;
                inGesture = false; holdLong = false; lastStroke = null;
                if (term != null) {
                    awaiting = true;
                    try {
                        dispatchGesture(build(term), terminalCallback(), null);
                        return;
                    } catch (Exception e) {
                        Log.w(TAG, "收尾段派发失败", e);
                        awaiting = false;
                    }
                }
                // term 为空或派发失败：本批已结束，继续跑挂起动作
            } else {
                // 长按持有：原地零长度保活段，不跟随目标、不产生位移（输入法不会误判「上滑取消」）
                Path seg = holdLong
                        ? segPath(curX, curY, curX, curY)
                        : segPath(curX, curY, tgtX, tgtY);
                GestureDescription.StrokeDescription next;
                try {
                    next = lastStroke.continueStroke(seg, 0, SEG_MS, true);
                } catch (Exception e) {
                    Log.w(TAG, "续段构造失败", e);
                    next = null;
                }
                if (next != null) {
                    lastStroke = next;
                    curX = tgtX; curY = tgtY;
                    awaiting = true;
                    try {
                        dispatchGesture(build(next), holdLong ? holdChainCallback() : chainCallback(), null);
                        return;
                    } catch (Exception e) {
                        Log.w(TAG, "续段派发失败", e);
                        awaiting = false;
                    }
                }
                inGesture = false; wantUp = false; holdLong = false; lastStroke = null;
            }
        }
        if (pendingStart != null) {
            Runnable r = pendingStart;
            pendingStart = null;
            r.run();
        }
    }

    /** 长按持有续链回调：系统若取消（比如 inject 被打断），等短暂空档后自动重起按住，
     *  不等 lend 也不依赖主控，保证语音「按住说话」期间一直按着。 */
    private GestureResultCallback holdChainCallback() {
        return new GestureResultCallback() {
            @Override public void onCompleted(GestureDescription gd) {
                awaiting = false;
                pump();
            }
            @Override public void onCancelled(GestureDescription gd) {
                awaiting = false;
                inGesture = false; lastStroke = null;
                Log.w(TAG, "长按持有段被系统取消，稍后自动重起按住");
                if (holdLong && !wantUp) {
                    handler.postDelayed(() -> {
                        if (holdLong && !wantUp && !inGesture && !awaiting) {
                            startLongHoldAgain();
                        }
                    }, SEG_MS);
                }
            }
        };
    }

    /** 被系统取消后，用按下坐标原地重起自维持链（不重置 5 分钟兜底起点）。 */
    private void startLongHoldAgain() {
        measureScreen();
        wantInterrupt = false;
        curX = tgtX = holdX; curY = tgtY = holdY;   // 原地，不跟随任何微小位移
        inGesture = true; holdLong = true; wantUp = false;
        Path p0 = segPath(curX, curY, curX, curY);
        GestureDescription.StrokeDescription s0 =
                new GestureDescription.StrokeDescription(p0, 0, SEG_MS, true);
        lastStroke = s0;
        awaiting = true;
        try {
            dispatchGesture(build(s0), holdChainCallback(), null);
        } catch (Exception e) {
            Log.w(TAG, "长按持有重起派发失败", e);
            inGesture = false; holdLong = false; lastStroke = null; awaiting = false;
        }
    }

    /** 续链中某段完成 → 推进下一段。 */
    private GestureResultCallback chainCallback() {
        return new GestureResultCallback() {
            @Override public void onCompleted(GestureDescription gd) {
                awaiting = false;
                pump();
            }
            @Override public void onCancelled(GestureDescription gd) {
                // 系统已把该 gesture 取消并抬起 pointer，清状态后跑挂起动作
                awaiting = false;
                inGesture = false; holdLong = false; wantUp = false; lastStroke = null;
                Log.w(TAG, "续手势被系统取消，本批结束");
                pump();
            }
        };
    }

    /** 收尾段 / 独立手势完成 → 本批彻底结束，跑挂起动作。 */
    private GestureResultCallback terminalCallback() {
        return new GestureResultCallback() {
            @Override public void onCompleted(GestureDescription gd) {
                awaiting = false;
                pump();
            }
            @Override public void onCancelled(GestureDescription gd) {
                awaiting = false;
                pump();
            }
        };
    }

    public static int[] screenSize() {
        CtrlAccessibilityService s = sInstance;
        return s == null ? new int[]{1080, 2400} : new int[]{s.screenW, s.screenH};
    }
}
