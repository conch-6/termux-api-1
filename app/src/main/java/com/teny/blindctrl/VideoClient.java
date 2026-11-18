package com.teny.blindctrl;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import java.io.DataInputStream;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;

/**
 * 主控端画面接收：另开一条 TCP 连到被控端 CtrlBus.PORT_VIDEO，
 * 读「4 字节长度 + JPEG」循环，解码后回调给触摸板当背景。
 *
 * 与指令连接完全分离：画面断了不影响触摸，触摸断了画面自己重连。
 * 只保留最新一帧：解码慢的时候旧帧直接丢掉，绝不排队。
 */
public class VideoClient {

    private static final String TAG = "BlindCtrl";
    private static final int CONNECT_TIMEOUT = 2000;
    private static final int MAX_FRAME = 4 * 1024 * 1024;

    public interface Listener {
        void onFrame(Bitmap bmp);
        void onVideoState(boolean connected);
    }

    private final String host;
    private final int port;
    private final Listener listener;
    private final Handler ui = new Handler(Looper.getMainLooper());

    private volatile boolean running = false;
    private volatile boolean dead = false;
    private volatile Socket socket;
    private Thread thread;

    public VideoClient(String host, int port, Listener listener) {
        this.host = host;
        this.port = port;
        this.listener = listener;
    }

    public void start() {
        if (running) return;
        running = true;
        dead = false;
        thread = new Thread(this::loop, "BlindCtrl-VIDEO");
        thread.setDaemon(true);
        thread.start();
    }

    public void stop() {
        dead = true;
        running = false;
        Socket s = socket;
        socket = null;
        if (s != null) {
            try { s.close(); } catch (IOException ignored) {}
        }
        if (thread != null) thread.interrupt();
    }

    private void loop() {
        while (running && !dead) {
            Socket s = null;
            try {
                s = new Socket();
                s.connect(new InetSocketAddress(host, port), CONNECT_TIMEOUT);
                s.setTcpNoDelay(true);
                s.setSoTimeout(0);
                socket = s;
                notifyState(true);
                DataInputStream in = new DataInputStream(s.getInputStream());
                BitmapFactory.Options opt = new BitmapFactory.Options();
                opt.inPreferredConfig = Bitmap.Config.RGB_565;   // 省一半内存
                while (running && !dead && socket == s) {
                    int len = in.readInt();
                    if (len <= 0 || len > MAX_FRAME) break;
                    byte[] buf = new byte[len];
                    in.readFully(buf);
                    Bitmap bmp = BitmapFactory.decodeByteArray(buf, 0, len, opt);
                    if (bmp != null) {
                        final Bitmap f = bmp;
                        ui.post(() -> { if (!dead) listener.onFrame(f); });
                    }
                }
            } catch (Exception e) {
                if (running && !dead) Log.w(TAG, "画面连接断开: " + e.getMessage());
            } finally {
                if (s != null) {
                    try { s.close(); } catch (IOException ignored) {}
                }
                if (socket == s) socket = null;
            }
            notifyState(false);
            if (!running || dead) break;
            try { Thread.sleep(1000); } catch (InterruptedException e) { break; }
        }
    }

    private void notifyState(boolean c) {
        ui.post(() -> { if (!dead) listener.onVideoState(c); });
    }
}
