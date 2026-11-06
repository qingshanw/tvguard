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
import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioTrack;

/**
 * 答题提示音(AudioTrack 实时合成,无需音频资源文件):
 *  - 答对:上扬的清脆"叮—咚"双音,欢快
 *  - 答错:下行的低沉"呜—呜"双音,失落
 *  - 全部毕业:上行三连音庆祝
 */
public final class SoundUtil {

    private static final int SAMPLE_RATE = 22050;

    private SoundUtil() {
    }

    /** 答对:上扬双音 叮—咚(1318Hz → 1568Hz,带高次谐波的清脆音色)。 */
    public static void correct(Context c) {
        play(new double[]{1318.0, 1568.0}, new int[]{130, 220}, 0.9);
    }

    /** 答错:下行低沉双音 呜—呜(320Hz → 240Hz,带颤音的闷音色)。 */
    public static void wrong(Context c) {
        play(new double[]{320.0, 240.0}, new int[]{260, 420}, 0.0);
    }

    /** 毕业庆祝:上行三连音。 */
    public static void cheer(Context c) {
        play(new double[]{1046.5, 1318.5, 1568.0}, new int[]{120, 120, 260}, 1.0);
    }

    /**
     * 合成并播放一段音列。
     * @param freqs     每个音的频率(Hz)
     * @param durations 每个音的时长(毫秒)
     * @param bright    音色亮度 0~1:越大越清脆(高次谐波多),越小越低沉(谐波少+颤音)
     */
    private static void play(final double[] freqs, final int[] durations, final double bright) {
        new Thread(new Runnable() {
            @Override
            public void run() {
                AudioTrack track = null;
                try {
                    int totalMs = 0;
                    for (int d : durations) totalMs += d;
                    int totalSamples = SAMPLE_RATE * totalMs / 1000 + SAMPLE_RATE / 10;
                    short[] buf = new short[totalSamples];

                    int pos = 0;
                    for (int n = 0; n < freqs.length; n++) {
                        double freq = freqs[n];
                        int samples = SAMPLE_RATE * durations[n] / 1000;
                        for (int i = 0; i < samples && pos < buf.length; i++, pos++) {
                            double t = (double) i / SAMPLE_RATE;
                            double progress = (double) i / samples;
                            // 谐波:答对高亮(含2/3次谐波),答错暗淡(仅基波+轻微2次)
                            double w = 2 * Math.PI * freq * t;
                            double wave;
                            if (bright > 0.5) {
                                wave = Math.sin(w) + 0.45 * bright * Math.sin(2 * w)
                                        + 0.2 * bright * Math.sin(3 * w);
                            } else {
                                // 低沉"呜":正弦波 + 轻微颤音(6Hz)
                                double vib = 1.0 + 0.025 * Math.sin(2 * Math.PI * 6 * t);
                                wave = Math.sin(w * vib) + 0.15 * Math.sin(2 * w * vib);
                            }
                            // 包络:快起音,指数衰减,音尾淡出
                            double attack = Math.min(1.0, progress / 0.06);
                            double release = Math.min(1.0, (1.0 - progress) / 0.35);
                            double env = attack * release * Math.exp(-2.2 * progress);
                            double v = wave * env * 0.32;
                            if (v > 1.0) v = 1.0;
                            if (v < -1.0) v = -1.0;
                            buf[pos] = (short) (v * Short.MAX_VALUE);
                        }
                    }

                    track = new AudioTrack(AudioManager.STREAM_MUSIC, SAMPLE_RATE,
                            AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT,
                            buf.length * 2, AudioTrack.MODE_STATIC);
                    track.write(buf, 0, buf.length);
                    track.play();
                    Thread.sleep(totalMs + 150);
                } catch (Throwable e) {
                    // 音频设备被占用等情况静默失败,不影响答题流程
                } finally {
                    if (track != null) {
                        try {
                            track.stop();
                            track.release();
                        } catch (Throwable ignored) {
                        }
                    }
                }
            }
        }, "quiz-sound").start();
    }
}
