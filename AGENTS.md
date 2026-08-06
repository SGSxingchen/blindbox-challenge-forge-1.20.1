# 项目索引

## 当前阶段

P1 的盲盒、打包、全局奖池、事务隔离与调试命令，P2 的 50 项基础物品族，以及 P3 的持久能力、抱枕投掷与返航剪刀均已验收归档；P3 最终六门禁证据见 [P3_IMPLEMENTATION.md](docs/P3_IMPLEMENTATION.md)、[P3_ACCEPTANCE.md](docs/P3_ACCEPTANCE.md)。本仓库现进入 P4 交互、传送与在线音频开发：CORE-03 信件、038 死亡笔记、037-B 任意门 + CORE-04 安全落点、046-D 发条小黄鸡，以及 047-B 木质手工八音盒均已进入静态实现待验。八音盒只在客户端异步下载、校验缓存并预解码 OGG/MP3；服务端只保存 HTTPS URL 并向当时在线玩家广播一次事件。八音盒 URL/DNS/TLS 采用单一截止点、真实公网地址固定连接与 NAT64/映射 IPv4 拒绝；正式 Jar 会携带 JLayer LGPL/NOTICE。任意门新增未加载伙伴的持久失效回执、下方安全点抵达免疫、双方反链和“每门恰有一个安全点”复验；小黄鸡以独立 TNT 实体持久保存主人、武装刻、Fuse 和爆炸威力。受控会话、持久排程、门反链、原创资源与 ciTest 范围见 [P4_IMPLEMENTATION.md](docs/P4_IMPLEMENTATION.md)，验收要求与真实未覆盖边界见 [P4_ACCEPTANCE.md](docs/P4_ACCEPTANCE.md)，准备裁决见 [P4_PREPARATION.md](docs/P4_PREPARATION.md)。原始资料在 `source-package/`，不得修改或删除；原压缩包备份在 `archive/`。

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

后续新增或变更功能时，必须同步更新对应文档与本索引；所有代码、提交信息和工程文档使用中文。
