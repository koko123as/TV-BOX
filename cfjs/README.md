# CFJS · Spider 服务（TV-BOX 独立模块）

本模块把 FongMi/TV 的 Java Spider 引擎跑在 Android 前台服务里。它提供只绑定 127.0.0.1 的 HTTP API，并通过 cloudflared 让 Cloudflare 访问这个 API。

## 为什么是独立模块

上游 `:app` 依赖 FongMi 自行构建、没有公开发布的 `lib-*.aar`（media3、mpvplayer、ffmpeg），所以只用公开源码无法编译（详见 `cfjs/docs`）。

本模块的组成：

- 直接依赖上游的 `:catvod`
- 由 `scripts/sync-upstream.sh` **原样复制** `JarLoader`、`SpiderLoader`、`SpiderLoadException`。CI 会逐字节对比，确认它们和上游一致。
- 用同名的小型垫片类提供这些文件需要的 `App.get()`、`UrlUtil.convert`、`JsHost`
- 不配置、不修改上游 `:app`

同步上游的步骤：先合并 FongMi/TV，再运行 `cfjs/scripts/sync-upstream.sh`，然后提交。

## 构建

```
./gradlew -p cfjs :app:assembleDebug
```

cloudflared 由 CI 从官方源码以 `GOOS=android` 编译，放到 `jniLibs/arm64-v8a/libcloudflared.so`，不提交到仓库。

## HTTP API（v1，仅 GET）

除 `health` 外，所有请求都要带请求头 `X-CFJS-Token`。Token 在设备上生成，可以在界面上复制。

| 路径 | 参数 |
|---|---|
| `/api/v1/health` | 无 |
| `/api/v1/sites` | 无 |
| `/api/v1/home` | `site` |
| `/api/v1/category` | `site`、`tid`、`page`、`filters`（JSON） |
| `/api/v1/search` | `site`、`wd`、`page` |
| `/api/v1/detail` | `site`、`id`（返回中附加 `cfjs_flags`：线路和剧集） |
| `/api/v1/play` | `site`、`flag`、`id` |

`/play` 返回以下字段：

- 资源站原始的 `url` 和 `urls`
- 合并后的 `header`（包括 UA、Referer、Cookie）
- `parse`、`jx`、`playUrl`、`format`
- `directPlayable` 和 `notDirectReason`
- 原始结果 `raw`

视频流不经过本服务，也不经过 Cloudflare。

## 范围与限制（v1）

- 只运行 Java（`csp_`）站点。Python、QuickJS、Node 站点会标为 unsupported。
- 不解密、不修改 Guard jar，原样交给上游 `JarLoader` 加载。
- 不包含 WebView：需要网页登录、弹窗或嗅探的站点不可用。
- 本阶段只支持 Quick Tunnel（trycloudflare.com 随机地址）。有名隧道和 Access 在后续阶段加入。

## Jellyfin 接口（0.2.0，供 SenPlayer 本机使用）

服务启动时，会在 `127.0.0.1:9978` 另开一个 Jellyfin 兼容服务器。它只绑定本机回环地址，不需要 Token，用户名任意，密码留空。这个服务器和上面 19978 端口的 Token API 互相独立，19978 的行为不变。

**SenPlayer 设置：** 添加 Jellyfin 服务器，地址填 `127.0.0.1`，端口填 `9978`。

这部分逻辑移植自 SpiderHost 0.0.15 的 `JellyfinApi`，是 SenPlayer 已经接受过的实现。站点调用改走本模块的 `SpiderBridge`：Jellyfin 使用单独的实例，每个站点最多同时执行 3 个调用，不影响 19978。

### 对应关系

- **媒体库**
  - 「推荐」：取首页站点 `homeContent` 返回的 list，没有时用 `homeVideoContent`。
  - 首页站点的各个分类。
  - 「全部站点（N）」：所有 Java 站点，层级为 站点 → 推荐/分类 → 影片。
- **首页站点**：在界面上点「选择 Jellyfin 首页站点」指定。不指定时用配置里的第一个 Java 站点。服务器名称显示为首页站点名。
- **影片结构**：影片对应 Series，线路对应 Season，集对应 Episode。
- **搜索**：所有可搜索的 Java 站点并发搜索，总时限 15 秒。标题相同的结果合并成一条「[n源]」。
- **`msearch:` 条目**（豆瓣类跳转）：打开时，在其他站点搜索同名影片，并把找到的结果合并显示。
- **播放**：先调用 `playerContent`。
  - 不需要请求头的地址：返回 302 直接跳转到资源站。
  - 需要请求头的地址：经 127.0.0.1 转发。HLS 会改写 m3u8，让分片、子列表和密钥请求都带上请求头。
  - 有多个画质时：每个画质对应一个 MediaSource。
  - `parse=1`、`jx=1`、`playUrl` 不支持。
- **`/proxy`**：CatVod 本地代理。请求带 `siteKey` 时转给该站点 spider 的 `proxy`，否则转给 jar 的静态 `Proxy.proxy`。启动时会执行 `Proxy.set(9978)`。
- **收藏、播放进度、继续观看、下一集**：保存在本机的 `files/jf-user.json`。

### 与 SpiderHost 相比去掉的功能

- 换源（AltSources）。
- SpiderHost 自己的 jar 加载方式。

封面补全改为全局只用 1 个后台线程。

## 网盘（0.3.0）

网盘站（夸克、UC、阿里、115、百度、天翼、123、迅雷、移动等）在 FongMi 里依赖下面几样宿主能力，本模块按上游补齐：

- **存储**：catvod `Path.root()` 是外部存储根目录，`Path.tv()` 是 `/sdcard/TV`。上游申请 `MANAGE_EXTERNAL_STORAGE`（`app/src/main/AndroidManifest.xml`、`PermissionUtil.requestFile`），这里同样声明，并在「网盘登录」页提供授权入口。
- **登录界面**：Spider 通过 `Runtime.activity()`（前台 Activity）弹自己的二维码/输入框，或调用 `Runtime.dialog`（上游 `SpiderDialog`）。本模块用 `CfjsDialog` 实现同一套选项（title、message、qrcode、image、inputs、buttons、回调补丁）。登录弹窗只在 Spider 服务界面处于前台时可用，所以登录要在「网盘登录」页里操作。
- **action 条目**：带 `action` 字段的条目（配置中心、扫码入口）调用 `spider.action`（上游 `TypeFragment` → `SiteApi.action`），返回的 `msg` 会提示，`web` 会用内置网页打开。
- **Cookie**：`spider.net` 的 Cookie 存在系统 `CookieManager`（与上游 `WebViewCookies` 相同），重启后仍在。
- **本地代理**：`127.0.0.1:9978/proxy` 按上游 `server/process/Proxy` 转发给 `spider.proxy` 或 jar 的静态 `Proxy.proxy`，原样回传状态码、全部响应头和 Content-Length，流式输出，不缓存。`proxy://` 地址和 `http://<任意主机>:9978/proxy` 地址都按本地代理处理，Jellyfin 播放时直接在进程内处理 SenPlayer 的 Range 请求。
- **/file/**：只读提供外部存储下的文件（上游 `/file/`），供 `http://127.0.0.1:9978/file/TVBox/xxx.txt` 这类 ext 使用。

日志前缀：`[NETDISK]`（存储、登录页、播放线路、自检）、`[PROXY]`（每个代理请求的状态、类型、长度、Content-Range、实际发出的字节数）、`[TOAST]`（Spider 的提示）。日志里只写请求头的名字，不写 Cookie 的值。

「网盘自检」在玩偶站上依次跑首页、详情和每条网盘线路的 playerContent，再像 SenPlayer 一样读取前 64 KB，结果写到「下载/cfjs-netdisk-*.txt」。

限制：不解密 Guard。Guard jar 自带的登录逻辑看不到源码，只能从设备日志判断它走的是哪条路；需要网页嗅探（parse=1）的线路仍不支持。
