# P5 八音盒缓存压力实施与验收设计

> 阶段工单：[Issue #5](https://github.com/SGSxingchen/blindbox-challenge-forge-1.20.1/issues/5)。本文件是已落盘生产缓存加固与下一轮 Hosted Runner 验收的边界记录；在同 SHA 真实六门禁成功前，不能把它写成通过证据。

## 已落盘的生产缓存加固

`RemoteAudioDownload` 仍只在客户端运行，单个游戏目录的下载上限为 16 MiB、缓存上限为 64 MiB。P5 将缓存复验、原子改名和 LRU 淘汰放到同一短临界区：网络、DNS、TLS、响应体写入、完整 OGG/MP3 解码和 SoundEngine 播放都不会持有该锁。

P4 的短音频继续从同一提交的 `raw.githubusercontent.com` 读取。P5 的 14 MiB 原创 ciTest OGG 则固定从 `cdn.jsdelivr.net/gh/<仓库>@<当前 Git SHA>/...` 读取：它只是同一不可变 Git 提交的 HTTPS 只读映射，目的是让 `.ogg` 获得正确的 `audio/ogg` 响应头。GitHub Raw 会把该大文件标为 `application/octet-stream`，而生产下载器必须严格拒绝该泛型类型，不能为 CI 放宽。jsDelivr URL 仍受生产客户端的公开 DNS、固定 IP TLS、SNI、无 Cookie/认证、16 MiB、Ogg 文件头与摘要缓存校验约束；质量门禁锁定当前 SHA 路径与专用 P5 基址。它不表示第三方音频可用性或 CDN 长期可用承诺。

每次 `fetch` 返回的是独立的短租约，而不是被同 URL future 共享的可关闭对象。租约从下载/命中返回起保留到工作线程打开并完整解码缓存文件；LRU 会跳过仍被租用的条目，解码完成立即释放并重新收敛缓存。这样并发 URL 的新提交不能在另一个异步任务 `Files.newInputStream` 前删除它；PCM 已入内存后也不会因长音频播放而永久钉住磁盘缓存。

缓存访问时间使用持久文件 mtime，并在同一毫秒的连续访问中单调递增；淘汰以该时间再以文件名作确定性次序。该策略的范围是**单个客户端进程和游戏目录**，不声称两个进程故意共用同一游戏目录时也有跨进程锁。

同 URL 的 `IN_FLIGHT` future 只共享不可关闭的内部缓存条目；每名等待者领取自己的租约。生产声音实例保留一个只读 `singleFlightFollower` 事实，表示该真实下载确实等待了本 JVM 已在途的同 URL future；它与 `cacheHit` 独立，二者都不等同于播放成功。P5 探针只能在 SoundEngine 实际读取到 PCM 后记录它们。

## 受控真实场景

压力夹具是本仓库提交的原创合法 OGG，且只打入独立 `ciTest` Jar：不复用任何 `source-package/` 原始音频、照片、压缩包或第三方成品音频。之所以提交到 `src/ciTest` 而不是 Hosted Runner 临时生成，是因为真实客户端必须从本 SHA 的 GitHub raw HTTPS 重新下载；ciTest Jar 内的本地文件不能伪装公网下载。夹具单文件严格大于 13 MiB 且不超过生产 16 MiB 响应上限；五个只差 query 的 HTTPS URL 因规范化后的 URL hash 不同而形成五个独立缓存键。夹具的 PCM 在首次 SoundEngine read 后由测试声音自然关闭，避免测试长音频占用两条生产播放槽；关闭发生在已经得到真实 PCM 之后，不能替代下载、解码或播放。

每一步都由 Alice 的生产方块右键、`MusicBoxScreen`、真实编辑控件输入、服务端一次性会话校验和生产 S2C 广播触发；服务端与探针均不直调下载器、`MusicBoxService.play`、业务包或 SoundEngine read。两台真实 Forge 客户端都必须收到同一生产事件并交叉提供 PCM marker。

验收顺序为：

1. 以同一个配置 URL 的两次真实普通右键生成两个不同事件；两端必须在 PCM marker 中同时证明一条 owner 与一条 `singleFlightFollower`，且都不是缓存命中。
2. 依次经真实 GUI 配置并播放另外四个 query URL；每个 URL 在两端均须有不同事件 UUID、生产 S2C、首个 PCM 和首次非命中事实。
3. 五个大条目超过 64 MiB 后，再经真实 GUI 播放第一个 URL；两端必须以非命中、不同事件和真实 PCM 证明它被 LRU 驱逐后重新下载，而非仅检查文件存在。
4. 对仍在缓存的第二个 URL，探针只能在前一次真实 PCM 后破坏本地该 URL 的已校验缓存文件，并留下非成功的操作事实；再次经生产 GUI 播放时，`findCached` 必须删除坏摘要文件、真实重新下载并在两端得到非命中 PCM marker。

服务端会逐个回读 marker 的玩家 UUID、事件 UUID、URL、源方块、S2C 事实、PCM 字节数、缓存命中与单飞字段；旧 marker、预写成功 marker、只有文件存在、缺少任一客户端、吞异常或缩短超时均为失败。P4 的短 OGG/MP3、失败和断线不补播路径保持独立，不能被 P5 压力结果替代。

## 未覆盖边界

该场景只验证受控 GitHub HTTPS 夹具与单进程缓存语义，不承诺任意第三方音频的可用性、带宽、版权、长期地址稳定性，也不替代历史正式资源的作者/许可证追溯。历史资源授权仍是正式 Release 阻塞项。
