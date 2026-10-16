import com.example.healthmon.LineAssembler;

/**
 * 分帧测试。
 *
 * TCP 是字节流，没有消息边界。这组用例覆盖三种真实会发生的切分：
 * 半帧到达、两帧粘在一次读里、以及"半帧超时后紧跟新帧"。
 */
public class LineAssemblerTest {

    public static void main(String[] args) {
        halfFrameThenRest();
        twoFramesInOneRead();
        crlfTolerated();
        halfFrameThenNewFrame();
        boundedBuffer();
        T.done();
    }

    /** 半帧 -> 无输出；补全后才出一行。这是最基本的一条。 */
    static void halfFrameThenRest() {
        LineAssembler a = new LineAssembler();
        a.feed("$D,SEQ=12,BT=36");
        T.ok("half: nothing until newline", a.poll() == null);
        T.ok("half: buffer holds partial", a.buffered() > 0);

        a.feed(".5,SW=1,AL=0,*00\n");
        T.eq("half: complete line", a.poll(), "$D,SEQ=12,BT=36.5,SW=1,AL=0,*00");
        T.ok("half: nothing left", a.poll() == null);
    }

    /** 两帧粘在一次读里 -> 两条都要能取出来，顺序不能乱。 */
    static void twoFramesInOneRead() {
        LineAssembler b = new LineAssembler();
        b.feed("$D,SEQ=1,SW=1,*00\n$D,SEQ=2,SW=1,*00\n");
        T.eq("sticky: first", b.poll(), "$D,SEQ=1,SW=1,*00");
        T.eq("sticky: second", b.poll(), "$D,SEQ=2,SW=1,*00");
        T.ok("sticky: exhausted", b.poll() == null);
    }

    /** 对端发 \r\n 时要容错剥离 \r，否则字段尾部会多一个不可见字符。 */
    static void crlfTolerated() {
        LineAssembler c = new LineAssembler();
        c.feed("$D,SEQ=3,SW=1,*00\r\n");
        T.eq("crlf: stripped", c.poll(), "$D,SEQ=3,SW=1,*00");
    }

    /**
     * 半帧超时后紧跟一条新帧。
     *
     * 这是最容易出错的场景：如果不能识别前半截已经作废，
     * 会把 "$D,SEQ=12,BT=36" 和 "$D,SEQ=13,..." 拼成一条垃圾记录。
     * 本实现的取舍是"按换行切分"，所以第一条半帧会与后一帧拼在一起 ——
     * 但拼接结果必然以 '$' 开头且含 '='，会被 Protocol 按"字段无 = "拒绝，
     * 不会变成一条看似有效的记录。这里就把这个性质钉住。
     */
    static void halfFrameThenNewFrame() {
        LineAssembler d = new LineAssembler();
        d.feed("$D,SEQ=12,BT=36");                       // 半帧，没有换行
        d.feed("$D,SEQ=13,BT=36.6,SW=1,*00\n");          // 新帧到达

        String line = d.poll();
        T.ok("mixed: produced exactly one line", line != null);
        T.ok("mixed: nothing after it", d.poll() == null);

        // 关键性质：拼出来的东西不会被真正的解析器当成有效帧
        boolean parsed = com.example.healthmon.Protocol.parseLine(line) != null;
        T.ok("mixed: merged garbage is NOT accepted as a valid frame", !parsed);
    }

    /** 缓冲区必须有上限，否则对端狂发不带换行的数据会把内存吃光。 */
    static void boundedBuffer() {
        LineAssembler e = new LineAssembler();
        StringBuilder big = new StringBuilder();
        for (int i = 0; i < 10000; i++) big.append('x');
        e.feed(big.toString());
        T.ok("bounded: buffer capped", e.buffered() <= LineAssembler.MAX_BUFFERED);
    }
}
