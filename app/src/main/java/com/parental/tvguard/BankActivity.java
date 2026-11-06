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
 * 题库管理:内嵌四年级题库(可禁用/启用)、查看已导入题库、启用/禁用、删除(二次确认)、从U盘导入。
 */
public class BankActivity extends Activity {

    private static final int REQ_IMPORT = 3;

    private LinearLayout listBox;
    private TextView tvSummary;
    private String pendingDeleteName; // 待二次确认删除的题库文件名

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

        // 内嵌四年级题库(禁用=不参与出题可随时恢复;删除=停用并清除其统计,二次确认)
        final boolean grade4On = QuestionBank.isGrade4Enabled(this);
        LinearLayout emb = new LinearLayout(this);
        emb.setOrientation(LinearLayout.HORIZONTAL);
        emb.setGravity(Gravity.CENTER_VERTICAL);
        emb.setPadding(dp(14), dp(12), dp(14), dp(12));
        emb.setBackground(roundBg(grade4On ? "#153E2B" : "#3A3A3A", dp(12)));

        TextView embLabel = new TextView(this);
        embLabel.setText((grade4On ? "📗 " : "📕 ") + "四年级题库(内嵌 100 题)"
                + (grade4On ? "" : " · 已禁用"));
        embLabel.setTextColor(grade4On ? Color.WHITE : Color.parseColor("#9E9E9E"));
        embLabel.setTextSize(TypedValue.COMPLEX_UNIT_PX, dp(26));
        emb.addView(embLabel, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        Button embBtn = new Button(this, null, android.R.attr.borderlessButtonStyle);
        embBtn.setText(grade4On ? "禁用" : "启用");
        embBtn.setTextColor(Color.WHITE);
        embBtn.setAllCaps(false);
        embBtn.setIncludeFontPadding(false);
        embBtn.setPadding(dp(20), dp(8), dp(20), dp(8));
        embBtn.setMinimumHeight(dp(56));
        embBtn.setBackground(roundBg(grade4On ? "#8D6E63" : "#2E7D32", dp(12)));
        embBtn.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                QuestionBank.setGrade4Enabled(BankActivity.this, !grade4On);
                MonitorService.reloadBank(BankActivity.this);
                Toast.makeText(BankActivity.this,
                        grade4On ? "已禁用内嵌四年级题库" : "已启用内嵌四年级题库",
                        Toast.LENGTH_SHORT).show();
                refresh();
            }
        });
        LinearLayout.LayoutParams embBtnLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        embBtnLp.leftMargin = dp(8);
        emb.addView(embBtn, embBtnLp);

        // 内嵌题库无法真正删除文件,删除=停用并清除其答题统计(可用「启用」恢复)
        Button embDel = new Button(this, null, android.R.attr.borderlessButtonStyle);
        boolean grade4Armed = "__grade4__".equals(pendingDeleteName);
        embDel.setText(grade4Armed ? "确认删除?" : "删除");
        embDel.setTextColor(Color.WHITE);
        embDel.setAllCaps(false);
        embDel.setIncludeFontPadding(false);
        embDel.setPadding(dp(20), dp(8), dp(20), dp(8));
        embDel.setMinimumHeight(dp(56));
        embDel.setBackground(roundBg(grade4Armed ? "#FF5722" : "#B71C1C", dp(12)));
        embDel.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if ("__grade4__".equals(pendingDeleteName)) {
                    QuestionBank.setGrade4Enabled(BankActivity.this, false);
                    new QuestionBank(BankActivity.this).stats().removeBank("四年级题库");
                    MonitorService.reloadBank(BankActivity.this);
                    pendingDeleteName = null;
                    Toast.makeText(BankActivity.this,
                            "内嵌题库已停用,其答题统计已清除(可用「启用」恢复)",
                            Toast.LENGTH_LONG).show();
                    refresh();
                } else {
                    pendingDeleteName = "__grade4__";
                    ((Button) v).setText("确认删除?");
                    ((Button) v).setBackground(roundBg("#FF5722", dp(12)));
                    Toast.makeText(BankActivity.this,
                            "内嵌题库将被停用并清除其答题统计,再点一次确认",
                            Toast.LENGTH_LONG).show();
                }
            }
        });
        LinearLayout.LayoutParams embDelLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        embDelLp.leftMargin = dp(8);
        emb.addView(embDel, embDelLp);
        listBox.addView(emb, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        TextView importTitle = new TextView(this);
        importTitle.setText("── 导入的题库 ──");
        importTitle.setTextColor(Color.parseColor("#78909C"));
        importTitle.setTextSize(TypedValue.COMPLEX_UNIT_PX, dp(24));
        importTitle.setGravity(Gravity.CENTER);
        importTitle.setPadding(0, dp(14), 0, dp(4));
        listBox.addView(importTitle);

        if (QuestionBank.allBankFiles(this).isEmpty()) {
            TextView empty = new TextView(this);
            empty.setText("暂无导入的题库。\n可从U盘导入,或在设置页开启局域网上传。");
            empty.setTextColor(Color.parseColor("#78909C"));
            empty.setTextSize(TypedValue.COMPLEX_UNIT_PX, dp(26));
            empty.setGravity(Gravity.CENTER);
            empty.setPadding(0, dp(24), 0, 0);
            listBox.addView(empty);
            return;
        }

        java.util.Set<String> disabled = QuestionBank.disabledBanks(this);
        for (final String name : QuestionBank.allBankFiles(this)) {
            final boolean enabled = !disabled.contains(name);
            int count = QuestionBank.bankQuestionCount(this, name);

            LinearLayout row = new LinearLayout(this);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setPadding(dp(14), dp(12), dp(14), dp(12));
            row.setBackground(roundBg(enabled ? "#153E2B" : "#3A3A3A", dp(12)));
            LinearLayout.LayoutParams rp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            rp.topMargin = dp(10);
            row.setLayoutParams(rp);

            TextView label = new TextView(this);
            label.setText(shortName(name)
                    + (count >= 0 ? " · " + count + "题" : " · 解析失败")
                    + (enabled ? "" : " · 已禁用"));
            label.setTextColor(enabled ? Color.WHITE : Color.parseColor("#9E9E9E"));
            label.setTextSize(TypedValue.COMPLEX_UNIT_PX, dp(26));
            label.setSingleLine(true);
            label.setEllipsize(android.text.TextUtils.TruncateAt.END);
            row.addView(label, new LinearLayout.LayoutParams(0,
                    ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

            // 启用/禁用切换(禁用保留文件与统计,只是不出题)
            Button toggle = new Button(this, null, android.R.attr.borderlessButtonStyle);
            toggle.setText(enabled ? "禁用" : "启用");
            toggle.setTextColor(Color.WHITE);
            toggle.setAllCaps(false);
            toggle.setIncludeFontPadding(false);
            toggle.setPadding(dp(16), dp(8), dp(16), dp(8));
            toggle.setMinimumHeight(dp(56));
            toggle.setBackground(roundBg(enabled ? "#8D6E63" : "#2E7D32", dp(12)));
            toggle.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    QuestionBank.setBankEnabled(BankActivity.this, name, !enabled);
                    MonitorService.reloadBank(BankActivity.this);
                    Toast.makeText(BankActivity.this,
                            enabled ? "已禁用,该题库不再出题" : "已启用",
                            Toast.LENGTH_SHORT).show();
                    refresh();
                }
            });
            row.addView(toggle, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));

            // 删除(二次确认:第一击变"确认删除?",再击才真正删除)
            Button del = new Button(this, null, android.R.attr.borderlessButtonStyle);
            del.setText(name.equals(pendingDeleteName) ? "确认删除?" : "删除");
            del.setTextColor(Color.WHITE);
            del.setAllCaps(false);
            del.setIncludeFontPadding(false);
            del.setPadding(dp(16), dp(8), dp(16), dp(8));
            del.setMinimumHeight(dp(56));
            del.setBackground(roundBg(name.equals(pendingDeleteName) ? "#FF5722" : "#B71C1C", dp(12)));
            del.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    if (name.equals(pendingDeleteName)) {
                        QuestionBank.deleteBank(BankActivity.this, name);
                        MonitorService.reloadBank(BankActivity.this);
                        pendingDeleteName = null;
                        Toast.makeText(BankActivity.this, "已删除题库及其答题统计",
                                Toast.LENGTH_SHORT).show();
                        refresh();
                    } else {
                        pendingDeleteName = name;
                        ((Button) v).setText("确认删除?");
                        ((Button) v).setBackground(roundBg("#FF5722", dp(12)));
                        Toast.makeText(BankActivity.this,
                                "删除后该题库的答题统计也一并清除,再点一次确认",
                                Toast.LENGTH_LONG).show();
                    }
                }
            });
            LinearLayout.LayoutParams delLp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            delLp.leftMargin = dp(8);
            row.addView(del, delLp);

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
