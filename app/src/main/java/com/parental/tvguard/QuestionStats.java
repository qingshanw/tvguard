package com.parental.tvguard;

import android.content.Context;
import android.util.Log;

import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.util.concurrent.Executors;

/**
 * 题目作答统计(持久化于 files/question_stats.json):
 *  - 记录每道题的作答次数与答对次数
 *  - 答对达到 RETIRE_AFTER 次(默认5次)的题目"毕业",不再出现
 *  - 支持家长重置
 * 题目身份 id = 题库文件名:文件内序号,导入文件名带时间戳,重新导入不影响旧统计。
 */
public final class QuestionStats {

    public static final int RETIRE_AFTER = 5; // 答对5次后毕业

    private static final String FILE = "question_stats.json";
    private static final Object LOCK = new Object();

    private static QuestionStats sInstance;

    private final Context ctx;
    private JSONObject data; // id -> {a:作答次数, c:答对次数}

    /** 进程级单例:所有题库实例共享同一份统计,避免多实例并发写文件互相覆盖。 */
    public static synchronized QuestionStats getInstance(Context ctx) {
        if (sInstance == null) {
            sInstance = new QuestionStats(ctx.getApplicationContext());
        }
        return sInstance;
    }

    private QuestionStats(Context ctx) {
        this.ctx = ctx.getApplicationContext();
        load();
    }

    private void load() {
        synchronized (LOCK) {
            data = new JSONObject();
            try {
                File f = new File(ctx.getFilesDir(), FILE);
                if (f.exists()) {
                    FileInputStream fis = new FileInputStream(f);
                    java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream();
                    byte[] buf = new byte[4096];
                    int n;
                    while ((n = fis.read(buf)) > 0) bos.write(buf, 0, n);
                    fis.close();
                    data = new JSONObject(bos.toString("UTF-8"));
                }
            } catch (Exception e) {
                Log.e("QuestionStats", "读取统计失败,按空统计处理", e);
                data = new JSONObject();
            }
        }
    }

    private void saveAsync() {
        final String json;
        synchronized (LOCK) {
            json = data.toString();
        }
        Executors.newSingleThreadExecutor().execute(new Runnable() {
            @Override
            public void run() {
                try {
                    File f = new File(ctx.getFilesDir(), FILE);
                    File tmp = new File(ctx.getFilesDir(), FILE + ".tmp");
                    FileOutputStream fos = new FileOutputStream(tmp);
                    fos.write(json.getBytes("UTF-8"));
                    fos.close();
                    // 原子替换:先写临时文件再重命名,断电/并发也不会写坏
                    if (!tmp.renameTo(f)) {
                        FileOutputStream fos2 = new FileOutputStream(f);
                        fos2.write(json.getBytes("UTF-8"));
                        fos2.close();
                        tmp.delete();
                    }
                } catch (Exception e) {
                    Log.e("QuestionStats", "保存统计失败", e);
                }
            }
        });
    }

    /** 记录一次作答。 */
    public void record(String id, boolean correct) {
        if (id == null) return;
        synchronized (LOCK) {
            JSONObject o = data.optJSONObject(id);
            if (o == null) o = new JSONObject();
            try {
                o.put("a", o.optInt("a", 0) + 1);
                if (correct) o.put("c", o.optInt("c", 0) + 1);
                data.put(id, o);
            } catch (Exception ignored) {
            }
        }
        saveAsync();
    }

    public int attempts(String id) {
        synchronized (LOCK) {
            JSONObject o = data.optJSONObject(id);
            return o == null ? 0 : o.optInt("a", 0);
        }
    }

    public int correct(String id) {
        synchronized (LOCK) {
            JSONObject o = data.optJSONObject(id);
            return o == null ? 0 : o.optInt("c", 0);
        }
    }

    public boolean graduated(String id) {
        return correct(id) >= RETIRE_AFTER;
    }

    /** 家长重置:清空全部统计(所有毕业题目重新出现)。 */
    public void resetAll() {
        synchronized (LOCK) {
            data = new JSONObject();
        }
        saveAsync();
    }
}
