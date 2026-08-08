# 项目索引

## 当前阶段

P1 的盲盒、打包、全局奖池、事务隔离与调试命令，P2 的 50 项基础物品族，以及 P3 的持久能力、抱枕投掷与返航剪刀均已验收归档；P4 的信件、死亡笔记、任意门、安全落点、发条小黄鸡和木质手工八音盒亦已归档。P5 的三个中性原创装饰方块和 64 MiB 八音盒缓存压力已随 `v1.0.0` 发布。当前补丁工单 [#6](https://github.com/SGSxingchen/blindbox-challenge-forge-1.20.1/issues/6) 已取得创造栏实现提交的同 SHA 六项门禁：专属标签页从 67 项玩家 `Item` 的统一注册来源生成，墙面附属方块、实体、方块实体和菜单不伪造条目；当前正以 `1.0.1` 版本候选重跑六项门禁，随后才发布正式 Jar、SHA-256、tag 和 Release。实现、静态契约与真实客户端验收见 [v1.0.1 创造栏补丁记录](docs/V1_0_1_CREATIVE_TAB.md)。

质量工作流的稳定静态契约位于 `tools/verify_quality_contract.py`，范围和量化结果见 [P5_IMPLEMENTATION.md](docs/P5_IMPLEMENTATION.md)；真实行为只由 Hosted Runner 的运行期门禁裁决。

**资源发行边界：**全部 182 项正式资源均为当前项目的原创重绘或定义产物：68 张 PNG 由可复现生成器重绘，其余模型、方块状态、战利品、双语与元数据由项目内定义生成器闭合。原版图片只作需求输入，不读取、采样或混合，且不进入 Release。项目方提供素材与需求背景，许可本项目使用、修改与发行；该许可不外推至项目外第三方。TAC、魔女服、乐魂、原始照片与压缩素材继续排除。原始资料在 `source-package/`，不得修改或删除；原压缩包备份在 `archive/`。

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
* [v1.0.1 创造模式物品栏补丁记录](docs/V1_0_1_CREATIVE_TAB.md)

后续新增或变更功能时，必须同步更新对应文档与本索引；所有代码、提交信息和工程文档使用中文。
