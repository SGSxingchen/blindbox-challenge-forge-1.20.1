# P5 中性原创装饰方块与发布准备验收矩阵

> 阶段工单：[Issue #5](https://github.com/SGSxingchen/blindbox-challenge-forge-1.20.1/issues/5)。当前 0/6 阶段门禁；所有动态 Forge/Minecraft 结论仅以 GitHub Hosted Runner 为准。

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

`4e79779` 的单客户端 run `31168158886` 已把第 2 轮首错收窄为低矮地面画板的准星穿过选择轮廓，`0be0b2b` 仅将其真实攻击瞄准点降到共同底座内，仍待 Hosted 验证。该 SHA 的双客户端 run `31168158859` 另在 R1 放置、破坏、正常掉落成功后，于 R2 `WAIT_FOR_INITIAL_PICKUP` 超时；日志已明确 P4 文本让 Bob 真实死亡且既有验收禁止其后复活。后续双端修复必须保持两个真实 Forge 客户端及两份逐 UUID marker，但由 Alice 执行三轮原版拾取/输入，Bob 继续以死亡后的真实客户端观察，不能用复活、直接给物或单端结果掩盖。P5 仍为 **0/6**。

`0be0b2b` 的单客户端 run `31168748820` 已进一步验证低位准星确实命中 R2 目标并连续稳定 8 tick，攻击保持也已注入，但仍未开始破坏；这是新的严格首错，不得以 marker、缩短等待或直调客户端 `gameMode` 规避。后续只补一次 `KeyMapping` 攻击点击来启动原版输入循环，并继续保持攻击键；质量门禁禁止直接网络包、目标格改写、`gameMode`、`destroyBlock`、直接给物等替代。P5 仍为 **0/6**。

`7e8a533` 的单客户端 run `31169408832` 表明 attack click 后 R2 仍严格停在 `WAIT_FOR_BREAK`，但客户端命中和攻击注入均为真；源码复核已定位为旧 R1 的持久阶段旗标在每 tick 取消全局攻击键，覆盖 R2 的 held attack。后续只将抬键时机移至完整轮次循环之后，并以仍存在的当前生产方块决定保持；不改真实输入、服务端掉落/回收、双端 marker、时限或任何生产玩法。P5 仍为 **0/6**。
