package com.connect_screen.mirror.job;

import com.connect_screen.mirror.Pref;
import com.connect_screen.mirror.State;

import java.net.InetAddress;
import java.net.NetworkInterface;
import java.net.SocketException;
import java.util.Enumeration;
import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.Socket;
import android.util.Log;

public class ConnectToClient {
    public static void connect(int pin) {
        SunshineServer.pinCandidate = String.valueOf(pin);
        String clientIpAndPort = Pref.getSelectedClient();
        // 解析客户端 IP 和端口
        String[] parts = clientIpAndPort.split(":");
        String clientIp = parts[0];
        int clientPort = -1;
        try {
            clientPort = Integer.parseInt(parts[1]);
        } catch(Exception e) {
            State.log("客户端地址非法: " + clientIpAndPort + "  " + e);
            return;
        }
        // 获取服务器 IP（与客户端同网段）
        String serverIp = findServerIpInSameSubnet(clientIp);
        if (serverIp == null) {
            State.log("找不到和客户端在同网段的ip");
            return;
        }
        if (State.serverUuid == null) {
            State.log("ServerUuid 为空");
            return;
        }
        // 生成4位随机数作为PIN码
        String request = "{\"action\": \"connect\", \"ip\": \"" + serverIp + "\", \"pin\": \"" + pin + "\", \"uuid\": \"" + State.serverUuid + "\"}\n";
        State.log("发送自启动请求到: " + clientIp + ":" + clientPort);
        
        // 创建新线程执行TCP连接
        final String finalClientIp = clientIp;
        final int finalClientPort = clientPort;
        final String finalRequest = request;
        
        new Thread(() -> {
            connectToClientInBackground(finalClientIp, finalClientPort, finalRequest);
        }).start();
    }
    
    /**
     * 在后台线程中执行TCP连接操作
     * @param clientIp 客户端IP地址
     * @param clientPort 客户端端口
     * @param request 请求内容
     */
    private static void connectToClientInBackground(String clientIp, int clientPort, String request) {
        try (Socket socket = new Socket(clientIp, clientPort)) {
            // 设置连接超时
            socket.setSoTimeout(15000);
            
            // 获取输出流并发送请求
            OutputStream outputStream = socket.getOutputStream();
            outputStream.write(request.getBytes());
            outputStream.flush();
            
            // 获取响应（可选）
            InputStream inputStream = socket.getInputStream();
            BufferedReader reader = new BufferedReader(new InputStreamReader(inputStream));
            String response = reader.readLine();
            
            if (response != null) {
                Log.i("ConnectToClient", "收到客户端响应: " + response);
            } else {
                Log.i("ConnectToClient", "未收到客户端响应");
            }
        } catch (Exception e) {
            Log.e("ConnectToClient", "连接客户端失败: " + e.getMessage());
        }
    }
    
    /**
     * 查找与客户端 IP 同网段的本地 IP 地址
     * @param clientIp 客户端 IP 地址
     * @return 同网段的本地 IP 地址，如果未找到则返回 null
     */
    private static String findServerIpInSameSubnet(String clientIp) {
        try {
            // 获取客户端 IP 的网段前缀
            String clientSubnet = getSubnetPrefix(clientIp);
            
            // 遍历所有网络接口
            Enumeration<NetworkInterface> networkInterfaces = NetworkInterface.getNetworkInterfaces();
            while (networkInterfaces.hasMoreElements()) {
                NetworkInterface networkInterface = networkInterfaces.nextElement();
                
                // 跳过禁用的接口
                if (!networkInterface.isUp() || networkInterface.isLoopback() || networkInterface.isVirtual()) {
                    continue;
                }
                
                // 检查每个接口的 IP 地址
                Enumeration<InetAddress> addresses = networkInterface.getInetAddresses();
                while (addresses.hasMoreElements()) {
                    InetAddress address = addresses.nextElement();
                    String localIp = address.getHostAddress();
                    
                    // 跳过 IPv6 地址
                    if (localIp.contains(":")) {
                        continue;
                    }
                    
                    // 检查是否与客户端在同一网段
                    if (getSubnetPrefix(localIp).equals(clientSubnet)) {
                        return localIp;
                    }
                }
            }
        } catch (SocketException e) {
            State.log("查找匹配的ip失败: " + e);
        }
        
        return null;
    }
    
    /**
     * 获取 IP 地址的网段前缀（假设是 C 类网络，即前 24 位）
     * @param ip IP 地址
     * @return 网段前缀
     */
    private static String getSubnetPrefix(String ip) {
        // 简单实现：假设是 C 类网络，取前三个数字
        String[] octets = ip.split("\\.");
        if (octets.length >= 3) {
            return octets[0] + "." + octets[1] + "." + octets[2];
        }
        return "";
    }
}
