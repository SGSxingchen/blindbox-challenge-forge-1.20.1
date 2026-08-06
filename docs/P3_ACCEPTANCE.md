# P3 持久能力、抱枕投掷与返航剪刀验收矩阵

> 阶段工单：[Issue #3](https://github.com/SGSxingchen/blindbox-challenge-forge-1.20.1/issues/3)。本文记录各批同一提交的真实验收证据；前三批已完成各自同 SHA 六门禁，P3 整体仍未验收。

## 范围基线

P3 共 **6 项**：008 石墩子抱枕、009 易筋经、011 高效养猪技术、016 钻石抱枕、033 路障头饰、045 返航剪刀。当前为 **6/6 静态实现、3/6 批次已验证、0/6 阶段验收**；001、002 已在 P2 完成，不重复计入；TAC、魔女服、金色手枪、乐魂和编号 012 继续排除。

|对象|最低真实服务端断言|当前状态|
|---|---|---|
|009 易筋经|首次与重复学习、消耗、固定 UUID 属性对账、合法与拒绝二段跳、落地重置、5 tick 个人冷却、死亡/重连/换维/跟踪及强杀恢复|第一批已通过六门禁；真实客户端按键、Clone、换维、跟踪和强杀专项待 Hosted Runner|
|011 高效养猪技术|10 格球形范围、扫描上限、服务端冷却、无食物催生繁殖|第二批已通过同 SHA 六门禁；真实双客户端父猪/幼猪 UUID 观察专项已静态接入，待 Hosted Runner|
|008 石墩子抱枕|放置、座位、蓄力投掷、实体命中和物品守恒|第三批同 SHA 六门禁成功|
|016 钻石抱枕|放置、座位、蓄力投掷、实体命中和物品守恒|第三批同 SHA 六门禁成功|
|033 路障头饰|头部槽位、铁头盔等值耐久与护甲属性、最低可运行盔甲层|第一批已通过六门禁|
|045 返航剪刀|服务端投掷、命中、返航、完整 `ItemStack` 归还及满包掉落兜底|生产代码、同步主人 UUID 和真实双客户端探针已静态接入，待 Hosted Runner|

## 强制六门禁

动态 Gradle、Forge 与 Minecraft 只能在 GitHub Hosted Runner 执行。同一 P3 提交必须同时通过以下六项；缺失、取消、跳过、失败、不同 SHA 或只通过静态构建都不得认定阶段通过。

|门禁|最低要求|当前证据|
|---|---|---|
|质量与构建|编译、检查、正式 Jar 与独立 ciTest Jar；正式 Jar 不含探针；逐包方向断言正确|第一批 [31081445397](https://github.com/SGSxingchen/blindbox-challenge-forge-1.20.1/actions/runs/31081445397)、第二批 [31084531314](https://github.com/SGSxingchen/blindbox-challenge-forge-1.20.1/actions/runs/31084531314)、第三批 [31087862553](https://github.com/SGSxingchen/blindbox-challenge-forge-1.20.1/actions/runs/31087862553) 均成功|
|真实专用服务器|加载正式模组、启动、命令与正常停止|第一批 [31081445470](https://github.com/SGSxingchen/blindbox-challenge-forge-1.20.1/actions/runs/31081445470)、第二批 [31084531344](https://github.com/SGSxingchen/blindbox-challenge-forge-1.20.1/actions/runs/31084531344)、第三批 [31087860971](https://github.com/SGSxingchen/blindbox-challenge-forge-1.20.1/actions/runs/31087860971) 均成功|
|生命周期强杀恢复|`save-all flush → SIGKILL → 同世界重启`；P1/P2 基线与 P3 持久状态均正确|第一批 [31081445465](https://github.com/SGSxingchen/blindbox-challenge-forge-1.20.1/actions/runs/31081445465)、第二批 [31084532343](https://github.com/SGSxingchen/blindbox-challenge-forge-1.20.1/actions/runs/31084532343)、第三批 [31087862606](https://github.com/SGSxingchen/blindbox-challenge-forge-1.20.1/actions/runs/31087862606) 成功；009 专项仍待补|
|真实单客户端|Xvfb 启动 Forge 客户端并稳定进入主菜单|第一批 [31081445556](https://github.com/SGSxingchen/blindbox-challenge-forge-1.20.1/actions/runs/31081445556)、第二批 [31084532145](https://github.com/SGSxingchen/blindbox-challenge-forge-1.20.1/actions/runs/31084532145)、第三批 [31087862199](https://github.com/SGSxingchen/blindbox-challenge-forge-1.20.1/actions/runs/31087862199) 均成功|
|真实双客户端|两独立客户端同服；P1/P2 回归及 P3 服务端业务探针、真实观察标记均成功|第一批 [31081445569](https://github.com/SGSxingchen/blindbox-challenge-forge-1.20.1/actions/runs/31081445569)、第二批 [31084534575](https://github.com/SGSxingchen/blindbox-challenge-forge-1.20.1/actions/runs/31084534575)、第三批 [31087862596](https://github.com/SGSxingchen/blindbox-challenge-forge-1.20.1/actions/runs/31087862596) 成功；011 客户端实体观察专项待补|
|强制回归汇总|同一 SHA 的前五项均成功；缺失、取消、跳过或失败均失败|第一批 [31081711807](https://github.com/SGSxingchen/blindbox-challenge-forge-1.20.1/actions/runs/31081711807)、第二批 [31084791520](https://github.com/SGSxingchen/blindbox-challenge-forge-1.20.1/actions/runs/31084791520)、第三批 [31088135881](https://github.com/SGSxingchen/blindbox-challenge-forge-1.20.1/actions/runs/31088135881) 均成功|

## 业务探针矩阵

下表是必须实现的验收场景，而不是已获得的覆盖结论。探针只打入独立 ciTest Jar，不能以预写标记、`continue-on-error` 或删除失败场景伪造成功。

|场景|证据来源|当前状态|
|---|---|---|
|易筋经能力与生命周期|真实服务端 Capability NBT、固定 UUID 属性和二段跳速度；克隆、登录、换维、跟踪与强杀重启后的复核|首次/重复、速度、冷却与真实重连已验证；真实客户端按键、Clone、换维、`StartTracking` 和强杀恢复专项已静态接入，待 Hosted Runner|
|养猪范围交互|真实服务端扫描结果、冷却、配置上限及繁殖状态|第二批真实服务端书本入口与六门禁成功；新增专项要求两客户端实际跟踪同一对父猪与幼猪 UUID，待 Hosted Runner|
|两种抱枕|真实服务端方块/实体/乘骑状态与物品账本；两名客户端各自实际观察到坐姿、飞行及命中后才生成标记|第三批已通过：服务端以真实放置/长按入口断言放置、单座、拆除清理、满蓄力、石命中、钻石超时与带 NBT 的每变体恰一件回收；客户端 marker 含座位、两投掷物、目标和两回收物 UUID，服务端逐项比对|
|返航剪刀|真实服务端投掷实体、命中、返航、完整 NBT 与满包掉落账本|静态接入真实 `use/releaseUsing` 两次投掷、命中/返航/背包回收、36 格满包掉落和双客户端 UUID/返航态 marker；待 Hosted Runner|
|路障头饰与双端隔离|真实服务端装备属性；专服加载；客户端仅接收同步数据|第一批已验证铁头盔等值、专服/单客户端加载与包方向|
|回归完整性|P1 canonical 资产守恒、P2 已有业务探针、正式 Jar 无 ciTest 类|第一、二批均随各自六门禁验证|

## 最终填写规则与实际未覆盖边界

只有在 6 项门禁对同一验收提交均成功后，才可在此处填入完整提交 SHA、六个运行编号、正式 Jar 文件名与 SHA-256，并将“待实现、待验证”改为真实结果。在此之前 P3 结论固定为：**未验收**。

第一批验收提交 `318a56fce64710e78a356f09c47f065174e5ce41` 的六门禁均成功：质量 [31081445397](https://github.com/SGSxingchen/blindbox-challenge-forge-1.20.1/actions/runs/31081445397)、专服 [31081445470](https://github.com/SGSxingchen/blindbox-challenge-forge-1.20.1/actions/runs/31081445470)、强杀恢复 [31081445465](https://github.com/SGSxingchen/blindbox-challenge-forge-1.20.1/actions/runs/31081445465)、单客户端 [31081445556](https://github.com/SGSxingchen/blindbox-challenge-forge-1.20.1/actions/runs/31081445556)、双客户端 [31081445569](https://github.com/SGSxingchen/blindbox-challenge-forge-1.20.1/actions/runs/31081445569)、汇总 [31081711807](https://github.com/SGSxingchen/blindbox-challenge-forge-1.20.1/actions/runs/31081711807)。第二批验收提交 `da781700d4d2955109629730053cac4049602c98` 的六门禁也均成功：质量 [31084531314](https://github.com/SGSxingchen/blindbox-challenge-forge-1.20.1/actions/runs/31084531314)、专服 [31084531344](https://github.com/SGSxingchen/blindbox-challenge-forge-1.20.1/actions/runs/31084531344)、强杀恢复 [31084532343](https://github.com/SGSxingchen/blindbox-challenge-forge-1.20.1/actions/runs/31084532343)、单客户端 [31084532145](https://github.com/SGSxingchen/blindbox-challenge-forge-1.20.1/actions/runs/31084532145)、双客户端 [31084534575](https://github.com/SGSxingchen/blindbox-challenge-forge-1.20.1/actions/runs/31084534575)、汇总 [31084791520](https://github.com/SGSxingchen/blindbox-challenge-forge-1.20.1/actions/runs/31084791520)。第三批验收提交 `3f6981519d25749008ef3671e6f1d133cd3cd287` 的六门禁也均成功：质量 [31087862553](https://github.com/SGSxingchen/blindbox-challenge-forge-1.20.1/actions/runs/31087862553)、专服 [31087860971](https://github.com/SGSxingchen/blindbox-challenge-forge-1.20.1/actions/runs/31087860971)、强杀恢复 [31087862606](https://github.com/SGSxingchen/blindbox-challenge-forge-1.20.1/actions/runs/31087862606)、单客户端 [31087862199](https://github.com/SGSxingchen/blindbox-challenge-forge-1.20.1/actions/runs/31087862199)、双客户端 [31087862596](https://github.com/SGSxingchen/blindbox-challenge-forge-1.20.1/actions/runs/31087862596)、汇总 [31088135881](https://github.com/SGSxingchen/blindbox-challenge-forge-1.20.1/actions/runs/31088135881)。

目前未覆盖的事实包括：009 的真实客户端按键/C2S 传输、死亡克隆、换维、跟踪及强杀恢复专项，011 的真实客户端实体观察专项，以及 045 的全部玩法、客户端观察与物品守恒均待 Hosted Runner 验证；未执行 `save-all flush` 的真实掉电窗口仍是 P1 已知边界；P4 的 GUI、任意门、安全落点、死亡笔记、发条小黄鸡和 047-B 八音盒在线音频，以及 P5 的专属模型精修、资源授权/商标审查和正式 Release 均尚未验收。027 耳机已在 P2 限定为原版音乐播放，不具有 URL 在线音频能力。
