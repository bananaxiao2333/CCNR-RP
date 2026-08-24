# CCNR-RP（Forge 1.20.1）— 项目框架

CCNR 服务器 RolePlay 模组项目骨架（仅初始化，功能开发中）。

## 技术栈

- Forge 1.20.1（47.2.0+，Java 17 字节码 / JDK 21 构建）
- 国内镜像：Gradle 发行版走腾讯云，Maven Central 走阿里云

## 构建

```bash
export JAVA_HOME=/opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home
./gradlew build
```

产物：`build/libs/ccnr_rp-0.1.0-alpha.jar`
开发运行：`./gradlew runClient` / `./gradlew runServer`

## 目录规划（骨架阶段）

- `src/main/java/com/ccnrcom/rp/`——模组逻辑入口（当前仅 `CCNRRPMod`）
- `src/main/resources/assets/ccnr_rp/`——资源与语言包
- `docs/`——设计文档（待建）

## License

MIT
