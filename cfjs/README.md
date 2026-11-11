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
