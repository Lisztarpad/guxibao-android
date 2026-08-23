package eu.lizihan.guxibao;

import android.app.DownloadManager;
import android.content.ContentValues;
import android.content.Context;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.provider.MediaStore;
import android.util.Base64;
import android.webkit.MimeTypeMap;
import android.webkit.URLUtil;

import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStream;
import java.util.Locale;

/** 把网页发起的下载落到系统「下载」目录。 */
final class Downloads {

    private Downloads() {}

    /** http(s) 直链交给系统下载管理器，通知栏有进度、可续传 */
    static void enqueue(MainActivity activity, String url, String userAgent,
                        String contentDisposition, String mime) {
        try {
            String name = URLUtil.guessFileName(url, contentDisposition, mime);
            DownloadManager.Request req = new DownloadManager.Request(Uri.parse(url));
            req.setMimeType(mime);
            req.addRequestHeader("User-Agent", userAgent);
            req.setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED);
            req.setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, name);
            DownloadManager dm = (DownloadManager) activity.getSystemService(Context.DOWNLOAD_SERVICE);
            if (dm == null) {
                activity.toast("系统下载服务不可用");
                return;
            }
            dm.enqueue(req);
            activity.toast("已开始下载 " + name);
        } catch (Exception e) {
            activity.toast("下载失败：" + e.getMessage());
        }
    }

    static void saveDataUrl(MainActivity activity, String dataUrl, String mime) {
        saveDataUrl(activity, dataUrl, mime, null);
    }

    /** data: / 由 blob 转成的 dataURL，解码后写入「下载」目录 */
    static void saveDataUrl(MainActivity activity, String dataUrl, String mime, String suggestedName) {
        try {
            int comma = dataUrl.indexOf(',');
            if (comma < 0) {
                activity.toast("导出失败：数据格式无法识别");
                return;
            }
            String header = dataUrl.substring(0, comma);
            String payload = dataUrl.substring(comma + 1);

            String actualMime = mime;
            if (actualMime == null || actualMime.isEmpty()) {
                int colon = header.indexOf(':');
                int semi = header.indexOf(';');
                if (colon >= 0) {
                    actualMime = header.substring(colon + 1, semi > colon ? semi : header.length());
                }
            }
            if (actualMime == null || actualMime.isEmpty()) {
                actualMime = "application/octet-stream";
            }

            byte[] bytes = header.contains(";base64")
                    ? Base64.decode(payload, Base64.DEFAULT)
                    : Uri.decode(payload).getBytes("UTF-8");

            String name = sanitize(suggestedName);
            if (name == null) {
                name = "guxibao-" + System.currentTimeMillis() + extensionFor(actualMime);
            }
            writeToDownloads(activity, name, actualMime, bytes);
            activity.toast("已保存到「下载」：" + name);
        } catch (Exception e) {
            activity.toast("导出失败：" + e.getMessage());
        }
    }

    private static void writeToDownloads(MainActivity activity, String name, String mime, byte[] bytes)
            throws Exception {
        Context ctx = activity;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            // Android 10+ 分区存储：走 MediaStore，无需任何存储权限
            ContentValues values = new ContentValues();
            values.put(MediaStore.Downloads.DISPLAY_NAME, name);
            values.put(MediaStore.Downloads.MIME_TYPE, mime);
            values.put(MediaStore.Downloads.IS_PENDING, 1);
            Uri collection = MediaStore.Downloads.EXTERNAL_CONTENT_URI;
            Uri item = ctx.getContentResolver().insert(collection, values);
            if (item == null) {
                throw new IllegalStateException("无法创建下载文件");
            }
            try (OutputStream out = ctx.getContentResolver().openOutputStream(item)) {
                if (out == null) {
                    throw new IllegalStateException("无法写入下载文件");
                }
                out.write(bytes);
            }
            values.clear();
            values.put(MediaStore.Downloads.IS_PENDING, 0);
            ctx.getContentResolver().update(item, values, null, null);
        } else {
            if (!activity.ensureLegacyStoragePermission()) {
                throw new IllegalStateException("需要存储权限，授权后请再试一次");
            }
            File dir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS);
            if (!dir.exists() && !dir.mkdirs()) {
                throw new IllegalStateException("无法创建下载目录");
            }
            File target = uniqueFile(dir, name);
            try (FileOutputStream out = new FileOutputStream(target)) {
                out.write(bytes);
            }
        }
    }

    /** Android 9 及以下没有 MediaStore 去重，同名文件手动加序号 */
    private static File uniqueFile(File dir, String name) {
        File f = new File(dir, name);
        if (!f.exists()) {
            return f;
        }
        int dot = name.lastIndexOf('.');
        String base = dot > 0 ? name.substring(0, dot) : name;
        String ext = dot > 0 ? name.substring(dot) : "";
        for (int i = 1; i < 1000; i++) {
            File candidate = new File(dir, base + "(" + i + ")" + ext);
            if (!candidate.exists()) {
                return candidate;
            }
        }
        return new File(dir, base + "-" + System.currentTimeMillis() + ext);
    }

    private static String sanitize(String name) {
        if (name == null) {
            return null;
        }
        String cleaned = name.replaceAll("[/\\\\:*?\"<>|\\r\\n\\t]", "_").trim();
        return cleaned.isEmpty() ? null : cleaned;
    }

    private static String extensionFor(String mime) {
        String ext = MimeTypeMap.getSingleton()
                .getExtensionFromMimeType(mime.toLowerCase(Locale.US));
        return ext == null ? ".bin" : "." + ext;
    }
}
