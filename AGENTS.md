# 项目索引

## 当前阶段

P1 的盲盒、打包、全局奖池、事务隔离与调试命令，P2 的 50 项基础物品族，以及 P3 的持久能力、抱枕投掷与返航剪刀均已验收归档；P3 最终六门禁证据见 [P3_IMPLEMENTATION.md](docs/P3_IMPLEMENTATION.md)、[P3_ACCEPTANCE.md](docs/P3_ACCEPTANCE.md)。P4 的 CORE-03 信件、038 死亡笔记、037-B 任意门 + CORE-04 安全落点、046-D 发条小黄鸡与 047-B 木质手工八音盒已在 `672509faae6a6fc10741dedadcdc3f11d086b9af` 取得质量、专服、强杀恢复、单客户端、双客户端和汇总六门禁证据；正式 Jar 仍明确排除独立 `ciTest` 探针与自制音频夹具。八音盒只在客户端异步下载、校验缓存并预解码 OGG/MP3；服务端只保存 HTTPS URL 并向当时在线玩家广播一次事件。八音盒 URL/DNS/TLS 采用单一截止点、真实公网地址固定连接和手写无 Cookie/认证/代理的 TLS 请求，并拒绝 NAT64/映射 IPv4；正式 Jar 会携带 JLayer LGPL/NOTICE。P4 详细实现、Jar SHA-256、运行链接和真实未覆盖边界见 [P4_IMPLEMENTATION.md](docs/P4_IMPLEMENTATION.md)、[P4_ACCEPTANCE.md](docs/P4_ACCEPTANCE.md)；本次归档提交仍需自身同 SHA 六门禁成功，方可关闭 Issue #4 并启动 P5。P5 仅可按 [P5_PREPARATION.md](docs/P5_PREPARATION.md) 实施三项中性原创装饰方块与发布准备；TAC、魔女服、乐魂、原始照片与压缩素材继续排除。原始资料在 `source-package/`，不得修改或删除；原压缩包备份在 `archive/`。

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
* [P5 正式资产与发布准备记录（P4 验收前不得开工）](docs/P5_PREPARATION.md)

后续新增或变更功能时，必须同步更新对应文档与本索引；所有代码、提交信息和工程文档使用中文。
