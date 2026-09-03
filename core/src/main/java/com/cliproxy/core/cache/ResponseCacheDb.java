package com.cliproxy.core.cache;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;
import android.util.LruCache;

import java.util.concurrent.atomic.AtomicLong;

/**
 * 本地智能缓存数据库：
 * 采用 L1 内存 LRU (256项，<1ms) + L2 SQLite 持久化 (5000项，~3ms) 双层架构。
 */
public class ResponseCacheDb extends SQLiteOpenHelper {
    private static final String DB_NAME = "cliproxy_cache.db";
    private static final int DB_VERSION = 1;
    private static final String TABLE_NAME = "response_cache";

    public static class CacheEntry {
        public final String cacheKey;
        public final String model;
        public final String promptSummary;
        public final String responseContent;
        public final int tokenCount;
        public int hitCount;

        public CacheEntry(String cacheKey, String model, String promptSummary, String responseContent, int tokenCount, int hitCount) {
            this.cacheKey = cacheKey;
            this.model = model;
            this.promptSummary = promptSummary;
            this.responseContent = responseContent;
            this.tokenCount = tokenCount;
            this.hitCount = hitCount;
        }
    }

    private static ResponseCacheDb instance;
    private final LruCache<String, CacheEntry> memoryCache;
    private final AtomicLong totalHits = new AtomicLong(0);
    private final AtomicLong totalSavedTokens = new AtomicLong(0);

    public static synchronized ResponseCacheDb getInstance(Context context) {
        if (instance == null) {
            instance = new ResponseCacheDb(context.getApplicationContext());
        }
        return instance;
    }

    private ResponseCacheDb(Context context) {
        super(context, DB_NAME, null, DB_VERSION);
        this.memoryCache = new LruCache<>(256);
    }

    @Override
    public void onCreate(SQLiteDatabase db) {
        db.execSQL("CREATE TABLE IF NOT EXISTS " + TABLE_NAME + " (" +
                "cache_key TEXT PRIMARY KEY," +
                "model TEXT," +
                "prompt_summary TEXT," +
                "response_content TEXT," +
                "token_count INTEGER," +
                "hit_count INTEGER DEFAULT 1," +
                "created_at INTEGER," +
                "last_accessed_at INTEGER" +
                ");");
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_last_accessed ON " + TABLE_NAME + "(last_accessed_at);");
    }

    @Override
    public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) {
        db.execSQL("DROP TABLE IF EXISTS " + TABLE_NAME);
        onCreate(db);
    }

    /** 读取缓存（L1 内存优先，未命中走 L2 数据库） */
    public CacheEntry get(String cacheKey) {
        if (cacheKey == null || cacheKey.isEmpty()) return null;

        // 1. 探测 L1 内存
        CacheEntry entry = memoryCache.get(cacheKey);
        if (entry != null) {
            totalHits.incrementAndGet();
            totalSavedTokens.addAndGet(entry.tokenCount);
            entry.hitCount++;
            return entry;
        }

        // 2. 查询 L2 数据库
        try {
            SQLiteDatabase db = getReadableDatabase();
            try (Cursor c = db.query(TABLE_NAME, null, "cache_key = ?",
                    new String[]{cacheKey}, null, null, null)) {
                if (c != null && c.moveToFirst()) {
                    String model = c.getString(c.getColumnIndexOrThrow("model"));
                    String summary = c.getString(c.getColumnIndexOrThrow("prompt_summary"));
                    String content = c.getString(c.getColumnIndexOrThrow("response_content"));
                    int tokens = c.getInt(c.getColumnIndexOrThrow("token_count"));
                    int hits = c.getInt(c.getColumnIndexOrThrow("hit_count")) + 1;

                    entry = new CacheEntry(cacheKey, model, summary, content, tokens, hits);
                    memoryCache.put(cacheKey, entry);

                    totalHits.incrementAndGet();
                    totalSavedTokens.addAndGet(tokens);

                    // 异步更新命中次数与最后访问时间
                    final int fHits = hits;
                    new Thread(() -> {
                        try {
                            ContentValues cv = new ContentValues();
                            cv.put("hit_count", fHits);
                            cv.put("last_accessed_at", System.currentTimeMillis());
                            getWritableDatabase().update(TABLE_NAME, cv, "cache_key = ?", new String[]{cacheKey});
                        } catch (Exception ignored) {}
                    }).start();

                    return entry;
                }
            }
        } catch (Exception ignored) {}

        return null;
    }

    /** 写入缓存 */
    public void put(String cacheKey, String model, String promptSummary, String responseContent, int tokenCount) {
        if (cacheKey == null || responseContent == null || responseContent.isEmpty()) return;

        CacheEntry entry = new CacheEntry(cacheKey, model, promptSummary, responseContent, tokenCount, 1);
        memoryCache.put(cacheKey, entry);

        new Thread(() -> {
            try {
                SQLiteDatabase db = getWritableDatabase();
                ContentValues cv = new ContentValues();
                cv.put("cache_key", cacheKey);
                cv.put("model", model);
                cv.put("prompt_summary", promptSummary);
                cv.put("response_content", responseContent);
                cv.put("token_count", tokenCount);
                cv.put("hit_count", 1);
                cv.put("created_at", System.currentTimeMillis());
                cv.put("last_accessed_at", System.currentTimeMillis());

                db.insertWithOnConflict(TABLE_NAME, null, cv, SQLiteDatabase.CONFLICT_REPLACE);

                // 容量保护：最多保留 5000 条，超出淘汰最旧数据
                db.execSQL("DELETE FROM " + TABLE_NAME + " WHERE cache_key NOT IN (" +
                        "SELECT cache_key FROM " + TABLE_NAME + " ORDER BY last_accessed_at DESC LIMIT 5000);");
            } catch (Exception ignored) {}
        }).start();
    }

    /** 清空所有缓存 */
    public void clear() {
        memoryCache.evictAll();
        totalHits.set(0);
        totalSavedTokens.set(0);
        new Thread(() -> {
            try {
                getWritableDatabase().delete(TABLE_NAME, null, null);
            } catch (Exception ignored) {}
        }).start();
    }

    public long getTotalHits() { return totalHits.get(); }
    public long getTotalSavedTokens() { return totalSavedTokens.get(); }

    /** 获取所有已缓存条目列表（按最近访问时间倒序） */
    public java.util.List<CacheEntry> getAllEntries(int limit) {
        java.util.List<CacheEntry> list = new java.util.ArrayList<>();
        try {
            SQLiteDatabase db = getReadableDatabase();
            try (Cursor c = db.query(TABLE_NAME, null, null, null, null, null,
                    "last_accessed_at DESC", String.valueOf(limit))) {
                while (c != null && c.moveToNext()) {
                    String key = c.getString(c.getColumnIndexOrThrow("cache_key"));
                    String model = c.getString(c.getColumnIndexOrThrow("model"));
                    String summary = c.getString(c.getColumnIndexOrThrow("prompt_summary"));
                    String content = c.getString(c.getColumnIndexOrThrow("response_content"));
                    int tokens = c.getInt(c.getColumnIndexOrThrow("token_count"));
                    int hits = c.getInt(c.getColumnIndexOrThrow("hit_count"));
                    list.add(new CacheEntry(key, model, summary, content, tokens, hits));
                }
            }
        } catch (Exception ignored) {}
        return list;
    }

    /** 删除单条缓存 */
    public void deleteEntry(String cacheKey) {
        memoryCache.remove(cacheKey);
        new Thread(() -> {
            try {
                getWritableDatabase().delete(TABLE_NAME, "cache_key = ?", new String[]{cacheKey});
            } catch (Exception ignored) {}
        }).start();
    }

    /** 获取数据库总缓存条数 */
    public int getCachedEntriesCount() {
        try {
            SQLiteDatabase db = getReadableDatabase();
            try (Cursor c = db.rawQuery("SELECT COUNT(*) FROM " + TABLE_NAME, null)) {
                if (c != null && c.moveToFirst()) {
                    return c.getInt(0);
                }
            }
        } catch (Exception ignored) {}
        return memoryCache.size();
    }
}
