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

`P5DecorCiScenario`、`CiClientP5DecorObservation`、双客户端启动参数、阶段旗标、旧 marker 拒绝和服务端逐 UUID 反查均已落盘，但尚未产生本阶段 Hosted Runner 成功证据；本表仍为 **0/6**。单客户端真实放置/回收、全资源清单、64 MiB 音频缓存压力、发行说明和正式版本仍未完成，不能用已有主菜单 smoke 或 P4 旧证据替代。
