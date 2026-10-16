import com.example.healthmon.NetManager;

import java.io.InputStream;
import java.io.OutputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 网络层测试 —— 用【真实的本地 TCP 服务器】跑，不打桩、不模拟。
 *
 * 这是可能的，因为 NetManager 只依赖 java.net，不需要 android API。
 * 于是它最容易出错的地方（半帧、对端关闭、连上但没数据、线程泄漏）
 * 全部能在本机一秒内验证，而不用等一次设备安装或云构建。
 *
 * 线程测试最容易写成 flaky，所以这里统一用"带截止时间的轮询"，
 * 不用 sleep 猜时间。
 */
public class NetManagerTest {

    /** 收集回调事件，供轮询等待 */
    static final class Recorder implements NetManager.Listener {
        final List<String> lines = Collections.synchronizedList(new ArrayList<String>());
        final List<String> states = Collections.synchronizedList(new ArrayList<String>());

        public void onLine(String line) { lines.add(line); }
        public void onState(int state, String detail) { states.add(state + (detail == null ? "" : ":" + detail)); }

        boolean sawState(int s) {
            synchronized (states) {
                for (String x : states) if (x.startsWith(s + "")) return true;
            }
            return false;
        }
        int lineCount() { return lines.size(); }
    }

    /** 带截止时间的轮询等待 —— 比 sleep 固定时间可靠 */
    static boolean waitFor(Cond cond, long timeoutMs) {
        long end = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < end) {
            if (cond.ok()) return true;
            try { Thread.sleep(20); } catch (InterruptedException e) { return false; }
        }
        return cond.ok();
    }

    interface Cond { boolean ok(); }

    public static void main(String[] args) throws Exception {
        connectAndReceive();
        halfFrameAcrossReads();
        sendReachesServer();
        serverCloseDetected();
        deadPortReportsError();
        connectedButSilentReportsNoData();
        disconnectStopsCallbacks();
        T.done();
    }

    // ---------------------------------------------------------------- 用例

    /** 最基本的：连上、收到一帧完整数据。 */
    static void connectAndReceive() throws Exception {
        ServerSocket ss = new ServerSocket(0);
        final Socket[] held = new Socket[1];
        Thread acceptor = new Thread(new Runnable() {
            public void run() {
                try { held[0] = ss.accept(); } catch (Exception ignored) {}
            }
        });
        acceptor.start();

        Recorder rec = new Recorder();
        NetManager nm = new NetManager(rec);
        nm.connect("127.0.0.1", ss.getLocalPort());

        T.ok("connect: reaches CONNECTED", waitFor(new Cond() { public boolean ok() { return rec.sawState(NetManager.STATE_CONNECTED); } }, 4000));
        T.ok("connect: isConnected()", nm.isConnected());

        waitFor(new Cond() { public boolean ok() { return held[0] != null; } }, 2000);
        OutputStream os = held[0].getOutputStream();
        os.write("$D,SEQ=1,BT=36.5,SW=1,AL=0,*00\n".getBytes("UTF-8"));
        os.flush();

        T.ok("connect: line delivered", waitFor(new Cond() { public boolean ok() { return rec.lineCount() >= 1; } }, 3000));
        T.eq("connect: line content intact", rec.lines.get(0), "$D,SEQ=1,BT=36.5,SW=1,AL=0,*00");

        nm.disconnect();
        ss.close();
    }

    /** 半帧跨两次 read 到达 —— TCP 是字节流，这是必须处理的情况。 */
    static void halfFrameAcrossReads() throws Exception {
        ServerSocket ss = new ServerSocket(0);
        final Socket[] held = new Socket[1];
        new Thread(new Runnable() { public void run() { try { held[0] = ss.accept(); } catch (Exception ignored) {} } }).start();

        Recorder rec = new Recorder();
        NetManager nm = new NetManager(rec);
        nm.connect("127.0.0.1", ss.getLocalPort());
        waitFor(new Cond() { public boolean ok() { return held[0] != null; } }, 3000);

        OutputStream os = held[0].getOutputStream();
        os.write("$D,SEQ=7,BT=36".getBytes("UTF-8"));
        os.flush();
        Thread.sleep(120);
        T.eq("half: nothing emitted before newline", rec.lineCount(), 0);

        os.write(".6,SW=1,*00\n".getBytes("UTF-8"));
        os.flush();
        T.ok("half: assembled after newline", waitFor(new Cond() { public boolean ok() { return rec.lineCount() >= 1; } }, 3000));
        T.eq("half: content", rec.lines.get(0), "$D,SEQ=7,BT=36.6,SW=1,*00");

        nm.disconnect();
        ss.close();
    }

    /** 客户端发出去的东西必须真的到达对端，且 send 的返回值要如实。 */
    static void sendReachesServer() throws Exception {
        ServerSocket ss = new ServerSocket(0);
        final Socket[] held = new Socket[1];
        new Thread(new Runnable() { public void run() { try { held[0] = ss.accept(); } catch (Exception ignored) {} } }).start();

        Recorder rec = new Recorder();
        NetManager nm = new NetManager(rec);
        nm.connect("127.0.0.1", ss.getLocalPort());
        waitFor(new Cond() { public boolean ok() { return held[0] != null && rec.sawState(NetManager.STATE_CONNECTED); } }, 3000);

        T.ok("send: returns true when connected", nm.send("$C,CMD=QUERY,*00\n"));

        InputStream is = held[0].getInputStream();
        byte[] buf = new byte[64];
        final StringBuilder got = new StringBuilder();
        T.ok("send: server receives it", waitFor(new Cond() {
            public boolean ok() {
                try {
                    if (is.available() > 0) { int n = is.read(buf); got.append(new String(buf, 0, n, "UTF-8")); }
                } catch (Exception ignored) {}
                return got.indexOf("QUERY") >= 0;
            }
        }, 3000));
        T.ok("send: payload correct", got.toString().startsWith("$C,CMD=QUERY"));

        nm.disconnect();
        ss.close();
    }

    /** 对端关闭必须被检测到 —— 否则界面会一直显示"已连接"。 */
    static void serverCloseDetected() throws Exception {
        ServerSocket ss = new ServerSocket(0);
        final Socket[] held = new Socket[1];
        new Thread(new Runnable() { public void run() { try { held[0] = ss.accept(); } catch (Exception ignored) {} } }).start();

        Recorder rec = new Recorder();
        NetManager nm = new NetManager(rec);
        nm.connect("127.0.0.1", ss.getLocalPort());
        waitFor(new Cond() { public boolean ok() { return held[0] != null && rec.sawState(NetManager.STATE_CONNECTED); } }, 3000);

        held[0].close();
        T.ok("close: DISCONNECTED reported", waitFor(new Cond() { public boolean ok() { return rec.sawState(NetManager.STATE_DISCONNECTED); } }, 4000));
        T.ok("close: isConnected() false", waitFor(new Cond() { public boolean ok() { return !nm.isConnected(); } }, 2000));

        nm.disconnect();
        ss.close();
    }

    /** 连一个没人监听的端口 -> 必须报 ERROR，不能一直转圈。 */
    static void deadPortReportsError() throws Exception {
        // 先占一个端口再关掉，确保它没人监听
        ServerSocket tmp = new ServerSocket(0);
        int deadPort = tmp.getLocalPort();
        tmp.close();

        Recorder rec = new Recorder();
        NetManager nm = new NetManager(rec);
        nm.connect("127.0.0.1", deadPort);

        T.ok("dead: ERROR reported", waitFor(new Cond() { public boolean ok() { return rec.sawState(NetManager.STATE_ERROR); } }, 6000));
        T.ok("dead: not connected", !nm.isConnected());
        nm.disconnect();
    }

    /**
     * 连上了但一直没数据 —— 必须能和"没连上"区分开。
     * 上一项目在这里让用户对着转圈界面干等，所以这条单独测。
     */
    static void connectedButSilentReportsNoData() throws Exception {
        ServerSocket ss = new ServerSocket(0);
        final Socket[] held = new Socket[1];
        new Thread(new Runnable() { public void run() { try { held[0] = ss.accept(); } catch (Exception ignored) {} } }).start();

        Recorder rec = new Recorder();
        NetManager nm = new NetManager(rec);
        nm.setNoDataTimeoutMs(600);            // 测试里缩短，避免拖慢套件
        nm.connect("127.0.0.1", ss.getLocalPort());

        T.ok("silent: CONNECTED first", waitFor(new Cond() { public boolean ok() { return rec.sawState(NetManager.STATE_CONNECTED); } }, 4000));
        T.ok("silent: NO_DATA reported", waitFor(new Cond() { public boolean ok() { return rec.sawState(NetManager.STATE_NO_DATA); } }, 4000));
        T.ok("silent: still 'connected' at socket level", nm.isConnected());

        nm.disconnect();
        ss.close();
    }

    /** disconnect() 必须真的停掉读线程，之后不能再有回调（否则界面会被已死的连接刷新）。 */
    static void disconnectStopsCallbacks() throws Exception {
        ServerSocket ss = new ServerSocket(0);
        final Socket[] held = new Socket[1];
        new Thread(new Runnable() { public void run() { try { held[0] = ss.accept(); } catch (Exception ignored) {} } }).start();

        Recorder rec = new Recorder();
        NetManager nm = new NetManager(rec);
        nm.connect("127.0.0.1", ss.getLocalPort());
        waitFor(new Cond() { public boolean ok() { return rec.sawState(NetManager.STATE_CONNECTED); } }, 3000);

        nm.disconnect();
        T.ok("disconnect: isConnected() false", !nm.isConnected());

        int before = rec.states.size();
        Thread.sleep(600);                      // 给残余线程时间"犯错"
        T.eq("disconnect: no further state callbacks", rec.states.size(), before);
        T.ok("disconnect: send() refuses", !nm.send("$C,CMD=QUERY,*00\n"));

        ss.close();
    }
}
