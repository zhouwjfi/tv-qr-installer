package com.zhouwj.tvinstaller;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Intent;
import android.os.Build;
import android.os.IBinder;

import java.io.File;

/**
 * 前台服务:持有 Web 服务器,不随界面退出/后台化而中断。
 * 界面不在前台时收到的 APK 会暂存,待界面恢复后继续安装流程。
 */
public class WebService extends Service implements HttpFileServer.Callback {

    private static final String CHANNEL_ID = "web_service";
    private static final int NOTIFICATION_ID = 1;
    private static final int BASE_PORT = 8266;

    private static volatile WebService instance;

    private HttpFileServer server;
    private File uploadFile;
    private String serverUrl;
    private volatile HttpFileServer.Callback listener;
    private volatile File pendingFile;
    private volatile String pendingName;

    static WebService getInstance() {
        return instance;
    }

    @Override
    public void onCreate() {
        super.onCreate();
        instance = this;
        uploadFile = new File(getCacheDir(), "upload.apk");
        startForegroundInternal();
        ensureServer();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        ensureServer();
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        instance = null;
        if (server != null) {
            server.stop();
        }
        super.onDestroy();
    }

    @Override
    public void onTaskRemoved(Intent rootIntent) {
        // 用户从最近任务里划掉应用时,才真正停止服务
        stopSelf();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    String getUrl() {
        return serverUrl;
    }

    void setListener(HttpFileServer.Callback l) {
        listener = l;
    }

    File getPendingUpload() {
        return pendingFile;
    }

    String getPendingName() {
        return pendingName;
    }

    void clearPendingUpload() {
        pendingFile = null;
        pendingName = null;
    }

    /** 服务不在运行或已失效时重建(可从任意位置重复调用) */
    synchronized void ensureServer() {
        if (server != null && server.isAlive()) {
            return;
        }
        server = null;
        serverUrl = null;
        String ip = Net.lanIp();
        if (ip == null) {
            updateNotification("服务启动失败:未获取到局域网 IP");
            return;
        }
        for (int p = BASE_PORT; p < BASE_PORT + 10; p++) {
            try {
                String url = "http://" + ip + ":" + p + "/";
                HttpFileServer s = new HttpFileServer(p, uploadFile, buildIndexHtml(url), this);
                s.start();
                server = s;
                serverUrl = url;
                updateNotification("服务运行中 " + url);
                if (listener != null) {
                    listener.onServerReady(url);
                }
                return;
            } catch (Exception e) {
                // 端口被占用等,尝试下一个
            }
        }
        updateNotification("服务启动失败:端口 " + BASE_PORT + "-" + (BASE_PORT + 9) + " 被占用");
    }

    // ---------------- 上传回调(服务器线程) ----------------

    @Override
    public void onUploadStart(String name, long totalBytes) {
        if (listener != null) {
            listener.onUploadStart(name, totalBytes);
        }
    }

    @Override
    public void onProgress(long done, long totalBytes) {
        if (listener != null) {
            listener.onProgress(done, totalBytes);
        }
    }

    @Override
    public void onReceived(File file, String displayName) {
        if (listener != null) {
            listener.onReceived(file, displayName);
        } else {
            // 界面不在前台:暂存,等界面回来后继续安装
            pendingFile = file;
            pendingName = displayName;
        }
    }

    @Override
    public void onUploadError(String message) {
        if (listener != null) {
            listener.onUploadError(message);
        }
    }

    @Override
    public void onServerReady(String url) {
        if (listener != null) {
            listener.onServerReady(url);
        }
    }

    // ---------------- 通知 ----------------

    private void startForegroundInternal() {
        Notification n = buildNotification("局域网安装服务准备中");
        startForeground(NOTIFICATION_ID, n);
    }

    private void updateNotification(String text) {
        NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        nm.notify(NOTIFICATION_ID, buildNotification(text));
    }

    private Notification buildNotification(String text) {
        Notification.Builder b;
        if (Build.VERSION.SDK_INT >= 26) {
            NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
            NotificationChannel ch = new NotificationChannel(CHANNEL_ID, "安装服务",
                    NotificationManager.IMPORTANCE_MIN);
            nm.createNotificationChannel(ch);
            b = new Notification.Builder(this, CHANNEL_ID);
        } else {
            b = new Notification.Builder(this);
        }
        return b.setContentTitle(getString(R.string.app_name))
                .setContentText(text)
                .setSmallIcon(R.drawable.ic_launcher)
                .setOngoing(true)
                .build();
    }

    // ---------------- 手机端上传页 ----------------

    private String buildIndexHtml(String url) {
        return INDEX_HTML
                .replace("%TV_NAME%", Build.MANUFACTURER + " " + Build.MODEL)
                .replace("%URL%", url);
    }

    private static final String INDEX_HTML =
            "<!DOCTYPE html>\n" +
            "<html lang=\"zh\">\n" +
            "<head>\n" +
            "<meta charset=\"utf-8\">\n" +
            "<meta name=\"viewport\" content=\"width=device-width,initial-scale=1,viewport-fit=cover\">\n" +
            "<title>电视 APK 安装</title>\n" +
            "<style>\n" +
            "*{box-sizing:border-box;margin:0;padding:0}\n" +
            "body{font-family:-apple-system,'PingFang SC','Microsoft YaHei',sans-serif;background:#0d1420;color:#e8edf6;min-height:100vh;display:flex;flex-direction:column;align-items:center;padding:36px 20px}\n" +
            "h1{font-size:22px;margin-bottom:8px}\n" +
            ".sub{color:#8fa0bd;font-size:13px;margin-bottom:30px}\n" +
            ".btn{display:block;width:100%;max-width:420px;background:#f5c518;color:#1a1a1a;font-size:18px;font-weight:600;text-align:center;padding:17px;border-radius:14px;cursor:pointer}\n" +
            "input[type=file]{display:none}\n" +
            ".tip{color:#8fa0bd;font-size:13px;margin-top:18px;text-align:center;line-height:1.8}\n" +
            ".bar-wrap{width:100%;max-width:420px;height:10px;background:#1b2433;border-radius:5px;margin-top:26px;overflow:hidden;display:none}\n" +
            ".bar{height:100%;width:0;background:#4ade80;border-radius:5px;transition:width .15s}\n" +
            "#msg{margin-top:18px;font-size:15px;text-align:center;white-space:pre-wrap;word-break:break-all}\n" +
            ".ok{color:#4ade80}.err{color:#f87171}\n" +
            "</style>\n" +
            "</head>\n" +
            "<body>\n" +
            "<h1>📺 电视 APK 安装</h1>\n" +
            "<div class=\"sub\">%TV_NAME% · %URL%</div>\n" +
            "<label class=\"btn\">选择 APK,立即上传\n" +
            "<input type=\"file\" accept=\".apk,application/vnd.android.package-archive\" onchange=\"pick(this)\">\n" +
            "</label>\n" +
            "<div class=\"bar-wrap\" id=\"bw\"><div class=\"bar\" id=\"bar\"></div></div>\n" +
            "<div id=\"msg\"></div>\n" +
            "<div class=\"tip\">上传完成后电视会弹出确认页<br>⚠️ 默认选中「取消」,请按遥控器「右」方向键<br>选中「安装/继续更新」后,再按「确认」键</div>\n" +
            "<script>\n" +
            "function fmt(n){return n>=1048576?(n/1048576).toFixed(1)+' MB':(n/1024).toFixed(0)+' KB'}\n" +
            "function msg(t,c){var m=document.getElementById('msg');m.textContent=t;m.className=c||''}\n" +
            "function bar(p){document.getElementById('bw').style.display='block';document.getElementById('bar').style.width=(p*100).toFixed(1)+'%'}\n" +
            "function pick(i){\n" +
            " var f=i.files[0];if(!f)return;\n" +
            " if(!/\\.apk$/i.test(f.name)){msg('请选择 .apk 文件','err');i.value='';return}\n" +
            " msg('开始上传 '+f.name+'('+fmt(f.size)+')…');\n" +
            " var x=new XMLHttpRequest();\n" +
            " x.open('POST','/upload');\n" +
            " x.setRequestHeader('X-Filename',encodeURIComponent(f.name));\n" +
            " x.upload.onprogress=function(e){if(e.lengthComputable){bar(e.loaded/e.total);msg('上传中 '+Math.round(e.loaded/e.total*100)+'%\\n'+fmt(e.loaded)+' / '+fmt(e.total))}};\n" +
            " x.onload=function(){\n" +
            "  if(x.status==200){bar(1);msg('✅ 已送达电视,请在电视上按遥控器完成安装','ok')}\n" +
            "  else{msg('上传失败 (HTTP '+x.status+')'+(x.responseText?' '+x.responseText:''),'err')}\n" +
            " };\n" +
            " x.onerror=function(){msg('网络中断,请确认手机与电视在同一 WiFi','err')};\n" +
            " x.send(f);\n" +
            "}\n" +
            "</script>\n" +
            "</body>\n" +
            "</html>";
}
