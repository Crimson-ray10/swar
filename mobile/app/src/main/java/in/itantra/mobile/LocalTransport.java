package in.itantra.mobile;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.net.NetworkInfo;
import android.net.nsd.*;
import android.net.wifi.WifiManager;
import android.net.wifi.WpsInfo;
import android.net.wifi.p2p.*;
import android.net.wifi.p2p.WifiP2pManager.*;
import android.os.Build;
import android.util.Log;
import org.json.*;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;
import java.security.SecureRandom;

/**
 * Foreground, two-peer LAN and WiFi Direct transport.
 * Supports WiFi Direct (P2P), mDNS DNS-SD, UDP Broadcast Beacon (port 8989), and TCP direct streaming (port 8988).
 * Auto-links walkie-talkie devices in proximity with zero friction.
 */
public final class LocalTransport {
    private static final String TAG = "LocalTransport";
    public interface Listener { void event(String type, JSONObject data); void received(ItpPacket.Decoded message); }
    private final Context context;
    private final Listener listener;
    private final NsdManager nsd;
    private final WifiManager.MulticastLock multicast;
    private final ExecutorService io = Executors.newCachedThreadPool();
    private final ExecutorService sender = Executors.newSingleThreadExecutor();
    private final ScheduledExecutorService clock = Executors.newSingleThreadScheduledExecutor();
    private final UUID session = UUID.randomUUID();
    private final Map<Long, Long> pending = new ConcurrentHashMap<>();
    private final LinkedHashSet<String> seen = new LinkedHashSet<>();
    private final Queue<String[]> outbox = new ConcurrentLinkedQueue<>();
    private final Object writeLock = new Object();

    // WiFi Direct (P2P) fields
    private WifiP2pManager p2pManager;
    private Channel p2pChannel;
    private BroadcastReceiver p2pReceiver;
    private volatile boolean p2pReceiverRegistered = false;
    private volatile boolean p2pDiscovering = false;
    private volatile boolean p2pEnabled = false;
    private volatile boolean p2pGroupConnected = false;
    private volatile boolean p2pIsGroupOwner = false;
    private volatile String p2pGroupOwnerAddress = null;
    private volatile String myP2pAddress = null;

    private volatile Socket socket;
    private volatile ServerSocket server;
    private volatile DatagramSocket udpSocket;
    private volatile boolean connected = false, closed = false, hosting = false;
    private volatile String pin = "", address = "", peer = "", state = "DISCONNECTED", callsign = Build.MODEL;
    private volatile int port = 8988;
    private long seq = 0, sent = 0, received = 0, bytesSent = 0, bytesReceived = 0, sourceSent = 0, payloadSent = 0;
    private long crcFailures = 0, fecFailures = 0, corrected = 0, duplicates = 0, unacked = 0, lastPacketBytes = 0;
    private volatile long lastReceived = now(), nextSend = 0, generation = 0;
    private final long metricsStarted = now();
    private volatile int bitrate = 2000;
    private volatile long rtt = -1;
    private int retry = 0;
    private UUID remoteSession;
    private long remoteSequence = 0;
    private NsdManager.RegistrationListener registration;
    private NsdManager.DiscoveryListener discovery;
    private final Set<String> resolving = ConcurrentHashMap.newKeySet();
    private volatile Socket pendingSocket = null;
    private volatile ScheduledFuture<?> approvalTimer = null;

    public LocalTransport(Context context, Listener listener) {
        this.context = context;
        this.listener = listener;
        nsd = (NsdManager) context.getSystemService(Context.NSD_SERVICE);
        WifiManager wifi = (WifiManager) context.getApplicationContext().getSystemService(Context.WIFI_SERVICE);
        multicast = wifi.createMulticastLock("linc-discovery");
        multicast.setReferenceCounted(false);
        initWifiP2p();
        clock.scheduleWithFixedDelay(this::tick, 2, 2, TimeUnit.SECONDS);
        startListening();
        startUdpBeaconListener();
    }

    private static long now() { return android.os.SystemClock.elapsedRealtime(); }

    static JSONObject json(Object... fields) {
        JSONObject o = new JSONObject();
        try {
            for (int i = 0; i < fields.length; i += 2) o.put((String) fields[i], fields[i + 1]);
        } catch (JSONException e) {
            throw new IllegalArgumentException(e);
        }
        return o;
    }

    private void event(String type, Object... fields) {
        listener.event(type, json(fields));
    }

    private void state(String value) {
        state = value;
        Log.i(TAG, "Transport state changed -> " + value + " (peer=" + peer + ")");
        event("connection", "state", value, "peer", peer);
    }

    public boolean isConnected() { return connected; }
    public void bitrate(int value) {
        if (value == 500 || value == 1000 || value == 2000 || value == 4000 || value == 8000 || value == 16000) {
            bitrate = value;
        }
    }
    public void callsign(String name) {
        if (name != null && !name.trim().isEmpty()) {
            String trimmed = name.trim();
            if (!trimmed.equals(callsign)) {
                callsign = trimmed;
                if (registration != null && nsd != null) {
                    try {
                        nsd.unregisterService(registration);
                    } catch (Exception ignored) {}
                    registration = null;
                    advertise();
                }
            }
        }
    }

    public boolean isSelfAddress(String host) {
        if (host == null || host.isEmpty() || host.equals("127.0.0.1") || host.equals("localhost")) return true;
        try {
            for (NetworkInterface iface : Collections.list(NetworkInterface.getNetworkInterfaces())) {
                for (InetAddress addr : Collections.list(iface.getInetAddresses())) {
                    if (host.equals(addr.getHostAddress())) return true;
                }
            }
        } catch (Exception ignored) {}
        return false;
    }

    // ==========================================
    // WiFi Direct (P2P) Implementation
    // ==========================================

    private void initWifiP2p() {
        try {
            if (context != null) {
                p2pManager = (WifiP2pManager) context.getSystemService(Context.WIFI_P2P_SERVICE);
                if (p2pManager != null) {
                    p2pChannel = p2pManager.initialize(context, context.getMainLooper(), () -> {
                        Log.w(TAG, "WiFi P2P Channel disconnected; re-initializing...");
                        try {
                            p2pChannel = p2pManager.initialize(context, context.getMainLooper(), null);
                        } catch (Exception e) {
                            Log.w(TAG, "Failed to reinitialize P2P channel: " + e.getMessage());
                        }
                    });
                    Log.i(TAG, "WiFi P2P initialized successfully");
                    if (Build.VERSION.SDK_INT >= 29) {
                        try {
                            p2pManager.requestDeviceInfo(p2pChannel, device -> {
                                if (device != null && device.deviceAddress != null) {
                                    myP2pAddress = device.deviceAddress;
                                    Log.i(TAG, "Self P2P device initialized: " + myP2pAddress);
                                    event("self_p2p", "p2pAddress", device.deviceAddress, "name", device.deviceName != null ? device.deviceName : "");
                                }
                            });
                        } catch (Exception ignored) {}
                    }
                } else {
                    Log.w(TAG, "WifiP2pManager is null; WiFi Direct not supported on this device");
                }
            }
        } catch (Exception e) {
            Log.w(TAG, "Failed to initialize WiFi Direct: " + e.getMessage());
        }
    }

    public String getMyP2pAddress() {
        return myP2pAddress;
    }

    public void requestSelfP2pInfo() {
        if (myP2pAddress != null && !myP2pAddress.isEmpty()) {
            event("self_p2p", "p2pAddress", myP2pAddress);
        } else if (Build.VERSION.SDK_INT >= 29 && p2pManager != null && p2pChannel != null) {
            try {
                p2pManager.requestDeviceInfo(p2pChannel, device -> {
                    if (device != null && device.deviceAddress != null) {
                        myP2pAddress = device.deviceAddress;
                        Log.i(TAG, "Self P2P device initialized via request: " + myP2pAddress);
                        event("self_p2p", "p2pAddress", device.deviceAddress, "name", device.deviceName != null ? device.deviceName : "");
                    }
                });
            } catch (Exception ignored) {}
        }
    }

    public synchronized void registerP2pReceiver() {
        if (context == null || p2pManager == null || p2pChannel == null || p2pReceiverRegistered) return;
        try {
            if (p2pReceiver == null) {
                p2pReceiver = new BroadcastReceiver() {
                    @Override
                    public void onReceive(Context ctx, Intent intent) {
                        handleP2pIntent(intent);
                    }
                };
            }
            IntentFilter filter = new IntentFilter();
            filter.addAction(WifiP2pManager.WIFI_P2P_STATE_CHANGED_ACTION);
            filter.addAction(WifiP2pManager.WIFI_P2P_PEERS_CHANGED_ACTION);
            filter.addAction(WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION);
            filter.addAction(WifiP2pManager.WIFI_P2P_THIS_DEVICE_CHANGED_ACTION);

            if (Build.VERSION.SDK_INT >= 33) {
                context.registerReceiver(p2pReceiver, filter, Context.RECEIVER_EXPORTED);
            } else {
                context.registerReceiver(p2pReceiver, filter);
            }
            p2pReceiverRegistered = true;
            Log.i(TAG, "WiFi P2P BroadcastReceiver registered");
            discoverP2pPeers();
        } catch (Exception e) {
            Log.w(TAG, "Failed to register P2P receiver: " + e.getMessage());
        }
    }

    public synchronized void unregisterP2pReceiver() {
        if (!p2pReceiverRegistered || context == null || p2pReceiver == null) return;
        try {
            context.unregisterReceiver(p2pReceiver);
            p2pReceiverRegistered = false;
            Log.i(TAG, "WiFi P2P BroadcastReceiver unregistered");
        } catch (Exception e) {
            Log.w(TAG, "Failed to unregister P2P receiver: " + e.getMessage());
        }
    }

    private void handleP2pIntent(Intent intent) {
        if (intent == null) return;
        String action = intent.getAction();
        if (action == null) return;

        switch (action) {
            case WifiP2pManager.WIFI_P2P_STATE_CHANGED_ACTION -> {
                int state = intent.getIntExtra(WifiP2pManager.EXTRA_WIFI_STATE, -1);
                p2pEnabled = (state == WifiP2pManager.WIFI_P2P_STATE_ENABLED);
                Log.i(TAG, "WiFi P2P state changed: " + (p2pEnabled ? "ENABLED" : "DISABLED"));
                if (!p2pEnabled) {
                    p2pDiscovering = false;
                }
            }

            case WifiP2pManager.WIFI_P2P_PEERS_CHANGED_ACTION -> {
                if (p2pManager != null && p2pChannel != null) {
                    try {
                        p2pManager.requestPeers(p2pChannel, peers -> {
                            if (peers == null) return;
                            Collection<WifiP2pDevice> deviceList = peers.getDeviceList();
                            Log.i(TAG, "WiFi P2P peers discovered count: " + deviceList.size());
                            for (WifiP2pDevice device : deviceList) {
                                String p2pAddr = device.deviceAddress;
                                String devName = device.deviceName;
                                if (p2pAddr == null) continue;
                                if (myP2pAddress != null && p2pAddr.equalsIgnoreCase(myP2pAddress)) continue;

                                if (device.status == WifiP2pDevice.UNAVAILABLE || device.status == WifiP2pDevice.FAILED) {
                                    event("peerLost", "name", devName != null ? devName : "", "p2pAddress", p2pAddr);
                                    continue;
                                }

                                Log.i(TAG, "P2P peer found: " + devName + " (" + p2pAddr + ") status=" + p2pDeviceStatus(device.status));
                                event("peer",
                                    "name", devName != null && !devName.isEmpty() ? devName : "WiFi Direct Peer",
                                    "model", devName != null ? devName : "",
                                    "address", "",
                                    "p2pAddress", p2pAddr,
                                    "port", 8988,
                                    "source", "p2p",
                                    "status", p2pDeviceStatus(device.status)
                                );
                            }
                        });
                    } catch (SecurityException se) {
                        Log.w(TAG, "SecurityException requesting P2P peers: " + se.getMessage());
                    } catch (Exception e) {
                        Log.w(TAG, "Error requesting P2P peers: " + e.getMessage());
                    }
                }
            }

            case WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION -> {
                NetworkInfo networkInfo = intent.getParcelableExtra(WifiP2pManager.EXTRA_NETWORK_INFO);
                boolean isP2pConn = networkInfo != null && networkInfo.isConnected();
                Log.i(TAG, "WiFi P2P connection changed: " + (isP2pConn ? "CONNECTED" : "DISCONNECTED"));
                if (isP2pConn && p2pManager != null && p2pChannel != null) {
                    try {
                        p2pManager.requestConnectionInfo(p2pChannel, info -> {
                            if (info == null) return;
                            p2pGroupConnected = info.groupFormed;
                            p2pIsGroupOwner = info.isGroupOwner;
                            InetAddress goInet = info.groupOwnerAddress;
                            p2pGroupOwnerAddress = goInet != null ? goInet.getHostAddress() : null;

                            Log.i(TAG, "P2P Group Formed! isGroupOwner=" + p2pIsGroupOwner + ", GO Address=" + p2pGroupOwnerAddress);
                            event("p2p_connection",
                                "groupFormed", info.groupFormed,
                                "isGroupOwner", info.isGroupOwner,
                                "groupOwnerAddress", p2pGroupOwnerAddress != null ? p2pGroupOwnerAddress : ""
                            );

                            if (info.groupFormed) {
                                if (p2pIsGroupOwner) {
                                    // Group Owner hosts TCP server on port 8988
                                    Log.i(TAG, "Acting as P2P Group Owner; listening on port 8988 for incoming client TCP");
                                    startListening();
                                } else {
                                    // Client connects to the Group Owner's IP on port 8988
                                    if (p2pGroupOwnerAddress != null && !p2pGroupOwnerAddress.isEmpty()) {
                                        Log.i(TAG, "Acting as P2P Client; connecting to GO at " + p2pGroupOwnerAddress + ":8988");
                                        clock.schedule(() -> {
                                            connect(p2pGroupOwnerAddress, 8988, pin, callsign);
                                        }, 600, TimeUnit.MILLISECONDS);
                                    }
                                }
                            }
                        });
                    } catch (Exception e) {
                        Log.w(TAG, "Error requesting P2P connection info: " + e.getMessage());
                    }
                } else if (!isP2pConn) {
                    p2pGroupConnected = false;
                    p2pIsGroupOwner = false;
                    p2pGroupOwnerAddress = null;
                }
            }

            case WifiP2pManager.WIFI_P2P_THIS_DEVICE_CHANGED_ACTION -> {
                WifiP2pDevice thisDevice = intent.getParcelableExtra(WifiP2pManager.EXTRA_WIFI_P2P_DEVICE);
                if (thisDevice != null && thisDevice.deviceAddress != null) {
                    myP2pAddress = thisDevice.deviceAddress;
                    Log.i(TAG, "This device P2P address: " + thisDevice.deviceAddress + " (" + thisDevice.deviceName + ")");
                    event("self_p2p", "p2pAddress", thisDevice.deviceAddress, "name", thisDevice.deviceName != null ? thisDevice.deviceName : "");
                }
            }
        }
    }

    public void discoverP2pPeers() {
        if (p2pManager == null || p2pChannel == null) return;
        try {
            p2pManager.discoverPeers(p2pChannel, new WifiP2pManager.ActionListener() {
                @Override
                public void onSuccess() {
                    p2pDiscovering = true;
                    Log.i(TAG, "WiFi P2P peer discovery started");
                }

                @Override
                public void onFailure(int reason) {
                    p2pDiscovering = false;
                    Log.w(TAG, "WiFi P2P peer discovery failed: " + p2pFailureReason(reason));
                }
            });
        } catch (SecurityException se) {
            Log.w(TAG, "SecurityException on discoverP2pPeers: " + se.getMessage());
        } catch (Exception e) {
            Log.w(TAG, "Error on discoverP2pPeers: " + e.getMessage());
        }
    }

    public void stopP2pDiscovery() {
        if (p2pManager == null || p2pChannel == null) return;
        try {
            p2pManager.stopPeerDiscovery(p2pChannel, new WifiP2pManager.ActionListener() {
                @Override public void onSuccess() { p2pDiscovering = false; }
                @Override public void onFailure(int reason) {}
            });
        } catch (Exception ignored) {}
    }

    public void connectP2p(String p2pAddress) {
        if (p2pManager == null || p2pChannel == null || p2pAddress == null || p2pAddress.trim().isEmpty()) {
            event("error", "message", "WiFi Direct is unavailable or invalid address.");
            return;
        }
        try {
            WifiP2pConfig config = new WifiP2pConfig();
            config.deviceAddress = p2pAddress.trim();
            config.wps.setup = WpsInfo.PBC;

            state("WAITING_APPROVAL");
            Log.i(TAG, "Connecting via WiFi Direct to " + p2pAddress);

            p2pManager.connect(p2pChannel, config, new WifiP2pManager.ActionListener() {
                @Override
                public void onSuccess() {
                    Log.i(TAG, "WiFi P2P connect initiated successfully to " + p2pAddress);
                }

                @Override
                public void onFailure(int reason) {
                    Log.w(TAG, "WiFi P2P connect failed: " + p2pFailureReason(reason));
                    event("error", "message", "WiFi Direct connect failed: " + p2pFailureReason(reason));
                    state("DISCONNECTED");
                }
            });
        } catch (SecurityException se) {
            Log.w(TAG, "SecurityException on connectP2p: " + se.getMessage());
            event("error", "message", "WiFi Direct permission missing: " + se.getMessage());
            state("DISCONNECTED");
        } catch (Exception e) {
            Log.w(TAG, "Exception on connectP2p: " + e.getMessage());
            event("error", "message", "WiFi Direct error: " + e.getMessage());
            state("DISCONNECTED");
        }
    }

    public void disconnectP2pGroup() {
        if (p2pManager == null || p2pChannel == null) return;
        try {
            p2pManager.removeGroup(p2pChannel, new WifiP2pManager.ActionListener() {
                @Override public void onSuccess() { Log.i(TAG, "WiFi P2P group removed"); }
                @Override public void onFailure(int reason) {}
            });
            p2pManager.cancelConnect(p2pChannel, null);
        } catch (Exception ignored) {}
    }

    private static String p2pDeviceStatus(int status) {
        return switch (status) {
            case WifiP2pDevice.AVAILABLE -> "Available";
            case WifiP2pDevice.INVITED -> "Invited";
            case WifiP2pDevice.CONNECTED -> "Connected";
            case WifiP2pDevice.FAILED -> "Failed";
            case WifiP2pDevice.UNAVAILABLE -> "Unavailable";
            default -> "Unknown";
        };
    }

    private static String p2pFailureReason(int reason) {
        return switch (reason) {
            case WifiP2pManager.P2P_UNSUPPORTED -> "P2P unsupported on this device";
            case WifiP2pManager.ERROR -> "Internal framework error";
            case WifiP2pManager.BUSY -> "Framework busy";
            default -> "Code " + reason;
        };
    }

    public void startListening() {
        if (closed || server != null) return;
        io.execute(() -> {
            if (server != null) return;
            try {
                ServerSocket localServer = new ServerSocket();
                localServer.setReuseAddress(true);
                localServer.bind(new InetSocketAddress(8988));
                server = localServer;
                Log.i(TAG, "TCP Server listening on port 8988");
                advertise();
                while (!closed && server == localServer) {
                    Socket incoming = localServer.accept();
                    if (connected) {
                        String incomingIp = incoming.getInetAddress() != null ? incoming.getInetAddress().getHostAddress() : "";
                        String currentPeerIp = socket != null && socket.getInetAddress() != null ? socket.getInetAddress().getHostAddress() : "";
                        if (incomingIp.equals(currentPeerIp)) {
                            Log.i(TAG, "Replacing stale connection with new incoming connection from " + incomingIp);
                            try { if (socket != null) socket.close(); } catch (Exception ignored) {}
                            socket = null;
                            connected = false;
                        } else {
                            try {
                                DataOutputStream out = new DataOutputStream(incoming.getOutputStream());
                                JSONObject busy = json("type", "REJECT", "reason", "Device is busy in another session");
                                FrameIO.write(out, (byte) 1, busy.toString().getBytes(StandardCharsets.UTF_8));
                                incoming.close();
                            } catch (Exception ignored) {}
                            continue;
                        }
                    }

                    // Simultaneous connect tie-breaker
                    if (socket != null && !connected) {
                        String inIp = incoming.getInetAddress() != null ? incoming.getInetAddress().getHostAddress() : "";
                        String myTarget = address != null ? address : "";
                        if (inIp.compareTo(myTarget) > 0) {
                            Log.i(TAG, "Simultaneous connect tie-breaker: yielding to incoming from " + inIp);
                            try { socket.close(); } catch (Exception ignored) {}
                            socket = null;
                        } else {
                            Log.i(TAG, "Simultaneous connect tie-breaker: keeping outgoing to " + myTarget);
                            try { incoming.close(); } catch (Exception ignored) {}
                            continue;
                        }
                    }

                    if (pendingSocket != null) {
                        try { pendingSocket.close(); } catch (Exception ignored) {}
                        pendingSocket = null;
                    }
                    attach(incoming, true);
                }
            } catch (Exception e) {
                Log.w(TAG, "ServerSocket error: " + e.getMessage());
            }
        });
    }

    private void startUdpBeaconListener() {
        io.execute(() -> {
            try {
                DatagramSocket udp = new DatagramSocket(null);
                udp.setReuseAddress(true);
                udp.setBroadcast(true);
                udp.bind(new InetSocketAddress(8989));
                udpSocket = udp;
                Log.i(TAG, "UDP Beacon listening on port 8989");
                byte[] buf = new byte[1024];
                while (!closed && udpSocket == udp) {
                    DatagramPacket packet = new DatagramPacket(buf, buf.length);
                    udp.receive(packet);
                    String msg = new String(packet.getData(), 0, packet.getLength(), StandardCharsets.UTF_8);
                    if (msg.startsWith("LINC_BEACON:")) {
                        String[] parts = msg.split(":");
                        if (parts.length >= 4) {
                            String beaconSession = parts[2];
                            String beaconCallsign = parts[3];
                            String beaconModel = parts.length >= 5 ? parts[4] : "";
                            String beaconP2p = parts.length >= 6 ? parts[5] : "";
                            String senderIp = packet.getAddress() != null ? packet.getAddress().getHostAddress() : "";
                            if (!session.toString().equals(beaconSession) && !isSelfAddress(senderIp)) {
                                Log.i(TAG, "Discovered LinC peer via UDP beacon: " + beaconCallsign + " (" + senderIp + ")" + (!beaconP2p.isEmpty() ? " [p2p:" + beaconP2p + "]" : ""));
                                if (!beaconP2p.isEmpty()) {
                                    event("peer", "name", beaconCallsign, "model", beaconModel, "address", senderIp, "p2pAddress", beaconP2p, "port", 8988, "source", "beacon");
                                } else {
                                    event("peer", "name", beaconCallsign, "model", beaconModel, "address", senderIp, "port", 8988, "source", "beacon");
                                }
                            }
                        }
                    }
                }
            } catch (Exception e) {
                Log.d(TAG, "UDP Beacon listener exited: " + e.getMessage());
            }
        });
    }

    private void broadcastUdpBeacon() {
        if (connected) return;
        io.execute(() -> {
            try {
                String p2pSuffix = (myP2pAddress != null && !myP2pAddress.isEmpty()) ? ":" + myP2pAddress : "";
                String payloadStr = "LINC_BEACON:8988:" + session.toString() + ":" + callsign + ":" + Build.MODEL + p2pSuffix;
                byte[] payload = payloadStr.getBytes(StandardCharsets.UTF_8);
                DatagramSocket sender = new DatagramSocket();
                sender.setBroadcast(true);
                
                // 1. General broadcast
                DatagramPacket p1 = new DatagramPacket(payload, payload.length, InetAddress.getByName("255.255.255.255"), 8989);
                sender.send(p1);

                // 2. Subnet directed broadcast
                for (NetworkInterface iface : Collections.list(NetworkInterface.getNetworkInterfaces())) {
                    for (InterfaceAddress ifaceAddr : iface.getInterfaceAddresses()) {
                        InetAddress bcast = ifaceAddr.getBroadcast();
                        if (bcast != null) {
                            sender.send(new DatagramPacket(payload, payload.length, bcast, 8989));
                        }
                    }
                }
                sender.close();
            } catch (Exception ignored) {}
        });
    }

    public void host() {
        io.execute(() -> {
            if (server != null) { event("host", "pin", pin, "addresses", addresses(), "port", 8988); return; }
            disconnect();
            hosting = true;
            pin = String.format(Locale.US, "%06d", new SecureRandom().nextInt(1000000));
            startListening();
            state("DISCOVERING");
            event("host", "pin", pin, "addresses", addresses(), "port", 8988);
        });
    }

    public void connect(String host, int requestedPort, String code, String clientCallsign) {
        if (!host.matches("(?:\\d{1,3}\\.){3}\\d{1,3}") || requestedPort < 1 || requestedPort > 65535) {
            event("error", "message", "Enter a valid local IPv4 address.");
            return;
        }
        if (isSelfAddress(host)) {
            Log.d(TAG, "Ignoring connect to self address: " + host);
            return;
        }
        try {
            InetAddress ip = InetAddress.getByName(host);
            if (!ip.isSiteLocalAddress() && !ip.isLinkLocalAddress()) {
                event("error", "message", "Only local-network addresses are accepted.");
                return;
            }
        } catch (Exception e) {
            return;
        }
        if (clientCallsign != null && !clientCallsign.trim().isEmpty()) callsign = clientCallsign.trim();
        disconnect();
        hosting = false;
        address = host;
        port = requestedPort;
        pin = code != null ? code : "";
        retry = 0;
        connectInternal();
    }

    public void connect(String host, int requestedPort, String code) {
        connect(host, requestedPort, code, callsign);
    }

    private void connectInternal() {
        long cycle = generation;
        io.execute(() -> {
            if (closed || hosting || connected || address.isEmpty() || cycle != generation) return;
            state(retry == 0 ? "WAITING_APPROVAL" : "RECONNECTING");
            Socket next = new Socket();
            try {
                Log.i(TAG, "Connecting to peer at " + address + ":" + port + "...");
                next.connect(new InetSocketAddress(address, port), 4000);
                if (cycle != generation) {
                    next.close();
                    return;
                }
                attach(next, false);
            } catch (Exception e) {
                try { next.close(); } catch (Exception ignored) {}
                if (cycle == generation) lost(null, e.getMessage());
            }
        });
    }

    private void attach(Socket next, boolean accepted) throws IOException {
        next.setTcpNoDelay(true);
        next.setSoTimeout(45000);
        socket = next;
        connected = false;
        lastReceived = now();
        remoteSession = null;
        remoteSequence = 0;
        state(accepted ? "CONNECTING" : "WAITING_APPROVAL");
        if (!accepted) {
            control(json("type", "HELLO", "protocol", 1, "session", session.toString(), "name", Build.MODEL, "callsign", callsign, "pin", pin, "codec", "utf8-deflate-hamming84", "languages", "hi,en,hinglish-experimental"));
        }
        io.execute(() -> {
            try {
                DataInputStream input = new DataInputStream(next.getInputStream());
                while (!closed && (socket == next || pendingSocket == next)) {
                    byte[] body = FrameIO.read(input);
                    int length = body.length;
                    synchronized (this) { bytesReceived += length + 4; }
                    lastReceived = now();
                    if (body[0] == 1) {
                        onControl(new JSONObject(new String(body, 1, length - 1, StandardCharsets.UTF_8)), accepted);
                    } else if (body[0] == 2) {
                        if (!connected) throw new IOException("Data before handshake");
                        onPacket(Arrays.copyOfRange(body, 1, length));
                    } else throw new IOException("Unknown frame kind");
                }
            } catch (Exception e) {
                lost(next, e.getMessage());
            }
        });
    }

    private void onControl(JSONObject data, boolean accepted) throws Exception {
        String type = data.optString("type");
        if (!connected) {
            if (accepted && type.equals("HELLO")) {
                if (data.optInt("protocol") != 1 || !data.optString("codec").equals("utf8-deflate-hamming84")) {
                    control(json("type", "REJECT", "reason", "Protocol or codec mismatch"));
                    throw new IOException("Protocol mismatch");
                }
                remoteSession = UUID.fromString(data.getString("session"));
                String reqName = data.optString("name", "Nearby Phone");
                String reqCallsign = data.optString("callsign", reqName);
                peer = reqCallsign;
                pendingSocket = socket;
                String incomingPin = data.optString("pin", "");
                if (!pin.isEmpty() && !pin.equals(incomingPin)) {
                    control(json("type", "REJECT", "reason", "Incorrect PIN"));
                    throw new IOException("Incorrect PIN");
                }
                state("PENDING_APPROVAL");
                String clientIp = socket != null && socket.getInetAddress() != null ? socket.getInetAddress().getHostAddress() : address;
                Log.i(TAG, "Incoming connection request from " + reqCallsign + " (" + clientIp + "). Showing Accept/Decline modal on receiver.");
                event("connection_request", "peer", reqCallsign, "name", reqName, "callsign", reqCallsign, "address", clientIp, "pin", incomingPin);
                if (approvalTimer != null) approvalTimer.cancel(false);
                approvalTimer = clock.schedule(() -> {
                    if (pendingSocket != null && !connected) {
                        respondRequest(false);
                    }
                }, 30, TimeUnit.SECONDS);
                return;
            }
            if (!accepted && type.equals("READY") && data.optInt("protocol") == 1 && data.optString("codec").equals("utf8-deflate-hamming84")) {
                remoteSession = UUID.fromString(data.getString("session"));
                peer = data.optString("callsign", data.optString("name", "Phone"));
                Log.i(TAG, "Connection READY received from peer: " + peer);
                ready();
                return;
            }
            if (!accepted && type.equals("REJECT")) {
                String reason = data.optString("reason", "Connection request was declined by " + peer);
                event("request_declined", "peer", peer, "reason", reason);
                retry = 99;
                address = "";
                lost(socket, reason);
                return;
            }
            if (accepted && type.equals("CANCEL")) {
                if (approvalTimer != null) { approvalTimer.cancel(false); approvalTimer = null; }
                event("request_cancelled", "peer", peer);
                if (pendingSocket != null) { try { pendingSocket.close(); } catch (Exception ignored) {} pendingSocket = null; }
                lost(socket, "Request cancelled by peer");
                return;
            }
            throw new IOException(type.equals("REJECT") ? "Connection rejected" : "Invalid handshake");
        }
        switch (type) {
            case "PING" -> control(json("type", "PONG", "at", data.getLong("at")));
            case "PONG" -> rtt = Math.max(0, now() - data.getLong("at"));
            case "ACK" -> {
                long sequence = data.getLong("seq");
                Long start = pending.remove(sequence);
                if (start != null) {
                    event("delivery", "sequence", sequence, "state", "Decoded by peer", "ackMs", now() - start);
                }
            }
            case "BYE" -> throw new IOException("Peer disconnected");
            default -> throw new IOException("Unknown control message");
        }
    }

    public void respondRequest(boolean accept) {
        if (approvalTimer != null) { approvalTimer.cancel(false); approvalTimer = null; }
        Socket s = pendingSocket != null ? pendingSocket : socket;
        if (s == null) return;
        io.execute(() -> {
            try {
                if (accept) {
                    socket = s;
                    pendingSocket = null;
                    control(json("type", "READY", "protocol", 1, "session", session.toString(), "name", Build.MODEL, "callsign", callsign, "codec", "utf8-deflate-hamming84"));
                    ready();
                } else {
                    socket = s;
                    control(json("type", "REJECT", "reason", "Connection declined by " + (callsign.isEmpty() ? Build.MODEL : callsign)));
                    pendingSocket = null;
                    retry = 99;
                    lost(s, "Connection declined by user");
                }
            } catch (Exception e) {
                lost(s, e.getMessage());
            }
        });
    }

    public void cancelRequest() {
        if (!connected && socket != null) {
            io.execute(() -> {
                try { control(json("type", "CANCEL")); } catch (Exception ignored) {}
                disconnect();
            });
        }
    }

    private void ready() {
        lastReceived = now();
        connected = true;
        retry = 0;
        state("CONNECTED");
        flushOutbox();
    }

    private void flushOutbox() {
        String[] msg;
        while ((msg = outbox.poll()) != null) {
            Log.i(TAG, "Flushing queued speech to peer: '" + msg[0] + "' (" + msg[1] + ")");
            sendTextInternal(msg[0], msg[1]);
        }
    }

    private void onPacket(byte[] wire) throws IOException {
        ItpPacket.Decoded message;
        try {
            message = ItpPacket.decode(wire);
        } catch (IOException e) {
            synchronized (this) {
                if (e.getMessage() != null && e.getMessage().contains("CRC")) crcFailures++;
                else fecFailures++;
            }
            event("error", "message", "Packet rejected: " + e.getMessage());
            return;
        }
        if (!message.session().equals(remoteSession)) throw new IOException("Session mismatch");
        String key = message.session() + ":" + message.sequence();
        synchronized (this) {
            if (seen.contains(key)) {
                duplicates++;
                control(json("type", "ACK", "seq", message.sequence()));
                return;
            }
            if (message.sequence() <= remoteSequence) throw new IOException("Out-of-order sequence");
            remoteSequence = message.sequence();
            seen.add(key);
            if (seen.size() > 1024) seen.remove(seen.iterator().next());
            received++;
            corrected += message.correctedCodewords();
        }
        control(json("type", "ACK", "seq", message.sequence()));
        Log.i(TAG, "Received ITP packet from peer: '" + message.text() + "', forwarding to listener");
        listener.received(message);
    }

    public void sendText(String text, String language) {
        if (text == null || text.trim().isEmpty()) return;
        if (!connected) {
            if (outbox.size() < 10) {
                outbox.add(new String[]{text.trim(), language});
                Log.i(TAG, "Not connected yet; queued speech for auto-connect: '" + text.trim() + "'");
                event("notice", "message", "Linking to peer phone... speech queued");
            }
            discover();
            return;
        }
        sendTextInternal(text, language);
    }

    private void sendTextInternal(String text, String language) {
        Socket target = socket;
        sender.execute(() -> {
            try {
                if (target != socket || !connected) throw new IOException("Connection changed before transmission");
                long sequence;
                synchronized (this) { sequence = ++seq; }
                var packet = ItpPacket.encode(session, sequence, language, text.trim());
                long delay = Math.max(0, nextSend - now());
                if (delay > 0) Thread.sleep(delay);
                if (target != socket || !connected) throw new IOException("Connection lost while waiting for bitrate budget");
                nextSend = now() + Math.max(1, packet.payloadBytes() * 8000L / bitrate);
                event("sent", "text", text.trim(), "sequence", sequence, "packetBytes", packet.wire().length + 5, "payloadBytes", packet.payloadBytes());
                pending.put(sequence, now());
                write((byte) 2, packet.wire());
                synchronized (this) {
                    sent++;
                    sourceSent += packet.sourceBytes();
                    payloadSent += packet.payloadBytes();
                    lastPacketBytes = packet.wire().length + 5;
                }
                Log.i(TAG, "Successfully sent ITP packet #" + sequence + " to peer: '" + text.trim() + "'");
            } catch (Exception e) {
                event("error", "message", "Message not sent: " + e.getMessage());
            }
        });
    }

    private void control(JSONObject data) throws IOException {
        write((byte) 1, data.toString().getBytes(StandardCharsets.UTF_8));
    }

    private void write(byte kind, byte[] bytes) throws IOException {
        synchronized (writeLock) {
            Socket current = socket != null ? socket : pendingSocket;
            if (current == null) throw new IOException("Disconnected");
            DataOutputStream out = new DataOutputStream(current.getOutputStream());
            FrameIO.write(out, kind, bytes);
            bytesSent += bytes.length + 5;
        }
    }

    private void tick() {
        try {
            if (connected) {
                if (now() - lastReceived > 9000) {
                    lost(socket, "Heartbeat timeout");
                    return;
                }
                control(json("type", "PING", "at", now()));
            } else {
                broadcastUdpBeacon();
                if (!p2pDiscovering && p2pEnabled) {
                    discoverP2pPeers();
                }
            }
            for (var entry : pending.entrySet()) {
                if (now() - entry.getValue() > 12000 && pending.remove(entry.getKey(), entry.getValue())) {
                    unacked++;
                    event("delivery", "sequence", entry.getKey(), "state", "No decode acknowledgement; delivery uncertain");
                }
            }
            double seconds = Math.max(1, (now() - metricsStarted) / 1000.0);
            event("metrics", "sent", sent, "received", received, "txBytes", bytesSent, "rxBytes", bytesReceived, "appTxBps", Math.round(bytesSent * 8 / seconds), "payloadBps", Math.round(payloadSent * 8 / seconds), "payloadBytes", payloadSent, "sourceBytes", sourceSent, "crcFailures", crcFailures, "fecFailures", fecFailures, "fecCorrected", corrected, "duplicates", duplicates, "unacknowledged", unacked, "rttMs", rtt, "configuredBps", bitrate, "lastPacketBytes", lastPacketBytes, "overheadBytes", bytesSent - payloadSent, "textCompression", payloadSent == 0 ? 0 : Math.round(sourceSent * 100.0 / payloadSent) / 100.0);

            // Auto-probe personal hotspot gateway - emit peer bubble for manual tap
            if (!connected && !hosting && socket == null && address.isEmpty()) {
                try {
                    for (NetworkInterface network : Collections.list(NetworkInterface.getNetworkInterfaces())) {
                        for (InetAddress ip : Collections.list(network.getInetAddresses())) {
                            if (ip instanceof Inet4Address && !ip.isLoopbackAddress() && ip.isSiteLocalAddress()) {
                                String ipStr = ip.getHostAddress();
                                if (ipStr != null && ipStr.startsWith("192.168.43.") && !ipStr.equals("192.168.43.1")) {
                                    event("peer", "name", "Hotspot Host", "address", "192.168.43.1", "port", 8988);
                                }
                            }
                        }
                    }
                } catch (Exception ignored) {}
            }
        } catch (Exception e) {
            if (connected) lost(socket, e.getMessage());
        }
    }

    private synchronized void lost(Socket expected, String reason) {
        if (expected != null && socket != expected && pendingSocket != expected) return;
        Socket old = socket;
        socket = null;
        connected = false;
        if (old != null) try { old.close(); } catch (Exception ignored) {}
        Socket p = pendingSocket;
        pendingSocket = null;
        if (p != null) try { p.close(); } catch (Exception ignored) {}
        if (approvalTimer != null) { approvalTimer.cancel(false); approvalTimer = null; }
        if (closed) return;
        Log.i(TAG, "Connection lost: " + reason);
        boolean isExplicitDeclineOrCancel = reason != null && (
            reason.contains("Declined") || reason.contains("declined") ||
            reason.contains("reject") || reason.contains("Reject") ||
            reason.contains("cancel") || reason.contains("Cancel") ||
            reason.contains("busy") || reason.contains("Busy") ||
            reason.contains("local user")
        );
        if (isExplicitDeclineOrCancel) {
            retry = 99;
            address = "";
        } else {
            event("error", "message", "Link: " + (reason != null && !reason.trim().isEmpty() ? reason : "Connection closed"));
        }
        if (!hosting && !address.isEmpty() && retry < 4) {
            retry++;
            state("RECONNECTING");
            long cycle = generation;
            clock.schedule(() -> {
                if (cycle == generation && !connected) connectInternal();
            }, Math.min(8, retry * 2), TimeUnit.SECONDS);
        } else {
            state(hosting ? "DISCOVERING" : "DISCONNECTED");
        }
    }

    public synchronized void disconnect() {
        generation++;
        address = "";
        connected = false;
        hosting = false;
        if (approvalTimer != null) { approvalTimer.cancel(false); approvalTimer = null; }
        Socket p = pendingSocket;
        pendingSocket = null;
        if (p != null) try { p.close(); } catch (Exception ignored) {}
        Socket old = socket;
        socket = null;
        if (old != null) try { control(json("type", "BYE")); old.close(); } catch (Exception ignored) {}
        for (long id : pending.keySet()) event("delivery", "sequence", id, "state", "Disconnected; delivery uncertain");
        pending.clear();
        state("DISCONNECTED");
        startListening();
    }

    private void advertise() {
        if (registration != null) return;
        NsdServiceInfo info = new NsdServiceInfo();
        String nodeName = callsign != null && !callsign.isEmpty() ? callsign : Build.MODEL;
        info.setServiceName("LinC-" + nodeName.replaceAll("[^a-zA-Z0-9-]", "") + "-" + session.toString().substring(0, 4));
        info.setServiceType("_itantra._tcp.");
        info.setPort(8988);
        registration = new NsdManager.RegistrationListener() {
            public void onServiceRegistered(NsdServiceInfo i) { Log.i(TAG, "mDNS Service registered: " + i.getServiceName()); }
            public void onRegistrationFailed(NsdServiceInfo i, int error) { event("error", "message", "Discovery advertising unavailable; use host address."); }
            public void onServiceUnregistered(NsdServiceInfo i) {}
            public void onUnregistrationFailed(NsdServiceInfo i, int e) {}
        };
        try {
            nsd.registerService(info, NsdManager.PROTOCOL_DNS_SD, registration);
        } catch (Exception e) {
            event("error", "message", "Use host address; discovery registration failed.");
        }
    }

    public void discover() {
        startListening();
        broadcastUdpBeacon();
        discoverP2pPeers();
        if (discovery != null) return;
        try {
            multicast.acquire();
            discovery = new NsdManager.DiscoveryListener() {
                public void onDiscoveryStarted(String type) {
                    event("discovery", "state", "Scanning local network");
                    Log.i(TAG, "mDNS Discovery started");
                }
                public void onServiceFound(NsdServiceInfo info) {
                    if (info.getServiceName().contains(session.toString().substring(0, 4)) || !resolving.add(info.getServiceName())) return;
                    Log.i(TAG, "mDNS Service found: " + info.getServiceName() + ", resolving...");
                    nsd.resolveService(info, new NsdManager.ResolveListener() {
                        public void onResolveFailed(NsdServiceInfo i, int e) {
                            resolving.remove(i.getServiceName());
                        }
                        public void onServiceResolved(NsdServiceInfo i) {
                            resolving.remove(i.getServiceName());
                            String host = i.getHost().getHostAddress();
                            if (host != null && host.contains(".") && !isSelfAddress(host)) {
                                Log.i(TAG, "mDNS Service resolved: " + i.getServiceName() + " -> " + host + ":" + i.getPort());
                                event("peer", "name", i.getServiceName(), "serviceName", i.getServiceName(), "address", host, "port", i.getPort(), "source", "mdns");
                            }
                        }
                    });
                }
                public void onServiceLost(NsdServiceInfo info) {
                    event("peerLost", "name", info.getServiceName(), "serviceName", info.getServiceName());
                }
                public void onDiscoveryStopped(String t) { Log.i(TAG, "mDNS Discovery stopped"); }
                public void onStartDiscoveryFailed(String t, int e) { event("error", "message", "Discovery failed; use the host address shown on the other phone."); }
                public void onStopDiscoveryFailed(String t, int e) {}
            };
            nsd.discoverServices("_itantra._tcp.", NsdManager.PROTOCOL_DNS_SD, discovery);
        } catch (Exception e) {
            event("error", "message", "Discovery unavailable: " + e.getMessage());
        }
    }

    private JSONArray addresses() {
        JSONArray result = new JSONArray();
        try {
            for (NetworkInterface network : Collections.list(NetworkInterface.getNetworkInterfaces())) {
                for (InetAddress ip : Collections.list(network.getInetAddresses())) {
                    if (ip instanceof Inet4Address && !ip.isLoopbackAddress() && ip.isSiteLocalAddress()) {
                        result.put(ip.getHostAddress());
                    }
                }
            }
        } catch (Exception ignored) {}
        return result;
    }

    private void closeServer() {
        ServerSocket old = server;
        server = null;
        if (old != null) try { old.close(); } catch (Exception ignored) {}
        DatagramSocket oldUdp = udpSocket;
        udpSocket = null;
        if (oldUdp != null) try { oldUdp.close(); } catch (Exception ignored) {}
        if (registration != null) {
            try { nsd.unregisterService(registration); } catch (Exception ignored) {}
            registration = null;
        }
    }

    public void close() {
        closed = true;
        disconnect();
        closeServer();
        stopP2pDiscovery();
        disconnectP2pGroup();
        unregisterP2pReceiver();
        if (discovery != null) try { nsd.stopServiceDiscovery(discovery); } catch (Exception ignored) {}
        if (multicast.isHeld()) multicast.release();
        sender.shutdownNow();
        io.shutdownNow();
        clock.shutdownNow();
    }
}
