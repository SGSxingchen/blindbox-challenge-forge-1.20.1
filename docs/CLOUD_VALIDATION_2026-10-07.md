# 云端验证与 PNG 生成器跨平台修正（2026-10-07）

## 环境与范围

本轮在 dot 云端 Linux amd64 工作区检出 `codex/item-corrections`，基线提交为 `1f59b55c2828187a89341966a65f657799ae2aec`。只同步本分支，不修改 master，不恢复 GitHub Actions，不创建 PR、tag 或 Release。

工具链为 Eclipse Adoptium JDK 17.0.20.1、Gradle 8.8、Python 3、Pillow 12.3.0 与 zlib 1.3.2。Gradle 从官方 `https://services.gradle.org/distributions/gradle-8.8-bin.zip` 下载，并对照官方 `.sha256` 校验为 `a4b4158601f8636cdeeab09bd76afb640030bb5b144aafe261a5e8af027dc612`。

## 已复现问题与修正

原始 148 项工具测试在 Java 17 可用时全部执行，146 项通过、2 项失败，无跳过。9 张物品 PNG 在当前 Pillow/zlib 下重新编码的字节与已提交资源不同，但尺寸、RGBA 模式与每个像素均一致。这是生成器字节可复现性问题，不是美术内容变化。

沿用项目现有固定 PNG 载荷惯例，将剩余 10 张仍依赖 Pillow 编码的物品图标固定为已验收 PNG 字节，其中 9 张已复现漂移，另一张一并消除同类环境依赖。新增独立载荷模块，不修改正式 PNG、资源清单、游戏代码或已有高清徽章。保留旧 RGBA 载荷作为独立像素基准，目标列表按原顺序去重。

新增两项回归验证：

- 固定 PNG 与原 RGBA 基准逐像素相等，目标恰为 59 项且无重复。
- 全部 59 张物品图标不调用平台 PNG 编码器、不读取工作区正式图，仍可从源码载荷重建。

固定完整 PNG 比只锁定 Pillow 版本更符合本项目的逐字节契约：PNG 压缩输出还可能受底层 zlib 与平台影响。本轮保留既有字节校验，没有改成只比较像素或放宽预期值。

## 本轮实测结果

- `python3 -m unittest discover -s tools/tests -v`：150 项全部通过，无跳过。
- `python3 tools/generate_original_textures.py --check`：通过。
- `python3 tools/generate_original_models.py --check`：通过。
- `python3 tools/generate_original_metadata.py --check`：通过。
- `python3 tools/verify_quality_contract.py`：通过，67 项玩家物品创造栏静态映射一致。
- `python3 tools/verify_item_texture_redraw.py`：59 项通过。
- `git diff --check`：通过。

## 构建阻塞与未验收项

Gradle 8.8 本身可正常启动；`check build ciTestJar` 在解析设置插件 `org.gradle.toolchains.foojay-resolver-convention:0.7.0` 时失败，未进入 Java 编译。详细日志显示 Java 到环境默认代理 `browser-proxy:8889` 的连接报 `Network is unreachable`；显式采用当前 HTTP 代理也未完成解析。受审查的扩展网络执行则在启动前报 bubblewrap `/root/.codex` 挂载目标不是目录。普通 curl 可以取得官方分发包与插件 POM，因此不能把失败归因为插件不存在。

当前 Gradle 依赖缓存没有可用构件；仅手动下载首个插件 POM 无法补齐 ForgeGradle、Mixin、Minecraft 映射/合并处理器、Mojang 运行库及 GeckoLib 等完整依赖图。没有篡改 Gradle 内部缓存元数据、替换仓库或禁用校验来制造构建成功。

因此本轮尚未通过完整构建、正式包/探针隔离、真实专服、客户端画面、音频生命周期/人工听感、生存获取、盲盒真实多人同步与非空奖池发奖测试。历史本地通过记录不视为本轮云端证据。仓库已有 Linux CI 脚本；Mac 专用启动脚本不应在未完成云端构建前盲目替换。

## 后续：依赖连接恢复与验证脚本跨平台化

进一步定位发现：当前执行工具提供的 HTTPS 代理端口随调用变化，不能沿用上一次调用的端口。按同一次执行的 `HTTPS_PROXY` 为 Java 配置代理后，Gradle 已通过设置插件解析并开始下载后续依赖。上文是首次失败记录；当前尚不据此宣称完整构建或游戏测试通过。

本轮同时修正本地验证脚本的固定 Homebrew Java 路径。公共解析器按 `--java`、`JAVA_HOME/bin/java`、`PATH` 顺序选择，校验可执行权限、命令退出状态与 Java 17 主版本，并设置 10 秒版本检查超时；显式配置错误时拒绝运行，不悄悄改用另一份 Java。专服脚本接受 `--gradle-cache`，默认遵循 `GRADLE_USER_HOME/caches` 或 `~/.gradle/caches`。任意门恢复编排把选定 Java 明确传递给两个客户端。

保留既有 `install-client-macos.py`、`run-client-macos.py` 文件名以兼容旧命令，但启动逻辑不再固定 macOS：`-XstartOnFirstThread` 仅在 Darwin 使用，Apple Silicon 原生库过滤也同时限定 Darwin 与 arm64。没有改动账户、模组内容、判定 marker 或隔离目录边界。

新增 10 项运行环境回归，累计 160 项全部通过，无跳过。云端实际 Java 17 路径解析、Gradle 缓存解析、Linux 无首线程参数、四个命令的 `--help`、全部本地验证脚本语法，以及三类资源生成器/静态契约/59项清单检查均通过。客户端启动依赖 `minecraft-launcher-lib==8.0` 安装于独立虚拟环境。这些结果仅证明运行前置和脚本参数，不能替代 Forge 专服或客户端实际启动。


## 最终云端构建与同包专服证据

源提交为 `6a19766ae0c9dc98dfb16bb51443a42bbc6d979e`。完整 `check build ciTestJar` 已通过；最终复跑 23 秒完成。Gradle `test` 为 `NO-SOURCE`，不能冒充 Java 单元测试；160项工具回归独立通过。后续此记录提交只变更文档，不改变被测代码。

最终固定产物：

- 正式完整包 SHA-256：`50bcfcf046b65e1959864fa56b3a5ffbb5bb68f94a1f82f804a2f366b0533a07`
- 独立探针包 SHA-256：`716953c94a5d5fffd197d8c81247590965ff74b8411e5eddf9f5c911a3a74e3d`

构建复跑因现有 Manifest 时间戳改变正式包字节，故没有沿用首个包的通过记录，而是冻结最终包并重新执行全部本轮专服验证。正式包/探针隔离、内嵌 GeckoLib/JLayer 与 JLayer 许可文本检查通过。

最终正式包在云端 Forge 47.4.22 独立专服两次正常启动并退出（均为0），67项物品命令解析/注册通过，67条奖池内容保存后重启保留。另一独立世界仅额外安装本次探针，既有开盒断言通过：本次80刻快照不被中途10刻配置或重复使用重置，下一次使用新配置，取消与空池完成不消耗且清理使用态。该探针使用模拟玩家，不是实际多人或非空池发奖验收。

所有测试服务器仅绑定 `127.0.0.1` 并使用独立临时世界，未复制账户或用户世界；最终三次专服运行均已正常停止。日志中有 Yggdrasil 公钥下载失败，作为离线网络限制保留；没有把离线启动冒充在线认证通过，也没有服务端 tick 崩溃或客户端类隔离错误。

完整机器可读摘要与日志摘要值见[本轮云端证据](assets/cloud-validation-2026-10-07.json)。实际图形客户端仍未通过：显示进程受 Unix 套接字权限/挂载故障阻塞，客户端安装器额外下载内置 Java 的官方 manifest 又遭代理403，未换路请求或关闭校验。人工视觉、声音生命周期/听感、生存获取、真实多人同步及非空池发奖仍待有条件时验收。
