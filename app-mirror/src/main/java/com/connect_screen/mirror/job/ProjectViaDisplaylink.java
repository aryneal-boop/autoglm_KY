package com.connect_screen.mirror.job;

import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.hardware.display.VirtualDisplay;
import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbManager;
import android.media.ImageReader;
import android.os.Handler;
import android.os.HandlerThread;
import android.view.Surface;

import com.connect_screen.mirror.MirrorSettingsActivity;
import com.connect_screen.mirror.Pref;
import com.connect_screen.mirror.SunshineService;
import com.connect_screen.mirror.shizuku.ServiceUtils;
import com.displaylink.manager.NativeDriver;
import com.displaylink.manager.NativeDriverListener;
import com.displaylink.manager.display.DisplayMode;
import com.connect_screen.mirror.MirrorMainActivity;
import com.connect_screen.mirror.State;
import com.connect_screen.mirror.DisplaylinkState;
import com.connect_screen.mirror.shizuku.ShizukuUtils;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;

public class ProjectViaDisplaylink implements Job {
    private final AcquireShizuku acquireShizuku = new AcquireShizuku();
    private boolean usbRequested = false;
    private boolean device2UsbRequested = false;
    private boolean mediaProjectionRequested = false;
    private final String deviceName;
    private final VirtualDisplayArgs virtualDisplayArgs;

    public ProjectViaDisplaylink(UsbDevice device, VirtualDisplayArgs virtualDisplayArgs) {
        this.deviceName = device.getDeviceName();
        this.virtualDisplayArgs = virtualDisplayArgs;
    }

    public void start() throws YieldException {
        Context context = State.getContext();
        if (context == null) {
            State.log("Activity 不存在，跳过任务");
            return;
        }
        UsbManager usbManager = (UsbManager) context.getSystemService(Context.USB_SERVICE);
        DisplaylinkState displaylinkState = State.displaylinkState;

        if (displaylinkState == null) {
            State.log("USB 设备 " + deviceName + " 状态不存在，跳过任务");
            return;
        }

        if (displaylinkState.displaylinkDevice2 != null && displaylinkState.getVirtualDisplay() != null) {
            displaylinkState.destroy();
        }

        if (!requestUsbPermission(context, usbManager, displaylinkState.device)) {
            return;
        }
        if (!requestDevice2UsbPermission(context, usbManager, displaylinkState)) {
            return;
        }
        openUsbConnection(context, usbManager, displaylinkState);
        copyFirmwares(context);
        if (!initializeNativeDriver(context, displaylinkState)) {
            return;
        }
        boolean singleAppMode = Pref.getSingleAppMode();
        if (singleAppMode) {
            if (ShizukuUtils.hasPermission()) {
                String selectedAppPackage = Pref.getSelectedAppPackage();
                createVirtualDisplay(context, State.displaylinkState, selectedAppPackage);
            } else {
                State.showErrorStatus("Displaylink 单应用投屏需要 shizuku 权限");
            }
        } else {
            if (requestMediaProjectionPermission(context, displaylinkState)) {
                displaylinkState.nativeDriver.setMode(displaylinkState.encoderId, new DisplayMode(virtualDisplayArgs.width, virtualDisplayArgs.height, virtualDisplayArgs.refreshRate), virtualDisplayArgs.width * 4, 1);
                new AutoRotateAndScaleForDisplaylink(virtualDisplayArgs, context);
            }
        }
    }


    private void createVirtualDisplay(Context context, DisplaylinkState displaylinkState, String lastPackageName) {
        int singleAppDpi = Pref.getSingleAppDpi();
        virtualDisplayArgs.dpi = singleAppDpi;
        int virtualDisplayWidth = virtualDisplayArgs.width;
        displaylinkState.imageReader = ImageReader.newInstance(virtualDisplayWidth, virtualDisplayArgs.height, 1, 2);
        displaylinkState.handlerThread = new HandlerThread("ImageAvailableListenerThread");
        displaylinkState.handlerThread.start();
        displaylinkState.handler = new Handler(displaylinkState.handlerThread.getLooper());

        displaylinkState.imageReader.setOnImageAvailableListener(new ListenImageReaderAndPostFrame(virtualDisplayArgs), displaylinkState.handler);
        Surface surface = displaylinkState.imageReader.getSurface();
        VirtualDisplay virtualDisplay = State.displaylinkState.getVirtualDisplay();
        if (virtualDisplay == null) {
            virtualDisplay = CreateVirtualDisplay.createVirtualDisplay(virtualDisplayArgs, surface);
            displaylinkState.createdVirtualDisplay(virtualDisplay);
            if (lastPackageName != null) {
                ServiceUtils.launchPackage(context, lastPackageName, virtualDisplay.getDisplay().getDisplayId());
            }
        } else {
            State.log("复用已经存在的 virtual display: " + virtualDisplay.getDisplay().getDisplayId());
            virtualDisplay.setSurface(surface);
        }
        int displayId = virtualDisplay.getDisplay().getDisplayId();
        InputRouting.moveImeToExternal(displayId);
        InputRouting.bindAllExternalInputToDisplay(displayId);
        new Handler().postDelayed(() -> {
            InputRouting.bindAllExternalInputToDisplay(displayId);
        }, 5000);
    }

    private void copyFirmwares(Context context) {
        try {
            String[] files = context.getAssets().list("");
            if (files == null) {
                return;
            }
            for (String file : files) {
                if (!file.endsWith(".spkg")) {
                    continue;
                }
                File targetFile = new File(context.getFilesDir(), file);
                if (targetFile.exists() && targetFile.length() > 0) {
                    State.log("固件文件已存在，跳过: " + file);
                    continue;
                }
                try (InputStream in = context.getAssets().open(file);
                     FileOutputStream out = context.openFileOutput(file, Context.MODE_PRIVATE)) {
                    byte[] buffer = new byte[4096];
                    int read;
                    while ((read = in.read(buffer)) != -1) {
                        out.write(buffer, 0, read);
                    }
                    State.log("成功复制固件文件: " + file);
                } catch (IOException e) {
                    State.log("复制固件文件失败: " + file + ", 错误: " + e.getMessage());
                }
            }
        } catch (IOException e) {
            State.log("复制固件失败: " + e.getMessage());
        }
    }

    private boolean requestUsbPermission(Context context, UsbManager usbManager, UsbDevice device) throws YieldException {
        if (usbManager.hasPermission(device)) {
            State.log("已经拥有USB设备权限: " + device.getDeviceName());
        } else if (usbRequested) {
            State.log("因为未授予USB设备权限: " + device.getDeviceName() + "，跳过任务");
            return false;
        } else {
            usbRequested = true;
            PendingIntent pendingIntent = PendingIntent.getBroadcast(context, 0, new Intent(SunshineService.ACTION_USB_PERMISSION), PendingIntent.FLAG_IMMUTABLE);
            usbManager.requestPermission(device, pendingIntent);
            throw new YieldException("等待用户USB授权");
        }
        return true;
    }

    private void openUsbConnection(Context context, UsbManager usbManager, DisplaylinkState displaylinkState) {
        if (displaylinkState.displaylinkDevice2 == null) {
            if (displaylinkState.usbConnection == null) {
                displaylinkState.usbConnection = usbManager.openDevice(displaylinkState.device);
                if (displaylinkState.usbConnection == null) {
                    throw new RuntimeException("无法打开 USB 设备连接");
                } else {
                    State.log("成功打开 USB 设备连接");
                }
            } else {
                State.log("USB 设备连接已存在");
            }
        } else {
            if (displaylinkState.displaylinkConnection2 == null || displaylinkState.usbConnection == null || displaylinkState.usbConnection.getRawDescriptors() == null) {
                if (displaylinkState.usbConnection != null) {
                    displaylinkState.usbConnection.close();
                }
                displaylinkState.usbConnection = usbManager.openDevice(displaylinkState.device);
                if (displaylinkState.usbConnection == null) {
                    throw new RuntimeException("无法打开 USB 设备连接");
                } else {
                    State.log("成功打开 USB 设备连接");
                }
                displaylinkState.displaylinkConnection2 = usbManager.openDevice(displaylinkState.displaylinkDevice2);
                if (displaylinkState.displaylinkConnection2 == null) {
                    throw new RuntimeException("无法打开第二个 USB 设备连接");
                } else {
                    State.log("成功打开第二个 USB 设备连接");
                }
            } else {
                State.log("第二个 USB 设备连接已存在");
            }
        }
        if (displaylinkState.usbConnection.getRawDescriptors() == null) {
            throw new RuntimeException("USB 连接无法获得 raw descriptors");
        }
    }

    private boolean requestDevice2UsbPermission(Context context, UsbManager usbManager, DisplaylinkState displaylinkState) throws YieldException {
        if (displaylinkState.displaylinkDevice2 == null) {
            for (UsbDevice device : usbManager.getDeviceList().values()) {
                if (device.getDeviceName().equals(displaylinkState.device.getDeviceName())) {
                    continue;
                }
                if (device.getVendorId() == 6121) {
                    displaylinkState.displaylinkDevice2 = device;
                    break;
                }
            }
        }
        if (displaylinkState.displaylinkDevice2 == null) {
            return true;
        }
        if (usbManager.hasPermission(displaylinkState.displaylinkDevice2)) {
            State.log("已经拥有第二个 USB 设备权限: " + displaylinkState.displaylinkDevice2.getDeviceName());
            return true;
        } else if (device2UsbRequested) {
            State.log("因为未授予第二个 USB 设备权限: " + displaylinkState.displaylinkDevice2.getDeviceName() + "，跳过任务");
            return false;
        }
        device2UsbRequested = true;
        PendingIntent pendingIntent = PendingIntent.getBroadcast(context, 0, new Intent(SunshineService.ACTION_USB_PERMISSION), PendingIntent.FLAG_IMMUTABLE);
        usbManager.requestPermission(displaylinkState.displaylinkDevice2, pendingIntent);
        throw new YieldException("等待用户第二个 USB 授权");
    }
    private boolean initializeNativeDriver(Context context, DisplaylinkState displaylinkState) throws YieldException {
        if (displaylinkState.displaylinkDevice2 != null && displaylinkState.monitorInfo == null) {
            if (displaylinkState.nativeDriver != null) {
                displaylinkState.nativeDriver.destroy();
                displaylinkState.nativeDriver = null;
            }
        }
        if (displaylinkState.nativeDriver == null) {
            displaylinkState.nativeDriver = new NativeDriver();
            displaylinkState.nativeDriverListener = new NativeDriverListener(deviceName);
            displaylinkState.nativeDriver.destroy();
            int resultCode = displaylinkState.nativeDriver.create(displaylinkState.nativeDriverListener, context.getFilesDir().toString(), true);
            if (resultCode != 0) {
                throw new RuntimeException("创建NativeDriver失败: " + resultCode);
            } else {
                State.log("创建NativeDriver成功");
            }
            displaylinkState.nativeDriver.usbDeviceDetached(deviceName);
            if (displaylinkState.displaylinkDevice2 != null) {
                displaylinkState.nativeDriver.usbDeviceDetached(displaylinkState.displaylinkDevice2.getDeviceName());
            }
            resultCode = displaylinkState.nativeDriver.usbDeviceAttached(deviceName, displaylinkState.usbConnection.getFileDescriptor(), displaylinkState.usbConnection.getRawDescriptors(), displaylinkState.usbConnection.getRawDescriptors().length);
            if (resultCode != 0) {
                throw new RuntimeException("附加USB设备失败: " + resultCode);
            } else {
                State.log("附加USB设备成功");
            }
            if (displaylinkState.displaylinkDevice2 != null) {
                resultCode = displaylinkState.nativeDriver.usbDeviceAttached(displaylinkState.displaylinkDevice2.getDeviceName(), displaylinkState.displaylinkConnection2.getFileDescriptor(), displaylinkState.displaylinkConnection2.getRawDescriptors(), displaylinkState.displaylinkConnection2.getRawDescriptors().length);
                if (resultCode != 0) {
                    throw new RuntimeException("附加第二个USB设备失败: " + resultCode);
                } else {
                    State.log("附加第二个USB设备成功");
                }
            }
        } else {
            State.log("NativeDriver 已经存在，跳过重复创建");
        }

        if (displaylinkState.monitorInfo == null) {
            State.log("未找到显示器信息, 请连接显示器之后重试");
            return false;
        }
        return true;
    }

    private boolean requestMediaProjectionPermission(Context context, DisplaylinkState displaylinkState) throws YieldException {
        if (State.displaylinkState.getVirtualDisplay() != null) {
            State.log("已存在 virtual display 跳过询问投屏权限");
            return true;
        }
        if (State.getMediaProjection() != null) {
            State.log("MediaProjection 已经存在，跳过重复请求");
            return true;
        }
        if (mediaProjectionRequested) {
            State.log("因为未授予投屏权限，跳过任务");
            return false;
        }
        displaylinkState.stopVirtualDisplay();
        mediaProjectionRequested = true;
        MirrorMainActivity mirrorMainActivity = State.getCurrentActivity();
        if (mirrorMainActivity == null) {
            return false;
        }
        mirrorMainActivity.startMediaProjectionService();
        throw new YieldException("等待用户投屏授权");
    }
}