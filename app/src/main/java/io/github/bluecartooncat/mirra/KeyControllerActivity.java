package io.github.bluecartooncat.mirra;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Bundle;
import android.view.Gravity;
import android.view.HapticFeedbackConstants;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;
import com.google.android.material.button.MaterialButton;
import com.google.android.material.color.DynamicColors;

/** Shared controls for the remote and button-based gamepad. */
abstract class KeyControllerActivity extends AppCompatActivity {
    private ScrcpySession session;
    private boolean registered;
    private final android.os.Handler handler = new android.os.Handler(android.os.Looper.getMainLooper());
    private final java.util.Set<Integer> pressedKeys = new java.util.HashSet<>();
    private final java.util.Set<android.view.View> pressedViews = new java.util.HashSet<>();
    private final BroadcastReceiver receiver = new BroadcastReceiver() {
        @Override public void onReceive(Context context, Intent intent) {
            if (!intent.getBooleanExtra("active", false)) finish();
        }
    };

    abstract boolean isGamepad();

    @Override protected void onCreate(Bundle state) {
        DynamicColors.applyToActivityIfAvailable(this);
        super.onCreate(state);
        if (!isGamepad()) {
            // The remote needs touch input, not keyboard focus. Keeping the
            // phone window non-focusable avoids repeatedly stealing focus from
            // the external display before each scrcpy keyboard event.
            getWindow().addFlags(android.view.WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE);
        }
        session = ScrcpySession.active();
        if (session == null) { Toast.makeText(this, R.string.controller_unavailable, Toast.LENGTH_SHORT).show(); finish(); return; }
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setGravity(Gravity.CENTER);
        root.setPadding(dp(20), dp(28), dp(20), dp(20));
        androidx.core.view.WindowCompat.setDecorFitsSystemWindows(getWindow(), false);
        androidx.core.view.ViewCompat.setOnApplyWindowInsetsListener(root, (view, insets) -> {
            androidx.core.graphics.Insets safe = insets.getInsets(
                    androidx.core.view.WindowInsetsCompat.Type.systemBars()
                            | androidx.core.view.WindowInsetsCompat.Type.displayCutout());
            view.setPadding(dp(20) + safe.left, dp(28) + safe.top,
                    dp(20) + safe.right, dp(20) + safe.bottom);
            return insets;
        });
        if (!isGamepad()) {
            root.setGravity(Gravity.CENTER_HORIZONTAL);
            root.setBackgroundColor(android.graphics.Color.BLACK);
            androidx.core.view.WindowInsetsControllerCompat bars =
                    androidx.core.view.WindowCompat.getInsetsController(getWindow(), getWindow().getDecorView());
            bars.setAppearanceLightStatusBars(false);
            bars.setAppearanceLightNavigationBars(false);
            LinearLayout toolbar = new LinearLayout(this);
            toolbar.setGravity(Gravity.CENTER_VERTICAL);
            android.widget.ImageButton close = new android.widget.ImageButton(this);
            close.setImageResource(R.drawable.ic_remote_arrow);
            close.setScaleType(android.widget.ImageView.ScaleType.CENTER);
            close.setPadding(0, 0, 0, 0);
            close.setRotation(270);
            close.setColorFilter(android.graphics.Color.WHITE);
            android.util.TypedValue selectable = new android.util.TypedValue();
            getTheme().resolveAttribute(android.R.attr.selectableItemBackgroundBorderless, selectable, true);
            close.setBackgroundResource(selectable.resourceId);
            close.setContentDescription(getString(R.string.controller_page_back));
            close.setOnClickListener(v -> getOnBackPressedDispatcher().onBackPressed());
            toolbar.addView(close, new LinearLayout.LayoutParams(dp(48), dp(48)));
            toolbar.addView(new android.view.View(this), new LinearLayout.LayoutParams(0, dp(48), 1));
            android.widget.ImageButton displayOff = new android.widget.ImageButton(this);
            displayOff.setImageResource(R.drawable.ic_controller_power);
            displayOff.setScaleType(android.widget.ImageView.ScaleType.CENTER);
            displayOff.setPadding(0, 0, 0, 0);
            displayOff.setColorFilter(android.graphics.Color.WHITE);
            displayOff.setBackgroundResource(selectable.resourceId);
            displayOff.setContentDescription(getString(R.string.controller_screen_off));
            displayOff.setTooltipText(getString(R.string.controller_screen_off));
            displayOff.setOnClickListener(v -> session.turnOffPhoneDisplay());
            toolbar.addView(displayOff, new LinearLayout.LayoutParams(dp(48), dp(48)));
            root.addView(toolbar, new LinearLayout.LayoutParams(-1, -2));
            LinearLayout remoteBody = new LinearLayout(this);
            remoteBody.setOrientation(LinearLayout.VERTICAL);
            remoteBody.setGravity(Gravity.CENTER);
            root.addView(remoteBody, new LinearLayout.LayoutParams(-1, 0, 1));
            addRemote(remoteBody);
            setContentView(root);
            return;
        }
        TextView title = new TextView(this);
        title.setText(R.string.controller_gamepad);
        title.setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_HeadlineSmall);
        root.addView(title);
        if (isGamepad()) {
            addRow(root, new int[]{R.string.gamepad_l1, R.string.gamepad_r1},
                    new int[]{KeyEvent.KEYCODE_BUTTON_L1, KeyEvent.KEYCODE_BUTTON_R1});
        }
        addRow(root, new int[]{0, R.string.remote_up, 0}, new int[]{0, KeyEvent.KEYCODE_DPAD_UP, 0});
        addRow(root, new int[]{R.string.remote_left, R.string.remote_select, R.string.remote_right},
                new int[]{KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_DPAD_RIGHT});
        addRow(root, new int[]{0, R.string.remote_down, 0}, new int[]{0, KeyEvent.KEYCODE_DPAD_DOWN, 0});
        if (isGamepad()) {
            addRow(root, new int[]{R.string.gamepad_x, R.string.gamepad_y}, new int[]{KeyEvent.KEYCODE_BUTTON_X, KeyEvent.KEYCODE_BUTTON_Y});
            addRow(root, new int[]{R.string.gamepad_a, R.string.gamepad_b}, new int[]{KeyEvent.KEYCODE_BUTTON_A, KeyEvent.KEYCODE_BUTTON_B});
            addRow(root, new int[]{R.string.gamepad_select, R.string.gamepad_start}, new int[]{KeyEvent.KEYCODE_BUTTON_SELECT, KeyEvent.KEYCODE_BUTTON_START});
        } else {
            addRow(root, new int[]{R.string.controller_back, R.string.remote_home},
                    new int[]{KeyEvent.KEYCODE_BACK, KeyEvent.KEYCODE_HOME});
        }
        setContentView(root);
    }

    private void addRemote(LinearLayout root) {
        LinearLayout pad = new LinearLayout(this);
        pad.setOrientation(LinearLayout.VERTICAL);
        pad.setGravity(Gravity.CENTER);
        android.graphics.drawable.GradientDrawable padBackground = new android.graphics.drawable.GradientDrawable();
        padBackground.setColor(0xff242424);
        padBackground.setShape(android.graphics.drawable.GradientDrawable.OVAL);
        pad.setBackground(padBackground);
        LinearLayout.LayoutParams padParams = new LinearLayout.LayoutParams(dp(264), dp(264));
        padParams.gravity = Gravity.CENTER_HORIZONTAL;
        padParams.topMargin = dp(32);
        root.addView(pad, padParams);
        remoteRow(pad, new int[]{0, R.drawable.ic_remote_arrow, 0},
                new int[]{0, R.string.remote_up, 0}, new int[]{0, KeyEvent.KEYCODE_DPAD_UP, 0}, new int[]{0, 0, 0});
        remoteRow(pad, new int[]{R.drawable.ic_remote_arrow, R.drawable.ic_remote_select, R.drawable.ic_remote_arrow},
                new int[]{R.string.remote_left, R.string.remote_select, R.string.remote_right},
                new int[]{KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_DPAD_RIGHT},
                new int[]{270, 0, 90});
        remoteRow(pad, new int[]{0, R.drawable.ic_remote_arrow, 0},
                new int[]{0, R.string.remote_down, 0}, new int[]{0, KeyEvent.KEYCODE_DPAD_DOWN, 0}, new int[]{0, 180, 0});
        LinearLayout actions = new LinearLayout(this);
        actions.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams actionsParams = new LinearLayout.LayoutParams(-1, -2);
        actionsParams.topMargin = dp(28);
        root.addView(actions, actionsParams);
        addRemoteButton(actions, R.drawable.ic_controller_back, R.string.controller_back, KeyEvent.KEYCODE_BACK, 0);
        addRemoteButton(actions, R.drawable.ic_remote_home, R.string.remote_home, KeyEvent.KEYCODE_HOME, 0);
    }

    private void remoteRow(LinearLayout parent, int[] icons, int[] labels, int[] keys, int[] rotations) {
        LinearLayout row = new LinearLayout(this);
        row.setGravity(Gravity.CENTER);
        for (int i = 0; i < icons.length; i++) {
            if (icons[i] == 0) row.addView(new android.view.View(this), new LinearLayout.LayoutParams(dp(88), dp(88)));
            else addRemoteButton(row, icons[i], labels[i], keys[i], rotations[i]);
        }
        parent.addView(row);
    }

    private void addRemoteButton(LinearLayout row, int icon, int label, int key, int rotation) {
        android.view.View button;
        int foreground = android.graphics.Color.WHITE;
        if (key == KeyEvent.KEYCODE_DPAD_CENTER) {
            TextView ok = new TextView(this);
            ok.setText(R.string.remote_ok);
            ok.setGravity(Gravity.CENTER);
            ok.setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_TitleMedium);
            ok.setTextColor(foreground);
            button = ok;
        } else {
            android.widget.ImageButton image = new android.widget.ImageButton(this);
            image.setImageResource(icon);
            image.setColorFilter(foreground);
            image.setScaleType(android.widget.ImageView.ScaleType.CENTER);
            button = image;
        }
        button.setContentDescription(getString(label));
        button.setTooltipText(getString(label));
        android.graphics.drawable.GradientDrawable shape = new android.graphics.drawable.GradientDrawable();
        shape.setShape(android.graphics.drawable.GradientDrawable.OVAL);
        shape.setColor(key == KeyEvent.KEYCODE_DPAD_CENTER ? 0xff484848 : 0xff242424);
        button.setBackground(new android.graphics.drawable.RippleDrawable(
                android.content.res.ColorStateList.valueOf(0x44808080), shape, null));
        button.setPadding(0, 0, 0, 0);
        button.setRotation(rotation);
        bindKey(button, key, key >= KeyEvent.KEYCODE_DPAD_UP && key <= KeyEvent.KEYCODE_DPAD_RIGHT);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(dp(80), dp(80));
        params.setMargins(dp(4), dp(4), dp(4), dp(4));
        row.addView(button, params);
    }

    private void bindKey(android.view.View button, int key, boolean repeat) {
        final int[] count = {0};
        Runnable repeater = new Runnable() {
            @Override public void run() {
                if (!pressedKeys.contains(key) || ScrcpySession.active() != session) return;
                session.repeatKey(key, ++count[0]);
                handler.postDelayed(this, 100);
            }
        };
        button.setOnTouchListener((view, event) -> {
            switch (event.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    if (ScrcpySession.active() != session) return false;
                    count[0] = 0;
                    if (pressedKeys.add(key)) session.remoteKeyAction(key, true);
                    view.setPressed(true);
                    pressedViews.add(view);
                    view.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP);
                    if (repeat && !session.nativeRemote()) handler.postDelayed(repeater, 400);
                    return true;
                case MotionEvent.ACTION_UP:
                case MotionEvent.ACTION_CANCEL:
                    handler.removeCallbacks(repeater);
                    if (pressedKeys.remove(key)) session.remoteKeyAction(key, false);
                    view.setPressed(false);
                    pressedViews.remove(view);
                    if (event.getActionMasked() == MotionEvent.ACTION_UP) view.performClick();
                    return true;
                default: return true;
            }
        });
    }

    private void addRow(LinearLayout root, int[] labels, int[] keys) {
        LinearLayout row = new LinearLayout(this);
        row.setGravity(Gravity.CENTER);
        for (int i = 0; i < labels.length; i++) {
            if (labels[i] == 0) {
                android.view.View spacer = new android.view.View(this);
                row.addView(spacer, new LinearLayout.LayoutParams(0, dp(60), 1));
                continue;
            }
            MaterialButton button = new MaterialButton(this);
            button.setText(labels[i]);
            button.setAllCaps(false);
            button.setMinWidth(0);
            final int key = keys[i];
            button.setOnTouchListener((view, event) -> {
                if (ScrcpySession.active() != session) return false;
                if (event.getActionMasked() == MotionEvent.ACTION_DOWN) {
                    view.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP);
                    session.sendKeyAction(key, true);
                    view.setPressed(true);
                    return true;
                }
                if (event.getActionMasked() == MotionEvent.ACTION_UP || event.getActionMasked() == MotionEvent.ACTION_CANCEL) {
                    session.sendKeyAction(key, false);
                    view.setPressed(false);
                    if (event.getActionMasked() == MotionEvent.ACTION_UP) view.performClick();
                    return true;
                }
                return true;
            });
            row.addView(button, new LinearLayout.LayoutParams(0, dp(60), 1));
        }
        root.addView(row, new LinearLayout.LayoutParams(-1, -2));
    }

    @Override protected void onResume() {
        super.onResume();
        if (session == null || ScrcpySession.active() != session) { finish(); return; }
        ContextCompat.registerReceiver(this, receiver, new IntentFilter(CastingService.ACTION_STATUS), ContextCompat.RECEIVER_NOT_EXPORTED);
        registered = true;
        if (!isGamepad()) session.setRemoteEnabled(true);
    }
    @Override protected void onPause() {
        handler.removeCallbacksAndMessages(null);
        if (session != null) for (int key : pressedKeys) session.remoteKeyAction(key, false);
        pressedKeys.clear();
        for (android.view.View view : pressedViews) view.setPressed(false);
        pressedViews.clear();
        if (session != null && !isGamepad()) session.setRemoteEnabled(false);
        if (registered) { unregisterReceiver(receiver); registered = false; }
        super.onPause();
    }
    private int dp(int value) { return (int) (value * getResources().getDisplayMetrics().density + .5f); }
}
