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
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;

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
    private MaterialButton controllerButton;
    private MaterialButton wifiDirectSettingsButton;
    private IBinder shellBinder;
    private Shizuku.UserServiceArgs shellArgs;
    private boolean shellBound, resumed, discoveryActive, awaitingAuthorization, awaitingService;
    private String pendingAddress, pendingName;
    private String ownP2pAddress;
    private String ownP2pName;
    private String lastPeerSnapshot;
    private boolean pendingAudio;
    private boolean pendingExternalDisplay;
    private int pendingGroupOwnerIntent = 7;

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
            else if (WifiP2pManager.WIFI_P2P_THIS_DEVICE_CHANGED_ACTION.equals(action)) {
                WifiP2pDevice device = intent.getParcelableExtra(WifiP2pManager.EXTRA_WIFI_P2P_DEVICE);
                updateOwnP2pDevice(device);
                requestPeers();
            }
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
        controllerButton = findViewById(R.id.btnController);
        wifiDirectSettingsButton = findViewById(R.id.btnWifiDirectSettings);
        wifiDirectSettingsButton.setOnClickListener(v -> openWifiDirectSettings());
        controllerButton.setOnClickListener(v -> startActivity(new Intent(this, ControllerActivity.class)));
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
            pendingExternalDisplay = savedInstanceState.getBoolean("pendingExternalDisplay");
            pendingGroupOwnerIntent = savedInstanceState.getInt("pendingGroupOwnerIntent", 7);
        }
        Shizuku.addBinderReceivedListenerSticky(binderReceived);
        Shizuku.addBinderDeadListener(binderDead);
        Shizuku.addRequestPermissionResultListener(permissionResult);
        bindIfAuthorized();
        if (!getSharedPreferences("onboarding", MODE_PRIVATE).getBoolean("complete", false)
                && !getPreferences(MODE_PRIVATE).getBoolean("onboarding_complete", false)) {
            findViewById(R.id.mainContent).post(() -> {
                if (!isFinishing() && !isDestroyed()) showOnboarding();
            });
        }
    }

    private void showOnboarding() {
        startActivity(new Intent(this, OnboardingActivity.class));
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
        out.putBoolean("pendingExternalDisplay", pendingExternalDisplay);
        out.putInt("pendingGroupOwnerIntent", pendingGroupOwnerIntent);
        super.onSaveInstanceState(out);
    }

    @Override protected void onResume() {
        super.onResume();
        resumed = true;
        IntentFilter filter = new IntentFilter();
        filter.addAction(WifiP2pManager.WIFI_P2P_PEERS_CHANGED_ACTION);
        filter.addAction(WifiP2pManager.WIFI_P2P_THIS_DEVICE_CHANGED_ACTION);
        filter.addAction(WifiP2pManager.WIFI_P2P_DISCOVERY_CHANGED_ACTION);
        ContextCompat.registerReceiver(this, receiver, filter, ContextCompat.RECEIVER_EXPORTED);
        ContextCompat.registerReceiver(this, statusReceiver, new IntentFilter(CastingService.ACTION_STATUS), ContextCompat.RECEIVER_NOT_EXPORTED);
        if (discoveryActive) { handler.removeCallbacks(refreshPeers); handler.post(refreshPeers); }
        if (CastingService.isSessionActive() || CastingService.getCurrentTitle() != null) {
            String title = CastingService.getCurrentTitle();
            String detail = CastingService.getCurrentDetail();
            updateStatus(title != null ? title : getString(R.string.casting),
                    detail != null ? detail : getString(R.string.screen_being_shared), CastingService.isSessionActive());
        }
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
                    Log.i(TAG, "Wi-Fi Direct discovery started");
                    discoveryActive = true;
                    emptyState.setText(R.string.looking_for_displays);
                    handler.removeCallbacks(refreshPeers);
                    if (resumed) handler.post(refreshPeers);
                    if (shellBinder != null) sendWfd(false);
                }
                @Override public void onFailure(int reason) {
                    Log.w(TAG, "Wi-Fi Direct discovery failed: " + reason);
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
                            continueWithoutAudio();
                        })
                        .setOnCancelListener(dialog -> clearPending())
                        .show();
            }
        }
    }

    private void requestPeers() {
        if (manager == null || channel == null) return;
        try {
            if (Build.VERSION.SDK_INT >= 29) {
                manager.requestDeviceInfo(channel, device -> {
                    updateOwnP2pDevice(device);
                    loadPeers();
                });
            } else loadPeers();
        } catch (SecurityException e) { Log.w(TAG, "Local device query denied", e); loadPeers(); }
    }

    private void updateOwnP2pDevice(WifiP2pDevice device) {
        if (device != null && device.deviceName != null && !device.deviceName.trim().isEmpty())
            ownP2pName = device.deviceName.trim();
        if (device != null && device.deviceAddress != null && !"02:00:00:00:00:00".equalsIgnoreCase(device.deviceAddress))
            ownP2pAddress = device.deviceAddress;
    }

    private void loadPeers() {
        if (manager == null || channel == null) return;
        try {
            manager.requestPeers(channel, list -> {
                peerRows.removeAllViews();
                int count = 0;
                ArrayList<String> discovered = new ArrayList<>();
                String localName = ownP2pName;
                if (localName == null || localName.isEmpty())
                    localName = android.provider.Settings.Global.getString(getContentResolver(), android.provider.Settings.Global.DEVICE_NAME);
                for (WifiP2pDevice device : list.getDeviceList()) {
                    if (ownP2pAddress != null && ownP2pAddress.equalsIgnoreCase(device.deviceAddress)) continue;
                    if (localName != null && device.deviceName != null
                            && !localName.trim().isEmpty() && localName.trim().equalsIgnoreCase(device.deviceName.trim())) continue;
                    addPeer(device);
                    discovered.add(device.deviceName + " [" + device.deviceAddress + "] status=" + device.status);
                    count++;
                }
                emptyState.setVisibility(count == 0 ? View.VISIBLE : View.GONE);
                java.util.Collections.sort(discovered);
                String snapshot = discovered.toString();
                if (!snapshot.equals(lastPeerSnapshot)) {
                    lastPeerSnapshot = snapshot;
                    Log.i(TAG, "Nearby displays: " + snapshot);
                }
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
                .setPositiveButton(R.string.continue_action, (dialog, which) -> chooseConnectionStyle(address, name, false)).show());
        card.setOnLongClickListener(v -> {
            chooseConnectionStyle(address, name, true);
            return true;
        });
        peerRows.addView(card);
    }

    private int dp(int value) { return (int) (value * getResources().getDisplayMetrics().density + 0.5f); }

    private void chooseConnectionStyle(String address, String name, boolean reset) {
        if (address == null || address.isEmpty()) { showMessage(getString(R.string.display_unavailable), getString(R.string.display_unavailable_detail)); return; }
        if (CastingService.isSessionActive()) { showMessage(getString(R.string.already_casting), getString(R.string.already_casting_detail)); return; }
        android.content.SharedPreferences preferences = getSharedPreferences("connection_styles", MODE_PRIVATE);
        String key = address.toLowerCase(java.util.Locale.ROOT);
        if (reset) preferences.edit().remove(key).apply();
        int saved = preferences.getInt(key, -1);
        if (!reset && (saved == 7 || saved == 15)) {
            pendingGroupOwnerIntent = saved;
            beginCasting(address, name);
            return;
        }
        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(dp(24), 0, dp(24), 0);
        TextView description = new TextView(this);
        description.setText(R.string.connection_style_description);
        content.addView(description);
        android.widget.RadioGroup group = new android.widget.RadioGroup(this);
        android.widget.RadioButton shared = new android.widget.RadioButton(this);
        shared.setId(android.view.View.generateViewId());
        shared.setText(R.string.connection_style_shared);
        android.widget.RadioButton host = new android.widget.RadioButton(this);
        host.setId(android.view.View.generateViewId());
        host.setText(R.string.connection_style_host);
        group.addView(shared); group.addView(host); group.check(shared.getId());
        content.addView(group);
        android.widget.CheckBox remember = new android.widget.CheckBox(this);
        remember.setText(R.string.connection_style_remember);
        content.addView(remember);
        new MaterialAlertDialogBuilder(this).setTitle(R.string.connection_style_title)
                .setView(content).setNegativeButton(R.string.cancel, null)
                .setPositiveButton(R.string.continue_action, (dialog, which) -> {
                    pendingGroupOwnerIntent = group.getCheckedRadioButtonId() == host.getId() ? 15 : 7;
                    if (remember.isChecked()) preferences.edit().putInt(key, pendingGroupOwnerIntent).apply();
                    beginCasting(address, name);
                }).show();
    }

    private void beginCasting(String address, String name) {
        if (address == null || address.isEmpty()) { showMessage(getString(R.string.display_unavailable), getString(R.string.display_unavailable_detail)); return; }
        if (CastingService.isSessionActive()) { showMessage(getString(R.string.already_casting), getString(R.string.already_casting_detail)); return; }
        pendingAddress = address; pendingName = name;
        Log.i(TAG, "Preparing receiver " + name + " [" + address + "] group-owner intent=" + pendingGroupOwnerIntent);
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
            if (item.getItemId() == R.id.menu_guide) {
                showOnboarding();
                return true;
            }
            if (item.getItemId() == R.id.menu_logs) {
                showLogs();
                return true;
            }
            if (item.getItemId() == R.id.menu_video_modes) {
                showVideoModes();
                return true;
            }
            if (item.getItemId() == R.id.menu_scrcpy_settings) {
                showScrcpySettings();
                return true;
            }
            if (item.getItemId() == R.id.menu_reset_wifi_direct) {
                showResetWifiDirect();
                return true;
            }
            if (item.getItemId() == R.id.menu_about) {
                showAbout();
                return true;
            }
            return false;
        });
        menu.show();
    }

    private void showAbout() {
        String source = getString(R.string.about_source_display);
        String sourceLine = getString(R.string.about_source_line, source);
        android.text.SpannableStringBuilder message = new android.text.SpannableStringBuilder()
                .append(getString(R.string.about_message)).append("\n\n");
        int lineStart = message.length();
        message.append(sourceLine);
        int linkStart = sourceLine.indexOf(source);
        if (linkStart >= 0) {
            message.setSpan(new android.text.style.URLSpan(getString(R.string.about_source_url)),
                    lineStart + linkStart, lineStart + linkStart + source.length(),
                    android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        }
        String scrcpySource = getString(R.string.about_scrcpy_url);
        message.append("\n\n").append(getString(R.string.about_scrcpy)).append("\n");
        int scrcpyStart = message.length();
        message.append(scrcpySource);
        message.setSpan(new android.text.style.URLSpan(scrcpySource), scrcpyStart, message.length(),
                android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        message.append("\n\n").append(getString(R.string.about_copyright))
                .append("\n").append(getString(R.string.about_license));
        androidx.appcompat.app.AlertDialog dialog = new MaterialAlertDialogBuilder(this)
                .setTitle(R.string.menu_about)
                .setMessage(message)
                .setPositiveButton(R.string.close, null)
                .show();
        TextView text = dialog.findViewById(android.R.id.message);
        if (text != null) text.setMovementMethod(android.text.method.LinkMovementMethod.getInstance());
    }

    private void showVideoModes() {
        int[][] modes = VideoNegotiator.supportedModes();
        int disabled = VideoModePreferences.getDisabled(this);
        String[] labels = new String[modes.length + 1];
        boolean[] enabled = new boolean[modes.length + 1];
        for (int i = 0; i < modes.length; i++) {
            int label = modes[i][4] == 0 ? R.string.video_mode_experimental_label : R.string.video_mode_label;
            labels[i] = getString(label, modes[i][0], modes[i][1], modes[i][2]);
            enabled[i] = (disabled & (1 << modes[i][3])) == 0;
        }
        labels[modes.length] = getString(R.string.video_mode_force_levels);
        enabled[modes.length] = VideoModePreferences.forceSinkLevels(this);
        androidx.appcompat.app.AlertDialog dialog = new MaterialAlertDialogBuilder(this)
                .setTitle(getString(R.string.video_modes_title) + "\n" + getString(R.string.video_modes_description))
                .setMultiChoiceItems(labels, enabled, (ignored, index, checked) -> enabled[index] = checked)
                .setNegativeButton(R.string.cancel, null)
                .setPositiveButton(R.string.save, null)
                .create();
        dialog.setOnShowListener(ignored -> dialog.getButton(android.content.DialogInterface.BUTTON_POSITIVE)
                .setOnClickListener(v -> {
                    int newDisabled = 0;
                    boolean anyEnabled = false;
                    boolean standardEnabled = false;
                    for (int i = 0; i < modes.length; i++) {
                        if (enabled[i]) {
                            anyEnabled = true;
                            if (modes[i][4] != 0) standardEnabled = true;
                        }
                        else newDisabled |= 1 << modes[i][3];
                    }
                    if (!anyEnabled) {
                        android.widget.Toast.makeText(this, R.string.video_mode_required, android.widget.Toast.LENGTH_SHORT).show();
                        return;
                    }
                    if (!standardEnabled && !enabled[modes.length]) {
                        android.widget.Toast.makeText(this, R.string.video_mode_override_required,
                                android.widget.Toast.LENGTH_SHORT).show();
                        return;
                    }
                    VideoModePreferences.save(this, newDisabled, enabled[modes.length]);
                    dialog.dismiss();
                }));
        dialog.show();
    }

    private void showResetWifiDirect() {
        new MaterialAlertDialogBuilder(this)
                .setTitle(R.string.reset_wifi_direct)
                .setMessage(R.string.reset_wifi_direct_detail)
                .setNegativeButton(R.string.cancel, null)
                .setPositiveButton(R.string.open_settings, (dialog, which) -> {
                    clearPending();
                    discoveryActive = false;
                    handler.removeCallbacks(refreshPeers);
                    if (manager != null && channel != null) {
                        try { manager.stopPeerDiscovery(channel, null); }
                        catch (RuntimeException e) { Log.w(TAG, "Could not stop discovery", e); }
                    }
                    if (CastingService.isSessionActive()) CastingService.stopSession(this);
                    stopService(new Intent(this, ProjectionService.class));
                    peerRows.removeAllViews();
                    emptyState.setVisibility(View.VISIBLE);
                    emptyState.setText(R.string.discovery_hint);
                    updateStatus(getString(R.string.ready_to_cast), getString(R.string.ready_description), false);
                    openWifiDirectSettings();
                }).show();
    }

    private void openWifiDirectSettings() {
        // There is no public API for deleting persistent P2P groups. Prefer
        // the system Wi-Fi Direct screen when available, then Wi-Fi settings.
        Intent direct = new Intent(Intent.ACTION_MAIN).setClassName("com.android.settings",
                "com.android.settings.Settings$WifiP2pSettingsActivity");
        try {
            startActivity(direct);
            return;
        } catch (android.content.ActivityNotFoundException | SecurityException e) {
            Log.i(TAG, "Direct Wi-Fi Direct settings unavailable; opening Wi-Fi settings");
        }
        try {
            startActivity(new Intent(android.provider.Settings.ACTION_WIFI_SETTINGS));
            android.widget.Toast.makeText(this, R.string.wifi_direct_settings_hint,
                    android.widget.Toast.LENGTH_LONG).show();
        } catch (android.content.ActivityNotFoundException | SecurityException e) {
            showMessage(getString(R.string.reset_wifi_direct), getString(R.string.wifi_settings_unavailable));
        }
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
                LinearLayout header = new LinearLayout(this);
                header.setGravity(android.view.Gravity.CENTER_VERTICAL);
                header.setPadding(dp(24), dp(16), dp(16), 0);
                TextView heading = new TextView(this);
                heading.setText(R.string.diagnostic_logs);
                heading.setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_HeadlineSmall);
                header.addView(heading, new LinearLayout.LayoutParams(0, -2, 1));
                MaterialButton export = new MaterialButton(this);
                export.setText(R.string.export);
                export.setAllCaps(false);
                export.setOnClickListener(v -> chooseLogDestination());
                header.addView(export, new LinearLayout.LayoutParams(-2, -2));
                new MaterialAlertDialogBuilder(this)
                        .setCustomTitle(header)
                        .setMessage(R.string.logs_privacy_warning)
                        .setView(scroll)
                        .setNegativeButton(R.string.close, null)
                        .show();
            });
        }, "LoadLogs").start();
    }

    private void chooseLogDestination() {
        Intent save = new Intent(Intent.ACTION_CREATE_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE)
                .setType("text/plain").putExtra(Intent.EXTRA_TITLE, getString(R.string.logs_filename));
        startActivityForResult(save, EXPORT_LOGS_REQUEST);
    }

    private String readAppLogs() {
        try {
            byte[] bytes = Log.snapshot();
            return bytes.length == 0 ? getString(R.string.logs_empty) : new String(bytes, StandardCharsets.UTF_8);
        } catch (Exception e) {
            return getString(R.string.logs_read_error, e.getMessage());
        }
    }

    private void exportLogs(Uri destination) {
        new Thread(() -> {
            try (OutputStream out = getContentResolver().openOutputStream(destination)) {
                if (out == null) throw new java.io.IOException("Document could not be opened");
                out.write(Log.snapshot());
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
        String targetAddress = pendingAddress;
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
                if (pendingAddress == null || !pendingAddress.equals(targetAddress) || isFinishing() || isDestroyed()) return;
                if (message == null || !message.contains(": accepted")) {
                    clearPending();
                    showMessage(getString(R.string.prepare_failed), getString(R.string.prepare_failed_detail, message));
                    return;
                }
                askCastMode();
            });
        }, "WfdCommand").start();
    }

    private void askCastMode() {
        LinearLayout choices = new LinearLayout(this);
        choices.setOrientation(LinearLayout.VERTICAL);
        choices.setPadding(dp(20), dp(8), dp(20), dp(8));
        androidx.appcompat.app.AlertDialog dialog = new MaterialAlertDialogBuilder(this)
                .setTitle(R.string.cast_mode_title)
                .setView(choices)
                .setNegativeButton(R.string.cancel, (ignored, which) -> clearPending())
                .setOnCancelListener(ignored -> clearPending())
                .create();
        addCastChoice(choices, R.drawable.ic_cast_phone, R.string.cast_current_screen, 0, () -> {
                    dialog.dismiss(); pendingExternalDisplay = false; askAboutAudio();
                });
        addCastChoice(choices, R.drawable.ic_cast_monitor, R.string.cast_external_screen, R.string.cast_uses_scrcpy, () -> {
                    dialog.dismiss(); pendingExternalDisplay = true; pendingAudio = false;
                    checkDesktopSettings();
                });
        dialog.show();
    }

    private void checkDesktopSettings() {
        new Thread(() -> {
            boolean desktopEnabled = false;
            boolean freeformEnabled = false;
            boolean readable = true;
            try {
                desktopEnabled = "1".equals(android.provider.Settings.Global.getString(getContentResolver(),
                        "force_desktop_mode_on_external_displays"));
                freeformEnabled = "1".equals(android.provider.Settings.Global.getString(getContentResolver(),
                        "enable_freeform_support"));
            } catch (RuntimeException e) {
                readable = false;
                Log.w(TAG, "Unable to check desktop settings", e);
            }
            boolean enabled = desktopEnabled && freeformEnabled;
            boolean checked = readable;
            runOnUiThread(() -> {
                if (pendingAddress == null || isFinishing() || isDestroyed()) return;
                if (checked && enabled) { askAboutAudio(); return; }
                new MaterialAlertDialogBuilder(this)
                        .setTitle(R.string.desktop_settings_warning_title)
                        .setMessage(checked ? R.string.desktop_settings_warning : R.string.desktop_settings_unavailable)
                        .setNegativeButton(R.string.cancel, (ignored, which) -> clearPending())
                        .setPositiveButton(R.string.continue_action, (ignored, which) -> askAboutAudio())
                        .setOnCancelListener(ignored -> clearPending())
                        .show();
            });
        }, "CheckDesktopSettings").start();
    }

    private void addCastChoice(LinearLayout parent, int icon, int title, int subtitle, Runnable action) {
        MaterialCardView card = new MaterialCardView(this);
        card.setRadius(dp(20));
        android.util.TypedValue color = new android.util.TypedValue();
        getTheme().resolveAttribute(com.google.android.material.R.attr.colorSecondaryContainer, color, true);
        card.setCardBackgroundColor(color.data);
        LinearLayout row = new LinearLayout(this);
        row.setGravity(android.view.Gravity.CENTER_VERTICAL);
        row.setPadding(dp(18), dp(20), dp(18), dp(20));
        android.widget.ImageView image = new android.widget.ImageView(this);
        image.setImageResource(icon);
        getTheme().resolveAttribute(com.google.android.material.R.attr.colorOnSecondaryContainer, color, true);
        image.setColorFilter(color.data);
        row.addView(image, new LinearLayout.LayoutParams(dp(36), dp(36)));
        LinearLayout text = new LinearLayout(this);
        text.setOrientation(LinearLayout.VERTICAL);
        text.setPadding(dp(16), 0, 0, 0);
        TextView heading = new TextView(this);
        heading.setText(title);
        heading.setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_TitleMedium);
        text.addView(heading);
        if (subtitle != 0) {
            TextView detail = new TextView(this);
            detail.setText(subtitle);
            detail.setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_BodySmall);
            text.addView(detail);
        }
        row.addView(text, new LinearLayout.LayoutParams(0, -2, 1));
        card.addView(row);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-1, -2);
        params.bottomMargin = dp(12);
        parent.addView(card, params);
        card.setOnClickListener(v -> action.run());
    }

    private void showScrcpySettings() {
        ScrcpyPreferences settings = new ScrcpyPreferences(this);
        LinearLayout fields = new LinearLayout(this);
        fields.setOrientation(LinearLayout.VERTICAL);
        fields.setPadding(dp(24), 0, dp(24), 0);
        TextView description = new TextView(this);
        description.setText(R.string.scrcpy_settings_description);
        fields.addView(description);
        TextView resolutionLabel = new TextView(this);
        resolutionLabel.setText(R.string.scrcpy_resolution);
        resolutionLabel.setPadding(0, dp(12), 0, 0);
        fields.addView(resolutionLabel);
        int[][] resolutions = {{1280, 720}, {1920, 1080}, {2560, 1440}, {3840, 2160}};
        ArrayList<String> resolutionLabels = new ArrayList<>();
        int selectedResolution = -1;
        for (int i = 0; i < resolutions.length; i++) {
            resolutionLabels.add(getString(R.string.scrcpy_resolution_value, resolutions[i][0], resolutions[i][1]));
            if (settings.width == resolutions[i][0] && settings.height == resolutions[i][1]) selectedResolution = i;
        }
        if (selectedResolution == -1) {
            selectedResolution = resolutionLabels.size();
            resolutionLabels.add(getString(R.string.scrcpy_resolution_custom, settings.width, settings.height));
        }
        android.widget.Spinner resolution = new android.widget.Spinner(this);
        android.widget.ArrayAdapter<String> resolutionAdapter = new android.widget.ArrayAdapter<>(this,
                android.R.layout.simple_spinner_item, resolutionLabels);
        resolutionAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        resolution.setAdapter(resolutionAdapter);
        resolution.setSelection(selectedResolution);
        fields.addView(resolution, new LinearLayout.LayoutParams(-1, -2));
        android.widget.EditText dpi = settingsField(fields, R.string.scrcpy_dpi, settings.dpi);
        android.widget.EditText fps = settingsField(fields, R.string.scrcpy_fps, settings.fps);
        android.widget.EditText bitrate = settingsField(fields, R.string.scrcpy_bitrate, settings.bitrateMbps);
        String[] selectedApp = {settings.defaultApp == null ? "" : settings.defaultApp};
        TextView appLabel = new TextView(this);
        appLabel.setText(R.string.scrcpy_default_app);
        appLabel.setPadding(0, dp(12), 0, 0);
        fields.addView(appLabel);
        MaterialButton appPicker = new MaterialButton(this);
        appPicker.setText(defaultAppLabel(selectedApp[0]));
        appPicker.setAllCaps(false);
        appPicker.setOnClickListener(v -> chooseDefaultApp(selectedApp, appPicker));
        fields.addView(appPicker, new LinearLayout.LayoutParams(-1, -2));
        ScrollView scroll = new ScrollView(this);
        scroll.addView(fields);
        androidx.appcompat.app.AlertDialog dialog = new MaterialAlertDialogBuilder(this)
                .setTitle(R.string.scrcpy_settings_title).setView(scroll)
                .setNegativeButton(R.string.cancel, null)
                .setPositiveButton(R.string.save, null).create();
        dialog.setOnShowListener(ignored -> dialog.getButton(android.content.DialogInterface.BUTTON_POSITIVE)
                .setOnClickListener(v -> {
                    try {
                        int selection = resolution.getSelectedItemPosition();
                        int w = selection < resolutions.length ? resolutions[selection][0] : settings.width;
                        int h = selection < resolutions.length ? resolutions[selection][1] : settings.height;
                        int d = Integer.parseInt(dpi.getText().toString());
                        int f = Integer.parseInt(fps.getText().toString());
                        int b = Integer.parseInt(bitrate.getText().toString());
                        if (w < 320 || w > 3840 || h < 320 || h > 3840 || w % 8 != 0 || h % 8 != 0
                                || d < 72 || d > 640 || f < 1 || f > 120 || b < 1 || b > 50)
                            throw new IllegalArgumentException();
                        ScrcpyPreferences.save(this, w, h, f, b, d, selectedApp[0]);
                        dialog.dismiss();
                    } catch (IllegalArgumentException e) {
                        android.widget.Toast.makeText(this, R.string.scrcpy_settings_invalid,
                                android.widget.Toast.LENGTH_LONG).show();
                    }
                }));
        dialog.show();
    }

    private String defaultAppLabel(String packageName) {
        if (packageName == null || packageName.isEmpty()) return getString(R.string.scrcpy_default_app_none);
        try {
            PackageManager pm = getPackageManager();
            return pm.getApplicationLabel(pm.getApplicationInfo(packageName, 0)).toString();
        } catch (PackageManager.NameNotFoundException e) { return packageName; }
    }

    private void chooseDefaultApp(String[] selectedApp, MaterialButton button) {
        new Thread(() -> {
            try {
                PackageManager pm = getPackageManager();
                java.util.Map<String, String> apps = new java.util.HashMap<>();
                for (String category : new String[]{Intent.CATEGORY_LAUNCHER, Intent.CATEGORY_LEANBACK_LAUNCHER}) {
                    Intent query = new Intent(Intent.ACTION_MAIN).addCategory(category);
                    for (android.content.pm.ResolveInfo info : pm.queryIntentActivities(query, 0)) {
                        if (info.activityInfo == null || !info.activityInfo.enabled || !info.activityInfo.applicationInfo.enabled) continue;
                        String packageName = info.activityInfo.packageName;
                        apps.put(packageName, info.loadLabel(pm).toString());
                    }
                }
                ArrayList<String> packages = new ArrayList<>(apps.keySet());
                java.text.Collator collator = java.text.Collator.getInstance();
                packages.sort((a, b) -> {
                    int result = collator.compare(apps.get(a), apps.get(b));
                    return result != 0 ? result : a.compareTo(b);
                });
                ArrayList<String> labels = new ArrayList<>();
                labels.add(getString(R.string.scrcpy_default_app_none));
                for (String packageName : packages) labels.add(apps.get(packageName));
                int selected = packages.indexOf(selectedApp[0]) + 1;
                runOnUiThread(() -> {
                    if (isFinishing() || isDestroyed() || !button.isAttachedToWindow()) return;
                    new MaterialAlertDialogBuilder(this).setTitle(R.string.scrcpy_default_app_choose)
                            .setSingleChoiceItems(labels.toArray(new String[0]), selected, (dialog, index) -> {
                                selectedApp[0] = index == 0 ? "" : packages.get(index - 1);
                                button.setText(index == 0 ? getString(R.string.scrcpy_default_app_none) : labels.get(index));
                                dialog.dismiss();
                            }).setNegativeButton(R.string.cancel, null).show();
                });
            } catch (RuntimeException e) {
                Log.w(TAG, "Unable to list launchable apps", e);
                runOnUiThread(() -> {
                    if (!isFinishing() && !isDestroyed())
                        android.widget.Toast.makeText(this, R.string.scrcpy_apps_unavailable, android.widget.Toast.LENGTH_SHORT).show();
                });
            }
        }, "ListDefaultApps").start();
    }

    private android.widget.EditText settingsField(LinearLayout parent, int hint, int value) {
        android.widget.EditText field = new android.widget.EditText(this);
        field.setSingleLine(true);
        field.setInputType(android.text.InputType.TYPE_CLASS_NUMBER);
        field.setHint(hint);
        field.setText(String.valueOf(value));
        TextView label = new TextView(this);
        label.setText(hint);
        label.setPadding(0, dp(12), 0, 0);
        parent.addView(label);
        parent.addView(field, new LinearLayout.LayoutParams(-1, -2));
        return field;
    }

    private void askAboutAudio() {
        new MaterialAlertDialogBuilder(this)
                .setTitle(R.string.share_audio_title)
                .setMessage(pendingExternalDisplay ? R.string.share_external_audio_detail : R.string.share_audio_detail)
                .setNeutralButton(R.string.cancel, (dialog, which) -> clearPending())
                .setNegativeButton(R.string.skip_audio, (dialog, which) -> {
                    pendingAudio = false;
                    continueWithoutAudio();
                })
                .setPositiveButton(R.string.share_audio, (dialog, which) -> {
                    pendingAudio = true;
                    if (pendingExternalDisplay) { startProjectionService(0, null); return; }
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

    private void continueWithoutAudio() {
        if (pendingExternalDisplay) startProjectionService(0, null);
        else requestScreenCapture();
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
        startProjectionService(resultCode, data);
    }

    private void startProjectionService(int resultCode, Intent data) {
        if (pendingAddress == null) return;
        Log.i(TAG, "Starting " + (pendingExternalDisplay ? "scrcpy external display" : "phone screen")
                + ", audio=" + pendingAudio + ", receiver=" + pendingName);
        Intent service = new Intent(this, ProjectionService.class);
        service.putExtra("resultCode", resultCode);
        service.putExtra("resultData", data);
        service.putExtra("deviceAddress", pendingAddress);
        service.putExtra("deviceName", pendingName);
        service.putExtra("captureAudio", pendingAudio);
        service.putExtra("externalDisplay", pendingExternalDisplay);
        service.putExtra("groupOwnerIntent", pendingGroupOwnerIntent);
        updateStatus(getString(R.string.connecting), getString(R.string.connecting_to, pendingName), true);
        clearPending();
        try { ContextCompat.startForegroundService(this, service); }
        catch (RuntimeException e) { updateStatus(getString(R.string.casting_start_failed), e.getMessage(), false); }
    }

    private void clearPending() { pendingAddress = null; pendingName = null; pendingAudio = false; pendingExternalDisplay = false; pendingGroupOwnerIntent = 7; }
    private void updateStatus(String title, String detail, boolean active) {
        if (title != null) statusTitle.setText(title);
        if (detail != null) statusDetail.setText(detail);
        stopButton.setVisibility(active ? View.VISIBLE : View.GONE);
        controllerButton.setVisibility(active && ScrcpySession.active() != null ? View.VISIBLE : View.GONE);
        wifiDirectSettingsButton.setVisibility(!active && getString(R.string.connection_failed).equals(title)
                ? View.VISIBLE : View.GONE);
    }
    private void showMessage(String title, String message) {
        if (!isFinishing() && !isDestroyed()) new MaterialAlertDialogBuilder(this).setTitle(title).setMessage(message).setPositiveButton(R.string.ok, null).show();
    }
}
