package com.example.healthmon;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;

/**
 * TCP 客户端：连设备、读字节流、切成行、回调给界面。
 *
 * 【为什么是纯 Java】它只依赖 java.net 和线程，不 import android.*。
 * 于是最容易出错的那些地方 —— 半帧跨多次 read、对端关闭、连上但没数据、
 * 线程泄漏 —— 全部能用真实的本地 TCP 服务器在本机一秒内验证，
 * 而不是等一次设备安装或云构建。
 *
 * 【回调线程】onLine / onState 都在后台线程上调用，界面层负责切回主线程。
 * 这里刻意不依赖 android.os.Handler，正是为了让上面那件事成立。
 */
public final class NetManager {

    // ---- 状态 ----
    public static final int STATE_IDLE = 0;
    public static final int STATE_CONNECTING = 1;
    public static final int STATE_CONNECTED = 2;
    /** 已连上但一直没有数据 —— 必须和"没连上"区分开，否则用户对着转圈界面干等 */
    public static final int STATE_NO_DATA = 3;
    public static final int STATE_DISCONNECTED = 4;
    public static final int STATE_ERROR = 5;

    public interface Listener {
        void onLine(String line);
        void onState(int state, String detail);
    }

    private static final int CONNECT_TIMEOUT_MS = 3000;
    private static final long NO_DATA_DEFAULT_MS = 3000;

    private final Listener listener;

    private volatile Socket socket;
    private volatile OutputStream out;
    private volatile boolean running = false;
    private volatile long noDataTimeoutMs = NO_DATA_DEFAULT_MS;
    private volatile long lastLineAt = 0;
    private volatile boolean noDataReported = false;

    private Thread reader;
    private Thread watchdog;
    private final Object sendLock = new Object();

    public NetManager(Listener listener) {
        this.listener = listener;
    }

    /** 测试用：缩短"连上但没数据"的判定时间 */
    public void setNoDataTimeoutMs(long ms) {
        this.noDataTimeoutMs = ms;
    }

    public boolean isConnected() {
        Socket s = socket;
        return running && s != null && s.isConnected() && !s.isClosed();
    }

    /**
     * 连接。非阻塞 —— 结果通过 onState 回调。
     * 重复调用会先断开旧的，避免两个连接同时回调。
     */
    public void connect(final String host, final int port) {
        disconnect();
        running = true;
        emit(STATE_CONNECTING, host + ":" + port);

        Thread t = new Thread(new Runnable() {
            public void run() {
                Socket s = new Socket();
                try {
                    s.connect(new InetSocketAddress(host, port), CONNECT_TIMEOUT_MS);
                    s.setTcpNoDelay(true);          // 小包低延迟
                    if (!running) { s.close(); return; }
                    socket = s;
                    out = s.getOutputStream();
                    lastLineAt = System.currentTimeMillis();
                    noDataReported = false;
                    emit(STATE_CONNECTED, host + ":" + port);
                    startWatchdog();
                    readLoop(s);
                } catch (IOException e) {
                    closeQuietly(s);
                    if (running) {
                        emit(STATE_ERROR, shortMessage(e));
                    }
                } finally {
                    if (running) {
                        running = false;
                        emit(STATE_DISCONNECTED, null);
                    }
                }
            }
        }, "netmgr-connect");
        t.start();
    }

    /** 读循环：字节流 -> LineAssembler -> 完整行 */
    private void readLoop(Socket s) {
        LineAssembler asm = new LineAssembler();
        byte[] buf = new byte[1024];
        try {
            InputStream in = s.getInputStream();
            int n;
            while (running && (n = in.read(buf)) > 0) {
                asm.feed(buf, n);
                String line;
                while ((line = asm.poll()) != null) {
                    lastLineAt = System.currentTimeMillis();
                    noDataReported = false;
                    if (running) listener.onLine(line);
                }
            }
            // read 返回 -1：对端正常关闭。不是错误，走 finally 报 DISCONNECTED。
        } catch (IOException e) {
            if (running) emit(STATE_ERROR, shortMessage(e));
        }
    }

    /** 看门狗：连上了却长时间没有数据 -> 报 NO_DATA（只报一次） */
    private void startWatchdog() {
        Thread w = new Thread(new Runnable() {
            public void run() {
                while (running) {
                    try { Thread.sleep(200); } catch (InterruptedException e) { return; }
                    if (!running) return;
                    if (!noDataReported && System.currentTimeMillis() - lastLineAt > noDataTimeoutMs) {
                        noDataReported = true;
                        emit(STATE_NO_DATA, null);
                    }
                }
            }
        }, "netmgr-watchdog");
        w.setDaemon(true);
        watchdog = w;
        w.start();
    }

    /**
     * 发送一行。返回是否真的写出去了 —— 不吞异常，也不假装成功。
     * 界面据此决定要不要标记"已发送"。
     */
    public boolean send(String line) {
        OutputStream o = out;
        if (!running || o == null) return false;
        try {
            synchronized (sendLock) {
                o.write(line.getBytes("UTF-8"));
                o.flush();
            }
            return true;
        } catch (IOException e) {
            return false;
        }
    }

    /** 断开。必须能可靠停掉读线程与看门狗，否则界面会被已死的连接刷新。 */
    public void disconnect() {
        running = false;
        Socket s = socket;
        socket = null;
        out = null;
        closeQuietly(s);
        Thread r = reader; reader = null;
        Thread w = watchdog; watchdog = null;
        if (r != null) r.interrupt();
        if (w != null) w.interrupt();
    }

    private void emit(int state, String detail) {
        try {
            listener.onState(state, detail);
        } catch (RuntimeException ignored) {
            // 监听者自己的异常不该把网络线程带崩
        }
    }

    private static void closeQuietly(Socket s) {
        if (s == null) return;
        try { s.close(); } catch (IOException ignored) {}
    }

    /** 把异常压成一句人话，别把整段堆栈丢到界面上 */
    private static String shortMessage(IOException e) {
        String m = e.getMessage();
        if (m == null) return e.getClass().getSimpleName();
        return m.length() > 80 ? m.substring(0, 80) : m;
    }
}
