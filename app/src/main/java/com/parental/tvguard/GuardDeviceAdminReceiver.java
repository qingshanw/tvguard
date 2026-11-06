package com.parental.tvguard;

import android.app.admin.DeviceAdminReceiver;

/**
 * 设备管理员接收器:本身不启用任何强制策略,
 * 仅用于支持可选的"设备所有者"模式,让屏幕固定(锁定任务)静默生效。
 */
public class GuardDeviceAdminReceiver extends DeviceAdminReceiver {
}
