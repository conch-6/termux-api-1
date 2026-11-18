package com.teny.blindctrl;

import android.content.Context;
import android.util.Log;

import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;

/**
 * 被控端常连 TCP 服务（替代旧 HttpServer）。
 * 一根长连接、按行读指令(JSON)，发完就走不等回音(fire-and-forget)，避免每条指令都排队等 HTTP 往返。
 * 只允许一个主控连接：新连接会顶掉旧连接。下行只推状态行(S {...})供主控显示连接/就绪。
 * 仅接受局域网来源，端口 CtrlBus.PORT。
 *
 * 静态审查修复：
 *  - 下行写全部丢到专用单线程 DOWN 池异步做，主线程(翻转总开关/无障碍变化)绝不碰网络 I/O，避免 ANR。
 *  - 读指令边读边限长，超长无换行数据不会整行进内存打爆堆。
 */
public class CtrlLinkServer {

    private static final String TAG = "BlindCtrl";
    private static final int MAX_LINE = 4096;
    private static final String CMD_HELLO = "HELLO";
    private static final String CMD_PING = "PING";

    /** 专用单下行写线程：所有 ClientConn 共用，天然串行，绝不阻塞调用线程。 */
    private static final ExecutorService DOWN = Executors.newSingleThreadExecutor(new ThreadFactory() {
        @Override public Thread newThread(Runnable r) {
            Thread t = new Thread(r, "BlindCtrl-DOWN");
            t.setDaemon(true);
            return t;
        }
    });

    private final ServerSocket serverSocket;
    private final Context appContext;
    private final ExecutorService pool = Executors.newCachedThreadPool();
    private final Thread acceptThread;
    private volatile boolean running = false;

    // 当前活跃主控连接：只保留一个，串行派发指令
    private volatile ClientConn active = null;
    private final Object activeLock = new Object();
    private int connSeq = 0;

    public CtrlLinkServer(int port, Context context) throws IOException {
        this.appContext = context.getApplicationContext();
        serverSocket = new ServerSocket();
        serverSocket.setReuseAddress(true);
        serverSocket.bind(new InetSocketAddress(port));
        acceptThread = new Thread(this::acceptLoop, "BlindCtrl-LINK");
    }

    public void start() {
        running = true;
        acceptThread.start();
        CtrlBus.setClientConnected(false);
    }

    public void stop() {
        running = false;
        closeActive();
        try { serverSocket.close(); } catch (IOException ignored) {}
        pool.shutdownNow();
        CtrlBus.setClientConnected(false);
    }

    /** 前台服务看门狗用：端口和 accept 线程都活着就不重建，死了才重 bind。 */
    public boolean isAlive() {
        return running && serverSocket != null && !serverSocket.isClosed()
                && acceptThread != null && acceptThread.isAlive();
    }

    private void acceptLoop() {
        while (running) {
            try {
                Socket s = serverSocket.accept();
                s.setTcpNoDelay(true);     // 关 Nagle，低延迟
                s.setSoTimeout(0);
                // 拒绝本机自己连自己：MainActivity.onCreate 若存过指向本机的 master_addr，
                // 本机 client 会连回自己的 server，handleClient 里 closeActiveLocked() 只留一个主控，
                // 会把真主控反复顶掉，形成 600ms 一次的互踢拉锯。真主控来自另一台手机的局域网 IP，不受影响。
                InetAddress addr = s.getInetAddress();
                if (isSelf(addr) || !isLan(addr)) {
                    try { s.close(); } catch (IOException ignored) {}
                    continue;
                }
                pool.execute(() -> handleClient(s));
            } catch (IOException e) {
                if (!running) break;
                if (serverSocket.isClosed()) break;   // 端口已关，退出线程，交给看门狗重建
                Log.w(TAG, "accept 异常", e);
                // 避免 socket 被系统关掉后立刻再 accept 再抛异常、空转打日志
                try {
                    Thread.sleep(200);
                } catch (InterruptedException ie) {
                    break;   // 被打断就退出
                }
                continue;
            }
        }
    }

    private void handleClient(Socket s) {
        ClientConn conn;
        synchronized (activeLock) {
            closeActiveLocked(); // 只允许一个主控，顶掉旧的
            conn = new ClientConn(s, ++connSeq);
            active = conn;
        }
        CtrlBus.setClientConnected(true);
        Log.i(TAG, "主控已连接 #" + conn.seq + " " + s.getInetAddress());

        try {
            BufferedReader in = new BufferedReader(new InputStreamReader(
                    s.getInputStream(), StandardCharsets.UTF_8), 1024);
            // 新连接先推一次完整状态（异步写，不阻塞读线程）
            conn.send(stateLine());
            String line;
            while (running && (line = readLineLimited(in)) != null) {
                line = line.trim();
                if (line.isEmpty()) continue;
                if (CMD_HELLO.equalsIgnoreCase(line)) {
                    conn.send(stateLine());
                    continue;
                }
                if (CMD_PING.equalsIgnoreCase(line)) {
                    conn.send("PONG");
                    continue;
                }
                if (line.charAt(0) == '{') {
                    CmdDispatcher.dispatch(line); // 发完就走，不等回音
                }
            }
        } catch (Exception e) {
            Log.w(TAG, "主控连接断开 #" + conn.seq + ": " + e.getMessage());
        } finally {
            synchronized (activeLock) {
                if (active == conn) {
                    active = null;
                    CtrlBus.setClientConnected(false);
                }
            }
            conn.close();
        }
    }

    /** 边读边限长：超过 MAX_LINE 就丢掉整行剩余内容，不让超长数据整行入内存。 */
    private static String readLineLimited(BufferedReader in) throws IOException {
        StringBuilder sb = new StringBuilder();
        int ch;
        while ((ch = in.read()) != -1) {
            if (ch == '\n') return sb.toString();
            if (ch == '\r') continue;
            if (sb.length() >= MAX_LINE) {
                int c;
                while ((c = in.read()) != -1 && c != '\n') { /* 丢弃超长行剩余 */ }
                return "";   // 当作空行忽略
            }
            sb.append((char) ch);
        }
        return sb.length() > 0 ? sb.toString() : null;   // EOF 且无数据 = 断开
    }

    /** 状态变化时主动推给主控（总开关/无障碍变化）。只投递，不在这里做网络 I/O。 */
    public static void pushState() {
        CtrlLinkServer srv = instance;
        if (srv == null) return;
        ClientConn c = srv.active;
        if (c != null) c.send(stateLine());
    }

    private static volatile CtrlLinkServer instance = null;

    public static void setInstance(CtrlLinkServer s) { instance = s; }

    private static String stateLine() {
        try {
            JSONObject o = new JSONObject();
            o.put("enabled", CtrlBus.isEnabled());
            o.put("accessibility", CtrlBus.isAccessibilityConnected());
            o.put("server", CtrlBus.isServerRunning());
            o.put("video", ScreenStreamer.hasProjection());
            return "S " + o.toString();
        } catch (Exception e) {
            return "S {}";
        }
    }

    private void closeActive() {
        synchronized (activeLock) { closeActiveLocked(); }
    }

    private void closeActiveLocked() {
        ClientConn c = active;
        if (c != null) {
            active = null;
            c.close();
        }
    }

    /** 单个主控连接；下行写全部异步投递到 DOWN 单线程，调用方零阻塞。 */
    private static class ClientConn {
        final Socket socket;
        final int seq;
        private volatile BufferedWriter writer;

        ClientConn(Socket s, int seq) {
            this.socket = s;
            this.seq = seq;
            try {
                writer = new BufferedWriter(new OutputStreamWriter(
                        s.getOutputStream(), StandardCharsets.UTF_8));
            } catch (Exception e) {
                Log.w(TAG, "取输出流失败 #" + seq + ": " + e.getMessage());
            }
        }

        void send(String line) {
            final BufferedWriter w = writer;
            if (w == null) return;
            DOWN.execute(() -> writeLine(w, line));
        }

        private void writeLine(BufferedWriter w, String line) {
            if (writer != w) return;   // 已关闭或被替换，丢弃这次写
            try {
                w.write(line);
                w.write('\n');
                w.flush();
            } catch (IOException e) {
                Log.w(TAG, "下行写失败 #" + seq + ": " + e.getMessage());
                close();
            }
        }

        synchronized void close() {
            writer = null;
            try { socket.close(); } catch (IOException ignored) {}
        }
    }

    /** 判定来源是不是本机自己：防止本机 client 连回自己的 server 把真主控顶掉。 */
    private boolean isSelf(InetAddress addr) {
        if (addr == null) return false;
        if (addr.isLoopbackAddress() || addr.isAnyLocalAddress()) return true;
        String ip = addr.getHostAddress();
        if (ip == null) return false;
        if (ip.equals(CtrlWebUi.hostIp(appContext))) return true;
        if (ip.startsWith("127.")) return true;
        if ("::1".equals(ip) || "0:0:0:0:0:0:0:1".equals(ip)) return true;
        return false;
    }

    private boolean isLan(InetAddress addr) {
        if (addr == null) return false;
        if (addr.isLoopbackAddress() || addr.isLinkLocalAddress()) return true;
        if (addr.isSiteLocalAddress()) return true;
        byte[] b = addr.getAddress();
        if (b.length == 4) {
            int a = b[0] & 0xff, c = b[1] & 0xff;
            if (a == 100 && c >= 64 && c <= 127) return true;
        }
        if (b.length == 16 && (b[0] & 0xfe) == 0xfc) return true;
        return false;
    }
}
