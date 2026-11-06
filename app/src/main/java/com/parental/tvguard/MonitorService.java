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

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.media.AudioManager;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.PowerManager;
import android.os.SystemClock;
import android.util.Log;
import android.view.View;
import android.view.WindowManager;

/**
 * 计时守护服务(前台服务):
 *  - 统计亮屏观看时间,达到间隔后弹答题
 *  - 答题期间若被退出,持续重新拉起
 *  - 锁定倒计时期间持续阻断
 */
public class MonitorService extends Service {

    private static final String TAG = "MonitorService";
    private static final String CHANNEL_ID = "monitor";

    /** 服务 → 答题界面握手:QuizActivity onCreate 时置 true,服务 1.2 秒后检查,失败则用悬浮窗兜底。 */
    public static volatile boolean quizShowing = false;

    /** 家长配置界面(设置/题库/密码)可见计数:>0 表示家长正在操作,暂停计时。 */
    private static final java.util.concurrent.atomic.AtomicInteger configUiCount =
            new java.util.concurrent.atomic.AtomicInteger(0);

    public static void enterConfigUi() {
        configUiCount.incrementAndGet();
    }

    public static void exitConfigUi() {
        configUiCount.decrementAndGet();
    }

    /** 是否有任一配置界面(设置/子页面/密码页)在前台。 */
    public static boolean isConfigUiVisible() {
        return configUiCount.get() > 0;
    }

    private static MonitorService sInstance;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private ScreenReceiver receiver;
    private WindowManager wm;
    private View overlayView;          // 悬浮窗兜底视图(答题或锁定倒计时)
    private QuestionBank bank;

    private long lastTickRealtime;     // 上次累计的时刻
    private long lastRelaunchAt;       // 上次重新拉起阻断界面的时刻(防抖)
    private boolean screenOn = true;

    // 周期任务:计时
    private final Runnable tickRunnable = new Runnable() {
        @Override
        public void run() {
            tick();
            handler.postDelayed(this, 1000);
        }
    };

    @Override
    public void onCreate() {
        super.onCreate();
        sInstance = this;
        // 双保险:监控开关关闭时服务自行退出
        if (!new Prefs(this).getBool(Prefs.KEY_SERVICE_ON, true)) {
            stopSelf();
            return;
        }
        wm = (WindowManager) getSystemService(Context.WINDOW_SERVICE);
        bank = new QuestionBank(this);
        int count = bank.load(this);
        Log.i(TAG, "服务启动,题库 " + count + " 题");

        startForegroundCompat();
        updateStatusNotification(new Prefs(this), 0, new Prefs(this).intervalSec());
        registerScreenReceiver();
        AlarmReceiver.schedule(this); // 看门狗:防ROM冻结后台导致的计时停摆

        screenOn = isScreenOn();
        lastTickRealtime = SystemClock.elapsedRealtime();
        handler.post(tickRunnable);
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        handler.removeCallbacksAndMessages(null);
        if (receiver != null) {
            try {
                unregisterReceiver(receiver);
            } catch (Exception ignored) {
            }
            receiver = null;
        }
        removeOverlay();
        if (sInstance == this) sInstance = null;
        Log.i(TAG, "服务销毁");
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    public static MonitorService instance() {
        return sInstance;
    }

    /** 重新加载题库(导入后调用)。 */
    public static void reloadBank(Context ctx) {
        MonitorService s = sInstance;
        if (s != null) s.bank.load(ctx);
    }

    // ---- 计时核心 ----

    private void tick() {
        Prefs prefs = new Prefs(this);

        // 锁定状态检查优先
        long now = System.currentTimeMillis();
        long lockUntil = prefs.getLong(Prefs.KEY_LOCK_UNTIL, 0);
        if (lockUntil > 0) {
            if (now >= lockUntil) {
                // 锁定结束
                prefs.putLong(Prefs.KEY_LOCK_UNTIL, 0);
                prefs.putInt(Prefs.KEY_WRONG_STREAK, 0);
                prefs.putInt(Prefs.KEY_WATCHED, 0);
                onLockExpired();
            } else if (!quizShowing && overlayView == null) {
                // 锁定中但界面不在,重新拉起
                ensureBlockingUi(prefs, true);
            }
            return;
        }

        // 压制自愈:悬浮窗添加成功但长时间无人作答(ROM 把后台应用的悬浮窗
        // 压制为不可见,华为/荣耀等常见)→ 撤掉它,恢复计时,稍后重试并提醒授权
        long nowE = SystemClock.elapsedRealtime();
        if (prefs.getInt(Prefs.KEY_QUIZ_PENDING, 0) == 1 && overlayView != null
                && !quizShowing && overlayShownAt > 0
                && nowE - overlayShownAt > 90_000 && nowE - lastOverlayTouch > 60_000) {
            Log.w(TAG, "悬浮窗疑似被ROM压制不可见,撤掉并恢复计时,稍后重试");
            removeOverlay();
            prefs.putInt(Prefs.KEY_QUIZ_PENDING, 0);
            prefs.putInt(Prefs.KEY_WATCHED, prefs.intervalSec() / 2);
            pendingSince = 0;
            displayFailCount++;
            notifyBlocked();
        }

        // 卡死守卫:quizShowing=true 但界面实体全都不存在 → 状态失真,强制复位
        if (quizShowing && !QuizActivity.isAlive() && overlayView == null) {
            Log.e(TAG, "检测到 quizShowing 状态失真,强制复位");
            quizShowing = false;
        }

        // 答题未完成却被退出(按Home/强杀)→ 立即重新拉起,不给继续看
        // (家长正在配置界面时暂缓,避免弹窗打断家长操作;离开配置后自动恢复拉起)
        if (screenOn && configUiCount.get() == 0
                && prefs.getInt(Prefs.KEY_QUIZ_PENDING, 0) == 1
                && !quizShowing && overlayView == null) {
            long t = SystemClock.elapsedRealtime();
            if (t - lastRelaunchAt > 2000) {
                lastRelaunchAt = t;
                Log.i(TAG, "答题未完成被退出,重新拉起");
                ensureBlockingUi(prefs, false);
            }
            // 自愈:弹窗超过30秒仍发不出去(系统拦截且悬浮窗失败),
            // 放弃本轮,恢复计时,半轮后再重试,避免计时永久冻结
            if (pendingSince > 0 && t - pendingSince > 30_000) {
                Log.w(TAG, "弹窗30秒未能显示,暂缓本轮,恢复计时,半轮后重试");
                pendingSince = 0;
                prefs.putInt(Prefs.KEY_QUIZ_PENDING, 0);
                prefs.putInt(Prefs.KEY_WATCHED, prefs.intervalSec() / 2);
                notifyBlocked(); // 提醒家长去授权
            }
            return;
        }

        // 正常亮屏、无阻断界面、无待显示弹窗、且家长不在配置界面时才计时
        // (pending==1 期间严禁重复触发弹题——那是"刷新两次"的根源)
        if (screenOn && configUiCount.get() == 0 && !quizShowing && overlayView == null
                && prefs.getInt(Prefs.KEY_QUIZ_PENDING, 0) == 0) {
            long t = SystemClock.elapsedRealtime();
            // 答题/锁定期间的时间一律不计:若上次结算点早于答题结束时刻,从答题结束时刻起算
            if (quizClosedAt > 0 && lastTickRealtime < quizClosedAt) {
                lastTickRealtime = quizClosedAt;
            }
            int delta = (int) ((t - lastTickRealtime) / 1000);
            lastTickRealtime = t;
            // delta 大于1秒说明tick被延迟(ROM冻结/省电),亮屏期间该段时间孩子同样在看电视,
            // 应予补偿计入;设5分钟上限防止极端异常
            if (delta > 0) {
                delta = Math.min(delta, 300);
                int watched = prefs.getInt(Prefs.KEY_WATCHED, 0) + delta;
                prefs.putInt(Prefs.KEY_WATCHED, watched); // apply() 异步落盘,重启后接续
                updateStatusNotification(prefs, watched, prefs.intervalSec());

                // 强制休息:每连续观看 N 秒,锁定 M 秒(独立于答题)
                int forceN = prefs.forceIntervalSec();
                if (forceN > 0 && watched >= forceN) {
                    prefs.putInt(Prefs.KEY_WATCHED, 0);
                    startLock(this, prefs.forceLockSec());
                    quizShowing = false;
                    ensureBlockingUi(prefs, true);
                    return;
                }

                if (watched >= prefs.intervalSec()) {
                    // 注意:此处不清零观看时间,弹窗真正显示时才清零,
                    // 否则弹窗被系统拦截时孩子白得一整轮时间
                    triggerQuiz();
                }
            }
        } else {
            lastTickRealtime = SystemClock.elapsedRealtime();
        }
    }

    /** 到达间隔,弹答题:先发暂停键,再启动答题界面。 */
    private void triggerQuiz() {
        Log.i(TAG, "到达观看间隔,弹答题");
        sendMediaKey(KeyEvent_PAUSE());
        pendingSince = SystemClock.elapsedRealtime();
        new Prefs(this).putInt(Prefs.KEY_QUIZ_PENDING, 1);
        quizShowing = false;
        final Prefs p = new Prefs(this);
        try {
            Intent it = new Intent(this, QuizActivity.class);
            it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
            startActivity(it);
        } catch (Exception e) {
            Log.e(TAG, "启动答题失败,直接悬浮窗", e);
            showOverlay(buildQuizView(p), false);
            return;
        }
        // 握手检查:2.5 秒后若界面未获得焦点,用悬浮窗兜底
        handler.postDelayed(new Runnable() {
            @Override
            public void run() {
                if (quizShowing) return;
                Log.w(TAG, "答题界面未到前台,悬浮窗兜底");
                showOverlay(buildQuizView(p), false);
                if (overlayView == null) {
                    displayFailCount++; // Activity 和悬浮窗都失败了
                    Log.e(TAG, "悬浮窗也添加失败,保持重试(不再假装已显示)");
                }
            }
        }, 2500);
    }

    /** 服务主动拉起阻断界面(答题中途退出 / 锁定中途退出)。 */
    private void ensureBlockingUi(Prefs prefs, boolean lockMode) {
        quizShowing = false;
        try {
            Intent it = new Intent(this, QuizActivity.class);
            it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
            startActivity(it);
        } catch (Exception e) {
            showOverlay(lockMode ? buildLockView(prefs) : buildQuizView(prefs), lockMode);
            return;
        }
        handler.postDelayed(new Runnable() {
            @Override
            public void run() {
                if (quizShowing) return;
                showOverlay(lockMode ? buildLockView(prefs) : buildQuizView(prefs), lockMode);
            }
        }, 2500);
    }

    /** 答对后由 QuizActivity 调用:恢复播放,关闭悬浮窗兜底。 */
    public static void onQuizPassed(Context ctx) {
        Prefs prefs = new Prefs(ctx);
        prefs.putInt(Prefs.KEY_WATCHED, 0);
        prefs.putInt(Prefs.KEY_QUIZ_PENDING, 0);
        MonitorService s = sInstance;
        if (s != null) {
            s.pendingSince = 0;
            quizClosedAt = SystemClock.elapsedRealtime(); // 答对:从此刻起才恢复计时
            s.sendMediaKey(KeyEvent_PLAY());
            s.removeOverlay();
            s.quizShowing = false;
        }
    }

    /** 连错达到上限,由 QuizActivity 调用,进入锁定。 */
    public static void startLock(Context ctx) {
        startLock(ctx, new Prefs(ctx).lockSec());
    }

    /** 进入锁定,锁定时长由调用方指定(连错锁定用 lockSec,强制休息用 forceLockSec)。 */
    public static void startLock(Context ctx, long lockSeconds) {
        Prefs prefs = new Prefs(ctx);
        long until = System.currentTimeMillis() + lockSeconds * 1000L;
        prefs.putLong(Prefs.KEY_LOCK_UNTIL, until);
        prefs.putInt(Prefs.KEY_QUIZ_PENDING, 0);
        MonitorService s = sInstance;
        if (s != null) s.pendingSince = 0;
        Log.i(TAG, "进入锁定,时长 " + lockSeconds + " 秒,截止 " + until);
    }

    private void onLockExpired() {
        Log.i(TAG, "锁定结束,恢复");
        quizClosedAt = SystemClock.elapsedRealtime(); // 锁定结束:从此刻起才恢复计时
        Prefs prefs = new Prefs(this);
        prefs.putInt(Prefs.KEY_QUIZ_PENDING, 0);
        sendMediaKey(KeyEvent_PLAY());
        removeOverlay();
        quizShowing = false;
    }

    // ---- 悬浮窗兜底 ----

    private void showOverlay(View v, boolean lockMode) {
        removeOverlay();
        overlayView = v;
        overlayActive = true;
        int type;
        if (Build.VERSION.SDK_INT >= 26) {
            type = WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY;
        } else {
            // noinspection deprecation
            type = WindowManager.LayoutParams.TYPE_PHONE;
        }
        WindowManager.LayoutParams lp = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.MATCH_PARENT,
                type,
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
                android.graphics.PixelFormat.OPAQUE);
        try {
            wm.addView(v, lp);
            overlayShownAt = SystemClock.elapsedRealtime();
            lastOverlayTouch = overlayShownAt;
            // 夺取输入焦点并拦截返回/菜单键,防止按键绕过答题界面
            // (主页键由系统处理无法拦截,但悬浮窗始终盖在最上层,且会被自动重新拉起)
            v.setFocusable(true);
            v.setFocusableInTouchMode(true);
            v.requestFocus();
            v.setOnKeyListener(new View.OnKeyListener() {
                @Override
                public boolean onKey(View vv, int keyCode, android.view.KeyEvent event) {
                    return keyCode == android.view.KeyEvent.KEYCODE_BACK
                            || keyCode == android.view.KeyEvent.KEYCODE_MENU;
                }
            });
            displayFailCount = 0;
            cancelBlockedNotice();
            // 弹窗真正显示,本轮观看时间清零
            new Prefs(this).putInt(Prefs.KEY_WATCHED, 0);
            // 悬浮窗接管成功,关闭没能到前台的孤儿答题 Activity
            QuizActivity.finishRunning();
        } catch (Exception e) {
            Log.e(TAG, "悬浮窗添加失败", e);
            overlayView = null;
            overlayActive = false;
            displayFailCount++;
        }
    }

    private void removeOverlay() {
        overlayActive = false;
        quizClosedAt = SystemClock.elapsedRealtime(); // 悬浮窗关闭:从此之后才恢复计时
        if (overlayView != null) {
            try {
                wm.removeView(overlayView);
            } catch (Exception ignored) {
            }
            overlayView = null;
        }
    }

    /** 悬浮窗兜底是否正在显示(此时 Activity 已被关闭)。 */
    public static boolean isOverlayActive() {
        return overlayActive;
    }

    /** 弹窗被系统拦截时,通知家长去授权(点击直达设置页)。 */
    private void notifyBlocked() {
        try {
            android.app.NotificationManager nm =
                    (android.app.NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
            if (nm == null) return;
            android.app.NotificationChannel ch = new android.app.NotificationChannel(
                    "blocked", "弹窗拦截提醒", android.app.NotificationManager.IMPORTANCE_HIGH);
            nm.createNotificationChannel(ch);
            Intent it = new Intent(this, MainActivity.class)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            android.app.PendingIntent pi = android.app.PendingIntent.getActivity(
                    this, 2002, it,
                    android.app.PendingIntent.FLAG_UPDATE_CURRENT
                            | android.app.PendingIntent.FLAG_IMMUTABLE);
            boolean overlayOk = android.provider.Settings.canDrawOverlays(this);
            String why = overlayOk
                    ? "请到系统设置允许「后台弹出界面」权限,点此查看指引"
                    : "悬浮窗权限未授权,点此去授权";
            android.app.Notification n = new android.app.Notification.Builder(this, "blocked")
                    .setContentTitle("孩子观看时间已到,但弹窗被系统拦截")
                    .setContentText(why)
                    .setStyle(new android.app.Notification.BigTextStyle()
                            .bigText(why + "\n(若是新安装/升级,系统可能重置了权限)"))
                    .setSmallIcon(android.R.drawable.ic_dialog_alert)
                    .setContentIntent(pi)
                    .setAutoCancel(true)
                    .build();
            nm.notify(2, n);
        } catch (Exception e) {
            Log.e(TAG, "notifyBlocked failed", e);
        }
    }

    /** 弹窗成功显示后,撤销拦截提醒。 */
    private void cancelBlockedNotice() {
        try {
            android.app.NotificationManager nm =
                    (android.app.NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
            if (nm != null) nm.cancel(2);
        } catch (Exception ignored) {
        }
    }

    /** 看门狗闹钟调用:检查答题界面是否有逾期的切换未执行(进程冻结自愈)。 */
    public static void onWatchdog() {
        MonitorService s = sInstance;
        if (s != null && s.overlayView instanceof QuizOverlayView) {
            ((QuizOverlayView) s.overlayView).recoverIfStuck();
        }
        QuizActivity.recoverStuck();
    }

    private static volatile boolean overlayActive = false;
    private static volatile int displayFailCount = 0; // 弹窗连续发送失败次数(供界面提示权限问题)
    private static volatile long pendingSince = 0;    // 弹题开始等待显示的时刻(elapsedRealtime)
    private static volatile long overlayShownAt = 0;  // 悬浮窗添加时刻(用于识别"被压制不可见")
    private static volatile long quizClosedAt = 0;    // 答题界面关闭时刻(答题期间绝不计时)
    private static volatile long lastOverlayTouch = 0; // 悬浮窗最近一次被点击(作答中不动它)

    public static int getDisplayFailCount() {
        return displayFailCount;
    }

    /** 悬浮窗答题时收到点击(证明悬浮窗可见且有人作答)。 */
    public static void onOverlayTouched() {
        lastOverlayTouch = SystemClock.elapsedRealtime();
    }

    public static boolean isServiceAliveForDiag() {
        return sInstance != null;
    }

    /** 由 QuizActivity 销毁时回调:清除握手标记,tick 会按需重新拉起。 */
    public void onBlockingViewRemoved() {
        quizShowing = false;
    }

    /** 答题/锁定界面被隐藏(如按了主页键):0.6秒内立即重新拉起,不等秒级轮询。 */
    public void onQuizHidden() {
        final Prefs prefs = new Prefs(this);
        boolean lockActive = prefs.getLong(Prefs.KEY_LOCK_UNTIL, 0) > System.currentTimeMillis();
        boolean quizPending = prefs.getInt(Prefs.KEY_QUIZ_PENDING, 0) == 1;
        if ((lockActive || quizPending) && !quizShowing) {
            handler.removeCallbacks(relaunchNow);
            handler.postDelayed(relaunchNow, 600);
        }
    }

    private final Runnable relaunchNow = new Runnable() {
        @Override
        public void run() {
            Prefs prefs = new Prefs(MonitorService.this);
            boolean lockActive = prefs.getLong(Prefs.KEY_LOCK_UNTIL, 0) > System.currentTimeMillis();
            boolean quizPending = prefs.getInt(Prefs.KEY_QUIZ_PENDING, 0) == 1;
            if ((lockActive || quizPending) && !quizShowing && overlayView == null
                    && configUiCount.get() == 0) {
                Log.i(TAG, "界面被隐藏,快速重新拉起");
                ensureBlockingUi(prefs, lockActive);
            }
        }
    };

    // ---- 视图构建(悬浮窗兜底用) ----

    private View buildQuizView(final Prefs prefs) {
        QuizOverlayView v = new QuizOverlayView(this, bank, new QuizOverlayView.Listener() {
            @Override
            public void onPassed() {
                onQuizPassed(MonitorService.this);
            }

            @Override
            public void onMaxWrong() {
                startLock(MonitorService.this);
                // 立刻切到锁定倒计时视图
                Prefs p = new Prefs(MonitorService.this);
                showOverlay(buildLockView(p), true);
            }
        });
        return v;
    }

    private View buildLockView(final Prefs prefs) {
        LockOverlayView v = new LockOverlayView(this, new Runnable() {
            @Override
            public void run() {
                // 倒计时结束
                Prefs p = new Prefs(MonitorService.this);
                p.putLong(Prefs.KEY_LOCK_UNTIL, 0);
                p.putInt(Prefs.KEY_WRONG_STREAK, 0);
                p.putInt(Prefs.KEY_WATCHED, 0);
                sendMediaKey(KeyEvent_PLAY());
                removeOverlay();
                quizShowing = false;
            }
        }, new Runnable() {
            @Override
            public void run() {
                // 用户试图退出锁定视图(无法真正阻止,重新显示)
                handler.postDelayed(new Runnable() {
                    @Override
                    public void run() {
                        Prefs p = new Prefs(MonitorService.this);
                        if (p.getLong(Prefs.KEY_LOCK_UNTIL, 0) > 0 && overlayView == null && !quizShowing) {
                            showOverlay(buildLockView(p), true);
                        }
                    }
                }, 500);
            }
        });
        return v;
    }

    // ---- 媒体键 ----

    private void sendMediaKey(int code) {
        if (code == 0) return;
        try {
            AudioManager am = (AudioManager) getSystemService(Context.AUDIO_SERVICE);
            am.dispatchMediaKeyEvent(new android.view.KeyEvent(
                    android.view.KeyEvent.ACTION_DOWN, code));
            am.dispatchMediaKeyEvent(new android.view.KeyEvent(
                    android.view.KeyEvent.ACTION_UP, code));
        } catch (Exception e) {
            Log.e(TAG, "发送媒体键失败", e);
        }
    }

    private static int KeyEvent_PAUSE() {
        return android.view.KeyEvent.KEYCODE_MEDIA_PAUSE;
    }

    private static int KeyEvent_PLAY() {
        return android.view.KeyEvent.KEYCODE_MEDIA_PLAY;
    }

    // ---- 屏幕 / 前台 ----

    private boolean isScreenOn() {
        PowerManager pm = (PowerManager) getSystemService(Context.POWER_SERVICE);
        return pm.isInteractive();
    }

    private void registerScreenReceiver() {
        receiver = new ScreenReceiver();
        IntentFilter f = new IntentFilter();
        f.addAction(Intent.ACTION_SCREEN_ON);
        f.addAction(Intent.ACTION_SCREEN_OFF);
        registerReceiver(receiver, f);
    }

    /** 供 ScreenReceiver 调用。 */
    public void setScreenOn(boolean on) {
        screenOn = on;
        lastTickRealtime = SystemClock.elapsedRealtime();
    }

    private static final String STATUS_CHANNEL = "guard_status";
    private long lastNotifAt = 0;

    /** 更新常驻通知条幅:实时显示本轮观看时间(参考华为健康的后台常驻做法)。 */
    private void updateStatusNotification(Prefs prefs, int watched, int intervalSec) {
        long now = SystemClock.elapsedRealtime();
        if (now - lastNotifAt < 5000) return; // 5秒刷新一次即可
        lastNotifAt = now;
        try {
            NotificationManager nm =
                    (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
            if (nm == null) return;
            if (Build.VERSION.SDK_INT >= 26) {
                NotificationChannel ch = new NotificationChannel(STATUS_CHANNEL,
                        "守护状态", NotificationManager.IMPORTANCE_LOW);
                ch.setShowBadge(false);
                nm.createNotificationChannel(ch);
            }
            long lockUntil = prefs.getLong(Prefs.KEY_LOCK_UNTIL, 0);
            String title;
            String text;
            if (lockUntil > System.currentTimeMillis()) {
                long remain = (lockUntil - System.currentTimeMillis()) / 1000;
                title = "视界守卫者 · 休息锁定中";
                text = "剩余 " + remain / 60 + " 分 " + remain % 60 + " 秒";
            } else {
                title = "视界守卫者 · 已观看 " + watched / 60 + "分" + String.format("%02d", watched % 60) + "秒";
                int remainSec = Math.max(0, intervalSec - watched);
                text = "距离下次答题还有 " + remainSec / 60 + " 分 " + remainSec % 60 + " 秒";
            }
            Intent it = new Intent(this, MainActivity.class)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            android.app.PendingIntent pi = android.app.PendingIntent.getActivity(
                    this, 3003, it,
                    android.app.PendingIntent.FLAG_UPDATE_CURRENT
                            | android.app.PendingIntent.FLAG_IMMUTABLE);
            Notification.Builder b = Build.VERSION.SDK_INT >= 26
                    ? new Notification.Builder(this, STATUS_CHANNEL)
                    : new Notification.Builder(this).setPriority(Notification.PRIORITY_LOW);
            b.setContentTitle(title)
                    .setContentText(text)
                    .setSmallIcon(android.R.drawable.ic_lock_idle_lock)
                    .setOngoing(true)
                    .setOnlyAlertOnce(true)
                    .setContentIntent(pi);
            nm.notify(1, b.build());
        } catch (Exception e) {
            Log.e(TAG, "update notification failed", e);
        }
    }

    private void startForegroundCompat() {
        NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        if (Build.VERSION.SDK_INT >= 26) {
            NotificationChannel ch = new NotificationChannel(CHANNEL_ID, "防沉迷守护",
                    NotificationManager.IMPORTANCE_MIN);
            nm.createNotificationChannel(ch);
        }
        Notification n;
        if (Build.VERSION.SDK_INT >= 26) {
            n = new Notification.Builder(this, CHANNEL_ID)
                    .setContentTitle("视界守卫者运行中")
                    .setSmallIcon(android.R.drawable.ic_lock_idle_lock)
                    .setOngoing(true)
                    .build();
        } else {
            n = new Notification.Builder(this)
                    .setContentTitle("视界守卫者运行中")
                    .setSmallIcon(android.R.drawable.ic_lock_idle_lock)
                    .setOngoing(true)
                    .build();
        }
        try {
            startForeground(1, n);
        } catch (Exception e) {
            Log.e(TAG, "startForeground 失败", e);
        }
    }
}
