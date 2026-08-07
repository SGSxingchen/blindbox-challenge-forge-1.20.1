# P5 中性原创装饰方块与发布准备实施记录

> 阶段工单：[Issue #5](https://github.com/SGSxingchen/blindbox-challenge-forge-1.20.1/issues/5)。P4 已归档；P5 技术范围已在 `d9c0aea` 取得真实同 SHA 六门禁，发行授权仍是独立外部条件。

## P5 技术完成状态

首批新增三个无额外玩法、无方块实体、无网络包的服务端安全装饰方块：`abstract_white_figurine`、`floor_art_panel`、`neutral_trophy`，并各有同名 `BlockItem`、战利品表、双语、方块状态、模型和原创 16×16 RGBA PNG。所有图案均为项目内新绘制的抽象几何像素图，不含角色、赛事、商标、画作、原始照片或压缩素材。

全部正式资源现由 [正式资源审计清单](ASSET_MANIFEST.md) 覆盖路径与 SHA-256；三项 P5 新资源有明确原创边界，历史资源中无法从仓库可靠追溯作者或许可的条目如实标为 Release 阻塞，不能借技术验收掩盖。

`DecorativeBlock` 只提供与 JSON 模型对应的选择/碰撞轮廓：摆件 8×14×8、地面画板 16×2×16、奖杯 8×12×8；它不含右键行为、方块实体、Capability、菜单、客户端类或网络包。正式客户端与专服均只使用原版方块状态和掉落同步。

## 真实放置、破坏与回收探针（已验收；保留实现记录）

已落盘独立的 `P5DecorCiScenario` 和仅在独立 `ciTest` Jar 内存在的 `CiClientP5DecorObservation`。三轮均由同一专服与两个真实 Forge 客户端完成：Alice 处理抽象白色小摆件和中性纪念奖杯，Bob 处理风格化地面画板；P5 整段固定在 P4 文本死亡笔记之前，因此两人均存活且必须各自完成原版拾取、放置、破坏和回收，同时在每轮交叉观察同一方块状态与掉落实体 UUID。服务端只搭临时石质支撑，并把原方块状态逐格保存；三个目标格必须开始为空，场景源码不向目标格 `setBlock`，不直调 `BlockItem#useOn`、`removeBlock`、`destroyBlock` 或 `Block.dropResources`。

初始 `BlockItem` 不直接写入玩家手中：临时 `ItemEntity` 必须先经原版碰撞拾取进入生存背包，服务端记录其 UUID 并要求热键栏中恰有一件。客户端仅在脚本于服务端 `PLACE_READY` 后创建的阶段旗标存在、已经同步到定位、正式手持物、石质支撑和精确 `BlockHitResult` 时，才以 `KeyMapping.click(keyUse)` 走生产 C2S 放置。服务端随后要求目标成为预期生产 `BlockState` 且手持物恰好扣为零。

破坏阶段同样只在服务端 `BREAK_READY` 后放行：操作客户端必须命中预期生产方块并保持原版 `keyAttack`，服务端要求目标真实变为空气、附近恰有一枚 count=1 的同名原版掉落实体，记录其 UUID。两台客户端都要先从真实同步中观察三项方块状态和三枚同 UUID 掉落实体，使用同目录临时文件原子改名写出各自 marker；服务端逐字段回查观察者 UUID、坐标、方块 ID、物品 ID 与掉落实体 UUID。40 tick 观察窗口结束后，掉落实体仍保留，由操作玩家的原版碰撞拾取回收；服务端还要求背包恰为一件且原实体 UUID 已消失，绝不删除后补物。

所有 P5 marker 在启动前严格拒绝旧文件，`run-multi-client.py` 会清除本轮专用 marker 和阶段旗标；阶段旗标只安排输入时序，不代表成功。`multi-client.sh` 仍保留进程存活和 `BLINDBOX_CITEST_P5_DECOR=failed` 首错检测，再执行服务端 marker 复验、cleanup 与 canonical 导出。质量门禁已静态锁定资源闭合、16×16 RGBA PNG、模型/碰撞盒一致、正式 Jar 隔离以及上述反替代约束。

同一 `P5DecorCiScenario` 另提供显式的 `start_p5_decor_single`、`verify_p5_decor_single`、`cleanup_p5_decor_single`，不会把双客户端结果降格为单端结果。Hosted-only 的 `p5-single-decor.sh` 会启动一台独立专服和一台 `BlindBoxAlice` Forge 客户端；该客户端用同一 `ciTest` 输入器完成三轮真实拾取、放置、破坏、方块/掉落实体观察与拾取回收。`single-client.yml` 保留原有主菜单 smoke，并追加这条独立业务路径和完整日志 artifact。该代码尚待 Hosted Runner 结果。

`f5b4190` 的双客户端 run `31165998045` 给出首个 P5 真实失败：P4 小黄鸡、文本、门与八音盒均已通过，轮次 1 的初始 ItemEntity 也已被 Alice 正常拾取并由服务端输出 `PLACE_READY`，但在任何客户端 `keyUse` 注入、`BREAK_READY` 或 P5 成功 marker 出现前，服务端于 `P5DecorCiScenario.java:222` 超时 `WAIT_FOR_PLACE`。artifact 同时出现高空夹具的 `moved too quickly` 警告，不能猜测是定位、主手、支撑、HitResult 或输入消费。为下一轮仅补非成功的原子前置诊断：每台客户端在阶段旗标存在时记录角色、坐标、预期站位、主手/预期物品、目标/支撑状态、HitResult、瞄准 tick 与是否已注入；服务端只在超时消息汇总这些文件。诊断不作为 marker、不放宽时限或断言、不直设方块/发包，下一轮仍以真实 `keyUse` 结果为准。

`aec1832` 的独立单客户端 run `31167082588` 用该诊断把根因收窄到第 2 轮：第 1 轮已依次真实右键、攻击、服务端状态/掉落实体成功；第 2 轮站位、空气目标和石质支撑均正确，但客户端主手仍是第 1 轮回收的摆件而非新拾取的画板，因而没有注入右键。服务端此前仅改 `Inventory.selected` 并广播容器，未必令真实客户端切换热键栏。现 ciTest 在确认原版 ItemEntity 拾取的实际物品槽后发送原版 `ClientboundSetCarriedItemPacket`，并在快照恢复时同步原槽；它只同步已持有槽位，不创建/替换物品、不发自定义 C2S，后续放置仍必须由客户端 `KeyMapping` 进入生产 `BlockItem` 路径。该最小修复待下一 SHA Hosted Runner 验证。

`15403a8` 的单客户端 run `31167577826` 已证明已持有槽同步生效：第 1、2 轮均完成真实右键放置，且第 2 轮服务端已到 `BREAK_READY`。新首错精确停在 `WAIT_FOR_BREAK`：客户端诊断显示目标仍为画板、命中目标、瞄准 8 tick、`keyAttack` 已按住，但未使方块消失。与第 1 轮高摆件不同，地面画板只有 2/16 格高；原破坏站位横距 4.35 格时，眼睛到其命中面约 4.60 格，超过生存破坏距离。现仅把破坏站位收至 3.75 格，仍保留掉落不即时碰撞的距离；不改按键、`gameMode`、网络包、掉落或超时。待下一 SHA Hosted Runner 验证正常原版破坏/掉落路径。

`4e79779` 的独立单客户端 run `31168158886` 已证明收近站位使第 1 轮完整通过，但第 2 轮仍在 `WAIT_FOR_BREAK`。artifact 的真实诊断为：玩家已在 `(-55.500,289.000,3.750)` 的破坏站位，主手为空、目标仍为 `floor_art_panel`、支撑为石头，却持续命中 `(-56,289,-1)` 而非目标 `(-56,289,0)`，`break_aim_ticks=0`、`attack_injected=false`；因此没有发生攻击映射注入，不能归咎于掉落或服务端结算。根因是旧代码瞄准完整方块中心 `y=targetY+0.5`，该射线会越过仅 2/16 格高的画板。下一提交只把真实准星目标降到三种装饰共同拥有的底座内 `y=targetY+0.0625`；仍由 `keyAttack` 驱动原版破坏，服务端超时、BlockState、掉落实体、双端观察和原版碰撞回收断言均不变，并由质量门禁锁定该低位命中点。

同一 `4e79779` 的双客户端 run `31168158859` 则给出与低画板瞄准无关的下一首错：第 1 轮已完成 `PLACE_READY`、`BREAK_READY`、`SERVER_DROP`，第 2 轮却在 `WAIT_FOR_INITIAL_PICKUP` 超时，尚未到放置旗标。服务器日志确认 P4 文本在此前已输出 `BlindBoxBob fell out of the world` 和 `P4_TEXT_SERVER=success`；这是死亡笔记的必要真实死亡，并被既有门禁锁定为“之后不复活”。当时 P5 被排在文本/八音盒之后，旧设计却把初始 `floor_art_panel` 掉落实体交给 Bob，构成死亡语义与真实拾取的冲突。后续不复活 Bob，也不把他降格成死后观察者；而是把完整 P5 双端段移至已准备安全交接平台、完成小黄鸡 cleanup 之后且 P4 文本死亡之前，使 Alice 和 Bob 都以存活真实客户端执行各自轮次，同时保留两份 marker、同服双客户端、原版掉落与碰撞回收。

`0be0b2b` 的独立单客户端 run `31168748820` 已真实证明低位瞄准修正生效：第 2 轮 `hit=72,289,64` 正是目标、`break_aim_ticks=8`、`attack_injected=true`，且服务端已输出 `R2_BREAK_READY`；但方块仍未消失，严格停在 `WAIT_FOR_BREAK`。这排除手持物、站位、准星目标和稳定等待，却证明旧探针只保持攻击键状态时没有开始新的原版 destroy 动作。下一提交仅在同一稳定命中门槛到达后，通过 `KeyMapping.click(keyAttack)` 压入一次**原版攻击键**事件，再继续 `keyAttack.setDown(true)` 保持挖掘；它不访问 `gameMode`、不构造/发送网络包、不直写目标格，也不改变服务端掉落、回收或超时断言。质量门禁同时禁止这些替代调用。

`7e8a533` 的独立单客户端 run `31169408832` 证明攻击 click 本身也已进入真实客户端：R2 仍命中目标、稳定 8 tick、`attack_injected=true`，但未破坏。源码与该精确时序显示唯一共享攻击键被旧 R1 回收逻辑反复取消：R1 的 `p5-decor-break-1.flag` 必须保留到场景结束；R1 方块变空气后，循环每 tick 先 `setDown(false)`，R2 只在首次注入 tick 设为 true，下一 tick 又被旧轮次抬起。下一提交不改变输入、方块或服务端，而是在完整轮次循环后只根据“仍存在且已经开始原版破坏的当前生产目标”统一决定是否抬键；旧空气轮次不再覆盖 R2/R3 的真实 held attack。质量门禁锁定此共享按键生命周期，所有 C2S、掉落、回收和超时断言不变。

`6737f52` 的单客户端 run `31170034072` 已使三轮完整真实拾取、放置、破坏、生产掉落、PCM 无关的 marker 反查与 cleanup 全部成功。该 SHA 的双客户端 run `31170034066` 则证明三轮服务器链和 Alice marker 同样全成功，却缺少 Bob marker：P4 文本已让 Bob 死亡，P4 八音盒随后为不补播专项断线重连 Bob；P5 在重连后数秒即开始，死亡后的客户端没有形成三轮方块/掉落实体观察。根因不是放置、破坏、marker 复验或资源：把 P5 放在“Bob 必须死亡且不复活”的文本/音频之后，与两名存活玩家互相操作和观察冲突。现仅重排 ciTest 脚本到 P4 文本前的安全交接窗口，并恢复 Bob 的第二轮真实操作；P4 文本死亡、音频重连不补播、交接平台持有至 canonical 导出及所有服务端生产逻辑不变。

`895692c` 已在同一 SHA 获得装饰方块子范围的质量、专服、强杀恢复、真实单客户端、真实双客户端与汇总六项成功（完整链接见 [P5 验收矩阵](P5_ACCEPTANCE.md)）。双端 artifact 中 Alice 与 Bob 分别写出的三轮 marker 对 `BlockState`、坐标、物品和掉落实体 UUID 全部一致；服务器依次输出 R1 Alice 摆件、R2 Bob 画板、R3 Alice 奖杯的 `PLACE_READY`、`BREAK_READY`、`SERVER_DROP`，再输出 `SERVER`、`CLIENTS`、`CLEANUP` 成功。P5 cleanup 后，P4 文本真实死亡、八音盒 PCM/不补播、canonical export 与交接平台归还也保持成功。这是装饰子范围的真实基线，不代替后续音频缓存压力或发行验收。

### P4 交接回归修复（待 Hosted Runner 验证）

`5074e60` 的真实双客户端 artifact `31164591985` 在小黄鸡业务已成功后暴露 cleanup 首错：P3 强杀恢复保存的 Alice 高空坐标 `(0.5,128.0,0.5)` 周围没有符合只读自然地面规则的格子。现由**下一场景** `P4TextCiScenario` 在鸡 cleanup 前创建、完整保存并拥有 5×5×3 临时安全交接平台；鸡仅消费该已准备平台，不拥有或伪造它。平台一直保留到 P4 八音盒完成，避免其 cleanup 再把 Alice 送回已撤支撑；P5 cleanup 也先恢复到该平台。canonical 导出后才在同一结束流程归还所有原方块并 `save-all/stop`。这不改生产玩法、小黄鸡 Fuse/TNT、死亡笔记或音频断言。

以上为当时的排障记录；随后 `d9c0aea` 已完成 P5 双客户端、独立单客户端和音频缓存压力的 Hosted Runner 验收。发行授权与版本/说明的法律前置仍不能由技术门禁替代。

## P5 缓存并发与 LRU 加固（已验收；保留实现记录）

为使下一批受控多 URL 压力可以验证真实缓存边界，生产 `RemoteAudioDownload` 已将缓存复验、原子落盘和 LRU 淘汰串行化，但 DNS、HTTPS 下载、临时文件写入、完整解码与播放均保持并行。每个 `fetch` 调用现在领取独立短租约；同 URL 在途 future 不再共享可关闭的缓存对象，租约会防止另一 URL 的淘汰在异步解码打开文件前删除它，并在解码后的 PCM 已入内存时立即释放。mtime 在同毫秒也单调递增，避免快节奏五 URL 压力依赖 `Files.list` 的不确定同值顺序。

这只是生产一致性加固，不是音频压力通过：受控 URL、两次同 URL 的真实普通右键、五个 query 缓存键、LRU 驱逐重下、破坏摘要后的重试及两端 SoundEngine PCM 仍须由 Hosted Runner 实证。精确流程、仅 ciTest 的原创夹具边界和单进程范围见 [P5_AUDIO_PRESSURE.md](P5_AUDIO_PRESSURE.md)。

`e0c6201` 的质量、专服、强杀恢复和单客户端均已成功，双客户端 [31175170146](https://github.com/SGSxingchen/blindbox-challenge-forge-1.20.1/actions/runs/31175170146) 先完整通过 P4 门/小黄鸡、P5 装饰、文本及 P4 音频，却在 P5 压力首轮的 240 秒严格等待后没有 `FILL_1`。服务端只到 `P5_MUSIC_CACHE_STARTED`，artifact 既没有 P5 的生产 S2C、下载失败、PCM 或成功 marker，故不能猜测为下载/解码/LRU 问题，也不能用延长等待或成功旗标绕过。下一轮仅在 P5 客户端启用、GUI 等待和普通 use 等输入前置连续 100 tick 未满足时原子落一份**非成功**事实诊断；脚本只在原有严格阶段等待失败后显式打印该诊断和服务器尾部。它不写通过、不开下载/播放捷径、不改业务时限或两真实客户端组合，仍须据 Hosted Runner 的新首错继续修复。

`e2857bb` 的双客户端 [31176243299](https://github.com/SGSxingchen/blindbox-challenge-forge-1.20.1/actions/runs/31176243299) 再次在同一 `FILL_1` 严格等待失败，且新增的输入停滞文件也不存在；质量 artifact 已确认 ciTest Jar 含该观察器字节码，客户端 debug 日志也确认 Forge 自动订阅。因此不能把先前“位置同步竞态”当作已经证实的根因：当前更早的真实分界是观察器在输入停滞记录前返回，可能是客户端 tick、Alice 身份、P5 marker 属性或启用旗标可见性之一。下一轮只在**原有失败已经发生后**由脚本请求一份一次性非成功快照，记录状态、启用旗标、目标方块、站位、手持、俯仰、屏幕和可用性；若 marker 目录缺失，仅在真实 Alice 联机时写一条不含路径/URL 的客户端警告。该 20 秒证据收集发生在失败判定之后，不延长任何业务或成功等待，服务端从不读取该文件。

`7366453` 的双客户端 [31177478759](https://github.com/SGSxingchen/blindbox-challenge-forge-1.20.1/actions/runs/31177478759) 的失败后快照已排除 marker、身份、坐标和 GUI 前置：Alice 已真实到 `WAIT_PCM`，启用旗标和生产八音盒均已观察、站位/手持/俯仰可用。两端在 P5 开始后均由生产 `ClientMusicService` 报 `HTTP_HEADERS/IOException`，没有 PCM。对同 SHA 的只读 HTTPS 头复验显示短 OGG 为 `audio/ogg`，但 14 MiB 原创压力 OGG（含 query）被 GitHub Raw 标为 `application/octet-stream`；生产下载器的严格 MIME 拒绝正是预期安全行为，绝不能放宽为接受该泛型类型。现只把 P5 ciTest 基址改为 jsDelivr 对**当前同一 Git SHA**的只读 HTTPS 映射，该映射正确声明 `.ogg` 为 `audio/ogg`；P4 继续走 GitHub Raw，生产 URL 策略、DNS/TLS/文件头/16 MiB 限制和任何成功判据均不变。新 SHA 仍须由 Hosted Runner 验证真实 GUI→S2C→PCM。

`645ddaf` 的双客户端 [31178799811](https://github.com/SGSxingchen/blindbox-challenge-forge-1.20.1/actions/runs/31178799811) 已证明 jsDelivr 方案使两个真实客户端收到同一 P5 OGG 事件并各自读取 22050 字节 PCM、`cache_hit=false`；新首错发生于服务端解析这些真实 marker。原解析器错误要求整行只能有一个 `=`，而独立缓存键必须保留 URL query `?ci=p5-fill-1`，合法值内的 `=` 被误判成格式坏。现仅按**首个**等号切字段名/值，同时严格拒绝空值、非法/未知字段和重复字段；URL 本体与逐字段 UUID、URL、PCM、缓存命中、两端同事件断言完全不变，不能删除 query 或降格为单端结果。仍待新 SHA Hosted Runner 重验。


## `d9c0aea` 验收归档与发行阻塞

`d9c0aea86658b4c7a5290942577823f1e65b0e63` 已在 Hosted Runner 通过质量、专服、强杀恢复、真实单客户端、真实双客户端和汇总六项同 SHA 门禁；run 链接、正式候选 Jar SHA-256 和逐项业务证据见 [P5 验收矩阵](P5_ACCEPTANCE.md)。这确认了装饰方块的原版交互链和八音盒缓存压力生产路径均可运行，且正式 Jar 保持与 `ciTest` 夹具隔离。

该技术结论不覆盖发行权。批次 A 已以可复现源码替换 68 张历史 PNG；批次 B/C 已重建 99 项模型、状态、战利品、双语与 Forge 元数据，并在 `b864e83` 获得 Hosted Runner 同 SHA 六门禁。资源审计清单当前工作树的 182 项均为项目维护者新资源但仍待项目发行许可证确认；必须由权利人确认项目许可证、可再发行范围及必要署名，并完成名称/商标中性化，才能进入 tag/Release 流程。此前 Issue #5 保持 OPEN，候选 Jar 不得公开分发。

发行候选的 `AbstractArchiveTask` 统一关闭归档时间戳并固定文件顺序，`jar` 清单不再写入 `Implementation-Timestamp`；质量门禁直接检查正式 `-all.jar` 的清单。它只减少同一构建链的无意义差异，正式发布仍以最终成功 Hosted Runner artifact 的 SHA-256 为唯一交付校验值。
