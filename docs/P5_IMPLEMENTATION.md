# P5 中性原创装饰方块与发布准备实施记录

> 阶段工单：[Issue #5](https://github.com/SGSxingchen/blindbox-challenge-forge-1.20.1/issues/5)。P4 已归档；P5 范围收束后的独立拓扑已在 `e33a3fe` 取得真实同 SHA 六门禁，并已作为 `v1.0.0` 发布基线归档。

> `v1.0.0` 已发布后发现的创造栏遗漏转入独立补丁工单 [#6](https://github.com/SGSxingchen/blindbox-challenge-forge-1.20.1/issues/6)，不改变 P5 玩法、资源或独立客户端拓扑；补丁记录见 [V1_0_1_CREATIVE_TAB.md](V1_0_1_CREATIVE_TAB.md)。

## P5 技术完成状态

首批新增三个无额外玩法、无方块实体、无网络包的服务端安全装饰方块：`abstract_white_figurine`、`floor_art_panel`、`neutral_trophy`，并各有同名 `BlockItem`、战利品表、双语、方块状态、模型和原创 16×16 RGBA PNG。所有图案均为项目内新绘制的抽象几何像素图，不含角色、赛事、商标、画作、原始照片或压缩素材。

全部 182 项正式资源现由 [正式资源审计清单](ASSET_MANIFEST.md) 覆盖路径与 SHA-256：68 张 PNG 使用项目内原创重绘，其余模型、方块状态、战利品、双语与元数据由项目内定义生成器闭合。原版图片仅作需求输入且不进入 Release；项目方提供素材与需求背景，许可本项目使用、修改与发行，不外推对项目外第三方的再授权。

## CI 静态门闩收束

`quality-build.yml` 从 505 行收束为 65 行：删除 466 行脆弱源码文本门闩、154 条 `grep`、106 条内嵌 Python `assert` 与 26 个实现顺序检查。新增 `tools/verify_quality_contract.py` 只保留稳定契约：正式资源/注册/双语/战利品/清单 SHA-256 闭合、68 张原创重绘 PNG 一致性、正式 Jar 与 ciTest Jar 隔离、服务端客户端类隔离、网络方向、交互权限，以及禁止直接改目标方块、直接破坏、直发业务包、直接给物、直调音频和 `continue-on-error` 的安全边界。

未删除任何 P1—P5 真实运行期场景或失败断言；专服、强杀恢复、真实单客户端、真实双客户端与汇总继续由 GitHub Hosted Runner 裁决玩法正确性。Jar 检查会逐项打印候选路径和每个断言结果，避免以“找到某个 Jar”掩盖候选歧义。

`DecorativeBlock` 只提供与 JSON 模型对应的选择/碰撞轮廓：摆件 8×14×8、地面画板 16×2×16、奖杯 8×12×8；它不含右键行为、方块实体、Capability、菜单、客户端类或网络包。正式客户端与专服均只使用原版方块状态和掉落同步。

## 真实放置、破坏与回收探针（已验收）

已落盘独立的 `P5DecorCiScenario` 和仅在独立 `ciTest` Jar 内存在的 `CiClientP5DecorObservation`。P5 现由 `p5-client-suite.sh` / `run-p5-decor-clients.py` 在独立新建专服世界执行：双端模式启动两名存活的 Alice/Bob，顺序完成装饰与缓存压力；P1—P4 原双客户端链不再承载 P5，也不提供死亡笔记、小黄鸡、任意门或交接平台状态。三轮仍由 Alice 处理抽象白色小摆件和中性纪念奖杯、Bob 处理风格化地面画板；服务端只搭临时石质支撑，并把原方块状态逐格保存；三个目标格必须开始为空，场景源码不向目标格 `setBlock`，不直调 `BlockItem#useOn`、`removeBlock`、`destroyBlock` 或 `Block.dropResources`。

初始 `BlockItem` 不直接写入玩家手中：临时 `ItemEntity` 必须先经原版碰撞拾取进入生存背包，服务端记录其 UUID 并要求热键栏中恰有一件。客户端仅在服务端 `PLACE_READY` 后创建的阶段旗标存在、已经同步到定位、正式手持物、石质支撑和精确 `BlockHitResult` 时，才以 `KeyMapping.click(keyUse)` 走生产 C2S 放置。服务端随后要求目标成为预期生产 `BlockState` 且手持物恰好扣为零。

破坏阶段同样只在服务端 `BREAK_READY` 后放行：操作客户端必须命中预期生产方块并保持原版 `keyAttack`，服务端要求目标真实变为空气、附近恰有一枚 count=1 的同名原版掉落实体，记录其 UUID。两台客户端都要先从真实同步中观察三项方块状态和三枚同 UUID 掉落实体；服务端逐字段回查观察者 UUID、坐标、方块 ID、物品 ID 与掉落实体 UUID。40 tick 观察窗口结束后，掉落实体仍保留，由操作玩家的原版碰撞拾取回收；服务端还要求背包恰为一件且原实体 UUID 已消失，绝不删除后补物。

所有 P5 marker 在独立 runner 启动前严格拒绝旧文件；阶段旗标只安排输入时序，不代表成功。双端 runner 在服务端首错、超时或客户端退出时严格失败，成功 marker 仍只能由真实客户端观察/PCM 读取写入，再由服务端逐 UUID 复验、cleanup。单端模式与双端模式复用同一安装、启动、释放与正常退出基础设施；质量门禁只保留资源闭合、正式 Jar 隔离和反替代稳定契约。该独立拓扑已由 `e33a3fe` 的同 SHA 六门禁复验。

同一 `P5DecorCiScenario` 另提供显式的 `start_p5_decor_single`、`verify_p5_decor_single`、`cleanup_p5_decor_single`，不会把双客户端结果降格为单端结果。`p5-client-suite.sh single` 使用同一公共启动器建立独立专服与一台 Alice；`dual` 使用另一全新世界与两名存活客户端，并在装饰 cleanup 后运行缓存压力。主手槽同步、低矮画板命中、原版攻击点击、共享按键抬起时序和跨场景音频观察器互扰均已修复，且未改变真实输入与服务端掉落/回收断言。

## P5 缓存并发与 LRU 加固（已验收；保留实现记录）

为使下一批受控多 URL 压力可以验证真实缓存边界，生产 `RemoteAudioDownload` 已将缓存复验、原子落盘和 LRU 淘汰串行化，但 DNS、HTTPS 下载、临时文件写入、完整解码与播放均保持并行。每个 `fetch` 调用现在领取独立短租约；同 URL 在途 future 不再共享可关闭的缓存对象，租约会防止另一 URL 的淘汰在异步解码打开文件前删除它，并在解码后的 PCM 已入内存时立即释放。mtime 在同毫秒也单调递增，避免快节奏五 URL 压力依赖 `Files.list` 的不确定同值顺序。

受控 URL、两次同 URL 的真实普通右键、五个 query 缓存键、LRU 驱逐重下、破坏摘要后的重试及两端 SoundEngine PCM 已由 Hosted Runner 实证。精确流程、仅 ciTest 的原创夹具边界和单进程范围见 [P5_AUDIO_PRESSURE.md](P5_AUDIO_PRESSURE.md)。

已解决的问题收束为四项：独立双端输入只在生产方块、站位和真实 GUI 前置成立后驱动；GitHub Raw 对大 OGG 的泛 MIME 保持严格拒绝，P5 夹具改走同一 Git SHA 的 jsDelivr `audio/ogg` 只读映射；marker 解析按首个等号切分以保留 URL query 缓存键；未配置另一场景基址的观察器只忽略非目标事件，不能截断生产 S2C 分发。未改变 240 秒严格等待、两端 PCM、LRU、单飞、损坏缓存重试或任何生产 URL 安全限制。

## `e33a3fe` 范围收束验收归档

`e33a3fe39cf0fc8beb7bdb2e7f44bc5e5727819f` 已在 Hosted Runner 通过质量、专服、强杀恢复、真实单客户端、拆分后的真实双客户端和汇总六项同 SHA 门禁；run 链接和逐项业务证据见 [P5 验收矩阵](P5_ACCEPTANCE.md)。这确认了独立 P5 世界中的装饰方块原版交互链和八音盒缓存压力生产路径均可运行，且正式 Jar 保持与 `ciTest` 夹具隔离。

批次 A 已以可复现源码完成完整原创资源包：68 张正式 PNG 为项目内原创重绘，114 项模型、方块状态、战利品、双语与元数据为项目内原创定义产物；原版图片仅作需求输入且不进入 Release。项目方提供素材与需求背景，许可本项目使用、修改与发行，不外推对项目外第三方的再授权。Issue #5 仅在最终候选 SHA 六门禁、版本、中文说明和发布件齐备后关闭。
