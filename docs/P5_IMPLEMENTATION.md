# P5 中性原创装饰方块与发布准备实施记录

> 阶段工单：[Issue #5](https://github.com/SGSxingchen/blindbox-challenge-forge-1.20.1/issues/5)。P4 已归档；本文件仅记录已落盘实现与真实证据，不能把静态资源或计划写成动态通过。

## 当前批次

首批新增三个无额外玩法、无方块实体、无网络包的服务端安全装饰方块：`abstract_white_figurine`、`floor_art_panel`、`neutral_trophy`，并各有同名 `BlockItem`、战利品表、双语、方块状态、模型和原创 16×16 RGBA PNG。所有图案均为项目内新绘制的抽象几何像素图，不含角色、赛事、商标、画作、原始照片或压缩素材。

全部正式资源现由 [正式资源审计清单](ASSET_MANIFEST.md) 覆盖路径与 SHA-256；三项 P5 新资源有明确原创边界，历史资源中无法从仓库可靠追溯作者或许可的条目如实标为 Release 阻塞，不能借技术验收掩盖。

`DecorativeBlock` 只提供与 JSON 模型对应的选择/碰撞轮廓：摆件 8×14×8、地面画板 16×2×16、奖杯 8×12×8；它不含右键行为、方块实体、Capability、菜单、客户端类或网络包。正式客户端与专服均只使用原版方块状态和掉落同步。

## 真实放置、破坏与回收探针（待 Hosted Runner 验证）

已落盘独立的 `P5DecorCiScenario` 和仅在独立 `ciTest` Jar 内存在的 `CiClientP5DecorObservation`。三轮均由同一专服与两个真实 Forge 客户端完成：仍存活的 Alice 对三个方块完成拾取、放置、破坏和回收；Bob 不被复活，持续作为第二个真实 Forge 客户端同步观察同一方块状态与掉落实体 UUID。服务端只搭临时石质支撑，并把原方块状态逐格保存；三个目标格必须开始为空，场景源码不向目标格 `setBlock`，不直调 `BlockItem#useOn`、`removeBlock`、`destroyBlock` 或 `Block.dropResources`。

初始 `BlockItem` 不直接写入玩家手中：临时 `ItemEntity` 必须先经原版碰撞拾取进入生存背包，服务端记录其 UUID 并要求热键栏中恰有一件。客户端仅在脚本于服务端 `PLACE_READY` 后创建的阶段旗标存在、已经同步到定位、正式手持物、石质支撑和精确 `BlockHitResult` 时，才以 `KeyMapping.click(keyUse)` 走生产 C2S 放置。服务端随后要求目标成为预期生产 `BlockState` 且手持物恰好扣为零。

破坏阶段同样只在服务端 `BREAK_READY` 后放行：操作客户端必须命中预期生产方块并保持原版 `keyAttack`，服务端要求目标真实变为空气、附近恰有一枚 count=1 的同名原版掉落实体，记录其 UUID。两台客户端都要先从真实同步中观察三项方块状态和三枚同 UUID 掉落实体，使用同目录临时文件原子改名写出各自 marker；服务端逐字段回查观察者 UUID、坐标、方块 ID、物品 ID 与掉落实体 UUID。40 tick 观察窗口结束后，掉落实体仍保留，由操作玩家的原版碰撞拾取回收；服务端还要求背包恰为一件且原实体 UUID 已消失，绝不删除后补物。

所有 P5 marker 在启动前严格拒绝旧文件，`run-multi-client.py` 会清除本轮专用 marker 和阶段旗标；阶段旗标只安排输入时序，不代表成功。`multi-client.sh` 仍保留进程存活和 `BLINDBOX_CITEST_P5_DECOR=failed` 首错检测，再执行服务端 marker 复验、cleanup 与 canonical 导出。质量门禁已静态锁定资源闭合、16×16 RGBA PNG、模型/碰撞盒一致、正式 Jar 隔离以及上述反替代约束。

同一 `P5DecorCiScenario` 另提供显式的 `start_p5_decor_single`、`verify_p5_decor_single`、`cleanup_p5_decor_single`，不会把双客户端结果降格为单端结果。Hosted-only 的 `p5-single-decor.sh` 会启动一台独立专服和一台 `BlindBoxAlice` Forge 客户端；该客户端用同一 `ciTest` 输入器完成三轮真实拾取、放置、破坏、方块/掉落实体观察与拾取回收。`single-client.yml` 保留原有主菜单 smoke，并追加这条独立业务路径和完整日志 artifact。该代码尚待 Hosted Runner 结果。

`f5b4190` 的双客户端 run `31165998045` 给出首个 P5 真实失败：P4 小黄鸡、文本、门与八音盒均已通过，轮次 1 的初始 ItemEntity 也已被 Alice 正常拾取并由服务端输出 `PLACE_READY`，但在任何客户端 `keyUse` 注入、`BREAK_READY` 或 P5 成功 marker 出现前，服务端于 `P5DecorCiScenario.java:222` 超时 `WAIT_FOR_PLACE`。artifact 同时出现高空夹具的 `moved too quickly` 警告，不能猜测是定位、主手、支撑、HitResult 或输入消费。为下一轮仅补非成功的原子前置诊断：每台客户端在阶段旗标存在时记录角色、坐标、预期站位、主手/预期物品、目标/支撑状态、HitResult、瞄准 tick 与是否已注入；服务端只在超时消息汇总这些文件。诊断不作为 marker、不放宽时限或断言、不直设方块/发包，下一轮仍以真实 `keyUse` 结果为准。

`aec1832` 的独立单客户端 run `31167082588` 用该诊断把根因收窄到第 2 轮：第 1 轮已依次真实右键、攻击、服务端状态/掉落实体成功；第 2 轮站位、空气目标和石质支撑均正确，但客户端主手仍是第 1 轮回收的摆件而非新拾取的画板，因而没有注入右键。服务端此前仅改 `Inventory.selected` 并广播容器，未必令真实客户端切换热键栏。现 ciTest 在确认原版 ItemEntity 拾取的实际物品槽后发送原版 `ClientboundSetCarriedItemPacket`，并在快照恢复时同步原槽；它只同步已持有槽位，不创建/替换物品、不发自定义 C2S，后续放置仍必须由客户端 `KeyMapping` 进入生产 `BlockItem` 路径。该最小修复待下一 SHA Hosted Runner 验证。

`15403a8` 的单客户端 run `31167577826` 已证明已持有槽同步生效：第 1、2 轮均完成真实右键放置，且第 2 轮服务端已到 `BREAK_READY`。新首错精确停在 `WAIT_FOR_BREAK`：客户端诊断显示目标仍为画板、命中目标、瞄准 8 tick、`keyAttack` 已按住，但未使方块消失。与第 1 轮高摆件不同，地面画板只有 2/16 格高；原破坏站位横距 4.35 格时，眼睛到其命中面约 4.60 格，超过生存破坏距离。现仅把破坏站位收至 3.75 格，仍保留掉落不即时碰撞的距离；不改按键、`gameMode`、网络包、掉落或超时。待下一 SHA Hosted Runner 验证正常原版破坏/掉落路径。

`4e79779` 的独立单客户端 run `31168158886` 已证明收近站位使第 1 轮完整通过，但第 2 轮仍在 `WAIT_FOR_BREAK`。artifact 的真实诊断为：玩家已在 `(-55.500,289.000,3.750)` 的破坏站位，主手为空、目标仍为 `floor_art_panel`、支撑为石头，却持续命中 `(-56,289,-1)` 而非目标 `(-56,289,0)`，`break_aim_ticks=0`、`attack_injected=false`；因此没有发生攻击映射注入，不能归咎于掉落或服务端结算。根因是旧代码瞄准完整方块中心 `y=targetY+0.5`，该射线会越过仅 2/16 格高的画板。下一提交只把真实准星目标降到三种装饰共同拥有的底座内 `y=targetY+0.0625`；仍由 `keyAttack` 驱动原版破坏，服务端超时、BlockState、掉落实体、双端观察和原版碰撞回收断言均不变，并由质量门禁锁定该低位命中点。

同一 `4e79779` 的双客户端 run `31168158859` 则给出与低画板瞄准无关的下一首错：第 1 轮已完成 `PLACE_READY`、`BREAK_READY`、`SERVER_DROP`，第 2 轮却在 `WAIT_FOR_INITIAL_PICKUP` 超时，尚未到放置旗标。服务器日志确认 P4 文本在此前已输出 `BlindBoxBob fell out of the world` 和 `P4_TEXT_SERVER=success`；这是死亡笔记的必要真实死亡，并被既有门禁锁定为“之后不复活”。第 2 轮旧设计仍把初始 `floor_art_panel` 掉落实体交给 Bob，失败时两端已经同步到各自 R2 站位，却没有证据表明死亡玩家可以完成原版拾取。下一提交只让仍存活的 Alice 完成第 2 轮真实 `ItemEntity` 拾取、`keyUse` 和 `keyAttack`；Bob 仍以原死亡状态的第二个真实 Forge 客户端观察每轮生产状态和同 UUID 掉落实体，服务端仍强制两份 marker、同服双客户端、原版掉落与碰撞回收。它不复活 Bob、不减少客户端组合、不降低超时，也不把观察 marker 当作操作或通过。

### P4 交接回归修复（待 Hosted Runner 验证）

`5074e60` 的真实双客户端 artifact `31164591985` 在小黄鸡业务已成功后暴露 cleanup 首错：P3 强杀恢复保存的 Alice 高空坐标 `(0.5,128.0,0.5)` 周围没有符合只读自然地面规则的格子。现由**下一场景** `P4TextCiScenario` 在鸡 cleanup 前创建、完整保存并拥有 5×5×3 临时安全交接平台；鸡仅消费该已准备平台，不拥有或伪造它。平台一直保留到 P4 八音盒完成，避免其 cleanup 再把 Alice 送回已撤支撑；P5 cleanup 也先恢复到该平台。canonical 导出后才在同一结束流程归还所有原方块并 `save-all/stop`。这不改生产玩法、小黄鸡 Fuse/TNT、死亡笔记或音频断言。

本批仍待 Hosted Runner 的 P5 双客户端、独立单客户端、资源全量清单、音频缓存压力、版本与发行说明；不得因为注册、静态门禁或探针已落盘而宣称 P5 或 Release 已完成。
