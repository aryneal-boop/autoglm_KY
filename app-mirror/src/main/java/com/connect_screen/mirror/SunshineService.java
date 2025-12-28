package com.connect_screen.mirror;

import static android.app.Activity.RESULT_OK;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.hardware.display.DisplayManager;
import android.media.MediaCodecInfo;
import android.media.projection.MediaProjection;
import android.media.projection.MediaProjectionManager;
import android.net.wifi.WifiManager;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.PowerManager;
import android.provider.Settings;
import android.util.Log;

import androidx.core.app.NotificationCompat;

import com.connect_screen.mirror.job.MirrorDisplayMonitor;
import com.connect_screen.mirror.job.MirrorDisplaylinkMonitor;
import com.connect_screen.mirror.job.SunshineServer;
import com.connect_screen.mirror.shizuku.PermissionManager;
import com.connect_screen.mirror.shizuku.ShizukuUtils;

import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.InetAddress;
import java.net.InterfaceAddress;
import java.net.NetworkInterface;
import java.net.SocketException;
import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import javax.jmdns.JmDNS;
import javax.jmdns.ServiceInfo;

public class SunshineService extends Service {
    public static final String ACTION_USB_PERMISSION = "com.connect_screen.mirror.USB_PERMISSION";
    public static SunshineService instance;
    private static final String CHANNEL_ID = "SunshineServiceChannel";
    private static final int NOTIFICATION_ID = 2;
    private static final String TAG = "SunshineService";

    private final BroadcastReceiver usbPermissionReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            android.util.Log.d("SunshineService", "received action: " + intent.getAction());
            String action = intent.getAction();
            if (ACTION_USB_PERMISSION.equals(action)) {
                State.resumeJob();
            }
        }
    };

    private int currentTimeout;

    @Override
    public void onCreate() {
        super.onCreate();
        instance = this;
        createNotificationChannel();
        startForeground(NOTIFICATION_ID, createNotification());
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        instance = null;
        releaseWakeLock();
        try {
            unregisterReceiver(usbPermissionReceiver);
        } catch (Exception e) {
            // ignore
        }
        State.unbindUserService();
    }

    public void releaseWakeLock() {
        if (currentTimeout > 0) {
            Settings.System.putInt(this.getContentResolver(),
                    Settings.System.SCREEN_OFF_TIMEOUT, currentTimeout);
        }
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && intent.hasExtra("data")) {
            MediaProjectionManager mediaProjectionManager = (MediaProjectionManager) getSystemService(Context.MEDIA_PROJECTION_SERVICE);
            Intent data = intent.getParcelableExtra("data");
            State.setMediaProjection(mediaProjectionManager.getMediaProjection(RESULT_OK, data));
            State.getMediaProjection().registerCallback(new MediaProjection.Callback() {
                @Override
                public void onStop() {
                    super.onStop();
                    State.log("MediaProjection onStop 回调");
                }
            }, null);
            State.resumeJob();
        } else {
            State.log("SunshineService 收到错误的授权数据");
            State.resumeJob();
        }
        if (Pref.getPreventAutoLock()) {
            preventAutoLock();
        }
        // 在后台线程中启动 Sunshine 服务器
        String sunshineName = "屏易连-"  + Build.MANUFACTURER + "-" + Build.MODEL;
        SunshineServer.setSunshineName(sunshineName);
        Set<String> ipAddresses = getAllWifiIpAddresses(this);
        probeH265();

        // 将网络初始化操作移到后台线程
        new Thread(() -> {
            try {
                SunshineServer.setFileStatePath(SunshineService.this.getFilesDir().getAbsolutePath() + "/sunshine_state.json");
                writeCertAndKey(SunshineService.this);
                List<JmDNS> dnsServers = new ArrayList<>();
                if(!ipAddresses.isEmpty()) {
                    for (String addr : ipAddresses) {
                        try {
                            JmDNS jmdns = JmDNS.create(InetAddress.getByName(addr));
                            dnsServers.add(jmdns);
                            ServiceInfo serviceInfo = ServiceInfo.create(
                                    "_nvstream._tcp.local.",
                                    "ConnectScreen",
                                    47989,
                                    "ConnectScreen"
                            );

                            jmdns.registerService(serviceInfo);
                            Log.i("SunshineService", "JmDNS服务注册成功，IP: " + addr);
                        } catch (Exception e) {
                            Log.e("SunshineService", "在IP " + addr + " 上注册JmDNS服务失败", e);
                        }
                    }
                }
                new Thread(() -> { 
                    try {
                        SunshineServer.start();
                        for (JmDNS server : dnsServers) {
                            server.close();
                        }
                    } catch(Throwable e) {
                        Log.e("SunshineService", "thread quit", e);
                    }
                }).start();
                if (ipAddresses.isEmpty()) {
                    State.log("无法获取WiFi IP地址");
                } else {
                    State.log("发布 moonlight 服务名："  + sunshineName);
                    for (String addr : ipAddresses) {
                        State.log("发布 moonlight ip："  + addr);
                    }
                }
            } catch (Exception e) {
                Log.e("SunshineService", "初始化网络服务失败", e);
            }
        }).start();

        // 注册 USB 权限广播接收器
        IntentFilter permissionFilter = new IntentFilter(ACTION_USB_PERMISSION);
        registerReceiver(usbPermissionReceiver, permissionFilter, null, null, Context.RECEIVER_EXPORTED);

        // 监听显示器变化
        DisplayManager displayManager = (DisplayManager) getSystemService(Context.DISPLAY_SERVICE);
        MirrorDisplayMonitor.init(displayManager);
        MirrorDisplaylinkMonitor.init(this);
        State.refreshMainActivity();
        Handler handler = new Handler();
        handler.postDelayed(() -> {
            if (ShizukuUtils.hasPermission() && State.userService == null) {
                State.log("try start shizuku user service");
                State.bindUserService();
                handler.postDelayed(() -> {
                    if (ShizukuUtils.hasPermission() && State.userService == null) {
                        State.log("shizuku user service 启动失败，请取消 shizuku 授权并再次授予。try start user service again");
                        State.unbindUserService();
                        State.bindUserService();
                    }
                }, 15 * 1000);
            }
        }, 2000);
        return START_NOT_STICKY;
    }

    private void preventAutoLock() {
        if (!ShizukuUtils.hasPermission()) {
            return;
        }
        if (PermissionManager.grant("android.permission.WRITE_SECURE_SETTINGS")) {
            // 读取当前的屏幕超时设置
            currentTimeout = Settings.System.getInt(this.getContentResolver(),
                    Settings.System.SCREEN_OFF_TIMEOUT, 0);
            Log.i("SunshineService", "当前屏幕超时设置: " + currentTimeout + "ms");
            if (currentTimeout >= 4 * 60 * 60 * 1000) {
                currentTimeout = 15 * 1000;
            }
            Settings.System.putInt(this.getContentResolver(),
                    Settings.System.SCREEN_OFF_TIMEOUT, 4 * 60 * 60 * 1000);
        }
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel serviceChannel = new NotificationChannel(
                CHANNEL_ID,
                "Sunshine Service Channel",
                NotificationManager.IMPORTANCE_LOW
            );
            NotificationManager manager = getSystemService(NotificationManager.class);
            manager.createNotificationChannel(serviceChannel);
        }
    }

    private Notification createNotification() {
        return new NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("屏易连")
            .setContentText("Sunshine 服务正在运行")
            .setSmallIcon(R.mipmap.ic_mirror)
            .build();
    }

    private boolean probeH265() {
        try {
            // 检查设备是否支持 H.265/HEVC 编码
            android.media.MediaCodecList codecList = new android.media.MediaCodecList(android.media.MediaCodecList.REGULAR_CODECS);
            for (android.media.MediaCodecInfo codecInfo : codecList.getCodecInfos()) {
                if (!codecInfo.isHardwareAccelerated()) {
                    continue;
                }
                if (!codecInfo.isEncoder()) {
                    continue;
                }
                if (!isSupported(codecInfo, "video/hevc")) {
                    continue;
                }
                SunshineServer.enableH265();
                return true;
            }
            State.log("设备不支持 H.265/HEVC 编码");
            return false;
        } catch (Exception e) {
            State.log("检查 H.265 编码支持时出错: " + e.getMessage());
            return false;
        }
    }

    private boolean isSupported(MediaCodecInfo codecInfo, String mime) {
        String[] types = codecInfo.getSupportedTypes();
        for (String type : types) {
            if (type.equalsIgnoreCase(mime)) {
                return true;
            }
        }
        return false;
    }

    public static Set<String> getAllWifiIpAddresses(Context context) {
        Set<String> ipAddresses = new HashSet<>();
        
        // 获取WiFi IP地址
        WifiManager wifiManager = (WifiManager) context.getApplicationContext().getSystemService(Context.WIFI_SERVICE);
        if (wifiManager != null && wifiManager.isWifiEnabled()) {
            int ipAddress = wifiManager.getConnectionInfo().getIpAddress();
            if (ipAddress != 0) {
                // Convert little-endian to big-endian if needed
                byte[] bytes = new byte[4];
                bytes[0] = (byte) (ipAddress & 0xFF);
                bytes[1] = (byte) ((ipAddress >> 8) & 0xFF);
                bytes[2] = (byte) ((ipAddress >> 16) & 0xFF);
                bytes[3] = (byte) ((ipAddress >> 24) & 0xFF);

                try {
                    String ip = InetAddress.getByAddress(bytes).getHostAddress();
                    ipAddresses.add(ip);
                } catch (UnknownHostException e) {
                    Log.e(TAG, "获取WiFi IP地址失败", e);
                }
            }
        }
        
        // 获取所有网络接口的IP地址
        try {
            Enumeration<NetworkInterface> networkInterfaces = NetworkInterface.getNetworkInterfaces();
            while (networkInterfaces.hasMoreElements()) {
                NetworkInterface ni = networkInterfaces.nextElement();
                if (ni.isUp() && !ni.isLoopback()) {
                    List<InterfaceAddress> interfaceAddresses = ni.getInterfaceAddresses();
                    for (InterfaceAddress interfaceAddress : interfaceAddresses) {
                        if (interfaceAddress.getAddress() != null) {
                            String ip = interfaceAddress.getAddress().getHostAddress();
                            if (ip != null && ip.startsWith("192.168")) {
                                ipAddresses.add(ip);
                            }
                        }
                    }
                }
            }
        } catch (SocketException e) {
            Log.e(TAG, "获取网络接口IP地址失败", e);
        }
        
        return ipAddresses;
    }

    public static void writeCertAndKey(Context context) {
        try {
            // 写入证书文件
            try (InputStream certInput = context.getAssets().open("cacert.pem");
                 FileOutputStream certOutput = context.openFileOutput("cacert.pem", Context.MODE_PRIVATE)) {
                byte[] buffer = new byte[1024];
                int length;
                while ((length = certInput.read(buffer)) > 0) {
                    certOutput.write(buffer, 0, length);
                }
                SunshineServer.setCertPath(context.getFilesDir().getAbsolutePath() + "/cacert.pem");
            }

            // 写入密钥文件
            try (InputStream keyInput = context.getAssets().open("cakey.pem");
                 FileOutputStream keyOutput = context.openFileOutput("cakey.pem", Context.MODE_PRIVATE)) {
                byte[] buffer = new byte[1024];
                int length;
                while ((length = keyInput.read(buffer)) > 0) {
                    keyOutput.write(buffer, 0, length);
                }
                SunshineServer.setPkeyPath(context.getFilesDir().getAbsolutePath() + "/cakey.pem");
            }

            android.util.Log.i(TAG, "证书和密钥文件写入成功: " + context.getFilesDir().getAbsolutePath());
        } catch (IOException e) {
            android.util.Log.e("TAG", "写入证书文件失败", e);
        }
    }
} 