# 物品业务口径与实际实现审计

## 审计边界

本表以正式版本 `v1.0.2` 对应提交 `ea113a870bf8fb47c38e34b973108bfdfb7b507e` 为代码基线，将 `docs/ITEM_CATALOG.md` 记录的最初业务口径与该提交中的 Forge 1.20.1 注册、物品类、事件、网络、Capability、实体和方块行为逐项交叉。

当前隔离工作树没有 `source-package/`，因此无法复核原始 `盲盒.txt`、63 张参考图或其他一手资料；本表的“最初业务口径”是对仓库现有 `ITEM_CATALOG.md` 的转录和压缩，不把它冒充一手材料。主工作区中尚未提交的 001 相关脏改动也未纳入本审计，001 只按上述正式 SHA 判定。

这是静态源码审计，不等于动态验收。凡涉及实际命中距离、客户端画面、声音传播、多人同步、碰撞、跨维传送、实体返航、菜单交互和服务端配置的项目，仍需在真实客户端/专用服务器中验证。

判定含义：`一致` 表示源码可观察行为覆盖现有业务口径；`基本一致` 表示核心玩法一致，但表现、参数或边界存在收束；`不一致` 表示存在直接功能偏差；`业务待定` 表示原口径没有足够效果数据，不能用后来选定的实现反证一致。

## 67 项逐项矩阵

|序号|注册 ID|最初业务口径|当前实际实现|判定|代码证据|差距或下一步|
|---:|---|---|---|---|---|---|
|1|`blind_box`|CORE-01：长按开盒、期间减速，从全局奖池取完整奖项|单件；长按 40 tick，服务端加 60% 减速并调用全局奖池开盒，松开/死亡/异常中断清理状态|一致|`ModItems.java:49`；`BlindBoxItem.java:18-90`；`BlindBoxService.java`|动态验证空池、并发和完整 NBT 奖项|
|2|`packing_tool`|CORE-02：菜单选择多种物品和数量，原子打包进全局池并获得盲盒|单件；右键建立服务端会话并打开 `PackingMenu`，提交由网络包和服务处理|一致|`ModItems.java:50`；`PackingToolItem.java:18-29`；`PackingMenu.java`；`CommitPackingPacket.java`|动态验证并发改包、库存变化和回滚|
|3|`letter`|CORE-03：右键阅读，潜行右键编辑；受限纯文本由服务端校验保存同步|单件；普通右键发送只读正文，潜行右键打开带实例、修订和槽位凭据的编辑菜单|一致|`ModItems.java:51`；`LetterItem.java:23-62`；`LetterService.java`|动态验证中文、换行上限和并发修订冲突|
|4|`death_note`|038：输入在线玩家名后使目标死亡，可延迟；服务端解析，不拼命令|右键开受控菜单；提交经服务端服务记录并由 tick 事件延迟执行|一致|`ModItems.java:52`；`DeathNoteItem.java:20-52`；`DeathNoteService.java`；`DeathNoteTickEvents.java`|动态验证离线、同名边界和伤害类型|
|5|`clockwork_chicken`|046-D：右键启动 60 秒倒计时，原地以 TNT 两倍威力爆炸|最多 16；右键生成自定义 TNT 实体并扣 1；Fuse 和默认威力取服务端配置，实体持久化并爆炸|一致|`ModItems.java:53`；`ClockworkChickenItem.java:16-32`；`ClockworkChickenEntity.java:22-103`|核对发布配置确为 1200 tick、威力 8；动态验证卸载恢复|
|6|`black_knight_telescopic_knife`|001：右键伸刀；木剑属性；攻击时有概率收缩；缩回规则原待定|木剑耐久；右键切换 NBT；缩回完全不能攻击；伸出命中后 20% 收缩|基本一致|`ModItems.java:55-56`；`BlackKnightTelescopicKnifeItem.java:15-59`；`ModItemAttributeEvents.java:38-56`|20% 与“缩回不可攻击”是实现选择；需用户最终确认；主工作区脏改动未计入|
|7|`purple_toy_pickaxe_sword`|002：右键切换木镐/木剑，两形态均为木质完整能力|木耐久；NBT 切形态；分别提供木镐采掘/掉落和木剑动作、挖速、攻击属性|一致|`ModItems.java:57-58`；`PurpleToyPickaxeSwordItem.java:20-88`；`ModItemAttributeEvents.java:26-35`|动态验证客户端属性提示和所有木镐标签|
|8|`adrenaline`|003：30 秒速度 II、力量 II、恢复 IV、饱和；数量 ×10 含义待定|最多 16；饮用 24 tick 后耗 1，施加所述四效果，前三项 600 tick|基本一致|`ModItems.java:59`；`AdrenalineItem.java:16-42`|效果一致；业务的“×10”未落实为堆叠上限或盲盒数量|
|9|`vodka`|020：饮用后微醺；客户端模糊、扭曲，时长服务端管理|最多 16；饮用后耗 1，服务端施加 30 秒原版反胃效果，由客户端显示原版扭曲|基本一致|`ModItems.java:60`；`VodkaItem.java:16-46`|没有独立“模糊”渲染，仅原版反胃视觉；需确认表现是否足够|
|10|`headphones`|027：仅播放原版音乐，不处理在线链接|单件；右键只播放一次原版 `MUSIC_DISC_CAT` 声音事件，1 秒冷却|基本一致|`ModItems.java:61`；`HeadphonesItem.java:16-31`|没有播放/停止状态、曲目选择或完整唱片播放控制；确认“一次 CAT 音效”是否符合播放音乐|
|11|`safety_exit_sign_shield`|029：盾牌耐久 5；举盾反伤 50%|原版盾牌，耐久 5、不可修；成功格挡直接生物攻击时反射已格挡伤害 50%|一致|`ModItems.java:62`；`SafetyExitSignShieldItem.java:6-23`；`ServerLifecycleEvents.java:97-123`|投射物不反伤是实现边界，需动态验证盾耐久消耗|
|12|`decision_coin`|039-B：正面力量 II 10 秒；反面清除自身全部 buff；服务端随机|右键必耗 1；服务端随机，正面完全一致，反面仅移除所有有益效果|基本一致|`ModItems.java:63`；`DecisionCoinItem.java:14-49`|“所有 buff”与“仅有益效果”范围不完全相同；需确认负面效果是否也应清除|
|13|`birthday_candle`|046-E：长按许愿，随机正面 buff 30 秒，CD 20 秒|长按 32 tick；从速度、力量、抗性、恢复中随机 I 级 30 秒；冷却 20 秒；不消耗|一致|`ModItems.java:64`；`BirthdayCandleItem.java:17-59`|候选集合是实现选择，动态验证冷却提示|
|14|`rainbow_hoop`|046-G：长按蓄力，松开把自己弹起|蓄力 10–40 tick，松开将竖直速度设为 0.45–0.90；不消耗、无冷却|一致|`ModItems.java:65`；`RainbowHoopItem.java:14-49`|高度、落伤、冷却原本待定；需游戏内手感验收|
|15|`yijin_manual`|009：右键永久学习，加血、加伤、二段跳，死亡保留；默认 +2/+1/0.42|首次使用消耗；Capability 永久保存并同步 +2 最大生命、+1 攻击、一次空中跳 0.42；落地重置|一致|`ModItems.java:66`；`YiJinJingItem.java:14-28`；`PlayerAbilityService.java:14-81`；`PlayerAbilityEvents.java:21-96`|动态验证重生、换维、重连及按键同步|
|16|`road_barrier_helmet`|033：戴在头部，等同铁头盔盔甲值|铁制 `ArmorItem` 头盔，使用专属护甲贴图，无额外功能|一致|`ModItems.java:67-68`；`RoadBarrierHelmetItem.java:12-21`|动态验证装备模型和全部铁头盔属性|
|17|`efficient_pig_breeding`|011：周围球形 10 格猪自动繁殖，不耗食物，冷却可配置|单件书不消耗；服务端扫描并配对繁殖；成功才加配置冷却|一致|`ModItems.java:69`；`EfficientPigBreedingItem.java:16-30`；`PigBreedingService.java`|动态核对半径、幼年猪、繁殖冷却和密集猪群|
|18|`stone_pillow`|008：蓄力投掷砸人；可放置、可坐|`BlockItem`；空气长按 10–40 tick 投掷自定义实体，生存扣物并处理回收；方块可生成座位供乘坐|一致|`ModItems.java:70-71`；`PillowBlockItem.java:17-72`；`PillowBlock.java:18-58`；`PillowProjectileEntity.java`|投掷伤害原待配置；动态验收命中、掉落、回收和多人乘坐|
|19|`diamond_pillow`|016：与石抱枕相同，可投掷、放置、坐|与 18 共用完整实现，实体/方块用 `DIAMOND` 变体区分|一致|`ModItems.java:72-73`；同 18 的实现链|动态对比两个变体伤害与回收差异|
|20|`anywhere_door`|037-B：两门双向关联，只传到目的门安全落点，校验碰撞/维度/区块|单件 `BlockItem`；方块潜行右键选取/配对，进入无碰撞门后由服务端排队传送；拆除使关联失效|一致|`ModItems.java:74-75`；`AnywhereDoorBlock.java:23-63`；`DoorService.java`|静态审计不能替代跨维、未加载区块和危险落点验收|
|21|`safety_landing`|CORE-04：任意门独立安全落点，传送前检查碰撞、维度和区块|普通 `BlockItem` 放置安全落点；拆除会使邻门关系失效；实际选点校验在 `DoorService`|一致|`ModItems.java:76-77`；`SafetyLandingBlock.java:9-19`；`DoorService.java`|物品自身无交互属正常；需与任意门联合动态验收|
|22|`music_box`|047-B：可放置；右键播放配置 OGG/MP3，一次自然停止；开始时广播当前全服在线玩家，新登录不补播|单件 `BlockItem`；未配置或潜行右键开 URL 菜单，普通右键经服务端广播播放包，客户端下载/解码播放|一致|`ModItems.java:78-79`；`MusicBoxBlock.java:18-38`；`MusicBoxService.java`；`ClientMusicService.java`|必须动态验证真实 OGG/MP3、URL 安全、缓存和多人广播|
|23|`abstract_white_figurine`|010：小白人装饰方块，无效果|普通装饰 `BlockItem`，只放置自定义装饰方块|一致|`ModItems.java:80-81`；`ModBlocks.java:45-48`；`DecorativeBlock.java`|黑白变体原待定，当前只有一项|
|24|`floor_art_panel`|036：画像，可放置在地上|普通装饰 `BlockItem`，只放置地面艺术面板方块|一致|`ModItems.java:82-83`；`ModBlocks.java:49-51`；`DecorativeBlock.java`|动态检查仅地面放置方向与碰撞形状|
|25|`neutral_trophy`|051：奖杯装饰方块|普通装饰 `BlockItem`，只放置奖杯方块|一致|`ModItems.java:84-85`；`ModBlocks.java:52-54`；`DecorativeBlock.java`|造型缺少一手图，仅能验功能|
|26|`returning_scissors`|045：等同三叉戟；投掷后自动回收|耐久 250；蓄力至少 10 tick 生成自定义剪刀实体，命中后返航并归还物品，但并非完整复用原版三叉戟全部属性、附魔与水中语义|基本一致|`ModItems.java:86-87`；`ReturningScissorsItem.java:18-71`；`ReturningScissorsEntity.java`|投掷回收方向一致；需明确“等同三叉戟”要求覆盖哪些原版能力，并动态验证满背包、死亡、换维、断线和实体卸载|
|27|`rat_jerky_totem`|006：等同不死图腾|单件；任一手持且非绕过无敌伤害致死时，耗 1、保 1 血并施加原版图腾三效果|一致|`ModItems.java:88`；`ServerLifecycleEvents.java:71-91,136-138`|不是 `TotemOfUndyingItem` 子类，但可观察死亡保护一致；动态验收|
|28|`long_screwdriver`|014：铁剑属性，攻击距离 +1|铁剑基线和耐久；主手额外实体攻击距离 +1|一致|`ModItems.java:89`；`LongScrewdriverItem.java:14-30`|动态验证服务端真实命中距离而非仅面板|
|29|`pickaxe_hoe`|019：铁镐与铁锄二合一，不是铲|铁锄基类，同时加入铁镐动作、挖速和铁级掉落判断|一致|`ModItems.java:90`；`PickaxeHoeItem.java:19-41`|动态覆盖原木剥皮等不应出现的工具动作|
|30|`lighter`|024：等同打火石|直接继承原版打火石，耐久 64|一致|`ModItems.java:91`；`LighterItem.java:5-9`|动态验证点火、TNT、营火和传送门|
|31|`bath_bucket`|025：等同桶；装岩浆耗 1 耐久；总耐久 10；支持液体原待定|10 耐久自定义容器，仅收水/岩浆源；收岩浆耗 1，水不耗；NBT 保存并可倒出|一致|`ModItems.java:92`；`BathBucketItem.java:7-21`；`RestrictedFluidContainerItem.java:26-138`|“等同桶”已收束为水/岩浆；动态验收下界蒸发和最后耐久|
|32|`glow_stick`|026：照明物品，等同火把|立式/壁式方块物品，可放置，方块固定发光等级 14|一致|`ModItems.java:93-95`；`GlowStickBlock.java:8-11`|动态验证墙面/地面放置和掉落|
|33|`bml_cheer_stick`|028：一对应援棒；右键开启发光，等同火把|单个立式/壁式方块物品；放置后固定发光，没有手持右键开关或“一对”语义|不一致|`ModItems.java:96-98`；`ModBlocks.java:30-33`；`BmlCheerStickBlock.java`|决定是补手持开关/方块开关，还是把业务口径改为“单物品、放置即亮”|
|34|`paper_cup`|046-F：只能装水的桶；能否倒水原待定|单件受限容器；可收水源并倒出，不能饮用|一致|`ModItems.java:99`；`PaperCupItem.java:8-17`；`RestrictedFluidContainerItem.java:42-131`|若用户期望“喝水”，需新增明确业务需求，当前台账没有该要求|
|35|`kazoo`|031：右键吹响|单件；右键服务端广播长笛音效，冷却 1 秒|一致|`ModItems.java:100`；`KazooItem.java:14-29`|音色和听众范围需游戏内确认|
|36|`truffle_ham_cracker`|021：曲奇饱食度|普通食物，营养 2、饱和系数 0.1|一致|`ModItems.java:101,146-147`|按原版曲奇数值理解；确认术语|
|37|`potato_snack`|030：牛排饱食度|普通食物，营养 8、饱和系数 0.8|一致|`ModItems.java:102,146-147`|与原版牛排数据一致|
|38|`ration_pack`|032：20 点饱食度|普通食物，营养 20、饱和系数 1.0|一致|`ModItems.java:103,146-147`|一次填满整条饥饿值；需确认“20 点”理解|
|39|`sun_candy`|042：曲奇饱食度|普通食物，营养 2、饱和系数 0.1|一致|`ModItems.java:104,146-147`|无额外糖果效果，符合现口径|
|40|`white_rabbit_candy`|022：曲奇饱食度|普通食物，营养 2、饱和系数 0.1|一致|`ModItems.java:105,146-147`|无额外效果，符合现口径|
|41|`deep_sea_fish`|035-A：食物，属性未说明|普通食物，营养 2、饱和系数 0.1|业务待定|`ModItems.java:106,146-147`|需用户给出食物数值或认可当前保守值|
|42|`ham_sausage`|035-B：食物，属性未说明|普通食物，营养 4、饱和系数 0.3|业务待定|`ModItems.java:107,146-147`|需确认当前数值|
|43|`quail_egg`|035-C：食物，属性未说明|普通食物，营养 2、饱和系数 0.2|业务待定|`ModItems.java:108,146-147`|需确认生熟定位和数值|
|44|`green_soy_milk`|040：食物/饮品，效果未说明|普通 `FoodProperties` 物品，营养 4、饱和系数 0.3；沿用吃食物动画，无容器和饮用特性|业务待定|`ModItems.java:109,146-147`|优先确认是否应使用饮用动画、返还容器及具体效果|
|45|`beef_bites`|044：食物，属性未说明|普通食物，营养 6、饱和系数 0.6|业务待定|`ModItems.java:110,146-147`|需确认当前数值|
|46|`oil_chestnut`|046-A：食物，属性未说明|普通食物，营养 4、饱和系数 0.3|业务待定|`ModItems.java:111,146-147`|需确认当前数值|
|47|`wind_blown_cake`|046-B：食物，属性未说明|普通食物，营养 4、饱和系数 0.3|业务待定|`ModItems.java:112,146-147`|需确认当前数值|
|48|`sweet_sour_turkey_noodles`|047-A：食物，属性未说明|普通食物，营养 8、饱和系数 0.7|业务待定|`ModItems.java:113,146-147`|需确认当前数值及是否返还容器|
|49|`sesame_rice_noodles`|047-D：食物，属性未说明|普通食物，营养 8、饱和系数 0.6|业务待定|`ModItems.java:114,146-147`|需确认当前数值及是否返还容器|
|50|`potato_chips`|048：呀土豆，食物效果未说明；与 030 名称近似|普通食物，营养 6、饱和系数 0.5|业务待定|`ModItems.java:115,146-147`|先确认与 `potato_snack` 是否应合并，再确认数值|
|51|`black_truffle_ham_cracker`|049：与 021 同名，是否重复未定，食物效果未说明|另注册普通食物，营养 2、饱和系数 0.1|业务待定|`ModItems.java:116,146-147`|先确认是否应与 `truffle_ham_cracker` 合并；当前重复占奖池项|
|52|`magic_crispy_noodles`|050：食物，属性未说明|普通食物，营养 6、饱和系数 0.5|业务待定|`ModItems.java:117,146-147`|需确认当前数值|
|53|`nail_art`|004：主副手攻击 +1、攻击距离 +2，允许双手叠加|单件；主手和副手分别提供 +1 攻击、+2 实体攻击距离，四个 UUID 独立可叠加|一致|`ModItems.java:119`；`NailItem.java:14-47`|动态验证副手属性和服务端实际距离|
|54|`pink_butterfly_wings`|005-A：等同鞘翅|原版 `ElytraItem`，耐久 432，占胸甲槽并提供原版滑翔|一致|`ModItems.java:120-121`|动态验证外观及修复规则|
|55|`toy_knife`|007-B：近战武器，伤害低一点；具体属性未定|木级 `SwordItem`，额外伤害参数 1、攻速 -2.4、木耐久|业务待定|`ModItems.java:122-123`|“低一点”没有比较基准；需确认最终伤害值|
|56|`chainsaw_sword`|015：石斧属性，攻击距离 +2|石斧基线、石耐久和斧类交互；主手额外实体攻击距离 +2|一致|`ModItems.java:124`；`ChainsawSwordItem.java:15-31`|动态验证真实攻击距离和斧类交互|
|57|`eggy_eye_mask`|037-C/046-C：戴头部后失明|皮甲头盔；装备时若无外部失明则施加无限失明，卸下只移除自身写入的失明|一致|`ModItems.java:125-126`；`EggyEyeMaskItem.java:11-34`；`ServerLifecycleEvents.java:126-134`|两处需求已合并为一个注册项；动态验证外部失明共存|
|58|`wenxu_standee`|039-A：等同不死图腾；是否可放置待定|单件普通 Item 外形；全局死亡事件将其作为自定义图腾处理；不可放置|一致|`ModItems.java:127-128`；`ServerLifecycleEvents.java:71-91,136-138`|保命功能一致；“方块模型/可放置”未定，当前不实现|
|59|`cat_doll`|047-E：普通物品/装饰，用途和能否放置未定|默认可 64 堆叠的普通 Item，无使用、放置或装备行为|业务待定|`ModItems.java:129,142-144`|确认纯收藏品是否足够，或是否需要可放置|
|60|`face_mask`|052：可戴在头部盔甲，护甲值待定|原版皮甲头盔，无额外行为|一致|`ModItems.java:130-131`|当前占头盔槽且给皮甲属性；需确认护甲值即皮甲是否可接受|
|61|`fairy_wand`|005-B：近战击退 II，基础伤害和耐久待定|木剑基线，主手额外攻击击退属性 +2|一致|`ModItems.java:133`；`FairyWandItem.java:14-34`|动态确认属性 +2 与预期“击退 II”手感一致|
|62|`toy_car`|013：普通物品/装饰，无效果；不可擅自骑乘或放置|默认 64 堆叠普通 Item，无行为|一致|`ModItems.java:134,142-144`|无差距|
|63|`million_pound_note`|017：普通收藏品，无效果|默认 64 堆叠普通 Item，无行为|一致|`ModItems.java:135,142-144`|无差距|
|64|`math_exam_paper`|023：普通收藏品，无效果|默认 64 堆叠普通 Item，无行为|一致|`ModItems.java:136,142-144`|无差距|
|65|`wang_lixin_badge`|034：普通收藏品，无效果|默认 64 堆叠普通 Item，无行为|一致|`ModItems.java:137,142-144`|无差距|
|66|`flowing_black_flag`|037-A：普通物品，无效果|默认 64 堆叠普通 Item，无行为|一致|`ModItems.java:138,142-144`|无差距|
|67|`shark_dagger_pillow`|043：近战武器，等同石剑|石级 `SwordItem`，额外伤害参数 3、攻速 -2.4、石耐久；无抱枕投掷/放置能力|一致|`ModItems.java:139-140`|业务明确定位为石剑，“抱枕”目前仅造型名称|

## 判定小计

|判定|数量|
|---|---:|
|一致|46|
|基本一致|6|
|不一致|1|
|业务待定|14|
|**合计**|**67**|

当前唯一可直接由现有业务文本判为“不一致”的项目是 `bml_cheer_stick`：业务写有“右键开启发光”和“一对”，实现则是单个、放置后固定发光的火把式方块物品。6 个“基本一致”项目均保留了核心效果，但存在尚需产品确认的表现、参数或原版等价范围收束；14 个“业务待定”项目是原需求本身缺少足够属性，不能假装已经完成业务一致性确认。

## 排除、合并与重复冲突

### 明确排除且未注册

以下需求保留在 71 项需求台账，但按既有决定不进入本模组注册表：

- 007-A 玩具 AK：依赖 TAC 的外部内容。
- 018 伊蕾娜 cos 服：外部魔女模组内容。
- 041 黄金沙漠之鹰：标注 TAC 的外部内容。
- 047-C 小乐魂：外部乐魂模组内容。
- 012：名称、文本效果和图片都缺失，无法形成可验证对象。
- 007-C 子弹：仅是未定义的潜在弹药，不计作独立需求物品。

### 已合并或由技术对象承载

- 037-C 与 046-C 都是“蛋仔眼罩”，合并为一个 `eggy_eye_mask`，没有重复注册。
- 008 与 016 分别注册石、钻石抱枕，但共用投掷实体类型和业务实现，以变体区分。
- 荧光棒和 BML 应援棒各自具有立式、壁式两个技术方块，但每种只对应一个可获得 Item。
- 任意门的“两扇门”是同一个注册类型的两个方块实例，不是两个 Item ID。
- 安全落点是 CORE-04 方块需求；技术上必须有 `safety_landing` BlockItem，因此计入 67 个注册 Item。

### 尚未解决的重复或口径冲突

- 021 与 049 同名“黑松露火腿苏打饼干”，当前分别注册为 `truffle_ham_cracker` 和 `black_truffle_ham_cracker`，数值相同；应由用户决定合并、改名或保留不同版本。
- 030“呀土豆零食”和 048“呀土豆”名称近似，当前分别注册 `potato_snack`（牛排数值）与 `potato_chips`（自定数值）；需要确认是否重复。
- 028 的“一对”当前只注册一个 `bml_cheer_stick`，且“右键开启发光”未实现，是本轮直接不一致项。
- 003 的“×10”当前没有落实为最大堆叠 10 或固定奖池数量；实现最大堆叠为 16。
- 001 的 20% 收缩概率和缩回后完全不可攻击是后续实现选择，不是最初业务文本中的最终定值。
- 039-A 文绪立牌实现了图腾功能，但没有可放置方块；原业务对此本就标为待定。

## 后续处理顺序

1. 先请用户裁决 `bml_cheer_stick` 的开关方式与“一对”含义，以及 021/049、030/048 是否合并。
2. 再逐项确认 14 个“业务待定”的食物/收藏品/武器数值，避免用开发者默认值替代业务决定。
3. 对 6 个“基本一致”项目确认表现边界，尤其是耳机、伏特加、返航剪刀和 001。
4. 确认后再制定最小代码改动与逐项动态验收用例；在真实客户端、专用服务器和多人环境通过前，不把静态“代码存在”表述为玩法已经验收。
