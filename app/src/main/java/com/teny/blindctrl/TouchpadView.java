package com.teny.blindctrl;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.os.Handler;
import android.os.Looper;
import android.util.AttributeSet;
import android.view.MotionEvent;
import android.view.View;

import org.json.JSONObject;

/**
 * 主控触摸板：竖屏 9:20（对应被控端逻辑坐标 1080x2400）。
 * 三种手势互斥，判定后即锁定，互不混用：
 *  - 点击：按下后没超过 DRAG_SLOP 位移就抬手 → 发一条 tap（被控端注入约 60ms 的短按）
 *  - 长按：任意位置都监测（已取消「只能在底部 1/3」的限制）；
 *    手指静止不动超过 LONG_PRESS_MS → 发一条 lstart（带按下坐标），此后不再发任何指令，
 *    由被控端自己一直按住；松手只发一条 lend（不带坐标），被控端收到才抬手。
 *    其余区域不排长按定时器，按住不动到抬手 = 点击，位移超阈值 = 滑动（只能点击/滑动）。
 *  - 滑动：按下后位移超过 DRAG_SLOP → 从起点 down 起，一路 move，抬手 up（实时流）
 * 长按「不实时流」：主控只发 lstart + lend 两条，中间不发心跳 move，避免主控心跳丢失 /
 * 被控端失联超时把语音「按住说话」的手势抬掉。结束信号不带坐标，因为长按中可能有极微小位移。
 * 全屏按「按住时长」判定：手指静止（位移始终没超过 LONG_PRESS_SLOP）满 300ms 即锁定为
 * 长按，保证输入法语音「按住说话」能稳稳按住；只要位移超过 LONG_PRESS_SLOP 就立刻取消
 * 长按判定（手指在动 = 用户在滑动，不是长按）。
 * 防「APP 自带长按」：只有位移超过 DRAG_SLOP 才把 down 发给被控端，down 之后紧跟的那条 move
 * 与 down 点相距必然 > DRAG_SLOP，一步就跨过 APP 自己的触摸阈值(约 8dp)，APP 自身的长按判定
 * 当场作废并持续到本次抬手，所以再慢的滑动也不会把 APP 里的文字长按出弹窗。
 * 重连后也不在原地补 down（原地补会重新激活 APP 长按），而是收起旧手势、等手指真正移动
 * 超过 DRAG_SLOP 再以「起点 + 大跨步 move」重新起手。
 * 长按与滑动绝不叠加（不会变成「长按后拖动」）：判为长按后不跟随移动。
 * 背景可铺被控端实时画面（setFrame），等比缩放填满整块板，只做视觉参考，不拦截触摸。
 */
public class TouchpadView extends View {

    private static final float LW = 1080f, LH = 2400f;
    private static final long MOVE_MIN_MS = 16;        // ~60fps 节流
    private static final long LONG_PRESS_MS = 300;     // 手指静止不动超过这个时间 = 长按
    private static final long KEEPALIVE_MS = 400;      // 滑动静止心跳，必须 < 被控端 CONT_MAX_MS；长按不用心跳
    private static final float LONG_PRESS_SLOP = 8f;   // 位移超过它就取消长按判定：手指在动就是滑动，不是长按
    /** 超过它才真正把「按下」发给被控端。必须明显大于 APP 自己的触摸阈值(约 8dp≈22px)，
     *  这样按下之后紧跟的那条 move 一步就跨过 APP 阈值，APP 自带的长按判定当场作废，
     *  慢速滑动再也不会被 APP 当成用户在长按文字。 */
    private static final float DRAG_SLOP = 36f;
    /** 辅助标注：来自「临时」文件夹 6 个文档，坐标与大小都是 1080x2400 原始像素，勿改。 */
    private static final float[][] MARKS_RECT = {
            {724, 2298, 90, 82},     // 1.md
            {493, 2293, 97, 85},     // 2.md
            {268, 2296, 82, 83},     // 3.md
            {392, 2067, 296, 184},   // 6.md
    };
    private static final float[][] MARKS_CIRCLE = {
            {964, 1103, 34},         // 4.md
            {970, 1101, 36},         // 5.md
    };

    private static final int MODE_IDLE = 0, MODE_PENDING = 1, MODE_LONG = 2, MODE_SWIPE = 3,
            MODE_SWIPE_PAUSED = 4;

    private final Paint bg = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint border = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint dot = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint hint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint markRect = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint markCircle = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint markFill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private LinkClient client;
    private final Handler handler = new Handler(Looper.getMainLooper());
    /** 被控端画面：铺满触摸板当背景，缩放到刚好填满（保持比例、不裁切内容）。 */
    private android.graphics.Bitmap frame;
    private final Paint framePaint = new Paint(Paint.FILTER_BITMAP_FLAG);
    private final android.graphics.RectF frameDst = new android.graphics.RectF();

    private boolean pressing = false;     // 手指还在触摸板上
    private int mode = MODE_IDLE;         // 当前手势（判定后锁定）
    private int activePid = -1;
    private float downX = 0, downY = 0;   // 按下起点
    private float curX = 0, curY = 0;     // 当前逻辑坐标
    private float pausedX = 0, pausedY = 0; // 滑动暂停态（重连后收手势）的起点
    private long downAt = 0;
    private long lastMoveAt = 0;
    private boolean longZone = false;    // 现在全屏都可长按，恒为 true；保留以便沿用原判定流程
    private boolean pendingLongEnd = false; // 长按已松手、lend 可能因断连没发出，重连后补发

    /** 静止心跳：仅滑动期间持续发 move，防止被控端失联保险自动抬手。
     *  长按「不实时流」，被控端自维持按住，不发任何心跳。 */
    private final Runnable keepalive = new Runnable() {
        @Override public void run() {
            if (pressing) {
                if (mode == MODE_SWIPE) send("move", curX, curY);
                handler.postDelayed(this, KEEPALIVE_MS);
            }
        }
    };

    /** 按住不动达到阈值：锁定为长按，发一条 lstart（带坐标）。
     *  此后主控不再发任何指令，被控端自己一直按住，直到松手发 lend。 */
    private final Runnable longPress = new Runnable() {
        @Override public void run() {
            if (pressing && mode == MODE_PENDING) {
                mode = MODE_LONG;
                curX = downX; curY = downY;
                lastMoveAt = System.currentTimeMillis();
                handler.removeCallbacks(keepalive);   // 长按不实时流，去掉滑动心跳
                sendLongStart(downX, downY);
                invalidate();
            }
        }
    };

    public TouchpadView(Context c) { super(c); init(); }
    public TouchpadView(Context c, AttributeSet a) { super(c, a); init(); }

    private void init() {
        bg.setColor(0xFF0E1420);
        bg.setStyle(Paint.Style.FILL);
        border.setColor(0xFF3D6FFF);
        border.setStyle(Paint.Style.STROKE);
        border.setStrokeWidth(dp(2));
        dot.setColor(0xAA5B8CFF);
        dot.setStyle(Paint.Style.FILL);
        hint.setColor(0xFF66708A);
        hint.setTextAlign(Paint.Align.CENTER);
        hint.setTextSize(dp(14));

        markRect.setColor(0xFF64B5F6);
        markRect.setStyle(Paint.Style.STROKE);
        markRect.setStrokeWidth(dp(2));
        markCircle.setColor(0xFFFFB74D);
        markCircle.setStyle(Paint.Style.STROKE);
        markCircle.setStrokeWidth(dp(2));
        markFill.setStyle(Paint.Style.FILL);
        setClickable(true);
    }

    public void setClient(LinkClient c) { this.client = c; }

    /** 收到一帧新画面：替换旧帧并重绘。旧帧不再引用，交给 GC。 */
    public void setFrame(android.graphics.Bitmap b) {
        frame = b;
        invalidate();
    }

    /** 强制 9:20 竖屏，适配可用空间（取能放下的最大尺寸）。 */
    @Override
    protected void onMeasure(int wSpec, int hSpec) {
        int wAvail = MeasureSpec.getSize(wSpec);
        int hAvail = MeasureSpec.getSize(hSpec);
        float ratio = LW / LH; // 0.45
        int w = (int) (hAvail * ratio);
        int h = hAvail;
        if (w > wAvail) { w = wAvail; h = (int) (wAvail / ratio); }
        setMeasuredDimension(
                resolveSize(w, wSpec), resolveSize(h, hSpec));
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        int w = getWidth(), h = getHeight();
        canvas.drawRoundRect(2, 2, w - 2, h - 2, dp(14), dp(14), bg);

        // 画面背景：等比缩放铺满整块触摸板（宽高比不同就留边，绝不裁切成看不清）
        if (frame != null && !frame.isRecycled()) {
            float fw = frame.getWidth(), fh = frame.getHeight();
            float s = Math.min(w / fw, h / fh);
            float dw = fw * s, dh = fh * s;
            frameDst.set((w - dw) / 2f, (h - dh) / 2f, (w + dw) / 2f, (h + dh) / 2f);
            canvas.drawBitmap(frame, null, frameDst, framePaint);
        }

        // 辅助标注：逻辑坐标线性铺到板上，与 toLogical 完全互逆，位置精确到像素
        float sx = w / LW, sy = h / LH;
        markFill.setColor(0x2264B5F6);
        for (float[] r : MARKS_RECT) {
            float l = r[0] * sx, t = r[1] * sy;
            float rr = (r[0] + r[2]) * sx, bb = (r[1] + r[3]) * sy;
            canvas.drawRect(l, t, rr, bb, markFill);
            canvas.drawRect(l, t, rr, bb, markRect);
        }
        markFill.setColor(0x22FFB74D);
        for (float[] c : MARKS_CIRCLE) {
            float cx = c[0] * sx, cy = c[1] * sy;
            float rad = c[2] * (sx + sy) / 2f;
            canvas.drawCircle(cx, cy, rad, markFill);
            canvas.drawCircle(cx, cy, rad, markCircle);
        }

        canvas.drawRoundRect(2, 2, w - 2, h - 2, dp(14), dp(14), border);

        if (pressing && mode != MODE_PENDING) {
            float px = curX / LW * w;
            float py = curY / LH * h;
            canvas.drawCircle(px, py, dp(26), dot);
        } else if (!pressing) {
            canvas.drawText("点按 / 长按 / 滑动", w / 2f, h / 2f, hint);
        }
    }

    @Override
    public boolean onTouchEvent(MotionEvent e) {
        int action = e.getActionMasked();
        switch (action) {
            case MotionEvent.ACTION_DOWN:
            case MotionEvent.ACTION_POINTER_DOWN: {
                if (pressing) return true;
                int idx = e.getActionIndex();
                activePid = e.getPointerId(idx);
                float[] p = toLogical(e.getX(idx), e.getY(idx));
                downX = p[0]; downY = p[1];
                curX = p[0]; curY = p[1];
                downAt = System.currentTimeMillis();
                pressing = true;
                mode = MODE_PENDING;       // 先不发任何指令：等抬手(点击)/超时(长按)/位移(滑动)
                lastMoveAt = 0;
                longZone = true;   // 全屏都能长按，不再限制在底部 1/3
                pendingLongEnd = false;
                handler.removeCallbacks(keepalive);
                handler.removeCallbacks(longPress);
                handler.postDelayed(keepalive, KEEPALIVE_MS);
                if (longZone) handler.postDelayed(longPress, LONG_PRESS_MS);
                performClick();
                invalidate();
                return true;
            }
            case MotionEvent.ACTION_MOVE: {
                if (!pressing) return true;
                for (int i = 0; i < e.getPointerCount(); i++) {
                    if (e.getPointerId(i) == activePid) {
                        float[] p = toLogical(e.getX(i), e.getY(i));
                        long now = System.currentTimeMillis();
                        if (mode == MODE_PENDING) {
                            float dx = p[0] - downX, dy = p[1] - downY;
                            float d2 = dx * dx + dy * dy;
                            if (d2 > DRAG_SLOP * DRAG_SLOP) {
                                // 真滑动：先补 down 起点，紧跟一条到当前点的 move。
                                // 这条 move 与 down 点距离必然 > DRAG_SLOP > APP 触摸阈值，
                                // APP 自身的长按判定当场作废（本次手势内不会再触发）。
                                handler.removeCallbacks(longPress);
                                mode = MODE_SWIPE;
                                curX = p[0]; curY = p[1];
                                lastMoveAt = now;
                                send("down", downX, downY);
                                send("move", curX, curY);
                            } else {
                                float mx = p[0] - curX, my = p[1] - curY;
                                curX = p[0]; curY = p[1];
                                boolean moved = mx * mx + my * my > 0.25f;
                                if (d2 > LONG_PRESS_SLOP * LONG_PRESS_SLOP) {
                                    // 已经离开按下点超过 8：手指在挪 = 在滑动，掐掉长按且不再重排
                                    handler.removeCallbacks(longPress);
                                } else if (longZone && moved) {
                                    // 还在按下点附近、但有真实位移（手抖/极慢挪动）：重计 300ms。
                                    // 静止时某些机型照样每帧发 MOVE，那种 moved=false，不重计，
                                    // 保证按住不动 300ms 能稳稳触发长按（语音按住说话）。
                                    handler.removeCallbacks(longPress);
                                    handler.postDelayed(longPress, LONG_PRESS_MS);
                                }
                            }
                        } else if (mode == MODE_SWIPE_PAUSED) {
                            // 重连后暂停态：手指真正动了才重新起手
                            float dx = p[0] - pausedX, dy = p[1] - pausedY;
                            if (dx * dx + dy * dy > DRAG_SLOP * DRAG_SLOP) {
                                curX = p[0]; curY = p[1];
                                lastMoveAt = now;
                                mode = MODE_SWIPE;
                                send("down", pausedX, pausedY);
                                send("move", curX, curY);
                            } else {
                                curX = p[0]; curY = p[1];
                            }
                        } else if (mode == MODE_SWIPE) {
                            curX = p[0]; curY = p[1];
                            if (now - lastMoveAt >= MOVE_MIN_MS) {
                                lastMoveAt = now;
                                send("move", curX, curY);
                            }
                        } else if (mode == MODE_LONG) {
                            // 长按锁死原地：手指移动一律不跟随，杜绝「长按+滑动」；也不发心跳
                        }
                        invalidate();
                        break;
                    }
                }
                return true;
            }
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_POINTER_UP:
            case MotionEvent.ACTION_CANCEL: {
                int idx = e.getActionIndex();
                if (action != MotionEvent.ACTION_CANCEL
                        && e.getPointerId(idx) != activePid) return true;
                finish(action == MotionEvent.ACTION_CANCEL);
                return true;
            }
        }
        return true;
    }

    private void finish(boolean cancelled) {
        if (!pressing) return;
        pressing = false;
        handler.removeCallbacks(keepalive);
        handler.removeCallbacks(longPress);
        switch (mode) {
            case MODE_PENDING:
                // 没超时、没位移就抬手 = 点击；系统取消时不发，避免误触
                if (!cancelled) send("tap", downX, downY);
                break;
            case MODE_LONG:
                // 只发一条不带坐标的结束信号，被控端收到才抬手
                sendLongEnd();
                break;
            case MODE_SWIPE:
                send("move", curX, curY);   // 补最后一个坐标再抬手，保证落点准确
                send("up", curX, curY);
                break;
            case MODE_SWIPE_PAUSED:
                // 重连时已经发过 up，这里什么都不用补
                break;
        }
        mode = MODE_IDLE;
        activePid = -1;
        invalidate();
    }

    /**
     * 重连成功后由 MainActivity 调用。滑动仍是实时流，但绝不在原地补 down：
     * 原地 down 会让被控端 APP 重新开始它自己的长按计时（手指此时往往正慢慢挪），
     * 于是改成——先把旧手势收掉（up），进入暂停态；等手指真正移动超过 DRAG_SLOP，
     * 再以「起点 + 大跨步 move」重新起手，APP 的长按判定照例被一步跨过、当场作废。
     * 长按「不实时流」被控端自维持、与连接无关，重连不补；松手的 lend 是保序控制指令，必达。
     * 点击是一次性指令，不需要补发。
     */
    public void resendDownIfPressing() {
        if (pressing && mode == MODE_SWIPE) {
            send("up", curX, curY);
            mode = MODE_SWIPE_PAUSED;
            pausedX = curX; pausedY = curY;
        }
    }

    private float[] toLogical(float px, float py) {
        float w = getWidth(), h = getHeight();
        // 有画面时按「画面实际铺开的区域」换算，避免留边导致坐标整体偏移
        if (frame != null && !frame.isRecycled() && frameDst.width() > 1 && frameDst.height() > 1) {
            float x = (px - frameDst.left) / frameDst.width() * LW;
            float y = (py - frameDst.top) / frameDst.height() * LH;
            if (x < 0) x = 0; if (x > LW) x = LW;
            if (y < 0) y = 0; if (y > LH) y = LH;
            return new float[]{x, y};
        }
        float x = w > 0 ? px / w * LW : 0;
        float y = h > 0 ? py / h * LH : 0;
        if (x < 0) x = 0; if (x > LW) x = LW;
        if (y < 0) y = 0; if (y > LH) y = LH;
        return new float[]{x, y};
    }

    private void send(String t, float x, float y) {
        if (client == null) return;
        try {
            JSONObject o = new JSONObject();
            o.put("t", t);
            o.put("x", (double) x);
            o.put("y", (double) y);
            String line = o.toString();
            if (t.equals("move")) client.sendMove(line);
            else client.sendControl(line);
        } catch (Exception ignored) {}
    }

    /** 长按开始：只发一条带按下坐标的信号，此后不实时流，被控端自己一直按住。 */
    private void sendLongStart(float x, float y) {
        if (client == null) return;
        try {
            JSONObject o = new JSONObject();
            o.put("t", "lstart");
            o.put("x", (double) x);
            o.put("y", (double) y);
            client.sendControl(o.toString());   // 保序，绝不丢
        } catch (Exception ignored) {}
    }

    /** 长按结束：不带坐标（长按中可能有极微小位移），被控端收到才抬手。 */
    private void sendLongEnd() {
        pendingLongEnd = true;   // 断连时 lend 会被清出发送队列，重连后要补发
        if (client == null) return;
        try {
            JSONObject o = new JSONObject();
            o.put("t", "lend");
            client.sendControl(o.toString());   // 保序，绝不丢
        } catch (Exception ignored) {}
    }

    /** 重连成功后由 MainActivity 调用：补发一条 lend，防止断连窗口里松手信号被清掉、
     *  被控端一直按着不松。重复 lend 在被控端是空操作（holdLong 已清），安全。 */
    public void flushPendingLongEnd() {
        if (!pendingLongEnd || client == null) return;
        pendingLongEnd = false;   // 只补一次，避免每次重连都重复发
        try {
            JSONObject o = new JSONObject();
            o.put("t", "lend");
            client.sendControl(o.toString());
        } catch (Exception ignored) {}
    }

    @Override
    public boolean performClick() { return super.performClick(); }

    private float dp(float v) {
        return v * getResources().getDisplayMetrics().density;
    }
}
