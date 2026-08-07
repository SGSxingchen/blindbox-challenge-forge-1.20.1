# P5 中性原创装饰方块与发布准备验收矩阵

> 阶段工单：[Issue #5](https://github.com/SGSxingchen/blindbox-challenge-forge-1.20.1/issues/5)。当前 0/6 阶段门禁；所有动态 Forge/Minecraft 结论仅以 GitHub Hosted Runner 为准。

|范围|必须真实验收|
|---|---|
|三项装饰方块|正式 `BlockItem` 的真实客户端放置、服务端状态/扣除复验、两客户端同步观察、正常破坏战利品恰一次回收；不得用 `setBlock`、creative、直接给物或预写 marker 替代|
|资源闭合|注册、BlockItem、方块状态、模型、纹理、战利品、双语闭合；PNG 16×16 RGBA、原创资源清单与 SHA-256 完整|
|专服隔离|主源码无客户端导入；不新建 BE、菜单、网络包或 Capability；正式 Jar 排除 ciTest/ci-audio|
|音频压力|真实 GUI→下载→PCM 覆盖 64 MiB LRU、驱逐重下、同 URL 单飞、损坏缓存删除重试，测试音频只在 ciTest|
|发行准备|统一正式版本、中文说明、JLayer 许可证、Jar SHA-256、真实未覆盖边界；仅全量同 SHA 六门禁成功后创建 tag/Release|

P5 未验收前不得创建 Release，也不得把受控测试推广为第三方音频可用性、授权或长期压力承诺。
