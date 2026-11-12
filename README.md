# memshell-party-playframework

Play Framework 服务端插件，同时提供 Godzilla 与 Command 两类内存马。插件放在宿主
`plugins/` 目录即可在启动时被 `PluginBootstrap` 自动装载，无需修改宿主代码。构建目标是
`3.2.0-SNAPSHOT` 的宿主 SDK。

## 支持矩阵

| Play 版本 | HTTP 实现 | shellType | shellTool |
| --- | --- | --- | --- |
| 2.8.x | Akka HTTP 10.1.x | `AkkaHandler` | `Godzilla` / `Command` |
| 3.0.x | Pekko HTTP 1.0.x | `PekkoHandler` | `Godzilla` / `Command` |

`AkkaHandler` 与 `PekkoHandler` 共用同一份注入器和 shell 字节码；运行期按请求对象的包名
（`akka.*` / `org.apache.pekko.*`）选择目标 API，因此同一个插件 jar 同时覆盖两代 Play。插件通过
`getToolMappingOverrides()` 把这两个类型挂到内置工具上，不注册新的工具名，界面里的配置项与宿主
自带工具完全一致。

## 构建与测试

依赖全部来自远程仓库，不需要 `includeBuild`，也不需要本地发布 SDK。`settings.gradle.kts`
从 Maven Central 取发行版（shelltool `3.0.0` / `3.1.0` 等），从 Sonatype snapshots 取
`3.2.0-SNAPSHOT` 的宿主 SDK：

```text
https://central.sonatype.com/repository/maven-snapshots
```

本地和 CI 使用同一组仓库，避免开发机的 `mavenLocal()` 里残留一份不同的快照。仓库里没有
`includeBuild`，可以直接推到 GitHub。

```bash
./gradlew :plugin:test      # 单元测试
./gradlew :plugin:jar       # 插件 jar
./gradlew :integration-test:test
```

单元测试校验 `PlayFrameworkServer` 的注册信息：两个 Play 类型到同一个注入器的映射、
`Godzilla` / `Command` 的内置工具覆盖、Java 8 下限、`play.core.server` 包名、`handle` /
`matches` 契约，以及 `META-INF/services` 里的 SPI 条目。

集成测试会启动两个真实靶场（`range-playframework2`、`range-playframework3`），把生成的注入器
通过 `POST /vul/load` 投递进目标进程，再用 Godzilla 协议客户端和 Command 请求验证连通性：

- `pluginRegistersServerAndTools`：插件 jar 被发现，`PlayFramework` 服务端注册成功，内置
  `Godzilla` 与 `Command` 在它下面都有 `AkkaHandler` / `PekkoHandler` 两种类型。
- `serverRaisesTargetToJava8AndUsesPlayPackage`：`getMinimumTargetJreVersion()` 把默认目标抬到
  Java 8，`getShellPackageName()` 让 shell 落在 `play.core.server` 下。
- `godzillaShellStartsAndAnswers`：首个请求定义 payload，后续请求返回 `ok`。
- `commandShellExecutes`：表单参数与查询参数都能执行命令并回显。

Godzilla 客户端是仓库内的 `GodzillaTestClient`，直接实现 AES/md5 协议。官方的
`io.github.beichendream:godzilla:4.01` 是约 32 MB 的桌面端发行包，打包了 FlatLaf、GUI 和
BouncyCastle，没有可直接调用的无头客户端 API，因此没有作为测试依赖引入。

`@BeforeAll` 先跑 `PluginBootstrap.validate(pluginsDir)`，确认模板、契约、generator 构造器都完整，
再执行 `PluginBootstrap.load()`；校验失败时测试直接报出具体条目，不用等到生成期才暴露。

靶场只是注入载体：`/vul/load` 接收 base64 类字节并 `defineClass` + 实例化，模拟一个表达式执行
入口。真实环境用任意代码执行点加载 `MemShellResult#getInjectorBytes()` 即可。

## CI 与产物

`.github/workflows/ci.yml` 在 push 到 `master`、PR 和手动触发时运行四个作业：

1. `unit-test`：`./gradlew :plugin:test`。
2. `integration-test`：构建插件并跑 Play 2.8 / 3.0 两套靶场的端到端用例。
3. `package`：前两个作业通过后构建插件 jar，生成 `SHA256SUMS`，上传为 GitHub Actions
   artifact `memshell-party-playframework-plugin`。
4. `verify-artifact`：下载刚上传的 artifact，校验 SHA-256 和 jar 内容，然后通过
   `./gradlew :integration-test:test -PpluginJar=<下载的 jar>` 把 CI 产物重新注入两个靶场。
   这一步保证产物不仅能下载，还能实际生成并连接内存马。

本地取回产物：

```bash
gh run list --workflow=ci.yml --branch=master
gh run download <run-id> -n memshell-party-playframework-plugin -D downloaded
cd downloaded && sha256sum -c SHA256SUMS
```

`integration-test` 的 `preparePlugins` 默认复制 `:plugin:jar`；传 `-PpluginJar=<path>` 时改为复制
指定的 jar，`verify-artifact` 就是用这个入口验证下载件。

## 部署

```bash
mkdir -p <host>/plugins
cp plugin/build/libs/memshell-party-playframework-plugin-3.2.0-SNAPSHOT.jar <host>/plugins/
```

宿主启动时插件目录默认是 `<启动目录>/plugins`，可用 `-Dmemshell.plugins.dir=...` 或环境变量
`MEMSHELL_PLUGINS_DIR` 覆盖。

插件把 shell 类型挂在内置 `Godzilla` / `Command` 上，宿主 classpath 必须有
`memshell-party-shelltool-godzilla` 与 `memshell-party-shelltool-command`（boot 与 gui-app 的默认发行版
已包含）。插件本身不再编译依赖这两个模块。shell 模板由 `ByteBuddyShellGenerator` 通过
`AbstractServer#getInjectorTemplateClassLoader()` 解析，宿主 core 需要带该修复的 `3.2.0-SNAPSHOT`；
`getMinimumTargetJreVersion()` 与 `getShellPackageName()` 也要求 3.2 宿主。

内存马需要读取请求实体、改写流阶段字段并定义 payload 类，目标 JVM 必须放开对应模块：

```text
--add-opens java.base/java.lang=ALL-UNNAMED
--add-opens java.base/java.util=ALL-UNNAMED
--add-opens java.base/java.util.concurrent=ALL-UNNAMED
```

## 生成

```java
ShellConfig shellConfig = ShellConfig.builder()
        .server("PlayFramework")
        .shellTool("Godzilla")           // 或 "Command"
        .shellType("PekkoHandler")       // Play 3.x；Play 2.x 用 "AkkaHandler"
        .targetJreVersion(52)
        .build();

Map<String, Object> params = new HashMap<>();
params.put("pass", "playpass");
params.put("key", "0123456789abcdef");

MemShellResult result = new MemShellGenerator().generate(
        shellConfig, InjectorConfig.builder().urlPattern("/*").build(), params);

// 投递：result.getInjectorBytes() 是单类产物，不含内部类。
```

Command 工具用 `paramName` 指定参数名，查询串、请求头、`application/x-www-form-urlencoded`
表单三种位置都可取参。

## 实现要点

- **注入点**：`akka.stream.impl.fusing.MapAsync[Unordered].f`（Pekko 同名）。每次 `onPush`
  都通过 `$outer.f()` 读取该字段，替换为 `scala.Function1` 动态代理即可在路由前拦截请求，
  未命中的请求原样透传给原 handler。
- **定位**：从当前线程与 Akka/Pekko 的 `ForkJoinWorkerThread` 出发，按对象图 BFS 找到流阶段
  对象；同时收集 `Materializer` 与 `ActorSystem`，找不到时用 `Materializer$.matFromSystem` 兜底。
- **实体物化**：Play 请求实体默认是流式的，需要 `entity.toStrict(3000, materializer)`。这一步
  会阻塞，因此 shell 逻辑在独立线程执行，避免 Play 2.8 的小调度器在 `MapAsync` 回调里死锁。
- **响应构造**：手工拼 `StatusCode`、`ByteString`、`HttpEntity.Strict`、`Seq` 与
  `HttpResponse`，再包装为 `Scala Future` 返回给流阶段。
- **单类投递**：注入器本身兼作 `InvocationHandler` 与后台任务的 `Runnable` 代理，shell 也不含
  内部类，所以一次请求只投递一份类字节。
- **SDK 3.2 钩子**：`getToolMappingOverrides()` 把两个 Play 类型挂到内置 `Godzilla` / `Command`，
  工具名、配置项和界面条目都与宿主一致。shell 模板由 `ByteBuddyShellGenerator` 通过
  `AbstractServer#getInjectorTemplateClassLoader()` 解析，插件不用再把 config 类搬进插件加载器。
  `getMinimumTargetJreVersion()` 声明 Java 8 下限，调用方给更低值时由生成入口自动抬高；
  `getShellPackageName()` 把 shell 放进 `play.core.server`，不再落到通用
  `org.apache.http.web.handlers`。集成测试用 `PluginBootstrap.validate()` 在生成前校验模板与契约。

## 上游改进建议

1. **反应式服务端的请求/响应适配 SPI**。当前 shell 契约默认拿 Servlet 的 request/response；本插件
   只能为 Akka/Pekko 重写整套 Godzilla/Command 协议适配（取参、物化、构造响应、异步包装）。
   建议增加 server-neutral 的 `ShellRequest`/`ShellResponse` 或 `matches/handle` 生命周期，
   让同一份工具实现复用到 Servlet、Netty、Akka 等多种传输。
2. **shell 的“快速匹配 + 异步处理”契约**。反应式服务端不能同步读 body，否则会阻塞调度器；
   插件只能自己实现 `matches(request)` 预判与独立线程执行。建议把这一步纳入 SPI 文档和类型。
