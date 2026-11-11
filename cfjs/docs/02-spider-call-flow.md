# 02 · Spider 调用流程

> 版本同 01（TV `5b41d9d`，CatVodSpider `98d6f2c`）。`app/…/tv/` 指 `TV/app/src/main/java/com/fongmi/android/tv/`。

## 1. 总流程（以 UI 打开详情为例）

```
Activity / Fragment
  -> SiteViewModel.detailContent(key, id)              model/SiteViewModel.java
     -> ViewModelTaskRunner.execute(..., TIMEOUT_VOD=30s)
        -> Task.submit(callable, 30s)                   catvod Task.executor()（5 线程）
           -> SiteApi.detailContent(key, id, false)     api/SiteApi.java:140
              -> VodConfig.get().getSite(key)
              -> site.recent()                          bean/Site.java:343  -> BaseLoader.setRecent（全局）
              -> .spider()                              bean/Site.java:348  -> BaseLoader.getSpider(key, api, ext, jar)
                 -> JarLoader / JsLoader / PyLoader / NodeLoader
                    -> SpiderLoader.load(...)           同一个 key 只创建一次：new + init(context, ext)
              -> spider.detailContent([id])             返回 JSON 字符串
              -> Result.fromJson(...) + Source.get().parse(vod)
     -> LiveData.postValue(result)                       回到 UI
```

同一个任务类型再次提交时，旧任务会被 `cancel(true)`（`ViewModelTaskRunner.java:31`）。中断只对能响应中断的代码有效。

## 2. 六个方法逐一分析

接口定义见 `catvod/src/main/java/com/github/catvod/crawler/Spider.java`，所有方法都返回 `String`（JSON）并允许抛出 `Exception`。

### 2.1 homeContent(boolean filter)

| 项 | 内容 |
|---|---|
| 实际调用 | `SiteApi.homeContent(Site)`（83 行）：`spider.homeContent(true)` 加 `spider.homeVideoContent()`，后者的 `list` 不为空时覆盖 |
| 输入 | `filter=true`（宿主固定传 true） |
| 输出 | `{"class":[{type_id,type_name}], "filters":{tid:[...]}, "list":[vod...]}` |
| 线程 | ViewModel 线程池；QuickJS 再转到该 Spider 自己的 JS 线程 |
| 异步 | 宿主侧异步（Future）；Spider 方法本身是同步的 |
| 异常 | 向上抛出，`SiteViewModel.handleError` 转成 `Result.error` 并弹 Toast |
| 全局状态 | `Prefers "crash"`：上次崩溃后 `CrashActivity` 把它置为 true，下次首页**跳过 Spider 调用**；`setRecent` |
| 非 UI 线程 | 可以（本身就在线程池里调用） |

### 2.2 categoryContent(tid, pg, filter, extend)

| 项 | 内容 |
|---|---|
| 实际调用 | `SiteApi.categoryContent`（114 行） |
| 输入 | `tid` 字符串、`pg` 页码字符串、`filter`、`extend` 为 `HashMap<String,String>` 筛选条件 |
| 输出 | `{"page","pagecount","limit","total","list":[vod...]}` |
| 线程、异步、异常 | 同 2.1 |
| 全局状态 | `site.recent()` |

### 2.3 searchContent(key, quick[, pg])

| 项 | 内容 |
|---|---|
| 实际调用 | `SiteApi.searchContent(Site, keyword, quick, page)`（301 行）：页码为 "1" 时调用两参数版本，否则调用三参数版本 |
| 输入 | 关键字先经过 `Trans.convert(keyword, site.getLang())` 做简繁转换 |
| 输出 | `{"list":[vod...]}`，宿主给每个 vod 写入 `site` |
| 线程 | 多站搜索：`ViewModelSearchRunner`，`Task.largeExecutor()`（20 线程），每站 `TIMEOUT_SEARCH` 为 30 秒，新搜索开始时 `cancel(true)` 上一轮 |
| 全局状态 | **不调用 `recent()`**，所以搜索不会改变代理路由 |
| 非 UI 线程 | 可以 |

### 2.4 detailContent(List<String> ids)

| 项 | 内容 |
|---|---|
| 实际调用 | `SiteApi.detailContent(key, id, builtin)`（140 行），宿主**每次只传一个 id** |
| 输出 | `{"list":[{vod_id,vod_name,vod_pic,vod_content,vod_play_from,vod_play_url,...}]}`；线路用 `$$$` 分隔，集用 `#` 分隔，名称与地址用 `$` 分隔 |
| 后处理 | `Source.get().parse(result.getVod().setFlags())`：把每集的地址交给提取器预解析（需要时并发，30 秒） |
| 全局状态 | `site.recent()`；`Source` 单例 |

### 2.5 playerContent(flag, id, vipFlags)

| 项 | 内容 |
|---|---|
| 实际调用 | 播放：`SiteApi.playerContent(...)`（181 行）。它**先调用 `Source.get().stop()`**（停掉全局提取器），再走 `loadPlayerContent`（210 行），最后用 `Source.get().fetch` 做嗅探、解析、Push 等 |
| 只取结果 | `SiteApi.preloadContent(...)`（204 行）只走 `loadPlayerContent`，不碰 `Source` |
| 输入 | `flag` 线路名、`id` 集地址、`vipFlags = VodConfig.get().getFlags()`（配置里的 `flags`） |
| 输出 | `Result` 字段（`bean/Result.java`）：`url`（字符串，或 `[名称, 地址, …]` 多画质数组）、`parse`（0 直链 / 1 需嗅探或解析）、`jx`、`playUrl`、`header`、`format`、`subs`、`danmaku`、`drm`、`position`、`click`、`key`、`flag` 等 |
| 请求头 | Spider 返回的 `header` 优先；只有它为空时才用站点配置的 `header`（`Result.setHeader`，279 行）。UA、Referer、Cookie 都在这个 Map 里 |
| 全局状态 | `Source` 单例的 `begin`/`end`/`stop` 是 `synchronized` 的，播放和 API 同时调用会互相影响；还有 `recent()` |

### 2.6 destroy()

| 项 | 内容 |
|---|---|
| 实际调用 | 只有 `SpiderLoader.destroy()` 调用它，由 `BaseLoader.destroy()` 和 `VodConfig.destroy()`（`clear(true)`）触发，也就是**退出时** |
| 换配置 | `BaseLoader.clear()` 只调用 `spider.close()`（关闭网络、JS、对话框），**不调用 `destroy()`**；旧 loader 进入 `retiredLoaders`，直到下次 `destroy` 才统一 `destroy` |
| QuickJS | `quickjs/…/Spider.destroy()`（223 行）：取消请求，执行 JS 的 `destroy`（5 秒期限），关闭上下文 |
| Python | `close` 时调用 `app.callAttr("release", obj)` |

## 3. 各运行时的线程模型

| 运行时 | 实例 | 同一实例并发调用 | 超时与取消 |
|---|---|---|---|
| Java（JAR） | 每个 `md5(jar)+siteKey` 一个 | **宿主不加锁**，由各 Spider 自己保证线程安全（上游本身就会并发调用：多站搜索加打开详情） | 宿主侧 Future 超时；`Net` 关闭时取消网络；纯计算或第三方阻塞无法强制停止 |
| QuickJS | 每站一个 QuickJS 上下文和一个 JS 线程 | 在 JS 线程上**串行**执行 | `getCallTimeout()` 为 30 秒；中断会取消 Future 并取消网络 |
| Python | 每站一个 Python 对象 | 共享一个解释器，受 GIL 限制 | 依赖线程中断与 `net` 取消 |
| Node.js | 独立进程 | 由 Node 侧决定 | 进程级 |

## 4. 七个重点问题的结论

1. **不进播放页能否调用 Spider？可以。** `SiteApi` 全是静态方法，`SpiderTest`（`/spider-api`）已经在 HTTP 线程池里调用了所有方法。前提是先加载好配置（`VodConfig.load`），QuickJS 或 Python 站点还要装好对应运行时模块。
2. **能否同时管理多个 Spider？可以。** 每个 loader 按 key 缓存多个实例，上游的多站搜索就是这么用的。
3. **是否依赖 Activity 或 UI 生命周期？** 调用本身不依赖。但有三处会用到 UI：
   - `JsHost.openWeb` 和 `getUserAgent` 依赖 `WebActivity`，`getCookie` 依赖 WebView Cookie。
   - Spider 主动弹 `dialog` 时，没有 Activity 就会失败。
   - 不过 `Server.stop()` 挂在 `HomeActivity` 销毁时，**HTTP 服务目前跟随主界面**。
4. **如何在后台执行？** 新建一个前台 Service，持有 `Server` 和配置的生命周期，调用 `SiteApi` 走 `Task` 线程池，不依赖 Activity。
5. **如何拿到原始地址和请求头？** 用 `SiteApi.preloadContent(key, flag, id)` 得到 `Result`，读 `getUrl()`（含多画质）、`getHeader()`、`getParse()`、`getJx()`、`getPlayUrl()`、`getFormat()`。不要用 `playerContent`：它会停掉全局 `Source`，还会把 parse=1 的结果交给 WebView 嗅探。
6. **如何避免搜索互相干扰？**
   - 每个 API 请求一个独立 Future，不复用 ViewModel 的「同类型取消旧任务」逻辑。
   - 每站加信号量（第一版并发数为 1）。
   - 搜索不调用 `recent()`；分类、详情、播放会改 `recent`，这只影响 `/proxy` 的默认路由，因为带 `siteKey` 的代理地址会直接路由到对应站点（`BaseLoader.proxy`）。
7. **如何正确释放资源？** 停止服务时调用 `VodConfig.get().destroy()`，它会依次调用 `BaseLoader.destroy()` 和每个 Spider 的 `close()` 与 `destroy()`；QuickJS 会关闭上下文，Python 会调用 `release`。还要额外处理三件事：
   - `Server.stop()`
   - Node 子进程：`NodeRuntime` 有 `clear`/`cancelLoad`，需要验证它是否随之退出
   - 进程级线程池（`Task`）不会被关闭，它们在 App 进程内常驻，空闲时不耗电，但会占内存

## 5. 待真机验证（不能只凭读代码下结论）

- `VodConfig.load` 没有 Activity 时能否完整走完（它依赖 Room 和网络，预计可以）。
- 服务只在后台运行时，`JsHost.getUserAgent()` 返回什么（可能为空字符串）。
- 停止服务后，内存和线程是否真的回落。
