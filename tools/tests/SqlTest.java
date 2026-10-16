import com.example.healthmon.DbSchema;

/**
 * 数据库结构测试。
 *
 * 真的连库需要设备，但"语句本身对不对"可以在本机秒级验证 ——
 * 而这里恰恰是错一次就要等一次设备安装的地方。
 *
 * 尤其是淘汰语句：写反了不是报错，而是把【最新】的数据删掉，
 * 界面上表现为"越用越没有历史"，非常难查。
 */
public class SqlTest {

    public static void main(String[] args) {
        samplesTableCoversAllFields();
        timestampIndexed();
        evictDeletesOldestNotNewest();
        rowCapIsSane();
        insertMatchesTable();
        usersAndSettings();
        evictCheckCadence();
        T.done();
    }

    /** 上行帧里要存的每个字段都必须有对应列，漏一个就是永久丢数据。 */
    static void samplesTableCoversAllFields() {
        String s = DbSchema.SQL_CREATE_SAMPLES;
        String[] cols = {"ts", "bt", "hr", "spo2", "steps", "water", "pump", "pmode", "sw", "al"};
        int missing = 0;
        for (String c : cols) {
            if (!s.contains(c)) missing++;
        }
        T.eq("samples: all 10 columns present", missing, 0);
        T.ok("samples: has autoincrement id", s.contains("INTEGER PRIMARY KEY AUTOINCREMENT"));
        T.ok("samples: ts is NOT NULL", s.contains("ts") && s.contains("NOT NULL"));
    }

    /** 历史查询全部按时间范围走，没有索引会随数据量线性变慢。 */
    static void timestampIndexed() {
        T.ok("index: on samples(ts)", DbSchema.SQL_CREATE_IDX_TS.contains("samples(ts)"));
        T.ok("index: is IF NOT EXISTS", DbSchema.SQL_CREATE_IDX_TS.contains("IF NOT EXISTS"));
    }

    /**
     * 淘汰必须是【删最旧】。
     * 写成 DESC 的话删掉的是最新数据 —— 不报错，只是历史永远长不起来。
     */
    static void evictDeletesOldestNotNewest() {
        String s = DbSchema.SQL_EVICT_OLDEST;
        T.ok("evict: orders by id ASC (oldest first)", s.contains("ORDER BY id ASC"));
        T.ok("evict: uses LIMIT", s.contains("LIMIT"));
        T.ok("evict: is a DELETE", s.trim().toUpperCase().startsWith("DELETE"));
        T.ok("evict: targets samples", s.contains("samples"));
        T.ok("evict: no DESC anywhere", !s.toUpperCase().contains("DESC"));
    }

    static void rowCapIsSane() {
        T.eq("cap: MAX_ROWS is 50000", DbSchema.MAX_ROWS, 50000);
        T.ok("cap: evict batch smaller than cap", DbSchema.EVICT_BATCH < DbSchema.MAX_ROWS);
        T.ok("cap: evict batch positive", DbSchema.EVICT_BATCH > 0);
    }

    /** 插入语句的列数必须与取值占位符数量一致，否则运行期才炸。 */
    static void insertMatchesTable() {
        String s = DbSchema.SQL_INSERT_SAMPLE;
        T.ok("insert: is INSERT INTO samples", s.contains("INSERT INTO samples"));
        int cols = s.substring(s.indexOf('(') + 1, s.indexOf(')')).split(",").length;
        int marks = s.substring(s.indexOf("VALUES")).split("\\?").length - 1;
        T.eq("insert: column count == placeholder count", marks, cols);
    }

    static void usersAndSettings() {
        String u = DbSchema.SQL_CREATE_USERS;
        for (String c : new String[]{"username", "pwd_hash", "salt", "age", "gender", "height", "weight"}) {
            T.ok("users: has " + c, u.contains(c));
        }
        T.ok("users: username UNIQUE", u.contains("UNIQUE"));

        String t = DbSchema.SQL_CREATE_SETTINGS;
        T.ok("settings: has key", t.contains("k "));
        T.ok("settings: has value", t.contains("v "));
        T.ok("settings: key is PRIMARY KEY", t.contains("PRIMARY KEY"));
    }

    /**
     * 淘汰检查不能每帧都做（COUNT(*) 全表扫描）。
     * 每 N 次插入查一次 —— 这条策略本身也要能被测试钉住。
     */
    static void evictCheckCadence() {
        T.eq("cadence: check every 100 inserts", DbSchema.EVICT_CHECK_EVERY, 100);
        T.ok("cadence: 1st insert checks", DbSchema.shouldCheckEvict(1));
        T.ok("cadence: 99th does not", !DbSchema.shouldCheckEvict(99));
        T.ok("cadence: 100th checks", DbSchema.shouldCheckEvict(100));
        T.ok("cadence: 101st does not", !DbSchema.shouldCheckEvict(101));
        T.ok("cadence: 200th checks", DbSchema.shouldCheckEvict(200));
        T.ok("cadence: 0 handled", !DbSchema.shouldCheckEvict(0));
    }
}
