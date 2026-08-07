# P5 可审计原创资源替换记录

> 关联工单：[Issue #5](https://github.com/SGSxingchen/blindbox-challenge-forge-1.20.1/issues/5)。本记录说明正式资源的完整原创重绘/定义闭合；不改变已验收玩法、注册 ID、网络协议或 CI 的真实部署组合。

## 目标与原则

原版 PNG 不能直接发行。本批以可复现生成器输出项目内原创重绘 PNG；不读取、采样、裁切或混合 `source-package/` 的照片、包装、截图、压缩素材和第三方成品。保留资源路径只为存档和联机兼容，不改变注册 ID、玩法、显示名或元数据。

每批必须同时：

1. 保存生成来源和 SHA-256；
2. 更新 `ASSET_MANIFEST.md` 的路径、边界和哈希；
3. 保持资源路径、模型引用、双语与正式 Jar/ciTest 隔离；
4. 在 Hosted Runner 对该提交重新运行质量、专服、强杀恢复、真实单客户端、真实双客户端和汇总。

## 批次 A：确定性几何像素贴图

`tools/generate_original_textures.py` 仅用 Python 标准库生成 68 张此前无法追溯授权的 PNG：67 张 16×16 RGBA 方块/物品贴图和 1 张 64×32 RGBA 装备层。像素由固定前缀加资源路径的 SHA-256、类别轮廓和程序调色板计算，输出只含 `IHDR`、`IDAT`、`IEND`；不读取旧 PNG 的内容，因此可以用下列命令完全复现并检测漂移：

```bash
python3 tools/generate_original_textures.py
python3 tools/generate_original_textures.py --update-manifest
python3 tools/generate_original_textures.py --check
```

轮廓只使用中性的工具、纸张、食品、佩饰、方块、门、照明棒、抱枕、八音盒和安全落点几何符号；不绘制人物、赛事、商标、画作、包装、旗帜文字或现实照片。

## 批次 B：模型、数据与元数据闭合

完整原创资源包不能只保留 PNG。`tools/generate_original_models.py` 确定性生成并校验 77 个 block/item 模型、9 个 blockstate 与 9 个方块战利品表；它保留资源路径、注册 ID、两个既有模型谓词和墙面照明棒回收站立物的玩法语义。`tools/generate_original_metadata.py` 确定性生成 119 键的中英双语、正式 `mods.toml` 与 `pack.mcmeta`。两者均不读取原版图片，也不改 Java、注册、网络或存档代码：

```bash
python3 tools/generate_original_models.py --check
python3 tools/generate_original_metadata.py --check
```

生成器内的发行描述遵循当前事实：项目方提供素材与需求背景，许可本项目使用、修改与发行；原版图片不进入 Release；不外推项目外第三方再授权。

本批的 68 项 PNG 统一记录为“项目内原创重绘”；其余 114 项模型、方块状态、战利品、双语和元数据为“项目内原创定义产物”，由确定性生成器与既有兼容资源路径闭合。项目方提供素材与需求背景，许可本项目使用、修改与发行；该许可不外推至项目外第三方。

## 真实边界

发行包不包含原版图片、原始照片或压缩素材。第三方 URL 与音频内容的可用性、版权和长期稳定性不作承诺；项目方对素材与需求背景给予本项目使用、修改与发行许可，但不外推项目外第三方再授权。
