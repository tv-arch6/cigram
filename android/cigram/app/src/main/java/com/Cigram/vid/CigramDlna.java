package com.Cigram.vid;

import android.content.Context;
import android.net.ConnectivityManager;
import android.net.LinkProperties;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.wifi.WifiManager;
import android.os.Build;
import android.util.Log;

import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;
import org.xml.sax.InputSource;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.StringReader;
import java.net.DatagramPacket;
import java.net.HttpURLConnection;
import java.net.InetAddress;
import java.net.MulticastSocket;
import java.net.NetworkInterface;
import java.net.SocketTimeoutException;
import java.net.URL;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

import javax.xml.parsers.DocumentBuilderFactory;

/**
 * UPnP / DLNA media renderer support (v2.6): finds TVs on the Wi-Fi network through SSDP and plays a
 * video link on them with AVTransport (SetAVTransportURI / Play / Seek / Stop / position polling).
 * This is what makes Samsung, LG, Sony, Philips, Hisense ... TVs work even without Chromecast.
 *
 * v5.24 (modern phones: Samsung, Xiaomi / Redmi / POCO, Huawei, Honor, Oppo, Realme, Infinix,
 * Tecno, vivo ...): every socket and HTTP request is bound to the Wi-Fi network itself. Those phones
 * often keep mobile data as the default network ("Wi-Fi assistant", "Dual-channel / Link Turbo",
 * "Smart network switch", VPN, Wi-Fi without internet), so the search used to leave on mobile data
 * and no TV was ever found. It also listens to the TVs' own "NOTIFY" announcements on port 1900 and
 * searches with more targets (ssdp:all, rootdevice), since some TVs ignore one kind of search.
 *
 * v5.25 (the icon still did not show on some Infinix / Xiaomi / Realme phones): a second, independent
 * search ("sweep") runs next to the first one. For every Wi-Fi / Ethernet network of the phone (many of
 * these phones have two Wi-Fi links) it sends the multicast search AND a direct (unicast) search to
 * every address of the local subnet, so a TV is found even when the router / the phone's Wi-Fi chip
 * drops multicast packets. The TV's description page is fetched over every candidate network (not only
 * the first Wi-Fi one) and parsed leniently, and a failed fetch is retried instead of being
 * blacklisted as "not a TV" for five minutes.
 *
 * v5.26: VPN networks are no longer mistaken for the Wi-Fi (the search used to go into the tunnel),
 * and the search socket is not re-bound every few seconds when nothing changed.
 */
public final class CigramDlna {

    private static final String TAG = "CigramDlna";
    private static final String ST = "urn:schemas-upnp-org:device:MediaRenderer:1";
    private static final long ALIVE_MS = 90000L;
    /** A location that is not a media renderer is not fetched again for this long. */
    private static final long NOT_RENDERER_MS = 5L * 60L * 1000L;
    private static final String[] TARGETS = {
            ST,
            "urn:schemas-upnp-org:service:AVTransport:1",
            "urn:schemas-upnp-org:device:MediaRenderer:2",
            "upnp:rootdevice",
            "ssdp:all",
    };

    private CigramDlna() { }

    public static final class Device {
        public String udn = "";
        public String name = "";
        public String location = "";
        public String controlUrl = "";
        public String serviceType = "urn:schemas-upnp-org:service:AVTransport:1";
        long lastSeen;
        /** Network the TV answered on (null = default routing). */
        Network net;
        @Override public String toString() { return name; }
    }

    public static final class Info {
        public String state = "";
        public long positionMs;
        public long durationMs;
    }

    // ------------------------------------------------------------------ discovery

    private static final Map<String, Device> DEVICES = new LinkedHashMap<String, Device>();
    private static final Object LOCK = new Object();
    private static final java.util.HashSet<String> FETCHING = new java.util.HashSet<String>();
    private static volatile boolean running;
    private static int users;
    private static Thread thread;
    private static Thread notifyThread;
    private static Thread sweepThread;
    private static final HashMap<String, Long> FAILED = new HashMap<String, Long>();
    private static WifiManager.MulticastLock multicast;
    private static final HashMap<String, Long> NOT_RENDERER = new HashMap<String, Long>();
    private static Context appCtx;
    private static volatile Network wifiNet;
    private static volatile NetworkInterface wifiIf;
    private static Runnable changed;

    public static void setOnChanged(Runnable r) { changed = r; }

    public static ArrayList<Device> devices() {
        ArrayList<Device> out = new ArrayList<Device>();
        long now = System.currentTimeMillis();
        synchronized (LOCK) {
            for (Device d : DEVICES.values()) {
                if (now - d.lastSeen <= ALIVE_MS && d.controlUrl.length() > 0) out.add(d);
            }
        }
        return out;
    }

    public static synchronized void start(Context context) {
        users++;
        if (running) return;
        running = true;
        final Context app = context.getApplicationContext();
        appCtx = app;
        findWifi(app);
        try {
            WifiManager wm = (WifiManager) app.getSystemService(Context.WIFI_SERVICE);
            if (wm != null) {
                multicast = wm.createMulticastLock("cigram-dlna");
                multicast.setReferenceCounted(false);
                multicast.acquire();
            }
        } catch (Throwable error) {
            Log.w(TAG, "multicast lock failed", error);
        }
        thread = new Thread(new Runnable() {
            @Override public void run() { discoveryLoop(); }
        }, "cigram-ssdp");
        thread.setDaemon(true);
        thread.start();
        notifyThread = new Thread(new Runnable() {
            @Override public void run() { notifyLoop(); }
        }, "cigram-ssdp-notify");
        notifyThread.setDaemon(true);
        notifyThread.start();
        sweepThread = new Thread(new Runnable() {
            @Override public void run() { sweepLoop(); }
        }, "cigram-ssdp-sweep");
        sweepThread.setDaemon(true);
        sweepThread.start();
    }

    /** The Wi-Fi (or Ethernet, for TV boxes) network, even when mobile data is the default one. */
    @SuppressWarnings("deprecation")
    private static void findWifi(Context app) {
        Network net = null;
        NetworkInterface nif = null;
        try {
            ConnectivityManager cm = (ConnectivityManager) app.getSystemService(Context.CONNECTIVITY_SERVICE);
            if (cm != null && Build.VERSION.SDK_INT >= 21) {
                Network[] all = cm.getAllNetworks();
                for (int pass = 0; pass < 2 && net == null; pass++) {
                    int transport = pass == 0 ? NetworkCapabilities.TRANSPORT_WIFI : NetworkCapabilities.TRANSPORT_ETHERNET;
                    for (int i = 0; all != null && i < all.length; i++) {
                        NetworkCapabilities nc = cm.getNetworkCapabilities(all[i]);
                        if (nc == null || !nc.hasTransport(transport) || isVpn(nc)) continue;
                        net = all[i];
                        LinkProperties lp = cm.getLinkProperties(all[i]);
                        if (lp != null && lp.getInterfaceName() != null) {
                            nif = NetworkInterface.getByName(lp.getInterfaceName());
                        }
                        break;
                    }
                }
            }
        } catch (Throwable error) {
            Log.w(TAG, "findWifi failed", error);
        }
        if (nif == null) {
            try {
                Enumeration<NetworkInterface> en = NetworkInterface.getNetworkInterfaces();
                while (en != null && en.hasMoreElements()) {
                    NetworkInterface n = en.nextElement();
                    String name = n.getName() == null ? "" : n.getName().toLowerCase(Locale.US);
                    // v6.3: also the phone's own hotspot (ap0 / swlan0 / softap0 ...): a TV joined to
                    // the hotspot is on that interface, and Android lists no Wi-Fi network for it
                    boolean lan = name.startsWith("wlan") || name.startsWith("eth") || name.startsWith("ap")
                            || name.startsWith("swlan") || name.startsWith("softap");
                    if (lan && n.isUp() && !n.isLoopback()
                            && n.supportsMulticast() && hasIpv4(n)) {
                        nif = n;
                        break;
                    }
                }
            } catch (Throwable ignored) { }
        }
        wifiNet = net;
        wifiIf = nif;
    }

    /**
     * v6.4: true when the phone can reach TVs at all: Wi-Fi turned on (also covers Miracast, which
     * needs no shared network), a Wi-Fi / Ethernet network, or the phone's own hotspot.
     */
    public static boolean localNetworkPossible(Context c) {
        if (c == null) return false;
        Context app = c.getApplicationContext();
        try {
            WifiManager wm = (WifiManager) app.getSystemService(Context.WIFI_SERVICE);
            if (wm != null && wm.isWifiEnabled()) return true;
        } catch (Throwable ignored) { }
        try {
            ConnectivityManager cm = (ConnectivityManager) app.getSystemService(Context.CONNECTIVITY_SERVICE);
            Network[] all = cm == null ? null : cm.getAllNetworks();
            for (int i = 0; all != null && i < all.length; i++) {
                NetworkCapabilities nc = cm.getNetworkCapabilities(all[i]);
                if (nc != null && (nc.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)
                        || nc.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET))) return true;
            }
        } catch (Throwable ignored) { }
        try {
            Enumeration<NetworkInterface> en = NetworkInterface.getNetworkInterfaces();
            while (en != null && en.hasMoreElements()) {
                NetworkInterface n = en.nextElement();
                String name = n.getName() == null ? "" : n.getName().toLowerCase(Locale.US);
                if ((name.startsWith("ap") || name.startsWith("swlan") || name.startsWith("softap"))
                        && n.isUp() && hasIpv4(n)) return true;   // hotspot on
            }
        } catch (Throwable ignored) { }
        return false;
    }

    private static volatile boolean forceSend;

    /** v6.4: «إعادة البحث»: forget earlier failures and search the network again right now. */
    public static void rescan(Context context) {
        synchronized (LOCK) {
            FAILED.clear();
            NOT_RENDERER.clear();
        }
        forceSend = true;
        if (!running && context != null) {
            start(context);
            stop(); // keep the count balanced: the search runs while a screen holds it
        }
    }

    private static boolean hasIpv4(NetworkInterface n) {
        try {
            Enumeration<InetAddress> a = n.getInetAddresses();
            while (a != null && a.hasMoreElements()) {
                if (a.nextElement() instanceof java.net.Inet4Address) return true;
            }
        } catch (Throwable ignored) { }
        return false;
    }

    /**
     * v5.26: a VPN network also reports the Wi-Fi transport under it (Android 9+), so it was taken as
     * "the Wi-Fi": the search then went into the VPN tunnel (tun0) and no TV ever answered.
     */
    private static boolean isVpn(NetworkCapabilities nc) {
        try {
            return nc.hasTransport(NetworkCapabilities.TRANSPORT_VPN)
                    || !nc.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN);
        } catch (Throwable ignored) {
            return false;
        }
    }

    /** Puts a socket on the Wi-Fi network (no-op when it is unknown). */
    private static void bindToWifi(MulticastSocket socket) {
        Network net = wifiNet;
        if (net != null && Build.VERSION.SDK_INT >= 23) {
            try { net.bindSocket(socket); } catch (Throwable error) { Log.w(TAG, "bindSocket failed", error); }
        }
        NetworkInterface nif = wifiIf;
        if (nif != null) {
            try { socket.setNetworkInterface(nif); } catch (Throwable error) { Log.w(TAG, "setNetworkInterface failed", error); }
        }
    }

    /** HTTP request over the Wi-Fi network: the TV is never reachable over mobile data. */
    private static HttpURLConnection open(String url, Network net) throws Exception {
        URL u = new URL(url);
        if (net != null && Build.VERSION.SDK_INT >= 21) {
            try { return (HttpURLConnection) net.openConnection(u); } catch (Throwable ignored) { }
        }
        return (HttpURLConnection) u.openConnection();
    }

    public static synchronized void stop() {
        if (users > 0) users--;
        if (users > 0) return;
        running = false;
        try { if (thread != null) thread.interrupt(); } catch (Throwable ignored) { }
        thread = null;
        try { if (notifyThread != null) notifyThread.interrupt(); } catch (Throwable ignored) { }
        notifyThread = null;
        try { if (sweepThread != null) sweepThread.interrupt(); } catch (Throwable ignored) { }
        sweepThread = null;
        try { if (multicast != null && multicast.isHeld()) multicast.release(); } catch (Throwable ignored) { }
        multicast = null;
    }

    private static void discoveryLoop() {
        MulticastSocket socket = null;
        try {
            socket = new MulticastSocket(null);
            socket.setReuseAddress(true);
            bindToWifi(socket);
            socket.bind(new java.net.InetSocketAddress(0));
            socket.setSoTimeout(1000);
            socket.setTimeToLive(4);
            InetAddress group = InetAddress.getByName("239.255.255.250");
            byte[][] reqs = new byte[TARGETS.length][];
            for (int i = 0; i < TARGETS.length; i++) {
                reqs[i] = ("M-SEARCH * HTTP/1.1\r\n"
                        + "HOST: 239.255.255.250:1900\r\n"
                        + "MAN: \"ssdp:discover\"\r\n"
                        + "MX: 2\r\n"
                        + "ST: " + TARGETS[i] + "\r\n"
                        + "USER-AGENT: Android/" + Build.VERSION.RELEASE + " UPnP/1.1 Cigram/5.26\r\n\r\n").getBytes("UTF-8");
            }
            long startedAt = System.currentTimeMillis();
            long nextSend = 0L;
            byte[] buf = new byte[8192];
            while (running) {
                long now = System.currentTimeMillis();
                if (now >= nextSend || forceSend) {
                    forceSend = false;
                    // the Wi-Fi may have changed (reconnect, other access point): follow it
                    if (appCtx != null && now - startedAt > 1000L) {
                        Network before = wifiNet;
                        findWifi(appCtx);
                        // equals(): every lookup returns new Network objects for the same network
                        Network after = wifiNet;
                        if (after != null && !after.equals(before)) bindToWifi(socket);
                    }
                    for (int round = 0; round < 2; round++) {
                        for (int i = 0; i < reqs.length; i++) {
                            try {
                                socket.send(new DatagramPacket(reqs[i], reqs[i].length, group, 1900));
                            } catch (Throwable error) {
                                Log.w(TAG, "M-SEARCH send failed", error);
                            }
                        }
                    }
                    // quick searches in the first 30 s, then every 10 s
                    nextSend = now + (now - startedAt < 30000L ? 4000L : 10000L);
                }
                try {
                    DatagramPacket p = new DatagramPacket(buf, buf.length);
                    socket.receive(p);
                    handleReply(new String(p.getData(), 0, p.getLength(), "UTF-8"));
                } catch (SocketTimeoutException ignored) {
                }
            }
        } catch (Throwable error) {
            if (running) Log.w(TAG, "discovery stopped", error);
        } finally {
            try { if (socket != null) socket.close(); } catch (Throwable ignored) { }
        }
    }

    /** TVs announce themselves ("NOTIFY ssdp:alive") on 239.255.255.250:1900 every few minutes. */
    private static void notifyLoop() {
        MulticastSocket socket = null;
        try {
            InetAddress group = InetAddress.getByName("239.255.255.250");
            socket = new MulticastSocket(null);
            socket.setReuseAddress(true);
            bindToWifi(socket);
            socket.bind(new java.net.InetSocketAddress(1900));
            socket.setSoTimeout(1000);
            NetworkInterface nif = wifiIf;
            if (nif != null) socket.joinGroup(new java.net.InetSocketAddress(group, 1900), nif);
            else socket.joinGroup(group);
            byte[] buf = new byte[8192];
            while (running) {
                try {
                    DatagramPacket p = new DatagramPacket(buf, buf.length);
                    socket.receive(p);
                    String text = new String(p.getData(), 0, p.getLength(), "UTF-8");
                    if (text.startsWith("NOTIFY")) handleReply(text);
                } catch (SocketTimeoutException ignored) {
                }
            }
        } catch (Throwable error) {
            // port 1900 busy on some phones: the active search above still works
            if (running) Log.w(TAG, "notify listener stopped", error);
        } finally {
            try { if (socket != null) socket.close(); } catch (Throwable ignored) { }
        }
    }

    // ------------------------------------------------------------------ v5.25: sweep
    // A second search that does not depend on multicast: for every Wi-Fi / Ethernet link of the phone
    // it sends the multicast search plus a direct (unicast) search to every address of the subnet.

    private static final class Lan {
        Network net;                       // null = default routing
        NetworkInterface nif;
        java.net.Inet4Address addr;
        int prefix;
    }

    private static boolean usable(InetAddress a) {
        return a instanceof java.net.Inet4Address && !a.isLoopbackAddress()
                && !a.isLinkLocalAddress() && !a.isAnyLocalAddress();
    }

    /** All IPv4 LAN links of the phone (Wi-Fi, a second Wi-Fi link, Ethernet, own hotspot). */
    @SuppressWarnings("deprecation")
    private static ArrayList<Lan> lanPaths() {
        ArrayList<Lan> out = new ArrayList<Lan>();
        HashMap<String, Network> byIf = new HashMap<String, Network>();
        Context app = appCtx;
        try {
            ConnectivityManager cm = app == null ? null
                    : (ConnectivityManager) app.getSystemService(Context.CONNECTIVITY_SERVICE);
            if (cm != null && Build.VERSION.SDK_INT >= 21) {
                Network[] all = cm.getAllNetworks();
                for (int i = 0; all != null && i < all.length; i++) {
                    NetworkCapabilities nc = cm.getNetworkCapabilities(all[i]);
                    if (nc == null || isVpn(nc)) continue;
                    if (!nc.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)
                            && !nc.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)) continue;
                    LinkProperties lp = cm.getLinkProperties(all[i]);
                    if (lp == null || lp.getInterfaceName() == null) continue;
                    byIf.put(lp.getInterfaceName(), all[i]);
                    for (android.net.LinkAddress la : lp.getLinkAddresses()) {
                        if (!usable(la.getAddress())) continue;
                        Lan l = new Lan();
                        l.net = all[i];
                        try { l.nif = NetworkInterface.getByName(lp.getInterfaceName()); } catch (Throwable ignored) { }
                        l.addr = (java.net.Inet4Address) la.getAddress();
                        l.prefix = la.getPrefixLength();
                        out.add(l);
                    }
                }
            }
        } catch (Throwable error) {
            Log.w(TAG, "lanPaths (connectivity) failed", error);
        }
        try {
            Enumeration<NetworkInterface> en = NetworkInterface.getNetworkInterfaces();
            while (en != null && en.hasMoreElements()) {
                NetworkInterface n = en.nextElement();
                String name = n.getName() == null ? "" : n.getName().toLowerCase(Locale.US);
                boolean lanName = name.startsWith("wlan") || name.startsWith("eth") || name.startsWith("swlan")
                        || name.startsWith("ap") || name.startsWith("wifi");
                if (name.startsWith("tun") || name.startsWith("ppp") || name.startsWith("ipsec")) continue;
                if (!n.isUp() || n.isLoopback() || (!lanName && !byIf.containsKey(n.getName()))) continue;
                for (java.net.InterfaceAddress ia : n.getInterfaceAddresses()) {
                    if (!usable(ia.getAddress())) continue;
                    boolean dup = false;
                    for (Lan x : out) if (x.addr.equals(ia.getAddress())) dup = true;
                    if (dup) continue;
                    Lan l = new Lan();
                    l.net = byIf.get(n.getName());
                    l.nif = n;
                    l.addr = (java.net.Inet4Address) ia.getAddress();
                    l.prefix = ia.getNetworkPrefixLength();
                    out.add(l);
                }
            }
        } catch (Throwable ignored) { }
        return out;
    }

    /** Networks to try when fetching a TV's description / sending it commands. */
    private static ArrayList<Network> candidateNets() {
        ArrayList<Network> out = new ArrayList<Network>();
        Network w = wifiNet;
        if (w != null) out.add(w);
        try {
            for (Lan l : lanPaths()) if (l.net != null && !out.contains(l.net)) out.add(l.net);
        } catch (Throwable ignored) { }
        out.add(null);
        return out;
    }

    private static void sweepLoop() {
        try {
            Thread.sleep(1200L);
            int cycle = 0;
            while (running) {
                boolean unicast = cycle < 6 || cycle % 3 == 0;
                ArrayList<Lan> lans = lanPaths();
                if (lans.isEmpty()) sweepOnce(null, false);
                for (int i = 0; running && i < lans.size(); i++) sweepOnce(lans.get(i), unicast);
                cycle++;
                Thread.sleep(cycle < 6 ? 5000L : 20000L);
            }
        } catch (InterruptedException ignored) {
        } catch (Throwable error) {
            if (running) Log.w(TAG, "sweep stopped", error);
        }
    }

    private static byte[] search(String st, String host) throws java.io.UnsupportedEncodingException {
        return ("M-SEARCH * HTTP/1.1\r\n"
                + "HOST: " + host + "\r\n"
                + "MAN: \"ssdp:discover\"\r\n"
                + "MX: 2\r\n"
                + "ST: " + st + "\r\n"
                + "USER-AGENT: Android/" + Build.VERSION.RELEASE + " UPnP/1.1 Cigram/5.26\r\n\r\n").getBytes("UTF-8");
    }

    private static void send(MulticastSocket s, byte[] req, InetAddress to) {
        try {
            s.send(new DatagramPacket(req, req.length, to, 1900));
        } catch (Throwable ignored) {
            // one unreachable target must not stop the sweep
        }
    }

    private static int toInt(byte[] b) {
        return ((b[0] & 255) << 24) | ((b[1] & 255) << 16) | ((b[2] & 255) << 8) | (b[3] & 255);
    }

    private static byte[] fromInt(int v) {
        return new byte[]{(byte) (v >>> 24), (byte) (v >>> 16), (byte) (v >>> 8), (byte) v};
    }

    private static void sweepOnce(Lan lan, boolean unicast) throws InterruptedException {
        MulticastSocket s = null;
        try {
            s = new MulticastSocket(null);
            s.setReuseAddress(true);
            if (lan != null && lan.net != null && Build.VERSION.SDK_INT >= 21) {
                try { lan.net.bindSocket(s); } catch (Throwable error) { Log.w(TAG, "sweep bindSocket failed", error); }
            }
            if (lan != null) {
                try {
                    s.bind(new java.net.InetSocketAddress(lan.addr, 0));
                } catch (Throwable error) {
                    s.bind(new java.net.InetSocketAddress(0));
                }
                if (lan.nif != null) {
                    try { s.setNetworkInterface(lan.nif); } catch (Throwable ignored) { }
                }
            } else {
                s.bind(new java.net.InetSocketAddress(0));
            }
            try { s.setReceiveBufferSize(262144); } catch (Throwable ignored) { }
            s.setSoTimeout(300);
            s.setTimeToLive(4);
            InetAddress group = InetAddress.getByName("239.255.255.250");
            String[] sts = {ST, "urn:schemas-upnp-org:service:AVTransport:1"};
            for (int k = 0; k < sts.length; k++) send(s, search(sts[k], "239.255.255.250:1900"), group);
            if (lan != null && unicast && lan.prefix <= 30) {
                int ip = toInt(lan.addr.getAddress());
                int prefix = Math.max(24, lan.prefix);
                int mask = -1 << (32 - prefix);
                int netw = ip & mask;
                int count = 1 << (32 - prefix);
                int sent = 0;
                for (int i = 1; i < count - 1 && running; i++) {
                    int host = netw + i;
                    if (host == ip) continue;
                    InetAddress target = InetAddress.getByAddress(fromInt(host));
                    String hp = target.getHostAddress() + ":1900";
                    for (int k = 0; k < sts.length; k++) send(s, search(sts[k], hp), target);
                    if (++sent % 24 == 0) Thread.sleep(12L);
                }
            }
            long end = System.currentTimeMillis() + 3500L;
            byte[] buf = new byte[8192];
            while (running && System.currentTimeMillis() < end) {
                try {
                    DatagramPacket p = new DatagramPacket(buf, buf.length);
                    s.receive(p);
                    handleReply(new String(p.getData(), 0, p.getLength(), "UTF-8"));
                } catch (SocketTimeoutException ignored) {
                }
            }
        } catch (InterruptedException e) {
            throw e;
        } catch (Throwable error) {
            Log.w(TAG, "sweep failed", error);
        } finally {
            try { if (s != null) s.close(); } catch (Throwable ignored) { }
        }
    }

    private static void handleReply(String text) {
        String location = header(text, "LOCATION");
        if (location.length() == 0) return;
        String nts = header(text, "NTS");
        if (nts.toLowerCase(Locale.US).contains("byebye")) return;
        Device known = null;
        synchronized (LOCK) {
            for (Device d : DEVICES.values()) if (location.equals(d.location)) known = d;
            if (known != null) {
                known.lastSeen = System.currentTimeMillis();
                return;
            }
            if (FETCHING.contains(location)) return;
            Long failed = FAILED.get(location);
            if (failed != null && System.currentTimeMillis() - failed < 8000L) return;
            Long bad = NOT_RENDERER.get(location);
            if (bad != null && System.currentTimeMillis() - bad < NOT_RENDERER_MS) return;
            FETCHING.add(location);
        }
        final String loc = location;
        new Thread(new Runnable() {
            @Override public void run() {
                try {
                    Device d = describe(loc);
                    if (d != null) {
                        synchronized (LOCK) {
                            // same TV seen at a new address (DHCP renewal): replace the old entry
                            DEVICES.put(d.udn.length() > 0 ? d.udn : loc, d);
                        }
                        Runnable r = changed;
                        if (r != null) r.run();
                    } else {
                        synchronized (LOCK) { NOT_RENDERER.put(loc, System.currentTimeMillis()); }
                    }
                } catch (Throwable error) {
                    Log.w(TAG, "describe failed " + loc, error);
                    synchronized (LOCK) { FAILED.put(loc, System.currentTimeMillis()); }
                } finally {
                    synchronized (LOCK) { FETCHING.remove(loc); }
                }
            }
        }, "cigram-upnp-describe").start();
    }

    private static String header(String text, String name) {
        String[] lines = text.split("\r?\n");
        String prefix = name.toLowerCase(Locale.US) + ":";
        for (int i = 0; i < lines.length; i++) {
            String l = lines[i];
            if (l.toLowerCase(Locale.US).startsWith(prefix)) return l.substring(prefix.length()).trim();
        }
        return "";
    }

    private static Device describe(String location) throws Exception {
        String xml = "";
        Network used = null;
        Throwable last = null;
        ArrayList<Network> nets = candidateNets();
        for (int i = 0; i < nets.size() && xml.length() == 0; i++) {
            Network n = nets.get(i);
            try {
                xml = get(location, n);
                if (xml.length() > 0) used = n;
            } catch (Throwable error) {
                last = error;
            }
        }
        // not reachable (yet): an error, so the caller retries soon instead of blacklisting the address
        if (xml.length() == 0) {
            throw new java.io.IOException("description not reachable: " + location + (last == null ? "" : " (" + last + ")"));
        }
        Device d = parseDevice(location, xml);
        if (d != null) d.net = used;
        return d;
    }

    private static String clean(String raw) {
        String x = raw.replace("\u0000", "");
        int a = x.indexOf('<');
        int b = x.lastIndexOf('>');
        return (a < 0 || b < a) ? x : x.substring(a, b + 1);
    }

    private static String first(String xml, String tag) {
        java.util.regex.Matcher m = java.util.regex.Pattern.compile(
                "<" + tag + "(?:\\s[^>]*)?>(.*?)</" + tag + ">",
                java.util.regex.Pattern.DOTALL | java.util.regex.Pattern.CASE_INSENSITIVE).matcher(xml);
        return m.find() ? m.group(1).trim() : "";
    }

    /** null = answers fine but is not a media renderer. Lenient: TVs with sloppy XML are still read. */
    private static Device parseDevice(String location, String raw) throws Exception {
        String xml = clean(raw);
        String type = "", control = "", name = "", udn = "", base = "";
        boolean parsed = false;
        try {
            Document doc = DocumentBuilderFactory.newInstance().newDocumentBuilder()
                    .parse(new InputSource(new StringReader(xml)));
            NodeList services = doc.getElementsByTagName("service");
            for (int i = 0; i < services.getLength(); i++) {
                Element s = (Element) services.item(i);
                String st = child(s, "serviceType");
                if (st.contains("service:AVTransport:")) {
                    type = st;
                    control = child(s, "controlURL");
                    break;
                }
            }
            NodeList devs = doc.getElementsByTagName("device");
            if (devs.getLength() > 0) {
                Element root = (Element) devs.item(0);
                name = child(root, "friendlyName");
                udn = child(root, "UDN");
            }
            base = child(doc.getDocumentElement(), "URLBase");
            parsed = true;
        } catch (Throwable error) {
            Log.w(TAG, "strict XML failed, reading leniently " + location, error);
        }
        if (!parsed) {
            java.util.regex.Matcher m = java.util.regex.Pattern.compile("<service(?:\\s[^>]*)?>(.*?)</service>",
                    java.util.regex.Pattern.DOTALL | java.util.regex.Pattern.CASE_INSENSITIVE).matcher(xml);
            while (m.find()) {
                String blk = m.group(1);
                String st = first(blk, "serviceType");
                if (st.contains("service:AVTransport:")) {
                    type = st;
                    control = first(blk, "controlURL");
                    break;
                }
            }
            name = first(xml, "friendlyName");
            udn = first(xml, "UDN");
            base = first(xml, "URLBase");
        }
        if (control.length() == 0) return null; // not a media renderer
        Device d = new Device();
        d.location = location;
        d.serviceType = type;
        d.name = name.length() == 0 ? "تلفاز" : name;
        d.udn = udn;
        URL baseUrl = new URL(base.length() > 0 ? base : location);
        d.controlUrl = new URL(baseUrl, control).toString();
        d.lastSeen = System.currentTimeMillis();
        return d;
    }

    private static String child(Element e, String tag) {
        NodeList l = e.getElementsByTagName(tag);
        if (l.getLength() == 0) return "";
        String t = l.item(0).getTextContent();
        return t == null ? "" : t.trim();
    }

    // ------------------------------------------------------------------ control

    private static String get(String url, Network net) throws Exception {
        HttpURLConnection c = open(url, net);
        c.setConnectTimeout(2500);
        c.setReadTimeout(4000);
        try {
            if (c.getResponseCode() / 100 != 2) return "";
            return read(c.getInputStream());
        } finally {
            c.disconnect();
        }
    }

    private static String read(InputStream in) throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] b = new byte[4096];
        int n;
        while ((n = in.read(b)) > 0 && out.size() < 400000) out.write(b, 0, n);
        return out.toString("UTF-8");
    }

    /** Sends one AVTransport action; returns the response body, or null when the TV refused it. */
    private static String soap(Device d, String action, String args) {
        String body = "<?xml version=\"1.0\" encoding=\"utf-8\"?>"
                + "<s:Envelope xmlns:s=\"http://schemas.xmlsoap.org/soap/envelope/\" "
                + "s:encodingStyle=\"http://schemas.xmlsoap.org/soap/encoding/\"><s:Body>"
                + "<u:" + action + " xmlns:u=\"" + d.serviceType + "\"><InstanceID>0</InstanceID>"
                + args + "</u:" + action + "></s:Body></s:Envelope>";
        // the network the TV answered on first; if that link is gone (Wi-Fi reconnect) try one other
        ArrayList<Network> nets = new ArrayList<Network>();
        nets.add(d.net);
        Network w = wifiNet;
        nets.add(w != null && w != d.net ? w : (d.net != null ? null : w));
        Throwable last = null;
        for (int attempt = 0; attempt < nets.size(); attempt++) {
            if (attempt > 0 && nets.get(attempt) == nets.get(0)) break;
            try {
                HttpURLConnection c = open(d.controlUrl, nets.get(attempt));
                c.setConnectTimeout(4000);
                c.setReadTimeout(8000);
                c.setRequestMethod("POST");
                c.setDoOutput(true);
                c.setRequestProperty("Content-Type", "text/xml; charset=\"utf-8\"");
                c.setRequestProperty("SOAPACTION", "\"" + d.serviceType + "#" + action + "\"");
                byte[] bytes = body.getBytes("UTF-8");
                c.setFixedLengthStreamingMode(bytes.length);
                OutputStream os = c.getOutputStream();
                os.write(bytes);
                os.close();
                int code = c.getResponseCode();
                String resp;
                try {
                    resp = read(code / 100 == 2 ? c.getInputStream() : c.getErrorStream());
                } catch (Throwable e) {
                    resp = "";
                }
                c.disconnect();
                if (code / 100 != 2) {
                    Log.w(TAG, action + " refused: " + code + " " + resp);
                    return null;
                }
                if (attempt > 0) d.net = nets.get(attempt);   // remember what works
                return resp;
            } catch (Throwable error) {
                last = error;
            }
        }
        Log.w(TAG, action + " failed", last);
        return null;
    }

    private static String esc(String s) {
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
                .replace("\"", "&quot;").replace("'", "&apos;");
    }

    private static String protocolFor(String mime) {
        String flags = "DLNA.ORG_OP=01;DLNA.ORG_CI=0;DLNA.ORG_FLAGS=01700000000000000000000000000000";
        return "http-get:*:" + mime + ":" + flags;
    }

    private static String didl(String title, String url, String mime) {
        return "<DIDL-Lite xmlns=\"urn:schemas-upnp-org:metadata-1-0/DIDL-Lite/\" "
                + "xmlns:dc=\"http://purl.org/dc/elements/1.1/\" "
                + "xmlns:upnp=\"urn:schemas-upnp-org:metadata-1-0/upnp/\">"
                + "<item id=\"0\" parentID=\"-1\" restricted=\"1\">"
                + "<dc:title>" + esc(title) + "</dc:title>"
                + "<upnp:class>object.item.videoItem</upnp:class>"
                + "<res protocolInfo=\"" + esc(protocolFor(mime)) + "\">" + esc(url) + "</res>"
                + "</item></DIDL-Lite>";
    }

    /** Loads a link on the TV (does not start it). */
    public static boolean setUri(Device d, String url, String title, String mime) {
        String args = "<CurrentURI>" + esc(url) + "</CurrentURI><CurrentURIMetaData>"
                + esc(didl(title, url, mime)) + "</CurrentURIMetaData>";
        if (soap(d, "SetAVTransportURI", args) != null) return true;
        // some TVs reject the metadata: retry with an empty one
        return soap(d, "SetAVTransportURI", "<CurrentURI>" + esc(url)
                + "</CurrentURI><CurrentURIMetaData></CurrentURIMetaData>") != null;
    }

    public static boolean play(Device d) {
        return soap(d, "Play", "<Speed>1</Speed>") != null;
    }

    public static boolean stop(Device d) {
        return soap(d, "Stop", "") != null;
    }

    public static boolean seek(Device d, long ms) {
        String t = time(ms);
        if (soap(d, "Seek", "<Unit>REL_TIME</Unit><Target>" + t + "</Target>") != null) return true;
        return soap(d, "Seek", "<Unit>ABS_TIME</Unit><Target>" + t + "</Target>") != null;
    }

    public static Info info(Device d) {
        Info i = new Info();
        String t = soap(d, "GetTransportInfo", "");
        if (t == null) return null;
        i.state = tag(t, "CurrentTransportState");
        String p = soap(d, "GetPositionInfo", "");
        if (p != null) {
            i.positionMs = parse(tag(p, "RelTime"));
            i.durationMs = parse(tag(p, "TrackDuration"));
        }
        return i;
    }

    private static String tag(String xml, String name) {
        int a = xml.indexOf("<" + name + ">");
        if (a < 0) return "";
        int b = xml.indexOf("</" + name + ">", a);
        if (b < 0) return "";
        return xml.substring(a + name.length() + 2, b).trim();
    }

    private static long parse(String s) {
        try {
            if (s.length() == 0 || s.contains("NOT_IMPL")) return 0L;
            String[] p = s.split(":");
            if (p.length != 3) return 0L;
            double sec = Double.parseDouble(p[2]);
            return (long) ((Long.parseLong(p[0]) * 3600L + Long.parseLong(p[1]) * 60L) * 1000L + sec * 1000.0);
        } catch (Throwable e) {
            return 0L;
        }
    }

    private static String time(long ms) {
        long s = Math.max(0L, ms / 1000L);
        return String.format(Locale.US, "%d:%02d:%02d", s / 3600L, (s % 3600L) / 60L, s % 60L);
    }
}
