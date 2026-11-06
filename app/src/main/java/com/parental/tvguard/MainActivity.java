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
import android.os.Build;
import android.util.TypedValue;
import android.os.Bundle;
import android.provider.Settings;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

/**
 * 家长设置界面(入口)。需先通过家长密码。
 * 横屏双栏卡片式布局:左栏监控规则,右栏题库与状态;底部「完成」退后台并开始计时。
 */
public class MainActivity extends Activity {

    private static final int REQ_PASSWORD = 1;
    private static final int REQ_IMPORT = 2;

    // 科技风配色
    static final String BG = "#0B1526";
    static final String CARD = "#14263F";
    static final String CARD_WARN = "#2B1E0E";
    static final String ACCENT = "#2E63E7";
    static final String ACCENT_CYAN = "#4FC3F7";
    static final String OK_GREEN = "#2E9E5B";
    static final String OFF_GRAY = "#546E7A";

    private Prefs prefs;
    private LanServer lanServer;
    private LinearLayout colLeft;   // 左栏内容容器(在 ScrollView 内)
    private LinearLayout colRight;  // 右栏内容容器(在 ScrollView 内)
    private TextView tvWatchStatus; // 标题下方常驻状态栏(一眼可见)
    private TextView tvDiag;        // 诊断详情(右栏底部,默认折叠)
    private TextView tvDiagToggle;  // 诊断展开/收起开关
    private final android.os.Handler statusTick = new android.os.Handler();
    private final Runnable statusTicker = new Runnable() {
        @Override
        public void run() {
            updateWatchStatus();
            statusTick.postDelayed(this, 1000);
        }
    };

    private boolean unlocked;
    private boolean askingPassword; // 防止重复拉起密码页

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        prefs = new Prefs(this);
        lanServer = LanServer.get(this);

        buildUi();
        if (prefs.getBool(Prefs.KEY_SERVICE_ON, true)) {
            ScreenReceiver.start(this);
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQ_PASSWORD) {
            askingPassword = false;
            unlocked = resultCode == RESULT_OK;
            if (unlocked) {
                renderPanels();
            } else {
                finish(); // 不输密码不得使用设置
            }
        } else if (requestCode == REQ_IMPORT) {
            if (resultCode == RESULT_OK && data != null && data.getData() != null) {
                importFromUri(data.getData());
            }
        }
    }

    /** Android 13+ 需要运行时请求通知权限,否则系统通知开关是灰色的无法打开。 */
    private void askNotificationPermission() {
        if (Build.VERSION.SDK_INT < 33) return;
        try {
            if (checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS)
                    == android.content.pm.PackageManager.PERMISSION_GRANTED) return;
            if (prefs.getBool(Prefs.KEY_NOTIF_ASKED, false)) return;
            prefs.putBool(Prefs.KEY_NOTIF_ASKED, true);
            requestPermissions(new String[]{
                    android.Manifest.permission.POST_NOTIFICATIONS}, 100);
        } catch (Exception ignored) {
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        MonitorService.enterConfigUi(); // 家长在配置,暂停计时
        askNotificationPermission();
        statusTicker.run(); // 状态栏立即刷新并每秒更新
        if (!unlocked && !askingPassword) {
            // 每次进入/回到本页都必须先通过家长密码
            askingPassword = true;
            Intent it = new Intent(this, PasswordActivity.class);
            it.putExtra(PasswordActivity.EXTRA_MODE,
                    prefs.hasPassword() ? PasswordActivity.MODE_VERIFY : PasswordActivity.MODE_SET);
            startActivityForResult(it, REQ_PASSWORD);
        } else if (unlocked) {
            renderPanels();
        }
    }

    @Override
    protected void onPause() {
        super.onPause();
        statusTick.removeCallbacks(statusTicker);
        MonitorService.exitConfigUi();
    }

    @Override
    protected void onStop() {
        super.onStop();
        // 仅在真正离开前台(回桌面/息屏/切其他应用)时上锁;
        // 应用内导航(子页面在上方)保持解锁,交互友好。
        if (!MonitorService.isConfigUiVisible()) {
            unlocked = false;
        }
    }

    // ---- 导入(U盘/本地) ----

    private void pickImportFile() {
        try {
            Intent it = new Intent(Intent.ACTION_OPEN_DOCUMENT);
            it.addCategory(Intent.CATEGORY_OPENABLE);
            it.setType("*/*");
            startActivityForResult(it, REQ_IMPORT);
        } catch (Exception e) {
            Toast.makeText(this, "无法打开文件选择器:" + e.getMessage(), Toast.LENGTH_LONG).show();
        }
    }

    private void importFromUri(final android.net.Uri uri) {
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
                    String json = new String(bos.toByteArray(), java.nio.charset.StandardCharsets.UTF_8);
                    if (json.startsWith("﻿")) json = json.substring(1);
                    String name = "usb_" + System.currentTimeMillis() + ".json";
                    final int count = QuestionBank.importBank(MainActivity.this, name, json);
                    MonitorService.reloadBank(MainActivity.this);
                    toastOnUiThread("导入成功,共 " + count + " 题");
                } catch (final Exception e) {
                    toastOnUiThread("导入失败:" + e.getMessage());
                }
            }
        }).start();
    }

    private void toastOnUiThread(final String msg) {
        runOnUiThread(new Runnable() {
            @Override
            public void run() {
                Toast.makeText(MainActivity.this, msg, Toast.LENGTH_LONG).show();
                if (unlocked) renderPanels();
            }
        });
    }

    // ---- UI ----

    private void buildUi() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(Color.parseColor(BG));
        int pad = dp(24);
        root.setPadding(pad, dp(12), pad, dp(12));

        TextView title = new TextView(this);
        title.setText(R.string.main_title);
        title.setTextSize(TypedValue.COMPLEX_UNIT_PX, dp(40));
        title.setTextColor(Color.WHITE);
        title.setTypeface(null, Typeface.BOLD);
        title.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams tp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        tp.bottomMargin = dp(6);
        title.setLayoutParams(tp);
        root.addView(title);

        // 常驻状态栏:一进设置页第一眼就能看到本轮观看情况
        tvWatchStatus = new TextView(this);
        tvWatchStatus.setTextSize(TypedValue.COMPLEX_UNIT_PX, dp(28));
        tvWatchStatus.setTextColor(Color.parseColor("#4FC3F7"));
        tvWatchStatus.setGravity(Gravity.CENTER);
        tvWatchStatus.setPadding(0, dp(4), 0, dp(4));
        root.addView(tvWatchStatus, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        boolean landscape = getResources().getConfiguration().orientation
                == android.content.res.Configuration.ORIENTATION_LANDSCAPE;

        colLeft = newPanel();
        colRight = newPanel();

        if (landscape) {
            // 横屏:左右双栏,每栏 = 固定标题 + 可滚动内容区
            LinearLayout columns = new LinearLayout(this);
            columns.setOrientation(LinearLayout.HORIZONTAL);
            columns.setGravity(Gravity.CENTER);
            columns.addView(makeColumn("⚙ 监控规则", colLeft),
                    new LinearLayout.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT, 1f));
            columns.addView(makeColumn("📚 题库与状态", colRight),
                    new LinearLayout.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT, 1f));
            root.addView(columns, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
        } else {
            // 竖屏:单列上下排列,整页滚动
            ScrollView pageScroll = new ScrollView(this);
            pageScroll.setFillViewport(true);
            pageScroll.setVerticalScrollBarEnabled(false);
            LinearLayout page = new LinearLayout(this);
            page.setOrientation(LinearLayout.VERTICAL);
            pageScroll.addView(page, new ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

            page.addView(sectionTitle("⚙ 监控规则"));
            page.addView(colLeft);
            page.addView(sectionTitle("📚 题库与状态"));
            page.addView(colRight);

            root.addView(pageScroll, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
        }

        // 「完成」按钮:保存并退到后台,开始计时
        Button done = new Button(this, null, android.R.attr.borderlessButtonStyle);
        done.setText("完 成  ·  开 始 守 护");
        done.setTextSize(TypedValue.COMPLEX_UNIT_PX, dp(30));
        done.setTextColor(Color.WHITE);
        done.setAllCaps(false);
        done.setIncludeFontPadding(false);
        done.setPadding(dp(16), dp(14), dp(16), dp(14));
        done.setMinimumHeight(dp(64));
        done.setBackground(roundBg(OK_GREEN, dp(14)));
        done.setFocusable(true);
        done.setOnFocusChangeListener(focusGlow());
        done.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (prefs.getBool(Prefs.KEY_SERVICE_ON, true)) {
                    ScreenReceiver.start(MainActivity.this);
                }
                moveTaskToBack(true); // 退到后台,配置界面隐藏后计时自动恢复
                Toast.makeText(MainActivity.this, "守护已开始", Toast.LENGTH_SHORT).show();
            }
        });
        LinearLayout.LayoutParams doneLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        doneLp.topMargin = dp(12);
        root.addView(done, doneLp);

        setContentView(root);
        if (unlocked) renderPanels();
    }

    /** 竖屏模式下的栏目标题。 */
    private TextView sectionTitle(String title) {
        TextView t = new TextView(this);
        t.setText(title);
        t.setTextSize(TypedValue.COMPLEX_UNIT_PX, dp(30));
        t.setTextColor(Color.parseColor(ACCENT_CYAN));
        t.setTypeface(null, Typeface.BOLD);
        t.setGravity(Gravity.CENTER);
        t.setMinHeight(dp(64));
        LinearLayout.LayoutParams tp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        tp.topMargin = dp(8);
        t.setLayoutParams(tp);
        return t;
    }

    /** 一栏 = 固定栏目标题 + ScrollView 内容。 */
    private LinearLayout makeColumn(String title, LinearLayout content) {
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setPadding(dp(10), 0, dp(10), 0);

        TextView t = new TextView(this);
        t.setText(title);
        t.setTextSize(TypedValue.COMPLEX_UNIT_PX, dp(30));
        t.setTextColor(Color.parseColor(ACCENT_CYAN));
        t.setTypeface(null, Typeface.BOLD);
        t.setGravity(Gravity.CENTER);
        t.setMinHeight(dp(64));
        t.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams tp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        tp.bottomMargin = dp(4);
        col.addView(t, tp);

        ScrollView sc = new ScrollView(this);
        sc.setFillViewport(true);
        sc.setVerticalScrollBarEnabled(false);
        sc.addView(content, new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        col.addView(sc, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
        return col;
    }

    private LinearLayout newPanel() {
        LinearLayout p = new LinearLayout(this);
        p.setOrientation(LinearLayout.VERTICAL);
        int side = dp(14);
        p.setPadding(side, 0, side, 0);
        return p;
    }

    /** 卡片容器:每行一张圆角卡,彼此留出间隔。 */
    private LinearLayout card(String bg) {
        LinearLayout c = new LinearLayout(this);
        c.setOrientation(LinearLayout.VERTICAL);
        c.setPadding(dp(18), dp(14), dp(18), dp(14));
        c.setBackground(roundBg(bg, dp(14)));
        c.setFocusable(true);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(12);
        c.setLayoutParams(lp);
        return c;
    }

    private void renderPanels() {
        if (colLeft == null) return;
        colLeft.removeAllViews();
        colRight.removeAllViews();

        // ===== 左栏:监控规则 =====
        final boolean serviceOn = prefs.getBool(Prefs.KEY_SERVICE_ON, true);
        LinearLayout c1 = card(CARD);
        c1.addView(rowWithToggle("防沉迷监控", serviceOn, new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                prefs.putBool(Prefs.KEY_SERVICE_ON, !serviceOn);
                if (!serviceOn) {
                    ScreenReceiver.start(MainActivity.this);
                } else {
                    MonitorService svc = MonitorService.instance();
                    if (svc != null) svc.stopSelf();
                }
                renderPanels();
            }
        }));
        colLeft.addView(c1);

        LinearLayout c2 = card(CARD);
        c2.addView(stepperRow("弹题间隔", intervalText(), -60, 60, new StepperCallback() {
            @Override
            public void onChange(int delta) {
                prefs.putInt(Prefs.KEY_INTERVAL, clamp(prefs.intervalSec() + delta, 60, 24 * 3600));
                renderPanels();
            }
        }));
        c2.addView(hGap());
        c2.addView(stepperRow("连错锁定", prefs.maxWrong() + " 题", -1, 1, new StepperCallback() {
            @Override
            public void onChange(int delta) {
                prefs.putInt(Prefs.KEY_MAX_WRONG, clamp(prefs.maxWrong() + delta, 1, 10));
                renderPanels();
            }
        }));
        c2.addView(hGap());
        c2.addView(stepperRow("答错锁屏", lockText(), -60, 60, new StepperCallback() {
            @Override
            public void onChange(int delta) {
                prefs.putInt(Prefs.KEY_LOCK_SEC, clamp(prefs.lockSec() + delta, 60, 24 * 3600));
                renderPanels();
            }
        }));
        colLeft.addView(c2);

        // 强制休息(独立于答题,每连续观看N分钟直接锁定M分钟)
        LinearLayout c3 = card(CARD);
        c3.addView(stepperRow("连续观看", forceIntervalText(), -300, 300, new StepperCallback() {
            @Override
            public void onChange(int delta) {
                prefs.putInt(Prefs.KEY_FORCE_INTERVAL,
                        clamp(prefs.forceIntervalSec() + delta, 0, 24 * 3600));
                renderPanels();
            }
        }));
        c3.addView(hGap());
        c3.addView(stepperRow("强制休息", (prefs.forceLockSec() / 60) + " 分钟", -300, 300,
                new StepperCallback() {
                    @Override
                    public void onChange(int delta) {
                        prefs.putInt(Prefs.KEY_FORCE_LOCK,
                                clamp(prefs.forceLockSec() + delta, 30, 24 * 3600));
                        renderPanels();
                    }
                }));
        c3.addView(hGap());
        TextView forceTip = smallLabel("连续观看达到设定时长后,不看题直接强制休息");
        c3.addView(forceTip);
        colLeft.addView(c3);

        LinearLayout c4 = card(CARD);
        c4.addView(rowWithToggle("开机自启", prefs.bootStart(), new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                prefs.putBool(Prefs.KEY_BOOT_START, !prefs.bootStart());
                renderPanels();
            }
        }));
        colLeft.addView(c4);

        Button pwdBtn = bigButton("修改家长密码", "#37474F");
        pwdBtn.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                Intent it = new Intent(MainActivity.this, PasswordActivity.class);
                it.putExtra(PasswordActivity.EXTRA_MODE, PasswordActivity.MODE_CHANGE);
                startActivity(it);
            }
        });
        colLeft.addView(fullCard(pwdBtn));
        colLeft.addView(fullCard(bigButton("📖 使用说明(必读)", "#00695C",
                new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        startActivity(new Intent(MainActivity.this, HelpActivity.class));
                    }
                })));


        // ===== 右栏:题库与状态 =====
        // 统一权限配置入口(内部逐项检测并打勾)
        LinearLayout permCard = card(CARD);
        TextView permTitle = new TextView(this);
        permTitle.setText("🔐 权限配置");
        permTitle.setTextSize(TypedValue.COMPLEX_UNIT_PX, dp(28));
        permTitle.setTextColor(Color.WHITE);
        permTitle.setTypeface(null, Typeface.BOLD);
        permCard.addView(permTitle);
        TextView permDesc = new TextView(this);
        permDesc.setText("悬浮窗 / 后台运行 / 电池优化 / 通知,进入后逐项检测,已开启的显示绿色对勾");
        permDesc.setTextSize(TypedValue.COMPLEX_UNIT_PX, dp(22));
        permDesc.setTextColor(Color.parseColor("#90CAF9"));
        permDesc.setPadding(0, dp(6), 0, 0);
        permDesc.setLineSpacing(dp(4), 1f);
        permCard.addView(permDesc);
        Button permBtn = bigButton("进入权限配置", ACCENT,
                new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        startActivity(new Intent(MainActivity.this, PermissionActivity.class));
                    }
                });
        LinearLayout.LayoutParams permLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        permLp.topMargin = dp(10);
        permCard.addView(permBtn, permLp);
        colRight.addView(permCard);

        colRight.addView(fullCard(bigButton("题库管理(查看 / 删除)", ACCENT,
                new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        startActivity(new Intent(MainActivity.this, BankActivity.class));
                    }
                })));
        colRight.addView(fullCard(bigButton("答题统计(正确率 / 毕业题目)", ACCENT,
                new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        startActivity(new Intent(MainActivity.this, StatsActivity.class));
                    }
                })));
        colRight.addView(fullCard(bigButton("从 U盘 / 本地导入题库", ACCENT,
                new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        pickImportFile();
                    }
                })));

        final boolean lanOn = lanServer.isRunning();
        LinearLayout lanCard = card(CARD);
        lanCard.addView(rowWithToggle("局域网上传题库", lanOn, new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (lanServer.isRunning()) {
                    lanServer.stop();
                } else {
                    lanServer.start();
                    Toast.makeText(MainActivity.this,
                            "已开启,请手机/电脑浏览器访问 http://"
                                    + LanServer.getLocalIp() + ":" + LanServer.PORT,
                            Toast.LENGTH_LONG).show();
                }
                renderPanels();
            }
        }));
        if (lanOn) {
            // 开启时给出明确指引:提示家长在手机/电脑端访问该网页上传题库
            TextView prompt = new TextView(this);
            prompt.setText("📱 请家长在手机或电脑的浏览器中访问以下网页,上传题库文件:");
            prompt.setTextSize(TypedValue.COMPLEX_UNIT_PX, dp(24));
            prompt.setTextColor(Color.WHITE);
            prompt.setLineSpacing(dp(4), 1f);
            prompt.setPadding(0, dp(10), 0, dp(4));
            lanCard.addView(prompt);

            TextView urlView = new TextView(this);
            urlView.setText("http://" + LanServer.getLocalIp() + ":" + LanServer.PORT);
            urlView.setTextSize(TypedValue.COMPLEX_UNIT_PX, dp(36));
            urlView.setTextColor(Color.parseColor("#4FC3F7"));
            urlView.setTypeface(null, Typeface.BOLD);
            urlView.setGravity(Gravity.CENTER);
            urlView.setPadding(0, dp(8), 0, dp(8));
            urlView.setTextIsSelectable(true);
            lanCard.addView(urlView);

            TextView hint = new TextView(this);
            hint.setText("手机与电视需连接同一个 WiFi;上传成功后电视会提示导入结果。");
            hint.setTextSize(TypedValue.COMPLEX_UNIT_PX, dp(22));
            hint.setTextColor(Color.parseColor("#78909C"));
            hint.setPadding(0, dp(2), 0, 0);
            lanCard.addView(hint);
        }
        colRight.addView(lanCard);

        // 诊断(放在局域网上传下方,默认折叠)
        tvDiagToggle = new TextView(this);
        tvDiagToggle.setText("▸ 诊断");
        tvDiagToggle.setTextSize(TypedValue.COMPLEX_UNIT_PX, dp(22));
        tvDiagToggle.setTextColor(Color.parseColor("#607D8B"));
        tvDiagToggle.setGravity(Gravity.END | Gravity.CENTER_VERTICAL);
        tvDiagToggle.setPadding(0, dp(10), dp(8), dp(2));
        colRight.addView(tvDiagToggle, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        tvDiag = new TextView(this);
        tvDiag.setTextSize(TypedValue.COMPLEX_UNIT_PX, dp(20));
        tvDiag.setTextColor(Color.parseColor("#607D8B"));
        tvDiag.setGravity(Gravity.CENTER);
        tvDiag.setVisibility(View.GONE);
        tvDiag.setText(buildDiagText());
        tvDiag.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                openVendorPermissionPage();
            }
        });
        colRight.addView(tvDiag, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        tvDiagToggle.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                boolean show = tvDiag.getVisibility() != View.VISIBLE;
                tvDiag.setVisibility(show ? View.VISIBLE : View.GONE);
                tvDiagToggle.setText(show ? "▾ 诊断" : "▸ 诊断");
                if (show) tvDiag.setText(buildDiagText());
            }
        });

        
        // 版本信息
        TextView ver = new TextView(this);
        ver.setText("视界守卫者 v" + appVersion() + " · by WangQingshan");
        ver.setTextSize(TypedValue.COMPLEX_UNIT_PX, dp(22));
        ver.setTextColor(Color.parseColor("#546E7A"));
        ver.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams verLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        verLp.topMargin = dp(10);
        colRight.addView(ver, verLp);
    }

    private String buildDiagText() {
        StringBuilder sb = new StringBuilder();
        try {
            // 设备与系统
            android.util.DisplayMetrics dm = getResources().getDisplayMetrics();
            int orientation = getResources().getConfiguration().orientation;
            sb.append("【设备】").append(Build.MANUFACTURER).append(" ").append(Build.MODEL)
                    .append(" / Android ").append(Build.VERSION.RELEASE)
                    .append(" (SDK").append(Build.VERSION.SDK_INT).append(")\n");
            sb.append("【屏幕】").append(dm.widthPixels).append("x").append(dm.heightPixels)
                    .append(" @").append(dm.densityDpi).append("dpi | ")
                    .append(orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE
                            ? "横屏" : "竖屏")
                    .append(" | 亮屏:").append(isScreenOnNow() ? "√" : "×")
                    .append("\n");

            // 版本
            sb.append("【版本】v").append(appVersion()).append("\n");

            // 权限检测
            boolean overlayOk = Build.VERSION.SDK_INT < 23 || Settings.canDrawOverlays(this);
            boolean notifOk = isNotificationOk();
            android.os.PowerManager pm = (android.os.PowerManager) getSystemService(POWER_SERVICE);
            boolean batteryOk = pm != null
                    && pm.isIgnoringBatteryOptimizations(getPackageName());
            boolean bgOk = prefs.getBool(Prefs.KEY_BG_CONFIRMED, false);
            sb.append("【权限】悬浮窗").append(overlayOk ? "√" : "×")
                    .append(" 通知").append(notifOk ? "√" : "×")
                    .append(" 电池白名单").append(batteryOk ? "√" : "×")
                    .append(" 后台(手动)").append(bgOk ? "√" : "×")
                    .append("\n");

            // 服务与计时状态
            long lockUntil = prefs.getLong(Prefs.KEY_LOCK_UNTIL, 0);
            int watched = prefs.getInt(Prefs.KEY_WATCHED, 0);
            int pending = prefs.getInt(Prefs.KEY_QUIZ_PENDING, 0);
            String state = lockUntil > System.currentTimeMillis() ? "休息锁定中"
                    : (pending == 1 ? "待答题" : "正常计时");
            sb.append("【服务】运行").append(MonitorService.isServiceAliveForDiag() ? "√" : "×")
                    .append(" | 状态:").append(state)
                    .append(" | 已看:").append(watched).append("秒/间隔:")
                    .append(prefs.intervalSec()).append("秒\n");

            // 弹窗通道
            sb.append("【弹窗】Activity创建").append(QuizActivity.createCount)
                    .append("/聚焦").append(QuizActivity.focusCount)
                    .append(" | 悬浮窗失败").append(MonitorService.getDisplayFailCount())
                    .append(" | 悬浮窗显示中:").append(MonitorService.isOverlayActive() ? "√" : "×")
                    .append("\n");

            // 题库
            QuestionBank bank = new QuestionBank(this);
            int total = bank.load(this);
            sb.append("【题库】共").append(total).append(" 待答").append(bank.activeCount())
                    .append(" 毕业").append(bank.graduatedCount())
                    .append(" | 局域网:").append(lanServer != null && lanServer.isRunning() ? "√" : "×");
        } catch (Throwable t) {
            sb.append("诊断信息采集异常: ").append(t);
        }
        return sb.toString();
    }

    private boolean isScreenOnNow() {
        android.os.PowerManager pm = (android.os.PowerManager) getSystemService(POWER_SERVICE);
        return pm == null || pm.isInteractive();
    }

    private boolean isNotificationOk() {
        try {
            if (Build.VERSION.SDK_INT >= 33
                    && checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS)
                    != android.content.pm.PackageManager.PERMISSION_GRANTED) {
                return false;
            }
            android.app.NotificationManager nm =
                    (android.app.NotificationManager) getSystemService(NOTIFICATION_SERVICE);
            return Build.VERSION.SDK_INT < 24 || nm == null || nm.areNotificationsEnabled();
        } catch (Throwable t) {
            return false;
        }
    }

    /** 打开各厂商的后台权限设置页(自启动/后台弹出/关联启动),失败则退回应用详情。 */
    private void openVendorPermissionPage() {
        String mfgr = android.os.Build.MANUFACTURER == null
                ? "" : android.os.Build.MANUFACTURER.toLowerCase();
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
            startActivity(new Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                    android.net.Uri.parse("package:" + getPackageName())));
        } catch (Exception e) {
            Toast.makeText(this, "请到系统设置中手动开启后台权限", Toast.LENGTH_LONG).show();
        }
    }

    private void updateWatchStatus() {
        if (tvWatchStatus == null) return;
        int watched = prefs.getInt(Prefs.KEY_WATCHED, 0);
        long lockUntil = prefs.getLong(Prefs.KEY_LOCK_UNTIL, 0);
        String txt;
        if (lockUntil > System.currentTimeMillis()) {
            long remain = (lockUntil - System.currentTimeMillis()) / 1000;
            txt = "🔒 休息中 剩余 " + remain / 60 + ":" + String.format("%02d", remain % 60);
            tvWatchStatus.setTextColor(Color.parseColor("#FF8A65"));
        } else if (prefs.getInt(Prefs.KEY_QUIZ_PENDING, 0) == 1) {
            txt = "⏰ 弹题待显示";
            tvWatchStatus.setTextColor(Color.parseColor("#FFB74D"));
        } else {
            txt = "已观看: " + watched / 60 + "分" + String.format("%02d", watched % 60) + "秒";
            tvWatchStatus.setTextColor(Color.parseColor("#4FC3F7"));
        }
        tvWatchStatus.setGravity(Gravity.END | Gravity.CENTER_VERTICAL);
        tvWatchStatus.setPadding(0, dp(2), dp(8), dp(2));
        tvWatchStatus.setText(txt);
        if (tvDiag != null && tvDiag.getVisibility() == View.VISIBLE) {
            tvDiag.setText(buildDiagText());
        }
    }

    private String appVersion() {
        try {
            return getPackageManager().getPackageInfo(getPackageName(), 0).versionName;
        } catch (Exception e) {
            return "?";
        }
    }

    private String intervalText() {
        int v = prefs.intervalSec();
        return v % 60 == 0 ? (v / 60) + " 分钟" : v + " 秒";
    }

    private String lockText() {
        int v = prefs.lockSec();
        return v % 60 == 0 ? (v / 60) + " 分钟" : v + " 秒";
    }

    private String forceIntervalText() {
        int v = prefs.forceIntervalSec();
        return v == 0 ? "关闭" : (v % 60 == 0 ? (v / 60) + " 分钟" : v + " 秒");
    }

    private int clamp(int v, int min, int max) {
        return v < min ? min : (v > max ? max : v);
    }

    // ---- 控件工厂 ----

    private interface StepperCallback {
        void onChange(int delta);
    }

    private TextView panelTitle(String text) {
        TextView t = new TextView(this);
        t.setText(text);
        t.setTextSize(TypedValue.COMPLEX_UNIT_PX, dp(30));
        t.setTextColor(Color.parseColor(ACCENT_CYAN));
        t.setTypeface(null, Typeface.BOLD);
        t.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(4);
        lp.bottomMargin = dp(2);
        t.setLayoutParams(lp);
        return t;
    }

    /** 无卡片内容的宽松包装(用于提示文字)。 */
    private LinearLayout wrapLoose(View v) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(8);
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.addView(v, lp);
        return box;
    }

    /** 整行卡片按钮。 */
    private LinearLayout fullCard(View v) {
        LinearLayout c = card(CARD);
        c.addView(v, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        return c;
    }

    private LinearLayout hGap() {
        View v = new View(this);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(12));
        return single(v, lp);
    }

    private LinearLayout single(View v, LinearLayout.LayoutParams lp) {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.addView(v, lp);
        return box;
    }

    /** 卡片内一行:左标签 + 右开关按钮。 */
    private LinearLayout rowWithToggle(String name, boolean on, View.OnClickListener listener) {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.HORIZONTAL);
        box.setGravity(Gravity.CENTER_VERTICAL);
        TextView label = new TextView(this);
        label.setText(name);
        label.setTextSize(TypedValue.COMPLEX_UNIT_PX, dp(28));
        label.setTextColor(Color.WHITE);
        label.setGravity(Gravity.CENTER_VERTICAL);
        label.setMinHeight(dp(56));
        label.setLayoutParams(new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f));
        box.addView(label);

        Button btn = new Button(this, null, android.R.attr.borderlessButtonStyle);
        btn.setText(on ? "已开启" : "已关闭");
        btn.setTextSize(TypedValue.COMPLEX_UNIT_PX, dp(26));
        btn.setTextColor(Color.WHITE);
        btn.setAllCaps(false);
        btn.setIncludeFontPadding(false);
        btn.setPadding(dp(24), dp(10), dp(24), dp(10));
        btn.setMinimumHeight(dp(56));
        btn.setBackground(roundBg(on ? OK_GREEN : OFF_GRAY, dp(28)));
        btn.setFocusable(true);
        btn.setOnClickListener(listener);
        btn.setOnFocusChangeListener(focusGlow());
        LinearLayout.LayoutParams bp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        bp.leftMargin = dp(10);
        box.addView(btn, bp);
        return box;
    }

    /** 卡片内一行:标签 + 数值 + 圆形加减按钮。 */
    private LinearLayout stepperRow(String name, String value, final int stepDown, final int stepUp,
                                    final StepperCallback cb) {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.HORIZONTAL);
        box.setGravity(Gravity.CENTER_VERTICAL);
        TextView label = new TextView(this);
        label.setText(name);
        label.setTextSize(TypedValue.COMPLEX_UNIT_PX, dp(28));
        label.setTextColor(Color.WHITE);
        label.setMinHeight(dp(56));
        label.setGravity(Gravity.CENTER_VERTICAL);
        label.setLayoutParams(new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f));
        box.addView(label);

        final TextView val = new TextView(this);
        val.setText(value);
        val.setTextSize(TypedValue.COMPLEX_UNIT_PX, dp(28));
        val.setTextColor(Color.parseColor("#FFD54F"));
        val.setGravity(Gravity.CENTER);
        val.setMinWidth(dp(130));
        box.addView(val, new LinearLayout.LayoutParams(dp(150), dp(64)));

        Button minus = roundBtn("－");
        minus.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                cb.onChange(stepDown);
            }
        });
        box.addView(minus, new LinearLayout.LayoutParams(dp(72), dp(64)));

        Button plus = roundBtn("＋");
        plus.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                cb.onChange(stepUp);
            }
        });
        LinearLayout.LayoutParams pp = new LinearLayout.LayoutParams(dp(72), dp(64));
        pp.leftMargin = dp(10);
        box.addView(plus, pp);
        return box;
    }

    private Button roundBtn(String text) {
        Button b = new Button(this, null, android.R.attr.borderlessButtonStyle);
        b.setText(text);
        b.setTextSize(TypedValue.COMPLEX_UNIT_PX, dp(26));
        b.setTextColor(Color.WHITE);
        b.setIncludeFontPadding(false);
        b.setPadding(0, dp(6), 0, dp(6));
        b.setMinimumHeight(dp(48));
        b.setBackground(roundBg("#233B60", dp(32)));
        b.setFocusable(true);
        b.setOnFocusChangeListener(focusGlow());
        return b;
    }

    private Button bigButton(String text, String color) {
        return bigButton(text, color, null);
    }

    private Button bigButton(String text, String color, View.OnClickListener listener) {
        Button b = new Button(this, null, android.R.attr.borderlessButtonStyle);
        b.setText(text);
        b.setTextSize(TypedValue.COMPLEX_UNIT_PX, dp(28));
        b.setTextColor(Color.WHITE);
        b.setAllCaps(false);
        b.setIncludeFontPadding(false);
        b.setPadding(dp(16), dp(14), dp(16), dp(14));
        b.setMinimumHeight(dp(56));
        b.setGravity(Gravity.CENTER);
        b.setBackground(roundBg(color, dp(12)));
        b.setFocusable(true);
        b.setOnFocusChangeListener(focusGlow());
        if (listener != null) b.setOnClickListener(listener);
        return b;
    }

    private TextView smallLabel(String text) {
        TextView t = new TextView(this);
        t.setText(text);
        t.setTextSize(TypedValue.COMPLEX_UNIT_PX, dp(22));
        t.setTextColor(Color.parseColor("#78909C"));
        t.setLineSpacing(dp(4), 1f);
        return t;
    }

    private View.OnFocusChangeListener focusGlow() {
        return new View.OnFocusChangeListener() {
            @Override
            public void onFocusChange(View v, boolean hasFocus) {
                v.setScaleX(hasFocus ? 1.03f : 1f);
                v.setScaleY(hasFocus ? 1.03f : 1f);
            }
        };
    }

    private GradientDrawable roundBg(String color, float radiusPx) {
        GradientDrawable d = new GradientDrawable();
        d.setCornerRadius(radiusPx);
        d.setColor(Color.parseColor(color));
        return d;
    }

    private int dp(int v) {
        return UiUtil.px(this, v);
    }
}
