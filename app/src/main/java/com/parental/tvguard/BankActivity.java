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
import android.content.Intent;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

/**
 * 题库管理:内嵌四年级题库(可删除/恢复)、查看已导入题库、删除、从U盘导入。
 */
public class BankActivity extends Activity {

    private static final int REQ_IMPORT = 3;

    private LinearLayout listBox;
    private TextView tvSummary;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        buildUi();
    }

    @Override
    protected void onResume() {
        super.onResume();
        MonitorService.enterConfigUi(); // 家长在配置,暂停计时
        refresh();
    }

    @Override
    protected void onPause() {
        super.onPause();
        MonitorService.exitConfigUi();
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQ_IMPORT && resultCode == RESULT_OK
                && data != null && data.getData() != null) {
            final android.net.Uri uri = data.getData();
            new Thread(new Runnable() {
                @Override
                public void run() {
                    try {
                        java.io.InputStream is = getContentResolver().openInputStream(uri);
                        java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream();
                        byte[] buf = new byte[8192];
                        int n;
                        while ((n = is.read(buf)) > 0) bos.write(buf, 0, n);
                        is.close();
                        String json = new String(bos.toByteArray(),
                                java.nio.charset.StandardCharsets.UTF_8);
                        if (json.startsWith("\uFEFF")) json = json.substring(1);
                        final int count = QuestionBank.importBank(BankActivity.this,
                                "usb_" + System.currentTimeMillis() + ".json", json);
                        MonitorService.reloadBank(BankActivity.this);
                        runOnUiThread(new Runnable() {
                            @Override
                            public void run() {
                                Toast.makeText(BankActivity.this,
                                        "导入成功,共 " + count + " 题", Toast.LENGTH_LONG).show();
                                refresh();
                            }
                        });
                    } catch (final Exception e) {
                        runOnUiThread(new Runnable() {
                            @Override
                            public void run() {
                                Toast.makeText(BankActivity.this,
                                        "导入失败:" + e.getMessage(), Toast.LENGTH_LONG).show();
                            }
                        });
                    }
                }
            }).start();
        }
    }


    /** 顶部栏:返回按钮 + 居中标题。返回后主界面会重新要求家长密码。 */
    private View topBar(String title) {
        LinearLayout bar = new LinearLayout(this);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setGravity(Gravity.CENTER_VERTICAL);

        Button back = new Button(this, null, android.R.attr.borderlessButtonStyle);
        back.setText("← 返回");
        back.setTextSize(TypedValue.COMPLEX_UNIT_PX, dp(26));
        back.setTextColor(Color.WHITE);
        back.setAllCaps(false);
        back.setIncludeFontPadding(false);
        back.setPadding(dp(20), dp(10), dp(20), dp(10));
        back.setMinimumHeight(dp(56));
        GradientDrawable bg = new GradientDrawable();
        bg.setCornerRadius(dp(28));
        bg.setColor(Color.parseColor("#233B60"));
        back.setBackground(bg);
        back.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                finish();
            }
        });
        bar.addView(back, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        TextView t = new TextView(this);
        t.setText(title);
        t.setTextSize(TypedValue.COMPLEX_UNIT_PX, dp(38));
        t.setTextColor(Color.WHITE);
        t.setTypeface(null, Typeface.BOLD);
        t.setGravity(Gravity.CENTER);
        t.setLayoutParams(new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        bar.addView(t);

        View space = new View(this);
        bar.addView(space, new LinearLayout.LayoutParams(dp(150), dp(1)));
        return bar;
    }

    private void buildUi() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(Color.parseColor(MainActivity.BG));
        int pad = dp(24);
        root.setPadding(pad, dp(12), pad, pad);

        root.addView(topBar("📚 题库管理"), new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        tvSummary = new TextView(this);
        tvSummary.setTextColor(Color.parseColor(MainActivity.ACCENT_CYAN));
        tvSummary.setTextSize(TypedValue.COMPLEX_UNIT_PX, dp(26));
        tvSummary.setGravity(Gravity.CENTER);
        tvSummary.setPadding(0, dp(8), 0, dp(8));
        root.addView(tvSummary, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        Button importBtn = new Button(this, null, android.R.attr.borderlessButtonStyle);
        importBtn.setText("从 U盘 / 本地导入题库(JSON)");
        importBtn.setTextColor(Color.WHITE);
        importBtn.setAllCaps(false);
        importBtn.setIncludeFontPadding(false);
        importBtn.setPadding(dp(20), dp(12), dp(20), dp(12));
        importBtn.setMinimumHeight(dp(56));
        importBtn.setBackground(roundBg(MainActivity.ACCENT, dp(12)));
        importBtn.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                try {
                    Intent it = new Intent(Intent.ACTION_OPEN_DOCUMENT);
                    it.addCategory(Intent.CATEGORY_OPENABLE);
                    it.setType("*/*");
                    startActivityForResult(it, REQ_IMPORT);
                } catch (Exception e) {
                    Toast.makeText(BankActivity.this, "无法打开文件选择器", Toast.LENGTH_LONG).show();
                }
            }
        });
        LinearLayout.LayoutParams ip = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        ip.topMargin = dp(6);
        root.addView(importBtn, ip);

        listBox = new LinearLayout(this);
        listBox.setOrientation(LinearLayout.VERTICAL);

        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.addView(listBox, new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        LinearLayout.LayoutParams sp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f);
        sp.topMargin = dp(10);
        root.addView(scroll, sp);

        setContentView(root);
    }

    private void refresh() {
        listBox.removeAllViews();
        QuestionBank bank = new QuestionBank(this);
        int total = bank.load(this);
        String err = bank.getLoadError();
        tvSummary.setText(err != null
                ? ("⚠ " + err)
                : ("当前生效题库共 " + total + " 题"));

        // 内嵌四年级题库(删除=停用,可恢复)
        final boolean grade4On = QuestionBank.isGrade4Enabled(this);
        LinearLayout emb = new LinearLayout(this);
        emb.setOrientation(LinearLayout.HORIZONTAL);
        emb.setGravity(Gravity.CENTER_VERTICAL);
        emb.setPadding(dp(14), dp(12), dp(14), dp(12));
        emb.setBackground(roundBg("#0E2B22", dp(12)));

        TextView embLabel = new TextView(this);
        embLabel.setText((grade4On ? "📗 " : "📕 ") + "四年级题库(内嵌 100 题)"
                + (grade4On ? "" : "(已删除)"));
        embLabel.setTextColor(Color.WHITE);
        embLabel.setTextSize(TypedValue.COMPLEX_UNIT_PX, dp(26));
        emb.addView(embLabel, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        Button embBtn = new Button(this, null, android.R.attr.borderlessButtonStyle);
        embBtn.setText(grade4On ? "删除" : "恢复");
        embBtn.setTextColor(Color.WHITE);
        embBtn.setAllCaps(false);
        embBtn.setIncludeFontPadding(false);
        embBtn.setPadding(dp(20), dp(8), dp(20), dp(8));
        embBtn.setMinimumHeight(dp(56));
        embBtn.setBackground(roundBg(grade4On ? "#B71C1C" : "#2E7D32", dp(12)));
        embBtn.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                QuestionBank.setGrade4Enabled(BankActivity.this, !grade4On);
                Toast.makeText(BankActivity.this,
                        grade4On ? "已删除内嵌四年级题库" : "已恢复内嵌四年级题库",
                        Toast.LENGTH_SHORT).show();
                refresh();
            }
        });
        emb.addView(embBtn, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        listBox.addView(emb, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        TextView importTitle = new TextView(this);
        importTitle.setText("── 导入的题库 ──");
        importTitle.setTextColor(Color.parseColor("#78909C"));
        importTitle.setTextSize(TypedValue.COMPLEX_UNIT_PX, dp(24));
        importTitle.setGravity(Gravity.CENTER);
        importTitle.setPadding(0, dp(14), 0, dp(4));
        listBox.addView(importTitle);

        if (bank.bankFileNames().isEmpty()) {
            TextView empty = new TextView(this);
            empty.setText("暂无导入的题库。\n可从U盘导入,或在设置页开启局域网上传。");
            empty.setTextColor(Color.parseColor("#78909C"));
            empty.setTextSize(TypedValue.COMPLEX_UNIT_PX, dp(26));
            empty.setGravity(Gravity.CENTER);
            empty.setPadding(0, dp(24), 0, 0);
            listBox.addView(empty);
            return;
        }

        for (final String name : bank.bankFileNames()) {
            LinearLayout row = new LinearLayout(this);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setPadding(dp(14), dp(12), dp(14), dp(12));
            row.setBackground(roundBg(MainActivity.CARD, dp(12)));
            LinearLayout.LayoutParams rp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            rp.topMargin = dp(10);
            row.setLayoutParams(rp);

            TextView label = new TextView(this);
            label.setText(shortName(name));
            label.setTextColor(Color.WHITE);
            label.setTextSize(TypedValue.COMPLEX_UNIT_PX, dp(26));
            label.setSingleLine(true);
            row.addView(label, new LinearLayout.LayoutParams(0,
                    ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

            Button del = new Button(this, null, android.R.attr.borderlessButtonStyle);
            del.setText("删除");
            del.setTextColor(Color.WHITE);
            del.setAllCaps(false);
            del.setIncludeFontPadding(false);
            del.setPadding(dp(20), dp(8), dp(20), dp(8));
            del.setMinimumHeight(dp(56));
            del.setBackground(roundBg("#B71C1C", dp(12)));
            del.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    QuestionBank.deleteBank(BankActivity.this, name);
                    MonitorService.reloadBank(BankActivity.this);
                    Toast.makeText(BankActivity.this, "已删除", Toast.LENGTH_SHORT).show();
                    refresh();
                }
            });
            row.addView(del, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));

            listBox.addView(row);
        }
    }

    private String shortName(String stored) {
        // 存储名带时间戳前缀,展示时去掉
        int idx = stored.indexOf('_');
        String shown = idx > 0 && stored.substring(0, idx).matches("\\d{13}")
                ? stored.substring(idx + 1) : stored;
        return "📄 " + shown;
    }

    private GradientDrawable roundBg(String color, float radius) {
        GradientDrawable d = new GradientDrawable();
        d.setCornerRadius(radius);
        d.setColor(Color.parseColor(color));
        return d;
    }

    private int dp(int v) {
        return UiUtil.px(this, v);
    }
}
