import com.example.healthmon.Auth;

/**
 * 口令哈希测试。
 *
 * 这类函数写错的两个极端都很糟：
 *   - 不可重现 -> 登录永远失败
 *   - 盐没起作用 / 比较写反 -> 任何密码都能进
 */
public class AuthTest {

    public static void main(String[] args) {
        deterministic();
        saltMatters();
        hashShape();
        verifyBothDirections();
        saltIsRandom();
        registrationValidation();
        T.done();
    }

    /** 同样输入必须得到同样哈希，否则登录永远失败 */
    static void deterministic() {
        T.eq("auth: deterministic", Auth.hash("abc123", "pw"), Auth.hash("abc123", "pw"));
    }

    /** 同一口令配不同盐必须得到不同哈希 —— 否则加盐毫无意义 */
    static void saltMatters() {
        T.ok("auth: salt changes hash", !Auth.hash("saltA", "pw").equals(Auth.hash("saltB", "pw")));
        T.ok("auth: password changes hash", !Auth.hash("s", "pw1").equals(Auth.hash("s", "pw2")));
    }

    static void hashShape() {
        String h = Auth.hash("s", "pw");
        T.eq("auth: sha-256 hex length 64", h.length(), 64);
        T.ok("auth: lowercase hex only", h.matches("[0-9a-f]{64}"));
    }

    static void verifyBothDirections() {
        String salt = Auth.newSalt();
        String h = Auth.hash(salt, "correct horse");
        T.ok("auth: verify accepts correct", Auth.verify(salt, h, "correct horse"));
        T.ok("auth: verify rejects wrong", !Auth.verify(salt, h, "wrong"));
        T.ok("auth: verify rejects null hash", !Auth.verify(salt, null, "correct horse"));
        T.ok("auth: verify rejects different salt", !Auth.verify(Auth.newSalt(), h, "correct horse"));
        T.ok("auth: case matters", !Auth.verify(salt, h, "Correct horse"));
    }

    static void saltIsRandom() {
        String a = Auth.newSalt(), b = Auth.newSalt();
        T.ok("auth: salts differ", !a.equals(b));
        T.eq("auth: salt is 32 hex chars", a.length(), 32);
    }

    static void registrationValidation() {
        T.ok("reg: ok", Auth.validateRegistration("ab", "1234", "1234") == null);
        T.ok("reg: short username rejected", Auth.validateRegistration("a", "1234", "1234") != null);
        T.ok("reg: null username rejected", Auth.validateRegistration(null, "1234", "1234") != null);
        T.ok("reg: short password rejected", Auth.validateRegistration("ab", "123", "123") != null);
        T.ok("reg: mismatch rejected", Auth.validateRegistration("ab", "1234", "12345") != null);
    }
}
