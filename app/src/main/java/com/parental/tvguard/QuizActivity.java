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

import android.app.Activity;
import android.os.Build;
import android.os.Bundle;
import android.view.KeyEvent;
import android.view.View;
import android.view.WindowManager;

/**
 * 全屏答题界面(不透明,盖住视频画面)。
 * 若当前处于锁定倒计时则显示倒计时,否则显示答题。
 * 视图逻辑在 QuizOverlayView / LockOverlayView 中,与悬浮窗兜底共用。
 */
public class QuizActivity extends Activity {

    private static QuizActivity sRunning; // 当前存活的实例(悬浮窗接管时由服务关闭)
    public static volatile int createCount = 0;  // 诊断:Activity 创建次数
    public static volatile int focusCount = 0;   // 诊断:窗口真正获得焦点次数

    private LockOverlayView lockView;
    private QuizOverlayView quizView;
    private boolean pinAttempted; // 屏幕固定只尝试一次,避免系统确认框反复弹出

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON
                | WindowManager.LayoutParams.FLAG_DISMISS_KEYGUARD);
        sRunning = this;
        createCount++;
        // 注意:此处在后台被创建时不算"已显示",握手以窗口获得焦点为准(onWindowFocusChanged)

        Prefs prefs = new Prefs(this);
        View content;
        if (prefs.getLong(Prefs.KEY_LOCK_UNTIL, 0) > System.currentTimeMillis()) {
            lockView = new LockOverlayView(this, new Runnable() {
                @Override
                public void run() {
                    // 锁定结束
                    Prefs p = new Prefs(QuizActivity.this);
                    p.putLong(Prefs.KEY_LOCK_UNTIL, 0);
                    p.putInt(Prefs.KEY_WRONG_STREAK, 0);
                    p.putInt(Prefs.KEY_WATCHED, 0);
                    MonitorService.onQuizPassed(QuizActivity.this); // 清计时+恢复播放
                    finish();
                }
            }, new Runnable() {
                @Override
                public void run() {
                    // 试图退出,由服务兜底
                }
            });
            content = lockView;
        } else {
            QuestionBank bank = new QuestionBank(this);
            bank.load(this);
            content = quizView = new QuizOverlayView(this, bank, new QuizOverlayView.Listener() {
                @Override
                public void onPassed() {
                    MonitorService.onQuizPassed(QuizActivity.this);
                    finish();
                }

                @Override
                public void onMaxWrong() {
                    MonitorService.startLock(QuizActivity.this);
                    recreate(); // 切换为锁定倒计时视图
                }
            });
        }
        setContentView(content);
    }

    @Override
    protected void onResume() {
        super.onResume();
        // 悬浮窗兜底已接管时,本 Activity 自动退出,避免悬浮窗消失后露出残留界面
        if (MonitorService.isOverlayActive()) {
            finish();
            return;
        }
        // 全屏沉浸
        View decor = getWindow().getDecorView();
        decor.setSystemUiVisibility(View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                | View.SYSTEM_UI_FLAG_FULLSCREEN
                | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                | View.SYSTEM_UI_FLAG_LAYOUT_STABLE);
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (MonitorService.isOverlayActive()) return;
        if (hasFocus) focusCount++;
        // 窗口真正获得焦点才算"弹题已显示"(后台创建不算),与服务握手
        MonitorService.quizShowing = hasFocus;
        if (hasFocus) {
            // 真正显示:本轮观看时间清零 + 撤销拦截提醒 + 屏幕固定
            new Prefs(this).putInt(Prefs.KEY_WATCHED, 0);
            try {
                android.app.NotificationManager nm =
                        (android.app.NotificationManager) getSystemService(NOTIFICATION_SERVICE);
                if (nm != null) nm.cancel(2);
            } catch (Exception ignored) {
            }
            if (!pinAttempted) {
                pinAttempted = true;
                tryPinScreen(); // 屏幕固定:固定期间主页/返回/最近任务全部失效
            }
        }
    }

    @Override
    protected void onStop() {
        super.onStop();
        // 界面被隐藏(如按了主页键):立即通知服务,0.6秒内重新拉起,不等轮询
        MonitorService svc = MonitorService.instance();
        if (svc != null) svc.onQuizHidden();
    }

    /**
     * 屏幕固定(Lock Task):仅在设备所有者模式下静默启用。
     * 普通模式不再调用 startLockTask——它会弹系统确认框抢焦点,
     * 导致答题界面被判"未显示"、悬浮窗兜底接管,出现"弹两次"的闪烁。
     */
    private void tryPinScreen() {
        try {
            android.app.admin.DevicePolicyManager dpm = (android.app.admin.DevicePolicyManager)
                    getSystemService(DEVICE_POLICY_SERVICE);
            if (dpm == null || !dpm.isDeviceOwnerApp(getPackageName())) {
                return; // 非设备所有者:不做屏幕固定,防逃靠服务秒级拉回
            }
            android.content.ComponentName admin =
                    new android.content.ComponentName(this, GuardDeviceAdminReceiver.class);
            dpm.setLockTaskPackages(admin, new String[]{getPackageName()});
            startLockTask(); // 静默固定,无系统确认框
        } catch (Throwable e) {
            // 设备不支持时忽略
        }
    }

    private void unpinScreen() {
        try {
            if (Build.VERSION.SDK_INT < 23) {
                return; // API 23 以下无法查询固定状态,stopLockTask 由系统自行处理
            }
            android.app.ActivityManager am = (android.app.ActivityManager)
                    getSystemService(ACTIVITY_SERVICE);
            if (am != null && am.getLockTaskModeState()
                    != android.app.ActivityManager.LOCK_TASK_MODE_NONE) {
                stopLockTask();
            }
        } catch (Throwable e) {
            // ignore
        }
    }

    /** 诊断:Activity 是否有存活实例。 */
    public static boolean isAlive() {
        return sRunning != null;
    }

    /** 看门狗调用:冻结后补执行逾期的答题切换。 */
    public static void recoverStuck() {
        QuizActivity a = sRunning;
        if (a != null && a.quizView != null) {
            a.quizView.recoverIfStuck();
        }
    }

    /** 供服务在悬浮窗接管时关闭孤儿 Activity。 */
    public static void finishRunning() {
        if (sRunning != null) {
            try {
                sRunning.finish();
            } catch (Exception ignored) {
            }
        }
    }

    @Override
    protected void onDestroy() {
        if (sRunning == this) sRunning = null;
        unpinScreen(); // 答题/锁定结束即解除固定
        MonitorService.quizShowing = false; // 焦点丢失/销毁都视为未显示
        MonitorService svc = MonitorService.instance();
        if (svc != null) svc.onBlockingViewRemoved();
        super.onDestroy();
    }

    @Override
    public void onBackPressed() {
        // 不允许退出;锁定模式下通知服务防绕过
        if (lockView != null) lockView.notifyEscape();
    }

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        if (keyCode == KeyEvent.KEYCODE_BACK) {
            onBackPressed();
            return true;
        }
        return super.onKeyDown(keyCode, event);
    }
}
