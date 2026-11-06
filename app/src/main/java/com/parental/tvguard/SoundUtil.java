package com.parental.tvguard;

import android.content.Context;
import android.media.AudioManager;
import android.media.ToneGenerator;
import android.os.Handler;
import android.os.Looper;

/**
 * 答题提示音:答对播放轻快确认音,答错播放错误提示音。
 * 用系统 ToneGenerator 实现,无需任何音频资源文件。
 */
public final class SoundUtil {

    private SoundUtil() {
    }

    /** 答对:上扬的双音"叮咚"。 */
    public static void correct(Context c) {
        tone(c, ToneGenerator.TONE_PROP_ACK, 300);
    }

    /** 答错:低沉的错误提示音。 */
    public static void wrong(Context c) {
        tone(c, ToneGenerator.TONE_SUP_ERROR, 400);
    }

    /** 全部题目毕业时的庆祝音。 */
    public static void cheer(Context c) {
        tone(c, ToneGenerator.TONE_CDMA_CONFIRM, 500);
    }

    private static void tone(Context c, final int tone, final int durationMs) {
        try {
            final ToneGenerator tg = new ToneGenerator(AudioManager.STREAM_MUSIC, 80);
            tg.startTone(tone, durationMs);
            new Handler(Looper.getMainLooper()).postDelayed(new Runnable() {
                @Override
                public void run() {
                    try {
                        tg.release();
                    } catch (Exception ignored) {
                    }
                }
            }, durationMs + 200);
        } catch (Exception ignored) {
            // 部分设备音频资源占用时静默失败,不影响答题流程
        }
    }
}
