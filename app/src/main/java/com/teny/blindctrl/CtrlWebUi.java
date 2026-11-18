package com.teny.blindctrl;

import android.content.Context;
import android.net.wifi.WifiManager;

import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.util.Enumeration;

/** 计算给被控端地址展示用的局域网 IP（主控端按这个 IP 连过来）。 */
public final class CtrlWebUi {

    private CtrlWebUi() {}

    public static String hostIp(Context c) {
        // 优先取 WiFi 的 IP
        try {
            WifiManager wm = (WifiManager) c.getApplicationContext()
                    .getSystemService(Context.WIFI_SERVICE);
            if (wm != null) {
                int ip = wm.getConnectionInfo().getIpAddress();
                if (ip != 0) {
                    return String.format("%d.%d.%d.%d",
                            ip & 0xff, (ip >> 8) & 0xff,
                            (ip >> 16) & 0xff, (ip >> 24) & 0xff);
                }
            }
        } catch (Exception ignored) {}
        // 退而求其次遍历网卡找私有 IPv4
        try {
            for (Enumeration<NetworkInterface> nis =
                 NetworkInterface.getNetworkInterfaces(); nis.hasMoreElements(); ) {
                NetworkInterface ni = nis.nextElement();
                for (Enumeration<InetAddress> as = ni.getInetAddresses();
                     as.hasMoreElements(); ) {
                    InetAddress a = as.nextElement();
                    if (a instanceof Inet4Address && !a.isLoopbackAddress()
                            && a.isSiteLocalAddress()) {
                        return a.getHostAddress();
                    }
                }
            }
        } catch (Exception ignored) {}
        return "127.0.0.1";
    }
}
