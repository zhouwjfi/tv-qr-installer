package com.zhouwj.tvinstaller;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.NetworkInterface;
import java.net.Socket;
import java.util.Collections;
import java.util.Enumeration;

final class Net {

    private Net() {
    }

    /**
     * 获取本机局域网 IPv4。
     * 优先通过一次短连接取默认路由出口地址(电视一般都有外网),
     * 失败再遍历网卡找 wlan/eth 上的 IPv4。
     */
    static String lanIp() {
        try (Socket s = new Socket()) {
            s.connect(new InetSocketAddress("223.5.5.5", 53), 1500);
            String ip = s.getLocalAddress().getHostAddress();
            if (ip != null && !ip.startsWith("127.")) {
                return ip;
            }
        } catch (Exception ignored) {
        }
        try {
            Enumeration<NetworkInterface> ns = NetworkInterface.getNetworkInterfaces();
            while (ns != null && ns.hasMoreElements()) {
                NetworkInterface n = ns.nextElement();
                if (!n.isUp() || n.isLoopback() || n.isVirtual()) {
                    continue;
                }
                String name = n.getName() == null ? "" : n.getName().toLowerCase();
                for (InetAddress a : Collections.list(n.getInetAddresses())) {
                    if (a.isLoopbackAddress() || a.isLinkLocalAddress()) {
                        continue;
                    }
                    String ip = a.getHostAddress();
                    if (ip == null || ip.contains(":")) {
                        continue;
                    }
                    // 优先无线/有线网卡,跳过虚拟网段
                    if ((name.startsWith("wlan") || name.startsWith("eth") || name.startsWith("ra"))
                            && !ip.startsWith("172.")) {
                        return ip;
                    }
                }
            }
        } catch (Exception ignored) {
        }
        return null;
    }
}
