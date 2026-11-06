package com.parental.tvguard;

import android.content.Context;
import android.graphics.Color;
import android.util.TypedValue;
import android.view.Gravity;
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

        addView(root, new LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
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
