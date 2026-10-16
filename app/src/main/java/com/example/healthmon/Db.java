package com.example.healthmon;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;

import java.util.ArrayList;
import java.util.List;

/**
 * SQLite 封装。
 *
 * 【线程约定】本类的所有方法都可能阻塞（尤其淘汰时的 COUNT(*) 与 DELETE），
 * 因此【必须在后台线程调用】。调用方负责，本类不替你做线程切换 ——
 * 上一项目的教训是：数据库操作悄悄跑到主线程上，表现是"偶尔卡一下"，
 * 极难定位，不如一开始就把责任写清楚。
 *
 * 表结构与淘汰语句都在 DbSchema（纯 Java，可本机秒级测试）。
 */
public final class Db extends SQLiteOpenHelper {

    private long insertCount = 0;

    public Db(Context ctx) {
        super(ctx, DbSchema.DB_NAME, null, DbSchema.DB_VERSION);
    }

    @Override
    public void onCreate(SQLiteDatabase db) {
        db.execSQL(DbSchema.SQL_CREATE_SAMPLES);
        db.execSQL(DbSchema.SQL_CREATE_IDX_TS);
        db.execSQL(DbSchema.SQL_CREATE_USERS);
        db.execSQL(DbSchema.SQL_CREATE_SETTINGS);
    }

    @Override
    public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) {
        // v1 阶段没有需要保留的历史包袱，直接重建。
        // 将来真要加字段时，这里必须改成真正的迁移，否则用户历史会丢。
        db.execSQL("DROP TABLE IF EXISTS " + DbSchema.T_SAMPLES);
        db.execSQL("DROP TABLE IF EXISTS " + DbSchema.T_USERS);
        db.execSQL("DROP TABLE IF EXISTS " + DbSchema.T_SETTINGS);
        onCreate(db);
    }

    // ---------------------------------------------------------------- 采样

    /**
     * 插入一帧。
     *
     * 缺失字段（NaN / -1）统一写成 SQL NULL，而不是把 -1 存进去 ——
     * 否则读回来时"没读到"和"读到了 -1"就分不清了，而这个区分
     * 正是界面显示 "--" 还是显示数字的依据。
     */
    public synchronized void insertSample(long ts, Frame f) {
        if (f == null) return;
        SQLiteDatabase db = getWritableDatabase();

        ContentValues v = new ContentValues(10);
        v.put("ts", ts);
        putDouble(v, "bt", f.bt);
        putInt(v, "hr", f.hr);
        putInt(v, "spo2", f.spo2);
        putLong(v, "steps", f.steps);
        putInt(v, "water", f.water);
        putInt(v, "pump", f.pump);
        putInt(v, "pmode", f.pmode);
        putInt(v, "sw", f.sw);
        v.put("al", f.al);

        db.insert(DbSchema.T_SAMPLES, null, v);

        insertCount++;
        if (DbSchema.shouldCheckEvict(insertCount)) {
            evictIfNeeded(db);
        }
    }

    private static void putDouble(ContentValues v, String k, double d) {
        if (Double.isNaN(d)) v.putNull(k);
        else v.put(k, d);
    }

    private static void putInt(ContentValues v, String k, int i) {
        if (i < 0) v.putNull(k);
        else v.put(k, i);
    }

    private static void putLong(ContentValues v, String k, long l) {
        if (l < 0) v.putNull(k);
        else v.put(k, l);
    }

    /** 超过上限就删最旧的一批。调用方保证已在后台线程。 */
    public void evictIfNeeded(SQLiteDatabase db) {
        long n = countSamples(db);
        if (n > DbSchema.MAX_ROWS) {
            db.execSQL(DbSchema.SQL_EVICT_OLDEST);
        }
    }

    public long countSamples() {
        return countSamples(getReadableDatabase());
    }

    private long countSamples(SQLiteDatabase db) {
        Cursor c = null;
        try {
            c = db.rawQuery(DbSchema.SQL_COUNT_SAMPLES, null);
            return c.moveToFirst() ? c.getLong(0) : 0L;
        } finally {
            if (c != null) c.close();
        }
    }

    /**
     * 按时间范围查采样，按时间升序返回（曲线要按时间画）。
     * NULL 列读回成对应哨兵，与 Protocol 的约定一致。
     */
    public List<Frame> querySamples(long fromTs, long toTs) {
        List<Frame> out = new ArrayList<Frame>();
        Cursor c = null;
        try {
            c = getReadableDatabase().rawQuery(
                    DbSchema.SQL_SELECT_RANGE,
                    new String[]{String.valueOf(fromTs), String.valueOf(toTs)});
            while (c.moveToNext()) {
                Frame f = new Frame();
                f.ts = c.getLong(1);
                f.bt = c.isNull(2) ? Double.NaN : c.getDouble(2);
                f.hr = c.isNull(3) ? -1 : c.getInt(3);
                f.spo2 = c.isNull(4) ? -1 : c.getInt(4);
                f.steps = c.isNull(5) ? -1 : c.getLong(5);
                f.water = c.isNull(6) ? -1 : c.getInt(6);
                f.pump = c.isNull(7) ? -1 : c.getInt(7);
                f.pmode = c.isNull(8) ? -1 : c.getInt(8);
                f.sw = c.isNull(9) ? -1 : c.getInt(9);
                f.al = c.isNull(10) ? 0 : c.getInt(10);
                out.add(f);
            }
        } finally {
            if (c != null) c.close();
        }
        return out;
    }

    // ---------------------------------------------------------------- 配置

    public String getSetting(String key, String def) {
        Cursor c = null;
        try {
            c = getReadableDatabase().rawQuery(
                    "SELECT v FROM " + DbSchema.T_SETTINGS + " WHERE k = ?", new String[]{key});
            if (c.moveToFirst() && !c.isNull(0)) return c.getString(0);
            return def;
        } finally {
            if (c != null) c.close();
        }
    }

    public void setSetting(String key, String value) {
        ContentValues v = new ContentValues(2);
        v.put("k", key);
        v.put("v", value);
        getWritableDatabase().insertWithOnConflict(
                DbSchema.T_SETTINGS, null, v, SQLiteDatabase.CONFLICT_REPLACE);
    }

    // ---------------------------------------------------------------- 用户

    public long insertUser(String username, String pwdHash, String salt) {
        ContentValues v = new ContentValues(4);
        v.put("username", username);
        v.put("pwd_hash", pwdHash);
        v.put("salt", salt);
        v.put("created", System.currentTimeMillis());
        return getWritableDatabase().insert(DbSchema.T_USERS, null, v);
    }

    /** 返回 {pwdHash, salt}；找不到返回 null。 */
    public String[] findUser(String username) {
        Cursor c = null;
        try {
            c = getReadableDatabase().rawQuery(
                    "SELECT pwd_hash, salt FROM " + DbSchema.T_USERS + " WHERE username = ?",
                    new String[]{username});
            if (c.moveToFirst()) return new String[]{c.getString(0), c.getString(1)};
            return null;
        } finally {
            if (c != null) c.close();
        }
    }

    public void updateUserProfile(String username, int age, String gender, double height, double weight) {
        ContentValues v = new ContentValues(4);
        v.put("age", age);
        v.put("gender", gender);
        v.put("height", height);
        v.put("weight", weight);
        getWritableDatabase().update(DbSchema.T_USERS, v, "username = ?", new String[]{username});
    }
}
