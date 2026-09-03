package com.cliproxy.app.ui;

import android.content.Context;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.view.Gravity;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;

/**
 * 界面主题与控件工厂：提供暗黑极客风格色值、圆角背景以及微胶囊按键组件
 */
public class UiTheme {
    // 基础色板常量
    public static final String C_BG          = "#0D1117";
    public static final String C_SURFACE     = "#161B22";
    public static final String C_SURFACE_ALT = "#21262D";
    public static final String C_BORDER      = "#30363D";
    public static final String C_BORDER_SUB  = "#21262D";
    public static final String C_TEXT        = "#E6EDF3";
    public static final String C_DIM         = "#8B949E";

    // 状态与指示色
    public static final String C_GREEN       = "#2EA043";
    public static final String C_BLUE        = "#58A6FF";
    public static final String C_PURPLE      = "#BC8CFF";
    public static final String C_RED         = "#DA3633";
    public static final String C_CYAN        = "#39C5CF";
    public static final String C_YELLOW      = "#D29922";

    /** dp 转 px 换算 */
    public static int dp(Context ctx, int s) {
        return (int) (s * ctx.getResources().getDisplayMetrics().density + 0.5f);
    }

    /** 创建统一圆角带描边背景 Drawable */
    public static GradientDrawable roundRect(Context ctx, String bgColor, String strokeColor, int strokeWidthDp, int radiusDp) {
        GradientDrawable gd = new GradientDrawable();
        if (bgColor != null) gd.setColor(Color.parseColor(bgColor));
        if (strokeColor != null && strokeWidthDp > 0) {
            gd.setStroke(dp(ctx, strokeWidthDp), Color.parseColor(strokeColor));
        }
        gd.setCornerRadius(dp(ctx, radiusDp));
        return gd;
    }

    /** 创建轻量微胶囊按键（规避系统 Button 48dp 强制内边距限制） */
    public static TextView createButton(Context ctx, String text, String textColor, String bgColor, String strokeColor, int radiusDp) {
        TextView btn = new TextView(ctx);
        btn.setText(text);
        btn.setTextSize(11.5f);
        btn.setTextColor(Color.parseColor(textColor));
        btn.setBackground(roundRect(ctx, bgColor, strokeColor, 1, radiusDp > 0 ? radiusDp : 5));
        btn.setPadding(dp(ctx, 10), dp(ctx, 5), dp(ctx, 10), dp(ctx, 5));
        btn.setGravity(Gravity.CENTER);
        btn.setClickable(true);
        btn.setFocusable(true);
        btn.setIncludeFontPadding(false);
        return btn;
    }

    /** 创建统一风格的区块分类标题行 */
    public static View buildSectionHeader(Context ctx, String title, String subtitle) {
        LinearLayout row = new LinearLayout(ctx);
        row.setOrientation(LinearLayout.VERTICAL);
        row.setPadding(dp(ctx, 2), dp(ctx, 12), dp(ctx, 2), dp(ctx, 4));

        TextView tv = new TextView(ctx);
        tv.setText(title);
        tv.setTextSize(13);
        tv.setTextColor(Color.parseColor(C_DIM));
        tv.setTypeface(Typeface.DEFAULT_BOLD);
        row.addView(tv);

        if (subtitle != null && !subtitle.isEmpty()) {
            TextView sub = new TextView(ctx);
            sub.setText(subtitle);
            sub.setTextSize(11);
            sub.setTextColor(Color.parseColor(C_DIM));
            sub.setAlpha(0.6f);
            sub.setPadding(0, dp(ctx, 2), 0, 0);
            row.addView(sub);
        }
        return row;
    }
}
