# 59 张物品贴图原创重绘交付记录

## 范围与结论

本批覆盖正式资源目录中的 59 张物品 PNG，全部为 16×16 RGBA。54 项仅以项目台账中的文字语义作为参考，5 项（`blind_box`、`packing_tool`、`letter`、`potato_chips`、`black_truffle_ham_cracker`）按自由设计处理。59 项人工视觉复核均通过；其中 5 项定向重试：`chainsaw_sword`、`flowing_black_flag`、`rat_jerky_totem`、`road_barrier_helmet`、`safety_exit_sign_shield`。

## 流程与边界

1. 使用 `gpt-image-2` 按逐项语义提示生成原创母稿，要求单个物品、无场景、无文字、无品牌、无水印、无人物手持，并拒绝广告背景和包装。
2. 本地执行键色去背、最近邻缩放至 16×16、RGBA 规范化，再逐项人工视觉复核。
3. 不读取、采样或混合不可访问的原广告参考图；没有以 `source-package/` 中的图片作为本批生成输入，也不声称查看过其中内容。
4. 安装前的全量备份位于 `output/imagegen/item-redraw/install-backup/20260811T131647939860Z/`。候选、母稿、复核和备份均在 `output/` 中，不提交。正式产物位于 `mod/src/main/resources/assets/blindboxchallenge/textures/item/`。

## 生成器闭合

`tools/generate_original_textures.py` 以源码内嵌 zlib+Base64 RGBA 像素载荷和 Pillow 逐字节重建 59 张物品 PNG；运行时不读取正式 PNG、`output/` 或网络。`--check-items` 只检查 59 项；默认无参数仅显示帮助，写入必须显式使用 `--write-items` 或 `--write-all`。为恢复既有完整生成器契约，8 张已跟踪且工作树干净的方块 PNG 亦以确定性载荷闭合，未改动其视觉字节。盔甲纹理保持原算法。

## 资源清单审计

任务开始时，`ASSET_MANIFEST.md` 的 182 条记录中有 170 条旧 SHA-256 与当前正式资源漂移，另有 12 条一致。逐路径确认 182 项正式资源均由 Git 跟踪且工作树干净后，只更新资源清单中的哈希、审查日期与本批物品来源口径，不修改任何正式资源内容。闭合复核结果为 182/182 路径存在且 SHA-256 与清单一致。

## 验证命令与边界

需要 Pillow；Gradle 使用 Java 17。

```powershell
python -m unittest discover -s tools/tests -p 'test_*.py' -v
python -m py_compile tools/generate_original_textures.py tools/original_item_pixel_payloads.py tools/verify_item_texture_redraw.py
python tools/verify_item_texture_redraw.py --validate-manifest
python tools/generate_original_textures.py --check-items
python tools/generate_original_textures.py --check
python tools/verify_quality_contract.py
$env:JAVA_HOME='<Java 17 路径>'; .\mod\gradlew.bat clean check
```

本地验证只证明结构、哈希、生成器可复现性、静态质量契约与 Gradle 检查，不等同于真实游戏中的视觉运行验证。正式 Jar、运行期表现与发布门禁仍由该提交对应的 Hosted Runner 六门禁裁决。

2026-08-11 本地结果：工具单测 89 项全部通过；59 张正式 PNG、生成器与资源清单三方 SHA-256 全部一致；资源清单 182 条路径全部存在且哈希一致；贴图、模型与元数据三个生成器的完整 `--check` 均通过；质量静态契约通过；Temurin Java 17.0.17 下 `clean check` 成功。为恢复完整生成器契约，在不修改正式资源字节的前提下，另闭合了 8 张方块 PNG、95 项权威模型/方块状态/战利品 JSON 与 4 项权威元数据。

模型与元数据生成器的写入入口已收紧：无参数只显示帮助，`--check` 只读，只有显式 `--write-all` 才执行写入。写入会先在唯一 staging 根生成并校验完整目标集合，再以同目录临时文件、`fsync` 和原子替换安装；任一安装失败都会尽力回滚全部目标并聚合前向与回滚错误，最后清理 staging。测试注入资源根同样执行目标路径边界检查。

## 逐项结果

|编号|正式贴图|参考状态|视觉状态|正式 SHA-256|
|---|---|---|---|---|
|`adrenaline`|`assets/blindboxchallenge/textures/item/adrenaline.png`|台账语义参考|通过|`f3aab6b614c13cf57f577f6f359e5d4a6fcde13613347e97172b35e7d8bf3506`|
|`bath_bucket`|`assets/blindboxchallenge/textures/item/bath_bucket.png`|台账语义参考|通过|`bc124420a6288a3c6d92edb395abdc9e3b872a14536a1fd56eb162806ca07167`|
|`beef_bites`|`assets/blindboxchallenge/textures/item/beef_bites.png`|台账语义参考|通过|`6351c567b55aa3f46e4195b88c33645f34fdbb11eb54d845c495b37a5a4f7bc9`|
|`birthday_candle`|`assets/blindboxchallenge/textures/item/birthday_candle.png`|台账语义参考|通过|`e0b4f72114738ad7f8c801ef74113bcbc5e3c051dd3f8de3bb5332adc7960a61`|
|`black_knight_telescopic_knife`|`assets/blindboxchallenge/textures/item/black_knight_telescopic_knife.png`|台账语义参考|通过|`2dd5994e2dc39f2ee9cf2a69edc931750ba585639ff3388b7b7affab589fcc9e`|
|`black_knight_telescopic_knife_extended`|`assets/blindboxchallenge/textures/item/black_knight_telescopic_knife_extended.png`|台账语义参考|通过|`21a2bfa3bc6823e2bc547901d5f8d5fd1635395d03b0f174ce3934f1316bcef9`|
|`black_truffle_ham_cracker`|`assets/blindboxchallenge/textures/item/black_truffle_ham_cracker.png`|自由设计|通过|`6c30e0b5d9b1c0780d149acbd38bc8073b18523066b51a71a22eda1fed19e6ff`|
|`blind_box`|`assets/blindboxchallenge/textures/item/blind_box.png`|自由设计|通过|`ec1ea530074a807aa47c63799ae0a3f3db37202153f7c34b5202e36b79d6db62`|
|`cat_doll`|`assets/blindboxchallenge/textures/item/cat_doll.png`|台账语义参考|通过|`0a958cdba10a1b8eb8a98e2239afa4790258d38b24fbc741344f2931bacc5816`|
|`chainsaw_sword`|`assets/blindboxchallenge/textures/item/chainsaw_sword.png`|台账语义参考|通过（定向重试）|`a1a32a8db0b3f6654cd7329f722eb4c306558e0b505becd61f58d2d21e7f7e8a`|
|`clockwork_chicken`|`assets/blindboxchallenge/textures/item/clockwork_chicken.png`|台账语义参考|通过|`644f9569273de396445c1e232ea04faec4f977b588e1fe7e876784019020b08c`|
|`death_note`|`assets/blindboxchallenge/textures/item/death_note.png`|台账语义参考|通过|`2fd9d707d254305cc99fe29adc332cd39f9b695ccb4c2e41706989391e430016`|
|`decision_coin`|`assets/blindboxchallenge/textures/item/decision_coin.png`|台账语义参考|通过|`72dd5c41bc07d4ce1f450f9f82e2539b0cae60c2d66aa531524db4003ab3b694`|
|`deep_sea_fish`|`assets/blindboxchallenge/textures/item/deep_sea_fish.png`|台账语义参考|通过|`c6af2b3218d2dc0d44f2fc6add781f77a361d6cb8d3676824fbe61949afae3f9`|
|`efficient_pig_breeding`|`assets/blindboxchallenge/textures/item/efficient_pig_breeding.png`|台账语义参考|通过|`7c7bfaac0be876f594cd24fc7b5d9ea2c4951496c59f2c1f73d98feed653240a`|
|`eggy_eye_mask`|`assets/blindboxchallenge/textures/item/eggy_eye_mask.png`|台账语义参考|通过|`373f78563de13c657083f06f3ba74d0d09582d2e19e2a53eba9166ed1d9d159c`|
|`face_mask`|`assets/blindboxchallenge/textures/item/face_mask.png`|台账语义参考|通过|`9311518ec1258fa0efa71311b6da63ae47ae66212e3dca5dbd327be7f4085dc3`|
|`fairy_wand`|`assets/blindboxchallenge/textures/item/fairy_wand.png`|台账语义参考|通过|`c6e182d4987f0236b59d54d5d78b34912cc2a9a8ab7c2f8b0727a37e007c56d7`|
|`flowing_black_flag`|`assets/blindboxchallenge/textures/item/flowing_black_flag.png`|台账语义参考|通过（定向重试）|`429987a2cc574cca1b7ede64ae03edc926f1ab3bb65b43271eccda0eace20a14`|
|`green_soy_milk`|`assets/blindboxchallenge/textures/item/green_soy_milk.png`|台账语义参考|通过|`7d11054b27062f083807e97b41a99725c3ebba7e4ee8083d232d27fde3a0d50e`|
|`ham_sausage`|`assets/blindboxchallenge/textures/item/ham_sausage.png`|台账语义参考|通过|`856a1f980d17c138d1bef8cf85286dc43d3f2bccd0d760908cfb643123d4a7fc`|
|`headphones`|`assets/blindboxchallenge/textures/item/headphones.png`|台账语义参考|通过|`225a2391aed1007e5e2fdf467462feb48326d1c70c8f24a2d56aa01e21952ca6`|
|`kazoo`|`assets/blindboxchallenge/textures/item/kazoo.png`|台账语义参考|通过|`dc8893bd15a7a36b7754eff764833123bb1c846193d08cc83c91316cdfabc0a4`|
|`letter`|`assets/blindboxchallenge/textures/item/letter.png`|自由设计|通过|`dfb5db4e023106db18024de2eb876adf6b0f5a25b2abcb58b3b087d8a49d020c`|
|`lighter`|`assets/blindboxchallenge/textures/item/lighter.png`|台账语义参考|通过|`f4b94909e0e3b220b39320974f792f9f3ad1ae459ef444bc3f17107e96e3e6c0`|
|`long_screwdriver`|`assets/blindboxchallenge/textures/item/long_screwdriver.png`|台账语义参考|通过|`7125dd4ad2655a3a51bac9641c029a9a153b887095d9068c065e34d601eef025`|
|`magic_crispy_noodles`|`assets/blindboxchallenge/textures/item/magic_crispy_noodles.png`|台账语义参考|通过|`d7f90312befa4d621e30e08e828f618f2f0344ed6f8a4bb5fd4a4896fdf3fb49`|
|`math_exam_paper`|`assets/blindboxchallenge/textures/item/math_exam_paper.png`|台账语义参考|通过|`5f63fbc9ccc3ace4c98f9300a900d46becc60456d4116a1a4415a0f84f09a0f9`|
|`million_pound_note`|`assets/blindboxchallenge/textures/item/million_pound_note.png`|台账语义参考|通过|`8033b06d2b004118ba7addd1232bf5c58a9224300c50c4ed480cbaa65391ff31`|
|`nail_art`|`assets/blindboxchallenge/textures/item/nail_art.png`|台账语义参考|通过|`2926092668d83f04754ec302fb6b813e101b5ed7373a362b5e5fd512868393fb`|
|`oil_chestnut`|`assets/blindboxchallenge/textures/item/oil_chestnut.png`|台账语义参考|通过|`179757b7cd78492bcf79f4d6ed63faec3b82a9de5be8a22dab883a105143ac4f`|
|`packing_tool`|`assets/blindboxchallenge/textures/item/packing_tool.png`|自由设计|通过|`588999b960ab92532db9e0b00f45581fb4a77883b989813da7874442eaf39abd`|
|`paper_cup`|`assets/blindboxchallenge/textures/item/paper_cup.png`|台账语义参考|通过|`7f183d5593670e54322387de6d318efd53b7df9970c7ec976c244716b3e3ad0a`|
|`pickaxe_hoe`|`assets/blindboxchallenge/textures/item/pickaxe_hoe.png`|台账语义参考|通过|`70b1e0a346efe2f838c748009f061c6fabe4a55b65522531bde86ea5cfd74343`|
|`pink_butterfly_wings`|`assets/blindboxchallenge/textures/item/pink_butterfly_wings.png`|台账语义参考|通过|`8400f0228b9b2e9d34091f84ce499d113e428463f45670708be9db9e713685f2`|
|`potato_chips`|`assets/blindboxchallenge/textures/item/potato_chips.png`|自由设计|通过|`2c1c08867b8a702591447f5601eef07fe79e50f1322235a02889ab0aebf442cf`|
|`potato_snack`|`assets/blindboxchallenge/textures/item/potato_snack.png`|台账语义参考|通过|`364bf44a3a120cd114601060f7deee02eb692d91b29eaa013186601896ee1b86`|
|`purple_toy_pickaxe_sword_pickaxe`|`assets/blindboxchallenge/textures/item/purple_toy_pickaxe_sword_pickaxe.png`|台账语义参考|通过|`9e722629d5134456d09e1b84a9beeb561231be2ee66ce4d92d331fba276b2847`|
|`purple_toy_pickaxe_sword_sword`|`assets/blindboxchallenge/textures/item/purple_toy_pickaxe_sword_sword.png`|台账语义参考|通过|`bf8ee8b63000ad301f3cce5dda8de1b9c98216b5a1c616168f7ee40eac0a2326`|
|`quail_egg`|`assets/blindboxchallenge/textures/item/quail_egg.png`|台账语义参考|通过|`e03a8dcb06687125588afac56fbf9845c76e2cafe4945344148c02908f2b6822`|
|`rainbow_hoop`|`assets/blindboxchallenge/textures/item/rainbow_hoop.png`|台账语义参考|通过|`a42dea7227ad43ac01da781808ac9ebf4b2fb561f126acbd9cd14069af272df2`|
|`rat_jerky_totem`|`assets/blindboxchallenge/textures/item/rat_jerky_totem.png`|台账语义参考|通过（定向重试）|`0521d2d0d1b48541b1bd67a2f933abf8e0ad9be92cc82cc1d57d8cdeccf77a41`|
|`ration_pack`|`assets/blindboxchallenge/textures/item/ration_pack.png`|台账语义参考|通过|`a7003a985f8afabff6ed1f3243097bf554dc09cb887d69c8beb47837be5ccf49`|
|`returning_scissors`|`assets/blindboxchallenge/textures/item/returning_scissors.png`|台账语义参考|通过|`0534e8f5b0eb9348555c672ee19cd862a54361b43e5c8a235473f2012648604b`|
|`road_barrier_helmet`|`assets/blindboxchallenge/textures/item/road_barrier_helmet.png`|台账语义参考|通过（定向重试）|`424c3019557e900e84aa8a151ff80765439cbd4dd959462cbb2b43cf9c5d3e5c`|
|`safety_exit_sign_shield`|`assets/blindboxchallenge/textures/item/safety_exit_sign_shield.png`|台账语义参考|通过（定向重试）|`adccb39d50a25d5ca29883ef3c63d33c9b03bba0cc0d3b91431c0e7838c80326`|
|`sesame_rice_noodles`|`assets/blindboxchallenge/textures/item/sesame_rice_noodles.png`|台账语义参考|通过|`7e4e24c3c3184944c860cb9ccb0cb11034adff359241875d7baeb20d6170ed54`|
|`shark_dagger_pillow`|`assets/blindboxchallenge/textures/item/shark_dagger_pillow.png`|台账语义参考|通过|`3111ee170a56efd13d4736d3067875a8e03e682772d3d4d520ffd5e449fdda6d`|
|`sun_candy`|`assets/blindboxchallenge/textures/item/sun_candy.png`|台账语义参考|通过|`fb47f523324b027afbc8f6126368a87c68d63d4df11264914357ec0a94c1ab70`|
|`sweet_sour_turkey_noodles`|`assets/blindboxchallenge/textures/item/sweet_sour_turkey_noodles.png`|台账语义参考|通过|`1d2a0f90357f88b8abef75a5b17c2971762c23307d71cb8b2f7422db44655c8a`|
|`toy_car`|`assets/blindboxchallenge/textures/item/toy_car.png`|台账语义参考|通过|`3411e2a33d9941c455e01743fdc8ed89841c11767a00cdb6e2ca886bfdae9de0`|
|`toy_knife`|`assets/blindboxchallenge/textures/item/toy_knife.png`|台账语义参考|通过|`9dd953ec7454f51dc3364fcff3f386bf4490fb5796f5a82e707cabfe36cfe05f`|
|`truffle_ham_cracker`|`assets/blindboxchallenge/textures/item/truffle_ham_cracker.png`|台账语义参考|通过|`f3738c2325fa95a0e7e9078ef91b0171bb689f2049b673b4eea9e5c07412f549`|
|`vodka`|`assets/blindboxchallenge/textures/item/vodka.png`|台账语义参考|通过|`74791d082a0d59efd1b32eedf72ce38cd41fe9bf8c0ea0679f0662251a2a9466`|
|`wang_lixin_badge`|`assets/blindboxchallenge/textures/item/wang_lixin_badge.png`|台账语义参考|通过|`189a143a7467c41303e2539623212a52bc0daafe67e22d91be73671caecb194e`|
|`wenxu_standee`|`assets/blindboxchallenge/textures/item/wenxu_standee.png`|台账语义参考|通过|`343f82044201decf1da801d030d29e5cd20b1a52e864edda32fc53af72b25ccb`|
|`white_rabbit_candy`|`assets/blindboxchallenge/textures/item/white_rabbit_candy.png`|台账语义参考|通过|`32b8fb11f2f425e144a0766e4e98f13edd1a33c480a23f17a9b94340b941c2d6`|
|`wind_blown_cake`|`assets/blindboxchallenge/textures/item/wind_blown_cake.png`|台账语义参考|通过|`ea922de6bb392cabca11d3727c0b1e9b5c3880570cac3a6c3fde39288ab7fb29`|
|`yijin_manual`|`assets/blindboxchallenge/textures/item/yijin_manual.png`|台账语义参考|通过|`f620480c00989db59e90d653214e929ccd72e15d2cf2fad02c1668b4c82be83c`|
