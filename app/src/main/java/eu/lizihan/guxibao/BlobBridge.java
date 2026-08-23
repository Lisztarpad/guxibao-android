package eu.lizihan.guxibao;

import android.webkit.JavascriptInterface;

/**
 * 站点导出备份用的是 {@code URL.createObjectURL} + {@code <a download>} + {@code a.click()}，
 * 而这个 a 元素从未插入文档。WebView 对 blob: 下载既不触发 DownloadListener 的可用信息，
 * 也拿不到 download 属性里的文件名，所以直接在 JS 层把 anchor 的 click 接管掉：
 * 命中 blob:/data: + download 时，前端自己读成 dataURL 再交回 Java 落盘。
 */
public class BlobBridge {

    public static final String NAME = "GuxibaoBridge";

    private final MainActivity activity;

    BlobBridge(MainActivity activity) {
        this.activity = activity;
    }

    @JavascriptInterface
    public void saveBase64(String dataUrl, String name, String mime) {
        Downloads.saveDataUrl(activity, dataUrl, mime, name);
    }

    @JavascriptInterface
    public void failed(String reason) {
        activity.toast("导出失败：" + reason);
    }

    /** 每次页面加载完注入一次，重复注入由 __guxibaoHooked 挡住 */
    static final String INSTALL_SCRIPT =
            "(function(){" +
            "  if (window.__guxibaoHooked) return;" +
            "  window.__guxibaoHooked = true;" +
            "  var nativeClick = HTMLElement.prototype.click;" +
            "  window.__guxibaoSave = function(href, name){" +
            "    fetch(href).then(function(r){ return r.blob(); }).then(function(b){" +
            "      var fr = new FileReader();" +
            "      fr.onload = function(){ " + NAME + ".saveBase64(String(fr.result), name || '', b.type || ''); };" +
            "      fr.onerror = function(){ " + NAME + ".failed('读取失败'); };" +
            "      fr.readAsDataURL(b);" +
            "    }).catch(function(e){ " + NAME + ".failed(String(e && e.message || e)); });" +
            "  };" +
            "  HTMLAnchorElement.prototype.click = function(){" +
            "    try {" +
            "      var href = this.getAttribute('href') || '';" +
            "      var name = this.getAttribute('download');" +
            "      if (name !== null && (href.indexOf('blob:') === 0 || href.indexOf('data:') === 0)) {" +
            "        window.__guxibaoSave(href, name);" +
            "        return;" +
            "      }" +
            "    } catch (e) {}" +
            "    return nativeClick.apply(this, arguments);" +
            "  };" +
            "})();";

    /** DownloadListener 兜底：某些 blob 下载没走 anchor.click，这时文件名只能自己编 */
    static String fetchBlobScript(String blobUrl) {
        String safeUrl = blobUrl.replace("\\", "\\\\").replace("'", "\\'");
        return "window.__guxibaoSave && window.__guxibaoSave('" + safeUrl + "', '');";
    }
}
