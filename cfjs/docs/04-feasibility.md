# 04 · 可行性验证（静态部分）

> 日期：2026-11-11。仓库：koko123as/TV-BOX（fork 自 FongMi/TV，`fongmi` 分支 `5b41d9d`，与 01–03 的审计版本相同）。
> 配置和 jar 视为第三方数据，只做结构分析，不提交到仓库，不解密 Guard 载荷。

## 1. 目标配置的结构

在沙箱里直接取到了配置（HTTP 200，约 34 KB，明文 JSON，未加密）。设备上之前出现的 `Unable to resolve host` 是手机端 DNS 问题，不是配置本身的问题。

| 项 | 结果 |
|---|---|
| 顶层字段 | `spider`、`wallpaper`、`logo`、`sites`、`parses`、`doh`、`rules`、`ads`、`lives`、`headers` |
| sites | **100 个，全部是 type 3 的 Java `csp_*Guard`**，共用一个全局 `spider` jar |
| Python / QuickJS / Node 站点 | **0 个** |
| 站点级 `jar` | 无 |
| `ext` | 少数站点有，形如 `{"config":"AppV7Dsx"}`、`{"embynumber":0}`、`{"json":"https://…"}`，或者一个 URL 字符串 |
| `spider` | 一个伪装成 `.jpg` 的地址，形如 `…jpg;md5;<32位>`。FongMi `JarLoader.loadJar` 支持 `;md5;` 校验 |
| parses | 若干 type 1（json 解析）接口，只服务于 jx 或 parse 线路，**远程直连场景用不上** |
| doh / rules / headers | FongMi 原生支持，`BaseConfig.setHosts/setHeaders/setProxy` 会应用 |

结论：**这份配置只需要 Java 运行时**，和 03 报告里「第一版只支持 `csp_`」的建议一致。

## 2. 「自定义 Guard」是什么（只读结构，未解密）

jar 实际是一个 zip（MD5 与配置声明一致），内容如下：

```
classes.dex             40 KB    外壳
assets/wexguard_v7.so   48 KB    armeabi-v7a 原生库
assets/wexguard_v8.so   48 KB    arm64 原生库
assets/wexshinidie.guard 978 KB  加密载荷（真正的 Spider）
```

用 androguard 反编译外壳 dex 看到的机制（145 个类，都在 `com.github.catvod.spider`）：

1. `Init.init(Context)`：把 Context **强转为 `android.app.Application`**，然后调用 `DexNative.getLoader(context)`，取得一个 `DexClassLoader`。
2. `DexNative` 的静态初始化：清理 `cacheDir` 下的 `.wexfnw*` 文件，按 `Build.CPU_ABI` 选 v7 或 v8 的 so，通过 `Init.classLoader().getResourceAsStream("assets/…")` 把它写到 `cacheDir/.wexfnw<随机>`，设为只读并 `chmod 0500`，然后 `System.load`。
3. 原生库 `libDexNative.so` 用 **Obfuscator-LLVM** 混淆，字符串已加密。它负责解密 `wexshinidie.guard`，得到真正的 dex 和 ClassLoader。
4. `XxxGuard extends BaseSpiderGuard extends com.github.catvod.crawler.Spider`：构造时调用 `Init.getSpider(类名)`，由原生代码返回真实的 Spider。之后所有方法**原样转发**给它。
5. `Proxy.proxy(Map)` 调用 `Init.proxyInvoke`，也由原生代码转发。

和 FongMi 的对接点逐项核对：

| Guard 的要求 | FongMi 的行为 | 结论 |
|---|---|---|
| 调用 `Init.init(Context)` | `JarLoader.initDex` 反射调用 `init(Context)`，传入 `App.get()`，它是 Application | 兼容 |
| 用 `getResourceAsStream` 读 assets | `DexClassLoader` 的路径就是 jar 本身，可以读到其中的资源 | 预计兼容 |
| 把 so 写到 cacheDir 再加载 | 应用自己的目录，没有限制 | 预计兼容 |
| 继承宿主的 `com.github.catvod.crawler.Spider` | FongMi 提供这个类，`Guard` 只重写了公开方法 | 外壳兼容 |
| **解密出的真实 Spider 调用宿主的 SDK**（`com.github.catvod.net.*`、`utils.*` 等） | FongMi 的 SDK 和 OK 影视、TVBox 系 SDK 可能有签名差异 | **只能在真机运行时确认**，会表现为 `NoSuchMethodError` 或 `NoClassDefFoundError` |
| 原生库是否校验宿主包名或签名 | 字符串已加密，无法静态确认 | SpiderHost 用的是**不同包名**，已在你的手机上跑通花卷站点首页，说明至少不强制校验 OK 影视的包名 |

所以 Guard 适配方案是：

- **不解密、不修改**。原样交给 FongMi 的 `JarLoader` 加载。
- 第一步在真机上跑自检，看哪些站点首页、分类、详情、播放能通过。
- 如果出现 SDK 签名不兼容，再在**独立模块**里补兼容类（只补缺失的方法），不改上游 `:catvod` 的已有签名。
- Guard 的 `Proxy` 地址（`127.0.0.1` 本机代理）在远程场景下按 03 报告的 R2 标为不可直连。

## 3. Cloudflare Tunnel 在 Android 上的可行性

| 项 | 结论 |
|---|---|
| 官方二进制 | cloudflared `2026.10.7`（2026-10-31）有 `cloudflared-linux-arm64`（约 37.7 MB），**没有 Android 版** |
| 能否执行 | Android 10 以上不允许执行应用数据目录里的文件。可行做法是把它以 `libcloudflared.so` 打进 `jniLibs/arm64-v8a`，开启 `useLegacyPackaging`，然后从 `nativeLibraryDir` 执行。需真机验证 |
| DNS | 静态 Go 程序在 Android 上没有 `/etc/resolv.conf`，可能会解析失败（你的手机之前就有 DNS 问题）。备选：用 `GOOS=android` 自行编译 cloudflared。需真机验证 |
| APK 体积 | 会增加约 37 MB（只打包 arm64） |
| 身份认证 | Quick Tunnel（trycloudflare）不需要账号和域名，但地址随机，也不能配 Access，只适合验证连通性。正式使用需要**有名隧道和一个托管在 Cloudflare 的域名**，再配 Access Service Token |
| 视频 | 不经过 Tunnel（03 报告 R2） |

## 4. 还需要在真机上验证的三件事

1. 在 TV-BOX 里导入这份配置，用自检确认后台（没有 Activity 处于前台时）能否调通 home、category、detail、play，以及 Guard 是否和 FongMi SDK 兼容。
2. cloudflared 能否在魅族 20 Pro（Android 16）上以 `nativeLibraryDir` 方式启动，并解析 DNS。
3. 用 Quick Tunnel 从外网访问 `/api/v1/health`。

## 5. 建议：先出一个「验证版」APK

只包含验证需要的最小代码，全部放在新包 `com.fongmi.android.tv.bridge` 中：

- `SpiderBridge`：调用 `SiteApi`；每站信号量；超时；播放走 `preloadContent`
- `PlayInfo`：完整保留播放字段
- `BridgeSelfTest`：自检报告写到「下载」目录
- 一个只绑定 127.0.0.1 的 `/api/v1/health` 和 `/api/v1/sites`
- 一个调用 cloudflared Quick Tunnel 的测试按钮

上游文件只改三处：

- `AndroidManifest.xml`：注册自检入口
- `app/build.gradle`：`applicationIdSuffix ".cfjs"`，和你已安装的影视仓并存
- 新增一个 CI 工作流，在 CI 里生成调试证书并写入 `local.properties`，不改上游的签名配置

验证通过后，再按 1 到 6 项功能正式开发。

## 6. 编译阻塞（2026-11-11 21:40 补充）

**结论：当前 FongMi/TV（`5b41d9d`）无法只用公开源码编译。**

- `app/build.gradle` 通过 `fileTree(dir: "libs", include: ["*.aar"])` 引入播放器库，而 `.gitignore` 排除了 `lib-*.aar`，仓库里只有 `hook-release.aar` 和 `tvbus-release.aar`。
- 源码中有 258 处 `androidx.media3.common` import，另有 exoplayer、ui、session，以及 18 处 `androidx.media3.mpvplayer` import。这些都来自 FongMi 自己构建的 `lib-*.aar`。
  - 2025-01 的提交 `a37c3b7d4` 之前，依赖还是逐个列出的 `lib-exoplayer-release.aar` 等，可以对照。
- `buildSrc/AbiApkPackaging` 的 `VerifyMpvNativeTask` 要求 `app/libs/lib-mpvplayer-release.aar` 和 `lib-decoder-ffmpeg-release.aar` 必须存在。
- FongMi/Release、FongMi/media、FongMi/mpv-android、FongMi/FFmpeg 的 Releases 里都**没有**发布这些 aar。
- 公开的 FongMi/media `release-1.11.0-fongmi` 分支（最后提交 2026-08-12，`3c2cbe8`）有 `mpvplayer` 模块，也带 mpv 的预编译 so，但**缺少** TV 当前用到的 4 个类：`MpvConfigFileInspector`、`MpvDecoderMode`、`MpvSubtitleContentLoader`、`media.MpvDrmSupport`。TV 从 2026-10-19 的 `6a5d4b2e6` 开始使用这些类。
- `:catvod` 模块只依赖 Maven 上的公开库，可以单独编译。

### 可选路线

| 路线 | 做法 | 优点 | 缺点 |
|---|---|---|---|
| A | 回退到 `6a5d4b2e6` 之前的 TV 提交；在 CI 里从 FongMi/media 公开分支构建 aar（FFmpeg 解码器的 so 也要自行构建或去掉校验） | 保留完整的 TV 应用 | 落后上游约 3 周（之后有 148 个提交，包括 crawler 改动）；仍可能有其他 API 不匹配；以后每次同步上游都可能再卡住；CI 很重 |
| B | 在 TV-BOX 仓库里新增一个独立的 Android 应用模块。它依赖上游 `:catvod`，并把 Spider 加载与调用相关的类（`JarLoader`、`SpiderLoader`、`SiteApi` 及其依赖的 bean）**原样复制**进来，保留出处 | 用公开源码就能编译；Spider 引擎与上游一致，同步时按文件对照即可 | 不包含播放器和完整 UI；复制的文件需要随上游更新 |
| C | 为缺失的 mpv 类写编译桩 | 改动看起来少 | 等于伪造接口，mpv 播放会出错，**不建议** |
