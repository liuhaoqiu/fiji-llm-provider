# 构建与验证

[English](../BUILDING.md) | **简体中文**

## 已验证的构建方式

构建脚本已在 **Windows、PowerShell 7 和 JDK 21** 上验证。
它使用本地 Fiji 安装中的 JAR，要求已安装 Fiji Chat。
插件以 --release 17 编译，但运行兼容性仍取决于完整的 Fiji / LangChain4j 依赖组合。

    pwsh -NoProfile -File ./build.ps1 -FijiRoot "C:/path/to/Fiji"

显式指定 JDK：

    pwsh -NoProfile -File ./build.ps1 -FijiRoot "C:/path/to/Fiji" -JdkHome "C:/path/to/jdk-21"

不指定 JdkHome 时，依次查找 Fiji 自带 JDK、JAVA_HOME、PATH 中的 javac.exe。
此打包流程要求 JDK 21 或更新版本；本次发行实际验证使用 21.0.7。

脚本不下载依赖，不安装生成文件，不重启 Fiji，不修改 API 密钥或已保存轮数。
测试在独立 Java 进程中运行，使用合成输入和回环地址 HTTP 服务器。

## 输出

九组验证全部通过后生成：

    build/fiji-llm-provider-0.5.1.jar

中间类文件和 Java 参数文件放在新的 build/run-.../ 目录中，避免旧类混入新 JAR。
编译和测试类路径排除所有已安装的本扩展版本。参数文件用于避开 Windows 命令长度限制。

版本号来自 MANIFEST.MF，发行版打包时间固定；.gitattributes 保留资源和许可文件的字节。
JAR 内包含 LICENSE.txt、NOTICE.txt 及上游许可证。更换 JDK 或依赖组合可能生成不同字节。

## 九组验证

| 验证程序 | 覆盖范围 |
| --- | --- |
| VerificationMain | 服务商接口契约与 SciJava 发现 |
| KeyManagerVerificationMain | 真实命令/模块 Future、解锁顺序及取消 |
| StartupVerificationMain | 本体启动循环、设置和取消回归 |
| ModelCatalogVerificationMain | 动态 ID、刷新、删除、元数据、缓存及失败 |
| MultimodalVerificationMain | 图像和多内容消息的 token 估算与记忆 |
| TransportVerificationMain | Responses 请求、SSE、工具与鉴权错误 |
| MultiProviderVerificationMain | Chat Completions 服务、推理内容、图像及接入点保存 |
| AssistantExecutionVerificationMain | 实际服务选择、轮数限制与设置 |
| ProviderTimeoutVerificationMain | 延迟响应头、SSE 保活、超时配置及错误 |

超时测试会刻意等待至少 65 秒。助手测试使用真实 LangChain4j 循环及确定性的
本地模型和工具，验证完成 1000 轮，并在执行第 1001 轮工具前停止。
验证不发起付费推理请求。

## 更新依赖或添加代码

扩展不打包 Fiji Chat 或 LangChain4j，而依赖安装环境的接口；
没有自动替换成另一套 SNAPSHOT 的 Maven 构建。针对每个新目标环境重新构建并测试。

SciJava 插件索引位于：

    src/main/resources/META-INF/json/org.scijava.plugin.Plugin

编译采用 -proc:none；添加或重命名插件时，要同时更新索引、Java 注解及发现测试。
历史包名 sc.fiji.llm.deepseek 包含全部四个服务商，保留它是为了兼容设置和既有集成。
相关兼容修复的依据见[实现说明](DEVELOPMENT.md)。
