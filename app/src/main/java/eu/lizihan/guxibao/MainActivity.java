package eu.lizihan.guxibao;

import android.Manifest;
import android.annotation.SuppressLint;
import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.view.View;
import android.view.ViewGroup;
import android.webkit.CookieManager;
import android.webkit.DownloadListener;
import android.webkit.RenderProcessGoneDetail;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceError;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.FrameLayout;
import android.widget.ProgressBar;
import android.widget.Toast;

import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.core.view.WindowInsetsControllerCompat;

/**
 * 「股息宝」安卓壳：把 https://guxibao.lizihan.eu/ 以全屏、无地址栏的方式装进 WebView，
 * 对标 iOS 上那个 com.apple.webClip.managed 描述文件。
 */
public class MainActivity extends Activity {

    static final String HOME_URL = "https://guxibao.lizihan.eu/";
    private static final String HOST = "guxibao.lizihan.eu";
    private static final String OFFLINE_URL = "file:///android_asset/offline.html";

    private WebView web;
    private ProgressBar progress;
    private FrameLayout root;
    private ValueCallback<Uri[]> filePathCallback;
    private static final int REQ_FILE_CHOOSER = 1001;
    private static final int REQ_STORAGE = 1002;

    /** 主框加载失败过一次，用来决定返回键是回退还是重试 */
    private boolean showingOffline = false;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        WindowCompat.setDecorFitsSystemWindows(getWindow(), false);

        root = new FrameLayout(this);
        root.setBackgroundColor(getColor(R.color.app_background));

        web = new WebView(this);
        web.setBackgroundColor(getColor(R.color.app_background));
        web.setLayoutParams(new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        root.addView(web);

        progress = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        progress.setMax(100);
        progress.setIndeterminate(false);
        progress.setLayoutParams(new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(3)));
        progress.getProgressDrawable().setTint(getColor(R.color.accent));
        progress.setBackgroundColor(Color.TRANSPARENT);
        root.addView(progress);

        setContentView(root);
        applyInsets();

        // 深色背景配浅色系统图标
        WindowInsetsControllerCompat bars = WindowCompat.getInsetsController(getWindow(), root);
        bars.setAppearanceLightStatusBars(false);
        bars.setAppearanceLightNavigationBars(false);

        configureWebView();

        if (savedInstanceState != null) {
            web.restoreState(savedInstanceState);
        } else {
            web.loadUrl(HOME_URL);
        }
    }

    /**
     * 内容铺到屏幕边缘，再按系统栏 / 刘海 / 输入法的实际尺寸补 padding。
     * 状态栏区域露出的是 app 背景色，观感上跟站点深色主题连成一片。
     */
    private void applyInsets() {
        ViewCompat.setOnApplyWindowInsetsListener(root, (v, insets) -> {
            Insets bars = insets.getInsets(
                    WindowInsetsCompat.Type.systemBars() | WindowInsetsCompat.Type.displayCutout());
            int imeBottom = insets.getInsets(WindowInsetsCompat.Type.ime()).bottom;
            v.setPadding(bars.left, bars.top, bars.right, Math.max(bars.bottom, imeBottom));
            return WindowInsetsCompat.CONSUMED;
        });
    }

    @SuppressLint("SetJavaScriptEnabled")
    private void configureWebView() {
        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);          // 站点用 localStorage 存持仓和设置
        s.setDatabaseEnabled(true);
        s.setLoadWithOverviewMode(true);
        s.setUseWideViewPort(true);
        s.setSupportZoom(false);               // 站点自己写了 maximum-scale=1
        s.setBuiltInZoomControls(false);
        s.setDisplayZoomControls(false);
        s.setMediaPlaybackRequiresUserGesture(false);
        s.setMixedContentMode(WebSettings.MIXED_CONTENT_NEVER_ALLOW);
        s.setCacheMode(WebSettings.LOAD_DEFAULT);
        s.setTextZoom(100);                    // 跟 iOS 描述文件一致，不跟随系统字号缩放打乱布局
        s.setUserAgentString(s.getUserAgentString() + " GuxibaoAndroid/1.0");

        CookieManager cookies = CookieManager.getInstance();
        cookies.setAcceptCookie(true);
        cookies.setAcceptThirdPartyCookies(web, true);

        web.setOverScrollMode(View.OVER_SCROLL_NEVER);
        web.setWebViewClient(new AppWebViewClient());
        web.setWebChromeClient(new AppChromeClient());
        web.setDownloadListener(new AppDownloadListener());
        web.addJavascriptInterface(new BlobBridge(this), BlobBridge.NAME);
    }

    private class AppWebViewClient extends WebViewClient {

        @Override
        public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
            return handleUrl(request.getUrl());
        }

        /** 站内链接留在壳里，站外链接和 mailto:/tel:/intent: 交给系统处理 */
        private boolean handleUrl(Uri uri) {
            String scheme = uri.getScheme();
            String host = uri.getHost();
            if (("https".equals(scheme) || "http".equals(scheme)) && host != null && host.equals(HOST)) {
                return false;
            }
            if (OFFLINE_URL.equals(uri.toString())) {
                return false;
            }
            try {
                startActivity(new Intent(Intent.ACTION_VIEW, uri)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            } catch (ActivityNotFoundException e) {
                toast("没有应用可以打开这个链接");
            }
            return true;
        }

        @Override
        public void onPageStarted(WebView view, String url, android.graphics.Bitmap favicon) {
            if (!OFFLINE_URL.equals(url)) {
                showingOffline = false;
            }
            progress.setVisibility(View.VISIBLE);
        }

        @Override
        public void onPageFinished(WebView view, String url) {
            progress.setVisibility(View.GONE);
            CookieManager.getInstance().flush();
            if (!OFFLINE_URL.equals(url)) {
                view.evaluateJavascript(BlobBridge.INSTALL_SCRIPT, null);
            }
        }

        @Override
        public void onReceivedError(WebView view, WebResourceRequest request, WebResourceError error) {
            // 只有主框架失败才整页替换，子资源失败交给页面自己处理
            if (request.isForMainFrame()) {
                showOffline();
            }
        }

        @Override
        public boolean onRenderProcessGone(WebView view, RenderProcessGoneDetail detail) {
            // 渲染进程被系统回收时若不接管，整个 app 会直接崩掉
            root.removeView(web);
            web.destroy();
            web = null;
            recreate();
            return true;
        }
    }

    private class AppChromeClient extends WebChromeClient {
        @Override
        public void onProgressChanged(WebView view, int newProgress) {
            progress.setProgress(newProgress);
            if (newProgress >= 100) {
                progress.setVisibility(View.GONE);
            }
        }

        @Override
        public boolean onShowFileChooser(WebView view, ValueCallback<Uri[]> callback,
                                         FileChooserParams params) {
            if (filePathCallback != null) {
                filePathCallback.onReceiveValue(null);
            }
            filePathCallback = callback;
            try {
                startActivityForResult(params.createIntent(), REQ_FILE_CHOOSER);
                return true;
            } catch (ActivityNotFoundException e) {
                filePathCallback = null;
                toast("没有可用的文件选择器");
                return false;
            }
        }
    }

    /**
     * 站点导出备份走的是 Blob + a.download，裸 WebView 对 blob:/data: 完全没反应。
     * 这里把这两种地址接管下来存进「下载」目录，http(s) 则交给系统下载管理器。
     */
    private class AppDownloadListener implements DownloadListener {
        @Override
        public void onDownloadStart(String url, String userAgent, String contentDisposition,
                                    String mimetype, long contentLength) {
            if (url.startsWith("blob:")) {
                web.evaluateJavascript(BlobBridge.fetchBlobScript(url), null);
            } else if (url.startsWith("data:")) {
                Downloads.saveDataUrl(MainActivity.this, url, mimetype);
            } else {
                Downloads.enqueue(MainActivity.this, url, userAgent, contentDisposition, mimetype);
            }
        }
    }

    private void showOffline() {
        showingOffline = true;
        web.loadUrl(OFFLINE_URL);
    }

    /** offline.html 上的「重试」按钮和外部调用都走这里 */
    public void reload() {
        runOnUiThread(() -> {
            showingOffline = false;
            if (web != null) {
                web.loadUrl(HOME_URL);
            }
        });
    }

    void toast(String msg) {
        runOnUiThread(() -> Toast.makeText(this, msg, Toast.LENGTH_SHORT).show());
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        if (requestCode != REQ_FILE_CHOOSER) {
            super.onActivityResult(requestCode, resultCode, data);
            return;
        }
        if (filePathCallback != null) {
            filePathCallback.onReceiveValue(
                    WebChromeClient.FileChooserParams.parseResult(resultCode, data));
            filePathCallback = null;
        }
    }

    @Override
    @SuppressWarnings("deprecation")
    public void onBackPressed() {
        if (showingOffline) {
            reload();
        } else if (web != null && web.canGoBack()) {
            web.goBack();
        } else {
            super.onBackPressed();
        }
    }

    @Override
    protected void onSaveInstanceState(Bundle outState) {
        super.onSaveInstanceState(outState);
        if (web != null) {
            web.saveState(outState);
        }
    }

    @Override
    protected void onPause() {
        super.onPause();
        if (web != null) {
            web.onPause();
        }
        CookieManager.getInstance().flush();
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (web != null) {
            web.onResume();
        }
    }

    @Override
    protected void onDestroy() {
        if (web != null) {
            root.removeView(web);
            web.destroy();
            web = null;
        }
        super.onDestroy();
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    /**
     * Android 9 及以下写公共「下载」目录需要运行时权限；Android 10+ 走 MediaStore 不需要。
     * 返回 false 时已经弹出授权框，用户授权后再点一次导出即可。
     */
    boolean ensureLegacyStoragePermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            return true;
        }
        if (checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE)
                == PackageManager.PERMISSION_GRANTED) {
            return true;
        }
        runOnUiThread(() -> requestPermissions(
                new String[]{Manifest.permission.WRITE_EXTERNAL_STORAGE}, REQ_STORAGE));
        return false;
    }
}
