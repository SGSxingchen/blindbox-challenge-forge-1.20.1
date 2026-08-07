# 项目索引

## 当前阶段

P1 的盲盒、打包、全局奖池、事务隔离与调试命令，P2 的 50 项基础物品族，以及 P3 的持久能力、抱枕投掷与返航剪刀均已验收归档；P4 的信件、死亡笔记、任意门、安全落点、发条小黄鸡和木质手工八音盒亦已归档。P5 的三个中性原创装饰方块、64 MiB 八音盒缓存压力以及 A/B/C 三批原创资源整改已在 `b864e83021b57391c9c13abc5c0e4903b17a7e6f` 获得质量、专服、强杀恢复、真实单客户端、真实双客户端及汇总六门禁同 SHA 证据；正式候选 Jar 为 `blindboxchallenge-0.1.0-p1-all.jar`，SHA-256 为 `916c72feb039e1b10894e3497600472b20c8bdbb96c3add5afd724d0957c728e`，且不含独立 `ciTest` 探针和自制音频夹具。详细实现、验收和缓存边界见 [P5_IMPLEMENTATION.md](docs/P5_IMPLEMENTATION.md)、[P5_ACCEPTANCE.md](docs/P5_ACCEPTANCE.md)、[P5_AUDIO_PRESSURE.md](docs/P5_AUDIO_PRESSURE.md)。

**公开发行仍被真实法律条件阻塞：**P5 批次 A 已将 68 张历史 PNG 替换为可复现的原创像素贴图，批次 B 已重建 95 项模型/状态/战利品 JSON，批次 C 已重建双语与 Forge 元数据；批次 B/C 均等待 Hosted Runner。当前 182 项正式资源均为项目维护者新建、但全数仍须确认项目发行许可证。技术门禁全绿不构成授权；在权利人确认项目许可证、可再发行范围及必要署名，并完成名称/商标审查前，Issue #5 必须保持 OPEN，不得创建 tag、公开 Release 或发布 Jar。TAC、魔女服、乐魂、原始照片与压缩素材继续排除。原始资料在 `source-package/`，不得修改或删除；原压缩包备份在 `archive/`。

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
