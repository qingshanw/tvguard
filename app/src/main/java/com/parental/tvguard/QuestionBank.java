/*
 * 视界守卫者 (Focus Quiz Guard) - 儿童电视防沉迷
 * Copyright (c) 2026 WangQingshan (https://github.com/qingshanw)
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 */

package com.parental.tvguard;

import android.content.Context;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * 题库管理:内置默认题 + 用户导入的 JSON 题库(存放在 files/questions/)。
 *
 * 题库文件统一格式(UTF-8):
 * { "version": 1, "questions": [ { "q": "题目", "options": ["A","B","C","D"], "answer": 0 } ] }
 *
 * 出题规则:随机抽题且避开最近出过的;答对达到 QuestionStats.RETIRE_AFTER 次的题目"毕业"
 * 不再出现;若所有题目都已毕业,allGraduated 置位,由界面提示家长解锁。
 */
public final class QuestionBank {

    private static final String TAG = "QuestionBank";
    public static final String BANK_DIR = "questions";

    /** 单道题。 */
    public static final class Question {
        public final String id;   // 全局唯一身份:题库文件:文件内序号
        public final String q;
        public final String[] options;
        public final int answer;  // 正确选项下标

        Question(String id, String q, String[] options, int answer) {
            this.id = id;
            this.q = q;
            this.options = options;
            this.answer = answer;
        }
    }

    private final List<Question> questions = new ArrayList<>();
    private final List<String> bankFiles = new ArrayList<>();
    private final List<Integer> recent = new ArrayList<>(); // 最近抽过的题,避免连续重复
    private final Random random = new Random();
    private final QuestionStats stats;
    public boolean allGraduated = false; // 所有题目均已毕业,等待家长重置
    private String loadError = null;     // 最近一次加载的错误信息(供界面提示)

    public QuestionBank(Context ctx) {
        stats = QuestionStats.getInstance(ctx);
    }

    /** 加载内置默认题库 + 所有已导入题库,返回总题数。 */
    public synchronized int load(Context ctx) {
        questions.clear();
        bankFiles.clear();
        recent.clear();
        allGraduated = false;
        loadError = null;

        // 内嵌四年级题库(100道,可在题库管理中删除/恢复)
        if (new Prefs(ctx).embedGrade4()) {
            try {
                JSONObject root = new JSONObject(readAsset(ctx, "grade4_bank.json"));
                JSONArray arr = root.optJSONArray("questions");
                if (arr != null) parseInto(arr, "四年级题库");
            } catch (Exception e) {
                Log.e(TAG, "load grade4 bank failed", e);
                loadError = "四年级题库加载失败: " + e.getMessage();
            }
        }

        // 已导入题库
        File dir = new File(ctx.getFilesDir(), BANK_DIR);
        File[] files = dir.listFiles();
        if (files != null) {
            java.util.Arrays.sort(files);
            for (File f : files) {
                if (!f.getName().toLowerCase().endsWith(".json")) continue;
                try {
                    String text = readFile(f);
                    JSONObject root = new JSONObject(text);
                    JSONArray arr = root.optJSONArray("questions");
                    if (arr == null) continue;
                    int before = questions.size();
                    parseInto(arr, f.getName());
                    if (questions.size() > before) bankFiles.add(f.getName());
                } catch (Exception e) {
                    Log.e(TAG, "parse bank failed: " + f.getName(), e);
                }
            }
        }
        return questions.size();
    }

    private void parseInto(JSONArray arr, String src) throws Exception {
        for (int i = 0; i < arr.length(); i++) {
            JSONObject o = arr.getJSONObject(i);
            String q = normalizeNewlines(o.optString("q", "").trim());
            JSONArray opts = o.optJSONArray("options");
            int ans = o.optInt("answer", -1);
            if (q.isEmpty() || opts == null || opts.length() < 2) continue;
            if (ans < 0 || ans >= opts.length()) continue;
            String[] os = new String[opts.length()];
            for (int j = 0; j < opts.length(); j++) {
                os[j] = normalizeNewlines(opts.optString(j, "").trim());
            }
            questions.add(new Question(src + ":" + i, q, os, ans));
        }
    }

    /** 题库里常写字面量 "\n"(反斜杠+n)表示换行,统一转成真正的换行符。 */
    private static String normalizeNewlines(String text) {
        if (text == null) return "";
        return text.replace("\\n", "\n");
    }

    public synchronized int size() {
        return questions.size();
    }

    /** 未毕业(仍会出现的)题目数。 */
    public synchronized int activeCount() {
        int n = 0;
        for (Question q : questions) if (!stats.graduated(q.id)) n++;
        return n;
    }

    /** 已毕业题目数。 */
    public synchronized int graduatedCount() {
        return questions.size() - activeCount();
    }

    public synchronized List<String> bankFileNames() {
        return new ArrayList<>(bankFiles);
    }

    /** 最近一次加载的错误信息,null = 正常。 */
    public synchronized String getLoadError() {
        return loadError;
    }

    public synchronized List<Question> allQuestions() {
        return new ArrayList<>(questions);
    }

    public QuestionStats stats() {
        return stats;
    }

    /** 随机抽一题:跳过已毕业题目,尽量避开最近出过的题。全部毕业时返回 null。 */
    public synchronized Question nextQuestion() {
        int n = questions.size();
        allGraduated = false;
        if (n == 0) return null;

        List<Integer> pool = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            if (!stats.graduated(questions.get(i).id)) pool.add(i);
        }
        if (pool.isEmpty()) {
            allGraduated = true;
            return null;
        }
        if (pool.size() <= 5) recent.clear();

        for (int attempt = 0; attempt < 10; attempt++) {
            int pick = random.nextInt(pool.size());
            int idx = pool.get(pick);
            if (!recent.contains(idx)) {
                recent.add(idx);
                while (recent.size() > 5) recent.remove(0);
                return questions.get(idx);
            }
        }
        return questions.get(pool.get(random.nextInt(pool.size())));
    }

    // ---- 导入 ----

    /** 校验并保存一个题库文件,返回题数;格式错误抛 IllegalArgumentException。 */
    public static int importBank(Context ctx, String fileName, String jsonText) throws Exception {
        JSONObject root = new JSONObject(jsonText);
        JSONArray arr = root.optJSONArray("questions");
        if (arr == null || arr.length() == 0) {
            throw new IllegalArgumentException("题库为空或缺少 questions 数组");
        }
        for (int i = 0; i < arr.length(); i++) {
            JSONObject o = arr.getJSONObject(i);
            String q = o.optString("q", "").trim();
            JSONArray opts = o.optJSONArray("options");
            int ans = o.optInt("answer", -1);
            if (q.isEmpty()) throw new IllegalArgumentException("第 " + (i + 1) + " 题缺少题目(q)");
            if (opts == null || opts.length() < 2)
                throw new IllegalArgumentException("第 " + (i + 1) + " 题选项少于 2 个");
            if (ans < 0 || ans >= opts.length())
                throw new IllegalArgumentException("第 " + (i + 1) + " 题答案(answer)不合法");
        }
        File dir = new File(ctx.getFilesDir(), BANK_DIR);
        if (!dir.exists() && !dir.mkdirs()) throw new IllegalStateException("无法创建题库目录");
        String safe = sanitizeName(fileName);
        File out = new File(dir, safe);
        java.io.FileOutputStream fos = new java.io.FileOutputStream(out);
        fos.write(jsonText.getBytes(StandardCharsets.UTF_8));
        fos.close();
        return arr.length();
    }

    /** 内嵌四年级题库是否启用。 */
    public static boolean isGrade4Enabled(Context ctx) {
        return new Prefs(ctx).embedGrade4();
    }

    /** 启用/删除内嵌四年级题库(删除=停用,统计保留,可恢复)。 */
    public static void setGrade4Enabled(Context ctx, boolean enabled) {
        new Prefs(ctx).putBool(Prefs.KEY_EMBED_GRADE4, enabled);
    }

    public static boolean deleteBank(Context ctx, String fileName) {
        File f = new File(new File(ctx.getFilesDir(), BANK_DIR), fileName);
        return f.delete();
    }

    private static String sanitizeName(String name) {
        if (name == null) name = "bank.json";
        name = new File(name).getName().trim();
        if (name.isEmpty()) name = "bank.json";
        if (!name.toLowerCase().endsWith(".json")) name = name + ".json";
        return System.currentTimeMillis() + "_" + name.replaceAll("[^\\w\\u4e00-\\u9fa5.\\-]", "_");
    }

    // ---- IO 工具 ----

    private static String readFile(File f) throws Exception {
        FileInputStream fis = new FileInputStream(f);
        try {
            return new String(readAll(fis), StandardCharsets.UTF_8);
        } finally {
            fis.close();
        }
    }

    private static String readAsset(Context ctx, String name) throws Exception {
        InputStream is = ctx.getAssets().open(name);
        try {
            return new String(readAll(is), StandardCharsets.UTF_8);
        } finally {
            is.close();
        }
    }

    private static byte[] readAll(InputStream is) throws Exception {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        int n;
        while ((n = is.read(buf)) > 0) bos.write(buf, 0, n);
        return bos.toByteArray();
    }
}
