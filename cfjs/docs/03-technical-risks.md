# 03 · 技术风险评估

> 依据：01 和 02 中的源码证据；Cloudflare 官方文档（2026-11-11 读取）；SpiderHost 项目在魅族 20 Pro / SenPlayer 上踩过的坑。
> 等级：**高** = 可能让方案不成立，**中** = 要专门设计，**低** = 注意即可。

## A. 架构层面

### R1（高）上游已有的 `/spider-api` 不能暴露到公网
`server/process/SpiderTest.java` 可以加载**任意 api、jar、类名**，可以读写共享存储里的文件（`/file`、`/save`、`/upload`），只有同源检查，没有鉴权。而且 `Nano` 用 `super(port)` 监听所有网卡。

**对策：**
- Tunnel 只指向我们新增的 `/api/v1/*`，不能指向整个 9978 端口。可以用单独端口或单独的 `NanoHTTPD` 实例，只绑定 127.0.0.1。
- 或者在 Tunnel 的 ingress 里按路径过滤，并在服务端校验 Access 的 JWT。
- 新接口只接受配置里已有的 siteId。

### R2（高）用 Tunnel 中转视频违反 Cloudflare 条款，所以「直连」是硬性要求
- Cloudflare 服务条款中 CDN（Free / Pro / Business）一节写明：要通过 CDN 提供视频和大文件，必须使用 Stream、Images、开发者平台等付费服务，否则 Cloudflare 有权限制访问。
- 官方文档 *Delivering Videos with Cloudflare* 明确说：**Tunnel 的公网主机名路由也受这条约束**。

由此推出：
- 视频绝不能经过 Worker 或 Tunnel，你的第六阶段要求是对的。
- **所有需要宿主本地代理的源，在远程场景下都不可用**：
  - Spider 返回的 `http://127.0.0.1:9978/proxy?...`（`proxyUrl()`）
  - parse=1 需要 WebView 嗅探的源
  - jx 解析源
  - 需要请求头、但播放器设置不了请求头的源

  这类源只能标为「不兼容」，或者限定在本机或局域网播放。

### R3（高）SenPlayer 能否带上资源站要求的请求头，目前未知
Jellyfin 的 `MediaSourceInfo` 有 `RequiredHttpHeaders` 字段，但 SenPlayer 是否使用它**没有验证过**。SpiderHost 目前的做法是在本机 `/hls` 中转（这只在本机可行）。
**对策**：第八阶段先用一个需要 Referer 的测试源抓包验证。如果 SenPlayer 不带请求头，这类源在远程场景下就不兼容。

### R4（中）播放地址有时效，而且可能和请求者的 IP 绑定
`playerContent` 是**在 Android 设备上**执行的，资源站看到的是手机的 IP；SenPlayer 如果在另一个网络，直连时 IP 不同。部分站点的签名地址会校验 IP 或 Cookie。
**对策**：
- 播放地址不缓存，每次 PlaybackInfo 时实时调用。
- 记录每个站点「直连成功率」，作为健康状态的一项。

### R5（中）这个方案要解决的是「异地播放」，本机场景已经有更简单的方案
如果 SenPlayer 和 Spider 在**同一台手机**上，SpiderHost 已经实现了本机 127.0.0.1 上的 Jellyfin，不需要 Worker、Tunnel、D1。Cloudflare 链路只在「SenPlayer 在电视、平板等其他设备，或在外网」时才有价值。
建议在验收标准里写清楚目标场景，避免重复建设。

## B. Spider 运行时

### R6（中）全局可变状态
- `BaseLoader.setRecent`（`site.recent()`）决定 `/proxy` 默认走哪个站点；分类、详情、播放都会改它。
- `Source` 是全局单例：`SiteApi.playerContent` 会先 `Source.get().stop()`。
- `Prefers "crash"` 置为 true 后，首页会跳过 Spider。

**对策**：
- API 播放用 `preloadContent`，不调用 `playerContent`。
- 搜索不调用 `recent()`。
- API 请求不要和 App 前台播放同时进行；不得不并行时，要接受代理路由可能串站。

### R7（中）Java Spider 的线程安全没有保证
宿主对同一个 Java Spider 实例**不加锁**，上游依赖各 jar 自己保证线程安全。第一版每站同时只执行一个任务（你的 4.3 要求）是正确的做法。QuickJS 本身就是串行的；Python 受 GIL 限制。

### R8（中）取消不彻底
`Future.cancel(true)` 只能中断能响应中断的代码。Java 和 Python 中的纯计算或第三方阻塞无法强制停止（CatVodSpider 的 `docs/development.md` 也这样写）。超时后结果被丢弃，但工作线程可能还要占用一段时间。
**对策**：每站加信号量，超时后**不立刻释放许可**，等真正结束再释放。SpiderHost 0.0.13 已经验证这样做有效。

### R9（中）依赖 UI 的能力在后台会失效
- `JsHost.getUserAgent()` 读 `WebActivity`，`getCookie` 读 WebView Cookie。
- `openWeb` 和 `dialog` 需要 Activity（`App.activity()` 只在有界面处于前台时不为 null）。
- 需要扫码登录、验证码弹窗的站点（比如网盘类）在纯后台场景下用不了。

**对策**：先在前台 App 里登录好，再启动服务；这类站点标为「需要前台交互」。

### R10（中）运行时模块需要单独安装
QuickJS、Python、Node 是可下载模块（`RuntimeModule`），没装时 `require()` 抛异常。
- `/api/v1/sites` 要返回每站需要的运行时和是否已安装。
- Node 运行在独立进程，停止服务时要确认这个进程也退出了（待验证）。

### R11（中）资源释放的粒度
- 上游 `clear()` 只调用 `close()` 不调用 `destroy()`，`destroy()` 只在退出时执行。
- 停止服务应调用 `VodConfig.get().destroy()`，但这会**同时清掉 App 前台正在用的配置**，App 内的浏览会受影响。
- `Task` 的线程池是进程级静态的，不会被关闭。

**结论**：「停止服务后不占资源」只能做到 Spider 实例、Tunnel、HTTP 服务全部释放，线程池会保留，但空闲时不耗电。最终要以真机内存和线程数为准。

### R12（中）旧配置兼容（OK 影视、`.guard`）
按你的要求，第一版不处理。已知情况：
- 你在用的配置里有 `.guard` 和 `newwex.json`。
- SpiderHost 遇到过需要伪装包名、预加载 so 的 jar。
- 也遇到过 `Unable to resolve host` 这类 DNS 问题：站点在运营商 DNS 下解析不了，需要 DoH。FongMi 有 `doh` 配置（`VodConfig.getDoh()`），可以直接复用。

## C. Android 生命周期

### R13（高）魅族 Flyme 会杀后台
SpiderHost 在你的魅族 20 Pro 上实测过：
- 「划卡即停」会强制停止整个 App（包括前台服务）。
- 系统省电策略还可能冻结 App。

前台服务能降低被回收的概率，但**不能完全避免**。
**对策**：
- 状态机以「实际探测结果」为准：Worker 定时探测 `/health`，手机上用 `onDestroy` 和进程存活来判断。
- 被杀后 Worker 返回 503。
- 在设置里引导用户手动加入白名单。不使用任何隐蔽的保活手段。

### R14（中）HTTP 服务目前跟随主界面
`Server.get().stop()` 挂在 `HomeActivity` 销毁时（mobile 310 行）。改为由服务持有后，要避免用户退出主界面时把 API 一起停掉。这需要改动上游的这一处调用，或者让我们的 API 使用独立的 `NanoHTTPD` 实例。

### R15（低）前台服务类型
Android 14 以上需要声明 `foregroundServiceType`。上游已经声明了 `dataSync`（`UpdateService`），但 dataSync 在 Android 15 有每天 6 小时的累计限制（待核实），长时间运行可能需要 `specialUse`。SpiderHost 用的是 `specialUse`，可以参考。

## D. Cloudflare

### R16（中）Workers 免费计划的限制（官方 Limits 页，2026-10-29 更新）
- 每天 100,000 次请求，UTC 零点重置。
- 每次请求 CPU 时间 10 ms，等待网络不计入。
- 每次请求最多 50 个子请求，同时等待响应头的连接最多 6 个。

SenPlayer 打开首页会并发请求很多接口，每个接口最多回源手机一次。只要**不在 Worker 里做 JSON 大量转换**，10 ms 一般够用，但要实测。图片代理会消耗请求数：海报应让 SenPlayer 直连图片地址。需要请求头的图片仍要中转，会被计入请求数，并且受 R2 的条款约束。

### R17（中）Tunnel 的前提条件
- 有名隧道（配合 Access 使用）需要一个**托管在 Cloudflare 上的域名**。Quick Tunnel（trycloudflare）不需要域名，但地址随机，也不能配 Access，只适合第四阶段的连通性验证。
- cloudflared **没有官方的 Android 版本**。用 linux-arm64 二进制在 Android 上运行需要验证三件事：放在 `jniLibs` 里以 `lib*.so` 名义打包并从 `nativeLibraryDir` 执行；Go 程序在 Android 上的 DNS 解析；SELinux 的执行限制。这是你第四阶段「先独立验证」要回答的问题。
- Access Service Token 只能存在 Worker Secret 和手机本地的加密配置里，不进仓库、不进 APK（你的要求，照做）。

### R18（低）D1 / KV 的一致性
KV 是最终一致的，写入后可能要过一段时间才能在其他地区读到；适合缓存，不适合存播放状态。用户数据和 ID 映射放在 D1。播放地址不进 KV（你的 7.4 要求）。

## E. 法律与许可

### R19（中）GPL-3.0
FongMi/TV 是 GPL-3.0。基于它的 Android 端改动须以 GPL-3.0 开源。Worker 是独立程序，通过网络 API 通信，一般不被视为衍生作品（这不是法律意见）。
仓库里**不放第三方 jar、片源配置或片源链接**（延续 SpiderHost 的规则）。

## F. 建议的方案调整（等你确认）

1. 新增接口用**独立的 `NanoHTTPD` 实例，只绑定 127.0.0.1**，不放进上游的 `Nano`。这样 Tunnel 只能访问到它，也避开了 R1 和 R14。
2. 播放统一走 `SiteApi.preloadContent`。返回原始 `Result` 的完整字段（url 或多画质、header、parse、jx、playUrl、format、subs、drm）。parse=1、jx、代理地址标为 `directPlayable: false`。
3. 第一版只支持 Java（`csp_`）站点，QuickJS、Python 留到稳定之后。
4. 第四阶段先用 Quick Tunnel 加 linux-arm64 的 cloudflared 验证能否在手机上运行；之后再用你的域名配有名隧道和 Access。
5. 第八阶段的 SenPlayer 测试，**直接复用 SpiderHost 已经验证过的 Jellyfin 最小实现与 `[JF]` 请求日志**。只是它目前也还没有在真机上完成全部验证。
