# 01 · 源码分析：FongMi/TV 与 CatVodSpider

> 第一阶段报告，只读源码，未改动代码。
> 源码版本（2026-11-11 克隆，`--depth 1`）：
> - FongMi/TV 分支 `fongmi`，提交 `5b41d9d`（2026-11-10）
> - FongMi/CatVodSpider 分支 `main`，提交 `98d6f2c`（2026-11-10）
>
> 下文路径中，`app/…/tv/` 指 `TV/app/src/main/java/com/fongmi/android/tv/`。行号对应上述提交。

## 1. 工程结构

`TV/settings.gradle` 包含这些模块：

| 模块 | 作用 |
|---|---|
| `:app` | 主程序，有两个 flavor 源码集：`leanback`（电视）、`mobile`（手机），公共代码在 `main` |
| `:catvod` | Spider SDK：`com.github.catvod.crawler.Spider`、`Net`/`OkHttp`、`Proxy`、`RuntimeModule`、`Task` |
| `:quickjs` | QuickJS Spider 运行时（`com.fongmi.quickjs.crawler.Spider`） |
| `:chaquo` | Python（Chaquopy）Spider 运行时（`com.fongmi.chaquo.Spider`） |
| `:nodejs` | Node.js Spider 运行时，在独立进程中运行 |
| `:thunder` | 迅雷下载 |

许可证：TV 为 **GPL-3.0**（`LICENSE.md`），在它基础上二次开发的代码也须按 GPL-3.0 发布。

QuickJS、Python、Node.js、迅雷都是**可下载的运行时模块**，不是 APK 自带：`catvod/…/utils/RuntimeModule.java:36-40` 定义了 `PYTHON / NODEJS / QUICKJS / THUNDER`，未安装时 `require()` 抛出 `"… module is not installed"`（同文件 138 行）。Java（`csp_`）Spider 不依赖这些模块。

## 2. Application 入口与初始化

`app/…/tv/App.java`，在 AndroidManifest 中注册为 `android:name=".App"`。

- `attachBaseContext`（88 行）：调用 `Runtime.set(base)`，并注册 `Runtime.setActivity(App::activity)`、`setToast` 和 `setDialog(SpiderDialog::show)`。这些是给 Spider 用的宿主回调。
- `onCreate`：`initRuntime()`（`RuntimeModule.refresh()`、清理运行时缓存、注册 `RuntimeReceiver`），然后创建通知渠道并注册 Activity 生命周期回调。
- `App.activity()` 只在有 Activity 处于 resumed 状态时不为 null（`onActivityResumed` / `onActivityPaused`）。

**结论**：`onCreate` 里没有加载配置，也没有创建 Spider。这些都是按需进行的。

## 3. 配置加载

`app/…/tv/api/config/VodConfig.java`（继承 `BaseConfig`）：

- `VodConfig.load(Config, Callback)`（70 行）依次调用 `clear()`、`config()`、`load(callback)`。
- `BaseConfig.load` 在 57 行和 112 行**先调用 `Server.get().start()`**（本地 HTTP 服务），再在后台调用 `load(Config)`。
- `load(Config)` 用 `Decoder.getJson(...)` 取得 JSON（支持 Node 配置），然后执行 `checkJson`：处理 `urls` 仓库，或者 `parseConfig`，后者再调用 `initSite`、`initParse` 等。
- `initSite`（207 行）把 `sites` 解析成 `Site` 列表，并决定首页站点（`setHome`）。
- `Config` 存在 Room 数据库里（`Config.vod()`、`Config.find`）。
- `clear(exiting)`（91 行）会调用 `BaseLoader.get().clear()` 或 `destroy()`。**换配置时会释放全部 Spider。**

## 4. Spider 管理与类加载器

### 4.1 统一入口：`BaseLoader`（单例）

`app/…/tv/api/loader/BaseLoader.java`

- `getSpider(key, api, ext, jar)`（132 行）按 api 分流：
  - `node:` 开头：`NodeLoader`
  - 含 `.py`：`PyLoader`
  - 含 `.js`：`JsLoader`
  - `csp_` 开头：`JarLoader`
  - 其他：`SpiderNull`
- `getSpider(key)`（147 行）：按 siteKey 从 `VodConfig` 或 `LiveConfig` 取 `Site`，再调用 `site.spider()`。
- `Site.spider()`（`bean/Site.java:348`）就是 `BaseLoader.get().getSpider(getKey(), getApi(), getExt(), getJar())`。
- `setRecent(...)` 记录「当前站点」，供本地 `/proxy` 路由使用。这是**全局可变状态**。
- `clear()` 和 `destroy()`（83 行起）：把当前各个 loader 换成新实例，旧实例在 `Task.execute` 里做 `clear` 或 `destroy`。

### 4.2 缓存与创建：`SpiderLoader`（抽象基类）

`app/…/tv/api/loader/SpiderLoader.java`

- 每个 loader 有一个 `Map<String, Spider> spiders`，**同一个 key 只创建一个实例并复用**。
- `load(key, context, ext, factory)`（70 行）：并发请求同一个 key 时共用同一个 `FutureTask`，`createSpider`（111 行）先调用 `factory.call()`，再调用 `spider.init(context, ext)`，然后缓存。
- `clear()`：关闭 loader（`closed = true`），取消加载中的任务，并对每个 Spider 调用 `close()`（关闭 JS、网络、对话框）。**不调用 `destroy()`。**
- `destroy()`（28 行）：做 `clear` 的全部步骤，再对每个 Spider 调用 `destroy()`。
- 关闭后再调用 `load` 会抛 `CancellationException("Crawler loader is closed")`。

### 4.3 Java（JAR）：`JarLoader`

`app/…/tv/api/loader/JarLoader.java`

- 每个 JAR（key 为 jar 地址的 md5）一个 `DexClassLoader`，父加载器为 `RuntimeModule.getSpiderClassLoader()`。
- 加载时调用 `com.github.catvod.spider.Init.init(App.get())`（`initDex`，85 行），并记录该 JAR 的 `Proxy.proxy(Map)` 方法。
- `newSpider`（192 行）：`loader.loadClass("com.github.catvod.spider." + api.substring(4))` 后实例化，再设置 `siteKey`、`net = new Net(JsHost.INSTANCE, key)` 和 `local`。
- 实例缓存的 key 是 `md5(jar) + siteKey`。
- JAR 支持 `url;md5;xxx` 校验，从 http 下载或用 `file://` 本地文件。

### 4.4 QuickJS：`JsLoader` 与 `:quickjs`

- `JsLoader.newSpider` 调用 `module.spider(api, dex, JsHost.INSTANCE)`。
- `quickjs/…/crawler/Spider.java`：每个 Spider 有自己的 `QuickJS ctx`（279 行），所有调用通过 `global.call(...)` 投递到该上下文的 JS 线程，**同一个 Spider 内部是串行的**。超时为 `host.getCallTimeout()`，即 `JsHost` 返回的 `Constant.TIMEOUT_VOD`（30 秒）；`destroy` 有单独的 5 秒期限。

### 4.5 Python：`PyLoader` 与 `:chaquo`

- `PyLoader` 构造时调用 `PythonRuntime.initialize()`，`newSpider` 调用 `loader.spider(api, key, JsHost.INSTANCE, …getUserAgent)`。
- `chaquo/…/Spider.java`：每个方法都是 `app.callAttr("homeContent", obj, …)`，受 Python GIL 约束。

### 4.6 Node.js：`NodeLoader`

`NodeRuntime.get().client().spider(api)`，运行在独立进程（`RuntimeModule.NODEJS` 的 "separate process"）。

## 5. Spider 方法的调用入口（业务层）

`app/…/tv/api/SiteApi.java`，全是静态方法，**不依赖任何 Activity 或 View**：

| 方法 | 行号 | 实际调用 |
|---|---|---|
| `homeContent(Site)` | 83 | `site.recent().spider().homeContent(true)`，再加 `homeVideoContent()` |
| `categoryContent(key,tid,page,filter,extend)` | 114 | `site.recent().spider().categoryContent(...)` |
| `detailContent(key,id,builtin)` | 140 | `site.recent().spider().detailContent([id])`，然后 `Source.get().parse(...)` |
| `searchContent(Site,keyword,quick,page)` | 301 | `site.spider().searchContent(...)` |
| `playerContent(key,flag,id,builtin,q)` | 181 | **`Source.get().stop()`**，然后 `loadPlayerContent` 和 `Source.get().fetch`（嗅探或解析） |
| `preloadContent(key,flag,id,builtin)` | 204 | 只调用 `loadPlayerContent`，**不进入 `Source` 解析与嗅探** |
| `loadPlayerContent` | 210 | `spider.playerContent(flag, id, VodConfig.get().getFlags())`，然后 `Result.fromJson`、`setHeader(site.getHeader())`（只在 Spider 没给 header 时生效，`Result.java:279`） |
| `action(key, action)` | — | `spider.action(...)` |

UI 层通过 `model/SiteViewModel.java` 调用上表方法：`ViewModelTaskRunner` 放到线程池执行（超时 `Constant.TIMEOUT_VOD` = 30 秒），多站搜索用 `ViewModelSearchRunner`（`Task.largeExecutor()`，20 线程，每站 30 秒超时，`cancel(true)` 中断）。

## 6. 现有本地 HTTP Server

- `app/…/tv/server/Server.java`：单例。`start()` 在 9978–9998 中找一个可用端口启动 `Nano`，并调用 `Proxy.set(port)`。`stop()` 在后台停止。
- `app/…/tv/server/Nano.java`：继承 `NanoHTTPD`。构造用的是 `super(port)`，**监听所有网卡（局域网可访问）**。路由由一组 `Process` 处理：`Action`、`Cache`、`Image`、`Local`、`Log`、`SpiderTest`、`Media`、`Parse`、`Proxy`、`Remote`、`Upload`、`Fetch`；未命中时返回 assets 网页。
- **已有的 Spider HTTP 接口**：`server/process/SpiderTest.java`，路径 `/spider-api`，是给网页「爬虫测试」用的会话式接口：
  - `POST /spider-api` 创建会话（最多 8 个，15 分钟过期）
  - `/load` 加载**任意 api/ext/jar**
  - `/run` 按方法名调用（`SpiderTestSession.java:197` 起用 switch 分派 `homeContent` / `categoryContent` / `detailContent` / `searchContent` / `playerContent` / `action` / `proxy` 等）
  - 还有 `/file`、`/save`、`/upload` 等，可以**读写共享存储里的源码文件**
  - 安全措施只有同源检查（`requireOrigin`，156 行），**没有鉴权**
- 它的设计目标是调试，**不能直接暴露到公网**（见 03 报告的风险 R1）。但它证明了：不打开播放页面，也能通过 HTTP 在后台线程调用 Spider。

## 7. Service 与后台运行

- `main/AndroidManifest.xml`：`PlaybackService`（`foregroundServiceType="mediaPlayback"`）和 `UpdateService`（`dataSync`）。`leanback` 另有 `DLNARendererService`。
- **没有专门让 Spider/HTTP 服务在后台常驻的 Service。** `Server.start()` 由加载配置时触发，`Server.get().stop()` 在 `HomeActivity` 销毁时调用（mobile 310 行、leanback 545 行）。也就是说，现在 HTTP 服务的生命周期跟随主界面。
- 线程池（`catvod/…/utils/Task.java:25-28`）：`executor` 5 线程，`largeExecutor` 20 线程，一个串行线程和一个调度线程，都是**进程级静态线程池**。

## 8. CatVodSpider

- `docs/development.md`：三种语言（Java / Python / QuickJS）共用一套接口：`init`、`homeContent`、`homeVideoContent`、`categoryContent`、`detailContent`、`searchContent`、`playerContent(flag, id, vipFlags)`、`action`、`proxy`、`destroy`。
- `app/src/main/java/com/github/catvod/spider/`：Demo、AList、WebDAV、Samba、Local、Market、Push、Proxy 等官方 Spider。`jar/custom_spider.jar` 是打包产物。
- SDK 引用：`app/libs/catvod-api.jar` 由 TV 的 `apiJar` 任务产出，以 `compileOnly` 方式引用。**Spider JAR 依赖宿主提供的 `com.github.catvod.*` 类**，所以宿主必须用 TV 的 `:catvod` 模块。
- 文档中的约束：
  - QuickJS 中，「JS 與 callback 仍在同一執行緒依序執行」。
  - Java 和 Python 的 `net.map` 回调在背景线程执行。
  - `getActivity()` 可能返回 null。
  - Java 和 Python 的对话框「在爬蟲工作執行緒同步呼叫」，没有 Activity 时抛出异常。

## 9. 增加 Spider API 的最佳位置（建议，未实施）

1. **不另起 HTTP 服务器**：在现有 `Nano` 中新增一个 `Process`，例如 `server/process/SpiderApi.java`，路径 `/api/v1/*`。这样可以复用端口、`Proxy` 和生命周期，也和现有 `SpiderTest` 风格一致。
2. **业务层直接调用 `SiteApi` / `VodConfig`**：
   - 用 `SiteApi.categoryContent / detailContent / searchContent / homeContent`。
   - 播放用 **`preloadContent`**（不进 `Source`，不会 `stop()` 正在播放的提取器），再由我们自己判断 parse/jx。
3. 新增独立的 `SpiderManager`（我们的类），负责：只接受配置里存在的 siteId、单站互斥或限流、超时、取消、计时。**不接收 api、jar 或类名**（与 `SpiderTest` 相反）。
4. 新增独立的前台 Service（`foregroundServiceType="dataSync"` 或 `specialUse`），负责启动或停止 `Server` 和 Tunnel。不再依赖 `HomeActivity`。
5. 以 flavor 或独立 module 的方式放置，尽量不改上游文件，便于和 FongMi 同步。需要改动的上游文件预计只有：`Nano.addProcess()`（加一行）、`AndroidManifest.xml`（注册 Service）、`Server.java`（监听地址可配置为 127.0.0.1）。
