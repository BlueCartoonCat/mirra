package io.github.bluecartooncat.mirra;

import android.content.res.ColorStateList;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.util.TypedValue;
import android.view.View;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import androidx.activity.OnBackPressedCallback;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowCompat;
import androidx.core.view.WindowInsetsCompat;

import com.google.android.material.button.MaterialButton;
import com.google.android.material.color.DynamicColors;

/** Full-screen introduction, also available from the main menu. */
public class OnboardingActivity extends AppCompatActivity {
    private static final int[] TITLES = {R.string.onboarding_welcome, R.string.onboarding_setup,
            R.string.onboarding_troubleshooting, R.string.onboarding_modes, R.string.onboarding_controls,
            R.string.onboarding_credits};
    private static final int[] DETAILS = {R.string.onboarding_welcome_detail, R.string.onboarding_setup_detail,
            R.string.onboarding_troubleshooting_detail, R.string.onboarding_modes_detail, R.string.onboarding_controls_detail,
            R.string.onboarding_credits_detail};
    private static final int[] ICONS = {R.drawable.ic_cast_monitor, R.drawable.ic_onboarding_shizuku,
            R.drawable.ic_onboarding_connection, R.drawable.ic_cast_monitor, R.drawable.ic_controller_gamepad,
            R.drawable.ic_onboarding_code};
    private int page, primary, surfaceVariant, onSurface;
    private LinearLayout content, dots, modeCards;
    private ScrollView scroll;
    private ImageView icon;
    private TextView title, detail, step;
    private MaterialButton next, previous;

    @Override protected void onCreate(Bundle state) {
        DynamicColors.applyToActivityIfAvailable(this);
        super.onCreate(state);
        page = state == null ? 0 : Math.max(0, Math.min(TITLES.length - 1, state.getInt("page")));
        primary = color(androidx.appcompat.R.attr.colorPrimary);
        surfaceVariant = color(com.google.android.material.R.attr.colorSecondaryContainer);
        onSurface = color(com.google.android.material.R.attr.colorOnSurface);
        WindowCompat.setDecorFitsSystemWindows(getWindow(), false);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(color(com.google.android.material.R.attr.colorSurface));
        setContentView(root);
        ViewCompat.setOnApplyWindowInsetsListener(root, (view, insets) -> {
            Insets safe = insets.getInsets(WindowInsetsCompat.Type.systemBars() | WindowInsetsCompat.Type.displayCutout());
            view.setPadding(safe.left + dp(24), safe.top + dp(12), safe.right + dp(24), safe.bottom + dp(16));
            return insets;
        });
        ViewCompat.requestApplyInsets(root);

        LinearLayout header = new LinearLayout(this);
        header.setGravity(android.view.Gravity.CENTER_VERTICAL);
        step = new TextView(this);
        step.setTextColor(primary);
        step.setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_LabelLarge);
        header.addView(step, new LinearLayout.LayoutParams(0, -2, 1));
        MaterialButton skip = new MaterialButton(this);
        skip.setText(R.string.onboarding_skip);
        skip.setOnClickListener(v -> complete());
        header.addView(skip);
        root.addView(header, new LinearLayout.LayoutParams(-1, -2));

        scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        root.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1));
        content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setGravity(android.view.Gravity.CENTER_VERTICAL);
        content.setPadding(0, dp(20), 0, dp(20));
        scroll.addView(content, new ScrollView.LayoutParams(-1, -2));

        icon = new ImageView(this);
        icon.setImageTintList(ColorStateList.valueOf(primary));
        icon.setScaleType(ImageView.ScaleType.FIT_CENTER);
        icon.setPadding(dp(28), dp(28), dp(28), dp(28));
        GradientDrawable circle = new GradientDrawable();
        circle.setShape(GradientDrawable.OVAL);
        circle.setColor(surfaceVariant);
        icon.setBackground(circle);
        icon.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        LinearLayout.LayoutParams iconParams = new LinearLayout.LayoutParams(dp(112), dp(112));
        iconParams.bottomMargin = dp(28);
        content.addView(icon, iconParams);

        title = new TextView(this);
        title.setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_HeadlineLarge);
        title.setTextColor(onSurface);
        content.addView(title);
        detail = new TextView(this);
        detail.setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_BodyLarge);
        detail.setTextColor(onSurface);
        detail.setLineSpacing(dp(3), 1f);
        detail.setMovementMethod(android.text.method.LinkMovementMethod.getInstance());
        detail.setLinkTextColor(primary);
        LinearLayout.LayoutParams detailParams = new LinearLayout.LayoutParams(-1, -2);
        detailParams.topMargin = dp(16);
        content.addView(detail, detailParams);
        modeCards = new LinearLayout(this);
        modeCards.setOrientation(LinearLayout.VERTICAL);
        modeCards.addView(modeCard(R.drawable.ic_cast_phone, R.string.onboarding_current_title, R.string.onboarding_current_detail));
        modeCards.addView(modeCard(R.drawable.ic_cast_monitor, R.string.onboarding_external_title, R.string.onboarding_external_detail));
        content.addView(modeCards, new LinearLayout.LayoutParams(-1, -2));

        dots = new LinearLayout(this);
        dots.setGravity(android.view.Gravity.CENTER);
        LinearLayout.LayoutParams dotsParams = new LinearLayout.LayoutParams(-1, dp(32));
        root.addView(dots, dotsParams);
        LinearLayout actions = new LinearLayout(this);
        actions.setGravity(android.view.Gravity.CENTER_VERTICAL);
        previous = new MaterialButton(this, null, com.google.android.material.R.attr.materialButtonOutlinedStyle);
        previous.setText(R.string.onboarding_previous);
        previous.setOnClickListener(v -> showPage(page - 1, true));
        actions.addView(previous, new LinearLayout.LayoutParams(0, dp(52), 1));
        View space = new View(this);
        actions.addView(space, new LinearLayout.LayoutParams(dp(12), 1));
        next = new MaterialButton(this);
        next.setOnClickListener(v -> { if (page == TITLES.length - 1) complete(); else showPage(page + 1, true); });
        actions.addView(next, new LinearLayout.LayoutParams(0, dp(52), 1));
        root.addView(actions);
        getOnBackPressedDispatcher().addCallback(this, new OnBackPressedCallback(true) {
            @Override public void handleOnBackPressed() { if (page > 0) showPage(page - 1, true); else complete(); }
        });
        showPage(page, false);
    }

    private void showPage(int target, boolean animate) {
        page = target;
        content.animate().cancel();
        icon.setImageTintList(ColorStateList.valueOf(primary));
        icon.setPadding(dp(28), dp(28), dp(28), dp(28));
        if (page == 0) {
            icon.setImageTintList(null);
            icon.setPadding(dp(12), dp(12), dp(12), dp(12));
            icon.setImageDrawable(getApplicationInfo().loadIcon(getPackageManager()));
        } else if (page == 1) {
            icon.setImageTintList(null);
            icon.setPadding(dp(12), dp(12), dp(12), dp(12));
            icon.setImageResource(R.drawable.shizuku_logo);
        } else {
            if (page == 3) icon.setPadding(dp(18), dp(18), dp(18), dp(18));
            icon.setImageResource(ICONS[page]);
        }
        title.setText(TITLES[page]);
        detail.setText(DETAILS[page]);
        detail.setVisibility(page == 3 ? View.GONE : View.VISIBLE);
        modeCards.setVisibility(page == 3 ? View.VISIBLE : View.GONE);
        step.setText(getString(R.string.onboarding_step, page + 1, TITLES.length));
        previous.setVisibility(page == 0 ? View.INVISIBLE : View.VISIBLE);
        next.setText(page == TITLES.length - 1 ? R.string.onboarding_finish : R.string.onboarding_next);
        dots.removeAllViews();
        for (int i = 0; i < TITLES.length; i++) {
            View dot = new View(this);
            GradientDrawable shape = new GradientDrawable();
            shape.setCornerRadius(dp(8));
            shape.setColor(i == page ? primary : surfaceVariant);
            dot.setBackground(shape);
            LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(dp(i == page ? 24 : 8), dp(8));
            params.setMargins(dp(3), 0, dp(3), 0);
            dots.addView(dot, params);
        }
        scroll.scrollTo(0, 0);
        content.setAlpha(animate ? 0f : 1f);
        content.setTranslationX(animate ? dp(20) : 0);
        if (animate) content.animate().alpha(1f).translationX(0).setDuration(220).start();
    }

    private View modeCard(int image, int heading, int description) {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.HORIZONTAL);
        card.setPadding(dp(16), dp(16), dp(16), dp(16));
        GradientDrawable background = new GradientDrawable();
        background.setColor(surfaceVariant);
        background.setCornerRadius(dp(20));
        card.setBackground(background);
        LinearLayout.LayoutParams cardParams = new LinearLayout.LayoutParams(-1, -2);
        cardParams.topMargin = dp(16);
        card.setLayoutParams(cardParams);
        ImageView imageView = new ImageView(this);
        imageView.setImageResource(image);
        imageView.setImageTintList(ColorStateList.valueOf(primary));
        imageView.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        card.addView(imageView, new LinearLayout.LayoutParams(dp(32), dp(32)));
        LinearLayout text = new LinearLayout(this);
        text.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams textParams = new LinearLayout.LayoutParams(0, -2, 1);
        textParams.leftMargin = dp(16);
        card.addView(text, textParams);
        TextView cardTitle = new TextView(this);
        cardTitle.setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_TitleMedium);
        cardTitle.setTextColor(color(com.google.android.material.R.attr.colorOnSecondaryContainer));
        cardTitle.setText(heading);
        text.addView(cardTitle);
        TextView body = new TextView(this);
        body.setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_BodyMedium);
        body.setTextColor(color(com.google.android.material.R.attr.colorOnSecondaryContainer));
        body.setText(description);
        LinearLayout.LayoutParams bodyParams = new LinearLayout.LayoutParams(-1, -2);
        bodyParams.topMargin = dp(6);
        text.addView(body, bodyParams);
        return card;
    }

    private void complete() {
        getSharedPreferences("onboarding", MODE_PRIVATE).edit().putBoolean("complete", true).apply();
        finish();
    }

    @Override protected void onSaveInstanceState(Bundle out) {
        out.putInt("page", page);
        super.onSaveInstanceState(out);
    }

    private int color(int attribute) {
        TypedValue value = new TypedValue();
        getTheme().resolveAttribute(attribute, value, true);
        return value.data;
    }
    private int dp(int value) { return (int) (value * getResources().getDisplayMetrics().density + .5f); }
}
