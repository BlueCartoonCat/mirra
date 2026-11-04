package io.github.bluecartooncat.mirra;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Bundle;
import android.view.Gravity;
import android.view.GestureDetector;
import android.view.HapticFeedbackConstants;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowCompat;
import androidx.core.view.WindowInsetsCompat;

import com.google.android.material.button.MaterialButton;
import com.google.android.material.card.MaterialCardView;
import com.google.android.material.color.DynamicColors;

/** Phone touchpad and text input for the current scrcpy external display. */
public class ControllerActivity extends AppCompatActivity {
    private ScrcpySession session;
    private float lastX, lastY;
    private boolean receiverRegistered;
    private boolean dragging, scaling;
    private boolean keyboardMode, keyboardShown;
    private GestureDetector gestures;
    private float lastCentroidX, lastCentroidY, gestureStartX, gestureStartY, startSpan, lastZoomSpan;
    private float scrollX, scrollY;
    private int twoFingerMode; // 0 undecided, 1 scrolling, 2 zooming
    private final BroadcastReceiver receiver = new BroadcastReceiver() {
        @Override public void onReceive(Context context, Intent intent) {
            if (!intent.getBooleanExtra("active", false)) finish();
        }
    };

    @Override protected void onCreate(Bundle state) {
        DynamicColors.applyToActivityIfAvailable(this);
        super.onCreate(state);
        WindowCompat.setDecorFitsSystemWindows(getWindow(), false);
        getWindow().addFlags(android.view.WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE);
        session = ScrcpySession.active();
        if (session == null) {
            Toast.makeText(this, R.string.controller_unavailable, Toast.LENGTH_SHORT).show();
            finish();
            return;
        }

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(24), dp(20), dp(24), dp(16));
        android.util.TypedValue surface = new android.util.TypedValue();
        getTheme().resolveAttribute(com.google.android.material.R.attr.colorSurface, surface, true);
        root.setBackgroundColor(surface.data);
        android.widget.ScrollView scroll = new android.widget.ScrollView(this);
        scroll.setFillViewport(true);
        scroll.setClipToPadding(false);
        scroll.setBackgroundColor(surface.data);
        scroll.addView(root, new android.widget.ScrollView.LayoutParams(-1, -2));
        setContentView(scroll);
        ViewCompat.setOnApplyWindowInsetsListener(scroll, (view, windowInsets) -> {
            Insets bars = windowInsets.getInsets(WindowInsetsCompat.Type.systemBars() | WindowInsetsCompat.Type.displayCutout());
            Insets ime = windowInsets.getInsets(WindowInsetsCompat.Type.ime());
            view.setPadding(bars.left, bars.top, bars.right, Math.max(bars.bottom, ime.bottom));
            if (keyboardMode) {
                if (windowInsets.isVisible(WindowInsetsCompat.Type.ime())) keyboardShown = true;
                else if (keyboardShown) leaveKeyboardMode();
            }
            return windowInsets;
        });
        ViewCompat.requestApplyInsets(scroll);
        LinearLayout buttons = new LinearLayout(this);
        buttons.setOrientation(LinearLayout.HORIZONTAL);
        buttons.setGravity(Gravity.CENTER_VERTICAL);
        android.widget.ImageButton close = new android.widget.ImageButton(this);
        close.setImageResource(R.drawable.ic_remote_arrow);
        close.setRotation(270);
        close.setScaleType(android.widget.ImageView.ScaleType.CENTER);
        close.setPadding(0, 0, 0, 0);
        android.util.TypedValue foreground = new android.util.TypedValue();
        getTheme().resolveAttribute(com.google.android.material.R.attr.colorOnSurface, foreground, true);
        close.setColorFilter(foreground.data);
        android.util.TypedValue selectable = new android.util.TypedValue();
        getTheme().resolveAttribute(android.R.attr.selectableItemBackgroundBorderless, selectable, true);
        close.setBackgroundResource(selectable.resourceId);
        close.setContentDescription(getString(R.string.controller_page_back));
        close.setTooltipText(getString(R.string.controller_page_back));
        close.setOnClickListener(v -> finish());
        buttons.addView(close, new LinearLayout.LayoutParams(dp(48), dp(56)));
        MaterialButton keyboard = iconButton(R.drawable.ic_controller_keyboard, R.string.controller_keyboard);
        MaterialButton remote = iconButton(R.drawable.ic_controller_remote, R.string.controller_remote);
        MaterialButton gamepad = iconButton(R.drawable.ic_controller_gamepad, R.string.controller_gamepad);
        MaterialButton screenOff = iconButton(R.drawable.ic_controller_power, R.string.controller_screen_off);
        screenOff.setOnClickListener(v -> session.turnOffPhoneDisplay());
        remote.setOnClickListener(v -> startActivity(new Intent(this, RemoteControllerActivity.class)));
        gamepad.setOnClickListener(v -> startActivity(new Intent(this, GamepadActivity.class)));
        for (MaterialButton control : new MaterialButton[]{keyboard, remote, gamepad, screenOff})
            buttons.addView(control, new LinearLayout.LayoutParams(0, dp(56), 1));
        root.addView(buttons);

        MaterialCardView touchpad = new MaterialCardView(this);
        touchpad.setRadius(dp(28));
        touchpad.setStrokeWidth(0);
        android.util.TypedValue color = new android.util.TypedValue();
        getTheme().resolveAttribute(com.google.android.material.R.attr.colorSecondaryContainer, color, true);
        touchpad.setCardBackgroundColor(color.data);
        touchpad.setContentDescription(getString(R.string.controller_touchpad));
        LinearLayout.LayoutParams touchpadParams = new LinearLayout.LayoutParams(-1, 0, 1);
        touchpadParams.topMargin = dp(12);
        root.addView(touchpad, touchpadParams);
        gestures = new GestureDetector(this, new GestureDetector.SimpleOnGestureListener() {
            @Override public boolean onDown(MotionEvent event) { return true; }
            @Override public boolean onSingleTapConfirmed(MotionEvent event) {
                if (!scaling && session != null && ScrcpySession.active() == session) {
                    touchpad.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP);
                    touchpad.performClick();
                    session.sendTap();
                }
                return true;
            }
            @Override public boolean onDoubleTap(MotionEvent event) {
                if (session != null && ScrcpySession.active() == session) {
                    touchpad.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS);
                    session.sendRightClick();
                }
                return true;
            }
            @Override public void onLongPress(MotionEvent event) {
                if (!scaling && session != null && ScrcpySession.active() == session) {
                    touchpad.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS);
                    dragging = true;
                    session.setDragging(true);
                }
            }
        });
        touchpad.setOnTouchListener(this::onTouchpad);

        EditText input = new EditText(this);
        input.setSingleLine(true);
        input.setHint(R.string.controller_text_hint);
        input.setVisibility(View.GONE);
        LinearLayout textRow = new LinearLayout(this);
        textRow.setOrientation(LinearLayout.HORIZONTAL);
        textRow.setGravity(Gravity.CENTER_VERTICAL);
        textRow.setVisibility(View.GONE);
        textRow.addView(input, new LinearLayout.LayoutParams(0, -2, 1));
        MaterialButton send = iconButton(R.drawable.ic_controller_send, R.string.controller_send);
        textRow.addView(send, new LinearLayout.LayoutParams(dp(56), dp(56)));
        root.addView(textRow);
        LinearLayout navigation = new LinearLayout(this);
        navigation.setGravity(Gravity.CENTER);
        android.widget.ImageButton back = navigationButton(R.drawable.ic_controller_back, R.string.controller_back);
        android.widget.ImageButton home = navigationButton(R.drawable.ic_remote_home, R.string.remote_home);
        android.widget.ImageButton apps = navigationButton(R.drawable.ic_controller_apps, R.string.controller_launch_app);
        back.setOnClickListener(v -> session.sendKey(KeyEvent.KEYCODE_BACK));
        home.setOnClickListener(v -> session.sendKey(KeyEvent.KEYCODE_HOME));
        apps.setOnClickListener(v -> showAppPicker());
        LinearLayout.LayoutParams navParams = new LinearLayout.LayoutParams(dp(64), dp(64));
        navParams.setMargins(dp(12), dp(8), dp(12), 0);
        navigation.addView(back, navParams);
        LinearLayout.LayoutParams homeParams = new LinearLayout.LayoutParams(dp(64), dp(64));
        homeParams.setMargins(dp(12), dp(8), dp(12), 0);
        navigation.addView(home, homeParams);
        LinearLayout.LayoutParams appsParams = new LinearLayout.LayoutParams(dp(64), dp(64));
        appsParams.setMargins(dp(12), dp(8), dp(12), 0);
        navigation.addView(apps, appsParams);
        root.addView(navigation);
        keyboard.setOnClickListener(v -> {
            keyboardMode = true;
            keyboardShown = false;
            getWindow().clearFlags(android.view.WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE);
            textRow.setVisibility(View.VISIBLE);
            input.setVisibility(View.VISIBLE);
            input.post(() -> {
                if (!keyboardMode || isFinishing() || isDestroyed()) return;
                input.requestFocus();
                android.view.inputmethod.InputMethodManager ime =
                        (android.view.inputmethod.InputMethodManager) getSystemService(INPUT_METHOD_SERVICE);
                if (ime != null) ime.showSoftInput(input, android.view.inputmethod.InputMethodManager.SHOW_IMPLICIT);
            });
        });
        send.setOnClickListener(v -> {
            session.sendText(input.getText().toString());
            input.setText("");
            android.view.inputmethod.InputMethodManager ime =
                    (android.view.inputmethod.InputMethodManager) getSystemService(INPUT_METHOD_SERVICE);
            if (ime != null) ime.hideSoftInputFromWindow(input.getWindowToken(), 0);
            input.clearFocus();
            leaveKeyboardMode();
        });
    }

    private void leaveKeyboardMode() {
        keyboardMode = false;
        keyboardShown = false;
        getWindow().addFlags(android.view.WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE);
    }

    private static final class LaunchableApp {
        final String packageName, label;
        final android.graphics.drawable.Drawable icon;
        LaunchableApp(String packageName, String label, android.graphics.drawable.Drawable icon) {
            this.packageName = packageName; this.label = label; this.icon = icon;
        }
    }

    private void showAppPicker() {
        ScrcpySession current = session;
        new Thread(() -> {
            try {
                android.content.pm.PackageManager pm = getPackageManager();
                java.util.Map<String, LaunchableApp> byPackage = new java.util.HashMap<>();
                for (String category : new String[]{Intent.CATEGORY_LAUNCHER, Intent.CATEGORY_LEANBACK_LAUNCHER}) {
                    Intent query = new Intent(Intent.ACTION_MAIN).addCategory(category);
                    for (android.content.pm.ResolveInfo info : pm.queryIntentActivities(query, 0)) {
                        if (info.activityInfo == null || !info.activityInfo.enabled || !info.activityInfo.applicationInfo.enabled) continue;
                        String name = info.activityInfo.packageName;
                        if (!byPackage.containsKey(name))
                            byPackage.put(name, new LaunchableApp(name, info.loadLabel(pm).toString(), info.loadIcon(pm)));
                    }
                }
                java.util.ArrayList<LaunchableApp> apps = new java.util.ArrayList<>(byPackage.values());
                java.text.Collator collator = java.text.Collator.getInstance();
                apps.sort((a, b) -> {
                    int result = collator.compare(a.label, b.label);
                    return result != 0 ? result : a.packageName.compareTo(b.packageName);
                });
                runOnUiThread(() -> {
                    if (isFinishing() || isDestroyed() || ScrcpySession.active() != current) return;
                    android.widget.ArrayAdapter<LaunchableApp> adapter = new android.widget.ArrayAdapter<LaunchableApp>(
                            this, android.R.layout.simple_list_item_1, apps) {
                        @Override public View getView(int position, View convertView, android.view.ViewGroup parent) {
                            LinearLayout row;
                            if (convertView instanceof LinearLayout) row = (LinearLayout) convertView;
                            else {
                                row = new LinearLayout(ControllerActivity.this);
                                row.setGravity(Gravity.CENTER_VERTICAL);
                                row.setPadding(dp(20), dp(8), dp(20), dp(8));
                                android.widget.ImageView icon = new android.widget.ImageView(ControllerActivity.this);
                                row.addView(icon, new LinearLayout.LayoutParams(dp(40), dp(40)));
                                TextView label = new TextView(ControllerActivity.this);
                                label.setPadding(dp(16), 0, 0, 0);
                                label.setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_BodyLarge);
                                row.addView(label, new LinearLayout.LayoutParams(0, -2, 1));
                            }
                            LaunchableApp app = getItem(position);
                            ((android.widget.ImageView) row.getChildAt(0)).setImageDrawable(app.icon);
                            ((TextView) row.getChildAt(1)).setText(app.label);
                            return row;
                        }
                    };
                    new com.google.android.material.dialog.MaterialAlertDialogBuilder(this)
                            .setTitle(R.string.controller_launch_app)
                            .setAdapter(adapter, (dialog, which) -> {
                                if (ScrcpySession.active() == current) current.launchApp(apps.get(which).packageName);
                            })
                            .setNegativeButton(R.string.cancel, null).show();
                });
            } catch (RuntimeException e) {
                Log.w("ControllerActivity", "Unable to list launchable apps", e);
                runOnUiThread(() -> {
                    if (!isFinishing() && !isDestroyed()) Toast.makeText(this, R.string.scrcpy_apps_unavailable, Toast.LENGTH_SHORT).show();
                });
            }
        }, "LoadControllerApps").start();
    }

    private boolean onTouchpad(View view, MotionEvent event) {
        if (session == null || ScrcpySession.active() != session) return false;
        if (event.getActionMasked() == MotionEvent.ACTION_POINTER_DOWN && event.getPointerCount() == 2) {
            scaling = true;
            if (dragging) { session.setDragging(false); dragging = false; }
            gestureStartX = lastCentroidX = (event.getX(0) + event.getX(1)) / 2f;
            gestureStartY = lastCentroidY = (event.getY(0) + event.getY(1)) / 2f;
            startSpan = lastZoomSpan = span(event);
            scrollX = scrollY = 0;
            twoFingerMode = 0;
        }
        gestures.onTouchEvent(event);
        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                view.getParent().requestDisallowInterceptTouchEvent(true);
                scaling = false;
                lastX = event.getX();
                lastY = event.getY();
                return true;
            case MotionEvent.ACTION_MOVE:
                if (event.getPointerCount() == 2 && scaling) {
                    handleTwoFingerMove(event);
                    return true;
                }
                if (event.getPointerCount() != 1 || scaling) return true;
                float x = event.getX(), y = event.getY();
                if (session.usesNativeMouse()) session.moveMouse(x - lastX, y - lastY);
                else session.setCursor(session.cursorX() + (x - lastX) * 1.5f / Math.max(1, view.getWidth()),
                            session.cursorY() + (y - lastY) * 1.5f / Math.max(1, view.getHeight()));
                if (dragging && !session.usesNativeMouse()) session.moveLegacyDrag();
                lastX = x; lastY = y;
                return true;
            case MotionEvent.ACTION_UP:
                if (dragging) { session.setDragging(false); dragging = false; }
                view.getParent().requestDisallowInterceptTouchEvent(false);
                return true;
            case MotionEvent.ACTION_CANCEL:
                if (dragging) { session.setDragging(false); dragging = false; }
                view.getParent().requestDisallowInterceptTouchEvent(false);
                return true;
            default:
                return true;
        }
    }

    private float span(MotionEvent event) {
        return (float) Math.hypot(event.getX(0) - event.getX(1), event.getY(0) - event.getY(1));
    }

    private void handleTwoFingerMove(MotionEvent event) {
        float x = (event.getX(0) + event.getX(1)) / 2f;
        float y = (event.getY(0) + event.getY(1)) / 2f;
        float distance = span(event);
        if (twoFingerMode == 0) {
            float translation = (float) Math.hypot(x - gestureStartX, y - gestureStartY);
            float scaleChange = Math.abs(distance - startSpan);
            if (scaleChange > dp(16) && scaleChange > translation) twoFingerMode = 2;
            else if (translation > dp(10)) twoFingerMode = 1;
        }
        if (twoFingerMode == 1) {
            scrollX += x - lastCentroidX;
            scrollY += y - lastCentroidY;
            int h = -(int) (scrollX / dp(24));
            int v = (int) (scrollY / dp(24));
            if (h != 0 || v != 0) {
                session.scroll(h, v);
                scrollX += h * dp(24);
                scrollY -= v * dp(24);
            }
        } else if (twoFingerMode == 2 && lastZoomSpan > 0) {
            float factor = distance / lastZoomSpan;
            if (factor >= 1.12f || factor <= 1f / 1.12f) {
                session.zoom(factor > 1 ? 1 : -1);
                lastZoomSpan = distance;
            }
        }
        lastCentroidX = x;
        lastCentroidY = y;
    }

    private MaterialButton iconButton(int icon, int label) {
        MaterialButton button = new MaterialButton(this);
        button.setIconResource(icon);
        button.setIconGravity(MaterialButton.ICON_GRAVITY_TEXT_START);
        button.setIconPadding(0);
        button.setGravity(Gravity.CENTER);
        button.setMinimumWidth(0);
        button.setPadding(0, 0, 0, 0);
        button.setContentDescription(getString(label));
        button.setTooltipText(getString(label));
        return button;
    }

    private android.widget.ImageButton navigationButton(int icon, int label) {
        android.widget.ImageButton button = new android.widget.ImageButton(this);
        button.setImageResource(icon);
        button.setColorFilter(android.graphics.Color.WHITE);
        button.setScaleType(android.widget.ImageView.ScaleType.CENTER);
        button.setPadding(0, 0, 0, 0);
        button.setContentDescription(getString(label));
        button.setTooltipText(getString(label));
        android.graphics.drawable.GradientDrawable shape = new android.graphics.drawable.GradientDrawable();
        shape.setShape(android.graphics.drawable.GradientDrawable.OVAL);
        shape.setColor(0xff242424);
        button.setBackground(new android.graphics.drawable.RippleDrawable(
                android.content.res.ColorStateList.valueOf(0x44808080), shape, null));
        return button;
    }

    @Override protected void onResume() {
        super.onResume();
        if (session == null || ScrcpySession.active() != session) { finish(); return; }
        session.setCursorVisible(true);
        ContextCompat.registerReceiver(this, receiver, new IntentFilter(CastingService.ACTION_STATUS),
                ContextCompat.RECEIVER_NOT_EXPORTED);
        receiverRegistered = true;
    }

    @Override protected void onPause() {
        leaveKeyboardMode();
        if (session != null && dragging) { session.setDragging(false); dragging = false; }
        if (session != null) session.setCursorVisible(false);
        if (receiverRegistered) { unregisterReceiver(receiver); receiverRegistered = false; }
        super.onPause();
    }

    private int dp(int value) { return (int) (value * getResources().getDisplayMetrics().density + .5f); }
}
