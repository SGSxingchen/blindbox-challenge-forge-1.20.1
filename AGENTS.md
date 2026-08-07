# 项目索引

## 当前阶段

P1 的盲盒、打包、全局奖池、事务隔离与调试命令，P2 的 50 项基础物品族，以及 P3 的持久能力、抱枕投掷与返航剪刀均已验收归档；P4 的信件、死亡笔记、任意门、安全落点、发条小黄鸡和木质手工八音盒亦已归档。P5 的三个中性原创装饰方块和 64 MiB 八音盒缓存压力已在 `d9c0aea86658b4c7a5290942577823f1e65b0e63` 获得质量、专服、强杀恢复、真实单客户端、真实双客户端及汇总六门禁同 SHA 证据；正式候选 Jar 为 `blindboxchallenge-0.1.0-p1-all.jar`，SHA-256 为 `f07f917e2b80a8140726ffcf33337e6ec2b8d9cb3bf659168a870bb1cbf2db50`，且不含独立 `ciTest` 探针和自制音频夹具。详细实现、验收和缓存边界见 [P5_IMPLEMENTATION.md](docs/P5_IMPLEMENTATION.md)、[P5_ACCEPTANCE.md](docs/P5_ACCEPTANCE.md)、[P5_AUDIO_PRESSURE.md](docs/P5_AUDIO_PRESSURE.md)。

**资源发行边界：**68 张正式 PNG 已由可复现生成器完成项目内原创重绘；原版图片仅作需求输入，不读取、采样或混合，且不进入 Release。其余项目资产由项目方提供，许可本项目使用、修改与发行；该许可不外推至项目外第三方。TAC、魔女服、乐魂、原始照片与压缩素材继续排除。原始资料在 `source-package/`，不得修改或删除；原压缩包备份在 `archive/`。

## 规划文档

* [物品完整台账](docs/ITEM_CATALOG.md)
* [技术设计与开发分期](docs/PLAN.md)
* [美术资产矩阵](docs/ASSET_MATRIX.md)
* [开工前关键确认项](docs/OPEN_QUESTIONS.md)
* [P1 实现与测试边界](docs/P1_IMPLEMENTATION.md)
* [P2 基础物品实施记录](docs/P2_IMPLEMENTATION.md)
* [P2 验收矩阵](docs/P2_ACCEPTANCE.md)
* [P3 持久能力、抱枕投掷与返航剪刀实施记录](docs/P3_IMPLEMENTATION.md)
* [P3 持久能力、抱枕投掷与返航剪刀验收矩阵](docs/P3_ACCEPTANCE.md)
* [P4 交互、传送与在线音频准备记录](docs/P4_PREPARATION.md)
* [P4 交互、传送与在线音频实施记录](docs/P4_IMPLEMENTATION.md)
* [P4 交互、传送与在线音频验收矩阵](docs/P4_ACCEPTANCE.md)
* [P5 正式资产与发布准备记录](docs/P5_PREPARATION.md)
* [P5 八音盒缓存压力实施与验收设计](docs/P5_AUDIO_PRESSURE.md)
* [正式资源审计清单](docs/ASSET_MANIFEST.md)
* [P5 可审计原创资源替换记录](docs/ORIGINAL_ASSET_REPLACEMENT.md)
* [P5 中性原创装饰方块与发布准备实施记录](docs/P5_IMPLEMENTATION.md)
* [P5 中性原创装饰方块与发布准备验收矩阵](docs/P5_ACCEPTANCE.md)

后续新增或变更功能时，必须同步更新对应文档与本索引；所有代码、提交信息和工程文档使用中文。
