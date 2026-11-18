package com.teny.blindctrl;

import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.Iterator;

/**
 * 主控端长连接客户端：一根 TCP 常连到被控端，指令发完就走不等回音，自动重连。
 * 低延迟关键：
 *  - 常连，不再每条指令都新建 HTTP 请求；
 *  - TCP_NODELAY 关 Nagle；
 *  - move 高频但可合并：发送队列里未发出的旧 move 会被新 move 顶掉，不堆积、不排队；
 *  - down/up/swipe/HELLO/PING 保序，绝不丢。
 *
 * 用 Item.flag 标记可合并项，不靠 JSONObject 的字符串前缀判断
 * （org.json 不保证键顺序，前缀判断会失灵导致坐标堆积）。
 *
 * 静态审查修复：
 *  - 一条连接的 socket/reader/writer 绑成不可变 Connection；发送线程整轮只认同一条连接，
 *    写失败只关自己这条 socket，绝不会关到刚重连上的新连接（旧版读全局 socket 的竞态）。
 *  - 未连接时发送线程等待而不是把 poll 出来的指令丢掉，down/up 绝不静默吞掉。
 *  - 断开处理用「当前连接是否仍是自己」做代次幂等，不再重复清队列/重复发通知。
 *  - stop 后置 dead，所有已 post 但未执行的 UI 回调自我丢弃，旧 client 不会覆盖新 client 的状态。
 */
public class LinkClient {

    private static final String TAG = "BlindCtrl";
    private static final int CONNECT_TIMEOUT = 1500;
    private static final int SO_TIMEOUT = 4000;
    private static final long PONG_TIMEOUT = 12000;
    private static final long RECONNECT_MS = 600;

    public interface Listener {
        void onLinkState(boolean connected, String detail);
        void onRemoteState(boolean enabled, boolean accessibility, boolean video);
    }

    private static final int F_KEEP = 0;      // 保序，绝不丢
    private static final int F_MOVE = 1;      // 可被后一条 move 顶掉

    private static class Item {
        final String line;
        final int flag;
        Item(String line, int flag) { this.line = line; this.flag = flag; }
    }

    /** 一条连接的所有资源绑在一起，发送线程整轮只认同一个 Connection。 */
    private static class Connection {
        final Socket socket;
        final BufferedReader reader;
        final BufferedWriter writer;
        Connection(Socket socket, BufferedReader reader, BufferedWriter writer) {
            this.socket = socket;
            this.reader = reader;
            this.writer = writer;
        }
    }

    private final String host;
    private final int port;
    private final Listener listener;
    private final Handler ui = new Handler(Looper.getMainLooper());

    private final ArrayDeque<Item> queue = new ArrayDeque<>();
    // cur 由 queue 锁保护；volatile 让读循环无锁可见
    private volatile Connection cur = null;
    private volatile boolean running = false;
    private volatile boolean dead = false;      // stop 后置位，丢弃过期 UI 回调
    private volatile boolean connected = false;
    private volatile long lastDownAt = 0;

    private Thread connThread, sendThread;

    public LinkClient(String host, int port, Listener listener) {
        this.host = host;
        this.port = port;
        this.listener = listener;
    }

    public void start() {
        if (running) return;
        running = true;
        dead = false;
        connThread = new Thread(this::connectLoop, "BlindCtrl-CONN");
        sendThread = new Thread(this::sendLoop, "BlindCtrl-SEND");
        connThread.start();
        sendThread.start();
    }

    public void stop() {
        dead = true;
        running = false;
        Connection c;
        synchronized (queue) {
            c = cur;
            cur = null;
            connected = false;
            queue.clear();
            queue.notifyAll();
        }
        if (c != null) {
            try { c.socket.close(); } catch (IOException ignored) {}
        }
        if (connThread != null) connThread.interrupt();
        if (sendThread != null) sendThread.interrupt();
        // 不主动 post UI：换地址时由新 client 接管，APP 关闭时 UI 本就销毁
    }

    public boolean isConnected() { return connected; }
    public String getHost() { return host; }

    /** 控制类指令：保序，绝不丢。 */
    public void sendControl(String line) {
        if (!running || dead) return;
        synchronized (queue) {
            queue.addLast(new Item(line, F_KEEP));
            queue.notifyAll();
        }
    }

    /** 实时 move：顶掉队列里还没发出的旧 move，避免高频坐标堆积。 */
    public void sendMove(String line) {
        if (!running || dead) return;
        synchronized (queue) {
            Iterator<Item> it = queue.iterator();
            while (it.hasNext()) {
                if (it.next().flag == F_MOVE) it.remove();
            }
            queue.addLast(new Item(line, F_MOVE));
            queue.notifyAll();
        }
    }

    /* ---------------- 连接与读下行 ---------------- */

    private void connectLoop() {
        while (running && !dead) {
            Socket s = null;
            Connection conn = null;
            try {
                s = new Socket();
                s.connect(new InetSocketAddress(host, port), CONNECT_TIMEOUT);
                s.setTcpNoDelay(true);
                s.setSoTimeout(SO_TIMEOUT);
                BufferedReader reader = new BufferedReader(new InputStreamReader(
                        s.getInputStream(), StandardCharsets.UTF_8), 1024);
                BufferedWriter w = new BufferedWriter(new OutputStreamWriter(
                        s.getOutputStream(), StandardCharsets.UTF_8));

                synchronized (queue) {
                    if (!running || dead) {
                        conn = null;   // stop 抢在建立完成前，放弃这条连接
                    } else {
                        conn = new Connection(s, reader, w);
                        cur = conn;
                        queue.notifyAll();   // 唤醒 sendLoop
                    }
                }
                if (conn == null) {
                    try { s.close(); } catch (IOException ignored) {}
                    break;
                }

                connected = true;
                lastDownAt = System.currentTimeMillis();
                notifyLink(true, host + ":" + port);
                sendControl("HELLO");

                while (running && !dead && cur == conn) {
                    String line;
                    try {
                        line = reader.readLine();
                    } catch (java.net.SocketTimeoutException te) {
                        sendControl("PING");
                        if (System.currentTimeMillis() - lastDownAt > PONG_TIMEOUT) {
                            Log.w(TAG, "探活超时，重连");
                            break;
                        }
                        continue;
                    }
                    if (line == null) break;
                    lastDownAt = System.currentTimeMillis();
                    handleDown(line.trim());
                }
            } catch (Exception e) {
                if (running && !dead) Log.w(TAG, "连接失败: " + e.getMessage());
            } finally {
                if (conn != null) closeConnection(conn);
                else {
                    try { if (s != null) s.close(); } catch (IOException ignored) {}
                }
            }
            if (!running || dead) break;
            try { Thread.sleep(RECONNECT_MS); } catch (InterruptedException e) { break; }
        }
    }

    /** 只关这一条连接；仅当它仍是当前连接时才清状态、发断开通知（代次幂等）。 */
    private void closeConnection(Connection c) {
        boolean notify = false;
        synchronized (queue) {
            if (cur == c) {
                cur = null;
                connected = false;
                notify = true;
                // 断开即丢弃 in-flight 手势指令：连接已断，被控端手势状态也已失效，
                // 重连后由 MainActivity 的 resendDownIfPressing 重建正在按着的手势，
                // 比把半截 down/move 发到新连接更干净。
                queue.clear();
                queue.notifyAll();   // 让 sendLoop 重新等待连接
            }
        }
        try { c.socket.close(); } catch (IOException ignored) {}
        if (notify) notifyLink(false, "连接断开，重连中…");
    }

    private void handleDown(String line) {
        if (line.equals("PONG")) return;
        if (line.startsWith("S ")) {
            try {
                JSONObject o = new JSONObject(line.substring(2));
                boolean en = o.optBoolean("enabled", false);
                boolean ax = o.optBoolean("accessibility", false);
                boolean vid = o.optBoolean("video", false);
                ui.post(() -> { if (!dead) listener.onRemoteState(en, ax, vid); });
            } catch (Exception ignored) {}
        }
    }

    /* ---------------- 发送 ---------------- */

    private void sendLoop() {
        while (running && !dead) {
            Item item;
            Connection c;
            synchronized (queue) {
                // 没连接或队列空就等：指令留在队列里，绝不静默吞掉
                while (running && !dead && (cur == null || queue.isEmpty())) {
                    try { queue.wait(); } catch (InterruptedException e) { return; }
                }
                if (!running || dead) return;
                item = queue.pollFirst();
                c = cur;   // 本轮绑定这一条连接，后续只认它
            }
            if (item == null || c == null) continue;
            try {
                c.writer.write(item.line);
                c.writer.write('\n');
                c.writer.flush();
            } catch (IOException e) {
                // 只关这次写失败的连接，绝不碰可能刚重连上的新连接
                closeConnection(c);
            }
        }
    }

    private void notifyLink(boolean c, String detail) {
        ui.post(() -> { if (!dead) listener.onLinkState(c, detail); });
    }
}
