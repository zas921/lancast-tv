package com.lancast.tv;

import java.net.*;
import java.util.*;

public final class Net {
    private Net() {}
    public static Map<NetworkInterface, Inet4Address> interfaces() {
        Map<NetworkInterface, Inet4Address> found = new LinkedHashMap<>();
        try {
            Enumeration<NetworkInterface> list = NetworkInterface.getNetworkInterfaces();
            while (list != null && list.hasMoreElements()) {
                NetworkInterface nic = list.nextElement();
                if (!nic.isUp() || nic.isLoopback()) continue;
                Enumeration<InetAddress> addresses = nic.getInetAddresses();
                while (addresses.hasMoreElements()) {
                    InetAddress ip = addresses.nextElement();
                    if (ip instanceof Inet4Address && !ip.isLoopbackAddress() && ip.isSiteLocalAddress()) {
                        found.put(nic, (Inet4Address) ip); break;
                    }
                }
            }
        } catch (Exception ignored) {}
        return found;
    }
    public static String ip() {
        Map<NetworkInterface, Inet4Address> list = interfaces();
        for (Map.Entry<NetworkInterface, Inet4Address> e : list.entrySet())
            if (e.getKey().getName().startsWith("wlan") || e.getKey().getName().startsWith("eth")) return e.getValue().getHostAddress();
        return list.isEmpty() ? "" : list.values().iterator().next().getHostAddress();
    }
    public static boolean local(InetAddress address) {
        return address.isLoopbackAddress() || address.isSiteLocalAddress() || address.isLinkLocalAddress();
    }
    public static String xml(String s) {
        return s == null ? "" : s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;").replace("'", "&apos;");
    }
}
