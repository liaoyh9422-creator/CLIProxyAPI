package com.cliproxy.app.tabs;

import android.app.Dialog;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.ColorDrawable;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import com.cliproxy.app.MainActivity;
import com.cliproxy.app.ui.UiTheme;
import com.cliproxy.core.metrics.MetricsTracker;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * MetricsTab: Tab 3 流量与运维统计仪表控制台
 * 包含核心六宫格指标、实时多模型Token消耗统计与最近访问审计异常排查
 */
public class MetricsTab {

    private final MainActivity activity;
    private View rootView;

    private TextView metricTotalRequests;
    private TextView metricFailedRequests;
    private TextView metricAvgLatency;
    private TextView metricTotalTokens;
    private TextView metricUptime;
    private LinearLayout metricsLogList;
    private int auditFilterMode = 0; // 0: 全部, 1: 仅失败, 2: 仅成功
    private final TextView[] auditFilterPills = new TextView[3];

    public MetricsTab(MainActivity activity) {
        this.activity = activity;
    }

    public View createView() {
        LinearLayout layout = new LinearLayout(activity);
        layout.setOrientation(LinearLayout.VERTICAL);
        layout.setLayoutParams(new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        layout.setPadding(UiTheme.dp(activity, 12), UiTheme.dp(activity, 8), UiTheme.dp(activity, 12), UiTheme.dp(activity, 4));

        layout.addView(UiTheme.buildSectionHeader(activity, "流量与运维监控", "核心调用吞吐指标、多模型实时Token统计与访问异常排查"));
        layout.addView(buildKpiGridSection());

        View auditCard = buildAuditSection();
        LinearLayout.LayoutParams alp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f);
        alp.topMargin = UiTheme.dp(activity, 6);
        auditCard.setLayoutParams(alp);
        layout.addView(auditCard);

        rootView = layout;
        refreshMetricsView();
        return rootView;
    }

    public View getView() {
        return rootView;
    }

    /** 核心指标六宫格卡片 */
    private View buildKpiGridSection() {
        LinearLayout card = new LinearLayout(activity);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setBackground(UiTheme.roundRect(activity, UiTheme.C_SURFACE, UiTheme.C_BORDER, 1, 6));
        card.setPadding(UiTheme.dp(activity, 10), UiTheme.dp(activity, 8), UiTheme.dp(activity, 10), UiTheme.dp(activity, 8));

        // 第 1 行: 今日请求数 + 失败请求 + 平均延迟
        LinearLayout row1 = new LinearLayout(activity);
        row1.setOrientation(LinearLayout.HORIZONTAL);

        View kpi1 = createKpiItem("今日请求量", "0", UiTheme.C_BLUE);
        metricTotalRequests = kpi1.findViewById(android.R.id.text1);
        row1.addView(kpi1, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        View kpi2 = createKpiItem("失败请求", "0 (0.0%)", UiTheme.C_GREEN);
        metricFailedRequests = kpi2.findViewById(android.R.id.text1);
        row1.addView(kpi2, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        View kpi3 = createKpiItem("平均延迟", "0 ms", UiTheme.C_CYAN);
        metricAvgLatency = kpi3.findViewById(android.R.id.text1);
        row1.addView(kpi3, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        card.addView(row1);

        View div = new View(activity);
        div.setBackgroundColor(Color.parseColor(UiTheme.C_BORDER_SUB));
        LinearLayout.LayoutParams dlp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, UiTheme.dp(activity, 1));
        dlp.topMargin = UiTheme.dp(activity, 6);
        dlp.bottomMargin = UiTheme.dp(activity, 6);
        card.addView(div, dlp);

        // 第 2 行: Token总消耗 + 节省Token + 运行时长
        LinearLayout row2 = new LinearLayout(activity);
        row2.setOrientation(LinearLayout.HORIZONTAL);

        View kpi4 = createKpiItem("Token 总消耗", "0", "#E3B341");
        metricTotalTokens = kpi4.findViewById(android.R.id.text1);
        row2.addView(kpi4, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        View kpi5 = createKpiItem("网关端口", ":8317", UiTheme.C_CYAN);
        row2.addView(kpi5, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        View kpi6 = createKpiItem("已运行时长", "未运行", UiTheme.C_PURPLE);
        metricUptime = kpi6.findViewById(android.R.id.text1);
        row2.addView(kpi6, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        card.addView(row2);

        return card;
    }

    private View createKpiItem(String title, String defaultValue, String accentColor) {
        LinearLayout box = new LinearLayout(activity);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(UiTheme.dp(activity, 4), UiTheme.dp(activity, 2), UiTheme.dp(activity, 4), UiTheme.dp(activity, 2));

        TextView tvTitle = new TextView(activity);
        tvTitle.setText(title);
        tvTitle.setTextSize(10);
        tvTitle.setTextColor(Color.parseColor(UiTheme.C_DIM));
        box.addView(tvTitle);

        TextView tvVal = new TextView(activity);
        tvVal.setId(android.R.id.text1);
        tvVal.setText(defaultValue);
        tvVal.setTextSize(14);
        tvVal.setTypeface(Typeface.DEFAULT_BOLD);
        tvVal.setTextColor(Color.parseColor(accentColor));
        tvVal.setPadding(0, UiTheme.dp(activity, 2), 0, 0);
        box.addView(tvVal);

        return box;
    }

    /** 访问审计流水区（带筛选胶囊） */
    private View buildAuditSection() {
        LinearLayout card = new LinearLayout(activity);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setBackground(UiTheme.roundRect(activity, UiTheme.C_SURFACE, UiTheme.C_BORDER, 1, 6));
        card.setPadding(UiTheme.dp(activity, 10), UiTheme.dp(activity, 6), UiTheme.dp(activity, 10), UiTheme.dp(activity, 6));

        LinearLayout top = new LinearLayout(activity);
        top.setOrientation(LinearLayout.HORIZONTAL);
        top.setGravity(Gravity.CENTER_VERTICAL);
        top.setPadding(0, 0, 0, UiTheme.dp(activity, 4));

        TextView title = new TextView(activity);
        title.setText("最近访问审计");
        title.setTextSize(11.5f);
        title.setTextColor(Color.parseColor(UiTheme.C_TEXT));
        title.setTypeface(Typeface.DEFAULT_BOLD);
        top.addView(title, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        // 筛选胶囊：全部 / 仅看失败 / 仅看成功
        String[] filterTitles = {"全部", "🔴 仅失败", "🟢 仅成功"};
        for (int i = 0; i < 3; i++) {
            final int fIdx = i;
            boolean active = (auditFilterMode == i);
            TextView pill = UiTheme.createButton(activity, filterTitles[i],
                    active ? UiTheme.C_GREEN : UiTheme.C_DIM,
                    active ? "#0D2818" : UiTheme.C_SURFACE_ALT,
                    active ? UiTheme.C_GREEN : UiTheme.C_BORDER, 3);
            pill.setTextSize(9f);
            pill.setPadding(UiTheme.dp(activity, 5), UiTheme.dp(activity, 2), UiTheme.dp(activity, 5), UiTheme.dp(activity, 2));
            LinearLayout.LayoutParams plp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            plp.leftMargin = UiTheme.dp(activity, 4);
            pill.setLayoutParams(plp);
            pill.setOnClickListener(v -> {
                auditFilterMode = fIdx;
                updateAuditFilterPills();
                refreshMetricsView();
            });
            auditFilterPills[i] = pill;
            top.addView(pill);
        }

        TextView refreshBtn = UiTheme.createButton(activity, "⟳", UiTheme.C_TEXT, UiTheme.C_SURFACE_ALT, UiTheme.C_BORDER, 3);
        LinearLayout.LayoutParams rfp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        rfp.leftMargin = UiTheme.dp(activity, 4);
        refreshBtn.setLayoutParams(rfp);
        refreshBtn.setOnClickListener(v -> refreshMetricsView());
        top.addView(refreshBtn);

        TextView resetBtn = UiTheme.createButton(activity, "重置", UiTheme.C_DIM, UiTheme.C_SURFACE_ALT, UiTheme.C_BORDER, 3);
        LinearLayout.LayoutParams rlp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        rlp.leftMargin = UiTheme.dp(activity, 4);
        resetBtn.setLayoutParams(rlp);
        resetBtn.setOnClickListener(v -> {
            MetricsTracker.getInstance().reset();
            refreshMetricsView();
            Toast.makeText(activity, "统计指标已重置", Toast.LENGTH_SHORT).show();
        });
        top.addView(resetBtn);
        card.addView(top);

        ScrollView scroll = new ScrollView(activity);
        scroll.setBackgroundColor(Color.parseColor("#080B0F"));
        scroll.setFillViewport(true);
        scroll.setLayoutParams(new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        metricsLogList = new LinearLayout(activity);
        metricsLogList.setOrientation(LinearLayout.VERTICAL);
        metricsLogList.setPadding(UiTheme.dp(activity, 6), UiTheme.dp(activity, 4), UiTheme.dp(activity, 6), UiTheme.dp(activity, 4));
        scroll.addView(metricsLogList);

        card.addView(scroll);
        return card;
    }

    private void updateAuditFilterPills() {
        for (int i = 0; i < 3; i++) {
            if (auditFilterPills[i] != null) {
                boolean active = (auditFilterMode == i);
                auditFilterPills[i].setTextColor(Color.parseColor(active ? UiTheme.C_GREEN : UiTheme.C_DIM));
                auditFilterPills[i].setBackground(UiTheme.roundRect(activity,
                        active ? "#0D2818" : UiTheme.C_SURFACE_ALT,
                        active ? UiTheme.C_GREEN : UiTheme.C_BORDER, 1, 3));
            }
        }
    }

    public void refreshMetricsView() {
        MetricsTracker mt = MetricsTracker.getInstance();
        if (metricTotalRequests != null) {
            metricTotalRequests.setText(String.valueOf(mt.getTotalRequests()));
        }
        if (metricFailedRequests != null) {
            long fails = mt.getFailedRequests();
            float rate = mt.getFailureRate();
            if (fails > 0) {
                metricFailedRequests.setText(String.format(Locale.getDefault(), "%d (%.1f%%)", fails, rate));
                metricFailedRequests.setTextColor(Color.parseColor(UiTheme.C_RED));
            } else {
                metricFailedRequests.setText("0 (0.0%)");
                metricFailedRequests.setTextColor(Color.parseColor(UiTheme.C_GREEN));
            }
        }
        if (metricAvgLatency != null) {
            metricAvgLatency.setText(mt.getAverageLatencyMs() + " ms");
        }
        if (metricTotalTokens != null) {
            metricTotalTokens.setText(mt.getFormattedTotalTokens());
        }
        if (metricUptime != null) {
            metricUptime.setText(mt.getFormattedUptime());
        }

        if (metricsLogList != null) {
            metricsLogList.removeAllViews();
            List<MetricsTracker.RequestRecord> rawList = mt.getRecentRecords();
            List<MetricsTracker.RequestRecord> list = new ArrayList<>();
            for (MetricsTracker.RequestRecord r : rawList) {
                if (auditFilterMode == 1 && r.isSuccess()) continue; // 仅看失败
                if (auditFilterMode == 2 && !r.isSuccess()) continue; // 仅看成功
                list.add(r);
            }

            if (list.isEmpty()) {
                TextView empty = new TextView(activity);
                empty.setText(auditFilterMode == 1 ? "暂无失败记录 (大模型请求全部正常)" :
                             (auditFilterMode == 2 ? "暂无成功请求记录" :
                             "暂无调用记录（启动服务后通过客户端发起请求即可记录）"));
                empty.setTextSize(10.5f);
                empty.setTextColor(Color.parseColor(UiTheme.C_DIM));
                empty.setPadding(UiTheme.dp(activity, 4), UiTheme.dp(activity, 8), UiTheme.dp(activity, 4), UiTheme.dp(activity, 8));
                metricsLogList.addView(empty);
                return;
            }

            for (MetricsTracker.RequestRecord rec : list) {
                LinearLayout row = new LinearLayout(activity);
                row.setOrientation(LinearLayout.HORIZONTAL);
                row.setGravity(Gravity.CENTER_VERTICAL);
                row.setPadding(UiTheme.dp(activity, 2), UiTheme.dp(activity, 3), UiTheme.dp(activity, 2), UiTheme.dp(activity, 3));
                row.setOnClickListener(v -> showRequestDetailsDialog(rec));

                // 状态码徽章
                TextView badge = new TextView(activity);
                if (rec.isCacheHit) {
                    badge.setText("⚡200 CACHE");
                    badge.setTextColor(Color.parseColor(UiTheme.C_CYAN));
                    badge.setBackground(UiTheme.roundRect(activity, "#0A2328", UiTheme.C_CYAN, 1, 2));
                } else if (rec.statusCode == 429) {
                    badge.setText("429 限流");
                    badge.setTextColor(Color.parseColor("#E3B341"));
                    badge.setBackground(UiTheme.roundRect(activity, "#28200A", "#E3B341", 1, 2));
                } else if (rec.statusCode == 401 || rec.statusCode == 403) {
                    badge.setText(rec.statusCode + " 未授权");
                    badge.setTextColor(Color.parseColor(UiTheme.C_RED));
                    badge.setBackground(UiTheme.roundRect(activity, "#2D1214", UiTheme.C_RED, 1, 2));
                } else if (rec.statusCode >= 500) {
                    badge.setText(rec.statusCode + " 错误");
                    badge.setTextColor(Color.parseColor(UiTheme.C_RED));
                    badge.setBackground(UiTheme.roundRect(activity, "#2D1214", UiTheme.C_RED, 1, 2));
                } else {
                    badge.setText(rec.statusCode + " OK");
                    badge.setTextColor(Color.parseColor(UiTheme.C_GREEN));
                    badge.setBackground(UiTheme.roundRect(activity, "#0D2818", UiTheme.C_GREEN, 1, 2));
                }
                badge.setTextSize(9);
                badge.setTypeface(Typeface.MONOSPACE);
                badge.setPadding(UiTheme.dp(activity, 4), UiTheme.dp(activity, 1), UiTheme.dp(activity, 4), UiTheme.dp(activity, 1));
                row.addView(badge);

                // 延迟
                TextView tvLat = new TextView(activity);
                tvLat.setText(" " + rec.latency);
                tvLat.setTextSize(9.5f);
                tvLat.setTextColor(Color.parseColor(rec.isCacheHit ? UiTheme.C_GREEN : UiTheme.C_CYAN));
                tvLat.setTypeface(Typeface.MONOSPACE);
                row.addView(tvLat);

                // 路径
                TextView tvPath = new TextView(activity);
                tvPath.setText(" " + rec.method + " " + rec.path);
                tvPath.setTextSize(9.5f);
                tvPath.setTextColor(Color.parseColor(rec.isSuccess() ? UiTheme.C_TEXT : "#FF7B72"));
                tvPath.setLayoutParams(new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
                tvPath.setSingleLine(true);
                tvPath.setEllipsize(android.text.TextUtils.TruncateAt.MIDDLE);
                row.addView(tvPath);

                // 时间
                TextView tvTime = new TextView(activity);
                tvTime.setText(rec.time);
                tvTime.setTextSize(9);
                tvTime.setTextColor(Color.parseColor(UiTheme.C_DIM));
                tvTime.setTypeface(Typeface.MONOSPACE);
                tvTime.setPadding(UiTheme.dp(activity, 4), 0, 0, 0);
                row.addView(tvTime);

                metricsLogList.addView(row);

                // 分割线
                View div = new View(activity);
                div.setBackgroundColor(Color.parseColor("#151B23"));
                metricsLogList.addView(div, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, UiTheme.dp(activity, 1)));
            }
        }
    }

    /** 弹窗展示请求完整诊断审计详情 */
    private void showRequestDetailsDialog(MetricsTracker.RequestRecord rec) {
        Dialog dialog = new Dialog(activity);
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);

        LinearLayout root = new LinearLayout(activity);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackground(UiTheme.roundRect(activity, UiTheme.C_SURFACE, UiTheme.C_BORDER, 1, 8));
        root.setPadding(UiTheme.dp(activity, 14), UiTheme.dp(activity, 12), UiTheme.dp(activity, 14), UiTheme.dp(activity, 12));

        TextView title = new TextView(activity);
        title.setText("📋 请求访问详情审计");
        title.setTextSize(12.5f);
        title.setTextColor(Color.parseColor(UiTheme.C_TEXT));
        title.setTypeface(Typeface.DEFAULT_BOLD);
        root.addView(title);

        LinearLayout card = new LinearLayout(activity);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setBackground(UiTheme.roundRect(activity, "#080B0F", UiTheme.C_BORDER, 1, 6));
        card.setPadding(UiTheme.dp(activity, 10), UiTheme.dp(activity, 6), UiTheme.dp(activity, 10), UiTheme.dp(activity, 6));
        LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        clp.topMargin = UiTheme.dp(activity, 8);
        card.setLayoutParams(clp);

        card.addView(buildDetailRow("请求时间", rec.time));
        card.addView(buildDetailRow("响应状态", rec.statusCode + " (" + rec.errorHint + ")"));
        card.addView(buildDetailRow("客户端 IP", rec.clientIp));
        card.addView(buildDetailRow("HTTP 方法", rec.method));
        card.addView(buildDetailRow("请求接口", rec.path));
        card.addView(buildDetailRow("往返延迟", rec.latency));
        card.addView(buildDetailRow("缓存状态", rec.isCacheHit ? "⚡ 命中智能缓存 (5ms 秒回)" : "全量直通模型"));
        root.addView(card);

        LinearLayout btnRow = new LinearLayout(activity);
        btnRow.setOrientation(LinearLayout.HORIZONTAL);
        btnRow.setGravity(Gravity.RIGHT);
        LinearLayout.LayoutParams blp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        blp.topMargin = UiTheme.dp(activity, 10);
        btnRow.setLayoutParams(blp);

        TextView copyBtn = UiTheme.createButton(activity, "复制诊断", UiTheme.C_CYAN, "#0A2328", UiTheme.C_CYAN, 3);
        copyBtn.setOnClickListener(v -> {
            String info = "时间: " + rec.time + "\n状态: " + rec.statusCode + " (" + rec.errorHint + ")\nIP: " + rec.clientIp + "\n接口: " + rec.method + " " + rec.path + "\n延迟: " + rec.latency;
            activity.copyToClipboard("请求诊断", info);
            dialog.dismiss();
        });
        btnRow.addView(copyBtn);

        TextView closeBtn = UiTheme.createButton(activity, "关闭", UiTheme.C_DIM, UiTheme.C_SURFACE_ALT, UiTheme.C_BORDER, 3);
        LinearLayout.LayoutParams clpBtn = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        clpBtn.leftMargin = UiTheme.dp(activity, 6);
        closeBtn.setLayoutParams(clpBtn);
        closeBtn.setOnClickListener(v -> dialog.dismiss());
        btnRow.addView(closeBtn);

        root.addView(btnRow);

        dialog.setContentView(root);
        if (dialog.getWindow() != null) {
            dialog.getWindow().setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
            dialog.getWindow().setLayout((int) (activity.getResources().getDisplayMetrics().widthPixels * 0.9), ViewGroup.LayoutParams.WRAP_CONTENT);
        }
        dialog.show();
    }

    private View buildDetailRow(String k, String v) {
        LinearLayout row = new LinearLayout(activity);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setPadding(0, UiTheme.dp(activity, 3), 0, UiTheme.dp(activity, 3));

        TextView tvK = new TextView(activity);
        tvK.setText(k + "：");
        tvK.setTextSize(10f);
        tvK.setTextColor(Color.parseColor(UiTheme.C_DIM));
        tvK.setLayoutParams(new LinearLayout.LayoutParams(UiTheme.dp(activity, 70), ViewGroup.LayoutParams.WRAP_CONTENT));
        row.addView(tvK);

        TextView tvV = new TextView(activity);
        tvV.setText(v);
        tvV.setTextSize(10f);
        tvV.setTextColor(Color.parseColor(UiTheme.C_TEXT));
        tvV.setTypeface(Typeface.MONOSPACE);
        tvV.setLayoutParams(new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        row.addView(tvV);

        return row;
    }
}
