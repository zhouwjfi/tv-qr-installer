package com.zhouwj.tvinstaller;

import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageInfo;
import android.net.Uri;
import android.os.Build;
import android.provider.Settings;

import androidx.core.content.FileProvider;

import java.io.File;

final class ApkInstaller {

    private ApkInstaller() {
    }

    /** Android 8.0+ 需要「安装未知应用」权限 */
    static boolean hasInstallPermission(Context ctx) {
        return Build.VERSION.SDK_INT < 26 || ctx.getPackageManager().canRequestPackageInstalls();
    }

    static void openPermissionSettings(Context ctx) {
        Intent i = new Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                Uri.parse("package:" + ctx.getPackageName()));
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        try {
            ctx.startActivity(i);
        } catch (Exception e) {
            // 个别系统没有该设置页,退回应用详情页
            Intent detail = new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                    Uri.parse("package:" + ctx.getPackageName()));
            detail.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            ctx.startActivity(detail);
        }
    }

    /** 解析 APK 的包名/版本信息,无效 APK 返回 null */
    static PackageInfo parseApk(Context ctx, File apk) {
        try {
            return ctx.getPackageManager().getPackageArchiveInfo(apk.getAbsolutePath(), 0);
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * 通过系统安装器打开 APK。
     * 与文件管理器点击 APK 的行为一致,兼容 MIUI TV 等定制系统的安装确认页;
     * PackageInstaller 会话在部分定制系统上会被直接拒绝,故不使用。
     */
    static void installViaSystemInstaller(Context ctx, File apk) {
        Uri uri = FileProvider.getUriForFile(ctx, ctx.getPackageName() + ".fileprovider", apk);
        Intent i = new Intent(Intent.ACTION_VIEW);
        i.setDataAndType(uri, "application/vnd.android.package-archive");
        i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_ACTIVITY_NEW_TASK);
        ctx.startActivity(i);
    }
}
