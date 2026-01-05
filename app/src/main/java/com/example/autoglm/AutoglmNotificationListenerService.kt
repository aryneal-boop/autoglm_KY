package com.example.autoglm

import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification

/**
 * 通知监听服务（Notification Listener Service, NLS）。
 *
 * **用途**
 * - 作为“通知监听权限”的锚点组件：
 *   - `PermissionUtils`/`AdbPermissionGranter` 会以该组件名为目标检查/尝试启用通知监听。
 * - 当前实现为轻量占位：不对通知做业务处理，但保留扩展空间（例如未来可用于解析通知内容触发自动化）。
 *
 * **引用路径**
 * - `AndroidManifest.xml` 中应声明该服务（若需要 NLS 权限）。
 * - `PermissionUtils.notificationListenerComponent` 返回该组件名。
 *
 * **使用注意事项**
 * - 启用通知监听属于敏感权限：需要用户在系统设置中手动授权，或在 ADB/Shizuku 通道可用时 best-effort 注入。
 */
class AutoglmNotificationListenerService : NotificationListenerService() {
    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        super.onNotificationPosted(sbn)
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification?) {
        super.onNotificationRemoved(sbn)
    }
}
