package com.example.healthmon;

/**
 * 应用协议（设计文档 §3）的实现。
 *
 * 帧格式：$ 开头，\n 结尾，字段用 , 分隔、key=value 连接，* 后跟 2 位校验。
 * 第一版校验一律 *00（视为未启用）；非 00 时只计数不丢帧。
 */
public final class Protocol {

    /**
     * 本类同时提供【无状态的静态方法】（解析、组帧、校验）和
     * 【有状态的实例成员】（序号与丢帧统计）。
     *
     * 这样分是刻意的：解析器可以随处静态调用，而丢帧统计必须绑定到
     * 一条具体的连接上 —— 两个连接共用一个计数器会互相干扰。
     */
    public Protocol() {}

    /** 单帧长度上限（§3.2）。超过说明对端或本方有 bug。 */
    public static final int MAX_FRAME_BYTES = 128;

    // ---------------------------------------------------------------- 解析

    /**
     * 解析一行 $D 帧。不是 $D 帧、或格式不成立时返回 null。
     *
     * 注意：$I 帧（ESP 状态）不是这个方法的职责，它由 ESP 相关代码单独处理 ——
     * 两种帧的字段完全不同，混在一起会让"缺字段"的语义变模糊。
     */
    public static Frame parseLine(String line) {
        if (line == null) return null;

        String s = line.trim();
        if (s.length() < 4) return null;              // 最短也要 "$D,x=y"
        if (s.charAt(0) != '$') return null;
        if (s.charAt(1) != 'D') return null;

        // 帧中间又出现 '$' 说明流已经失步（典型场景：一个半帧没被换行终结，
        // 后面紧跟了一条新帧，两截被拼成一条）。
        // 这种线里"哪个字段属于哪一帧"已经说不清了 —— 整条拒绝。
        // 1Hz 数据下丢一帧无所谓，拿一条可能错位的帧当真值才是问题。
        if (s.indexOf('$', 1) >= 0) return null;

        // 切掉校验部分（* 之后的两位）
        int star = s.indexOf('*');
        String body = (star > 0) ? s.substring(0, star) : s;

        String[] segs = body.split(",", -1);
        if (segs.length < 2) return null;

        Frame f = new Frame();
        boolean sawAnyField = false;

        for (int i = 1; i < segs.length; i++) {
            String seg = segs[i];

            // 校验符前面就是逗号（...,AL=0,*00），所以切出来最后一段是空的。
            // 空段是正常的，跳过 —— 不能因为它把整帧否掉。
            if (seg.isEmpty()) continue;

            int eq = seg.indexOf('=');
            // 有内容却没有 '=' 才是真的残缺（半帧），整帧作废，
            // 不要拿半截数据当真值。
            if (eq <= 0) return null;

            String k = seg.substring(0, eq);
            String v = seg.substring(eq + 1);
            sawAnyField = true;

            if (k.equals("SEQ")) {
                Integer n = parseIntOrNull(v);
                if (n != null) f.seq = n;
            } else if (k.equals("BT")) {
                Double d = parseDoubleOrNull(v);
                if (d != null) f.bt = d;
            } else if (k.equals("HR")) {
                Integer n = parseIntOrNull(v);
                if (n != null) f.hr = n;
            } else if (k.equals("SPO2")) {
                Integer n = parseIntOrNull(v);
                if (n != null) f.spo2 = n;
            } else if (k.equals("STEPS")) {
                Long n = parseLongOrNull(v);
                if (n != null) f.steps = n;
            } else if (k.equals("WATER")) {
                Integer n = parseIntOrNull(v);
                if (n != null) f.water = n;
            } else if (k.equals("PUMP")) {
                Integer n = parseIntOrNull(v);
                if (n != null) f.pump = n;
            } else if (k.equals("PMODE")) {
                Integer n = parseIntOrNull(v);
                if (n != null) f.pmode = n;
            } else if (k.equals("SW")) {
                Integer n = parseIntOrNull(v);
                if (n != null) f.sw = n;
            } else if (k.equals("AL")) {
                Integer n = parseIntOrNull(v);
                if (n != null) f.al = n;
            } else if (k.equals("BTHI")) {
                Double d = parseDoubleOrNull(v);
                if (d != null) f.btHi = d;
            } else if (k.equals("HRHI")) {
                Integer n = parseIntOrNull(v);
                if (n != null) f.hrHi = n;
            } else if (k.equals("WLOW")) {
                Integer n = parseIntOrNull(v);
                if (n != null) f.wLow = n;
            }
            // 未知字段直接忽略：这样固件加字段不会把老版本 App 弄崩
        }

        if (!sawAnyField) return null;
        return f;
    }

    /**
     * 解析 "--" 或任何解析不了的值为 null。
     *
     * 这里是"无效值绝不当成 0"这条约束的落点：解析失败返回 null，
     * 调用方保持哨兵不动，而不是写进一个 0。
     */
    private static Double parseDoubleOrNull(String v) {
        if (v == null || v.isEmpty() || v.equals("--")) return null;
        try {
            return Double.valueOf(v);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static Integer parseIntOrNull(String v) {
        if (v == null || v.isEmpty() || v.equals("--")) return null;
        try {
            return Integer.valueOf(v);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static Long parseLongOrNull(String v) {
        if (v == null || v.isEmpty() || v.equals("--")) return null;
        try {
            return Long.valueOf(v);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    // ---------------------------------------------------------------- 组帧

    /**
     * 组一条下行命令帧，返回【以 \n 结尾】的完整行，可直接写进 socket。
     *
     * @param cmd  命令名，如 "TH" / "PUMP" / "QUERY"
     * @param keys 参数名，可为 null（无参数命令）
     * @param vals 参数值，长度需与 keys 一致
     */
    public static String buildCommand(String cmd, String[] keys, String[] vals) {
        StringBuilder sb = new StringBuilder();
        sb.append("$C,CMD=").append(cmd);
        if (keys != null && vals != null) {
            int n = Math.min(keys.length, vals.length);
            for (int i = 0; i < n; i++) {
                sb.append(',').append(keys[i]).append('=').append(sanitize(vals[i]));
            }
        }
        sb.append(",*").append(checksumOf(sb.toString()));
        sb.append('\n');
        return sb.toString();
    }

    /**
     * 值里不能出现 , 或 *，否则会破坏 Key=Value 解析。
     * 这两个字符在 SSID/密码里出现属于已知限制（设计文档 §3.5.1），
     * 这里做防御性替换，避免把一条会被对端解析错位的帧发出去。
     */
    static String sanitize(String v) {
        if (v == null) return "";
        return v.replace(',', '_').replace('*', '_');
    }

    // ---------------------------------------------------------------- 校验

    /**
     * 异或校验：范围是 '$' 与最后一个 '*' 之间的所有字符，取 2 位大写十六进制。
     * 第一版固件一律发 *00，所以这个值只用于"启用校验后"的比对。
     */
    public static String checksumOf(String s) {
        if (s == null) return "00";
        int from = s.indexOf('$');
        if (from < 0) from = 0;
        int to = s.lastIndexOf('*');
        if (to < 0 || to < from) to = s.length();
        int x = 0;
        for (int i = from; i < to; i++) {
            x ^= (s.charAt(i) & 0xFF);
        }
        String hex = Integer.toHexString(x & 0xFF).toUpperCase();
        return hex.length() == 1 ? "0" + hex : hex;
    }

    // ---------------------------------------------------------------- 序号统计

    /**
     * 判定为"设备复位"的前进距离阈值。
     *
     * 回绕计数器天然有歧义：`(seq - last) & 0xFFFF` 把「前进 62537 帧」和
     * 「后退 2995 帧」算成同一个数 —— 两者无法从数值上区分。
     *
     * 按实用原则划线：
     *   - 正常丢帧的前进距离是几十到几百（1Hz 下几分钟）
     *   - 设备复位会让序号跳到一个很小的值，前进距离接近 65536
     *   - 阈值取 3000（1Hz 下约 50 分钟）：超过它就当作复位重新同步
     *
     * 代价：真的断了 50 分钟以上会被当成复位、不报丢帧数。
     * 但那种情况下报"丢 62536 帧"本来也没有意义，报出来反而像 bug
     * （上一项目就出现过界面上写着"丢 65277 帧"的荒唐数据）。
     */
    private static final int MAX_PLAUSIBLE_GAP = 3000;

    private int lastSeq = -1;
    private boolean synced = false;

    /** 丢帧计数，供界面显示 */
    public int dropped = 0;

    /** 收到的重复帧计数（诊断用） */
    public int duplicates = 0;

    /** 检测到的设备复位次数（诊断用） */
    public int resyncs = 0;

    /**
     * 记录一帧的序号并更新丢帧统计。
     *
     * 四种情况必须分清，顺序不能换：
     *   1. 首帧                 -> 只记，不计
     *   2. 与上一帧相同         -> 重复，不计（重发很常见）
     *   3. 正好 +1（含 65535->0 回绕）-> 正常，不计
     *   4. 大幅回退             -> 设备复位，重新同步，不计
     *   其余                    -> 真的丢了，差值累加
     *
     * 第 4 条是上一项目踩过的坑：设备复位后序号从 3000 掉回 5，
     * 被当成"丢了 65277 帧"，界面上出现了荒唐的假数据。
     */
    public void noteSeq(int seq) {
        if (!synced) {
            lastSeq = seq;
            synced = true;
            return;
        }
        if (seq == lastSeq) {
            duplicates++;
            return;
        }
        int forward = (seq - lastSeq) & 0xFFFF;
        if (forward == 1) {
            lastSeq = seq;
            return;
        }
        if (forward == 0xFFFF) {          // 后退一帧：设备重发上一帧，当重复处理
            duplicates++;
            return;
        }
        if (forward > MAX_PLAUSIBLE_GAP) {
            // 前进距离大到不可能：那是设备复位后序号跳回小值，重新同步
            resyncs++;
            lastSeq = seq;
            return;
        }
        dropped += forward - 1;
        lastSeq = seq;
    }

    public void reset() {
        lastSeq = -1;
        synced = false;
        dropped = 0;
        duplicates = 0;
        resyncs = 0;
    }
}
