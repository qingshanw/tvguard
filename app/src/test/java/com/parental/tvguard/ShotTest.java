package com.parental.tvguard;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Bitmap;
import android.os.Bundle;
import android.view.View;
import android.view.ViewGroup;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.GraphicsMode;

import java.io.File;
import java.io.FileOutputStream;

/**
 * 在 JVM 上按真实分辨率渲染界面并保存 PNG + 导出视图树布局,
 * 用于无模拟器验证"高分屏显示不全/文字被裁"问题。
 */
@RunWith(RobolectricTestRunner.class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = 33)
public class ShotTest {

    static final String OUT = "build/shots";

    @Test
    @Config(sdk = 33, qualifiers = "w933dp-h426dp-land-xxhdpi") // 手机 2800x1280@480dpi 横屏
    public void shotsPhone() throws Exception {
        renderAll("phone");
    }

    @Test
    @Config(sdk = 33, qualifiers = "w1280dp-h720dp-land-hdpi") // 电视 1920x1080@240dpi 横屏
    public void shotsTv() throws Exception {
        renderAll("tv");
    }

    private void renderAll(String tag) throws Exception {
        new File(OUT).mkdirs();
        System.out.println("== render " + tag + " ==");

        // 1) 家长设置页(已解锁),并导入超长题目测试题库
        Bundle unlocked = new Bundle();
        unlocked.putBoolean("unlocked", true);
        ActivityController<MainActivity> mc = Robolectric.buildActivity(MainActivity.class)
                .newIntent(new Intent()).create(unlocked).visible();
        MainActivity main = mc.get();
        QuestionBank.importBank(main, "long_test.json", buildLongBankJson());
        MonitorService.reloadBank(main);
        main.renderPanels();
        snap(main, tag + "_1_main.png");
        dumpTree(main.getWindow().getDecorView(), tag + "_1_main.txt");
        mc.pause().stop().destroy();

        // 2) 密码设置页
        ActivityController<PasswordActivity> pc = Robolectric
                .buildActivity(PasswordActivity.class,
                        new Intent(main, PasswordActivity.class)
                                .putExtra(PasswordActivity.EXTRA_MODE, PasswordActivity.MODE_SET))
                .newIntent(null).create(null).visible();
        snap(pc.get(), tag + "_2_password.png");
        dumpTree(pc.get().getWindow().getDecorView(), tag + "_2_password.txt");
        pc.pause().stop().destroy();

        // 3) 答题页(长题目)
        ActivityController<QuizActivity> qc = Robolectric
                .buildActivity(QuizActivity.class, new Intent(main, QuizActivity.class))
                .newIntent(null).create(null).visible();
        snap(qc.get(), tag + "_3_quiz.png");
        dumpTree(qc.get().getWindow().getDecorView(), tag + "_3_quiz.txt");
        qc.pause().stop().destroy();

        // 4) 锁定倒计时页
        Prefs prefs = new Prefs(main);
        prefs.putLong(Prefs.KEY_LOCK_UNTIL, System.currentTimeMillis() + 600_000);
        ActivityController<QuizActivity> lc = Robolectric
                .buildActivity(QuizActivity.class, new Intent(main, QuizActivity.class))
                .newIntent(null).create(null).visible();
        snap(lc.get(), tag + "_4_lock.png");
        dumpTree(lc.get().getWindow().getDecorView(), tag + "_4_lock.txt");
        lc.pause().stop().destroy();
        prefs.putLong(Prefs.KEY_LOCK_UNTIL, 0);
    }

    private String buildLongBankJson() throws Exception {
        JSONObject root = new JSONObject();
        root.put("version", 1);
        JSONArray arr = new JSONArray();
        arr.put(new JSONObject()
                .put("q", "这是一道特别特别长的测试题目,用来验证在超高分辨率屏幕上题目文字会不会出现显示不完整、被其他控件挡住或者超出屏幕边界等等问题,一二三四五六七八九十")
                .put("options", new JSONArray()
                        .put("选项A内容比较长一些测试横向排列是否会裁切文字")
                        .put("选项B短")
                        .put("选项C")
                        .put("选项D也是一段比较长的选项文字用来测试按钮内文字的显示效果"))
                .put("answer", 1));
        arr.put(new JSONObject()
                .put("q", "短题:1+1=?")
                .put("options", new JSONArray().put("1").put("2").put("3").put("4"))
                .put("answer", 1));
        root.put("questions", arr);
        return root.toString();
    }

    // ---- 截图与布局导出 ----

    private void snap(Activity a, String name) throws Exception {
        View decor = a.getWindow().getDecorView();
        int w = a.getResources().getDisplayMetrics().widthPixels;
        int h = a.getResources().getDisplayMetrics().heightPixels;
        if (decor.getWidth() == 0 || decor.getHeight() == 0) {
            int specW = View.MeasureSpec.makeMeasureSpec(w, View.MeasureSpec.EXACTLY);
            int specH = View.MeasureSpec.makeMeasureSpec(h, View.MeasureSpec.EXACTLY);
            decor.measure(specW, specH);
            decor.layout(0, 0, w, h);
        }
        Bitmap bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
        decor.draw(new android.graphics.Canvas(bmp));
        FileOutputStream fos = new FileOutputStream(new File(OUT, name));
        bmp.compress(Bitmap.CompressFormat.PNG, 100, fos);
        fos.close();
        System.out.println("saved " + name + " " + w + "x" + h);
    }

    private void dumpTree(View v, String name) throws Exception {
        StringBuilder sb = new StringBuilder();
        walk(v, 0, sb);
        FileOutputStream fos = new FileOutputStream(new File(OUT, name));
        fos.write(sb.toString().getBytes("UTF-8"));
        fos.close();
    }

    private void walk(View v, int depth, StringBuilder sb) {
        for (int i = 0; i < depth; i++) sb.append("  ");
        String cls = v.getClass().getSimpleName();
        StringBuilder desc = new StringBuilder();
        if (v instanceof android.widget.TextView) {
            String t = ((android.widget.TextView) v).getText().toString();
            if (!t.isEmpty()) {
                desc.append(" text=\"")
                        .append(t.length() > 18 ? t.substring(0, 18) + "…" : t)
                        .append("\"");
            }
        }
        boolean overflow = v.getWidth() > v.getResources().getDisplayMetrics().widthPixels
                || v.getHeight() > v.getResources().getDisplayMetrics().heightPixels;
        sb.append(cls).append(desc)
                .append(" [").append(v.getLeft()).append(",").append(v.getTop())
                .append(" ").append(v.getWidth()).append("x").append(v.getHeight()).append("]")
                .append(overflow ? "  << 超出屏幕!" : "")
                .append("\n");
        if (v instanceof ViewGroup) {
            ViewGroup g = (ViewGroup) v;
            for (int i = 0; i < g.getChildCount(); i++) walk(g.getChildAt(i), depth + 1, sb);
        }
    }
}
