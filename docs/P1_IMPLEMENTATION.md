# P1 核心 MVP 实现记录

> 范围：仅盲盒、打包道具、全局奖池、服务端菜单、事务日志、调试命令、最小资源与 CI。没有实现其余原始物品、TAC、魔女服、乐魂、信件、任意门或在线音频。

## 工程与注册名

* 工程：`mod/`，Minecraft Forge `1.20.1-47.4.22`，Java 17。
* 稳定模组 ID：`blindboxchallenge`。
* P1 注册：`blind_box`、`packing_tool`、`packing_menu`。资源路径均为小写。
* 原创占位资源：16×16 `blind_box.png`、`packing_tool.png`，不使用任何参考照片。

## 服务端流程

### 盲盒

`BlindBoxItem` 长按 40 tick（P1 占位常量）后才调用服务端 `BlindBoxService.open`。使用时以固定 UUID 添加瞬态移动速度修饰器；`releaseUsing`、背包 tick 的换手检测、死亡和登出都会删除修饰器。原版 `UseAnim.BOW` 提供持有者可见进度；奖池选择、容量预演、扣箱和奖项交付均只在逻辑服务端执行。

### 打包菜单与网络

打包道具通过 `NetworkHooks.openScreen` 打开 `PackingMenu`。P1 界面显示玩家背包，输入 `槽位:数量` 列表（例如 `0:5, 12:1`）后才发送 `CommitPackingPacket`。包中仅有：容器 ID、槽位、数量、该槽位完整 `ItemStack` NBT 的 SHA-256 指纹；**不携带、也不接受客户端给出的物品 NBT**。服务端再次校验当前菜单、槽位范围、去重、数量、禁打包项、物品与指纹，然后从真实库存复制完整 NBT。

禁止打包 `blind_box` 和 `packing_tool`，防止递归盲盒/事务工具进入奖池。

## SavedData schema 与 NBT 键

主世界数据名：`blindboxchallenge_pool`，实现类为 `BlindBoxPoolSavedData`。

```text
next_version: long
bundles: [{
  id: UUID, creator: UUID, created_game_time: long, version: long,
  stacks: [完整 ItemStack NBT]
}]
transactions: [{
  id: UUID, player_id: UUID, token_id: UUID, bundle_id: UUID,
  kind: "PACK" | "OPEN", stage: "PREPARED" | "COMMITTED" | "MANUAL_REVIEW",
  payload: PrizeBundle
}]
```

盲盒 ItemStack NBT：`blindboxchallenge_token`（唯一 UUID）；使用期临时键：`blindboxchallenge_opening`。`PrizeBundle` 的每个物品使用 `ItemStack.save` / `ItemStack.of` 保存与读取，保留物品 ID、数量和完整 NBT。

## 事务状态机与恢复入口

```text
PACK:  验证/容量预演 -> PREPARED(日志) -> 扣真实库存+给盲盒 -> COMMITTED(奖池追加)
OPEN:  验证/容量预演 -> PREPARED(日志) -> 扣盲盒+给完整 bundle -> COMMITTED(奖池删除)
```

事务 ID、盲盒 token、玩家 UUID、bundle UUID 和完整 payload 均持久化。`PlayerLoggedInEvent` 调用 `inspectRecovery`：只要发现非 `COMMITTED` 记录，就标为 `MANUAL_REVIEW` 并通知玩家/管理员，绝不猜测性补发、删除、掉落或抽取另一奖项。这样在玩家库存 NBT 与世界 SavedData 非 ACID 的崩溃窗口中，P1 选择**冻结且保留证据**，而非静默吞物或复制。

已覆盖：正常服务端主线程的多玩家串行提交、关闭菜单（没有临时转移物品）、换手/死亡/掉线取消开盒、空奖池、满背包不开盒、持久事务记录与登录隔离。

未覆盖：SIGKILL 恰好发生在玩家库存保存与世界 SavedData 保存之间时的自动幂等补偿。该窗口不会自动删除或补发，而会留下 `MANUAL_REVIEW` 记录及完整 payload；这是 P1 的明确 fail-safe，不宣称已完成自动恢复。P2 前需增加库存前后快照、来源槽位收据、受控保存屏障和管理员 `recover/quarantine` 命令，再以 SIGKILL 矩阵验收。

## 调试钩子与测试接口

仅权限等级 2 以上可以执行：

```text
/blindbox pool count
/blindbox pool clear
/blindbox pool inject <item> <count>
```

`inject` 生成确定性单物品 bundle，供 CI/人工专服测试；正常玩法不依赖这些命令。后续两客户端并发测试应并行发起不同打包请求并断言奖池版本/条目数；SIGKILL 恢复测试应在 `PREPARED` 和 `COMMITTED` 两个保存点杀服后重启并检查隔离记录。

## CI 与测试事实

* `.github/workflows/quality-build.yml` 在 GitHub Hosted Runner 上执行 `check build`、生成 Jar SHA-256、上传产物。
* `.github/workflows/dedicated-server.yml` 在 GitHub Hosted Runner 上安装 Forge 专用服务器、放入构建 Jar，并仅在日志出现 `Done (` 后通过。
* 根据本轮约束，本机**没有**运行 Gradle、Java、Forge 或 Minecraft；Actions 尚未因当前 `gh` 登录失效/没有远端仓库而实际触发。
* 两客户端并发自动化和 SIGKILL 自动恢复均未虚报为已测。
