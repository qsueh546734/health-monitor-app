package com.example.healthmon;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.List;

/**
 * DeepSeek API 客户端。
 *
 * 【为什么是纯 Java】它只用 java.net 和字符串处理，不 import android.*。
 * 于是最容易出错的两件事能在本机验证：
 *   1. 上下文怎么构造（发太多原始数据会烧 token，发缺字段进去 AI 会瞎猜）
 *   2. JSON 怎么转义（零依赖意味着要手写转义，引号和换行漏了就会 400）
 *
 * 【API key 的存放】存在 App 里，反编译可读。课程项目可接受，
 * 生产环境必须改成服务端中转 —— 这一点写进设计文档了。
 */
public final class AiClient {

    public static final String ENDPOINT = "https://api.deepseek.com/chat/completions";
    public static final String MODEL = "deepseek-chat";

    private static final int CONNECT_TIMEOUT_MS = 15000;
    private static final int READ_TIMEOUT_MS = 30000;

    public interface Callback {
        void onOk(String text, int promptTokens, int completionTokens);
        void onErr(String humanMessage);
    }

    /** messages 里每项是 {role, content}，role 取 "system" / "user" / "assistant" */
    public interface HttpPost {
        String post(String url, String jsonBody, String apiKey) throws Exception;
    }

    private final String apiKey;

    public AiClient(String apiKey) {
        this.apiKey = apiKey == null ? "" : apiKey.trim();
    }

    public boolean hasKey() {
        return apiKey.length() > 0;
    }

    /** 在后台线程调用；回调也在后台线程，调用方负责切主线程。 */
    public void chat(List<String[]> messages, Callback cb) {
        chat(messages, cb, new HttpPost() {
            public String post(String url, String jsonBody, String key) throws Exception {
                return httpPost(url, jsonBody, key);
            }
        });
    }

    /** 可注入的版本，便于本机测试解析与错误分支而不真的发请求 */
    public void chat(List<String[]> messages, Callback cb, HttpPost http) {
        if (!hasKey()) {
            cb.onErr("还没有填写 DeepSeek API key，请到设置页填写");
            return;
        }
        String body = buildRequestBody(messages);
        try {
            String resp = http.post(ENDPOINT, body, apiKey);
            String text = extractContent(resp);
            if (text == null) {
                cb.onErr(describeHttpProblem(resp));
                return;
            }
            cb.onOk(text, extractInt(resp, "prompt_tokens"), extractInt(resp, "completion_tokens"));
        } catch (Exception e) {
            String m = e.getMessage();
            cb.onErr(m == null ? "网络错误" : ("网络错误：" + m));
        }
    }

    // ---------------------------------------------------------------- 请求体

    public static String buildRequestBody(List<String[]> messages) {
        StringBuilder sb = new StringBuilder();
        sb.append("{\"model\":\"").append(MODEL).append("\",\"messages\":[");
        for (int i = 0; i < messages.size(); i++) {
            String[] m = messages.get(i);
            if (i > 0) sb.append(',');
            sb.append("{\"role\":\"").append(escape(m[0]))
              .append("\",\"content\":\"").append(escape(m[1])).append("\"}");
        }
        sb.append("],\"stream\":false}");
        return sb.toString();
    }

    /**
     * JSON 字符串转义。零依赖就得手写，而漏掉任何一条都会让请求 400。
     * 中文不需要转义（UTF-8 原样发送）。
     */
    public static String escape(String s) {
        if (s == null) return "";
        StringBuilder sb = new StringBuilder(s.length() + 16);
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"':  sb.append("\\\""); break;
                case '\\': sb.append("\\\\"); break;
                case '\n': sb.append("\\n"); break;
                case '\r': sb.append("\\r"); break;
                case '\t': sb.append("\\t"); break;
                case '\b': sb.append("\\b"); break;
                case '\f': sb.append("\\f"); break;
                default:
                    if (c < 0x20) {
                        sb.append(String.format("\\u%04x", (int) c));
                    } else {
                        sb.append(c);
                    }
            }
        }
        return sb.toString();
    }

    // ---------------------------------------------------------------- 上下文构造

    /**
     * 把一段采样压成统计摘要。
     *
     * 【为什么不是把原始数据全发过去】600 条原始数据就是几万 token，
     * 既贵又没必要 —— AI 要的是趋势和异常，不是每条记录。
     * 摘要还能避免把 NaN / -1 这类哨兵值发给模型（它会把 -1 当成真的负一度）。
     */
    public static String buildSummaryPrompt(List<Frame> rows, String profile) {
        StringBuilder sb = new StringBuilder();
        sb.append("你是健康数据分析助手。请根据下面的数据给出简明、易懂的建议。\n");
        sb.append("重要：不要做医学诊断，也不要下结论说用户有病；只提示需要关注的地方。\n\n");

        if (profile != null && profile.length() > 0) {
            sb.append("用户信息：").append(profile).append('\n');
        }

        if (rows == null || rows.isEmpty()) {
            sb.append("\n（本次没有采集到数据，请直接说明需要先连接设备采集数据。）\n");
            sb.append("\n请用中文回答，控制在 150 字以内。");
            return sb.toString();
        }

        // 逐项统计，跳过缺失值（绝不让 NaN / -1 进提示词）
        double btMin = Double.NaN, btMax = Double.NaN, btSum = 0;
        int btN = 0;
        int hrMin = Integer.MAX_VALUE, hrMax = Integer.MIN_VALUE, hrSum = 0, hrN = 0;
        int wMin = Integer.MAX_VALUE, wMax = Integer.MIN_VALUE;
        long stepsFirst = -1, stepsLast = -1;
        int alerts = 0;

        for (Frame f : rows) {
            if (!Double.isNaN(f.bt)) {
                if (Double.isNaN(btMin) || f.bt < btMin) btMin = f.bt;
                if (Double.isNaN(btMax) || f.bt > btMax) btMax = f.bt;
                btSum += f.bt; btN++;
            }
            if (f.hr >= 0) {
                if (f.hr < hrMin) hrMin = f.hr;
                if (f.hr > hrMax) hrMax = f.hr;
                hrSum += f.hr; hrN++;
            }
            if (f.water >= 0) {
                if (f.water < wMin) wMin = f.water;
                if (f.water > wMax) wMax = f.water;
            }
            if (f.steps >= 0) {
                if (stepsFirst < 0) stepsFirst = f.steps;
                stepsLast = f.steps;
            }
            if (f.al != 0) alerts++;
        }

        sb.append("\n数据摘要（最近 ").append(rows.size()).append(" 个采样点）：\n");
        if (btN > 0) {
            sb.append("- 体温：").append(Fmt.oneDecimal(btMin)).append(" ~ ").append(Fmt.oneDecimal(btMax))
              .append(" ℃，平均 ").append(Fmt.oneDecimal(btSum / btN)).append(" ℃\n");
        } else {
            sb.append("- 体温：本段没有有效数据\n");
        }
        if (hrN > 0) {
            sb.append("- 心率：").append(hrMin).append(" ~ ").append(hrMax)
              .append(" bpm，平均 ").append(hrSum / hrN).append(" bpm\n");
        } else {
            sb.append("- 心率：本段没有有效数据\n");
        }
        if (wMin != Integer.MAX_VALUE) {
            sb.append("- 水位：").append(wMin).append("% ~ ").append(wMax).append("%\n");
        }
        if (stepsFirst >= 0 && stepsLast >= stepsFirst) {
            sb.append("- 步数：本段增加 ").append(stepsLast - stepsFirst).append(" 步\n");
        }
        sb.append("- 报警次数：").append(alerts).append(" 次\n");

        sb.append("\n请用中文回答，控制在 200 字以内，分点给出关注建议。");
        return sb.toString();
    }

    /** 把界面上的用户信息拼成一行 */
    public static String profileLine(int age, String gender, double height, double weight) {
        List<String> parts = new ArrayList<String>();
        if (age > 0) parts.add(age + " 岁");
        if (gender != null && gender.length() > 0) parts.add(gender);
        if (height > 0) parts.add(Fmt.oneDecimal(height) + " cm");
        if (weight > 0) parts.add(Fmt.oneDecimal(weight) + " kg");
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < parts.size(); i++) {
            if (i > 0) sb.append(" / ");
            sb.append(parts.get(i));
        }
        return sb.toString();
    }

    // ---------------------------------------------------------------- 响应解析

    /**
     * 从响应里抠出正文。零依赖没有 JSON 库，手写解析。
     * 找 "content":" 之后按转义规则回读 —— 比正则可靠，因为正文里可能含引号。
     */
    public static String extractContent(String json) {
        if (json == null) return null;
        int key = json.indexOf("\"content\"");
        if (key < 0) return null;
        int colon = json.indexOf(':', key);
        if (colon < 0) return null;
        int q = json.indexOf('"', colon);
        if (q < 0) return null;

        StringBuilder sb = new StringBuilder();
        for (int i = q + 1; i < json.length(); i++) {
            char c = json.charAt(i);
            if (c == '\\') {
                if (i + 1 >= json.length()) break;
                char n = json.charAt(++i);
                switch (n) {
                    case 'n': sb.append('\n'); break;
                    case 'r': sb.append('\r'); break;
                    case 't': sb.append('\t'); break;
                    case 'b': sb.append('\b'); break;
                    case 'f': sb.append('\f'); break;
                    case 'u':
                        if (i + 4 < json.length()) {
                            try {
                                sb.append((char) Integer.parseInt(json.substring(i + 1, i + 5), 16));
                                i += 4;
                            } catch (NumberFormatException ignored) { }
                        }
                        break;
                    default: sb.append(n);   // \" 和 \\ 都落在这里
                }
            } else if (c == '"') {
                return sb.toString();
            } else {
                sb.append(c);
            }
        }
        return null;
    }

    /** 从 {"prompt_tokens":123} 里取整数 */
    public static int extractInt(String json, String field) {
        if (json == null) return 0;
        int k = json.indexOf("\"" + field + "\"");
        if (k < 0) return 0;
        int colon = json.indexOf(':', k);
        if (colon < 0) return 0;
        int i = colon + 1;
        while (i < json.length() && !Character.isDigit(json.charAt(i)) && json.charAt(i) != '-') i++;
        int start = i;
        while (i < json.length() && (Character.isDigit(json.charAt(i)) || json.charAt(i) == '-')) i++;
        if (i <= start) return 0;
        try {
            return Integer.parseInt(json.substring(start, i));
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    /** 把 HTTP 层的失败翻译成人话 —— 用户看不懂 401/402 */
    public static String describeHttpProblem(String body) {
        if (body == null || body.length() == 0) return "服务没有返回内容，请稍后重试";
        if (body.contains("401") || body.indexOf("Authentication") >= 0) return "API key 无效，请到设置页检查";
        if (body.contains("402") || body.indexOf("Insufficient Balance") >= 0) return "DeepSeek 账户余额不足，请充值后再试";
        if (body.contains("429")) return "请求太频繁或额度用尽，请稍后再试";
        if (body.contains("timeout") || body.contains("Timeout")) return "请求超时，请检查网络";
        String s = body.length() > 160 ? body.substring(0, 160) : body;
        return "调用失败：" + s;
    }

    // ---------------------------------------------------------------- HTTP

    private static String httpPost(String urlStr, String jsonBody, String key) throws Exception {
        HttpURLConnection conn = null;
        try {
            conn = (HttpURLConnection) new URL(urlStr).openConnection();
            conn.setRequestMethod("POST");
            conn.setConnectTimeout(CONNECT_TIMEOUT_MS);
            conn.setReadTimeout(READ_TIMEOUT_MS);
            conn.setDoOutput(true);
            conn.setRequestProperty("Content-Type", "application/json; charset=utf-8");
            conn.setRequestProperty("Authorization", "Bearer " + key);

            byte[] payload = jsonBody.getBytes("UTF-8");
            conn.setFixedLengthStreamingMode(payload.length);
            OutputStream os = conn.getOutputStream();
            os.write(payload);
            os.flush();
            os.close();

            int code = conn.getResponseCode();
            InputStream in = (code >= 200 && code < 300) ? conn.getInputStream() : conn.getErrorStream();
            String body = readAll(in);
            if (code < 200 || code >= 300) {
                throw new IllegalStateException(describeHttpProblem(code + " " + body));
            }
            return body;
        } finally {
            if (conn != null) conn.disconnect();
        }
    }

    private static String readAll(InputStream in) throws Exception {
        if (in == null) return "";
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        byte[] buf = new byte[4096];
        int n;
        while ((n = in.read(buf)) > 0) bos.write(buf, 0, n);
        in.close();
        return new String(bos.toByteArray(), "UTF-8");
    }
}
