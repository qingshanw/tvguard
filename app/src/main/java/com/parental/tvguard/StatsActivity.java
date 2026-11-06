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
        // 排序:未毕业在前,按答错次数降序;已毕业在最后
        Collections.sort(all, new Comparator<QuestionBank.Question>() {
            @Override
            public int compare(QuestionBank.Question a, QuestionBank.Question b) {
                boolean ga = stats.graduated(a.id), gb = stats.graduated(b.id);
                if (ga != gb) return ga ? 1 : -1;
                int wa = stats.attempts(a.id) - stats.correct(a.id);
                int wb = stats.attempts(b.id) - stats.correct(b.id);
                return wb - wa;
            }
        });

        for (QuestionBank.Question q : all) {
            listBox.addView(questionCard(q, stats));
        }
    }

    private LinearLayout questionCard(QuestionBank.Question q, QuestionStats stats) {
        LinearLayout c = new LinearLayout(this);
        c.setOrientation(LinearLayout.VERTICAL);
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

        TextView title = new TextView(this);
        title.setText((grad ? "🏆 " : "📝 ") + q.q);
        title.setTextSize(TypedValue.COMPLEX_UNIT_PX, dp(26));
        title.setTextColor(Color.WHITE);
        title.setMaxLines(2);
        c.addView(title);

        TextView line2 = new TextView(this);
        String rate = att == 0 ? "未作答" : "正确率 " + (cor * 100 / att) + "%";
        line2.setText("答对 " + cor + " 次 | 答错 " + wrong + " 次 | " + rate
                + (grad ? " | 已毕业" : ""));
        line2.setTextSize(TypedValue.COMPLEX_UNIT_PX, dp(22));
        line2.setTextColor(grad ? Color.parseColor("#66BB6A") : Color.parseColor("#90CAF9"));
        line2.setPadding(0, dp(6), 0, 0);
        c.addView(line2);

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
