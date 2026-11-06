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

import android.content.Context;
import android.media.AudioAttributes;
import android.media.AudioManager;
import android.media.SoundPool;
import android.os.Build;

/**
 * 答题提示音(SoundPool 播放内置音频资源):
 *  - 答对:上扬的钢鼓铃声(约1.4秒,300→470Hz 上行旋律)
 *  - 答错:下行的低沉钢鼓铃声(约1.4秒,420→263Hz 下行旋律)
 *
 * 音效来源:Kenney "Music Jingles" 音效包(kenney.nl),截取
 * jingles_steel_2 / jingles_steel_1,
 * 以 CC0 1.0 通用(CC0 1.0 Universal)许可发布,可自由使用、修改与再分发。
 */
public final class SoundUtil {

    private static SoundPool sPool;
    private static int sIdCorrect;
    private static int sIdWrong;
    private static boolean sLoaded;

    private SoundUtil() {
    }

    /** 答对:上扬的欢快铃声。 */
    public static void correct(Context c) {
        play(c, sIdCorrect);
    }

    /** 答错:下行的低沉铃声。 */
    public static void wrong(Context c) {
        play(c, sIdWrong);
    }

    private static synchronized void ensureLoaded(Context c) {
        if (sPool != null) return;
        try {
            SoundPool.Builder builder;
            if (Build.VERSION.SDK_INT >= 21) {
                AudioAttributes attrs = new AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                        .build();
                builder = new SoundPool.Builder().setMaxStreams(2).setAudioAttributes(attrs);
            } else {
                @SuppressWarnings("deprecation")
                SoundPool legacy = new SoundPool(2, AudioManager.STREAM_MUSIC, 0);
                builder = null;
                sPool = legacy;
            }
            if (sPool == null) sPool = builder.build();
            sPool.setOnLoadCompleteListener(new SoundPool.OnLoadCompleteListener() {
                @Override
                public void onLoadComplete(SoundPool pool, int sampleId, int status) {
                    if (status == 0) sLoaded = true;
                }
            });
            sIdCorrect = sPool.load(c.getApplicationContext(), R.raw.sound_correct, 1);
            sIdWrong = sPool.load(c.getApplicationContext(), R.raw.sound_wrong, 1);
        } catch (Throwable t) {
            // 音频初始化失败时静默,不影响答题流程
            sPool = null;
        }
    }

    private static void play(Context c, int id) {
        if (c == null) return;
        try {
            ensureLoaded(c);
            if (sPool == null || id == 0) return;
            // 未加载完成时播不出声音是常态,静默跳过即可
            float vol = 1.0f;
            sPool.play(id, vol, vol, 1, 0, 1.0f);
        } catch (Throwable t) {
            // 音频设备被占用等情况静默失败,不影响答题流程
        }
    }
}
