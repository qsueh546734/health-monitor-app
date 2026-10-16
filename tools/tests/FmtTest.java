import com.example.healthmon.Fmt;

/**
 * 显示格式化测试。
 *
 * 这是"界面显示 -- 还是显示 0"这条约束的落点。写错的后果很隐蔽：
 * 传感器没读到会显示成"体温 0.0°C"，比不显示更糟 —— 用户会以为真的测到了 0 度。
 *
 * 一位小数的拼接方式也必须钉住：JS 侧曾因为浮点除法吐出过 "36.6.6"。
 */
public class FmtTest {

    public static void main(String[] args) {
        oneDecimalFormat();
        dashesForInvalid();
        agoText();
        clamping();
        T.done();
    }

    static void oneDecimalFormat() {
        T.eq("fmt: 36.5", Fmt.oneDecimal(36.5), "36.5");
        T.eq("fmt: 36.0 keeps one decimal", Fmt.oneDecimal(36.0), "36.0");
        T.eq("fmt: 36.55 rounds to 36.6", Fmt.oneDecimal(36.55), "36.6");
        T.eq("fmt: 36.44 rounds to 36.4", Fmt.oneDecimal(36.44), "36.4");
        T.eq("fmt: 37.3", Fmt.oneDecimal(37.3), "37.3");
        T.eq("fmt: 0.0 is not a dash", Fmt.oneDecimal(0.0), "0.0");
        T.eq("fmt: 100.0", Fmt.oneDecimal(100.0), "100.0");
        T.eq("fmt: negative keeps sign", Fmt.oneDecimal(-1.5), "-1.5");
        // 关键性质：结果里只能有一个小数点
        int dots = 0;
        for (char c : Fmt.oneDecimal(36.66).toCharArray()) if (c == '.') dots++;
        T.eq("fmt: exactly one decimal point (catches 36.6.6 style bugs)", dots, 1);
    }

    static void dashesForInvalid() {
        T.eq("dash: NaN -> --", Fmt.oneDecimal(Double.NaN), "--");
        T.eq("dash: -1 -> --", Fmt.intOrDash(-1), "--");
        T.eq("dash: -1L -> --", Fmt.longOrDash(-1L), "--");
        T.eq("dash: 0 -> 0 (valid!)", Fmt.intOrDash(0), "0");
        T.eq("dash: 72 -> 72", Fmt.intOrDash(72), "72");
    }

    static void agoText() {
        T.eq("ago: 0s", Fmt.ago(0), "0 秒");
        T.eq("ago: 5s", Fmt.ago(5000), "5 秒");
        T.eq("ago: 59s", Fmt.ago(59000), "59 秒");
        T.eq("ago: 60s -> 1 分钟", Fmt.ago(60000), "1 分钟");
        T.eq("ago: 90s -> 1 分钟", Fmt.ago(90000), "1 分钟");
        T.eq("ago: 1h", Fmt.ago(3600000), "1 小时");
        T.eq("ago: negative clamped", Fmt.ago(-500), "0 秒");
    }

    static void clamping() {
        T.eq("clamp: below", Fmt.clamp(30.0, 35.0, 42.0), 35.0);
        T.eq("clamp: above", Fmt.clamp(50.0, 35.0, 42.0), 42.0);
        T.eq("clamp: inside", Fmt.clamp(37.0, 35.0, 42.0), 37.0);
        T.eq("clamp: NaN -> lo", Fmt.clamp(Double.NaN, 35.0, 42.0), 35.0);
        T.eq("clampInt: below", Fmt.clampInt(10, 60, 180), 60);
        T.eq("clampInt: above", Fmt.clampInt(300, 60, 180), 180);
        T.eq("clampInt: inside", Fmt.clampInt(100, 60, 180), 100);
    }
}
