package com.parental.tvguard;

import android.app.Activity;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.view.View;
import android.widget.Button;
import android.os.Bundle;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

/**
 * 使用说明:配置说明、题库导入方法、全部守护机制一览。
 */
public class HelpActivity extends Activity {

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(Color.parseColor(MainActivity.BG));
        int pad = dp(24);
        root.setPadding(pad, dp(14), pad, pad);

        root.addView(topBar("📖 使用说明"), new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        scroll.addView(box, new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        LinearLayout.LayoutParams sp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f);
        sp.topMargin = dp(10);
        root.addView(scroll, sp);

        // ===== 快速上手 =====
        section(box, "🚀 快速上手");
        text(box, "1. 本页各项配置按需调整(都有默认值,不改也能用);\n"
                + "2. 点击底部绿色「完成,开始守护」按钮 → 界面退到后台,计时立即开始;\n"
                + "3. 孩子正常看视频,到达设定时长会自动弹题;\n"
                + "4. 再次打开本 App 需要输入家长密码。");

        // ===== 配置说明 =====
        section(box, "⚙ 配置项说明");
        text(box, "· 防沉迷监控:总开关,关闭后不再弹题和锁定;\n"
                + "· 弹题间隔:每连续观看多久弹出一次答题(默认10分钟);\n"
                + "· 连错锁定:连续答错几题触发锁定休息(默认3题);\n"
                + "· 答错锁屏:答错锁定后的休息时长(默认10分钟);\n"
                + "· 连续观看/强制休息:不看题直接休息的硬性限制(如每30分钟强制休息5分钟,设为「关闭」则不启用);\n"
                + "· 开机自启:电视重启后守护自动运行;\n"
                + "· 局域网上传:开启后手机浏览器访问显示的地址,即可给电视上传题库。");

        // ===== 题库 =====
        section(box, "📚 题库与导入");
        text(box, "· App 内嵌四年级题库 100 题(语文30/数学40/英语30),可在题库管理中删除;\n题库全部删除或全部毕业后,观看将锁定,需家长密码解锁;\n"
                + "· 自定义题库:电脑上用「题库工具/题库模板.xlsx」出题 → 浏览器打开「转换器.html」转成 JSON → 拷入U盘 → 本页点「从U盘导入」;\n"
                + "· 或开启「局域网上传」后用手机浏览器直接上传 JSON 文件;\n"
                + "· JSON 格式:{ \"questions\": [ { \"q\":\"题目\", \"options\":[..], \"answer\":0 } ] },UTF-8 编码,answer 从 0 开始;\n"
                + "· 多次导入多份题库会全部生效、随机混合出题;可在「题库管理」删除不需要的。");

        // ===== 机制 =====
        section(box, "🛡 守护机制一览");
        text(box, "· 弹题:到达「弹题间隔」后,画面被全屏答题界面盖住,并尝试暂停播放器;\n"
                + "· 答对:播放鼓励音,继续观看,计时重新开始;答错:错误提示音,继续答下一题;\n"
                + "· 连错锁定:连续答错达到上限,全屏倒计时休息,结束自动恢复播放;\n"
                + "· 题目毕业:每题答对 5 次后自动毕业不再出现;答错的题会再出现;可在「答题统计」查看每题正确率,一键重置;\n"
                + "· 全部答完:所有题目毕业后弹题界面停在解锁页,必须家长输入密码重置才能继续看;\n"
                + "· 强制休息:与答题无关,连续观看达到时长直接锁定休息;\n"
                + "· 防逃避:答题/休息界面自动申请「屏幕固定」,主页键失效;若逃出会在 1 秒内自动拉回;配置页面不计时。");

        // ===== 家长须知 =====
        section(box, "⚠ 家长须知");
        text(box, "· 请牢记家长密码:忘记密码无法修改设置,也无法解锁题目全部答完的状态(只能卸载重装);\n"
                + "· 首次使用请务必授予「显示在应用上层」权限,否则弹窗可能无法盖住视频;\n"
                + "· 普通应用无法阻止孩子卸载本 App;需要更强防护可用 ADB 设置设备所有者(见 README);\n"
                + "· 个别不响应暂停键的播放器,画面会被盖住但声音可能继续。");

        // 底部版本信息
        TextView ver = new TextView(this);
        String v = "?";
        try {
            v = getPackageManager().getPackageInfo(getPackageName(), 0).versionName;
        } catch (Exception ignored) {
        }
        ver.setText("视界守卫者 v" + v);
        ver.setTextSize(TypedValue.COMPLEX_UNIT_PX, dp(22));
        ver.setTextColor(Color.parseColor("#546E7A"));
        ver.setGravity(Gravity.CENTER);
        ver.setPadding(0, dp(10), 0, dp(6));
        root.addView(ver, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        setContentView(root);
    }

    private LinearLayout section(LinearLayout box, String title) {
        TextView t = new TextView(this);
        t.setText(title);
        t.setTextSize(TypedValue.COMPLEX_UNIT_PX, dp(30));
        t.setTextColor(Color.parseColor(MainActivity.ACCENT_CYAN));
        t.setTypeface(null, Typeface.BOLD);

        LinearLayout c = card();
        c.addView(t);
        box.addView(c);
        return c;
    }

    private void text(LinearLayout sectionCard, String content) {
        TextView t = new TextView(this);
        t.setText(content);
        t.setTextSize(TypedValue.COMPLEX_UNIT_PX, dp(25));
        t.setTextColor(Color.WHITE);
        t.setLineSpacing(dp(6), 1f);
        t.setPadding(0, dp(8), 0, dp(4));
        sectionCard.addView(t);
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
        LinearLayout.LayoutParams tp = new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        t.setLayoutParams(tp);
        t.setGravity(Gravity.CENTER);
        bar.addView(t);

        // 占位让标题严格居中
        View space = new View(this);
        bar.addView(space, new LinearLayout.LayoutParams(dp(150), dp(1)));
        return bar;
    }

    private LinearLayout card() {
        LinearLayout c = new LinearLayout(this);
        c.setOrientation(LinearLayout.VERTICAL);
        c.setPadding(dp(18), dp(14), dp(18), dp(14));
        GradientDrawable bg = new GradientDrawable();
        bg.setCornerRadius(dp(14));
        bg.setColor(Color.parseColor(MainActivity.CARD));
        c.setBackground(bg);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(12);
        c.setLayoutParams(lp);
        return c;
    }

    private int dp(int v) {
        return UiUtil.px(this, v);
    }
}
