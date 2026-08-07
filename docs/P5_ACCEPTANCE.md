# P5 中性原创装饰方块与发布准备验收矩阵

> 阶段工单：[Issue #5](https://github.com/SGSxingchen/blindbox-challenge-forge-1.20.1/issues/5)。所有 Forge/Minecraft 动态结论只以 GitHub Hosted Runner 为准；正式 `1.0.0` 版本提交仍须以自身 SHA 再跑六项门禁，之后才创建 Release。

|范围|必须真实验收|
|---|---|
|三项装饰方块|正式 `BlockItem` 先经原版 `ItemEntity` 拾取，再由真实客户端 `keyUse` 放置；两端观察同一方块状态，原版 `keyAttack` 破坏后验证真实掉落和原版拾取回收。禁止目标格直设、直接给物、直毁、预写 marker 或 creative 替代。|
|独立双端拓扑|P1—P4 链和 P5 链使用独立的专服世界、客户端目录、marker 与日志。P5 双端为两名存活的 Alice/Bob，不能借用死亡笔记、小黄鸡或安全交接平台状态。|
|音频压力|真实生产 GUI → S2C → SoundEngine 非空 PCM 覆盖 64 MiB LRU、驱逐重下、同 URL 单飞和损坏缓存删除重试；测试音频只在 ciTest Jar。|
|发行闭合|统一正式版本、中文安装说明、JLayer 许可证、正式 Jar 与 SHA-256、真实未覆盖边界；只有同 SHA 六项门禁成功后创建 tag/Release。|

## 已解决问题与收束结果

质量静态门闩已由 505 行收束到 65 行，删除 466 行脆弱源码文本门闩、154 条 `grep`、106 条内嵌 `assert` 和 26 个实现顺序检查。保留资源/注册/双语/战利品/清单闭合、68 张原创重绘一致性、正式 Jar 与 ciTest 隔离、客户端类泄漏、网络/权限与反绕过安全契约；行为正确性由 Hosted Runner 的真实专服、单端、双端和恢复套件裁决。

`e33a3fe` 修复了 P1—P4 与独立 P5 客户端共装 ciTest Jar 时，另一场景观察器因缺少 JVM 音频基址而在生产 S2C 事件链抛错的问题。未配置场景现在只忽略非目标 URL；进入实际 P4/P5 场景时仍严格要求对应基址，未改超时、marker、GUI/右键、下载、解码或 PCM 断言。

## 范围收束后的 Hosted Runner 证据：6/6

`e33a3fe39cf0fc8beb7bdb2e7f44bc5e5727819f` 已在同一 SHA 成功通过：

|门禁|真实结果|
|---|---|
|质量与构建|[success 31189077056](https://github.com/SGSxingchen/blindbox-challenge-forge-1.20.1/actions/runs/31189077056)|
|专用服务器启动|[success 31189076809](https://github.com/SGSxingchen/blindbox-challenge-forge-1.20.1/actions/runs/31189076809)|
|生命周期强杀恢复|[success 31189076788](https://github.com/SGSxingchen/blindbox-challenge-forge-1.20.1/actions/runs/31189076788)|
|真实单客户端|[success 31189076732](https://github.com/SGSxingchen/blindbox-challenge-forge-1.20.1/actions/runs/31189076732)|
|真实双客户端|[success 31189076794](https://github.com/SGSxingchen/blindbox-challenge-forge-1.20.1/actions/runs/31189076794)：`p1-p4-multi-client-regression` 与 `p5-isolated-two-client` 两个 job 均成功。|
|强制回归汇总|[success 31189748586](https://github.com/SGSxingchen/blindbox-challenge-forge-1.20.1/actions/runs/31189748586)|

P5 双端 artifact 证明三个方块均完成真实拾取→放置→双端观察→破坏→掉落→回收。五个独立 URL 缓存键在两名客户端均产生生产 S2C 与非空 PCM；超过 64 MiB 后 fill-1 被 LRU 驱逐并由 GUI 真实重下；每个客户端 JVM 对单飞 URL 都有一位 owner 与一位 follower；两端各自将已验证的 fill-5 缓存截断到 64 字节，并经摘要删除、GUI、S2C 和 PCM 成功重试。P1—P4 的门、小黄鸡、文本、短音频与强杀恢复同时保持回归成功。

## 资源与真实边界

68 张正式 PNG 为项目内原创重绘；原版图片只作需求输入，不读取、采样或混合，且不进入 Release。其余项目资产由项目方提供，许可本项目使用、修改与发行；该事实不外推为项目外第三方再授权。逐文件路径与 SHA-256 见 [资源审计清单](ASSET_MANIFEST.md)。

受控 P5 场景只证明单个游戏进程/目录的缓存语义，不承诺任意第三方 URL 的可用性、带宽、版权或长期稳定性。未 `save-all flush` 的掉电窗口、死亡笔记伤害前后的非原子窗口、未加载伙伴门和任意门拓扑恢复仍是如实保留的运行边界。TAC、魔女服、乐魂、原始照片和压缩素材继续排除。
