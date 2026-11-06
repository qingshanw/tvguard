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
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
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
 * 统一权限配置页:悬浮窗 / 后台运行 / 电池优化 / 通知,
 * 可自动检测的显示绿色对勾,后台运行权限无法检测需家长手动标记。
 * 从系统设置返回时自动刷新状态。
 */
public class PermissionActivity extends Activity {

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
        MonitorService.enterConfigUi();
        refresh();
    }

    @Override
    protected void onPause() {
        super.onPause();
        MonitorService.exitConfigUi();
    }

    private void buildUi() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(Color.parseColor(MainActivity.BG));
        int pad = dp(24);
        root.setPadding(pad, dp(14), pad, pad);

        root.addView(topBar("🔐 权限配置"), new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        tvSummary = new TextView(this);
        tvSummary.setTextSize(TypedValue.COMPLEX_UNIT_PX, dp(28));
        tvSummary.setGravity(Gravity.CENTER);
        tvSummary.setPadding(0, dp(10), 0, dp(4));
        root.addView(tvSummary, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

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

        // 1. 悬浮窗(可自动检测)
        boolean overlayOk = Build.VERSION.SDK_INT < 23 || Settings.canDrawOverlays(this);
        listBox.addView(permRow(
                "显示在其他应用的上层",
                "弹题界面/休息界面盖住视频所必需。电视盒子一般无需此项;手机上必须开启。",
                overlayOk,
                overlayOk ? null : "去开启",
                overlayOk ? null : new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        try {
                            startActivity(new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                                    Uri.parse("package:" + getPackageName())));
                        } catch (Exception e) {
                            Toast.makeText(PermissionActivity.this,
                                    "请到系统设置中手动授权", Toast.LENGTH_LONG).show();
                        }
                    }
                },
                null, null));

        // 2. 后台运行权限(无法程序检测,家长手动确认)
        final boolean bgOk = new Prefs(this).getBool(Prefs.KEY_BG_CONFIRMED, false);
        listBox.addView(permRow(
                "后台运行权限",
                "把「允许自启动 / 允许关联启动 / 允许后台活动」全部打开,\n"
                        + "通常在系统管家/手机管家的「应用启动管理」或「自启动」里,\n"
                        + "不在应用信息页;华为/荣耀/小米/OPPO/vivo 路径见使用说明。",
                bgOk,
                bgOk ? null : "去设置",
                bgOk ? null : new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        openVendorPermissionPage();
                    }
                },
                bgOk ? null : "我已全部开启",
                bgOk ? null : new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        new Prefs(PermissionActivity.this).putBool(Prefs.KEY_BG_CONFIRMED, true);
                        Toast.makeText(PermissionActivity.this, "已标记为开启", Toast.LENGTH_SHORT).show();
                        refresh();
                    }
                }));

        // 3. 电池优化(可自动检测)
        android.os.PowerManager pm = (android.os.PowerManager) getSystemService(POWER_SERVICE);
        final boolean batteryOk = pm != null
                && pm.isIgnoringBatteryOptimizations(getPackageName());
        listBox.addView(permRow(
                "电池优化白名单",
                "关闭对本应用的电池优化,防止系统省电策略冻结后台计时。",
                batteryOk,
                batteryOk ? null : "加入白名单",
                batteryOk ? null : new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        try {
                            startActivity(new Intent(
                                    Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                                    Uri.parse("package:" + getPackageName())));
                        } catch (Exception e) {
                            Toast.makeText(PermissionActivity.this,
                                    "请到系统设置-电池中手动允许", Toast.LENGTH_LONG).show();
                        }
                    }
                },
                null, null));

        // 4. 通知(可自动检测,常驻观看时间条幅需要)
        android.app.NotificationManager nm =
                (android.app.NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        boolean runtimeOk = Build.VERSION.SDK_INT < 33
                || checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS)
                == android.content.pm.PackageManager.PERMISSION_GRANTED;
        boolean notifOk = runtimeOk && (Build.VERSION.SDK_INT < 24 || nm == null
                || nm.areNotificationsEnabled());
        listBox.addView(permRow(
                "通知权限",
                "下拉通知栏的常驻条幅(实时观看时间)与弹窗拦截提醒需要通知权限。",
                notifOk,
                notifOk ? null : "去开启",
                notifOk ? null : new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        // Android 13+: 必须先申请运行时权限,否则系统页开关是灰色的
                        if (Build.VERSION.SDK_INT >= 33
                                && checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS)
                                != android.content.pm.PackageManager.PERMISSION_GRANTED) {
                            requestPermissions(new String[]{
                                    android.Manifest.permission.POST_NOTIFICATIONS}, 100);
                            return;
                        }
                        try {
                            Intent it = new Intent(
                                    Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                                    .putExtra(Settings.EXTRA_APP_PACKAGE, getPackageName());
                            startActivity(it);
                        } catch (Exception e) {
                            try {
                                startActivity(new Intent(
                                        Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                                        Uri.parse("package:" + getPackageName())));
                            } catch (Exception ignored) {
                            }
                        }
                    }
                },
                null, null));

        int ok = (overlayOk ? 1 : 0) + (bgOk ? 1 : 0) + (batteryOk ? 1 : 0) + (notifOk ? 1 : 0);
        tvSummary.setText("已开启 " + ok + " / 4 项");
        tvSummary.setTextColor(Color.parseColor(ok == 4 ? "#66BB6A" : "#FFB74D"));

        // 5. 开机自启(应用自身的开关,非系统权限,不计入上面的统计)
        final Prefs p = new Prefs(this);
        final boolean bootOn = p.bootStart();
        listBox.addView(permRow(
                "开机自启",
                "设备重启后自动恢复守护,无需家长重新打开应用。",
                bootOn,
                bootOn ? "关闭" : "开启",
                new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        p.putBool(Prefs.KEY_BOOT_START, !bootOn);
                        Toast.makeText(PermissionActivity.this,
                                bootOn ? "已关闭开机自启" : "已开启开机自启",
                                Toast.LENGTH_SHORT).show();
                        refresh();
                    }
                },
                null, null));
    }

    /** 一行权限卡:标题 + 说明 + 状态(✓绿/未开启红) + 操作按钮。 */
    private LinearLayout permRow(String title, String desc, boolean ok,
                                 String btnText, View.OnClickListener btnAction,
                                 String btn2Text, View.OnClickListener btn2Action) {
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

        LinearLayout head = new LinearLayout(this);
        head.setOrientation(LinearLayout.HORIZONTAL);
        head.setGravity(Gravity.CENTER_VERTICAL);

        TextView t = new TextView(this);
        t.setText((ok ? "✅ " : "⛔ ") + title);
        t.setTextSize(TypedValue.COMPLEX_UNIT_PX, dp(28));
        t.setTextColor(ok ? Color.parseColor("#66BB6A") : Color.WHITE);
        t.setTypeface(null, Typeface.BOLD);
        head.addView(t, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        TextView status = new TextView(this);
        status.setText(ok ? "已开启" : "未开启");
        status.setTextSize(TypedValue.COMPLEX_UNIT_PX, dp(24));
        status.setTextColor(ok ? Color.parseColor("#66BB6A") : Color.parseColor("#E57373"));
        status.setGravity(Gravity.CENTER_VERTICAL);
        head.addView(status);
        c.addView(head);

        TextView d = new TextView(this);
        d.setText(desc);
        d.setTextSize(TypedValue.COMPLEX_UNIT_PX, dp(22));
        d.setTextColor(Color.parseColor("#90CAF9"));
        d.setLineSpacing(dp(4), 1f);
        d.setPadding(0, dp(6), 0, 0);
        c.addView(d);

        LinearLayout btnRow = new LinearLayout(this);
        btnRow.setOrientation(LinearLayout.HORIZONTAL);
        btnRow.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams brLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        brLp.topMargin = dp(10);

        if (btnText != null && btnAction != null) {
            Button b = actionButton(btnText, "#B26A00" );
            b.setOnClickListener(btnAction);
            LinearLayout.LayoutParams blp = new LinearLayout.LayoutParams(
                    0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
            btnRow.addView(b, blp);
        }
        if (btn2Text != null && btn2Action != null) {
            Button b2 = actionButton(btn2Text, "#2E7D32");
            b2.setOnClickListener(btn2Action);
            LinearLayout.LayoutParams b2lp = new LinearLayout.LayoutParams(
                    0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
            b2lp.leftMargin = dp(10);
            btnRow.addView(b2, b2lp);
        }
        if (btnText != null || btn2Text != null) {
            c.addView(btnRow, brLp);
        }
        return c;
    }

    private Button actionButton(String text, String color) {
        Button b = new Button(this, null, android.R.attr.borderlessButtonStyle);
        b.setText(text);
        b.setTextSize(TypedValue.COMPLEX_UNIT_PX, dp(26));
        b.setTextColor(Color.WHITE);
        b.setAllCaps(false);
        b.setIncludeFontPadding(false);
        b.setPadding(dp(16), dp(12), dp(16), dp(12));
        b.setMinimumHeight(dp(56));
        GradientDrawable bg = new GradientDrawable();
        bg.setCornerRadius(dp(12));
        bg.setColor(Color.parseColor(color));
        b.setBackground(bg);
        return b;
    }

    /** 打开各厂商的后台权限设置页,失败则退回应用详情。 */
    private void openVendorPermissionPage() {
        String mfgr = Build.MANUFACTURER == null ? "" : Build.MANUFACTURER.toLowerCase();
        Intent it = null;
        try {
            if (mfgr.contains("huawei") || mfgr.contains("honor")) {
                it = new Intent().setClassName("com.huawei.systemmanager",
                        "com.huawei.systemmanager.startupmgr.ui.StartupNormalAppListActivity");
            } else if (mfgr.contains("xiaomi") || mfgr.contains("redmi")) {
                it = new Intent().setClassName("com.miui.securitycenter",
                        "com.miui.permcenter.autostart.AutoStartManagementActivity");
            } else if (mfgr.contains("oppo") || mfgr.contains("realme") || mfgr.contains("oneplus")) {
                it = new Intent().setClassName("com.coloros.safecenter",
                        "com.coloros.safecenter.permission.startup.StartupAppListActivity");
            } else if (mfgr.contains("vivo") || mfgr.contains("iqoo")) {
                it = new Intent().setClassName("com.vivo.permissionmanager",
                        "com.vivo.permissionmanager.activity.BgStartUpManagerActivity");
            }
        } catch (Exception e) {
            it = null;
        }
        try {
            if (it != null) {
                it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                startActivity(it);
                return;
            }
        } catch (Exception ignored) {
        }
        try {
            startActivity(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                    Uri.parse("package:" + getPackageName())));
        } catch (Exception e) {
            Toast.makeText(this, "请到系统设置中手动开启后台权限", Toast.LENGTH_LONG).show();
        }
    }

    /** 顶部栏:返回按钮 + 居中标题。 */
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

    private int dp(int v) {
        return UiUtil.px(this, v);
    }
}
