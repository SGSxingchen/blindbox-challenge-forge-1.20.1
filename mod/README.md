# 盲盒挑战生存 Forge 模组

目标运行环境：Java 17、Minecraft 1.20.1、Forge 47.4.22。P1—P5 的技术范围已在 GitHub Hosted Runner 完成同 SHA 六门禁验收；证据、候选 Jar SHA-256 和真实边界见仓库根目录的 [P5 验收矩阵](../docs/P5_ACCEPTANCE.md)。

本机禁止运行 Gradle、Forge 或 Minecraft 动态验证；构建、专服及真实客户端验证只在 GitHub Hosted Runner 执行。`src/ciTest` 与 `*-citest.jar` 是 CI 专用探针/夹具，绝不能作为正式模组安装或分发。

目前没有公开 Release：正式资源中 167 项尚待权利人/法务确认，15 项尚待项目发行许可证确认。获得书面授权或完成全量可审计原创替换之前，不得创建公开 Release 或发布 Jar。详见 [资源审计清单](../docs/ASSET_MANIFEST.md)。
