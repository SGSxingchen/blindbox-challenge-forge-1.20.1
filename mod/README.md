# 盲盒挑战生存 Forge 模组

目标版本为 **1.0.0**，运行环境为 Java 17、Minecraft 1.20.1、Forge 47.4.22。客户端和专用服务器均只安装发布页提供的 `blindboxchallenge-1.0.0-all.jar`；下载后使用同页 SHA-256 文件执行 `sha256sum -c blindboxchallenge-1.0.0-all.jar.sha256`。

`src/ciTest` 与 `*-citest.jar` 是 GitHub Hosted Runner 专用探针和受控音频夹具，绝不能安装、分发或混入正式包。本机不运行 Gradle、Forge 或 Minecraft 动态验证；构建、专服和真实客户端验证只在 Hosted Runner 执行。

八音盒只接受 HTTPS URL，并仅对触发当时在线的玩家一次播放；缓存压力只证明受控 URL 和单进程缓存语义。全部 182 项正式资源均为当前项目的原创重绘或定义产物，原版图片不进入 Release；项目方提供素材与需求背景并许可本项目使用、修改与发行，不外推对项目外第三方的授权。完整安装说明、验收和真实边界见仓库根目录 [README](../README.md) 与 [P5 验收矩阵](../docs/P5_ACCEPTANCE.md)。
