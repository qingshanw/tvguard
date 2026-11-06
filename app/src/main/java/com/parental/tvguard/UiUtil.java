package com.parental.tvguard;

import android.content.Context;
import android.util.DisplayMetrics;

/**
 * UI 自适应工具:以"屏幕短边 = 1080px"为设计基准,
 * 所有尺寸/字号按比例缩放,在 720p 电视到 2800x1280 高分手机上都完整显示。
 */
public final class UiUtil {

    // 设计基准字号(短边1080时的像素值)
    public static final int TEXT_TITLE = 56;
    public static final int TEXT_QUESTION = 44;
    public static final int TEXT_OPTION = 34;
    public static final int TEXT_LABEL = 26;
    public static final int TEXT_TIMER = 120;

    private UiUtil() {
    }

    /** 缩放系数 = 屏幕短边 / 1080,与横竖屏、密度无关。 */
    public static float scale(Context c) {
        DisplayMetrics m = c.getResources().getDisplayMetrics();
        int min = Math.min(m.widthPixels, m.heightPixels);
        float s = min / 1080f;
        if (s < 0.4f) s = 0.4f;
        if (s > 3f) s = 3f;
        return s;
    }

    /** 把"设计像素"换算为当前屏幕实际像素。 */
    public static int px(Context c, int designPx) {
        return Math.round(designPx * scale(c));
    }
}
