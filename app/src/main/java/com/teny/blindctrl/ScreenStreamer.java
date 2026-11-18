package com.teny.blindctrl;

import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.PixelFormat;
import android.graphics.Point;
import android.graphics.Rect;
import android.hardware.display.DisplayManager;
import android.hardware.display.VirtualDisplay;
import android.media.Image;
import android.media.ImageReader;
import android.media.projection.MediaProjection;
import android.media.projection.MediaProjectionManager;
import android.os.Handler;
import android.os.HandlerThread;
import android.util.Log;
import android.view.Display;
import android.view.WindowManager;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.ByteBuffer;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 被控端画面回传：屏幕缩到 720p 以内，JPEG 质量 60，约 10fps，走独立端口 CtrlBus.PORT_VIDEO。
 *
 * 传输格式：每帧 4 字节大端长度 + JPEG 数据。只允许一个主控看画面，新连接顶掉旧的。
 * 画面只在主控连上来看时才真正抓（MediaProjection 的权限由 Activity 弹窗获得，这里只保存 token），
 * 主控断开就释放 VirtualDisplay，省电省资源。
 */
public final class ScreenStreamer {

    private static final String TAG = "BlindCtrl";
    private static final int FRAME_MS = 100;        // ~10fps
    private static final int JPEG_QUALITY = 60;
    private static final int MAX_SHORT_SIDE = 720;  // 短边上限 720
    private static final int MAX_LONG_SIDE = 1600;  // 长边上限 1600，20:9 屏正好落成 720x1600

    private static volatile MediaProjection sProjection;
    private static volatile boolean sProjectionStopped = true;
    /** 最近一次失败原因，给界面显示用（技术小白看不到 logcat）。 */
    public static volatile String lastError = null;

    public static String statusText() {
        if (hasProjection()) return "✓ 已授权（缩到 720p / 10fps）";
        if (sProjection != null && sProjectionStopped) return "✗ " + (lastError == null ? "授权已失效" : lastError);
        return "✗ 未授权（主控看不到画面，只能盲点）";
    }
    private static VideoServer sServer;

    private ScreenStreamer() {}

    /** Activity 授权回调里调用：把 MediaProjection token 交给我保管。 */
    public static boolean setProjection(Context ctx, int resultCode, Intent data) {
        if (resultCode != android.app.Activity.RESULT_OK || data == null) return false;
        try {
            MediaProjectionManager mpm = (MediaProjectionManager)
                    ctx.getSystemService(Context.MEDIA_PROJECTION_SERVICE);
            if (mpm == null) return false;
            final MediaProjection mp = mpm.getMediaProjection(resultCode, data);
            if (mp == null) { lastError = "系统没给出录屏通道"; return false; }
            // 先接管，再注册回调：回调里只认「自己这一次」的实例，
            // 否则停掉旧投影时会把新投影的标记一起打成失效（重授权必现假 ❌）。
            MediaProjection old = sProjection;
            sProjection = mp;
            sProjectionStopped = false;
            lastError = null;
            mp.registerCallback(new MediaProjection.Callback() {
                @Override public void onStop() {
                    if (sProjection == mp) {
                        sProjectionStopped = true;
                        lastError = "系统收回了录屏授权";
                        Log.w(TAG, "MediaProjection 被系统停止");
                        // 这次 token 已经废了，虚拟显示必须拆掉，等用户重新授权再建
                        VideoServer sv = sServer;
                        if (sv != null) sv.releaseCapture();
                    }
                }
            }, new Handler(android.os.Looper.getMainLooper()));
            if (old != null) {
                try { old.stop(); } catch (Exception ignored) {}
            }
            // 如果主控已经连着画面端口、只是刚才还没授权，这会儿补上抓屏
            VideoServer sv = sServer;
            if (sv != null) sv.ensureCapture();
            return true;
        } catch (Exception e) {
            lastError = "取录屏通道异常：" + e.getMessage();
            Log.w(TAG, "取 MediaProjection 失败: " + e.getMessage());
            return false;
        }
    }

    public static boolean hasProjection() {
        return sProjection != null && !sProjectionStopped;
    }

    public static synchronized void startServer(Context ctx) {
        if (sServer != null) return;
        try {
            VideoServer s = new VideoServer(ctx.getApplicationContext());
            s.start();
            sServer = s;
        } catch (Exception e) {
            Log.w(TAG, "画面端口启动失败: " + e.getMessage());
        }
    }

    public static synchronized void stopServer() {
        VideoServer s = sServer;
        sServer = null;
        if (s != null) s.stop();
    }

    private static int[] scaledSize(Context ctx) {
        int sw = 1080, sh = 1920;
        try {
            WindowManager wm = (WindowManager) ctx.getSystemService(Context.WINDOW_SERVICE);
            Display d = wm.getDefaultDisplay();
            Point p = new Point();
            d.getRealSize(p);
            if (p.x > 0 && p.y > 0) { sw = p.x; sh = p.y; }
        } catch (Exception ignored) {}
        int shortSide = Math.min(sw, sh), longSide = Math.max(sw, sh);
        float scale = Math.min(MAX_SHORT_SIDE / (float) shortSide, MAX_LONG_SIDE / (float) longSide);
        int w = Math.max(2, (int) (sw * scale)) & ~1;
        int h = Math.max(2, (int) (sh * scale)) & ~1;
        return new int[]{w, h};
    }

    private static final class VideoServer {
        private final Context ctx;
        private ServerSocket server;
        private Thread acceptThread;
        private Thread sendThread;
        private volatile boolean running;
        private volatile Socket client;
        private VirtualDisplay vd;
        private ImageReader reader;
        private HandlerThread capThread;
        private final AtomicReference<byte[]> pending = new AtomicReference<>();
        private Bitmap src, dst;
        private long lastFrameAt = 0;
        private DataOutputStream out;

        VideoServer(Context c) { this.ctx = c; }

        void start() throws Exception {
            server = new ServerSocket();
            server.setReuseAddress(true);
            server.bind(new InetSocketAddress(CtrlBus.PORT_VIDEO));
            running = true;
            acceptThread = new Thread(this::acceptLoop, "BlindCtrl-VACC");
            acceptThread.setDaemon(true);
            acceptThread.start();
            sendThread = new Thread(this::sendLoop, "BlindCtrl-VSEND");
            sendThread.setDaemon(true);
            sendThread.start();
            Log.i(TAG, "画面端口 " + CtrlBus.PORT_VIDEO + " 已就绪");
        }

        void stop() {
            running = false;
            closeClient();
            try { if (server != null) server.close(); } catch (Exception ignored) {}
            if (acceptThread != null) acceptThread.interrupt();
            if (sendThread != null) sendThread.interrupt();
            releaseCapture();
        }

        private void acceptLoop() {
            while (running) {
                try {
                    Socket s = server.accept();
                    s.setTcpNoDelay(true);
                    InetSocketAddress ra = (InetSocketAddress) s.getRemoteSocketAddress();
                    if (ra == null || ra.getAddress() == null || !ra.getAddress().isSiteLocalAddress()) {
                        try { s.close(); } catch (Exception ignored) {}
                        continue;
                    }
                    closeClient();
                    client = s;
                    if (hasProjection()) startCapture();
                    else Log.w(TAG, "主控要画面，但还没给屏幕录制权限");
                } catch (Exception e) {
                    if (!running) break;
                    try { Thread.sleep(200); } catch (InterruptedException ie) { break; }
                }
            }
        }

        private void closeClient() {
            Socket s = client;
            client = null;
            out = null;
            if (s != null) {
                try { s.close(); } catch (Exception ignored) {}
            }
            // 故意不销毁 VirtualDisplay：安卓 10+ 的录屏 token 只能建一次虚拟显示，
            // 断开就 release 的话，主控再连上来重建会直接失败（界面显示未授权）。
            // 没人看的时候 onFrame 会直接丢帧，几乎不耗电。
            pending.set(null);
        }

        /** 授权晚到时的补救：主控已连且刚拿到权限，立刻开抓。 */
        void ensureCapture() {
            if (client != null && hasProjection()) startCapture();
        }

        private synchronized void startCapture() {
            if (reader != null) return;
            MediaProjection mp = sProjection;
            if (mp == null) return;
            int[] wh = scaledSize(ctx);
            final int w = wh[0], h = wh[1];
            capThread = new HandlerThread("BlindCtrl-CAP");
            capThread.start();
            Handler handler = new Handler(capThread.getLooper());
            reader = ImageReader.newInstance(w, h, PixelFormat.RGBA_8888, 3);
            reader.setOnImageAvailableListener(this::onFrame, handler);
            try {
                vd = mp.createVirtualDisplay("blindctrl", w, h,
                        ctx.getResources().getDisplayMetrics().densityDpi,
                        DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                        reader.getSurface(), null, handler);
                Log.i(TAG, "开始回传画面 " + w + "x" + h);
            } catch (Exception e) {
                lastError = "建虚拟屏失败：" + e.getMessage();
                Log.w(TAG, "建 VirtualDisplay 失败: " + e.getMessage());
                releaseCapture();
            }
        }

        synchronized void releaseCapture() {
            if (vd != null) {
                try { vd.release(); } catch (Exception ignored) {}
                vd = null;
            }
            src = null; dst = null;
            if (reader != null) {
                try { reader.close(); } catch (Exception ignored) {}
                reader = null;
            }
            if (capThread != null) {
                capThread.quitSafely();
                capThread = null;
            }
            pending.set(null);
        }

        private void onFrame(ImageReader r) {
            Image img = null;
            try { img = r.acquireLatestImage(); } catch (Exception e) { return; }
            if (img == null) return;
            try {
                if (client == null) return;   // 没人看就不编码
                long now = System.currentTimeMillis();
                if (now - lastFrameAt < FRAME_MS) return;
                lastFrameAt = now;
                byte[] jpeg = encode(img);
                if (jpeg != null) pending.set(jpeg);
            } catch (Exception e) {
                Log.w(TAG, "编帧失败: " + e.getMessage());
            } finally {
                try { img.close(); } catch (Exception ignored) {}
            }
        }

        private byte[] encode(Image img) {
            Image.Plane plane = img.getPlanes()[0];
            ByteBuffer buf = plane.getBuffer();
            int rowStride = plane.getRowStride();
            int pixelStride = plane.getPixelStride();
            int w = img.getWidth(), h = img.getHeight();
            int strideW = rowStride / pixelStride;
            if (strideW <= 0) return null;
            if (src == null || src.getWidth() != strideW || src.getHeight() != h) {
                src = Bitmap.createBitmap(strideW, h, Bitmap.Config.ARGB_8888);
            }
            buf.rewind();
            src.copyPixelsFromBuffer(buf);
            Bitmap target;
            if (strideW != w) {
                if (dst == null || dst.getWidth() != w || dst.getHeight() != h) {
                    dst = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
                }
                Canvas cv = new Canvas(dst);
                cv.drawBitmap(src, new Rect(0, 0, w, h), new Rect(0, 0, w, h), null);
                target = dst;
            } else {
                target = src;
            }
            ByteArrayOutputStream bos = new ByteArrayOutputStream(96 * 1024);
            target.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, bos);
            return bos.toByteArray();
        }

        private void sendLoop() {
            while (running) {
                byte[] frame = pending.getAndSet(null);
                Socket s = client;
                if (frame == null || s == null) {
                    try { Thread.sleep(6); } catch (InterruptedException e) { break; }
                    continue;
                }
                try {
                    if (out == null) out = new DataOutputStream(new java.io.BufferedOutputStream(
                            s.getOutputStream(), 64 * 1024));
                    out.writeInt(frame.length);
                    out.write(frame);
                    out.flush();
                } catch (Exception e) {
                    Log.w(TAG, "画面发送断开: " + e.getMessage());
                    closeClient();
                }
            }
        }
    }
}
