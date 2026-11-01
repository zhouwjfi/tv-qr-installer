package com.zhouwj.tvinstaller;

import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageInfo;
import android.graphics.Bitmap;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.KeyEvent;
import android.view.WindowManager;
import android.widget.ImageView;
import android.widget.TextView;
import android.widget.Toast;

import java.io.File;
import java.util.Locale;

public class MainActivity extends Activity implements HttpFileServer.Callback {

    private static final int QR_SIZE = 512;
    private static final int WATCH_INTERVAL_MS = 2000;
    private static final int WATCH_MAX_TIMES = 45;
    private static final int BIND_MAX_RETRY = 10;

    private TextView urlView;
    private TextView statusTitle;
    private TextView statusDetail;
    private ImageView qrView;

    private final Handler main = new Handler(Looper.getMainLooper());
    private File pendingApk;
    private String pendingName;
    private String lastInstallDesc = "";
    private String targetPkg;
    private String targetVersion;
    private boolean waitingPermission = false;
    private int lastPostedPct = -1;
    private int watchCount;
    private int bindRetry;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);

        urlView = findViewById(R.id.url);
        statusTitle = findViewById(R.id.status_title);
        statusDetail = findViewById(R.id.status_detail);
        qrView = findViewById(R.id.qr);

        setStatus("服务启动中…", "", R.color.statusInfo);
        startWebService();
    }

    private void startWebService() {
        Intent si = new Intent(this, WebService.class);
        if (Build.VERSION.SDK_INT >= 26) {
            startForegroundService(si);
        } else {
            startService(si);
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        bindRetry = 0;
        bindWebService();
        // 用户从「未知应用」设置页返回后,自动继续安装
        if (waitingPermission && pendingApk != null && ApkInstaller.hasInstallPermission(this)) {
            waitingPermission = false;
            beginInstall(pendingApk, pendingName);
        }
    }

    /** 绑定 WebService 并刷新界面;服务尚未起来时自动重试(进程刚重启的场景) */
    private void bindWebService() {
        WebService svc = WebService.getInstance();
        if (svc == null) {
            startWebService();
            setStatus("服务启动中…", "稍候片刻", R.color.statusInfo);
            if (bindRetry++ < BIND_MAX_RETRY) {
                main.postDelayed(this::bindWebService, 800);
            }
            return;
        }
        bindRetry = 0;
        svc.ensureServer();
        svc.setListener(this);
        String url = svc.getUrl();
        if (url != null) {
            showQr(url);
            setStatus("等待手机上传 APK", "手机与电视需连接同一 WiFi", R.color.statusInfo);
        } else {
            urlView.setText("服务未就绪");
            setStatus("未获取到局域网 IP 或端口被占用", "请检查电视网络后重新进入应用", R.color.statusErr);
        }
        // 服务在界面退到后台期间收到 APK 的话,这里继续安装
        File pf = svc.getPendingUpload();
        if (pf != null) {
            String pn = svc.getPendingName();
            svc.clearPendingUpload();
            beginInstall(pf, pn == null ? "upload.apk" : pn);
        }
    }

    @Override
    protected void onPause() {
        WebService svc = WebService.getInstance();
        if (svc != null) {
            svc.setListener(null);
        }
        super.onPause();
    }

    @Override
    protected void onDestroy() {
        // 不停止服务:退到后台/退出界面后,手机依然可以扫码上传
        main.removeCallbacks(watchRunnable);
        super.onDestroy();
    }

    private void showQr(String url) {
        Bitmap qr = Qr.generate(url, QR_SIZE);
        if (qr != null) {
            qrView.setImageBitmap(qr);
        }
        urlView.setText(url);
    }

    // ---------------- 上传回调(服务器线程,转发到主线程) ----------------

    @Override
    public void onUploadStart(String name, long totalBytes) {
        lastPostedPct = -1;
        main.post(() -> setStatus("正在接收 " + name, human(totalBytes), R.color.statusInfo));
    }

    @Override
    public void onProgress(long done, long totalBytes) {
        int pct = (int) (done * 100 / totalBytes);
        if (pct == lastPostedPct) {
            return;
        }
        lastPostedPct = pct;
        main.post(() -> setStatus("正在接收 " + pct + "%", human(done) + " / " + human(totalBytes), R.color.statusInfo));
    }

    @Override
    public void onReceived(File file, String displayName) {
        main.post(() -> beginInstall(file, displayName));
    }

    @Override
    public void onUploadError(String message) {
        main.post(() -> setStatus("上传失败", message, R.color.statusErr));
    }

    @Override
    public void onServerReady(String url) {
        main.post(() -> {
            showQr(url);
            setStatus("等待手机上传 APK", "手机与电视需连接同一 WiFi", R.color.statusInfo);
        });
    }

    // ---------------- 安装流程 ----------------

    private void beginInstall(File apk, String name) {
        pendingApk = apk;
        pendingName = name;
        PackageInfo info = ApkInstaller.parseApk(this, apk);
        if (info == null || info.packageName == null) {
            setStatus("文件无效", name + " 不是有效的 APK 文件", R.color.statusErr);
            pendingApk = null;
            return;
        }
        lastInstallDesc = info.packageName + " · 版本 " + info.versionName;
        targetPkg = info.packageName;
        targetVersion = info.versionName;
        if (!ApkInstaller.hasInstallPermission(this)) {
            waitingPermission = true;
            setStatus("需要「安装未知应用」权限", "即将打开设置,允许后返回即可(只需一次)", R.color.statusInfo);
            ApkInstaller.openPermissionSettings(this);
            return;
        }
        setStatus("请在电视上完成安装", lastInstallDesc + "\n已打开系统安装器,默认选中「取消」,请按「右」方向键选中「安装」后按「确认」", R.color.statusInfo);
        try {
            ApkInstaller.installViaSystemInstaller(this, apk);
            startInstallWatch();
        } catch (Exception e) {
            setStatus("打开安装器失败", String.valueOf(e.getMessage()), R.color.statusErr);
        }
    }

    /** 轮询目标包版本,检测安装是否完成 */
    private void startInstallWatch() {
        watchCount = 0;
        main.removeCallbacks(watchRunnable);
        main.postDelayed(watchRunnable, WATCH_INTERVAL_MS);
    }

    private final Runnable watchRunnable = new Runnable() {
        @Override
        public void run() {
            try {
                String v = getPackageManager().getPackageInfo(targetPkg, 0).versionName;
                if (targetVersion != null && targetVersion.equals(v)) {
                    setStatus("✅ 安装成功", lastInstallDesc, R.color.statusOk);
                    return;
                }
            } catch (Exception ignored) {
            }
            if (++watchCount < WATCH_MAX_TIMES) {
                main.postDelayed(this, WATCH_INTERVAL_MS);
            }
        }
    };

    // ---------------- 遥控器按键 ----------------

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        if (keyCode == KeyEvent.KEYCODE_DPAD_CENTER || keyCode == KeyEvent.KEYCODE_ENTER) {
            if (!ApkInstaller.hasInstallPermission(this)) {
                ApkInstaller.openPermissionSettings(this);
            } else {
                Toast.makeText(this, "请用手机扫码上传 APK", Toast.LENGTH_SHORT).show();
            }
            return true;
        }
        return super.onKeyDown(keyCode, event);
    }

    // ---------------- 工具 ----------------

    private void setStatus(String title, String detail, int colorRes) {
        statusTitle.setText(breakable(title));
        statusTitle.setTextColor(getColor(colorRes));
        statusDetail.setText(breakable(detail));
    }

    /** 在长单词(文件名/包名等无空格内容)中插入零宽空格,使其可以折行,避免文字超出屏幕 */
    private static String breakable(String s) {
        if (s == null) {
            return "";
        }
        StringBuilder sb = new StringBuilder(s.length() + 16);
        int run = 0;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            sb.append(c);
            run = (c == ' ' || c == '\n' || c == '.' || c == '/' || c == '_' || c == '-' || c == '·') ? 0 : run + 1;
            if (run >= 14) {
                sb.append('\u200B');
                run = 0;
            }
        }
        return sb.toString();
    }

    private static String human(long bytes) {
        if (bytes >= 1048576L) {
            return String.format(Locale.CHINA, "%.1f MB", bytes / 1048576.0);
        }
        return bytes + " KB";
    }
}
