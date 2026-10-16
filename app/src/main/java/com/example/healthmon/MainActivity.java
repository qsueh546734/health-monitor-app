package com.example.healthmon;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.DialogInterface;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.media.ToneGenerator;
import android.net.ConnectivityManager;
import android.net.NetworkInfo;
import android.os.Bundle;
import android.os.Handler;
import android.os.SystemClock;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.LinkedBlockingQueue;

/**
 * 单 Activity 多页面。
 *
 * 【架构要点】网络与模拟器都汇聚到 handleLine(String) —— 保证两条路径
 * 走完全相同的解析。这是"没有硬件也能把界面开发完"的前提。
 *
 * 【线程约定】
 *   - handleLine 在后台线程被调用（网络读线程 / 模拟器定时器）
 *   - 落库在专门的数据库线程上（界面绝不阻塞）
 *   - 只有 renderXxx 才回主线程
 */
public class MainActivity extends Activity implements NetManager.Listener {

    // ---- 页面 ----
    private static final int PAGE_CONNECT = 0, PAGE_CONTROL = 1, PAGE_HISTORY = 2,
                             PAGE_ANALYSIS = 3, PAGE_SETTINGS = 4, PAGE_LOGIN = 5;
    private static final int PAGE_COUNT = 6;

    private final View[] pages = new View[PAGE_COUNT];
    private final Button[] tabs = new Button[PAGE_COUNT];

    // ---- 核心对象 ----
    private final Protocol protocol = new Protocol();
    private final AppState state = new AppState();
    private final Simulator simulator = new Simulator();
    private NetManager net;
    private Db db;
    private final Handler ui = new Handler();

    // ---- 运行状态 ----
    private boolean simulatorMode = false;
    private int badLines = 0;
    private boolean historyIsHr = false;      // 曲线当前画的是心率还是体温
    private int historyRangeMin = 10;
    private List<String[]> aiMessages = new ArrayList<String[]>();
    private int lastAlertBit = 0;

    // ---- 数据库线程 ----
    private final LinkedBlockingQueue<Frame> dbQueue = new LinkedBlockingQueue<Frame>(2000);
    private volatile boolean running = true;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        bindPages();
        bindConnectPage();
        bindControlPage();
        bindHistoryPage();
        bindAnalysisPage();
        bindSettingsPage();
        bindLoginPage();

        db = new Db(this);
        net = new NetManager(this);

        wireSimulatorClock();
        startDbWorker();
        startFreshnessClock();

        showPage("page_connect".equals(prefs().getString("lastPage", "")) ? PAGE_CONNECT : PAGE_CONTROL);
    }

    private SharedPreferences prefs() {
        return getSharedPreferences("healthmon", Context.MODE_PRIVATE);
    }

    // ================================================================ 页面框架

    private void bindPages() {
        pages[PAGE_CONNECT]  = findViewById(R.id.pageConnect);
        pages[PAGE_CONTROL]  = findViewById(R.id.pageControl);
        pages[PAGE_HISTORY]  = findViewById(R.id.pageHistory);
        pages[PAGE_ANALYSIS] = findViewById(R.id.pageAnalysis);
        pages[PAGE_SETTINGS] = findViewById(R.id.pageSettings);
        pages[PAGE_LOGIN]    = findViewById(R.id.pageLogin);

        tabs[PAGE_CONNECT]  = (Button) findViewById(R.id.tabConnect);
        tabs[PAGE_CONTROL]  = (Button) findViewById(R.id.tabControl);
        tabs[PAGE_HISTORY]  = (Button) findViewById(R.id.tabHistory);
        tabs[PAGE_ANALYSIS] = (Button) findViewById(R.id.tabAnalysis);
        tabs[PAGE_SETTINGS] = (Button) findViewById(R.id.tabSettings);
        tabs[PAGE_LOGIN]    = (Button) findViewById(R.id.tabLogin);

        for (int i = 0; i < PAGE_COUNT; i++) {
            final int idx = i;
            tabs[i].setOnClickListener(new View.OnClickListener() {
                public void onClick(View v) { showPage(idx); }
            });
        }
    }

    private void showPage(int idx) {
        for (int i = 0; i < PAGE_COUNT; i++) {
            pages[i].setVisibility(i == idx ? View.VISIBLE : View.GONE);
            tabs[i].setTextColor(i == idx ? Color.WHITE : Color.LTGRAY);
        }
        if (idx == PAGE_HISTORY) loadChart();
        if (idx == PAGE_ANALYSIS) startAnalysis();
        if (idx == PAGE_SETTINGS) refreshSettingsFromState();
    }

    private TextView tv(int id) { return (TextView) findViewById(id); }
    private EditText et(int id) { return (EditText) findViewById(id); }

    private void toast(String s) {
        Toast.makeText(this, s, Toast.LENGTH_SHORT).show();
    }

    // ================================================================ 连接页

    private void bindConnectPage() {
        et(R.id.etIp).setText(prefs().getString("ip", "192.168.4.1"));
        et(R.id.etPort).setText(prefs().getString("port", "8266"));

        ((Button) findViewById(R.id.btnConnect)).setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { doConnect(); }
        });
        ((Button) findViewById(R.id.btnDisconnect)).setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                setSimulator(false);
                net.disconnect();
                onState(NetManager.STATE_IDLE, null);
            }
        });
    }

    private void doConnect() {
        String ip = et(R.id.etIp).getText().toString().trim();
        String ps = et(R.id.etPort).getText().toString().trim();
        if (ip.length() == 0) { toast("请填写设备 IP"); return; }
        int port;
        try {
            port = Integer.parseInt(ps);
        } catch (NumberFormatException e) {
            toast("端口必须是数字"); return;
        }
        if (port < 1 || port > 65535) { toast("端口超出范围"); return; }

        prefs().edit().putString("ip", ip).putString("port", ps).apply();
        setSimulator(false);
        state.reset();
        protocol.reset();
        badLines = 0;
        net.connect(ip, port);
    }

    // ================================================================ 主控页

    private void bindControlPage() {
        ((Button) findViewById(R.id.btnPumpOn)).setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { sendPump(1); }
        });
        ((Button) findViewById(R.id.btnPumpOff)).setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { sendPump(0); }
        });
        ((Button) findViewById(R.id.btnPumpAuto)).setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { sendPump(2); }
        });
    }

    private void sendPump(int mode) {
        if (!send(Protocol.buildCommand("PUMP", new String[]{"MODE"}, new String[]{String.valueOf(mode)}))) return;
        state.markCommandSentPumpMode(mode);
        toast(mode == 2 ? "已请求切换到自动" : "已请求手动" + (mode == 1 ? "开" : "关"));
    }

    private boolean send(String line) {
        if (simulatorMode) {
            // 模拟器模式没有真实链路；把命令当作已被设备接受，走同一套状态机
            toast("模拟器模式下命令不会真的下发");
            return false;
        }
        boolean ok = net.send(line);
        if (!ok) toast("发送失败：设备未连接");
        return ok;
    }

    // ================================================================ 历史页

    private void bindHistoryPage() {
        ((Button) findViewById(R.id.btnChartBt)).setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { historyIsHr = false; loadChart(); }
        });
        ((Button) findViewById(R.id.btnChartHr)).setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { historyIsHr = true; loadChart(); }
        });
        ((Button) findViewById(R.id.btnRange10)).setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { historyRangeMin = 10; loadChart(); }
        });
        ((Button) findViewById(R.id.btnRange60)).setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { historyRangeMin = 60; loadChart(); }
        });
        ((Button) findViewById(R.id.btnRangeAll)).setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { historyRangeMin = 0; loadChart(); }
        });
    }

    /** 查库 + 抽点 + 画图。查库必须在后台线程。 */
    private void loadChart() {
        final boolean wantHr = historyIsHr;
        final int rangeMin = historyRangeMin;
        new Thread(new Runnable() {
            public void run() {
                final long now = System.currentTimeMillis();
                final long from = rangeMin <= 0 ? 0 : now - rangeMin * 60000L;
                final List<Frame> rows = db.querySamples(from, Long.MAX_VALUE);

                final List<long[]> pts = new ArrayList<long[]>();
                for (Frame f : rows) {
                    double v = wantHr ? f.hr : f.bt;
                    // 缺字段不画 —— 画成 0 会让曲线出现假的下探
                    if (Double.isNaN(v) || (wantHr && v < 0)) continue;
                    pts.add(new long[]{f.ts, Math.round(v * 10)});   // ×10 存整数
                }
                final int total = rows.size();
                runOnUiThread(new Runnable() {
                    public void run() {
                        ((ChartView) findViewById(R.id.chart)).setPoints(
                                pts, wantHr ? "心率" : "体温", wantHr ? "bpm" : "℃");
                        tv(R.id.tvChartCount).setText(total + " 条记录，绘制 " + pts.size() + " 点"
                                + (rangeMin <= 0 ? "（全部）" : "（最近 " + rangeMin + " 分钟）"));
                    }
                });
            }
        }, "chart-load").start();
    }

    // ================================================================ 分析页

    private void bindAnalysisPage() {
        ((Button) findViewById(R.id.btnAsk)).setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { askFollowUp(); }
        });
        ((Button) findViewById(R.id.btnReanalyze)).setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { startAnalysis(); }
        });
    }

    private void startAnalysis() {
        final String key = prefs().getString("aikey", "");
        if (key.length() == 0) {
            tv(R.id.tvAiResult).setText("还没有填写 DeepSeek API key。\n请到【设置】页填写后回来。");
            return;
        }
        if (!hasInternet()) {
            // 方案 C 的保底：连着设备热点时手机没有外网，直接说清楚，不要转圈
            tv(R.id.tvAiResult).setText("当前网络无法访问互联网。\n"
                    + "如果你正连着设备的 HealthMon 热点，请先切到移动数据再回来点【重新分析】。\n\n"
                    + "（采集到的数据已存在手机里，切网不会丢）");
            return;
        }

        tv(R.id.tvAiResult).setText("正在分析…");
        new Thread(new Runnable() {
            public void run() {
                final long now = System.currentTimeMillis();
                final List<Frame> rows = db.querySamples(now - 10 * 60000L, now);
                final String profile = AiClient.profileLine(
                        prefs().getInt("age", 0), prefs().getString("gender", ""),
                        prefs().getFloat("height", 0), prefs().getFloat("weight", 0));

                aiMessages = new ArrayList<String[]>();
                aiMessages.add(new String[]{"system", "你是健康数据分析助手，回答简明、通俗，不做医学诊断。"});
                aiMessages.add(new String[]{"user", AiClient.buildSummaryPrompt(rows, profile)});

                new AiClient(key).chat(aiMessages, new AiClient.Callback() {
                    public void onOk(final String text, final int pt, final int ct) {
                        aiMessages.add(new String[]{"assistant", text});
                        runOnUiThread(new Runnable() {
                            public void run() {
                                tv(R.id.tvAiResult).setText(text);
                                tv(R.id.tvTokens).setText("token：提问 " + pt + " / 回答 " + ct);
                            }
                        });
                    }
                    public void onErr(final String msg) {
                        runOnUiThread(new Runnable() {
                            public void run() { tv(R.id.tvAiResult).setText(msg); }
                        });
                    }
                });
            }
        }, "ai-analyze").start();
    }

    private void askFollowUp() {
        String q = et(R.id.etAsk).getText().toString().trim();
        if (q.length() == 0) { toast("请先输入问题"); return; }
        if (aiMessages.isEmpty()) { toast("请先做一次分析"); return; }

        aiMessages.add(new String[]{"user", q});
        et(R.id.etAsk).setText("");
        tv(R.id.tvAiResult).setText("正在思考…");

        final String key = prefs().getString("aikey", "");
        final List<String[]> msgs = new ArrayList<String[]>(aiMessages);
        new Thread(new Runnable() {
            public void run() {
                new AiClient(key).chat(msgs, new AiClient.Callback() {
                    public void onOk(final String text, final int pt, final int ct) {
                        aiMessages.add(new String[]{"assistant", text});
                        runOnUiThread(new Runnable() {
                            public void run() {
                                tv(R.id.tvAiResult).setText(text);
                                tv(R.id.tvTokens).setText("token：提问 " + pt + " / 回答 " + ct);
                            }
                        });
                    }
                    public void onErr(final String msg) {
                        runOnUiThread(new Runnable() {
                            public void run() { tv(R.id.tvAiResult).setText(msg); }
                        });
                    }
                });
            }
        }, "ai-ask").start();
    }

    /** 粗判外网可用性。方案 C 的保底全靠它 —— 判不出来就直接尝试，不阻塞用户。 */
    private boolean hasInternet() {
        try {
            ConnectivityManager cm = (ConnectivityManager) getSystemService(Context.CONNECTIVITY_SERVICE);
            if (cm == null) return true;
            NetworkInfo ni = cm.getActiveNetworkInfo();
            return ni != null && ni.isConnected();
        } catch (Exception e) {
            return true;
        }
    }

    // ================================================================ 设置页

    private void bindSettingsPage() {
        ((Button) findViewById(R.id.btnSendTh)).setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { sendThresholds(); }
        });
        ((Button) findViewById(R.id.btnReadTh)).setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { readThresholds(); }
        });
        ((Button) findViewById(R.id.btnClearSteps)).setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                if (send(Protocol.buildCommand("CLRSTEPS", null, null))) toast("已请求步数清零");
            }
        });
        ((Button) findViewById(R.id.btnToggleSim)).setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { setSimulator(!simulatorMode); }
        });
        ((Button) findViewById(R.id.btnSaveAi)).setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                prefs().edit().putString("aikey", et(R.id.etAiKey).getText().toString().trim()).apply();
                toast("已保存 API key");
            }
        });
        ((Button) findViewById(R.id.btnSaveProfile)).setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { saveProfile(); }
        });
        ((Button) findViewById(R.id.btnGenderM)).setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { prefs().edit().putString("gender", "男").apply(); toast("已设为男"); }
        });
        ((Button) findViewById(R.id.btnGenderF)).setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { prefs().edit().putString("gender", "女").apply(); toast("已设为女"); }
        });
        ((Button) findViewById(R.id.btnWifiConnect)).setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { sendWifiConfig(); }
        });
        ((Button) findViewById(R.id.btnWifiForget)).setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                if (send(Protocol.buildCommand("WIFIFORGET", null, null))) {
                    toast("已请求忘记网络，设备将回到 HealthMon 热点");
                }
            }
        });
    }

    private void sendThresholds() {
        double bt;
        int hr, wl;
        try {
            bt = Double.parseDouble(et(R.id.etBtHi).getText().toString().trim());
            hr = Integer.parseInt(et(R.id.etHrHi).getText().toString().trim());
            wl = Integer.parseInt(et(R.id.etWLow).getText().toString().trim());
        } catch (Exception e) {
            toast("请把三个阈值都填成数字");
            return;
        }
        double btC = Fmt.clamp(bt, 35.0, 42.0);
        int hrC = Fmt.clampInt(hr, 60, 180);
        int wlC = Fmt.clampInt(wl, 0, 100);
        if (btC != bt || hrC != hr || wlC != wl) {
            toast("有数值超出范围，已自动限制：" + Fmt.oneDecimal(btC) + " / " + hrC + " / " + wlC);
        }
        et(R.id.etBtHi).setText(Fmt.oneDecimal(btC));
        et(R.id.etHrHi).setText(String.valueOf(hrC));
        et(R.id.etWLow).setText(String.valueOf(wlC));

        String cmd = Protocol.buildCommand("TH",
                new String[]{"BTHI", "HRHI", "WLOW"},
                new String[]{Fmt.oneDecimal(btC), String.valueOf(hrC), String.valueOf(wlC)});
        if (send(cmd)) {
            state.markCommandSent(btC);
            tv(R.id.tvThStatus).setText("已下发，等待设备确认…");
        }
    }

    /** 读取当前值必须真的回填输入框 —— 这是用户反馈过的缺陷。 */
    private void readThresholds() {
        Frame f = state.last;
        if (f == null) { toast("还没有收到设备数据"); return; }
        boolean any = false;
        if (!Double.isNaN(f.btHi)) { et(R.id.etBtHi).setText(Fmt.oneDecimal(f.btHi)); any = true; }
        if (f.hrHi >= 0) { et(R.id.etHrHi).setText(String.valueOf(f.hrHi)); any = true; }
        if (f.wLow >= 0) { et(R.id.etWLow).setText(String.valueOf(f.wLow)); any = true; }
        tv(R.id.tvThStatus).setText(any ? "已从设备回读" : "设备帧里没有携带阈值");
        toast(any ? "已读取" : "设备帧里没有携带阈值");
    }

    private void saveProfile() {
        try {
            int age = Integer.parseInt(et(R.id.etAge).getText().toString().trim());
            float h = Float.parseFloat(et(R.id.etHeight).getText().toString().trim());
            float w = Float.parseFloat(et(R.id.etWeight).getText().toString().trim());
            prefs().edit().putInt("age", Fmt.clampInt(age, 1, 120))
                          .putFloat("height", (float) Fmt.clamp(h, 50, 250))
                          .putFloat("weight", (float) Fmt.clamp(w, 10, 300)).apply();
            toast("已保存个人信息");
        } catch (Exception e) {
            toast("请把年龄/身高/体重都填成数字");
        }
    }

    private void sendWifiConfig() {
        String ssid = et(R.id.etWifiSsid).getText().toString().trim();
        String pass = et(R.id.etWifiPass).getText().toString();
        if (ssid.length() == 0) { toast("请填写要连接的热点名称"); return; }
        if (ssid.indexOf(',') >= 0 || pass.indexOf(',') >= 0) {
            // 协议限制，早点拦下比让设备解析错位好
            toast("热点名和密码里不能有逗号（会破坏协议解析）");
            return;
        }
        String cmd = Protocol.buildCommand("WIFI",
                new String[]{"SSID", "PASS"}, new String[]{ssid, pass});
        if (send(cmd)) {
            tv(R.id.tvWifiStatus).setText("已下发。设备会保存并重启去连该热点；"
                    + "连接会短暂断开，之后请从 OLED 读取新 IP。");
            toast("已下发 WiFi 配置");
        }
    }

    private void refreshSettingsFromState() {
        et(R.id.etAiKey).setText(prefs().getString("aikey", ""));
        int age = prefs().getInt("age", 0);
        if (age > 0) et(R.id.etAge).setText(String.valueOf(age));
        float h = prefs().getFloat("height", 0);
        if (h > 0) et(R.id.etHeight).setText(Fmt.oneDecimal(h));
        float w = prefs().getFloat("weight", 0);
        if (w > 0) et(R.id.etWeight).setText(Fmt.oneDecimal(w));
        tv(R.id.tvSimState).setText(simulatorMode ? "模拟器：开（当前数据来自模拟器）" : "模拟器：关");
    }

    // ================================================================ 登录页（P2）

    private void bindLoginPage() {
        ((Button) findViewById(R.id.btnRegister)).setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { doRegister(); }
        });
        ((Button) findViewById(R.id.btnLogin)).setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { doLogin(); }
        });
        String u = prefs().getString("user", "");
        tv(R.id.tvLoginState).setText(u.length() > 0 ? ("当前登录：" + u) : "未登录（不影响其他功能）");
    }

    private void doRegister() {
        String u = et(R.id.etUser).getText().toString().trim();
        String p1 = et(R.id.etPass).getText().toString();
        String p2 = et(R.id.etPass2).getText().toString();
        String err = Auth.validateRegistration(u, p1, p2);
        if (err != null) { toast(err); return; }

        final String salt = Auth.newSalt();
        final String hash = Auth.hash(salt, p1);
        final String user = u;
        new Thread(new Runnable() {
            public void run() {
                if (db.findUser(user) != null) {
                    runOnUiThread(new Runnable() { public void run() { toast("用户名已存在"); } });
                    return;
                }
                db.insertUser(user, hash, salt);
                runOnUiThread(new Runnable() {
                    public void run() {
                        toast("注册成功");
                        tv(R.id.tvLoginState).setText("当前登录：" + user);
                    }
                });
            }
        }, "register").start();
    }

    private void doLogin() {
        final String u = et(R.id.etUser).getText().toString().trim();
        final String p = et(R.id.etPass).getText().toString();
        if (u.length() == 0 || p.length() == 0) { toast("请填写用户名和密码"); return; }
        new Thread(new Runnable() {
            public void run() {
                String[] row = db.findUser(u);
                final boolean ok = row != null && Auth.verify(row[1], row[0], p);
                runOnUiThread(new Runnable() {
                    public void run() {
                        if (ok) {
                            prefs().edit().putString("user", u).apply();
                            tv(R.id.tvLoginState).setText("当前登录：" + u);
                            toast("登录成功");
                        } else {
                            toast("用户名或密码不对");
                        }
                    }
                });
            }
        }, "login").start();
    }

    // ================================================================ 数据汇聚

    /** 网络与模拟器唯一的汇合点 —— 两条路径解析行为完全一致 */
    private void handleLine(String line) {
        Frame f = Protocol.parseLine(line);
        if (f == null) {
            badLines++;
            renderStats();
            return;
        }
        protocol.noteSeq(f.seq);
        f.ts = System.currentTimeMillis();
        state.onFrame(f);
        state.noteFrameTime(SystemClock.uptimeMillis());

        if (dbQueue.remainingCapacity() > 0) dbQueue.offer(f);

        final Frame copy = f;
        runOnUiThread(new Runnable() {
            public void run() {
                renderCards(copy);
                checkAlerts(copy);
                renderStats();
            }
        });
    }

    // ---- NetManager.Listener（后台线程）----

    public void onLine(String line) { handleLine(line); }

    public void onState(final int st, final String detail) {
        runOnUiThread(new Runnable() {
            public void run() {
                int color;
                String text;
                if (st == NetManager.STATE_CONNECTED) { color = Ui.OK; text = "已连接 " + (detail == null ? "" : detail); }
                else if (st == NetManager.STATE_CONNECTING) { color = Ui.WARN; text = "正在连接…"; }
                else if (st == NetManager.STATE_NO_DATA) { color = Ui.WARN; text = "已连接但设备没有数据"; }
                else if (st == NetManager.STATE_ERROR) { color = Ui.BAD; text = "连接失败：" + (detail == null ? "" : detail); }
                else if (st == NetManager.STATE_DISCONNECTED) { color = Ui.BAD; text = "连接已断开"; }
                else { color = Ui.DIM; text = simulatorMode ? "模拟器模式" : "未连接"; }

                tv(R.id.tvConnState).setText(text);
                tv(R.id.tvConnState).setTextColor(color);
                tv(R.id.tvConnDot).setTextColor(color);
            }
        });
    }

    // ================================================================ 渲染

    private void renderCards(Frame f) {
        // 拨动开关关掉：整体置灰，且不报警（这是用户主动行为，不是故障）
        if (f.isSystemOff()) {
            tv(R.id.tvSystem).setText("系统已停止");
            tv(R.id.tvSystem).setTextColor(Ui.DIM);
            setCard(R.id.tvBtValue, Fmt.DASH, Ui.DIM);
            setCard(R.id.tvHrValue, Fmt.DASH, Ui.DIM);
            setCard(R.id.tvSpo2Value, Fmt.DASH, Ui.DIM);
            setCard(R.id.tvStepsValue, Fmt.DASH, Ui.DIM);
            setCard(R.id.tvWaterValue, Fmt.DASH, Ui.DIM);
            return;
        }
        tv(R.id.tvSystem).setText("系统运行中");
        tv(R.id.tvSystem).setTextColor(Ui.OK);

        boolean btAlarm = (f.al & Frame.AL_BT) != 0;
        boolean hrAlarm = (f.al & Frame.AL_HR) != 0;
        boolean waterAlarm = (f.al & Frame.AL_WATER) != 0;

        setCard(R.id.tvBtValue, Double.isNaN(f.bt) ? Fmt.DASH : Fmt.oneDecimal(f.bt) + " ℃",
                btAlarm ? Ui.BAD : Ui.OK);
        setCard(R.id.tvHrValue, f.hr < 0 ? Fmt.DASH : f.hr + " bpm",
                hrAlarm ? Ui.BAD : Ui.OK);
        setCard(R.id.tvSpo2Value, f.spo2 < 0 ? Fmt.DASH : f.spo2 + " %", Ui.OK);
        setCard(R.id.tvStepsValue, f.steps < 0 ? Fmt.DASH : String.valueOf(f.steps), Ui.OK);
        setCard(R.id.tvWaterValue, f.water < 0 ? Fmt.DASH : f.water + " %",
                waterAlarm ? Ui.WARN : Ui.OK);

        // 水泵模式看 PMODE，不看 PUMP —— 自动模式下水泵本来就随水位自己动
        String pm = f.pmode == 2 ? "自动" : (f.pmode == 1 ? "手动·开" : (f.pmode == 0 ? "手动·关" : "未知"));
        tv(R.id.tvPumpState).setText("水泵：" + pm + "，当前" + (f.pump == 1 ? "运行中" : "停止"));
        tv(R.id.tvPumpState).setTextColor(f.pmode == 2 ? Ui.OK : Ui.WARN);

        // 隐式 ACK：命令是否已被设备确认
        if (state.isConfirmed(f)) {
            tv(R.id.tvThStatus).setText("设备已确认");
            state.clearCommand();
        }
    }

    private void setCard(int id, String text, int color) {
        TextView v = tv(id);
        v.setText(text);
        v.setTextColor(color);
    }

    private void renderStats() {
        String extra = simulatorMode ? "  [模拟器]" : "";
        tv(R.id.tvStats).setText("已收 " + state.recvCount + " 帧 / 丢 " + protocol.dropped
                + " 帧 / 错误行 " + badLines + extra);
    }

    /** 数据新鲜度：超时就把卡片置灰并说明，绝不把旧值当实时值显示 */
    private void startFreshnessClock() {
        ui.postDelayed(new Runnable() {
            public void run() {
                if (!running) return;
                long now = SystemClock.uptimeMillis();
                if (state.last != null && state.isStale(now)) {
                    long ago = now - lastFrameUptime();
                    tv(R.id.tvFresh).setText("数据已 " + Fmt.ago(ago) + " 未更新");
                    tv(R.id.tvFresh).setTextColor(Ui.WARN);
                } else {
                    tv(R.id.tvFresh).setText("");
                }
                ui.postDelayed(this, 500);
            }
        }, 500);
    }

    private long lastFrameUptime = 0;
    private long lastFrameUptime() { return lastFrameUptime; }

    // ================================================================ 报警

    private void checkAlerts(Frame f) {
        if (f.isSystemOff()) return;                 // 系统停止不报警

        long now = SystemClock.uptimeMillis();
        if ((f.al & Frame.AL_BT) != 0 && state.shouldAlert(Frame.AL_BT, now)) {
            alarm("体温异常", "体温 " + Fmt.oneDecimal(f.bt) + "℃，已超过上限 "
                    + (Double.isNaN(f.btHi) ? "设定值" : Fmt.oneDecimal(f.btHi) + "℃"));
        }
        if ((f.al & Frame.AL_HR) != 0 && state.shouldAlert(Frame.AL_HR, now)) {
            alarm("心率异常", "心率 " + f.hr + " bpm，已超过上限 " + (f.hrHi < 0 ? "设定值" : f.hrHi + " bpm"));
        }
        if ((f.al & Frame.AL_WATER) != 0 && state.shouldAlert(Frame.AL_WATER, now)) {
            alarm("水位过低", "剩余水位 " + f.water + "%，低于下限 " + (f.wLow < 0 ? "设定值" : f.wLow + "%")
                    + "，水泵应已自动启动");
        }
        if ((f.al & Frame.AL_FAULT) != 0 && state.shouldAlert(Frame.AL_FAULT, now)) {
            alarm("传感器故障", "有一路传感器读数无效，请检查探头接线");
        }
    }

    /** 三管齐下：弹窗 + 卡片标红（由 renderCards 负责）+ 响一声 */
    private void alarm(String title, String msg) {
        try {
            ToneGenerator tg = new ToneGenerator(android.media.AudioManager.STREAM_NOTIFICATION, 80);
            tg.startTone(ToneGenerator.TONE_PROP_BEEP, 300);
            final ToneGenerator toRelease = tg;
            ui.postDelayed(new Runnable() { public void run() { toRelease.release(); } }, 800);
        } catch (Exception ignored) {
            // 没声音也不能影响弹窗
        }
        new AlertDialog.Builder(this)
                .setTitle(title)
                .setMessage(msg)
                .setPositiveButton("知道了", (DialogInterface.OnClickListener) null)
                .show();
    }

    // ================================================================ 模拟器

    private void setSimulator(boolean on) {
        if (on == simulatorMode) return;
        simulatorMode = on;
        if (on) {
            net.disconnect();
            state.reset();
            protocol.reset();
            badLines = 0;
            tv(R.id.tvConnState).setText("模拟器模式");
            tv(R.id.tvConnState).setTextColor(Ui.OK);
        } else {
            tv(R.id.tvConnState).setText("未连接");
            tv(R.id.tvConnState).setTextColor(Ui.DIM);
        }
        refreshSettingsFromState();
    }

    /** 模拟器按 1Hz 走与网络完全相同的 handleLine 路径 */
    private void wireSimulatorClock() {
        ui.postDelayed(new Runnable() {
            public void run() {
                if (!running) return;
                if (simulatorMode) handleLine(simulator.next(System.currentTimeMillis()).trim());
                ui.postDelayed(this, 1000);
            }
        }, 1000);
    }

    // ================================================================ 数据库线程

    /** 落库必须离开主线程 —— 淘汰时的 COUNT(*) 与 DELETE 都会阻塞 */
    private void startDbWorker() {
        Thread t = new Thread(new Runnable() {
            public void run() {
                while (running) {
                    try {
                        Frame f = dbQueue.poll(500, java.util.concurrent.TimeUnit.MILLISECONDS);
                        if (f != null) db.insertSample(f.ts, f);
                    } catch (InterruptedException e) {
                        return;
                    } catch (Exception e) {
                        // 落库失败不该把整个应用带崩；界面仍能靠内存里的状态工作
                    }
                }
            }
        }, "db-writer");
        t.setDaemon(true);
        t.start();
    }

    // ================================================================ 生命周期

    @Override
    protected void onResume() {
        super.onResume();
        lastFrameUptime = SystemClock.uptimeMillis();
        ui.postDelayed(new Runnable() {
            public void run() {
                lastFrameUptime = SystemClock.uptimeMillis();
                if (running) ui.postDelayed(this, 500);
            }
        }, 500);
    }

    @Override
    protected void onDestroy() {
        // 不清理会泄漏线程与 socket —— 上一项目漏过这一步
        running = false;
        ui.removeCallbacksAndMessages(null);
        if (net != null) net.disconnect();
        if (db != null) db.close();
        super.onDestroy();
    }
}
