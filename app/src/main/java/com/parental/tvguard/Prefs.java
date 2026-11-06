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
import android.content.SharedPreferences;

import java.security.SecureRandom;

/**
 * SharedPreferences 封装:配置、计时状态、家长密码统一存取。
 */
public final class Prefs {

    private static final String FILE = "tvguard";

    // 配置项
    public static final String KEY_INTERVAL = "interval_sec";       // 每次观看多少秒弹题
    public static final String KEY_LOCK_SEC = "lock_sec";           // 连错后锁定秒数
    public static final String KEY_MAX_WRONG = "max_wrong";         // 连续错几题锁定
    public static final String KEY_PWD_HASH = "pwd_hash";           // 密码哈希(盐:哈希:格式)
    public static final String KEY_SERVICE_ON = "service_on";       // 监控开关
    public static final String KEY_FORCE_INTERVAL = "force_interval_sec"; // 每连续观看N秒强制休息(0=关闭)
    public static final String KEY_FORCE_LOCK = "force_lock_sec";   // 强制休息时长(秒)
    public static final String KEY_BOOT_START = "boot_start";       // 开机自启
    public static final String KEY_EMBED_GRADE4 = "embed_grade4";   // 内嵌四年级题库启用(默认开)
    public static final String KEY_DISABLED_BANKS = "disabled_banks"; // 已禁用的导入题库文件名(逗号分隔)
    public static final String KEY_BG_CONFIRMED = "bg_confirmed";   // 后台运行权限已手动确认
    public static final String KEY_NOTIF_ASKED = "notif_asked";     // 通知权限是否已请求过

    // 运行状态(重启后接续)
    public static final String KEY_WATCHED = "watched_sec";         // 本轮已观看秒数
    public static final String KEY_LOCK_UNTIL = "lock_until";       // 锁定截止时间戳(毫秒)
    public static final String KEY_WRONG_STREAK = "wrong_streak";   // 连续答错计数
    public static final String KEY_QUIZ_PENDING = "quiz_pending";   // 答题待完成标记(防按Home逃避)

    public static final int DEFAULT_INTERVAL = 600;   // 10 分钟
    public static final int DEFAULT_LOCK = 600;       // 10 分钟
    public static final int DEFAULT_MAX_WRONG = 3;

    private final SharedPreferences sp;

    public Prefs(Context ctx) {
        sp = ctx.getApplicationContext().getSharedPreferences(FILE, Context.MODE_PRIVATE);
    }

    public int getInt(String key, int def) {
        return sp.getInt(key, def);
    }

    public void putInt(String key, int v) {
        sp.edit().putInt(key, v).apply();
    }

    public long getLong(String key, long def) {
        return sp.getLong(key, def);
    }

    public void putLong(String key, long v) {
        sp.edit().putLong(key, v).apply();
    }

    public boolean getBool(String key, boolean def) {
        return sp.getBoolean(key, def);
    }

    public void putBool(String key, boolean v) {
        sp.edit().putBoolean(key, v).apply();
    }

    public String getString(String key, String def) {
        return sp.getString(key, def);
    }

    public void putString(String key, String v) {
        sp.edit().putString(key, v).apply();
    }

    // ---- 业务便捷方法 ----

    public int intervalSec() {
        int v = getInt(KEY_INTERVAL, DEFAULT_INTERVAL);
        return v < 30 ? 30 : Math.min(v, 24 * 3600);
    }

    public int lockSec() {
        int v = getInt(KEY_LOCK_SEC, DEFAULT_LOCK);
        return v < 30 ? 30 : Math.min(v, 24 * 3600);
    }

    public int maxWrong() {
        int v = getInt(KEY_MAX_WRONG, DEFAULT_MAX_WRONG);
        return v < 1 ? 1 : Math.min(v, 10);
    }

    /** 强制休息间隔(秒),0 = 关闭。 */
    public int forceIntervalSec() {
        int v = getInt(KEY_FORCE_INTERVAL, 0);
        return v < 0 ? 0 : Math.min(v, 24 * 3600);
    }

    /** 强制休息时长(秒)。 */
    public int forceLockSec() {
        int v = getInt(KEY_FORCE_LOCK, 300);
        return v < 30 ? 30 : Math.min(v, 24 * 3600);
    }

    /** 开机自启(默认开启)。 */
    public boolean bootStart() {
        return getBool(KEY_BOOT_START, true);
    }

    /** 内嵌四年级题库是否启用(默认启用)。 */
    public boolean embedGrade4() {
        return getBool(KEY_EMBED_GRADE4, true);
    }

    public boolean hasPassword() {
        return sp.contains(KEY_PWD_HASH);
    }

    public String passwordHash() {
        return getString(KEY_PWD_HASH, null);
    }

    /** 设置新密码,返回存储的哈希串。 */
    public void setPassword(String plain) {
        String salt = newSalt();
        putString(KEY_PWD_HASH, salt + ":" + PasswordUtil.hash(salt, plain));
    }

    public boolean checkPassword(String plain) {
        String stored = passwordHash();
        if (stored == null || plain == null) return false;
        int idx = stored.indexOf(':');
        if (idx <= 0) return false;
        String salt = stored.substring(0, idx);
        String hash = stored.substring(idx + 1);
        return PasswordUtil.hash(salt, plain).equals(hash);
    }

    private static String newSalt() {
        byte[] b = new byte[8];
        new SecureRandom().nextBytes(b);
        StringBuilder sb = new StringBuilder();
        for (byte x : b) sb.append(String.format("%02x", x));
        return sb.toString();
    }

    /** 简单 SHA-256,避免依赖 android.util.Base64 之外的东西。 */
    public static final class PasswordUtil {
        public static String hash(String salt, String plain) {
            try {
                java.security.MessageDigest md =
                        java.security.MessageDigest.getInstance("SHA-256");
                md.update((salt + ":" + plain + ":tvguard").getBytes("UTF-8"));
                byte[] d = md.digest();
                StringBuilder sb = new StringBuilder();
                for (byte x : d) sb.append(String.format("%02x", x));
                return sb.toString();
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        }
    }
}
