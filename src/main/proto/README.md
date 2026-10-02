# 这份 proto 是**vendored 副本**，不是真源

`moddata/v1/moddata.proto` 的唯一真源在 CCNR-Data 仓库：

```
../CCNR-Data/proto/moddata/v1/moddata.proto
```

**为什么复制一份而不是跨仓库引用**：跨仓库相对路径依赖会让「CCNR-Data 不在旁边」时的构建直接失败，
而模组仓库必须能单独 clone + 构建。代价是可能漂移，所以有 `scripts/sync-proto.sh`：

```bash
./scripts/sync-proto.sh          # 从 CCNR-Data 拉最新（真源存在时）
./scripts/sync-proto.sh --check  # 只比对，不一致就非零退出（CI / 提交前）
```

改了契约的正确顺序：**先在 CCNR-Data 改 proto 并过它的门禁 → 再跑 `sync-proto.sh` 同步到这里**。
反过来做（在模组仓库直接改）会让真源落后，而两边生成的桩看起来都能编译——这正是最难查的一类漂移。

## `../proto-include/`

`proto-include/google/protobuf/timestamp.proto` 是 Google 的 well-known type（BSD-3-Clause，文件头带原始许可）。

**为什么要手工放一份**：Maven 上的 `protoc` 可执行文件**不带** well-known types 的搜索路径兜底
（实测 protoc 3.21.12 / 3.25.5 / 4.29.3 都报 `google/protobuf/timestamp.proto: File not found`），
它只认 `-I`。`protoc` 插件本身不提供该目录，所以这里作为 **include-only** 目录传入
（见 `build.gradle` 的 `addIncludeDir`）——刻意**不**放进 `src/main/proto`：
那个目录下的文件会被当成生成输入，从而产出与 `protobuf-java.jar` 里同名的
`com.google.protobuf.Timestamp`，制造两个同名类。
