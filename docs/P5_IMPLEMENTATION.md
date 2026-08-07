# P5 中性原创装饰方块与发布准备实施记录

> 阶段工单：[Issue #5](https://github.com/SGSxingchen/blindbox-challenge-forge-1.20.1/issues/5)。P4 已归档；本文件仅记录已落盘实现与真实证据，不能把静态资源或计划写成动态通过。

## 当前批次

首批新增三个无额外玩法、无方块实体、无网络包的服务端安全装饰方块：`abstract_white_figurine`、`floor_art_panel`、`neutral_trophy`，并各有同名 `BlockItem`、战利品表、双语、方块状态、模型和原创 16×16 RGBA PNG。所有图案均为项目内新绘制的抽象几何像素图，不含角色、赛事、商标、画作、原始照片或压缩素材。

`DecorativeBlock` 只提供与 JSON 模型对应的选择/碰撞轮廓：摆件 8×14×8、地面画板 16×2×16、奖杯 8×12×8；它不含右键行为、方块实体、Capability、菜单、客户端类或网络包。正式客户端与专服均只使用原版方块状态和掉落同步。

## 真实放置、破坏与回收探针（待 Hosted Runner 验证）

已落盘独立的 `P5DecorCiScenario` 和仅在独立 `ciTest` Jar 内存在的 `CiClientP5DecorObservation`。三轮均由同一专服与两个真实 Forge 客户端完成：Alice 放置/破坏抽象白色小摆件和中性纪念奖杯，Bob 放置/破坏风格化地面画板。服务端只搭临时石质支撑，并把原方块状态逐格保存；三个目标格必须开始为空，场景源码不向目标格 `setBlock`，不直调 `BlockItem#useOn`、`removeBlock`、`destroyBlock` 或 `Block.dropResources`。

初始 `BlockItem` 不直接写入玩家手中：临时 `ItemEntity` 必须先经原版碰撞拾取进入生存背包，服务端记录其 UUID 并要求热键栏中恰有一件。客户端仅在脚本于服务端 `PLACE_READY` 后创建的阶段旗标存在、已经同步到定位、正式手持物、石质支撑和精确 `BlockHitResult` 时，才以 `KeyMapping.click(keyUse)` 走生产 C2S 放置。服务端随后要求目标成为预期生产 `BlockState` 且手持物恰好扣为零。

破坏阶段同样只在服务端 `BREAK_READY` 后放行：操作客户端必须命中预期生产方块并保持原版 `keyAttack`，服务端要求目标真实变为空气、附近恰有一枚 count=1 的同名原版掉落实体，记录其 UUID。两台客户端都要先从真实同步中观察三项方块状态和三枚同 UUID 掉落实体，使用同目录临时文件原子改名写出各自 marker；服务端逐字段回查观察者 UUID、坐标、方块 ID、物品 ID 与掉落实体 UUID。40 tick 观察窗口结束后，掉落实体仍保留，由操作玩家的原版碰撞拾取回收；服务端还要求背包恰为一件且原实体 UUID 已消失，绝不删除后补物。

所有 P5 marker 在启动前严格拒绝旧文件，`run-multi-client.py` 会清除本轮专用 marker 和阶段旗标；阶段旗标只安排输入时序，不代表成功。`multi-client.sh` 仍保留进程存活和 `BLINDBOX_CITEST_P5_DECOR=failed` 首错检测，再执行服务端 marker 复验、cleanup 与 canonical 导出。质量门禁已静态锁定资源闭合、16×16 RGBA PNG、模型/碰撞盒一致、正式 Jar 隔离以及上述反替代约束。

### P4 交接回归修复（待 Hosted Runner 验证）

`5074e60` 的真实双客户端 artifact `31164591985` 在小黄鸡业务已成功后暴露 cleanup 首错：P3 强杀恢复保存的 Alice 高空坐标 `(0.5,128.0,0.5)` 周围没有符合只读自然地面规则的格子。现由**下一场景** `P4TextCiScenario` 在鸡 cleanup 前创建、完整保存并拥有 5×5×3 临时安全交接平台；鸡仅消费该已准备平台，不拥有或伪造它。平台一直保留到 P4 八音盒完成，避免其 cleanup 再把 Alice 送回已撤支撑；P5 cleanup 也先恢复到该平台。canonical 导出后才在同一结束流程归还所有原方块并 `save-all/stop`。这不改生产玩法、小黄鸡 Fuse/TNT、死亡笔记或音频断言。

本批仍待 Hosted Runner 的 P5 双客户端、独立单客户端、资源全量清单、音频缓存压力、版本与发行说明；不得因为注册、静态门禁或探针已落盘而宣称 P5 或 Release 已完成。
