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
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.text.InputType;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;

/**
 * 答题视图(全屏不透明)。QuizActivity 与悬浮窗兜底共用。
 *  - 答对/答错播放提示音并记录统计
 *  - 答对 QuestionStats.RETIRE_AFTER 次的题目自动"毕业"不再出现
 *  - 所有题目毕业后,需家长在本界面输入密码解锁重置
 */
public class QuizOverlayView extends android.widget.FrameLayout {

    public interface Listener {
        void onPassed();

        void onMaxWrong();
    }

    /** 播放提示音后到界面切换前的等待时长:确保声音完整播放完。 */
    private static final long TRANSITION_DELAY_MS = 2000;

    private final QuestionBank bank;
    private final Listener listener;
    private final Prefs prefs;

    private TextView tvTitle;
    private TextView tvQuestion;
    private TextView tvFeedback;
    private LinearLayout optionBox;
    private final java.util.List<Button> optionButtons = new java.util.ArrayList<>();
    private QuestionBank.Question current;
    private Runnable pendingTransition;      // 待执行的切换(下一题/通过/锁定)
    private long transitionDueAt;            // 切换应执行的时刻
    private boolean transitionQueued;        // 防止重复提交
    private final android.os.Handler uiHandler =
            new android.os.Handler(android.os.Looper.getMainLooper());
    private Runnable guardedTransition;      // 本轮的守护切换(防重复执行)

    public QuizOverlayView(Context context, QuestionBank bank, Listener listener) {
        super(context);
        this.bank = bank;
        this.listener = listener;
        this.prefs = new Prefs(context);
        setBackgroundColor(Color.parseColor("#0B1526"));
        buildUi();
        nextQuestion();
    }

    private void buildUi() {
        LinearLayout root = new LinearLayout(getContext());
        root.setOrientation(LinearLayout.VERTICAL);
        root.setGravity(Gravity.CENTER);
        int pad = dp(32);
        root.setPadding(pad, pad * 2, pad, pad);

        tvTitle = new TextView(getContext());
        tvTitle.setText(R.string.quiz_title);
        tvTitle.setTextSize(TypedValue.COMPLEX_UNIT_PX, UiUtil.px(getContext(), UiUtil.TEXT_TITLE));
        tvTitle.setTextColor(Color.WHITE);
        tvTitle.setGravity(Gravity.CENTER);
        root.addView(tvTitle, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        tvQuestion = new TextView(getContext());
        tvQuestion.setTextSize(TypedValue.COMPLEX_UNIT_PX, UiUtil.px(getContext(), UiUtil.TEXT_QUESTION));
        tvQuestion.setTextColor(Color.WHITE);
        tvQuestion.setGravity(Gravity.CENTER);
        tvQuestion.setPadding(0, dp(24), 0, dp(24));
        // 显式占满宽度,长题目按可用宽度自动换行(也保留题库中的手动换行)
        root.addView(tvQuestion, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        tvFeedback = new TextView(getContext());
        tvFeedback.setTextSize(TypedValue.COMPLEX_UNIT_PX, UiUtil.px(getContext(), UiUtil.TEXT_OPTION));
        tvFeedback.setTextColor(Color.parseColor("#FFD54F"));
        tvFeedback.setGravity(Gravity.CENTER);
        tvFeedback.setVisibility(INVISIBLE);
        root.addView(tvFeedback, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        optionBox = new LinearLayout(getContext());
        optionBox.setOrientation(LinearLayout.VERTICAL);
        optionBox.setGravity(Gravity.CENTER_HORIZONTAL);
        LinearLayout.LayoutParams obLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        obLp.topMargin = dp(16);
        optionBox.setLayoutParams(obLp);
        root.addView(optionBox);

        // ScrollView 包一层,极宽/矮屏幕上内容过多时可滚动,不会跑出屏幕
        android.widget.ScrollView scroll = new android.widget.ScrollView(getContext());
        scroll.setFillViewport(true);
        scroll.setVerticalScrollBarEnabled(false);
        scroll.addView(root, new LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        addView(scroll, new LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
    }

    private void nextQuestion() {
        current = bank.nextQuestion();
        pendingTransition = null;
        transitionQueued = false;
        tvFeedback.setVisibility(INVISIBLE);
        if (current == null) {
            // 没有可出的题(全部毕业 或 题库被删空):一律锁定,只有家长密码能解锁
            optionBox.removeAllViews();
            showGraduated();
            return;
        }
        tvTitle.setText(R.string.quiz_title);
        tvQuestion.setText(current.q);
        optionBox.removeAllViews();
        optionButtons.clear();
        char label = 'A';
        for (int i = 0; i < current.options.length; i++) {
            final int idx = i;
            Button b = new Button(getContext(), null, android.R.attr.borderlessButtonStyle);
            b.setText(label++ + ". " + current.options[i]);
            b.setTextSize(TypedValue.COMPLEX_UNIT_PX, UiUtil.px(getContext(), UiUtil.TEXT_OPTION));
            b.setTextColor(Color.WHITE);
            b.setAllCaps(false);
            b.setIncludeFontPadding(false);
            b.setPadding(dp(24), dp(12), dp(24), dp(12));
            b.setMinimumHeight(dp(72));
            b.setBackground(optionBg(false));
            // 触屏(手机):单击选项=直接作答,不引入"先聚焦再点击"的两段式;
            // 遥控器(电视,非触摸模式):focusable 即可被焦点定位,按 OK/确认键才触发点击作答
            b.setFocusable(true);
            b.setOnClickListener(new OnClickListener() {
                @Override
                public void onClick(View v) {
                    submit(idx);
                }
            });
            b.setOnFocusChangeListener(new OnFocusChangeListener() {
                @Override
                public void onFocusChange(View v, boolean hasFocus) {
                    v.setBackground(optionBg(hasFocus));
                }
            });
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    dp(680), ViewGroup.LayoutParams.WRAP_CONTENT);
            lp.topMargin = dp(14);
            optionBox.addView(b, lp);
            optionButtons.add(b);
        }
        optionBox.post(new Runnable() {
            @Override
            public void run() {
                // 仅非触摸模式(遥控器)自动聚焦第一项,方便直接用方向键;触屏不需要
                if (!isInTouchMode()) {
                    View first = optionBox.getChildAt(0);
                    if (first != null) first.requestFocus();
                }
            }
        });
    }

    /** 所有题目已毕业:显示家长密码解锁界面。 */
    private void showGraduated() {
        if (bank.allGraduated) {
            tvTitle.setText("🎉 全部题目都答完啦!");
            tvQuestion.setText("所有题目都已被你成功答对 5 次,暂时没有新题目了。\n请家长输入密码重置题目后才能继续观看。");
        } else {
            tvTitle.setText("⏰ 观看时间到!");
            tvQuestion.setText("当前没有可回答的题目(题库为空或已被删除)。\n观看已锁定,请家长输入密码解锁,并导入题库。");
        }
        tvFeedback.setVisibility(INVISIBLE);

        final EditText pwd = new EditText(getContext());
        pwd.setHint("家长密码(4-8位数字)");
        pwd.setInputType(InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_VARIATION_PASSWORD);
        pwd.setTextSize(TypedValue.COMPLEX_UNIT_PX, UiUtil.px(getContext(), UiUtil.TEXT_OPTION));
        pwd.setTextColor(Color.WHITE);
        pwd.setHintTextColor(Color.parseColor("#607D8B"));
        pwd.setGravity(Gravity.CENTER);
        pwd.setIncludeFontPadding(false);
        pwd.setPadding(dp(16), dp(14), dp(16), dp(14));
        GradientDrawable inBg = new GradientDrawable();
        inBg.setCornerRadius(dp(12));
        inBg.setColor(Color.parseColor("#14263F"));
        inBg.setStroke(dp(2), Color.parseColor("#4FC3F7"));
        pwd.setBackground(inBg);
        pwd.setFocusable(true);
        pwd.setFocusableInTouchMode(true);
        LinearLayout.LayoutParams pwLp = new LinearLayout.LayoutParams(dp(680), ViewGroup.LayoutParams.WRAP_CONTENT);
        pwLp.topMargin = dp(10);
        optionBox.addView(pwd, pwLp);

        Button unlock = new Button(getContext(), null, android.R.attr.borderlessButtonStyle);
        unlock.setText("家长解锁,重置题目");
        unlock.setTextSize(TypedValue.COMPLEX_UNIT_PX, UiUtil.px(getContext(), UiUtil.TEXT_OPTION));
        unlock.setTextColor(Color.WHITE);
        unlock.setAllCaps(false);
        unlock.setIncludeFontPadding(false);
        unlock.setPadding(dp(20), dp(12), dp(20), dp(12));
        unlock.setMinimumHeight(dp(64));
        GradientDrawable uBg = new GradientDrawable();
        uBg.setCornerRadius(dp(12));
        uBg.setColor(Color.parseColor("#2E63E7"));
        unlock.setBackground(uBg);
        unlock.setFocusable(true);
        LinearLayout.LayoutParams uLp = new LinearLayout.LayoutParams(dp(680), ViewGroup.LayoutParams.WRAP_CONTENT);
        uLp.topMargin = dp(14);
        optionBox.addView(unlock, uLp);
        unlock.setOnClickListener(new OnClickListener() {
            @Override
            public void onClick(View v) {
                tryUnlock(pwd.getText().toString());
            }
        });
        pwd.requestFocus();
    }

    private void tryUnlock(String input) {
        if (prefs.checkPassword(input)) {
            bank.stats().resetAll();
            bank.load(getContext());
            if (bank.nextQuestion() == null) {
                // 解锁成功但题库仍为空:提示导入,保持密码框
                tvQuestion.setText("解锁成功,但当前题库为空。\n请家长通过 U盘 或 局域网 导入题库后重试。");
                return;
            }
            tvTitle.setText(R.string.quiz_title);
            nextQuestion();
        } else {
            tvFeedback.setText("❌ 密码错误,请重试");
            tvFeedback.setTextColor(Color.parseColor("#E57373"));
            tvFeedback.setVisibility(VISIBLE);
            SoundUtil.wrong(getContext());
        }
    }

    /**
     * 记录待执行切换:
     *  - 用主线程 Handler 而非 View.postDelayed —— 视图未附着时后者永不执行(偶发卡住的根因)
     *  - 到期后 2.5 秒再兜底触发一次,防止个别 ROM 丢消息
     *  - 进程被冻结错过时由看门狗 recoverIfStuck 补执行
     */
    private void scheduleTransition(final Runnable r, long delayMs) {
        pendingTransition = r;
        transitionDueAt = android.os.SystemClock.elapsedRealtime() + delayMs;
        transitionQueued = true;

        guardedTransition = new Runnable() {
            @Override
            public void run() {
                if (!transitionQueued) return;
                transitionQueued = false;
                pendingTransition = null;
                try {
                    r.run();
                } catch (Throwable ignored) {
                }
            }
        };
        uiHandler.postDelayed(guardedTransition, delayMs);
        final Runnable backup = guardedTransition;
        uiHandler.postDelayed(new Runnable() {
            @Override
            public void run() {
                if (transitionQueued) backup.run();
            }
        }, delayMs + 2500);
    }

    /** 看门狗调用:切换已逾期未执行(进程曾被冻结)则立即补执行,界面自愈。 */
    public void recoverIfStuck() {
        if (transitionQueued && pendingTransition != null
                && android.os.SystemClock.elapsedRealtime() >= transitionDueAt) {
            if (guardedTransition != null) {
                uiHandler.removeCallbacks(guardedTransition);
                guardedTransition.run(); // 内部会检查 transitionQueued 并执行原切换
            } else {
                transitionQueued = false;
                Runnable r = pendingTransition;
                pendingTransition = null;
                if (r != null) {
                    try {
                        r.run();
                    } catch (Throwable ignored) {
                    }
                }
            }
        }
    }

    private void submit(int idx) {
        MonitorService.onOverlayTouched();
        if (current == null || transitionQueued) return; // 防重复提交
        boolean correct = idx == current.answer;
        bank.stats().record(current.id, correct);
        // 判色反馈:正确项变绿,错选项变红,其余保持原色
        for (int i = 0; i < optionButtons.size(); i++) {
            Button b = optionButtons.get(i);
            b.setEnabled(false);
            if (i == current.answer) {
                b.setBackground(flatBg("#2E7D32", "#FFFFFF")); // 绿:正确
            } else if (i == idx) {
                b.setBackground(flatBg("#C62828", "#FFFFFF")); // 红:错选
            }
        }
        if (correct) {
            SoundUtil.correct(getContext());
            prefs.putInt(Prefs.KEY_WRONG_STREAK, 0);
            tvFeedback.setText(R.string.answer_correct);
            tvFeedback.setTextColor(Color.parseColor("#81C784"));
            tvFeedback.setVisibility(VISIBLE);
            scheduleTransition(new Runnable() {
                @Override
                public void run() {
                    listener.onPassed();
                }
            }, TRANSITION_DELAY_MS);
        } else {
            SoundUtil.wrong(getContext());
            int streak = prefs.getInt(Prefs.KEY_WRONG_STREAK, 0) + 1;
            int maxWrong = prefs.maxWrong();
            boolean lock = streak >= maxWrong;
            if (lock) streak = 0; // 进入锁定后清零
            prefs.putInt(Prefs.KEY_WRONG_STREAK, streak);

            tvFeedback.setText(getContext().getString(R.string.answer_wrong,
                    maxWrong - prefs.getInt(Prefs.KEY_WRONG_STREAK, 0)));
            tvFeedback.setTextColor(Color.parseColor("#E57373"));
            tvFeedback.setVisibility(VISIBLE);

            if (lock) {
                scheduleTransition(new Runnable() {
                    @Override
                    public void run() {
                        listener.onMaxWrong();
                    }
                }, TRANSITION_DELAY_MS);
            } else {
                scheduleTransition(new Runnable() {
                    @Override
                    public void run() {
                        nextQuestion();
                    }
                }, TRANSITION_DELAY_MS);
            }
        }
    }

    private GradientDrawable flatBg(String fill, String stroke) {
        GradientDrawable d = new GradientDrawable();
        d.setCornerRadius(dp(10));
        d.setColor(Color.parseColor(fill));
        d.setStroke(dp(2), Color.parseColor(stroke));
        return d;
    }

    private GradientDrawable optionBg(boolean focused) {
        GradientDrawable d = new GradientDrawable();
        d.setCornerRadius(dp(10));
        if (focused) {
            d.setColor(Color.parseColor("#3D5AFE"));
            d.setStroke(dp(2), Color.WHITE);
        } else {
            d.setColor(Color.parseColor("#1B2C4C"));
            d.setStroke(dp(1), Color.parseColor("#4A5C86"));
        }
        return d;
    }

    private int dp(int v) {
        return UiUtil.px(getContext(), v);
    }

    /** 拦截返回键,防止答题中途退出。 */
    @Override
    public boolean dispatchKeyEventPreIme(android.view.KeyEvent event) {
        if (event.getKeyCode() == android.view.KeyEvent.KEYCODE_BACK) return true;
        return super.dispatchKeyEventPreIme(event);
    }
}
