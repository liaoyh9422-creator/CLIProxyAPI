package com.cliproxy.app.tabs;

import android.content.Context;
import android.graphics.Color;
import android.graphics.Typeface;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import com.cliproxy.app.ui.UiTheme;

import java.util.Locale;

/**
 * LogHelper: 提供通用的日志时间清洗、色彩语法高亮与条目滚动渲染机制
 */
public class LogHelper {

    public static String stripDateTime(String line) {
        if (line == null) return "";
        String res = line;
        res = res.replaceFirst("^\\[?\\d{4}[-/]\\d{2}[-/]\\d{2}[ T]\\d{2}:\\d{2}:\\d{2}(\\.\\d+)?(Z|[+-]\\d{2}:?\\d{2})?\\]?\\s*", "");
        res = res.replaceFirst("^\\[?\\d{2}:\\d{2}:\\d{2}(\\.\\d+)?\\]?\\s*", "");
        res = res.replaceFirst("^\\[-+\\]\\s*", "");
        res = res.replace("[info ]", "[info]");
        return res;
    }

    public static int getLogColor(String line, int index) {
        String lower = line.toLowerCase(Locale.ROOT);
        if (lower.contains("error") || lower.contains("fail") || lower.contains("exception") || line.contains("❌") || line.contains("!!!")) {
            return Color.parseColor("#FF7B72");
        }
        if (lower.contains("warn") || line.contains("⚠")) {
            return Color.parseColor("#FFA657");
        }
        if (lower.contains("started") || lower.contains("listening") || lower.contains("success") || line.contains("✅") || line.contains("===") || line.contains("⚡")) {
            return Color.parseColor("#7EE787");
        }
        return (index % 2 == 0) ? Color.parseColor("#79C0FF") : Color.parseColor("#E6EDF3");
    }

    public static void appendStyledLog(Context context, LinearLayout container, StringBuilder fullLog, ScrollView sv, String rawLine, int index) {
        String cleanLine = stripDateTime(rawLine);
        if (cleanLine.trim().isEmpty() && rawLine.trim().isEmpty()) return;

        fullLog.append(cleanLine).append("\n");
        if (fullLog.length() > 300_000) {
            fullLog.delete(0, 100_000);
        }

        int color = getLogColor(cleanLine, index);

        if (container.getChildCount() > 0) {
            View div = new View(context);
            div.setBackgroundColor(Color.parseColor("#1C232E"));
            LinearLayout.LayoutParams dlp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, UiTheme.dp(context, 1));
            dlp.topMargin = UiTheme.dp(context, 3);
            dlp.bottomMargin = UiTheme.dp(context, 3);
            container.addView(div, dlp);
        }

        TextView tv = new TextView(context);
        tv.setText(cleanLine);
        tv.setTextSize(10.5f);
        tv.setTextColor(color);
        tv.setTypeface(Typeface.MONOSPACE);
        tv.setPadding(UiTheme.dp(context, 1), 0, UiTheme.dp(context, 1), 0);
        container.addView(tv);

        if (container.getChildCount() > 600) {
            container.removeViews(0, 100);
        }

        if (sv != null) {
            sv.post(() -> sv.fullScroll(ScrollView.FOCUS_DOWN));
        }
    }
}
