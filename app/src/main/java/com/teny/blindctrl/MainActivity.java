package com.teny.blindctrl;

import android.Manifest;
import android.app.AlertDialog;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.PowerManager;
import android.provider.Settings;
import android.text.InputType;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

public class MainActivity extends android.app.Activity
        implements CtrlBus.Listener, LinkClient.Listener, VideoClient.Listener {

    private static final int REQ_NOTIFICATION = 101;
    private static final int REQ_PROJECTION = 102;
    private static final int TAB_SLAVE = 0, TAB_MASTER = 1;

    /* ===== 被控页 ===== */
    private Switch swMaster;
    private TextView tvA11y, tvBattery, tvNotify, tvServer, tvUrl, tvHint, tvCapture;
    private Button btnA11y, btnBattery, btnNotify, btnLaunch, btnUrl, btnCapture;
    private boolean refreshing = false;

    /* ===== 主控页 / 双 Tab ===== */
    private View slavePage, masterPage;
    private Button tabSlave, tabMaster;
    private TextView tvLink;
    private TouchpadView pad;
    private LinkClient linkClient;
    private VideoClient videoClient;

    private boolean linkConnected = false;
    private String linkDetail = "未连接";
    private boolean remoteEnabled = false;
    private boolean remoteA11y = false;
    private boolean remoteVideo = false;
    private boolean videoConnected = false;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        CtrlBus.init(this);
        setContentView(buildUi());
        // 记住上次停留在哪个 Tab，下次启动直接进去
        showTab(CtrlBus.prefs(this).getInt("last_tab", TAB_SLAVE));
        // 已存过被控地址就开机直接连，不用再弹窗
        String saved = CtrlBus.prefs(this).getString(CtrlBus.KEY_MASTER_ADDR, "");
        if (!TextUtils.isEmpty(saved)) startClient(saved);
    }

    private View buildUi() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(0xFFF5F7FB);

        FrameLayout host = new FrameLayout(this);
        host.setId(View.generateViewId());
        root.addView(host, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        slavePage = buildSlavePage();
        masterPage = buildMasterPage();
        host.addView(slavePage, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        host.addView(masterPage, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        /* 底部两个 Tab */
        LinearLayout tabBar = new LinearLayout(this);
        tabBar.setOrientation(LinearLayout.HORIZONTAL);
        tabBar.setBackgroundColor(0xFFFFFFFF);
        tabBar.setPadding(0, dp(4), 0, dp(4));
        tabBar.setElevation(dp(6));

        tabSlave = makeTabButton("被控");
        tabMaster = makeTabButton("主控");
        tabBar.addView(tabSlave, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        tabBar.addView(tabMaster, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        tabSlave.setOnClickListener(v -> showTab(TAB_SLAVE));
        tabMaster.setOnClickListener(v -> showTab(TAB_MASTER));
        // 长按底部「主控」Tab 不切页、直接弹地址框改被控端地址，改完立刻重连
        tabMaster.setOnLongClickListener(v -> { showAddrDialog(false); return true; });
        root.addView(tabBar, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        return root;
    }

    private Button makeTabButton(String text) {
        Button b = new Button(this);
        b.setText(text);
        b.setAllCaps(false);
        b.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
        b.setBackgroundColor(0x00000000);
        return b;
    }

    private void showTab(int tab) {
        boolean slave = tab == TAB_SLAVE;
        CtrlBus.prefs(this).edit().putInt("last_tab", slave ? TAB_SLAVE : TAB_MASTER).apply();
        slavePage.setVisibility(slave ? View.VISIBLE : View.GONE);
        masterPage.setVisibility(slave ? View.GONE : View.VISIBLE);
        tabSlave.setTextColor(slave ? 0xFF2962FF : 0xFF999999);
        tabMaster.setTextColor(slave ? 0xFF999999 : 0xFF2962FF);
        tabSlave.getPaint().setFakeBoldText(slave);
        tabMaster.getPaint().setFakeBoldText(!slave);
        if (!slave) maybeAskAddr();   // 第一次进主控才弹地址框
    }

    /* ===================== 被控页（保持原样，仅去掉网页地址展示） ===================== */

    private View buildSlavePage() {
        ScrollView scroll = new ScrollView(this);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        int pad = dp(20);
        root.setPadding(pad, dp(28), pad, pad);
        scroll.addView(root, new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        TextView title = new TextView(this);
        title.setText("鲸控 · 被控端");
        title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 24);
        title.setTextColor(0xFF1A1A1A);
        title.getPaint().setFakeBoldText(true);
        root.addView(title);

        TextView sub = new TextView(this);
        sub.setText("局域网远程触控 + 画面回传");
        sub.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        sub.setTextColor(0xFF888888);
        sub.setPadding(0, dp(4), 0, dp(20));
        root.addView(sub);

        swMaster = new Switch(this);
        swMaster.setText("总开关");
        swMaster.setTextSize(TypedValue.COMPLEX_UNIT_SP, 18);
        swMaster.setTextColor(0xFF1A1A1A);
        swMaster.setPadding(0, dp(8), 0, dp(8));
        root.addView(swMaster, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        swMaster.setOnCheckedChangeListener((btn, checked) -> {
            if (refreshing) return;
            onMasterToggled(checked);
        });

        root.addView(section("运行状态"));
        tvCapture = statusLine(root, "画面回传");
        tvA11y = statusLine(root, "无障碍");
        tvBattery = statusLine(root, "电池优化");
        tvNotify = statusLine(root, "通知常驻");
        tvServer = statusLine(root, "被控服务");

        root.addView(section("被控端地址（主控填这个）"));
        tvUrl = new TextView(this);
        tvUrl.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
        tvUrl.setTextColor(0xFF2962FF);
        tvUrl.setPadding(dp(14), dp(12), dp(14), dp(12));
        tvUrl.setBackgroundColor(0xFFEEF3FF);
        root.addView(tvUrl, matchWrap());
        btnUrl = makeButton("复制地址");
        root.addView(btnUrl, matchWrap());
        btnUrl.setOnClickListener(v -> {
            android.content.ClipboardManager cm =
                    (android.content.ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
            cm.setPrimaryClip(android.content.ClipData.newPlainText(
                    "addr", CtrlWebUi.hostIp(this) + ":" + CtrlBus.PORT));
            Toast.makeText(this, "地址已复制", Toast.LENGTH_SHORT).show();
        });

        root.addView(section("权限设置"));
        tvHint = new TextView(this);
        tvHint.setText("开启总开关后，请把下面五项都设为允许；再在最近任务里把本应用下拉锁定一下，否则后台会被系统杀掉");
        tvHint.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        tvHint.setTextColor(0xFF999999);
        tvHint.setPadding(0, 0, 0, dp(10));
        root.addView(tvHint, matchWrap());

        btnCapture = makeButton("① 授权屏幕录制（主控才看得到画面）");
        root.addView(btnCapture, matchWrap());
        btnCapture.setOnClickListener(v -> requestProjection());

        btnA11y = makeButton("② 去开启无障碍服务");
        root.addView(btnA11y, matchWrap());
        btnA11y.setOnClickListener(v -> openAccessibility());

        btnBattery = makeButton("③ 关闭电池优化（保活）");
        root.addView(btnBattery, matchWrap());
        btnBattery.setOnClickListener(v -> openBattery());

        btnNotify = makeButton("④ 允许通知 / 常驻");
        root.addView(btnNotify, matchWrap());
        btnNotify.setOnClickListener(v -> openNotification());

        btnLaunch = makeButton("⑤ 允许后台自启动（保活）");
        root.addView(btnLaunch, matchWrap());
        btnLaunch.setOnClickListener(v -> openAutoStart());

        TextView note = new TextView(this);
        note.setText("支持点击、滑动、长按；屏幕画面会缩到 720p / 约 10fps 传给主控。\n"
                + "两台手机连同一 WiFi，另一台装同一 APP 切到「主控」填上面地址即可。\n"
                + "授权屏幕录制后主控才看得到画面，不给也能照旧盲点。\n"
                + "本应用不申请悬浮窗权限（无障碍本身即可后台运行）。");
        note.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        note.setTextColor(0xFFAAAAAA);
        note.setPadding(0, dp(18), 0, 0);
        root.addView(note, matchWrap());

        return scroll;
    }

    /* ===================== 主控页：一条细状态 + 纯触摸板 ===================== */

    private View buildMasterPage() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(0xFF0A0F19);

        tvLink = new TextView(this);
        tvLink.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        tvLink.setTextColor(0xFF8FA4CC);
        tvLink.setPadding(dp(12), dp(8), dp(12), dp(8));
        tvLink.setBackgroundColor(0xFF131C2E);
        tvLink.setText("未连接");
        // 长按状态条可重填被控地址（藏起来，不占触摸板空间）
        tvLink.setOnLongClickListener(v -> { showAddrDialog(false); return true; });
        root.addView(tvLink, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        FrameLayout padHost = new FrameLayout(this);
        // 不留内边距：触摸板按固定比例尽量撑满可用区域（比例不变、不超出视图）
        padHost.setPadding(0, 0, 0, 0);
        pad = new TouchpadView(this);
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.gravity = Gravity.CENTER;
        padHost.addView(pad, lp);
        root.addView(padHost, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        return root;
    }

    private void maybeAskAddr() {
        String saved = CtrlBus.prefs(this).getString(CtrlBus.KEY_MASTER_ADDR, "");
        if (TextUtils.isEmpty(saved)) {
            showAddrDialog(true);
        }
    }

    private void showAddrDialog(boolean first) {
        if (isFinishing()) return;
        final EditText et = new EditText(this);
        et.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
        et.setHint("被控端地址，例如 192.168.1.23");
        String saved = CtrlBus.prefs(this).getString(CtrlBus.KEY_MASTER_ADDR, "");
        if (!TextUtils.isEmpty(saved)) et.setText(saved);
        et.setSingleLine(true);

        AlertDialog d = new AlertDialog.Builder(this)
                .setTitle(first ? "第一次用，填一下被控端地址" : "改被控端地址")
                .setMessage("两台手机连同一 WiFi。地址在被控端「被控」页顶部，长按顶部状态条也能改。")
                .setView(et)
                .setCancelable(!first)
                .setPositiveButton("连接", (dialog, which) -> {
                    String s = et.getText().toString().trim();
                    if (TextUtils.isEmpty(s)) {
                        Toast.makeText(this, "地址不能为空", Toast.LENGTH_SHORT).show();
                        return;
                    }
                    CtrlBus.prefs(this).edit()
                            .putString(CtrlBus.KEY_MASTER_ADDR, s).apply();
                    startClient(s);
                })
                .setNegativeButton(first ? "稍后" : "取消", null)
                .create();
        d.show();
    }

    private void startClient(String addr) {
        String host = addr.trim();
        if (host.startsWith("http://")) host = host.substring(7);
        if (host.startsWith("https://")) host = host.substring(8);
        host = host.replace("/", "");
        int port = CtrlBus.PORT;
        int colon = host.lastIndexOf(':');
        if (colon > 0) {
            try { port = Integer.parseInt(host.substring(colon + 1)); }
            catch (NumberFormatException ignored) {}
            host = host.substring(0, colon);
        }
        if (TextUtils.isEmpty(host)) return;
        if (linkClient != null) linkClient.stop();
        linkClient = new LinkClient(host, port, this);
        pad.setClient(linkClient);
        linkClient.start();
        // 画面另走一条连接，连不上也不影响触摸
        if (videoClient != null) videoClient.stop();
        videoClient = new VideoClient(host, CtrlBus.PORT_VIDEO, this);
        videoClient.start();
        linkDetail = "连接中… " + host + ":" + port;
        updateLinkUi();
    }

    private void updateLinkUi() {
        runOnUiThread(() -> {
            if (tvLink == null) return;
            String state;
            int color;
            if (!linkConnected) {
                state = "○ " + linkDetail;
                color = 0xFF8FA4CC;
            } else if (!remoteEnabled) {
                state = "● 已连上，但被控端总开关没开";
                color = 0xFFFFB74D;
            } else if (!remoteA11y) {
                state = "● 已连上，但被控端无障碍没开";
                color = 0xFFFFB74D;
            } else if (remoteVideo && !videoConnected) {
                state = "● 已就绪 · 画面连接中…";
                color = 0xFF66BB6A;
            } else {
                state = "● 已就绪" + (remoteVideo ? " · 有画面" : " · 无画面（被控端未授权录屏）");
                color = 0xFF66BB6A;
            }
            tvLink.setText(state);
            tvLink.setTextColor(color);
        });
    }

    /* ===== LinkClient.Listener ===== */
    @Override
    public void onLinkState(boolean connected, String detail) {
        linkConnected = connected;
        linkDetail = detail;
        updateLinkUi();
        // 连上（含断线后重连成功）时：滑动是实时流，补一个 down 重建被控端因超时抬掉的
        // 手势；长按持有期间若松手的 lend 正好在断连窗口被清掉，补发一条 lend 让被控端松手
        // （重复 lend 在被控端是空操作，安全）。
        if (connected && pad != null) {
            pad.flushPendingLongEnd();
            pad.resendDownIfPressing();
        }
    }

    @Override
    public void onRemoteState(boolean enabled, boolean accessibility, boolean video) {
        remoteEnabled = enabled;
        remoteA11y = accessibility;
        remoteVideo = video;
        updateLinkUi();
    }

    /* ===== VideoClient.Listener ===== */
    @Override
    public void onFrame(android.graphics.Bitmap bmp) {
        if (pad != null) pad.setFrame(bmp);
    }

    @Override
    public void onVideoState(boolean connected) {
        videoConnected = connected;
        updateLinkUi();
    }

    /* ---------- UI 小工具 ---------- */

    private View section(String text) {
        TextView tv = new TextView(this);
        tv.setText(text);
        tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        tv.setTextColor(0xFF2962FF);
        tv.getPaint().setFakeBoldText(true);
        tv.setPadding(0, dp(22), 0, dp(8));
        return tv;
    }

    private TextView statusLine(LinearLayout parent, String label) {
        TextView tv = new TextView(this);
        tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
        tv.setTextColor(0xFF444444);
        tv.setPadding(0, dp(5), 0, dp(5));
        parent.addView(tv, matchWrap());
        return tv;
    }

    private Button makeButton(String text) {
        Button b = new Button(this);
        b.setText(text);
        b.setAllCaps(false);
        b.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
        b.setGravity(Gravity.START | Gravity.CENTER_VERTICAL);
        b.setPadding(dp(16), dp(10), dp(16), dp(10));
        return b;
    }

    private LinearLayout.LayoutParams matchWrap() {
        return new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
    }

    private int dp(int v) {
        return Math.round(TypedValue.applyDimension(
                TypedValue.COMPLEX_UNIT_DIP, v, getResources().getDisplayMetrics()));
    }

    /* ---------- 被控开关逻辑 ---------- */

    private void onMasterToggled(boolean on) {
        if (on) {
            CtrlBus.setEnabled(this, true);
            // 先申请通知权限再起前台服务：Android 13+ 没有通知权限时前台服务通知不显示，
            // 等于保活通知白起。权限请求是异步的，不影响随后服务启动。
            requestNotificationIfNeeded();
            startForegroundService();
            if (!isAccessibilityEnabled()) {
                Toast.makeText(this, "请开启无障碍服务后才能真正操控", Toast.LENGTH_LONG).show();
            }
            if (!isIgnoringBatteryOptimizations()) {
                Toast.makeText(this, "建议关闭电池优化，避免后台被杀", Toast.LENGTH_LONG).show();
            }
        } else {
            CtrlBus.setEnabled(this, false);
            stopForegroundService();
        }
        refresh();
    }

    private void startForegroundService() {
        Intent i = new Intent(this, CtrlForegroundService.class)
                .setAction(CtrlForegroundService.ACTION_START);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(i);
        } else {
            startService(i);
        }
    }

    private void stopForegroundService() {
        Intent i = new Intent(this, CtrlForegroundService.class)
                .setAction(CtrlForegroundService.ACTION_STOP);
        try {
            startService(i);
        } catch (IllegalStateException e) {
            stopService(new Intent(this, CtrlForegroundService.class));
        }
    }

    private void requestNotificationIfNeeded() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
                    != PackageManager.PERMISSION_GRANTED) {
                ActivityCompat.requestPermissions(this,
                        new String[]{Manifest.permission.POST_NOTIFICATIONS}, REQ_NOTIFICATION);
            }
        }
    }

    /* ---------- 权限跳转 ---------- */

    private void openAccessibility() {
        try {
            startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS));
        } catch (Exception e) {
            Toast.makeText(this, "打不开设置，请手动找「无障碍」", Toast.LENGTH_SHORT).show();
        }
        Toast.makeText(this, "找到「鲸控」并开启", Toast.LENGTH_LONG).show();
    }

    private void openBattery() {
        try {
            Intent i = new Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS);
            i.setData(Uri.parse("package:" + getPackageName()));
            startActivity(i);
        } catch (Exception e) {
            try {
                startActivity(new Intent(
                        Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS));
            } catch (Exception e2) {
                Toast.makeText(this, "请手动在设置里关闭电池优化", Toast.LENGTH_SHORT).show();
            }
        }
    }

    private void openNotification() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
                    != PackageManager.PERMISSION_GRANTED) {
                ActivityCompat.requestPermissions(this,
                        new String[]{Manifest.permission.POST_NOTIFICATIONS}, REQ_NOTIFICATION);
                return;
            }
        }
        try {
            Intent i = new Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS);
            i.putExtra(Settings.EXTRA_APP_PACKAGE, getPackageName());
            startActivity(i);
        } catch (Exception e) {
            Toast.makeText(this, "请手动在系统设置里允许通知", Toast.LENGTH_SHORT).show();
        }
    }

    /** 自启动白名单没有统一 API：华为/荣耀走自己的启动管理页，其余退到应用详情页。 */
    private void openAutoStart() {
        String[] candidates = {
                "com.huawei.systemmanager/.startupmgr.ui.StartupNormalAppListActivity",
                "com.huawei.systemmanager/.startupmgr.ui.StartupAppControlActivity",
                "com.huawei.systemmanager/.optimize.process.ProtectActivity",
                "com.huawei.systemmanager/.appcontrol.activity.StartupAppControlActivity",
                "com.miui.securitycenter/com.miui.permcenter.autostart.AutoStartManagementActivity"
        };
        for (String cn : candidates) {
            try {
                Intent i = new Intent();
                i.setComponent(ComponentName.unflattenFromString(cn));
                i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                startActivity(i);
                Toast.makeText(this, "把「鲸控」设为手动管理，三项全允许", Toast.LENGTH_LONG).show();
                return;
            } catch (Exception ignored) {}
        }
        try {
            Intent i = new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS);
            i.setData(Uri.parse("package:" + getPackageName()));
            startActivity(i);
            Toast.makeText(this, "找到自启动/后台管理，把「鲸控」允许", Toast.LENGTH_LONG).show();
        } catch (Exception e) {
            Toast.makeText(this, "请手动在系统设置里允许自启动和后台运行", Toast.LENGTH_SHORT).show();
        }
    }

    /** 弹系统「开始录制或投放」授权框。授权后画面端口才能在主控连进来时真正抓屏。 */
    private void requestProjection() {
        try {
            android.media.projection.MediaProjectionManager mpm =
                    (android.media.projection.MediaProjectionManager)
                            getSystemService(Context.MEDIA_PROJECTION_SERVICE);
            if (mpm == null) {
                Toast.makeText(this, "系统不支持屏幕录制", Toast.LENGTH_SHORT).show();
                return;
            }
            startActivityForResult(mpm.createScreenCaptureIntent(), REQ_PROJECTION);
        } catch (Exception e) {
            Toast.makeText(this, "打不开录屏授权：" + e.getMessage(), Toast.LENGTH_LONG).show();
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != REQ_PROJECTION) return;
        if (resultCode != RESULT_OK) {
            Toast.makeText(this, "没给录屏权限，主控只能盲点", Toast.LENGTH_LONG).show();
            refresh();
            return;
        }
        if (ScreenStreamer.setProjection(this, resultCode, data)) {
            Toast.makeText(this, "录屏已授权", Toast.LENGTH_SHORT).show();
        } else {
            String why = ScreenStreamer.lastError;
            Toast.makeText(this, "录屏授权失败" + (why == null ? "" : "：" + why),
                    Toast.LENGTH_LONG).show();
        }
        refresh();
    }

    /* ---------- 状态检测 ---------- */

    private boolean isAccessibilityEnabled() {
        int enabled = 0;
        try {
            enabled = Settings.Secure.getInt(getContentResolver(),
                    Settings.Secure.ACCESSIBILITY_ENABLED, 0);
        } catch (Exception ignored) {}
        if (enabled == 0) return false;
        String services = Settings.Secure.getString(getContentResolver(),
                Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
        if (TextUtils.isEmpty(services)) return false;
        String me = new ComponentName(this, CtrlAccessibilityService.class).flattenToString();
        String meShort = getPackageName() + "/.CtrlAccessibilityService";
        for (String s : services.split(":")) {
            if (me.equalsIgnoreCase(s) || meShort.equalsIgnoreCase(s)) return true;
        }
        return false;
    }

    private boolean isIgnoringBatteryOptimizations() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return true;
        PowerManager pm = (PowerManager) getSystemService(Context.POWER_SERVICE);
        return pm != null && pm.isIgnoringBatteryOptimizations(getPackageName());
    }

    private boolean isNotificationOk() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return true;
        return ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
                == PackageManager.PERMISSION_GRANTED;
    }

    /* ---------- 生命周期 ---------- */

    @Override
    protected void onResume() {
        super.onResume();
        CtrlBus.setListener(this);
        if (CtrlBus.isEnabled() && !CtrlBus.isServerRunning()) {
            startForegroundService();
        }
        refresh();
    }

    @Override
    protected void onPause() {
        super.onPause();
        CtrlBus.setListener(null);
    }

    @Override
    protected void onDestroy() {
        if (linkClient != null) linkClient.stop();
        if (videoClient != null) videoClient.stop();
        super.onDestroy();
    }

    @Override
    public void onStateChanged() { runOnUiThread(this::refresh); }

    private void refresh() {
        refreshing = true;
        swMaster.setChecked(CtrlBus.isEnabled());
        refreshing = false;

        tvA11y.setText("无障碍  " + mark(isAccessibilityEnabled()));
        tvBattery.setText("电池优化  " + (isIgnoringBatteryOptimizations()
                ? "✓ 已忽略（保活OK）" : "✗ 未忽略（会被杀）"));
        tvNotify.setText("通知常驻  " + mark(isNotificationOk()));
        tvServer.setText("被控服务  " + (CtrlBus.isServerRunning()
                ? "✓ 运行中 :" + CtrlBus.PORT + (CtrlBus.isClientConnected()
                        ? " · 主控已连" : " · 等主控连")
                : "✗ 未启动"));
        tvUrl.setText(CtrlWebUi.hostIp(this) + ":" + CtrlBus.PORT
                + "   ← 主控填这个");

        tvCapture.setText("画面回传  " + ScreenStreamer.statusText());
        btnCapture.setEnabled(true);

        btnA11y.setEnabled(true);
        btnBattery.setEnabled(true);
        btnNotify.setEnabled(true);
        btnLaunch.setEnabled(true);
    }

    private String mark(boolean ok) { return ok ? "✓ 已开启" : "✗ 未开启"; }
}
