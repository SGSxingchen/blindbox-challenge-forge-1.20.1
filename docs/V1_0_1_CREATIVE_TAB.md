# v1.0.1 创造模式物品栏补丁

对应工单：[Issue #6](https://github.com/SGSxingchen/blindbox-challenge-forge-1.20.1/issues/6)。本文件记录 `v1.0.0` 发布后发现的创造模式可获取性缺陷与补丁验收，运行期结论只由 GitHub Hosted Runner 产出。

## 根因与修复范围

`v1.0.0` 已注册 67 项面向玩家的 `Item`，但没有 Forge `CreativeModeTab` 注册或原版标签填充逻辑；因此这些物品可由 `/give` 获取，却没有创造模式物品栏入口。补丁新增专属 **“盲盒挑战 / Blind Box Challenge”** 标签页：它使用已注册的 `blind_box` 作为图标，并直接从 `ModItems.ITEMS` 的登记顺序输出条目。

创造栏不维护第二份手工 ID 列表。`ModItems.playerCreativeEntries()` 是唯一的玩家条目入口，直接派生自 `ITEMS.getEntries()`；新增正常玩家物品时会随注册顺序自动进入标签页，避免再次漏加。当前顺序沿既有用途段落稳定排列：核心交互、能力/工具与可放置物、食品、装备及收藏物。

## 覆盖与明确排除

|对象|数量|处理|
|---|---:|---|
|正常可获取注册 `Item`|67|全部展示，默认 `ItemStack` 安全可用；盲盒、信件、死亡笔记的受控数据仍只在正常服务端交互时创建。|
|可放置主方块对应物品|10|已包含在上列 67 项；门默认未链接、八音盒默认未配置、浴桶默认空，不灌入特殊 NBT。|
|墙面附属方块|2|`glow_stick_wall`、`bml_cheer_stick_wall` 没有独立 `Item`，仅作为 `StandingAndWallBlockItem` 的墙面状态，不能伪造条目。|
|实体、方块实体、菜单|0|它们不是可获取 `Item`，不在创造栏显示；返航剪刀和发条小黄鸡本体物品仍在 67 项内。|

## 验收设计

静态质量契约输出注册物品数、创造栏预期数、重复数和技术排除项，并验证中英标题、稳定图标、统一注册来源及资源闭合；它不依赖注释、局部变量名或实现排版。

真实单客户端场景在独立专服世界中让 Alice 真实联机后切换创造模式，打开原版创造模式界面，运行时核对自定义标签存在、其显示集合与 `ModItems.playerCreativeEntries()` 完全一致、无重复，并记录首/中/末代表项和总数。该成功证据只能由真实客户端写入；随后恢复生存模式，继续原有 P5 原版拾取、放置、观察、破坏和回收场景。双客户端、专服、强杀恢复、质量和汇总门禁继续保持原有覆盖。

禁止通过直设方块、直接给物、构造业务网络包、预写成功 marker 或放宽条目断言得到通过结果。

## 首个实现提交的 Hosted Runner 证据

`e70a705884367e6213b0be220395d229cf8092f6` 已通过同 SHA 六项门禁，确认补丁逻辑不破坏既有 P1—P5 覆盖：

|门禁|结果|
|---|---|
|质量与构建|[success 31233581889](https://github.com/SGSxingchen/blindbox-challenge-forge-1.20.1/actions/runs/31233581889)|
|专用服务器启动|[success 31233581852](https://github.com/SGSxingchen/blindbox-challenge-forge-1.20.1/actions/runs/31233581852)|
|生命周期强杀恢复|[success 31233581868](https://github.com/SGSxingchen/blindbox-challenge-forge-1.20.1/actions/runs/31233581868)|
|真实单客户端|[success 31233581875](https://github.com/SGSxingchen/blindbox-challenge-forge-1.20.1/actions/runs/31233581875)|
|真实双客户端|[success 31233581849](https://github.com/SGSxingchen/blindbox-challenge-forge-1.20.1/actions/runs/31233581849)|
|强制回归汇总|[success 31233854998](https://github.com/SGSxingchen/blindbox-challenge-forge-1.20.1/actions/runs/31233854998)|

单客户端 artifact 的真实 marker 记录 `CreativeModeInventoryScreen`、目标标签 ID、注册/标签/屏幕各 67 项、无重复、顺序相同，以及首 `blind_box`、中 `paper_cup`、末 `shark_dagger_pillow`。客户端日志还记录了切到 Forge 第二分页、真实鼠标选择和关闭屏幕；同一会话随后通过原有三项装饰方块的拾取→放置→观察→破坏→掉落→回收场景。

本表是 `1.0.1` 升版前的实现证据；正式 `v1.0.1` 仍须以升版提交自己的同 SHA 六项门禁、正式 Jar 与 SHA-256 发布件替换为最终结论。
