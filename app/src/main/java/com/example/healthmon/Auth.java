package com.example.healthmon;

import java.security.MessageDigest;
import java.security.SecureRandom;

/**
 * 本地登录用的口令哈希。
 *
 * 纯 Java（只用 java.security）—— 所以"加盐是否真的起作用""验证函数的正反方向"
 * 这些都能本机验证，而这类函数写错的后果是"登录永远失败"或"任何密码都能进"。
 */
public final class Auth {

    private Auth() {}

    private static final String HEX = "0123456789abcdef";

    /** 生成随机盐（16 字节的十六进制表示） */
    public static String newSalt() {
        byte[] b = new byte[16];
        new SecureRandom().nextBytes(b);
        return toHex(b);
    }

    /** SHA-256(salt + password) 的十六进制表示，固定 64 个字符 */
    public static String hash(String salt, String password) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            md.update((salt == null ? "" : salt).getBytes("UTF-8"));
            md.update((password == null ? "" : password).getBytes("UTF-8"));
            return toHex(md.digest());
        } catch (Exception e) {
            // 算法一定存在；真出问题也不能返回一个"看起来能用"的值
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }

    /** 常数时间比较，避免用 == 导致的时序差异 */
    public static boolean verify(String salt, String expectedHash, String password) {
        if (expectedHash == null) return false;
        String got = hash(salt, password);
        if (got.length() != expectedHash.length()) return false;
        int diff = 0;
        for (int i = 0; i < got.length(); i++) {
            diff |= got.charAt(i) ^ expectedHash.charAt(i);
        }
        return diff == 0;
    }

    private static String toHex(byte[] b) {
        StringBuilder sb = new StringBuilder(b.length * 2);
        for (byte x : b) {
            sb.append(HEX.charAt((x >> 4) & 0xF)).append(HEX.charAt(x & 0xF));
        }
        return sb.toString();
    }

    /** 注册时的基本校验。返回 null 表示通过，否则返回给用户看的原因。 */
    public static String validateRegistration(String username, String pwd1, String pwd2) {
        if (username == null || username.trim().length() < 2) return "用户名至少 2 个字符";
        if (pwd1 == null || pwd1.length() < 4) return "密码至少 4 位";
        if (!pwd1.equals(pwd2)) return "两次输入的密码不一致";
        return null;
    }
}
