package io.github.bluecartooncat.mirra;

import android.Manifest;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.ServiceConnection;
import android.content.pm.PackageManager;
import android.media.projection.MediaProjectionManager;
import android.net.wifi.p2p.WifiP2pDevice;
import android.net.wifi.p2p.WifiP2pManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.Parcel;
import android.util.Log;
import android.view.View;
import android.net.Uri;
import android.widget.PopupMenu;
import android.widget.ScrollView;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.core.view.WindowCompat;

import com.google.android.material.button.MaterialButton;
import com.google.android.material.card.MaterialCardView;
import com.google.android.material.color.DynamicColors;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;

import java.util.ArrayList;
import java.util.List;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;

import rikka.shizuku.Shizuku;

public class MainActivity extends AppCompatActivity {
    private static final String TAG = "MainActivity";
    private static final int WIFI_PERMISSION = 501, SHIZUKU_PERMISSION = 502, CAPTURE_REQUEST = 503,
            EXPORT_LOGS_REQUEST = 504, AUDIO_PERMISSION = 505;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private WifiP2pManager manager;
    private WifiP2pManager.Channel channel;
    private LinearLayout peerRows;
    private TextView statusTitle, statusDetail, emptyState;
    private MaterialButton stopButton;
    private IBinder shellBinder;
    private Shizuku.UserServiceArgs shellArgs;
    private boolean shellBound, resumed, discoveryActive, awaitingAuthorization, awaitingService;
    private String pendingAddress, pendingName;
    private boolean pendingAudio;

    private final Runnable refreshPeers = new Runnable() {
        @Override public void run() {
            if (!resumed || !discoveryActive) return;
            requestPeers();
            handler.postDelayed(this, 5000);
        }
    };
    private final Runnable serviceTimeout = () -> {
        if (awaitingService) {
            awaitingService = false;
            clearPending();
            showMessage(getString(R.string.shizuku_connect_failed), getString(R.string.shizuku_check_running));
        }
    };
    private final Shizuku.OnBinderReceivedListener binderReceived = this::bindIfAuthorized;
    private final Shizuku.OnBinderDeadListener binderDead = () -> runOnUiThread(() -> {
        shellBinder = null;
        shellBound = false;
        if (awaitingAuthorization || awaitingService) {
            awaitingAuthorization = awaitingService = false;
            clearPending();
            showMessage(getString(R.string.shizuku_start_title), getString(R.string.shizuku_start_detail));
        }
    });
    private final Shizuku.OnRequestPermissionResultListener permissionResult = (requestCode, result) -> {
        if (requestCode != SHIZUKU_PERMISSION || !awaitingAuthorization) return;
        awaitingAuthorization = false;
        if (result == PackageManager.PERMISSION_GRANTED) continueAfterAuthorization();
        else {
            clearPending();
            showMessage(getString(R.string.shizuku_access_title), getString(R.string.shizuku_access_detail));
        }
    };
    private final ServiceConnection shellConnection = new ServiceConnection() {
        @Override public void onServiceConnected(ComponentName name, IBinder binder) {
            shellBinder = binder;
            if (awaitingService) {
                awaitingService = false;
                handler.removeCallbacks(serviceTimeout);
                enableWfdThenCapture();
            } else if (discoveryActive) sendWfd(false);
        }
        @Override public void onServiceDisconnected(ComponentName name) {
            shellBinder = null;
            shellBound = false;
        }
    };
    private final BroadcastReceiver receiver = new BroadcastReceiver() {
        @Override public void onReceive(Context context, Intent intent) {
            String action = intent.getAction();
            if (WifiP2pManager.WIFI_P2P_PEERS_CHANGED_ACTION.equals(action)) requestPeers();
            else if (WifiP2pManager.WIFI_P2P_DISCOVERY_CHANGED_ACTION.equals(action)) {
                int state = intent.getIntExtra(WifiP2pManager.EXTRA_DISCOVERY_STATE, -1);
                if (state == WifiP2pManager.WIFI_P2P_DISCOVERY_STOPPED && discoveryActive) {
                    discoveryActive = false;
                    handler.removeCallbacks(refreshPeers);
                    requestPeers();
                }
            }
        }
    };
    private final BroadcastReceiver statusReceiver = new BroadcastReceiver() {
        @Override public void onReceive(Context context, Intent intent) {
            updateStatus(intent.getStringExtra("title"), intent.getStringExtra("detail"),
                    intent.getBooleanExtra("active", false));
        }
    };

    @Override protected void onCreate(Bundle savedInstanceState) {
        DynamicColors.applyToActivityIfAvailable(this);
        super.onCreate(savedInstanceState);
        WindowCompat.setDecorFitsSystemWindows(getWindow(), false);
        setContentView(R.layout.activity_main);
        applySystemBarInsets();
        peerRows = findViewById(R.id.peerRows);
        statusTitle = findViewById(R.id.statusTitle);
        statusDetail = findViewById(R.id.statusDetail);
        emptyState = findViewById(R.id.emptyState);
        stopButton = findViewById(R.id.btnStopCasting);
        findViewById(R.id.overflowMenu).setOnClickListener(this::showMenu);
        findViewById(R.id.btnDiscover).setOnClickListener(v -> discover());
        findViewById(R.id.readyCard).setOnClickListener(v -> {
            if (!CastingService.isSessionActive()) discover();
        });
        stopButton.setOnClickListener(v -> {
            clearPending();
            CastingService.stopSession(this);
            stopService(new Intent(this, ProjectionService.class));
            updateStatus(getString(R.string.ready_to_cast), getString(R.string.ready_description), false);
        });
        manager = (WifiP2pManager) getSystemService(WIFI_P2P_SERVICE);
        if (manager != null) channel = manager.initialize(this, getMainLooper(), () -> Log.w(TAG, "P2P channel disconnected"));
        if (savedInstanceState != null) {
            pendingAddress = savedInstanceState.getString("pendingAddress");
            pendingName = savedInstanceState.getString("pendingName");
            pendingAudio = savedInstanceState.getBoolean("pendingAudio");
        }
        Shizuku.addBinderReceivedListenerSticky(binderReceived);
        Shizuku.addBinderDeadListener(binderDead);
        Shizuku.addRequestPermissionResultListener(permissionResult);
        bindIfAuthorized();
    }

    /** Keep content clear of status bars, cutouts and the navigation area. */
    private void applySystemBarInsets() {
        View content = findViewById(R.id.mainContent);
        int left = content.getPaddingLeft();
        int top = content.getPaddingTop();
        int right = content.getPaddingRight();
        int bottom = content.getPaddingBottom();
        ViewCompat.setOnApplyWindowInsetsListener(content, (view, windowInsets) -> {
            Insets insets = windowInsets.getInsets(WindowInsetsCompat.Type.systemBars()
                    | WindowInsetsCompat.Type.displayCutout());
            view.setPadding(left + insets.left, top + insets.top,
                    right + insets.right, bottom + insets.bottom);
            return windowInsets;
        });
        ViewCompat.requestApplyInsets(content);
    }

    @Override protected void onSaveInstanceState(Bundle out) {
        out.putString("pendingAddress", pendingAddress);
        out.putString("pendingName", pendingName);
        out.putBoolean("pendingAudio", pendingAudio);
        super.onSaveInstanceState(out);
    }

    @Override protected void onResume() {
        super.onResume();
        resumed = true;
        IntentFilter filter = new IntentFilter();
        filter.addAction(WifiP2pManager.WIFI_P2P_PEERS_CHANGED_ACTION);
        filter.addAction(WifiP2pManager.WIFI_P2P_DISCOVERY_CHANGED_ACTION);
        ContextCompat.registerReceiver(this, receiver, filter, ContextCompat.RECEIVER_EXPORTED);
        ContextCompat.registerReceiver(this, statusReceiver, new IntentFilter(CastingService.ACTION_STATUS), ContextCompat.RECEIVER_NOT_EXPORTED);
        if (discoveryActive) { handler.removeCallbacks(refreshPeers); handler.post(refreshPeers); }
        if (CastingService.isSessionActive()) updateStatus(getString(R.string.casting), getString(R.string.screen_being_shared), true);
    }

    @Override protected void onPause() {
        resumed = false;
        handler.removeCallbacks(refreshPeers);
        unregisterReceiver(receiver);
        unregisterReceiver(statusReceiver);
        super.onPause();
    }

    @Override protected void onDestroy() {
        handler.removeCallbacksAndMessages(null);
        Shizuku.removeBinderReceivedListener(binderReceived);
        Shizuku.removeBinderDeadListener(binderDead);
        Shizuku.removeRequestPermissionResultListener(permissionResult);
        if (shellBound && shellArgs != null) {
            try { Shizuku.unbindUserService(shellArgs, shellConnection, true); }
            catch (Exception e) { Log.w(TAG, "Unable to unbind Shizuku service", e); }
        }
        super.onDestroy();
    }

    private void discover() {
        if (manager == null || channel == null) { showMessage(getString(R.string.wifi_unavailable), getString(R.string.wifi_unavailable_detail)); return; }
        List<String> missing = new ArrayList<>();
        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED)
            missing.add(Manifest.permission.ACCESS_FINE_LOCATION);
        if (Build.VERSION.SDK_INT >= 33 && ActivityCompat.checkSelfPermission(this, Manifest.permission.NEARBY_WIFI_DEVICES) != PackageManager.PERMISSION_GRANTED)
            missing.add(Manifest.permission.NEARBY_WIFI_DEVICES);
        if (!missing.isEmpty()) { ActivityCompat.requestPermissions(this, missing.toArray(new String[0]), WIFI_PERMISSION); return; }
        try {
            manager.discoverPeers(channel, new WifiP2pManager.ActionListener() {
                @Override public void onSuccess() {
                    discoveryActive = true;
                    emptyState.setText(R.string.looking_for_displays);
                    handler.removeCallbacks(refreshPeers);
                    if (resumed) handler.post(refreshPeers);
                    if (shellBinder != null) sendWfd(false);
                }
                @Override public void onFailure(int reason) {
                    discoveryActive = false;
                    showMessage(getString(R.string.find_failed), getString(R.string.discovery_failed_detail, reason));
                }
            });
        } catch (SecurityException e) { showMessage(getString(R.string.permission_needed), getString(R.string.wifi_permission_detail)); }
    }

    @Override public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] results) {
        super.onRequestPermissionsResult(requestCode, permissions, results);
        if (requestCode == WIFI_PERMISSION) {
            for (int result : results) if (result != PackageManager.PERMISSION_GRANTED) {
                showMessage(getString(R.string.permission_needed), getString(R.string.wifi_permission_detail)); return;
            }
            discover();
        } else if (requestCode == AUDIO_PERMISSION) {
            if (pendingAddress == null) return;
            if (results.length > 0 && results[0] == PackageManager.PERMISSION_GRANTED) {
                requestScreenCapture();
            } else {
                new MaterialAlertDialogBuilder(this)
                        .setTitle(R.string.audio_permission_title)
                        .setMessage(R.string.audio_denied_detail)
                        .setNegativeButton(R.string.cancel, (dialog, which) -> clearPending())
                        .setPositiveButton(R.string.skip_audio, (dialog, which) -> {
                            pendingAudio = false;
                            requestScreenCapture();
                        })
                        .setOnCancelListener(dialog -> clearPending())
                        .show();
            }
        }
    }

    private void requestPeers() {
        if (manager == null || channel == null) return;
        try {
            manager.requestPeers(channel, list -> {
                peerRows.removeAllViews();
                emptyState.setVisibility(list.getDeviceList().isEmpty() ? View.VISIBLE : View.GONE);
                for (WifiP2pDevice device : list.getDeviceList()) addPeer(device);
                Log.i(TAG, "Peer list contains " + list.getDeviceList().size() + " device(s)");
            });
        } catch (SecurityException e) { Log.w(TAG, "Peer query denied", e); }
    }

    private void addPeer(WifiP2pDevice device) {
        String name = device.deviceName == null || device.deviceName.isEmpty() ? getString(R.string.wireless_display) : device.deviceName;
        String address = device.deviceAddress;
        MaterialCardView card = new MaterialCardView(this);
        card.setRadius(dp(24));
        card.setStrokeWidth(0);
        android.util.TypedValue color = new android.util.TypedValue();
        getTheme().resolveAttribute(com.google.android.material.R.attr.colorSurfaceContainerHigh, color, true);
        card.setCardBackgroundColor(color.data);
        LinearLayout.LayoutParams cardParams = new LinearLayout.LayoutParams(-1, -2);
        cardParams.bottomMargin = dp(10);
        card.setLayoutParams(cardParams);
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.VERTICAL);
        row.setPadding(dp(20), dp(17), dp(20), dp(17));
        TextView title = new TextView(this);
        title.setText(name);
        title.setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_TitleMedium);
        TextView subtitle = new TextView(this);
        subtitle.setText(R.string.peer_subtitle);
        subtitle.setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_BodyMedium);
        row.addView(title); row.addView(subtitle); card.addView(row);
        card.setOnClickListener(v -> new MaterialAlertDialogBuilder(this)
                .setTitle(getString(R.string.confirm_cast_title, name))
                .setMessage(R.string.confirm_cast_message)
                .setNegativeButton(R.string.cancel, null)
                .setPositiveButton(R.string.continue_action, (dialog, which) -> beginCasting(address, name)).show());
        peerRows.addView(card);
    }

    private int dp(int value) { return (int) (value * getResources().getDisplayMetrics().density + 0.5f); }

    private void beginCasting(String address, String name) {
        if (address == null || address.isEmpty()) { showMessage(getString(R.string.display_unavailable), getString(R.string.display_unavailable_detail)); return; }
        if (CastingService.isSessionActive()) { showMessage(getString(R.string.already_casting), getString(R.string.already_casting_detail)); return; }
        pendingAddress = address; pendingName = name;
        if (!Shizuku.pingBinder()) {
            clearPending();
            showMessage(getString(R.string.shizuku_start_title), getString(R.string.shizuku_start_detail));
        } else if (Shizuku.checkSelfPermission() != PackageManager.PERMISSION_GRANTED) {
            if (Shizuku.shouldShowRequestPermissionRationale()) {
                clearPending();
                showMessage(getString(R.string.shizuku_access_title), getString(R.string.shizuku_settings_detail));
            } else {
                awaitingAuthorization = true;
                Shizuku.requestPermission(SHIZUKU_PERMISSION);
            }
        } else continueAfterAuthorization();
    }

    private void showMenu(View anchor) {
        PopupMenu menu = new PopupMenu(this, anchor);
        menu.inflate(R.menu.main_menu);
        menu.setOnMenuItemClickListener(item -> {
            if (item.getItemId() == R.id.menu_logs) {
                showLogs();
                return true;
            }
            if (item.getItemId() == R.id.menu_about) {
                new MaterialAlertDialogBuilder(this)
                        .setTitle(R.string.menu_about)
                        .setMessage(R.string.about_message)
                        .setPositiveButton(R.string.close, null).show();
                return true;
            }
            return false;
        });
        menu.show();
    }

    private void showLogs() {
        new Thread(() -> {
            String logs = readAppLogs();
            runOnUiThread(() -> {
                if (isFinishing() || isDestroyed()) return;
                TextView text = new TextView(this);
                text.setText(logs);
                text.setTextIsSelectable(true);
                text.setTextSize(11);
                text.setTypeface(android.graphics.Typeface.MONOSPACE);
                text.setPadding(dp(20), dp(8), dp(20), dp(8));
                ScrollView scroll = new ScrollView(this);
                scroll.addView(text);
                new MaterialAlertDialogBuilder(this)
                        .setTitle(R.string.diagnostic_logs)
                        .setMessage(R.string.logs_privacy_warning)
                        .setView(scroll)
                        .setNegativeButton(R.string.close, null)
                        .setPositiveButton(R.string.export, (dialog, which) -> {
                            Intent save = new Intent(Intent.ACTION_CREATE_DOCUMENT)
                                    .addCategory(Intent.CATEGORY_OPENABLE)
                                    .setType("text/plain")
                                    .putExtra(Intent.EXTRA_TITLE, getString(R.string.logs_filename));
                            startActivityForResult(save, EXPORT_LOGS_REQUEST);
                        }).show();
            });
        }, "LoadLogs").start();
    }

    // Android restricts logcat to this app's UID; cap the output so the viewer
    // and exported document remain usable during a long casting session.
    private String readAppLogs() {
        ArrayDeque<String> lines = new ArrayDeque<>();
        int size = 0;
        Process process = null;
        try {
            process = new ProcessBuilder("logcat", "-d", "-v", "time", "--pid="
                    + android.os.Process.myPid(), "*:V").redirectErrorStream(true).start();
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    if (line.length() > 8192) line = line.substring(0, 8192) + "…";
                    lines.addLast(line);
                    size += line.length() + 1;
                    while (size > 250000 && !lines.isEmpty()) size -= lines.removeFirst().length() + 1;
                }
            }
            if (process.waitFor() != 0) return getString(R.string.logs_read_failed, String.join("\n", lines));
            return lines.isEmpty() ? getString(R.string.logs_empty) : String.join("\n", lines);
        } catch (Exception e) {
            return getString(R.string.logs_read_error, e.getMessage());
        } finally {
            if (process != null) process.destroy();
        }
    }

    private void exportLogs(Uri destination) {
        new Thread(() -> {
            try (OutputStream out = getContentResolver().openOutputStream(destination)) {
                if (out == null) throw new java.io.IOException("Document could not be opened");
                out.write(readAppLogs().getBytes(StandardCharsets.UTF_8));
                runOnUiThread(() -> android.widget.Toast.makeText(this, R.string.logs_exported, android.widget.Toast.LENGTH_SHORT).show());
            } catch (Exception e) {
                Log.e(TAG, "Log export failed", e);
                runOnUiThread(() -> showMessage(getString(R.string.logs_export_failed), getString(R.string.logs_export_failed_detail)));
            }
        }, "ExportLogs").start();
    }

    private void continueAfterAuthorization() {
        if (pendingAddress == null) return;
        if (shellBinder != null) enableWfdThenCapture();
        else {
            awaitingService = true;
            handler.removeCallbacks(serviceTimeout);
            handler.postDelayed(serviceTimeout, 10000);
            bindIfAuthorized();
        }
    }

    private void bindIfAuthorized() {
        if (shellBound || !Shizuku.pingBinder() || Shizuku.checkSelfPermission() != PackageManager.PERMISSION_GRANTED) return;
        try {
            shellArgs = new Shizuku.UserServiceArgs(new ComponentName(this, WfdShellService.class))
                    .daemon(false).processNameSuffix("wfdtest").tag("mirra-wfd").version(1);
            shellBound = true;
            Shizuku.bindUserService(shellArgs, shellConnection);
        } catch (Exception e) {
            shellBound = false;
            Log.e(TAG, "Shizuku bind failed", e);
            if (awaitingService) { awaitingService = false; clearPending(); showMessage(getString(R.string.shizuku_connect_failed), getString(R.string.shizuku_start_retry)); }
        }
    }

    private void enableWfdThenCapture() { sendWfd(true); }

    private void sendWfd(boolean continueToCapture) {
        IBinder binder = shellBinder;
        if (binder == null) {
            if (continueToCapture) { clearPending(); showMessage(getString(R.string.shizuku_start_title), getString(R.string.shizuku_start_detail)); }
            return;
        }
        new Thread(() -> {
            Parcel data = Parcel.obtain(), reply = Parcel.obtain();
            String result;
            try {
                if (!binder.transact(WfdShellService.ENABLE, data, reply, 0)) throw new IllegalStateException("Unsupported WFD command");
                reply.readException();
                result = reply.readString();
            } catch (Exception e) { result = "error: " + e.getMessage(); }
            finally { data.recycle(); reply.recycle(); }
            String message = result;
            Log.i(TAG, "WFD: " + message);
            if (continueToCapture) runOnUiThread(() -> {
                if (pendingAddress == null || isFinishing() || isDestroyed()) return;
                if (message == null || !message.contains(": accepted")) {
                    clearPending();
                    showMessage(getString(R.string.prepare_failed), getString(R.string.prepare_failed_detail, message));
                    return;
                }
                askAboutAudio();
            });
        }, "WfdCommand").start();
    }

    private void askAboutAudio() {
        new MaterialAlertDialogBuilder(this)
                .setTitle(R.string.share_audio_title)
                .setMessage(R.string.share_audio_detail)
                .setNeutralButton(R.string.cancel, (dialog, which) -> clearPending())
                .setNegativeButton(R.string.skip_audio, (dialog, which) -> {
                    pendingAudio = false;
                    requestScreenCapture();
                })
                .setPositiveButton(R.string.share_audio, (dialog, which) -> {
                    pendingAudio = true;
                    if (ActivityCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
                            != PackageManager.PERMISSION_GRANTED) {
                        ActivityCompat.requestPermissions(this, new String[]{Manifest.permission.RECORD_AUDIO}, AUDIO_PERMISSION);
                    } else {
                        requestScreenCapture();
                    }
                })
                .setOnCancelListener(dialog -> clearPending())
                .show();
    }

    private void requestScreenCapture() {
        MediaProjectionManager projection = (MediaProjectionManager) getSystemService(MEDIA_PROJECTION_SERVICE);
        startActivityForResult(projection.createScreenCaptureIntent(), CAPTURE_REQUEST);
    }

    @Override protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == EXPORT_LOGS_REQUEST) {
            if (resultCode == RESULT_OK && data != null && data.getData() != null) exportLogs(data.getData());
            return;
        }
        if (requestCode != CAPTURE_REQUEST) return;
        if (resultCode != RESULT_OK || data == null || pendingAddress == null) {
            clearPending();
            updateStatus(getString(R.string.ready_to_cast), getString(R.string.capture_cancelled), false);
            return;
        }
        Intent service = new Intent(this, ProjectionService.class);
        service.putExtra("resultCode", resultCode);
        service.putExtra("resultData", data);
        service.putExtra("deviceAddress", pendingAddress);
        service.putExtra("deviceName", pendingName);
        service.putExtra("captureAudio", pendingAudio);
        updateStatus(getString(R.string.connecting), getString(R.string.connecting_to, pendingName), true);
        clearPending();
        try { ContextCompat.startForegroundService(this, service); }
        catch (RuntimeException e) { updateStatus(getString(R.string.casting_start_failed), e.getMessage(), false); }
    }

    private void clearPending() { pendingAddress = null; pendingName = null; pendingAudio = false; }
    private void updateStatus(String title, String detail, boolean active) {
        if (title != null) statusTitle.setText(title);
        if (detail != null) statusDetail.setText(detail);
        stopButton.setVisibility(active ? View.VISIBLE : View.GONE);
    }
    private void showMessage(String title, String message) {
        if (!isFinishing() && !isDestroyed()) new MaterialAlertDialogBuilder(this).setTitle(title).setMessage(message).setPositiveButton(R.string.ok, null).show();
    }
}
