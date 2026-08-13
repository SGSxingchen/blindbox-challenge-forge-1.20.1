# 道具人工模型导入实施计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 将 `道具.zip` 的九组人工模型转换为 Forge 1.20.1 可加载、可复现并可发行的正式资源。

**Architecture:** 以外部 ZIP 为只读设计输入，增加 GeckoLib Forge 1.20.1 前置，复杂旋转/穿戴模型使用 Geo 渲染管线，普通静态方块保留原生 JSON。保持注册、方块状态和业务功能不变，并同步视觉资源、确定性生成器与审计清单。

**Tech Stack:** Minecraft Forge 1.20.1、Java 17、GeckoLib 4.8.4、原生资源模型 JSON、Geo JSON、PNG、Python 生成器、Gradle 本地验证。

---

### 任务一：增加 GeckoLib 前置与转换契约

**文件：**
- 修改：`mod/build.gradle`
- 修改：`mod/src/main/resources/META-INF/mods.toml`
- 修改：`mod/src/main/java/cn/blindboxchallenge/BlindBoxChallenge.java`
- 修改：`tools/verify_quality_contract.py`
- 新建或修改：`tools/original_model_json_payloads.py`
- 测试：`tools/tests/test_generate_original_models.py`

- [ ] 增加官方 GeckoLib Forge 1.20.1 依赖、运行时前置声明和模组初始化。
- [ ] 增加九组目标 ID 的原生/Geo 模型结构与纹理引用断言。
- [ ] 先运行专项测试，确认旧资源不能满足新模型契约。
- [ ] 将人工模型转换后的 JSON 作为确定性载荷，禁止运行时依赖 ZIP。
- [ ] 运行专项测试，确认模型输出集合和引用闭合。

### 任务二：转换八组方块模型

**文件：**
- 修改：`mod/src/main/resources/assets/blindboxchallenge/models/block/*.json`
- 修改：`mod/src/main/resources/assets/blindboxchallenge/models/item/*.json`
- 修改：`mod/src/main/resources/assets/blindboxchallenge/textures/block/*.png`

- [ ] 转换任意门、奖杯、蒙娜奶龙画像、白色人偶的路径与展示参数。
- [ ] 将八音盒原生 JSON 不兼容旋转表达接入 GeckoLib 模型管线，保持人工外形。
- [ ] 导入石墩子并校准物品/投掷显示尺度。
- [ ] 加厚钻石抱枕，保留人工钻石轮廓。
- [ ] 建立应援棒开关及墙面四状态兼容模型，生成发行尺寸贴图。
- [ ] 运行模型生成器检查和 JSON/纹理引用验证。

### 任务三：通过 GeckoLib 接入交通锥头盔

**文件：**
- 修改：`mod/src/main/resources/assets/blindboxchallenge/models/item/road_barrier_helmet.json`
- 修改：`mod/src/main/resources/assets/blindboxchallenge/textures/item/road_barrier_helmet.png`
- 检查：`mod/src/main/resources/assets/blindboxchallenge/textures/models/armor/road_barrier_layer_1.png`

- [ ] 将 bbmodel 的 19 个盒体转换为 GeckoLib Geo 模型并接入物品/盔甲渲染器。
- [ ] 校准 GUI、手持、掉落和头部展示变换。
- [ ] 保持现有 ArmorItem 与穿戴层不变并验证资源引用。

### 任务四：闭合生成器、清单和文档

**文件：**
- 修改：`tools/generate_original_models.py`
- 修改：`tools/generate_original_textures.py`
- 修改：`tools/original_item_pixel_payloads.py`
- 修改：`docs/ASSET_MANIFEST.md`
- 新建：`docs/PROP_MODEL_IMPORT.md`
- 修改：`AGENTS.md`

- [ ] 将全部模型/贴图输出纳入确定性 `--check`。
- [ ] 按实际正式资源全集重算 186 项资源清单 SHA；确认 68 张 PNG 不变，增量仅为 2 个 Geo 与 2 个动画 JSON。
- [ ] 记录输入映射、转换规则、碰撞边界和验收结果。
- [ ] 更新项目索引并运行乱码、占位符和差异检查。

### 任务五：构建和视觉验收

**文件：**
- 测试：`tools/tests/`
- 构建：`mod/build.gradle`

- [ ] 运行 Python 生成器、质量契约及全部专项测试。
- [ ] 运行 `mod/gradlew.bat build` 并检查正式 JAR 资源。
- [ ] 启动专用服务器确认资源/注册无回归。
- [ ] 启动真实客户端逐项截图九组模型，复核背包、手持、放置与特殊状态。
- [ ] 修复所有紫黑材质、尺度、方向和首看辨识问题后重复验证。

### 任务六：远程交付（按用户要求暂停）

**文件：**
- 修改：版本与发行文档（仅当现有发布流程要求）

- [ ] 仅暂存本次模型导入及此前同分支已批准的审计文档，不纳入 `output/`。
- [ ] 本地验证完成后停止；不得推送或触发 GitHub Actions。
- [ ] 仅在用户再次明确授权后，才提交、推送并创建中文 PR。
- [ ] tag、Release 与远程门禁继续暂停，不以本地结果冒充远程发行证据。
