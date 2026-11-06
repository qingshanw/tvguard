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

import android.app.admin.DeviceAdminReceiver;

/**
 * 设备管理员接收器:本身不启用任何强制策略,
 * 仅用于支持可选的"设备所有者"模式,让屏幕固定(锁定任务)静默生效。
 */
public class GuardDeviceAdminReceiver extends DeviceAdminReceiver {
}
