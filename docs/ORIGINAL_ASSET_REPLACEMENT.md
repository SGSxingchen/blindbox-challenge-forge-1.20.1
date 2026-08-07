# P5 可审计原创资源替换记录

> 关联工单：[Issue #5](https://github.com/SGSxingchen/blindbox-challenge-forge-1.20.1/issues/5)。本记录只处理公开发行前的资源权利整改；不改变已验收玩法、注册 ID、网络协议或 CI 的真实部署组合。

## 目标与原则

历史资源不能仅凭仓库文件推定作者或可再发行许可，因此不能以“像素化”“转绘”推定授权。本整改按可复现批次替换为项目内新建的中性原创资源；不读取、采样、裁切或混合 `source-package/` 的照片、包装、截图、压缩素材和第三方成品。保留注册 ID 仅为存档和联机兼容，公开显示名称、图案和模型必须继续接受名称/商标审查。

每批必须同时：

1. 保存生成/建模的可审计来源和 SHA-256；
2. 更新 `ASSET_MANIFEST.md` 的作者、边界、许可证状态及哈希；
3. 保持资源路径、模型引用、双语与正式 Jar/ciTest 隔离；
4. 在 Hosted Runner 对该提交重新运行质量、专服、强杀恢复、真实单客户端、真实双客户端和汇总；
5. 不把“项目维护者新建”误写成项目已经取得整体发行许可证。

## 批次 A：确定性几何像素贴图

`tools/generate_original_textures.py` 仅用 Python 标准库生成 68 张此前无法追溯授权的 PNG：67 张 16×16 RGBA 方块/物品贴图和 1 张 64×32 RGBA 装备层。像素由固定前缀加资源路径的 SHA-256、类别轮廓和程序调色板计算，输出只含 `IHDR`、`IDAT`、`IEND`；不读取旧 PNG 的内容，因此可以用下列命令完全复现并检测漂移：

```bash
python3 tools/generate_original_textures.py
python3 tools/generate_original_textures.py --update-manifest
python3 tools/generate_original_textures.py --check
```

轮廓只使用中性的工具、纸张、食品、佩饰、方块、门、照明棒、抱枕、八音盒和安全落点几何符号；不绘制人物、赛事、商标、画作、包装、旗帜文字或现实照片。该批不处理现有模型 JSON、战利品表、语言文件、`mods.toml` 与 `pack.mcmeta`，它们必须在后续独立批次重新建立可审计作者来源。

本批完成后，资源审计从 167 项“待权利人/法务确认；Release 阻塞”和 15 项“仍需发行前项目许可证确认”，变为 99 项前者和 83 项后者。这里的 68 项转为“项目维护者新建；仍需项目发行许可证确认”，并不声称已经取得整体公开发行权。

## 批次 B：中性模型、状态与战利品模板

`tools/generate_original_models.py` 将重建除三项已审计 P5 方块及其方块物品模型外的全部现有模型 JSON，以及九个历史方块状态和九个历史方块战利品表。它不读取旧 JSON：模型路径和注册 ID 只作为兼容约束；手持物使用原创的 `minecraft:item/generated` 模板，方块使用中性方块、火把、门或抱枕几何模板，状态只覆盖代码已注册的 `lit`/`facing` 变体，战利品只保留原版爆炸存活条件和本模组同 ID 方块物品掉落。伸缩刀和变形玩具工具的两个既有客户端谓词仍明确写入生成器，避免资源整改破坏已验收玩法。

```bash
python3 tools/generate_original_models.py
python3 tools/generate_original_models.py --update-manifest
python3 tools/generate_original_models.py --check
```

此批也不读取、采样或复制旧 JSON 的文字、图案、结构或第三方内容。新模板仍待项目整体许可证确认；语言、`mods.toml` 和 `pack.mcmeta` 留在最后元数据批次处理。

本批的当前工作树审计将剩余历史授权阻塞降为 4 项：双语语言文件、`mods.toml` 与 `pack.mcmeta`。另有 178 项项目维护者新建资源仍需要项目整体许可证；批次 B 必须先通过其提交的同 SHA Hosted Runner 门禁，不能以静态生成器或清单状态取代动态验收。

## 仍未覆盖的发行条件

替换一批视觉文件不自动取得项目整体许可证，也不确认注册 ID 或展示名称不涉及商标/角色风险。必须继续完成其余历史 JSON/元数据的原创重建、全部新资源的项目许可证条件以及名称/商标审查；在此之前 Issue #5 继续 OPEN，不创建 tag、公开 Release 或上传 Jar。
