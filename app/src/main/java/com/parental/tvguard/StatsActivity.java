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

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

/**
 * 答题统计(家长):每道题的答对/答错次数、正确率、毕业状态;
 * 支持一键重置全部统计(毕业题目重新出现)。
 */
public class StatsActivity extends Activity {

    private LinearLayout listBox;
    private TextView tvSummary;
    private QuestionBank bank;
    private boolean sortByRate = false; // false=按答题次数(默认), true=按正确率
    private TextView tvSortBtn;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        buildUi();
    }

    @Override
    protected void onResume() {
        super.onResume();
        MonitorService.enterConfigUi();
        refresh();
    }

    @Override
    protected void onPause() {
        super.onPause();
        MonitorService.exitConfigUi();
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
        root.setPadding(pad, dp(12), pad, dp(12));

        root.addView(topBar("📊 答题统计"), new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        tvSummary = new TextView(this);
        tvSummary.setTextColor(Color.parseColor(MainActivity.ACCENT_CYAN));
        tvSummary.setTextSize(TypedValue.COMPLEX_UNIT_PX, dp(26));
        tvSummary.setGravity(Gravity.CENTER);
        tvSummary.setPadding(0, dp(8), 0, dp(8));
        root.addView(tvSummary, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        LinearLayout btnRow = new LinearLayout(this);
        btnRow.setOrientation(LinearLayout.HORIZONTAL);
        btnRow.setGravity(Gravity.CENTER);

        // 排序切换:默认按答题次数,可切到按正确率
        tvSortBtn = new TextView(this);
        tvSortBtn.setText(sortByRate ? "排序:正确率(低→高) ▾" : "排序:答题次数(多→少) ▾");
        tvSortBtn.setTextSize(TypedValue.COMPLEX_UNIT_PX, dp(26));
        tvSortBtn.setTextColor(Color.parseColor(MainActivity.ACCENT_CYAN));
        tvSortBtn.setPadding(dp(20), dp(10), dp(20), dp(10));
        GradientDrawable sg = new GradientDrawable();
        sg.setCornerRadius(dp(28));
        sg.setColor(Color.parseColor("#233B60"));
        tvSortBtn.setBackground(sg);
        tvSortBtn.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                sortByRate = !sortByRate;
                tvSortBtn.setText(sortByRate
                        ? "排序:正确率(低→高) ▾" : "排序:答题次数(多→少) ▾");
                refresh();
            }
        });
        LinearLayout.LayoutParams sortLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        sortLp.rightMargin = dp(16);
        btnRow.addView(tvSortBtn, sortLp);

        Button reset = new Button(this, null, android.R.attr.borderlessButtonStyle);
        reset.setText("重置全部统计");
        reset.setTextSize(TypedValue.COMPLEX_UNIT_PX, dp(26));
        reset.setTextColor(Color.WHITE);
        reset.setAllCaps(false);
        reset.setIncludeFontPadding(false);
        reset.setPadding(dp(24), dp(12), dp(24), dp(12));
        reset.setMinimumHeight(dp(56));
        GradientDrawable rg = new GradientDrawable();
        rg.setCornerRadius(dp(12));
        rg.setColor(Color.parseColor("#B71C1C"));
        reset.setBackground(rg);
        reset.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                bank.stats().resetAll();
                Toast.makeText(StatsActivity.this, "已重置,所有毕业题目重新出现", Toast.LENGTH_LONG).show();
                refresh();
            }
        });
        btnRow.addView(reset, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        root.addView(btnRow, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        listBox = new LinearLayout(this);
        listBox.setOrientation(LinearLayout.VERTICAL);

        ScrollView scroll = new ScrollView(this);
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
        bank = new QuestionBank(this);
        int total = bank.load(this);
        QuestionStats stats = bank.stats();

        tvSummary.setText("共 " + total + " 题 | 待答 " + bank.activeCount()
                + " | 已毕业 " + bank.graduatedCount()
                + "(答对 " + QuestionStats.RETIRE_AFTER + " 次自动毕业)");

        if (total == 0) {
            TextView empty = new TextView(this);
            String err = bank.getLoadError();
            empty.setText(err != null ? ("⚠ " + err) : "题库为空");
            empty.setTextColor(Color.parseColor("#78909C"));
            empty.setTextSize(TypedValue.COMPLEX_UNIT_PX, dp(26));
            empty.setGravity(Gravity.CENTER);
            empty.setPadding(0, dp(24), 0, 0);
            listBox.addView(empty);
            return;
        }

        List<QuestionBank.Question> all = bank.allQuestions();
        final boolean byRate = sortByRate;
        // 排序:未毕业在前、已毕业垫底;
        //   默认(次数模式):答题次数多的在前;
        //   正确率模式:正确率低的在前(最容易错的先看到),未作答的排在本组最后
        Collections.sort(all, new Comparator<QuestionBank.Question>() {
            @Override
            public int compare(QuestionBank.Question a, QuestionBank.Question b) {
                boolean ga = stats.graduated(a.id), gb = stats.graduated(b.id);
                if (ga != gb) return ga ? 1 : -1;
                int attA = stats.attempts(a.id), attB = stats.attempts(b.id);
                if (byRate) {
                    int rateA = attA == 0 ? Integer.MAX_VALUE
                            : stats.correct(a.id) * 100 / attA;
                    int rateB = attB == 0 ? Integer.MAX_VALUE
                            : stats.correct(b.id) * 100 / attB;
                    if (rateA != rateB) return rateA - rateB;
                    return attB - attA;
                }
                if (attA != attB) return attB - attA; // 答题次数多的在前
                return 0;
            }
        });

        for (QuestionBank.Question q : all) {
            listBox.addView(questionCard(q, stats));
        }
    }

    private LinearLayout questionCard(QuestionBank.Question q, QuestionStats stats) {
        LinearLayout c = new LinearLayout(this);
        c.setOrientation(LinearLayout.HORIZONTAL);
        c.setGravity(android.view.Gravity.CENTER_VERTICAL);
        int pad = dp(14);
        c.setPadding(pad, pad, pad, pad);
        c.setBackground(roundBg(MainActivity.CARD, dp(12)));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(10);
        c.setLayoutParams(lp);

        boolean grad = stats.graduated(q.id);
        int att = stats.attempts(q.id);
        int cor = stats.correct(q.id);
        int wrong = att - cor;

        // 左侧:题目 + 答对/答错次数
        LinearLayout left = new LinearLayout(this);
        left.setOrientation(LinearLayout.VERTICAL);
        left.setLayoutParams(new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        TextView title = new TextView(this);
        title.setText((grad ? "🏆 " : "📝 ") + q.q);
        title.setTextSize(TypedValue.COMPLEX_UNIT_PX, dp(26));
        title.setTextColor(Color.WHITE);
        title.setMaxLines(2);
        left.addView(title);

        TextView counts = new TextView(this);
        counts.setText("答对 " + cor + " 次 | 答错 " + wrong + " 次");
        counts.setTextSize(TypedValue.COMPLEX_UNIT_PX, dp(22));
        counts.setTextColor(Color.parseColor("#90CAF9"));
        counts.setPadding(0, dp(6), 0, 0);
        left.addView(counts);

        c.addView(left);

        // 右侧:作答状态 + 正确率(一眼可见)
        LinearLayout right = new LinearLayout(this);
        right.setOrientation(LinearLayout.VERTICAL);
        right.setGravity(android.view.Gravity.CENTER);
        int rp = dp(10);
        right.setPadding(dp(16), rp, dp(16), rp);
        android.graphics.drawable.GradientDrawable rbg =
                new android.graphics.drawable.GradientDrawable();
        rbg.setCornerRadius(dp(10));
        rbg.setColor(Color.parseColor(grad ? "#0E2B22" : "#182B4D"));
        right.setBackground(rbg);
        right.setLayoutParams(new LinearLayout.LayoutParams(
                dp(210), ViewGroup.LayoutParams.WRAP_CONTENT));

        TextView rateView = new TextView(this);
        if (att == 0) {
            rateView.setText("--");
            rateView.setTextColor(Color.parseColor("#78909C"));
        } else {
            int ratePct = cor * 100 / att;
            rateView.setText(ratePct + "%");
            rateView.setTextColor(Color.parseColor(
                    ratePct >= 60 ? "#66BB6A" : (ratePct >= 30 ? "#FFB74D" : "#E57373")));
        }
        rateView.setTextSize(TypedValue.COMPLEX_UNIT_PX, dp(34));
        rateView.setTypeface(null, Typeface.BOLD);
        rateView.setGravity(android.view.Gravity.CENTER);
        right.addView(rateView);

        TextView stateView = new TextView(this);
        stateView.setText(att == 0 ? "未作答" : (grad ? "已毕业" : "已作答"));
        stateView.setTextSize(TypedValue.COMPLEX_UNIT_PX, dp(22));
        stateView.setTextColor(Color.parseColor(
                grad ? "#66BB6A" : (att == 0 ? "#78909C" : "#90CAF9")));
        stateView.setGravity(android.view.Gravity.CENTER);
        right.addView(stateView);

        c.addView(right);
        return c;
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
