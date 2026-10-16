package com.example.healthmon;

/**
 * 把字节流切成完整行。
 *
 * TCP 和串口一样是【字节流】，没有消息边界：一次 read() 可能拿到半帧、
 * 也可能拿到两帧半。所以必须累积后按 '\n' 切分 —— 直接对每次 read 的内容
 * 调 split("\n") 是这类程序最经典的错。
 *
 * 用 StringBuilder 而不是字节数组：协议是 ASCII 文本，逐字节转 char 没有编码问题。
 */
public final class LineAssembler {

    /** 缓冲上限。超过就丢掉最旧的部分，避免对端狂发不带换行的数据把内存吃光。 */
    public static final int MAX_BUFFERED = 4096;

    private final StringBuilder sb = new StringBuilder();

    /** 喂入一段字节。不返回内容，取行统一走 poll()。 */
    public void feed(byte[] buf, int len) {
        if (buf == null || len <= 0) return;
        for (int i = 0; i < len; i++) {
            char c = (char) (buf[i] & 0xFF);
            if (c == '\n') {
                sb.append('\n');
            } else if (c != '\r') {
                // 容错剥离 \r：协议规定只用 \n，但对端可能是 Windows 工具发的 \r\n
                sb.append(c);
            }
            if (sb.length() > MAX_BUFFERED) {
                sb.delete(0, sb.length() - MAX_BUFFERED);
            }
        }
    }

    public void feed(String s) {
        feed(s.getBytes(), s.getBytes().length);
    }

    /** 取出下一整行（不含换行符）。没有完整行就返回 null。 */
    public String poll() {
        int i = sb.indexOf("\n");
        if (i < 0) return null;
        String line = sb.substring(0, i);
        sb.delete(0, i + 1);
        return line;
    }

    /** 当前缓冲了多少字符，用于测试与诊断 */
    public int buffered() {
        return sb.length();
    }

    public void clear() {
        sb.setLength(0);
    }
}
