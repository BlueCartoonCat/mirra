package io.github.bluecartooncat.mirra;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.net.wifi.p2p.WifiP2pManager;
import android.os.Build;
import android.os.IBinder;
import android.util.Log;

/** Owns the P2P listener independently of the activity lifecycle. */
public class CastingService extends Service {
    static final String ACTION_STATUS = "io.github.bluecartooncat.mirra.CAST_STATUS";
    private static volatile CastingService instance;
    private static volatile boolean sessionActive;
    private static volatile String currentTitle;
    private static volatile String currentDetail;
    private WifiP2pManager manager;
    private WifiP2pManager.Channel channel;
    private RTSPServer server;
    private boolean groupWasFormed;
    private boolean stopping;
    private String deviceName;
    private boolean captureAudio;
    private final BroadcastReceiver receiver = new BroadcastReceiver() {
        @Override public void onReceive(Context context, Intent intent) { inspectConnection(); }
    };

    @Override public void onCreate() {
        super.onCreate();
        instance = this;
        NotificationManager notifications = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        notifications.createNotificationChannel(new NotificationChannel("casting", getString(R.string.notification_cast_channel), NotificationManager.IMPORTANCE_LOW));
        Notification notification = new Notification.Builder(this, "casting")
                .setSmallIcon(android.R.drawable.ic_menu_share)
                .setContentTitle(getString(R.string.notification_cast_title))
                .setContentText(getString(R.string.notification_cast_description))
                .setOngoing(true).build();
        startForeground(2, notification);
        manager = (WifiP2pManager) getSystemService(WIFI_P2P_SERVICE);
        if (manager == null) { Log.e("CastingService", "Wi-Fi Direct unavailable"); stopSelf(); return; }
        channel = manager.initialize(this, getMainLooper(), () -> Log.e("CastingService", "P2P channel disconnected"));
        IntentFilter filter = new IntentFilter(WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION);
        if (Build.VERSION.SDK_INT >= 33) registerReceiver(receiver, filter, Context.RECEIVER_EXPORTED);
        else registerReceiver(receiver, filter);
    }

    private void inspectConnection() {
        if (channel == null || !sessionActive || stopping) return;
        try {
            manager.requestConnectionInfo(channel, info -> {
                if (!sessionActive || stopping) return;
                Log.i("CastingService", "groupFormed=" + info.groupFormed + " groupOwner=" + info.isGroupOwner
                        + " ownerAddress=" + info.groupOwnerAddress);
                if (info.groupFormed && server == null) {
                    groupWasFormed = true;
                    int disabledModes = VideoModePreferences.getDisabled(this);
                    boolean forceSinkLevels = VideoModePreferences.forceSinkLevels(this);
                    server = new RTSPServer(captureAudio, disabledModes, forceSinkLevels, selection ->
                            new android.os.Handler(getMainLooper()).post(() -> {
                                if (!sessionActive || stopping) return;
                                int format = selection.experimental ? R.string.stream_format_experimental : R.string.stream_format;
                                status(getString(R.string.casting), getString(format,
                                        deviceName, selection.width, selection.height, selection.fps), true);
                            }), () -> new android.os.Handler(getMainLooper()).post(() -> {
                                if (sessionActive && !stopping)
                                    stopInternal(getString(R.string.connection_failed), getString(R.string.no_compatible_video));
                            }));
                    server.start();
                    status(getString(R.string.connected), getString(R.string.connected_to, deviceName), true);
                } else if (!info.groupFormed && groupWasFormed) {
                    stopInternal(getString(R.string.casting_ended), getString(R.string.display_disconnected));
                }
            });
        } catch (SecurityException e) {
            Log.w("CastingService", "Wi-Fi Direct permission required; retry after granting it", e);
        }
    }

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent == null || intent.getStringExtra("deviceAddress") == null) { stopSelf(); return START_NOT_STICKY; }
        String address = intent.getStringExtra("deviceAddress");
        deviceName = intent.getStringExtra("deviceName");
        captureAudio = intent.getBooleanExtra("captureAudio", false);
        if (deviceName == null) deviceName = getString(R.string.wireless_display);
        sessionActive = true;
        status(getString(R.string.connecting), getString(R.string.connecting_to, deviceName), true);
        if (manager == null || channel == null) {
            stopInternal(getString(R.string.connection_failed), getString(R.string.wifi_direct_unavailable));
            return START_NOT_STICKY;
        }
        android.net.wifi.p2p.WifiP2pConfig config = new android.net.wifi.p2p.WifiP2pConfig();
        config.deviceAddress = address;
        // Use a balanced intent so either peer can become group owner.
        // Group ownership is independent of Mirra's Miracast source role.
        config.groupOwnerIntent = 7;
        config.wps.setup = android.net.wifi.WpsInfo.PBC;
        try {
            manager.connect(channel, config, new WifiP2pManager.ActionListener() {
                @Override public void onSuccess() { Log.i("CastingService", "P2P connection requested: " + address); }
                @Override public void onFailure(int reason) {
                    Log.e("CastingService", "P2P connect failed: " + reason);
                    stopInternal(getString(R.string.connection_failed), getString(R.string.connection_failed_reason, reason));
                }
            });
        } catch (RuntimeException e) {
            Log.e("CastingService", "P2P connect error", e);
            stopInternal(getString(R.string.connection_failed), getString(R.string.connection_permissions));
        }
        return START_NOT_STICKY;
    }

    static boolean isSessionActive() { return sessionActive; }
    static String getCurrentTitle() { return currentTitle; }
    static String getCurrentDetail() { return currentDetail; }

    static void stopSession(Context context) {
        CastingService service = instance;
        if (service != null) service.stopInternal(service.getString(R.string.ready_to_cast), service.getString(R.string.ready_description));
        else context.stopService(new Intent(context, CastingService.class));
    }

    private void status(String title, String detail, boolean active) {
        currentTitle = active ? title : null;
        currentDetail = active ? detail : null;
        sendBroadcast(new Intent(ACTION_STATUS).setPackage(getPackageName())
                .putExtra("title", title).putExtra("detail", detail).putExtra("active", active));
    }

    private void stopInternal(String title, String detail) {
        if (stopping) return;
        stopping = true;
        sessionActive = false;
        if (server != null) { server.stop(); server = null; }
        if (manager != null && channel != null) {
            try {
                manager.cancelConnect(channel, null);
                manager.removeGroup(channel, null);
            } catch (RuntimeException e) { Log.w("CastingService", "Unable to end P2P group", e); }
        }
        stopService(new Intent(this, ProjectionService.class));
        status(title, detail, false);
        stopSelf();
    }

    @Override public void onDestroy() {
        sessionActive = false;
        instance = null;
        if (manager != null) {
            try { unregisterReceiver(receiver); } catch (IllegalArgumentException ignored) { }
        }
        if (server != null) server.stop();
        super.onDestroy();
    }

    @Override public IBinder onBind(Intent intent) { return null; }
}
