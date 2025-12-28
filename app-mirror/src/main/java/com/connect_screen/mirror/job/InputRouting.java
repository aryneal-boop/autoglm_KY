package com.connect_screen.mirror.job;


import android.content.Context;
import android.hardware.input.IInputManager;
import android.hardware.input.InputManager;
import android.hardware.usb.UsbDevice;
import android.os.RemoteException;
import android.view.Display;
import android.view.DisplayAddress;
import android.view.DisplayInfo;
import android.view.IWindowManager;
import android.view.InputDevice;
import android.widget.Toast;

import com.connect_screen.mirror.Pref;
import com.connect_screen.mirror.State;
import com.connect_screen.mirror.shizuku.ServiceUtils;
import com.connect_screen.mirror.shizuku.ShizukuUtils;

import java.util.HashMap;
import java.util.Map;

public class InputRouting {
    public static Map<String, String> getInputDeviceDescriptorToPortMap() {
        if (State.userService == null) {
            State.log("user service 未启动，无法获取输入设备 descriptor -> port 的映射");
            return new HashMap<>();
        }
        Map<String, String> map = new HashMap<>();
        try {
            String inputDump = State.userService.executeCommand("dumpsys input");
            String[] lines = inputDump.split("\n");
            String lastDescriptor = "";
            for (String line : lines) {
                line = line.trim();
                if (line.startsWith("Descriptor:")) {
                    lastDescriptor = line.substring("Descriptor:".length()).trim();
                }
                if (line.startsWith("Location:")) {
                    String inputPort = line.substring("Location:".length()).trim();
                    map.put(lastDescriptor, inputPort);
                }
            }
        } catch (RemoteException ex) {
            throw new RuntimeException(ex);
        }
        return map;
    }

    public static void bindInputToDisplay(DisplayInfo displayInfo, InputDevice inputDevice, IInputManager inputManager, Map<String, String> inputDeviceDescriptorToPortMap) {
        if (!inputDevice.isExternal()) {
            return;
        }
        State.log("尝试绑定设备 " + inputDevice.getId());
        try {
            inputManager.removeUniqueIdAssociationByDescriptor(inputDevice.getDescriptor());
            inputManager.addUniqueIdAssociationByDescriptor(inputDevice.getDescriptor(), String.valueOf(displayInfo.uniqueId));
            State.log("成功更新输入设备路由: " + inputDevice.getName() + ", " + inputDevice.getDescriptor());
        } catch(Throwable e) {
            String inputPort = inputDeviceDescriptorToPortMap.get(inputDevice.getDescriptor());
            if (inputPort == null) {
                State.log("未能更新输入设备路由: " + inputDevice + ", " + e.getMessage());
            } else {
                try {
                    inputManager.removeUniqueIdAssociation(inputPort);
                    inputManager.addUniqueIdAssociation(inputPort, String.valueOf(displayInfo.uniqueId));
                    State.log("成功更新输入设备路由: " + inputDevice.getName() + ", " + inputPort + " => " + displayInfo.uniqueId);
                } catch(Throwable e2) {
                    try {
                        inputManager.removePortAssociation(inputPort);
                        int displayPort = ((DisplayAddress.Physical) displayInfo.address).getPort();
                        inputManager.addPortAssociation(inputPort, displayPort);
                        State.log("成功更新输入设备路由: " + inputDevice.getName() + ", " + inputPort + " => " + displayPort);
                    } catch(Throwable e3) {
                        State.log("改用 input port 仍然未能更新输入设备路由: " + inputDevice.getName() + ", " + e3.getMessage());
                        Context context = State.getContext();
                        if (ShizukuUtils.hasPermission() && context != null) {
                            Toast.makeText(context, "由于操作系统版本的限制，需要点击'模拟熄屏'按钮才可以使用触摸屏", Toast.LENGTH_SHORT).show();
                        }
                    }
                }
            }
        }
    }

    public static InputDevice findInputDevice(InputManager inputManager, UsbDevice usbDevice) {
        for(int inputDeviceId : inputManager.getInputDeviceIds()) {
            InputDevice inputDevice = inputManager.getInputDevice(inputDeviceId);
            if (inputDevice.isExternal() && inputDevice.getVendorId() == usbDevice.getVendorId() && inputDevice.getProductId() == usbDevice.getProductId()) {
                return inputDevice;
            }
        }
        return null;
    }

    public static void bindAllExternalInputToDisplay(int displayId) {
        if (!shouldBind()) {
            State.log("跳过绑定外设到显示器: " + displayId);
            return;
        }
        DisplayInfo displayInfo = ServiceUtils.getDisplayManager().getDisplayInfo(displayId);
        IInputManager inputManager = ServiceUtils.getInputManager();
        Map<String, String> inputDeviceDescriptorToPortMap = InputRouting.getInputDeviceDescriptorToPortMap();
        for (int deviceId : inputManager.getInputDeviceIds()) {
            InputDevice inputDevice = inputManager.getInputDevice(deviceId);
            InputRouting.bindInputToDisplay(displayInfo, inputDevice, inputManager, inputDeviceDescriptorToPortMap);
        }
    }

    private static boolean shouldBind() {
        try {
            return Pref.getAutoBindInput();
        } catch(Exception e) {
            // ignore
        }
        return true;
    }

    public static void moveImeToExternal(int displayId) {
        if(!shouldMoveIme()) {
            State.log("跳过移动输入法");
            return;
        }
        try {
            IWindowManager windowManager = ServiceUtils.getWindowManager();
            windowManager.setDisplayImePolicy(Display.DEFAULT_DISPLAY, 1);
            try {
                windowManager.setDisplayImePolicy(displayId, 0);
            } catch (Throwable e) {
                windowManager.setDisplayImePolicy(Display.DEFAULT_DISPLAY, 0);
                State.log("在此屏幕显示输入法，设置失败" + e);
            }
        } catch(Throwable e) {
            State.log("移动输入法失败: " + e);
        }
    }

    private static boolean shouldMoveIme() {
        try {
            return Pref.getAutoMoveIme();
        } catch(Exception e) {
            // ignore
        }
        return true;
    }

    public static void moveImeToDefault() {
        if (!ShizukuUtils.hasPermission()) {
            return;
        }
        IWindowManager windowManager = ServiceUtils.getWindowManager();
        windowManager.setDisplayImePolicy(Display.DEFAULT_DISPLAY, 0);
    }
}
