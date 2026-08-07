# P5 中性原创装饰方块与发布准备验收矩阵

> 阶段工单：[Issue #5](https://github.com/SGSxingchen/blindbox-challenge-forge-1.20.1/issues/5)。三个装饰方块子范围已取得同 SHA 六门禁；P5 整体仍待音频缓存压力、发行资料与资产法律边界完成，故工单保持 OPEN、不得 Release。所有动态 Forge/Minecraft 结论仅以 GitHub Hosted Runner 为准。

|范围|必须真实验收|
|---|---|
|三项装饰方块|正式 `BlockItem` 先经原版 `ItemEntity` 拾取，再由真实客户端 `keyUse` 放置；服务端复验状态/扣除为零、两客户端同步观察同一方块状态与掉落实体 UUID，真实 `keyAttack` 破坏后 count=1 正常战利品须由原版拾取恰一次回收；不得用目标格 `setBlock`、creative、直接给物、删除后补物或预写 marker 替代|
|资源闭合|注册、BlockItem、方块状态、模型、纹理、战利品、双语闭合；PNG 16×16 RGBA、原创资源清单与 SHA-256 完整|
|专服隔离|主源码无客户端导入；不新建 BE、菜单、网络包或 Capability；正式 Jar 排除 ciTest/ci-audio|
|音频压力|真实 GUI→下载→PCM 覆盖 64 MiB LRU、驱逐重下、同 URL 单飞、损坏缓存删除重试，测试音频只在 ciTest|
|发行准备|统一正式版本、中文说明、JLayer 许可证、Jar SHA-256、真实未覆盖边界；仅全量同 SHA 六门禁成功后创建 tag/Release|

P5 未验收前不得创建 Release，也不得把受控测试推广为第三方音频可用性、授权或长期压力承诺。

## 缓存压力实现已落盘，尚待 Hosted Runner

本批新增与 P4 短音频场景隔离的 `P5MusicCacheCiScenario` 和 `CiClientP5MusicCacheObservation`。P5 段只在 P4 的 PCM、失败、Bob 真实重连且不补播断言均已成功后、P4 cleanup 前启动另一枚生产八音盒；这保证它既不重排 P4 语义，也继续使用同一专服与两个真实 Forge 客户端。

Alice 对每个 URL 都必须经过生产 `MusicBoxScreen` 的潜行右键、真实输入框清空/键入、一次性服务端提交和普通右键；服务端不直接调用下载、播放服务或 S2C。五个 `?ci=p5-fill-N` URL 必须各自在两端完成生产 S2C 与 SoundEngine 非空 PCM，并以严格 `cache_hit=false` 逐 UUID 回查；第一个 URL 再次配置后仍须两端非命中 PCM，才算 LRU 驱逐后的真实重下。随后 Alice 连续两次真实普通右键同一未缓存 URL；两端各自两枚 PCM marker 必须恰有一条 owner 与一条 `single_flight_follower=true`，以生产 `IN_FLIGHT` future 的真实等待事实证明每个 JVM 的单飞，而不是用另一个客户端进程冒充。

最后，两端只能在自己的两条单飞事件都已真实读取 PCM 后，截断各自实际存在的 fill-5 缓存到 64 字节并写非成功操作事实；服务端回读两个截断事实后，Alice 仍须从 GUI 重配 fill-5，两个客户端均以新的事件 UUID、非命中与 PCM 证明摘要校验删除坏文件并真实重试。脚本只写阶段旗标，严格等待服务端成功日志后放行下一步；成功 marker 一律由客户端 SoundEngine read 写入。P5 大 OGG 仅位于 `src/ciTest/resources/ci-audio/`、严格大于 13 MiB 且不超过生产 16 MiB，GitHub raw HTTPS 才能从同 SHA 提供真实公网下载；正式 Jar 与正式资源清单继续排除它。

生产缓存同时补齐短租约与确定性 LRU：同 URL 等待者不共享可关闭值；从 fetch 返回到完整解码打开文件前，仍在租约中的条目不得被并发 LRU 删除；缓存命中/新提交的 mtime 在同毫秒仍单调递增。这些是实现和静态门闩，不是动态验收结果。当前 P5 总体仍未通过，#5 继续 OPEN。

## 当前待验证实现

`P5DecorCiScenario`、`CiClientP5DecorObservation`、双客户端启动参数、阶段旗标、旧 marker 拒绝和服务端逐 UUID 反查均已落盘；同一场景的显式单客户端命令以及 Hosted-only 一服一客户端脚本也已接入 `single-client.yml`，但两者均尚未产生本阶段 Hosted Runner 成功证据；本表仍为 **0/6**。全资源清单、64 MiB 音频缓存压力、发行说明和正式版本仍未完成，不能用已有主菜单 smoke 或 P4 旧证据替代。

`4e79779` 的单客户端 run `31168158886` 已把第 2 轮首错收窄为低矮地面画板的准星穿过选择轮廓，`0be0b2b` 仅将其真实攻击瞄准点降到共同底座内，仍待 Hosted 验证。该 SHA 的双客户端 run `31168158859` 另在 R1 放置、破坏、正常掉落成功后，于 R2 `WAIT_FOR_INITIAL_PICKUP` 超时；日志已明确 P4 文本让 Bob 真实死亡且既有验收禁止其后复活。后续双端修复必须保持两个真实 Forge 客户端及两份逐 UUID marker，正确做法是把 P5 放到文本死亡前，让 Alice/Bob 各自真实拾取/输入，不能用复活、直接给物或死后观察替代。P5 仍为 **0/6**。

`0be0b2b` 的单客户端 run `31168748820` 已进一步验证低位准星确实命中 R2 目标并连续稳定 8 tick，攻击保持也已注入，但仍未开始破坏；这是新的严格首错，不得以 marker、缩短等待或直调客户端 `gameMode` 规避。后续只补一次 `KeyMapping` 攻击点击来启动原版输入循环，并继续保持攻击键；质量门禁禁止直接网络包、目标格改写、`gameMode`、`destroyBlock`、直接给物等替代。P5 仍为 **0/6**。

`7e8a533` 的单客户端 run `31169408832` 表明 attack click 后 R2 仍严格停在 `WAIT_FOR_BREAK`，但客户端命中和攻击注入均为真；源码复核已定位为旧 R1 的持久阶段旗标在每 tick 取消全局攻击键，覆盖 R2 的 held attack。后续只将抬键时机移至完整轮次循环之后，并以仍存在的当前生产方块决定保持；不改真实输入、服务端掉落/回收、双端 marker、时限或任何生产玩法。P5 仍为 **0/6**。

`6737f52` 的单客户端 run `31170034072` 已完整通过三轮真实放置—破坏—掉落—拾取和 cleanup。双客户端 run `31170034066` 的三轮服务端链与 Alice marker也完整成功，但 Bob 已在 P4 文本真实死亡且八音盒不补播重连后才进入 P5，未形成其三轮观察 marker；这不能用单端或 Alice marker 冒充双端通过。后续固定将 P5 段移动到交接平台已准备/小黄鸡 cleanup 后、`run_p4_text_negative` 前，并恢复 Bob 的 R2 原版操作；质量门禁锁定此顺序，P5 仍为 **0/6**。

## `895692c` 装饰方块子范围基线：6/6

`895692cde1bb8de2d3ddc413fcb5a9dd7b3ea6d3` 的同 SHA Hosted Runner 已将本子范围六项门禁全部跑绿：

|门禁|真实结果|
|---|---|
|质量与构建|[success 31171119943](https://github.com/SGSxingchen/blindbox-challenge-forge-1.20.1/actions/runs/31171119943)：正式 Jar/ciTest 隔离、资源闭合、角色与执行顺序、禁止替代路径静态门禁通过|
|专用服务器|[success 31171119944](https://github.com/SGSxingchen/blindbox-challenge-forge-1.20.1/actions/runs/31171119944)|
|强杀恢复|[success 31171120835](https://github.com/SGSxingchen/blindbox-challenge-forge-1.20.1/actions/runs/31171120835)|
|真实单客户端|[success 31171121065](https://github.com/SGSxingchen/blindbox-challenge-forge-1.20.1/actions/runs/31171121065)：Alice 三轮均由原版 ItemEntity 拾取、keyUse、keyAttack、count=1 掉落和原版回收完成，再由服务端/marker反查并 cleanup|
|真实双客户端|[success 31171119931](https://github.com/SGSxingchen/blindbox-challenge-forge-1.20.1/actions/runs/31171119931)：R1 Alice 摆件、R2 Bob 画板、R3 Alice 奖杯均完成；两份 marker 对三轮 BlockState/掉落实体 UUID 逐字段一致，随后 `SERVER`、`CLIENTS`、`CLEANUP` 成功；P4 文本、音频、导出和交接平台归还也在其后成功|
|强制回归汇总|[success 31171667037](https://github.com/SGSxingchen/blindbox-challenge-forge-1.20.1/actions/runs/31171667037)：同 SHA 与五项 canonical 运行匹配|

这只验收三个原创中性装饰方块及其真实单/双端路径；**不是 P5 整体验收或 Release 结论**。64 MiB 音频缓存的 LRU/驱逐重下/单飞/损坏删除重试、中文发行说明、正式版本与对历史资源授权的可验证处理仍未完成，Issue #5 不关闭。

`e32035e` 的质量、专服、强杀恢复和单客户端已 success，但双客户端 [31174221178](https://github.com/SGSxingchen/blindbox-challenge-forge-1.20.1/actions/runs/31174221178) 在 P5 段前失败。artifact 的首个业务错误为 P4 小黄鸡“两个真实客户端未在观察窗口内同步小黄鸡实体”：跨维门 cleanup 把 Bob 交回 P3 强杀恢复的无支撑/嵌墙原位，日志记录其真实窒息，两个 chicken marker 均缺失。当前仅将文本场景拥有的双人安全交接平台提前至 door cleanup 前并由该 cleanup 真实交接；不复活、跳过、延长窗口或伪造 marker。必须先以新 SHA 回归此首错，P5 音频压力尚未开始，不能评价其结果。

`e0c6201` 已使上述 P4 门 cleanup 交接、P4 小黄鸡、P5 装饰、文本和既有音频链全部成功；但真实双客户端 [31175170146](https://github.com/SGSxingchen/blindbox-challenge-forge-1.20.1/actions/runs/31175170146) 在 P5 压力 `FILL_1` 的既有 240 秒严格等待后退出。服务端仅有 `P5_MUSIC_CACHE_STARTED`，两端没有 P5 生产 S2C、客户端失败事件、PCM 或成功 marker，不能将其伪称为缓存、下载或解码结论。下一批仅添加输入前置的非成功诊断及失败时日志打印；不延长观察/业务时限、不写成功 marker、不直调下载或播放服务，Issue #5 保持 OPEN。

`e2857bb` 的双客户端 [31176243299](https://github.com/SGSxingchen/blindbox-challenge-forge-1.20.1/actions/runs/31176243299) 仍止于同一首轮，新增输入停滞诊断也不存在。质量 artifact 和客户端 debug 分别证明观察器已被打包、Forge 已自动订阅，但这尚不能断言它已经进入输入状态；下一批仅在严格失败**之后**请求一次非成功客户端快照，以区分 marker 属性/旗标、身份、方块同步和输入阶段。该额外最多 20 秒只用于收集已经失败的证据，绝不改变原 240 秒成功等待、服务端断言或任何通过 marker；Issue #5 继续 OPEN。

`7366453` 的双客户端 [31177478759](https://github.com/SGSxingchen/blindbox-challenge-forge-1.20.1/actions/runs/31177478759) 的失败后快照已确认 Alice 经真实配置/普通右键到 `WAIT_PCM`，且启用旗标、生产方块、站位、手持、俯仰和可用性均为真；两端随后都是生产客户端 `HTTP_HEADERS/IOException`。同 SHA 只读响应头显示 GitHub Raw 将大 OGG 标为 `application/octet-stream`，生产严格拒绝该类型是正确安全结果，不能为 P5 接受泛 MIME。下一批只令 P5 使用 jsDelivr 对当前 Git SHA 的只读 `.ogg → audio/ogg` HTTPS 映射；P4 保持 Raw，原 240 秒阶段、双客户端 GUI/S2C/PCM、缓存/LRU/单飞/损坏重试及生产安全检查全不放宽。必须以新 SHA 的真实结果重新裁定；Issue #5 继续 OPEN。

`645ddaf` 的双客户端 [31178799811](https://github.com/SGSxingchen/blindbox-challenge-forge-1.20.1/actions/runs/31178799811) 已使 P5 首个真实 OGG 在两端各自经生产下载/解码读取 PCM（同 event UUID、`cache_hit=false`、22050 字节）；服务端随即错误拒绝 marker。根因是 parser 把 URL query 内合法 `=` 误作第二个字段分隔符，尽管 `?ci=p5-fill-1` 正是独立缓存键的必要组成。下一批只修为首等号切分，并继续拒绝空、非法/未知和重复字段；不移除 query、不改 URL 比对、不放宽双端 PCM 或缓存压力断言。Issue #5 继续 OPEN，须以新 SHA 重验。
