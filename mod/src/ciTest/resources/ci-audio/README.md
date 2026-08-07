# P4/P5 在线音频 CI 夹具

本目录只有 Hosted Runner 真实双客户端验收使用的原创音频夹具，**不属于正式模组资源**；`ciTest` 是独立测试 Jar，正式 Jar 的质量门禁不得包含本目录。

* `blindbox-ci-tone.ogg`：440 Hz、0.25 秒、单声道、自制信号。
* `blindbox-ci-tone.mp3`：660 Hz、0.25 秒、单声道、自制信号。
* `blindbox-ci-broken.ogg`：上述 OGG 的 64 字节截断副本，仅用于验证客户端解码失败路径。
* `blindbox-ci-cache-pressure.ogg`：只随独立 `ciTest` Jar 提交的原创压力 OGG。它保留本项目自制短音频帧，并使用确定性 ASCII Vorbis 注释负载构成合法 OGG 传输体；文件**严格大于 13 MiB 且不大于 16 MiB**，不会进入正式资源或正式 Jar。五个仅 query 不同的 HTTPS URL 会形成五份缓存条目，可靠越过 64 MiB LRU 上限，同时每次下载仍符合生产 16 MiB 限制。

没有使用、上传或转绘 `source-package/` 的任何原始音频、照片、压缩包或第三方成品音频。可用 `mod/scripts/ci/generate-ci-audio.sh` 以 FFmpeg 的 `lavfi sine` 源重新生成 P4 三夹具；`--verify-pressure` 只复核已提交 P5 夹具的魔数和严格大小。脚本不访问网络，也不参与正式构建。P5 文件虽需由 GitHub raw HTTPS 提供给真实客户端下载，但仅位于 `src/ciTest`、仅进入独立 ciTest Jar，不进入 `source-package/`、`archive/`、正式资源、正式 Jar 或资产清单。

当前 SHA-256：

```text
70b67cb71ca71c6e3232975c5a63c73e8dd77f07a9af8f0cc9fdd2b729488a30  blindbox-ci-broken.ogg
3977361d03da7942020491789f244f0b860ed82a3bbcf6cc5d1f30bcc5debe7b  blindbox-ci-tone.mp3
0457acf70b465bb005fdecb3e961c9fa29003e73c11b6264bdc51cdf93747db4  blindbox-ci-tone.ogg
c4e3576180201d041d37d9337b29ef8709664e78ee2f1116a261148589e7b3bd  blindbox-ci-cache-pressure.ogg
```
