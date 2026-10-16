package com.example.healthmon;

/**
 * 数据库结构：表、索引、淘汰策略。
 *
 * 【为什么单独拆出来】真的连库需要设备（一次改动就要等一次安装），
 * 但"语句本身对不对"在本机一秒就能验。把语句集中在这个不 import android.*
 * 的类里，SqlTest 就能覆盖它们 —— 否则这层验证根本不存在。
 *
 * 这里错一次的代价特别高：淘汰语句写成 DESC 不是报错，而是把【最新】数据删掉，
 * 界面上表现为"越用历史越少"，极难查。
 */
public final class DbSchema {

    private DbSchema() {}

    public static final String DB_NAME = "healthmon.db";
    public static final int DB_VERSION = 1;

    /** 采样表行数上限。1Hz 下 50000 行约等于 13.9 小时。 */
    public static final int MAX_ROWS = 50000;

    /** 一次淘汰删多少行 —— 删太少会频繁触发，删太多会让单次插入变慢。 */
    public static final int EVICT_BATCH = 1000;

    /** 每多少次插入做一次行数检查。COUNT(*) 是全表扫描，不能每帧都做。 */
    public static final int EVICT_CHECK_EVERY = 100;

    public static final String T_SAMPLES = "samples";
    public static final String T_USERS = "users";
    public static final String T_SETTINGS = "settings";

    /** 实时采样。字段与设计文档 §3.3 的上行帧一一对应。 */
    public static final String SQL_CREATE_SAMPLES =
        "CREATE TABLE IF NOT EXISTS samples (" +
        "  id     INTEGER PRIMARY KEY AUTOINCREMENT," +
        "  ts     INTEGER NOT NULL," +   // 毫秒时间戳，历史查询全靠它
        "  bt     REAL," +               // 体温（可空：缺字段或传感器无效）
        "  hr     INTEGER," +
        "  spo2   INTEGER," +
        "  steps  INTEGER," +
        "  water  INTEGER," +
        "  pump   INTEGER," +
        "  pmode  INTEGER," +
        "  sw     INTEGER," +
        "  al     INTEGER" +
        ")";

    public static final String SQL_CREATE_IDX_TS =
        "CREATE INDEX IF NOT EXISTS idx_samples_ts ON samples(ts)";

    /** 上限淘汰：删最旧的 EVICT_BATCH 行。ORDER BY id ASC 是这里的命门。 */
    public static final String SQL_EVICT_OLDEST =
        "DELETE FROM samples WHERE id IN (" +
        "  SELECT id FROM samples ORDER BY id ASC LIMIT " + EVICT_BATCH +
        ")";

    public static final String SQL_INSERT_SAMPLE =
        "INSERT INTO samples (ts, bt, hr, spo2, steps, water, pump, pmode, sw, al) " +
        "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)";

    public static final String SQL_SELECT_RANGE =
        "SELECT id, ts, bt, hr, spo2, steps, water, pump, pmode, sw, al FROM samples " +
        "WHERE ts >= ? AND ts <= ? ORDER BY ts ASC";

    public static final String SQL_COUNT_SAMPLES = "SELECT COUNT(*) FROM samples";

    /** 用户表。P2 才用到登录，但表先建好，省得以后改版本。 */
    public static final String SQL_CREATE_USERS =
        "CREATE TABLE IF NOT EXISTS users (" +
        "  id       INTEGER PRIMARY KEY AUTOINCREMENT," +
        "  username TEXT UNIQUE NOT NULL," +
        "  pwd_hash TEXT NOT NULL," +
        "  salt     TEXT NOT NULL," +
        "  age      INTEGER," +
        "  gender   TEXT," +
        "  height   REAL," +
        "  weight   REAL," +
        "  created  INTEGER" +
        ")";

    /** 键值配置：阈值、IP、端口、API key 等 */
    public static final String SQL_CREATE_SETTINGS =
        "CREATE TABLE IF NOT EXISTS settings (" +
        "  k TEXT PRIMARY KEY," +
        "  v TEXT" +
        ")";

    /**
     * 这一次插入之后要不要做上限检查。
     *
     * 第 1 次也查 —— 应用重启时如果库已经超了（上次没退干净），
     * 应该尽快收敛而不是再等 100 帧。
     */
    public static boolean shouldCheckEvict(long insertCount) {
        if (insertCount <= 0) return false;
        if (insertCount == 1) return true;
        return insertCount % EVICT_CHECK_EVERY == 0;
    }
}
