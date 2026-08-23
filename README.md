# 股息宝 · Android

`guxibao.mobileconfig` 那个 iOS 描述文件的安卓对应物。描述文件做的事是一个全屏 Web Clip，
这里用一个极薄的 WebView 壳把 <https://guxibao.lizihan.eu/> 装成独立 App：桌面有图标、
打开无地址栏、返回键当网页后退用。

## 相比裸「添加到主屏幕」补了什么

站点目前没有 PWA manifest，安卓 Chrome 的「添加到主屏幕」只会生成一个普通书签，
仍带地址栏。除全屏外，这个壳还处理了几件 WebView 默认做不了的事：

| 问题 | 处理 |
| --- | --- |
| 备份导出用 `Blob` + `<a download>`，裸 WebView 点了毫无反应 | 在 JS 层接管 `HTMLAnchorElement.prototype.click`，读成 dataURL 后由 Java 写入系统「下载」目录（Android 10+ 走 MediaStore，无需存储权限） |
| 刘海 / 手势条挡内容 | 内容铺满屏幕，再按 `WindowInsets` 实测尺寸补 padding，露出的部分是与站点同色的 `#0B1120` |
| 渲染进程被系统回收后整个 App 崩溃 | `onRenderProcessGone` 接管并重建 WebView |
| 断网时显示 WebView 自带的英文错误页 | 换成 `assets/offline.html`，带重试按钮 |
| 站外链接在壳里打开后出不去 | 非 `guxibao.lizihan.eu` 的链接、`mailto:`/`tel:` 一律转交系统 |
| 系统字号放大后布局错乱 | `textZoom` 固定 100，与 iOS Web Clip 行为一致 |

登录态和持仓数据存在 App 私有目录，与 Chrome 相互独立，并随系统备份一起走。

## 出包

推 tag 即可，产物是 GitHub Release 里的 `guxibao-<版本>.apk`：

```sh
git tag v1.0 && git push origin v1.0
```

普通推送到 `main` 也会构建，APK 在 Actions 的 artifact 里。

### 需要的仓库 Secret

| 名称 | 内容 |
| --- | --- |
| `KEYSTORE_B64` | `guxibao.p12` 的 base64 |
| `KEYSTORE_PASSWORD` | 该 keystore 的密码 |

密钥本体不在仓库里，保存在本机 `~/guxibao-android-signing/`。**这份文件务必备份**：
安卓靠签名判断「是不是同一个 App」，弄丢了就只能让用户卸载重装，本地数据一并没了。

## 改版本号

改 `app/build.gradle` 里的 `versionCode`（每次发版必须递增）和 `versionName`，再推新 tag。

## 本地构建

装了 Android Studio 的话直接打开本目录即可。不配签名环境变量时会回落到 debug 签名：

```sh
./gradlew assembleDebug
```
