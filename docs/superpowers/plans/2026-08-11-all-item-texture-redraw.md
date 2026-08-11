# 全部物品贴图原创重绘实施计划

> **供代理执行：**必须使用 `superpowers:subagent-driven-development`（推荐）或 `superpowers:executing-plans` 按任务逐项执行；所有步骤使用复选框追踪。

**目标：**重绘 59 张正式物品贴图，使其成为透明背景、单主体、清晰可辨的 Minecraft 16×16 原创图标，并移除广告图缩小重绘痕迹。

**架构：**以一份逐项 JSON 清单作为生成、后处理和审计的唯一数据源；每项独立调用图像生成技能，母稿与候选保存在 `output/imagegen`，审核通过后才复制到正式资源。正式 PNG 由现有生成器复现，文档与哈希清单同步闭合。

**技术栈：**Python 3、Pillow、`chordvers-imagegen`、Forge 1.20.1 资源模型、现有质量契约。

---

### 任务 1：冻结基线与逐项提示词清单

**文件：**
- 新建：`tools/item_texture_redraw_manifest.json`
- 新建：`tools/verify_item_texture_redraw.py`
- 测试：`tools/tests/test_verify_item_texture_redraw.py`

- [ ] **步骤 1：记录 59 张正式 PNG 的路径、尺寸、模式、SHA-256 和 Git 状态**

运行：`python tools/verify_item_texture_redraw.py --write-baseline output/imagegen/item-redraw/baseline.json`

预期：基线恰好包含 59 张 `textures/item/*.png`，并单独标记 001 两张工作区脏文件。

- [ ] **步骤 2：为每张贴图填写独立提示词字段**

每项必须包含 `id`、`texture`、`reference_status`、`subject`、`must_show`、`avoid`、`palette`；`avoid` 固定包含广告背景、包装、品牌、文字、水印和人物手持。

- [ ] **步骤 3：编写并运行失败测试**

运行：`python -m unittest tools.tests.test_verify_item_texture_redraw -v`

预期：缺字段、重复路径、非 59 项、引用不存在或 001 未标记保护时失败。

- [ ] **步骤 4：实现最小清单校验并运行测试**

运行：`python -m unittest tools.tests.test_verify_item_texture_redraw -v`

预期：所有清单结构测试通过。

### 任务 2：逐项生成母稿与透明候选

**文件：**
- 新建：`tools/generate_item_texture_candidates.py`
- 输出：`output/imagegen/item-redraw/sources/*.png`
- 输出：`output/imagegen/item-redraw/clean/*.png`
- 输出：`output/imagegen/item-redraw/candidates/*.png`

- [ ] **步骤 1：验证 `chordvers-imagegen` 可用性并试生成一个无参考物品**

运行：`python C:\Users\85330\.codex\skills\chordvers-imagegen\scripts\chordvers_imagegen.py --help`

预期：显示 `generate`、`edit`、`--chroma-key` 和输出参数。

- [ ] **步骤 2：生成 `blind_box` 母稿并做 16×16 可读性试验**

提示词明确“单个物品、硬边像素画、纯键色背景、无包装广告、无文字、无阴影”；输出进入 `sources/blind_box.png`，去背与最近邻缩放后进入候选目录。

- [ ] **步骤 3：检查试验候选**

运行：`python tools/verify_item_texture_redraw.py --candidate blind_box --report output/imagegen/item-redraw/blind_box-report.json`

预期：16×16 RGBA、透明与实色并存、边界不触碰画布、颜色数量与主体覆盖率处于清单阈值内。

- [ ] **步骤 4：按清单逐项独立生成其余候选**

运行：`python tools/generate_item_texture_candidates.py --manifest tools/item_texture_redraw_manifest.json --skip-protected`

预期：除受保护的 001 两张外，每张正式贴图均有独立母稿、去背图和 16×16 候选；失败项记录原因且不伪造输出。

### 任务 3：视觉审计与定向重试

**文件：**
- 新建：`tools/render_item_texture_contact_sheet.py`
- 输出：`output/imagegen/item-redraw/contact-sheet.png`
- 输出：`output/imagegen/item-redraw/visual-review.json`

- [ ] **步骤 1：生成带资源 ID 的 8 倍最近邻联系表**

运行：`python tools/render_item_texture_contact_sheet.py --input output/imagegen/item-redraw/candidates --output output/imagegen/item-redraw/contact-sheet.png`

预期：59 个槽位顺序与清单一致，001 使用受保护的正式候选，其余使用新候选。

- [ ] **步骤 2：逐项检查广告痕迹和轮廓可读性**

对每项记录 `通过` 或一个具体失败原因；禁止用自动尺寸检查代替视觉判断。

- [ ] **步骤 3：只针对失败原因重新生成**

每次重试只改变构图、主体强调、色板或背景复杂度中的一个变量，并保存版本化母稿；不得覆盖旧版本证据。

- [ ] **步骤 4：复查最终联系表**

预期：59 项全部标记通过，没有包装广告、品牌文字、人物摄影和难以辨认的抽象色块。

### 任务 4：替换正式资源并闭合生成器

**文件：**
- 修改：`mod/src/main/resources/assets/blindboxchallenge/textures/item/*.png`
- 修改：`tools/generate_original_textures.py`
- 测试：`tools/tests/test_verify_item_texture_redraw.py`

- [ ] **步骤 1：复制审核通过的候选，跳过受保护 001 文件**

运行：`python tools/generate_item_texture_candidates.py --install-approved output/imagegen/item-redraw/visual-review.json --skip-protected`

预期：只有审核通过且非受保护的正式 PNG 被替换。

- [ ] **步骤 2：更新确定性生成器数据**

将最终像素数据写入生成器的逐项定义，使 `python tools/generate_original_textures.py --check` 对物品贴图逐字节一致；无关方块漂移必须单独报告，不能冒充本任务失败。

- [ ] **步骤 3：运行资源引用和 PNG 合规检查**

运行：`python tools/verify_item_texture_redraw.py --verify-installed`

预期：59/59 PNG 合规、模型引用全部解析、路径和资源 ID 未改变、001 SHA 与基线一致。

### 任务 5：同步文档与质量验证

**文件：**
- 修改：`docs/ASSET_MANIFEST.md`
- 修改：`docs/ORIGINAL_ASSET_REPLACEMENT.md`
- 新建：`docs/ITEM_TEXTURE_REDRAW.md`
- 修改：`AGENTS.md`

- [ ] **步骤 1：更新 59 张 PNG 的 SHA-256 和来源口径**

资源清单必须写明“依据台账语义重新原创绘制；参考原图当前不可访问；未读取、采样或混合广告图”。

- [ ] **步骤 2：记录逐项视觉规则、生成方式和联系表路径**

`docs/ITEM_TEXTURE_REDRAW.md` 应包含范围、参考边界、59 项完成表、受保护 001 说明、验证命令和结果。

- [ ] **步骤 3：在项目索引新增文档链接**

在 `AGENTS.md` 的规划文档列表加入 `docs/ITEM_TEXTURE_REDRAW.md`，不重写用户已有阶段说明。

- [ ] **步骤 4：运行本地验证**

运行：`python -m unittest tools.tests.test_verify_item_texture_redraw -v`

运行：`python tools/verify_item_texture_redraw.py --verify-installed`

运行：`python tools/verify_quality_contract.py`

运行：`./gradlew.bat check`（工作目录 `mod`）

预期：所有命令退出码为 0；若 Forge 动态行为只能由 Hosted Runner 裁决，文档必须准确限定本地证据范围。

