# 保留暴露给 WebView JS 的接口方法，混淆后仍能被 addJavascriptInterface 调用
-keepclassmembers class * {
    @android.webkit.JavascriptInterface <methods>;
}
