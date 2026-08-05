# P1 事务恢复实现契约

> 目标：在 Minecraft 玩家存档与主世界 `SavedData` 无法数据库式原子提交的前提下，使 `save-all flush` 后 SIGKILL、重启、同 UUID 登录可以幂等恢复，并生成可机器断言的资产快照。未执行 flush 的掉电窗口仍明确列为未覆盖。

## 当前缺口

现有 `TransactionRecord` 只保存 `kind/stage/payload/token/bundle`，无法区分：

- PACK 是否已从具体槽位精确扣除；
- PACK 盲盒 token 是否已交付；
- OPEN token 是否已消耗；
- OPEN payload 是否已完整交付；
- 玩家库存和世界 `SavedData` 在崩溃时分别落盘到哪一步。

因此登录时把所有 `PREPARED` 统一转为 `MANUAL_REVIEW` 不能证明资产守恒，也不能自动完成或回滚。

## 持久化记录

`TransactionRecord` 增加向后兼容字段；旧记录缺键时按 `schema_version=1` 读取并进入人工隔离，不猜测补发。

- `schema_version`：新记录为 2。
- `created_game_time`、`updated_game_time`。
- `stage`：`PREPARED`、`PLAYER_APPLIED`、`WORLD_APPLIED`、`COMMITTED`、`ROLLED_BACK`、`MANUAL_REVIEW`。
- `source_receipts`：PACK 的逐槽收据，每项包含 slot、扣除数量、操作前完整 ItemStack、操作后完整 ItemStack。
- `token_receipt`：token UUID、期望出现数量、交付前后位置摘要；盲盒必须不可堆叠。
- `payload_receipts`：OPEN 奖品的规范化 ItemStack 与期望数量。
- `before_inventory_digest`、`after_inventory_digest`：主背包 36 槽 + 副手 + 光标栈的规范化摘要；逐槽快照另存，摘要只用于快速判断。
- `recovery_attempts`、`last_recovery_result`：可观测恢复状态并设置上限，避免每次登录无限重试。

ItemStack 规范化必须保留物品 ID、数量和完整 NBT；列表/Compound 键顺序稳定化后再摘要。不得只用当前 `StackFingerprint` 替代恢复快照。

## 写入顺序

### PACK

1. 服务端重新验证菜单 nonce、槽位、数量、指纹和容量。
2. 创建不可变 bundle、逐槽前后快照、唯一 token，并写 `PREPARED`；`setDirty()`。
3. 精确把来源槽位改成记录中的 after 状态，再交付 token；写 `PLAYER_APPLIED`。
4. 奖池按 bundle UUID 幂等 `putIfAbsent`；写 `WORLD_APPLIED`。
5. 最终核对来源槽位、token 数量和奖池 bundle，写 `COMMITTED`。

### OPEN

1. 在服务端主线程原子保留 bundle：同一 bundle 同时最多绑定一个未完成 OPEN 事务；其他玩家竞争时失败且保留 token。
2. 保存 token 所在槽位、完整背包前后快照和 payload 收据，写 `PREPARED`。
3. 精确消耗该 token，按预演后的逐槽结果交付全部 payload；写 `PLAYER_APPLIED`。
4. 奖池按 bundle UUID 幂等删除并清除保留；写 `WORLD_APPLIED`。
5. 核对 token、payload 和奖池，写 `COMMITTED`。

每个阶段转换都只允许前向、重复调用结果相同；禁止 `COMMITTED` 回退。完成事务保留有限审计期后再按容量/TTL 清理。

## 登录恢复判定

只处理属于当前玩家且未终态的事务，并限制单次登录处理数量。

### PACK

- 来源槽位仍为 before、token 不存在、bundle 不存在：安全回滚为 `ROLLED_BACK`，无需改库存。
- 来源槽位为 after、token 恰好 1、bundle 存在且内容一致：补记 `COMMITTED`。
- 来源槽位为 after、token 恰好 1、bundle 不存在：按记录幂等补入 bundle，再 `COMMITTED`。
- 来源槽位为 after、token 不存在、bundle 不存在：优先恢复来源槽位到 before，写 `ROLLED_BACK`；恢复前必须确认目标槽位未被其他物品占用。
- token 重复、槽位既非 before 也非 after、bundle 内容冲突：`MANUAL_REVIEW`，不得自动增删。

### OPEN

- token 存在、payload 未出现、bundle 存在：释放 bundle 保留并 `ROLLED_BACK`。
- token 已消耗、payload 完整、bundle 不存在：补记 `COMMITTED`。
- token 已消耗、payload 完整、bundle 仍存在：幂等删除 bundle并 `COMMITTED`。
- token 已消耗、payload 未出现、bundle 存在：按记录恢复 token 到原槽位或可证明安全的空槽，释放保留并 `ROLLED_BACK`。
- payload 部分出现、token 重复、bundle 内容冲突或库存无法安全回滚：`MANUAL_REVIEW`。

“payload 是否出现”不能只做全库存模糊计数；CI 资产使用唯一 NBT，生产恢复优先比对记录的逐槽 after 快照。无法唯一证明时宁可隔离。

## 并发与生命周期

- 所有奖池、保留和恢复变更只在逻辑服务端主线程执行；`synchronized` 不能替代主线程约束。
- OPEN 选择 bundle 时必须跳过已保留条目，避免两个玩家同时获得同一 bundle。
- logout/death/dimension change 只取消长按会话和减速，不擅自更改已持久化事务。
- 登录恢复执行前关闭相关菜单；恢复后广播容器变化并保存玩家。
- 事务表、保留表和审计记录必须有容量、TTL 与管理员查询命令；未终态记录不得清理。

## Canonical JSON

CI 探针从服务端真实状态导出，不预写 marker。每个检查点至少包含：

- 世界 ID、游戏刻、产品 Jar SHA；
- 玩家 UUID、36 个主背包槽、副手、光标栈；
- token UUID 及所在槽；
- 全部 bundle UUID、版本、创建者和完整 stacks；
- 全部相关事务 ID、kind、stage、收据摘要、恢复次数；
- 归属该玩家的持久地面掉落物；
- 按物品 ID + 数量 + 规范化 NBT 汇总的资产总量。

资产守恒检查必须防止同一 payload 同时计入玩家库存、可领取奖池和可领取恢复区。

## CI 验收顺序

1. 先用独立 `ciTest` 模组覆盖正常 PACK/OPEN、空池、满包、菜单关闭、过期 nonce、重复/伪造请求。
2. 两真实客户端竞争最后一个 bundle、并发打包唯一 NBT 资产。
3. 对 PACK/OPEN 各阶段写入后执行 `save-all flush`、SIGKILL(-9)、同世界同 UUID 重启。
4. 比较 kill 前后 canonical JSON，断言逐槽状态、token、bundle、事务阶段和资产总量。
5. 最终汇总 Job 对任何 failed/skipped/missing 判失败；测试探针不得进入正式 Jar。
