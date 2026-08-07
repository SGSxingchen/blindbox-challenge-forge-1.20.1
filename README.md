# 盲盒挑战生存（Forge 1.20.1）

## 版本与范围

正式版目标版本为 **1.0.0**。P1—P5 已覆盖盲盒奖池、持久能力、抱枕/剪刀、信件、死亡笔记、任意门、安全落点、发条小黄鸡、木质手工八音盒、三个中性装饰方块，以及 64 MiB 八音盒缓存压力。动态验证只在 GitHub Hosted Runner 运行；最终六项门禁和发布件见 [P5 验收矩阵](docs/P5_ACCEPTANCE.md)。

68 张正式 PNG 为项目内原创重绘：原版图片只作需求输入，不读取、采样或混合，且不进入 Release。其余项目资产由项目方提供，许可本项目使用、修改与发行；该许可不外推给项目外第三方。逐文件记录见 [资源审计清单](docs/ASSET_MANIFEST.md)。

## 安装与校验

运行环境：**Java 17、Minecraft 1.20.1、Forge 47.4.22**。

1. 在 [Releases](https://github.com/SGSxingchen/blindbox-challenge-forge-1.20.1/releases) 下载同一版本的 `blindboxchallenge-1.0.0-all.jar` 与其 SHA-256 文件。
2. 在下载目录执行 `sha256sum -c blindboxchallenge-1.0.0-all.jar.sha256`；结果必须为 `OK`。
3. 客户端将该 `-all.jar` 放进实例的 `mods/`；专用服务器将同一文件放进服务器的 `mods/`，再按 Forge 常规方式启动。
4. **绝不能安装或分发 `*-citest.jar`**：它只含 GitHub Hosted Runner 的探针和音频夹具，不是游戏模组。

正式包已携带 JLayer，并保留 LGPL-2.1 文本与 NOTICE；不要用不含 `-all` 的普通开发 Jar 替代发布包。

## 使用边界

* 八音盒只接受 HTTPS URL，只向触发时在线的玩家发送一次播放事件；后来登录者不会补播，服务器不代理音频。
* 缓存验证覆盖单个游戏进程/目录内的 64 MiB LRU、驱逐重下、同 URL 单飞与损坏缓存重试；不承诺任意第三方 URL 的可用性、带宽、版权或长期稳定性。
* 如实保留的恢复边界：未 `save-all flush` 的掉电窗口、死亡笔记到期伤害前后的非原子窗口、未加载伙伴门和任意门拓扑恢复。
* TAC、魔女服、乐魂、原始照片与压缩素材不在当前范围，也不进入 Release。

## 仓库结构

* `mod/`：Forge 模组工程。
* `docs/`：计划、实现、验收、资源审计与边界记录。
* `source-package/`：原始需求/参考资料，只读保留，不得修改或删除。
* `archive/`：原始压缩包备份。
