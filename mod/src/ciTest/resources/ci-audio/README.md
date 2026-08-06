# P4 在线音频 CI 夹具

本目录只有 Hosted Runner 真实双客户端验收使用的原创短促正弦波，**不属于正式模组资源**；`ciTest` 是独立测试 Jar，正式 Jar 的质量门禁不得包含本目录。

* `blindbox-ci-tone.ogg`：440 Hz、0.25 秒、单声道、自制信号。
* `blindbox-ci-tone.mp3`：660 Hz、0.25 秒、单声道、自制信号。
* `blindbox-ci-broken.ogg`：上述 OGG 的 64 字节截断副本，仅用于验证客户端解码失败路径。

没有使用、上传或转绘 `source-package/` 的任何原始音频、照片、压缩包或第三方成品音频。可用 `mod/scripts/ci/generate-ci-audio.sh` 以 FFmpeg 的 `lavfi sine` 源重新生成；该脚本不访问网络，也不参与正式构建。

当前 SHA-256：

```text
70b67cb71ca71c6e3232975c5a63c73e8dd77f07a9af8f0cc9fdd2b729488a30  blindbox-ci-broken.ogg
3977361d03da7942020491789f244f0b860ed82a3bbcf6cc5d1f30bcc5debe7b  blindbox-ci-tone.mp3
0457acf70b465bb005fdecb3e961c9fa29003e73c11b6264bdc51cdf93747db4  blindbox-ci-tone.ogg
```
