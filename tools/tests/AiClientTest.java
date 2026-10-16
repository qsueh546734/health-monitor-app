import com.example.healthmon.AiClient;
import com.example.healthmon.Frame;

import java.util.ArrayList;
import java.util.List;

/**
 * DeepSeek 客户端测试（纯 Java，不发真请求）。
 *
 * 这类代码有两处特别容易错，而且错了都要在真机上才发现：
 *   1. 上下文构造 —— 把 600 条原始数据全发过去会烧 token；
 *      把哨兵值（NaN / -1）发过去，AI 会把 -1 当成"真的负一度"来推理
 *   2. JSON 转义 —— 零依赖要手写，漏掉引号或换行就是 400
 */
public class AiClientTest {

    public static void main(String[] args) {
        summaryIsCompact();
        noSentinelsLeak();
        emptyDataSafe();
        jsonEscape();
        requestBodyShape();
        contentExtraction();
        contentWithEscapes();
        tokenExtraction();
        errorMessages();
        profileLine();
        T.done();
    }

    static List<Frame> series(int n) {
        List<Frame> rows = new ArrayList<Frame>();
        for (int i = 0; i < n; i++) {
            Frame f = new Frame();
            f.ts = i * 1000L;
            f.bt = 36.5 + (i % 5) * 0.1;
            f.hr = 70 + (i % 30);
            f.water = 60 - (i % 20);
            f.steps = 1000 + i * 2;
            f.al = (i == 7) ? 1 : 0;
            rows.add(f);
        }
        return rows;
    }

    /** 摘要必须紧凑：不能把原始逐条数据塞进去 */
    static void summaryIsCompact() {
        String p = AiClient.buildSummaryPrompt(series(600), "男 / 28 岁 / 175.0 cm / 68.0 kg");
        T.ok("prompt: has profile", p.indexOf("28 岁") >= 0);
        T.ok("prompt: has temperature range", p.indexOf("体温") >= 0);
        T.ok("prompt: has heart rate", p.indexOf("心率") >= 0);
        T.ok("prompt: has alert count", p.indexOf("报警次数") >= 0);
        T.ok("prompt: says how many samples", p.indexOf("600") >= 0);
        T.ok("prompt: compact (< 1200 chars)", p.length() < 1200);
        T.ok("prompt: no per-sample dump", p.indexOf("36.5,36.6") < 0);
        T.ok("prompt: tells model not to diagnose", p.indexOf("不要做医学诊断") >= 0);
    }

    /** 缺字段的帧绝不能把 NaN / -1 泄漏进提示词 */
    static void noSentinelsLeak() {
        List<Frame> rows = new ArrayList<Frame>();
        Frame empty = new Frame();          // 全哨兵：bt=NaN, hr=-1, water=-1
        rows.add(empty);
        Frame ok = new Frame();
        ok.bt = 36.6; ok.hr = 80; ok.water = 55; ok.steps = 10;
        rows.add(ok);

        String p = AiClient.buildSummaryPrompt(rows, "");
        T.ok("sentinel: no 'NaN' in prompt", p.indexOf("NaN") < 0);
        T.ok("sentinel: no '-1' in prompt", p.indexOf("-1") < 0);
        T.ok("sentinel: still mentions valid temp", p.indexOf("36.6") >= 0);
        T.ok("sentinel: says heart rate has only partial data", p.indexOf("心率") >= 0);
    }

    static void emptyDataSafe() {
        String p = AiClient.buildSummaryPrompt(new ArrayList<Frame>(), "男");
        T.ok("empty: no exception, non-empty prompt", p != null && p.length() > 0);
        T.ok("empty: asks to collect first", p.indexOf("没有采集到数据") >= 0);
        String q = AiClient.buildSummaryPrompt(null, null);
        T.ok("null: also safe", q != null && q.length() > 0);
    }

    /** 零依赖手写 JSON 转义，漏一条就会 400 */
    static void jsonEscape() {
        T.eq("escape: quote", AiClient.escape("a\"b"), "a\\\"b");
        T.eq("escape: backslash", AiClient.escape("a\\b"), "a\\\\b");
        T.eq("escape: newline", AiClient.escape("a\nb"), "a\\nb");
        T.eq("escape: tab", AiClient.escape("a\tb"), "a\\tb");
        T.eq("escape: CR", AiClient.escape("a\rb"), "a\\rb");
        T.eq("escape: control char", AiClient.escape("a\u0001b"), "a\\u0001b");
        T.eq("escape: Chinese untouched", AiClient.escape("体温 36.5"), "体温 36.5");
        T.eq("escape: null -> empty", AiClient.escape(null), "");
    }

    static void requestBodyShape() {
        List<String[]> msgs = new ArrayList<String[]>();
        msgs.add(new String[]{"system", "你是助手"});
        msgs.add(new String[]{"user", "他说的\"原话\"\n换行"});
        String b = AiClient.buildRequestBody(msgs);
        T.ok("body: has model", b.indexOf("\"model\":\"deepseek-chat\"") >= 0);
        T.ok("body: has role system", b.indexOf("\"role\":\"system\"") >= 0);
        T.ok("body: has role user", b.indexOf("\"role\":\"user\"") >= 0);
        T.ok("body: quote escaped", b.indexOf("\\\"原话\\\"") >= 0);
        T.ok("body: newline escaped", b.indexOf("\\n换行") >= 0);
        T.ok("body: no raw newline inside string", b.indexOf("\n") < 0);
        T.ok("body: stream false", b.indexOf("\"stream\":false") >= 0);
        T.ok("body: braces balanced", count(b, '{') == count(b, '}'));
    }

    static void contentExtraction() {
        String r = "{\"choices\":[{\"message\":{\"role\":\"assistant\",\"content\":\"你好，数据正常。\"}}]}";
        T.eq("extract: simple", AiClient.extractContent(r), "你好，数据正常。");
        T.ok("extract: missing content -> null", AiClient.extractContent("{\"error\":\"x\"}") == null);
        T.ok("extract: null -> null", AiClient.extractContent(null) == null);
    }

    static void contentWithEscapes() {
        String r = "{\"content\":\"第一行\\n第二行 \\\"引号\\\" 结束\"}";
        T.eq("extract: escapes decoded", AiClient.extractContent(r), "第一行\n第二行 \"引号\" 结束");
        String u = "{\"content\":\"\\u4f60\\u597d\"}";
        T.eq("extract: unicode decoded", AiClient.extractContent(u), "你好");
    }

    static void tokenExtraction() {
        String r = "{\"usage\":{\"prompt_tokens\":123,\"completion_tokens\":45},\"content\":\"x\"}";
        T.eq("tokens: prompt", AiClient.extractInt(r, "prompt_tokens"), 123);
        T.eq("tokens: completion", AiClient.extractInt(r, "completion_tokens"), 45);
        T.eq("tokens: missing -> 0", AiClient.extractInt("{}", "prompt_tokens"), 0);
        T.eq("tokens: null -> 0", AiClient.extractInt(null, "x"), 0);
    }

    /** 用户看不懂 401/402，必须翻译成人话 */
    static void errorMessages() {
        T.ok("err: 401 -> key 无效", AiClient.describeHttpProblem("401 Unauthorized").indexOf("key") >= 0);
        T.ok("err: 402 -> 余额不足", AiClient.describeHttpProblem("402 Insufficient Balance").indexOf("余额") >= 0);
        T.ok("err: 429 -> 太频繁", AiClient.describeHttpProblem("429 Too Many Requests").indexOf("频繁") >= 0);
        T.ok("err: empty -> 提示重试", AiClient.describeHttpProblem("").indexOf("重试") >= 0);
        T.ok("err: unknown carries detail", AiClient.describeHttpProblem("500 boom").indexOf("500") >= 0);
        T.ok("err: long body truncated", AiClient.describeHttpProblem(rep('x', 900)).length() < 260);
    }

    static void profileLine() {
        T.eq("profile: full", AiClient.profileLine(28, "男", 175, 68), "28 岁 / 男 / 175.0 cm / 68.0 kg");
        T.eq("profile: age only", AiClient.profileLine(28, "", 0, 0), "28 岁");
        T.eq("profile: empty", AiClient.profileLine(0, null, 0, 0), "");
    }

    static String rep(char c, int n) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < n; i++) sb.append(c);
        return sb.toString();
    }

    static int count(String s, char c) {
        int n = 0;
        for (int i = 0; i < s.length(); i++) if (s.charAt(i) == c) n++;
        return n;
    }
}
