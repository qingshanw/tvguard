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
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.Locale;

/**
 * 锁定倒计时视图(连错 3 题后全屏倒计时)。按秒读取 Prefs.KEY_LOCK_UNTIL,
 * 到时回调 onFinish。用户若按 Home 退出,服务会重新拉起。
 */
public class LockOverlayView extends FrameLayout {

    private final Prefs prefs;
    private final Runnable onFinish;
    private final Runnable onEscapeAttempt;
    private final android.os.Handler handler = new android.os.Handler();
    private TextView tvTime;
    private LinearLayout unlockBox;
    private android.widget.EditText unlockInput;
    private TextView unlockError;
    private boolean done;

    private final Runnable tick = new Runnable() {
        @Override
        public void run() {
            if (done) return;
            long until = prefs.getLong(Prefs.KEY_LOCK_UNTIL, 0);
            long remainMs = until - System.currentTimeMillis();
            if (remainMs <= 0) {
                done = true;
                onFinish.run();
                return;
            }
            long s = (remainMs + 999) / 1000;
            tvTime.setText(String.format(Locale.getDefault(), "%02d:%02d", s / 60, s % 60));
            handler.postDelayed(this, 500);
        }
    };

    public LockOverlayView(Context context, Runnable onFinish, Runnable onEscapeAttempt) {
        super(context);
        this.prefs = new Prefs(context);
        this.onFinish = onFinish;
        this.onEscapeAttempt = onEscapeAttempt;
        setBackgroundColor(Color.parseColor("#3E2723"));
        buildUi();
        handler.post(tick);
    }

    private void buildUi() {
        LinearLayout root = new LinearLayout(getContext());
        root.setOrientation(LinearLayout.VERTICAL);
        root.setGravity(Gravity.CENTER);
        int pad = dp(32);
        root.setPadding(pad, pad, pad, pad);

        TextView t1 = new TextView(getContext());
        t1.setText(R.string.lock_title);
        t1.setTextSize(TypedValue.COMPLEX_UNIT_PX, UiUtil.px(getContext(), UiUtil.TEXT_TITLE));
        t1.setTextColor(Color.WHITE);
        t1.setGravity(Gravity.CENTER);
        root.addView(t1);

        TextView t2 = new TextView(getContext());
        t2.setText(R.string.lock_hint);
        t2.setTextSize(TypedValue.COMPLEX_UNIT_PX, UiUtil.px(getContext(), UiUtil.TEXT_OPTION));
        t2.setTextColor(Color.parseColor("#BCAAA4"));
        t2.setGravity(Gravity.CENTER);
        t2.setPadding(0, dp(20), 0, dp(20));
        root.addView(t2);

        tvTime = new TextView(getContext());
        tvTime.setTextSize(TypedValue.COMPLEX_UNIT_PX, UiUtil.px(getContext(), UiUtil.TEXT_TIMER));
        tvTime.setTextColor(Color.parseColor("#FF8A65"));
        tvTime.setGravity(Gravity.CENTER);
        root.addView(tvTime);

        root.addView(buildUnlockUi());

        addView(root, new LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
    }

    /** 家长解锁区:默认只显示一个小按钮,点开后内嵌密码输入(输入框内嵌确认键)。 */
    private LinearLayout buildUnlockUi() {
        LinearLayout box = new LinearLayout(getContext());
        box.setOrientation(LinearLayout.VERTICAL);
        box.setGravity(Gravity.CENTER_HORIZONTAL);
        LinearLayout.LayoutParams bp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        bp.topMargin = dp(28);
        box.setLayoutParams(bp);

        final android.widget.Button unlockBtn = new android.widget.Button(getContext(),
                null, android.R.attr.borderlessButtonStyle);
        unlockBtn.setText("家长解锁");
        unlockBtn.setTextSize(TypedValue.COMPLEX_UNIT_PX, UiUtil.px(getContext(), UiUtil.TEXT_LABEL));
        unlockBtn.setTextColor(Color.parseColor("#A1887F"));
        unlockBtn.setAllCaps(false);
        unlockBtn.setIncludeFontPadding(false);
        unlockBtn.setPadding(dp(20), dp(8), dp(20), dp(8));
        android.graphics.drawable.GradientDrawable btnBg =
                new android.graphics.drawable.GradientDrawable();
        btnBg.setCornerRadius(dp(20));
        btnBg.setColor(Color.parseColor("#4E342E"));
        unlockBtn.setBackground(btnBg);
        unlockBtn.setFocusable(true);
        box.addView(unlockBtn);

        unlockBox = new LinearLayout(getContext());
        unlockBox.setOrientation(LinearLayout.HORIZONTAL);
        unlockBox.setGravity(Gravity.CENTER_VERTICAL);
        android.graphics.drawable.GradientDrawable boxBg =
                new android.graphics.drawable.GradientDrawable();
        boxBg.setCornerRadius(dp(10));
        boxBg.setColor(Color.parseColor("#5D4037"));
        boxBg.setStroke(2, Color.parseColor("#FF8A65"));
        unlockBox.setBackground(boxBg);
        int inPad = dp(12);
        unlockBox.setPadding(inPad, dp(6), dp(6), dp(6));
        unlockBox.setVisibility(GONE);

        unlockInput = new android.widget.EditText(getContext());
        unlockInput.setHint("家长密码");
        unlockInput.setInputType(android.text.InputType.TYPE_CLASS_NUMBER
                | android.text.InputType.TYPE_NUMBER_VARIATION_PASSWORD);
        unlockInput.setTextSize(TypedValue.COMPLEX_UNIT_PX,
                UiUtil.px(getContext(), UiUtil.TEXT_OPTION));
        unlockInput.setTextColor(Color.WHITE);
        unlockInput.setHintTextColor(Color.parseColor("#8D6E63"));
        unlockInput.setGravity(Gravity.CENTER_VERTICAL);
        unlockInput.setBackground(null);
        unlockInput.setPadding(dp(8), 0, dp(8), 0);
        unlockInput.setSingleLine(true);
        unlockBox.addView(unlockInput, new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.MATCH_PARENT, 1f));

        android.widget.Button ok = new android.widget.Button(getContext(),
                null, android.R.attr.borderlessButtonStyle);
        ok.setText("解锁");
        ok.setTextSize(TypedValue.COMPLEX_UNIT_PX, UiUtil.px(getContext(), UiUtil.TEXT_LABEL));
        ok.setTextColor(Color.WHITE);
        ok.setAllCaps(false);
        ok.setIncludeFontPadding(false);
        // 压掉 Button 主题自带的 minHeight/minWidth(88dip)
        ok.setMinimumHeight(0);
        ok.setMinimumWidth(0);
        ok.setPadding(dp(14), dp(3), dp(14), dp(3));
        android.graphics.drawable.GradientDrawable okBg =
                new android.graphics.drawable.GradientDrawable();
        okBg.setCornerRadius(dp(8));
        okBg.setColor(Color.parseColor("#2E63E7"));
        ok.setBackground(okBg);
        ok.setOnClickListener(new OnClickListener() {
            @Override
            public void onClick(View v) {
                tryUnlock();
            }
        });
        unlockInput.setOnEditorActionListener(new android.widget.TextView.OnEditorActionListener() {
            @Override
            public boolean onEditorAction(android.widget.TextView v, int actionId,
                                          android.view.KeyEvent event) {
                tryUnlock();
                return true;
            }
        });
        // 显式固定高度,彻底不受 Button 主题 minHeight 影响
        unlockBox.addView(ok, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, dp(44)));
        // 输入框整体定高(约按钮的1.6倍),比例与密码页一致
        box.addView(unlockBox, new LinearLayout.LayoutParams(dp(680), dp(72)));

        unlockError = new TextView(getContext());
        unlockError.setTextColor(Color.parseColor("#E57373"));
        unlockError.setTextSize(TypedValue.COMPLEX_UNIT_PX, UiUtil.px(getContext(), UiUtil.TEXT_LABEL));
        unlockError.setGravity(Gravity.CENTER);
        unlockError.setPadding(0, dp(8), 0, 0);
        unlockError.setVisibility(GONE);
        box.addView(unlockError);

        unlockBtn.setOnClickListener(new OnClickListener() {
            @Override
            public void onClick(View v) {
                boolean show = unlockBox.getVisibility() != VISIBLE;
                unlockBox.setVisibility(show ? VISIBLE : GONE);
                unlockError.setVisibility(GONE);
                if (show) unlockInput.requestFocus();
            }
        });
        return box;
    }

    /** 密码正确:结束锁定,走与自然到期相同的"进入下一轮"回调。 */
    private void tryUnlock() {
        if (prefs.checkPassword(unlockInput.getText().toString())) {
            done = true;
            handler.removeCallbacks(tick);
            onFinish.run();
        } else {
            unlockError.setText("密码错误");
            unlockError.setVisibility(VISIBLE);
            unlockInput.setText("");
        }
    }

    @Override
    protected void onDetachedFromWindow() {
        done = true;
        handler.removeCallbacks(tick);
        super.onDetachedFromWindow();
    }

    /** 返回键等退出尝试时由宿主调用,通知服务重新拉起。 */
    public void notifyEscape() {
        if (onEscapeAttempt != null) onEscapeAttempt.run();
    }

    /** 拦截返回键。 */
    @Override
    public boolean dispatchKeyEventPreIme(android.view.KeyEvent event) {
        if (event.getKeyCode() == android.view.KeyEvent.KEYCODE_BACK) {
            notifyEscape();
            return true;
        }
        return super.dispatchKeyEventPreIme(event);
    }

    private int dp(int v) {
        return UiUtil.px(getContext(), v);
    }
}
