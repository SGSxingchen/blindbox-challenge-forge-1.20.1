# P1 动态验证实施计划（未执行）

本文件记录 2026-08-05 动态验证审查结果，防止未实现的测试被误报为已通过。

## 必要前置改造

当前正式模组只有 `MANUAL_REVIEW` 事务隔离，缺少库存前后快照、来源槽位收据和可观测恢复状态；因此无法诚实断言 SIGKILL 后资产守恒。必须先在主代码补齐事务收据、恢复状态和限流，再写恢复测试。

测试探针须是独立 Forge 测试模组：使用 `mod/src/ciTest/` 源集、单独 `blindboxchallenge_citest` Jar 和单独重混淆任务，禁止进入正式 `blindboxchallenge-*.jar`。

质量构建必须编译 `ciTestJar`，并以 `jar tf` 断言正式 Jar 不含 `cn/blindboxchallenge/citest/`。该断言只证明发布纯度，不能替代任何动态场景结果。

## CI DAG

```text
quality-build（唯一正式构建/Jar）
  ├─ dedicated-server
  ├─ single-client（Xvfb 真实 Forge 客户端）
  ├─ multi-client（两真实客户端 + 同一专服）
  └─ lifecycle-recovery（flush 后 SIGKILL 重启）
       └─ regression-report（always，任何 failed/skipped/missing 均失败）
```

各下游 job 下载同一正式 Jar，不能各自重新构建。CI 测试模组需由真实客户端 GUI/网络和服务端状态机生成 canonical JSON；禁止预写 marker。统一上传正式 Jar、测试 Jar、服务端/客户端日志、crash-reports 和 JSON。

## 动态矩阵

* 单客户端：Xvfb 启动 Forge 客户端、到主菜单、连接测试服、打开打包菜单并验证资源/Screen 无崩溃。
* 双客户端：并发打包、并发开盒、竞争最后 bundle；比较两名 UUID 的库存、奖池和事务 UUID。
* 行为/网络：空池、带名称/附魔/NBT 的多栈 bundle、满包、取消生命周期、过期会话/伪造指纹/重复包/无权限命令。
* 恢复：唯一 NBT 资产，`save-all flush` 后 SIGKILL，同世界同 UUID 重连并验证守恒；未 flush 窗口单列未覆盖，不可假称通过。
