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
