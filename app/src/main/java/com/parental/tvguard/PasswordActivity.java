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
import android.graphics.Color;
import android.util.TypedValue;
import android.os.Bundle;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

/**
 * 家长密码:验证(MODE_VERIFY)/ 首次设置(MODE_SET)/ 修改(MODE_CHANGE)。
 */
public class PasswordActivity extends Activity {

    public static final String EXTRA_MODE = "mode";
    public static final int MODE_VERIFY = 0;
    public static final int MODE_SET = 1;
    public static final int MODE_CHANGE = 2;

    private int mode;
    private EditText input;
    private EditText inputConfirm;
    private EditText inputOld;
    private TextView tvError;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        mode = getIntent().getIntExtra(EXTRA_MODE, MODE_VERIFY);

        Prefs prefs = new Prefs(this);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setGravity(Gravity.CENTER);
        int pad = dp(40);
        root.setPadding(pad, pad, pad, pad);
        root.setBackgroundColor(Color.parseColor("#12203A"));

        TextView title = new TextView(this);
        title.setText(mode == MODE_VERIFY ? "请输入家长密码"
                : mode == MODE_SET ? "首次使用,请设置家长密码"
                : "输入旧密码,并设置新密码");
        title.setTextSize(TypedValue.COMPLEX_UNIT_PX, UiUtil.px(this, UiUtil.TEXT_TITLE));
        title.setTextColor(Color.WHITE);
        title.setGravity(Gravity.CENTER);
        root.addView(title, matchWrap(dp(0), dp(20)));

        if (mode == MODE_CHANGE) {
            inputOld = new EditText(this);
            inputOld.setHint("旧密码");
            inputOld.setInputType(android.text.InputType.TYPE_CLASS_NUMBER
                    | android.text.InputType.TYPE_NUMBER_VARIATION_PASSWORD);
            styleInput(inputOld);
            root.addView(inputOld, matchWrap(dp(0), dp(12)));
        }

        if (mode == MODE_CHANGE) {
            inputConfirm = new EditText(this);
            inputConfirm.setHint("再次输入新密码");
            inputConfirm.setInputType(android.text.InputType.TYPE_CLASS_NUMBER
                    | android.text.InputType.TYPE_NUMBER_VARIATION_PASSWORD);
            styleInput(inputConfirm);
            root.addView(inputConfirm, matchWrap(dp(0), dp(12)));
        }

        // 密码输入框:确认键内嵌在输入框右端,视觉一体(键盘回车/遥控器OK仍然可用)
        LinearLayout inputRow = new LinearLayout(this);
        inputRow.setOrientation(LinearLayout.HORIZONTAL);
        inputRow.setGravity(Gravity.CENTER_VERTICAL);
        android.graphics.drawable.GradientDrawable boxBg =
                new android.graphics.drawable.GradientDrawable();
        boxBg.setCornerRadius(dp(10));
        boxBg.setColor(Color.parseColor("#1E3257"));
        boxBg.setStroke(2, Color.parseColor("#3D5AFE"));
        inputRow.setBackground(boxBg);
        inputRow.setPadding(dp(2), 0, dp(6), 0); // 框高由输入框自然高度决定,同旧版

        input = new EditText(this);
        input.setHint(mode == MODE_VERIFY ? "密码" : "新密码(4-8位数字)");
        input.setInputType(android.text.InputType.TYPE_CLASS_NUMBER
                | android.text.InputType.TYPE_NUMBER_VARIATION_PASSWORD);
        // styleInput 与旧版完全一致(文字尺寸+按屏幕密度的内边距),高度自然等同旧版
        styleInput(input);
        input.setBackground(null); // 背景移到外层容器,本体透明
        inputRow.addView(input, new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.MATCH_PARENT, 1f));

        android.widget.Button ok = new android.widget.Button(this,
                null, android.R.attr.borderlessButtonStyle);
        ok.setText("确认");
        ok.setTextSize(TypedValue.COMPLEX_UNIT_PX, UiUtil.px(this, UiUtil.TEXT_LABEL));
        ok.setTextColor(Color.WHITE);
        ok.setAllCaps(false);
        ok.setIncludeFontPadding(false);
        // 压掉 Button 主题自带的 minHeight/minWidth(88dip),否则会和输入框一样高
        ok.setMinimumHeight(0);
        ok.setMinimumWidth(0);
        ok.setPadding(dp(14), 0, dp(14), 0);
        android.graphics.drawable.GradientDrawable okBg =
                new android.graphics.drawable.GradientDrawable();
        okBg.setCornerRadius(dp(8));
        okBg.setColor(Color.parseColor("#2E63E7"));
        ok.setBackground(okBg);
        ok.setFocusable(true);
        ok.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                submit();
            }
        });
        // 显式固定高度,彻底不受 Button 主题 minHeight 影响
        inputRow.addView(ok, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, dp(44)));
        // 框高=输入框自然高度(与旧版一致,WRAP_CONTENT)
        root.addView(inputRow, matchWrap(dp(0), dp(12)));

        tvError = new TextView(this);
        tvError.setTextColor(Color.parseColor("#E57373"));
        tvError.setTextSize(TypedValue.COMPLEX_UNIT_PX, UiUtil.px(this, UiUtil.TEXT_LABEL));
        tvError.setGravity(Gravity.CENTER);
        root.addView(tvError, matchWrap(dp(0), dp(12)));

        TextView hint = new TextView(this);
        hint.setText("输入完成后点「确认」,或按遥控器 OK / 回车");
        hint.setTextColor(Color.parseColor("#78909C"));
        hint.setTextSize(TypedValue.COMPLEX_UNIT_PX, UiUtil.px(this, UiUtil.TEXT_LABEL));
        hint.setGravity(Gravity.CENTER);
        root.addView(hint, matchWrap(dp(0), dp(8)));

        setContentView(root);

        input.setOnEditorActionListener(new TextView.OnEditorActionListener() {
            @Override
            public boolean onEditorAction(TextView v, int actionId, KeyEvent event) {
                submit();
                return true;
            }
        });
        if (inputOld != null) {
            inputOld.requestFocus();
        } else {
            input.requestFocus();
        }

        // 首次设置时给出默认说明
        if (mode == MODE_SET) {
            Toast.makeText(this, "请牢记家长密码,忘记密码只能卸载重装", Toast.LENGTH_LONG).show();
        }
        if (mode == MODE_VERIFY && !prefs.hasPassword()) {
            // 异常兜底:无密码却要求验证,直接放行
            setResult(RESULT_OK);
            finish();
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        MonitorService.enterConfigUi(); // 家长在操作,暂停计时
    }

    @Override
    protected void onPause() {
        super.onPause();
        MonitorService.exitConfigUi();
    }

    private void submit() {
        Prefs prefs = new Prefs(this);
        String val = input.getText().toString();
        if (mode == MODE_VERIFY) {
            if (prefs.checkPassword(val)) {
                setResult(RESULT_OK);
                finish();
            } else {
                tvError.setText("密码错误,请重试");
                input.setText("");
            }
        } else if (mode == MODE_SET) {
            if (val.length() < 4 || val.length() > 8) {
                tvError.setText("密码需为 4-8 位数字");
                return;
            }
            prefs.setPassword(val);
            Toast.makeText(this, "密码已设置", Toast.LENGTH_SHORT).show();
            setResult(RESULT_OK);
            finish();
        } else { // MODE_CHANGE
            String oldVal = inputOld.getText().toString();
            if (!prefs.checkPassword(oldVal)) {
                tvError.setText("旧密码错误");
                return;
            }
            if (val.length() < 4 || val.length() > 8) {
                tvError.setText("新密码需为 4-8 位数字");
                return;
            }
            if (inputConfirm != null && !val.equals(inputConfirm.getText().toString())) {
                tvError.setText("两次输入的新密码不一致,请重新输入");
                input.setText("");
                inputConfirm.setText("");
                return;
            }
            prefs.setPassword(val);
            Toast.makeText(this, "密码已修改", Toast.LENGTH_SHORT).show();
            finish();
        }
    }

    private void styleInput(EditText e) {
        e.setTextSize(TypedValue.COMPLEX_UNIT_PX, UiUtil.px(this, UiUtil.TEXT_OPTION));
        e.setTextColor(Color.WHITE);
        e.setHintTextColor(Color.parseColor("#607D8B"));
        e.setGravity(Gravity.CENTER);
        GradientDrawableHelper.apply(e, dp(10), "#1E3257");
    }

    private LinearLayout.LayoutParams matchWrap(int w, int topMargin) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = topMargin;
        return lp;
    }

    private int dp(int v) {
        return UiUtil.px(this, v);
    }

    /** 简单圆角背景工具。 */
    private static final class GradientDrawableHelper {
        static void apply(android.view.View v, int radiusPx, String color) {
            android.graphics.drawable.GradientDrawable d = new android.graphics.drawable.GradientDrawable();
            d.setCornerRadius(radiusPx);
            d.setColor(Color.parseColor(color));
            d.setStroke(2, Color.parseColor("#3D5AFE"));
            v.setBackground(d);
            v.setPadding(v.getPaddingLeft(), dp2(v) / 2, v.getPaddingRight(), dp2(v) / 2);
        }

        static int dp2(android.view.View v) {
            return (int) (24 * v.getResources().getDisplayMetrics().density + 0.5f);
        }
    }
}
