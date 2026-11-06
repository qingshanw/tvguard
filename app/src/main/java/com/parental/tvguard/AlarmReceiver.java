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

import android.app.AlarmManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.os.SystemClock;
import android.util.Log;

/**
 * 看门狗闹钟:国产ROM会把后台进程冻结(计时线程停摆),
 * 用 setExactAndAllowWhileIdle 每60秒唤醒一次进程并拉起服务,
 * 闹钟唤醒不受进程冻结影响(持有悬浮窗权限,Android 12+ 也允许后台拉起前台服务)。
 */
public class AlarmReceiver extends BroadcastReceiver {

    public static final String ACTION = "com.parental.tvguard.WATCHDOG";
    private static final String TAG = "AlarmReceiver";
    private static final long INTERVAL = 60_000;

    public static void schedule(Context context) {
        try {
            AlarmManager am = (AlarmManager) context.getSystemService(Context.ALARM_SERVICE);
            if (am == null) return;
            Intent it = new Intent(context, AlarmReceiver.class).setAction(ACTION);
            PendingIntent pi = PendingIntent.getBroadcast(context, 1001, it,
                    PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
            long at = SystemClock.elapsedRealtime() + INTERVAL;
            if (Build.VERSION.SDK_INT >= 23) {
                am.setExactAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME_WAKEUP, at, pi);
            } else {
                am.setExact(AlarmManager.ELAPSED_REALTIME_WAKEUP, at, pi);
            }
        } catch (Exception e) {
            Log.e(TAG, "schedule watchdog failed", e);
        }
    }

    @Override
    public void onReceive(Context context, Intent intent) {
        // 唤醒进程:计时服务若被杀/被冻结,重新拉起;然后安排下一次看门狗
        Prefs prefs = new Prefs(context);
        if (prefs.getBool(Prefs.KEY_SERVICE_ON, true)) {
            ScreenReceiver.start(context);
        }
        MonitorService.onWatchdog(); // 冻结自愈:补执行逾期的答题切换
        schedule(context);
    }
}
