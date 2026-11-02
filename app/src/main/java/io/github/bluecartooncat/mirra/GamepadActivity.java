package io.github.bluecartooncat.mirra;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.os.Bundle;
import android.util.SparseArray;
import android.view.HapticFeedbackConstants;
import android.view.MotionEvent;
import android.view.View;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;
import androidx.activity.OnBackPressedCallback;
import androidx.core.content.ContextCompat;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowCompat;
import androidx.core.view.WindowInsetsCompat;

/** Landscape, multi-touch virtual HID gamepad for the external display. */
public class GamepadActivity extends AppCompatActivity {
    private ScrcpySession session;
    private PadView pad;
    private boolean registered;
    private final BroadcastReceiver receiver = new BroadcastReceiver() {
        @Override public void onReceive(Context context, Intent intent) {
            if (!intent.getBooleanExtra("active", false)) finish();
        }
    };

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        getOnBackPressedDispatcher().addCallback(this, new OnBackPressedCallback(true) {
            @Override public void handleOnBackPressed() { }
        });
        session = ScrcpySession.active();
        if (session == null || !session.nativeRemote()) {
            Toast.makeText(this, R.string.gamepad_unavailable, Toast.LENGTH_LONG).show();
            finish(); return;
        }
        WindowCompat.setDecorFitsSystemWindows(getWindow(), false);
        androidx.core.view.WindowInsetsControllerCompat bars = WindowCompat.getInsetsController(getWindow(), getWindow().getDecorView());
        bars.setAppearanceLightStatusBars(false);
        bars.setAppearanceLightNavigationBars(false);
        pad = new PadView(this);
        pad.setContentDescription(getString(R.string.gamepad_controls_description));
        setContentView(pad);
        ViewCompat.setOnApplyWindowInsetsListener(pad, (view, insets) -> {
            Insets safe = insets.getInsets(WindowInsetsCompat.Type.systemBars() | WindowInsetsCompat.Type.displayCutout());
            view.setPadding(safe.left, safe.top, safe.right, safe.bottom);
            return insets;
        });
        ViewCompat.requestApplyInsets(pad);
    }

    @Override protected void onResume() {
        super.onResume();
        if (session == null || ScrcpySession.active() != session) { finish(); return; }
        ContextCompat.registerReceiver(this, receiver, new IntentFilter(CastingService.ACTION_STATUS), ContextCompat.RECEIVER_NOT_EXPORTED);
        registered = true;
        session.setGamepadEnabled(true);
        pad.reset();
    }

    @Override protected void onPause() {
        if (session != null && pad != null) { pad.reset(); session.setGamepadEnabled(false); }
        if (registered) { unregisterReceiver(receiver); registered = false; }
        super.onPause();
    }

    private final class PadView extends View {
        private static final int LEFT_STICK = 100, RIGHT_STICK = 101, DPAD = 102, LT = 103, RT = 104, EXIT = 105;
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final SparseArray<Contact> contacts = new SparseArray<>();
        private float width, height, unit;
        private final float[] baseX = {.22f, .56f, .38f, .19f, .81f, .19f, .81f, .80f, .75f, .85f, .80f, .45f, .55f};
        private final float[] baseY = {.56f, .72f, .75f, .14f, .14f, .28f, .28f, .43f, .57f, .57f, .71f, .35f, .35f};
        private final int[] ids = {LEFT_STICK, RIGHT_STICK, DPAD, LT, RT, 7, 8, 5, 4, 2, 1, 11, 12};
        private final float[] posX = new float[13], posY = new float[13], scale = new float[13];
        private boolean editing;
        private int selected = -1, editPointer = -1;
        private float dragX, dragY;
        private int buttons, lx = 128, ly = 128, rx = 128, ry = 128, lt, rt, hat = 8;

        private final class Contact {
            final int control;
            float x, y;
            Contact(int control, float x, float y) { this.control = control; this.x = x; this.y = y; }
        }

        PadView(Context context) {
            super(context);
            setBackgroundColor(Color.BLACK);
            android.content.SharedPreferences prefs = getSharedPreferences("gamepad_layout", MODE_PRIVATE);
            for (int i = 0; i < ids.length; i++) {
                posX[i] = prefs.getFloat("x" + i, baseX[i]);
                posY[i] = prefs.getFloat("y" + i, baseY[i]);
                scale[i] = prefs.getFloat("scale" + i, 1f);
            }
        }

        private void saveLayout() {
            android.content.SharedPreferences.Editor editor = getSharedPreferences("gamepad_layout", MODE_PRIVATE).edit();
            for (int i = 0; i < ids.length; i++) editor.putFloat("x" + i, posX[i]).putFloat("y" + i, posY[i]).putFloat("scale" + i, scale[i]);
            editor.apply();
        }

        private void beginControl(Canvas canvas, int i) {
            canvas.save();
            canvas.translate(width * posX[i], height * posY[i]);
            canvas.scale(scale[i], scale[i]);
            canvas.translate(-width * baseX[i], -height * baseY[i]);
        }

        @Override protected void onDraw(Canvas canvas) {
            super.onDraw(canvas);
            width = getWidth() - getPaddingLeft() - getPaddingRight();
            height = getHeight() - getPaddingTop() - getPaddingBottom();
            if (width <= 0 || height <= 0) return;
            unit = Math.min(width / 2.05f, height);
            canvas.save(); canvas.translate(getPaddingLeft(), getPaddingTop());
            paint.setColor(Color.WHITE); paint.setTextAlign(Paint.Align.CENTER); paint.setTextSize(unit * .08f);
            canvas.drawText("‹", width * .04f, height * .15f, paint);
            beginControl(canvas, 3); button(canvas, .19f, .14f, .095f, .065f, getString(R.string.gamepad_lt), Color.DKGRAY); canvas.restore();
            beginControl(canvas, 5); button(canvas, .19f, .28f, .095f, .075f, getString(R.string.gamepad_l1), 0xff454545); canvas.restore();
            beginControl(canvas, 4); button(canvas, .81f, .14f, .095f, .065f, getString(R.string.gamepad_rt), Color.DKGRAY); canvas.restore();
            beginControl(canvas, 6); button(canvas, .81f, .28f, .095f, .075f, getString(R.string.gamepad_r1), 0xff454545); canvas.restore();
            beginControl(canvas, 0);
            circle(canvas, .22f, .56f, .16f, 0xff292929);
            stick(canvas, .22f, .56f, lx, ly);
            canvas.restore(); beginControl(canvas, 1);
            circle(canvas, .56f, .72f, .16f, 0xff292929);
            stick(canvas, .56f, .72f, rx, ry);
            canvas.restore(); beginControl(canvas, 2); drawDpad(canvas); canvas.restore();
            beginControl(canvas, 7); face(canvas, .80f, .43f, getString(R.string.gamepad_y), 0xffffca28); canvas.restore();
            beginControl(canvas, 8); face(canvas, .75f, .57f, getString(R.string.gamepad_x), 0xff42a5f5); canvas.restore();
            beginControl(canvas, 9); face(canvas, .85f, .57f, getString(R.string.gamepad_b), 0xffef5350); canvas.restore();
            beginControl(canvas, 10); face(canvas, .80f, .71f, getString(R.string.gamepad_a), 0xff66bb6a); canvas.restore();
            beginControl(canvas, 11); button(canvas, .45f, .35f, .052f, .065f, getString(R.string.gamepad_select), 0xff454545); canvas.restore();
            beginControl(canvas, 12); button(canvas, .55f, .35f, .052f, .065f, getString(R.string.gamepad_start), 0xff454545); canvas.restore();
            button(canvas, .42f, .08f, .055f, .055f, getString(editing ? R.string.gamepad_done : R.string.gamepad_edit), 0xff454545);
            if (editing) {
                button(canvas, .55f, .08f, .035f, .055f, "−", 0xff454545);
                button(canvas, .64f, .08f, .035f, .055f, "+", 0xff454545);
                button(canvas, .76f, .08f, .065f, .055f, getString(R.string.gamepad_reset), 0xff454545);
                if (selected >= 0) {
                    paint.setColor(0xffffffff); paint.setStyle(Paint.Style.STROKE); paint.setStrokeWidth(unit * .006f);
                    canvas.drawCircle(width * posX[selected], height * posY[selected], unit * .1f * scale[selected], paint);
                    paint.setStyle(Paint.Style.FILL);
                }
            }
            canvas.restore();
        }

        private void circle(Canvas c, float x, float y, float radius, int color) {
            paint.setColor(color); c.drawCircle(width * x, height * y, unit * radius, paint);
        }
        private void stick(Canvas c, float x, float y, int ax, int ay) {
            paint.setColor(0xff606060);
            c.drawCircle(width * x + (ax - 128) / 127f * unit * .075f,
                    height * y + (ay - 128) / 127f * unit * .075f, unit * .085f, paint);
        }
        private void face(Canvas c, float x, float y, String text, int color) {
            circle(c, x, y, .065f, color);
            paint.setColor(Color.BLACK); paint.setTextAlign(Paint.Align.CENTER); paint.setTextSize(unit * .065f);
            paint.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
            c.drawText(text, width * x, height * y - (paint.ascent() + paint.descent()) / 2, paint);
            paint.setTypeface(android.graphics.Typeface.DEFAULT);
        }
        private void button(Canvas c, float x, float y, float halfWidth, float halfHeight, String text, int color) {
            float cx = width * x, cy = height * y;
            paint.setColor(color);
            c.drawRoundRect(cx - width * halfWidth, cy - height * halfHeight, cx + width * halfWidth,
                    cy + height * halfHeight, unit * .05f, unit * .05f, paint);
            paint.setColor(Color.WHITE); paint.setTextAlign(Paint.Align.CENTER); paint.setTextSize(unit * .045f);
            c.drawText(text, cx, cy - (paint.ascent() + paint.descent()) / 2, paint);
        }
        private void drawDpad(Canvas c) {
            float cx = width * .38f, cy = height * .75f, size = unit * .15f, arm = size * .34f;
            paint.setColor(0xff505050);
            c.drawRoundRect(cx - arm, cy - size, cx + arm, cy + size, arm / 2, arm / 2, paint);
            c.drawRoundRect(cx - size, cy - arm, cx + size, cy + arm, arm / 2, arm / 2, paint);
        }

        @Override public boolean onTouchEvent(MotionEvent event) {
            if (ScrcpySession.active() != session || width <= 0 || height <= 0) return false;
            int action = event.getActionMasked(), index = event.getActionIndex();
            if (action == MotionEvent.ACTION_DOWN || action == MotionEvent.ACTION_POINTER_DOWN) {
                float x = event.getX(index) - getPaddingLeft(), y = event.getY(index) - getPaddingTop();
                if (x < width * .09f && y < height * .20f) { finish(); return true; }
                if (within(x, y, .42f, .08f, .055f, .055f)) {
                    editing = !editing; reset(); selected = -1; saveLayout(); invalidate();
                    if (editing) Toast.makeText(GamepadActivity.this, R.string.gamepad_edit_hint, Toast.LENGTH_LONG).show();
                    return true;
                }
                if (editing) {
                    if (within(x, y, .55f, .08f, .035f, .055f) && selected >= 0) scale[selected] = Math.max(.6f, scale[selected] - .1f);
                    else if (within(x, y, .64f, .08f, .035f, .055f) && selected >= 0) scale[selected] = Math.min(1.5f, scale[selected] + .1f);
                    else if (within(x, y, .76f, .08f, .065f, .055f)) {
                        for (int i = 0; i < ids.length; i++) { posX[i] = baseX[i]; posY[i] = baseY[i]; scale[i] = 1; }
                        selected = -1;
                    } else {
                        selected = hitIndex(x, y);
                        if (selected >= 0) { editPointer = event.getPointerId(index); dragX = x / width - posX[selected]; dragY = y / height - posY[selected]; }
                    }
                    saveLayout(); invalidate(); return true;
                }
                int control = hit(x, y);
                if (control == EXIT) { finish(); return true; }
                if (control != 0) {
                    contacts.put(event.getPointerId(index), new Contact(control, x, y));
                    performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP);
                }
            } else if (action == MotionEvent.ACTION_MOVE) {
                if (editing) {
                    int pointer = event.findPointerIndex(editPointer);
                    if (selected >= 0 && pointer >= 0) {
                        posX[selected] = Math.max(.05f, Math.min(.95f, (event.getX(pointer) - getPaddingLeft()) / width - dragX));
                        posY[selected] = Math.max(.06f, Math.min(.94f, (event.getY(pointer) - getPaddingTop()) / height - dragY));
                        invalidate();
                    }
                    return true;
                }
                for (int i = 0; i < event.getPointerCount(); i++) {
                    Contact contact = contacts.get(event.getPointerId(i));
                    if (contact != null) { contact.x = event.getX(i) - getPaddingLeft(); contact.y = event.getY(i) - getPaddingTop(); }
                }
            } else if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_POINTER_UP) {
                if (editing) { if (event.getPointerId(index) == editPointer) { editPointer = -1; saveLayout(); } return true; }
                contacts.remove(event.getPointerId(index));
                if (action == MotionEvent.ACTION_UP) performClick();
            } else if (action == MotionEvent.ACTION_CANCEL) { contacts.clear(); editPointer = -1; if (editing) saveLayout(); }
            updateState();
            invalidate();
            return true;
        }

        @Override public boolean performClick() { super.performClick(); return true; }

        private int hit(float x, float y) {
            if (x < width * .09f && y < height * .20f) return EXIT;
            int i = hitIndex(x, y);
            return i < 0 ? 0 : ids[i];
        }
        private int hitIndex(float x, float y) {
            for (int i = ids.length - 1; i >= 0; i--) {
                float dx = (x - width * posX[i]) / scale[i], dy = (y - height * posY[i]) / scale[i];
                if (i <= 2) { if (Math.hypot(dx, dy) <= unit * (i == 2 ? .17f : .18f)) return i; }
                else if (i <= 6) { if (Math.abs(dx) <= width * .10f && Math.abs(dy) <= height * (i <= 4 ? .065f : .075f)) return i; }
                else if (i <= 10) { if (Math.hypot(dx, dy) <= unit * .085f) return i; }
                else if (Math.abs(dx) <= width * .055f && Math.abs(dy) <= height * .075f) return i;
            }
            return -1;
        }
        private boolean near(float x, float y, float cx, float cy, float radius) {
            return Math.hypot(x - width * cx, y - height * cy) <= unit * radius;
        }
        private boolean within(float x, float y, float cx, float cy, float hw, float hh) {
            return Math.abs(x - width * cx) <= width * hw && Math.abs(y - height * cy) <= height * hh;
        }
        private int axis(float delta) { return Math.max(0, Math.min(255, Math.round(128 + delta * 127))); }
        private void updateStick(Contact c, float cx, float cy, boolean left) {
            int i = left ? 0 : 1;
            float dx = (c.x - width * posX[i]) / (unit * .12f * scale[i]);
            float dy = (c.y - height * posY[i]) / (unit * .12f * scale[i]);
            float length = (float) Math.hypot(dx, dy);
            if (length > 1) { dx /= length; dy /= length; }
            if (left) { lx = axis(dx); ly = axis(dy); }
            else { rx = axis(dx); ry = axis(dy); }
        }
        private void updateState() {
            buttons = lt = rt = 0; lx = ly = rx = ry = 128; hat = 8;
            for (int i = 0; i < contacts.size(); i++) {
                Contact c = contacts.valueAt(i);
                if (c.control >= 1 && c.control <= 16) buttons |= 1 << (c.control - 1);
                else if (c.control == LT) lt = 255;
                else if (c.control == RT) rt = 255;
                else if (c.control == LEFT_STICK) updateStick(c, .22f, .56f, true);
                else if (c.control == RIGHT_STICK) updateStick(c, .56f, .72f, false);
                else if (c.control == DPAD) {
                    float dx = c.x - width * posX[2], dy = c.y - height * posY[2];
                    if (Math.hypot(dx, dy) > unit * .025f * scale[2]) {
                        double angle = Math.atan2(dx, -dy);
                        hat = ((int) Math.round(angle / (Math.PI / 4)) + 8) % 8;
                    }
                }
            }
            session.sendGamepadState(buttons, lx, ly, rx, ry, lt, rt, hat);
        }
        void reset() { contacts.clear(); updateState(); invalidate(); }
    }
}
