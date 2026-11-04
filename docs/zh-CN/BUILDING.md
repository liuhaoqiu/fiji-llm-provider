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

二十四组验证全部通过后生成：

    build/fiji-llm-provider-0.5.17.jar

中间类文件和 Java 参数文件放在新的 build/run-.../ 目录中，避免旧类混入新 JAR。
编译和测试类路径排除所有已安装的本扩展版本。参数文件用于避开 Windows 命令长度限制。

版本号来自 MANIFEST.MF，发行版打包时间固定；.gitattributes 保留资源和许可文件的字节。
JAR 内包含 LICENSE.txt、NOTICE.txt 及上游许可证。更换 JDK 或依赖组合可能生成不同字节。

## 二十四组验证

| 验证程序 | 覆盖范围 |
| --- | --- |
| MultimodalActivityVerificationMain | 真实图像/多内容 AI-service 回调、内容保留及最终输出 |
| DisplayIsolationVerificationMain | 展示故障注入与交付隔离 |
| StatusVerificationMain | 忙碌/启动原因、核验范围及真实 Groovy 编辑器生命周期 |
| EngineeringVerificationMain | 嵌套执行错误、稳定编辑目标及有界命令等待 |
| DeliveryVerificationMain | 绑定创建/替换工具、宏图像 ID 及完整交付包往返核验 |
| WorkflowVerificationMain | 真实命令及宏桥接、明确参数、标签与原图测量绑定、CSV/QC 不破坏及脚本版本核验 |
| ExecutorWorkerVerificationMain | 普通线程池空闲线程、实际执行/排队/计划任务及未知子线程 |
| UiAutomationVerificationMain | 数值编辑器与模型提交、范围校验及已安装 Cellpose-SAM 的真实参数元数据 |
| StagePresentationVerificationMain | 实时阶段正文、真实汇报工具轮次、最终分区、折叠细节、历史重载及取消 |
| SharedWorkerVerificationMain | 继承线程组的空闲公共池线程、真实计算、无关任务及私有池 |
| MacroExecutionVerificationMain | 宏子线程等待、异常及控制台维护隔离 |
| TaskRetentionVerificationMain | 原始任务、图像淘汰及独立会话 |
| StreamStatusVerificationMain | 等待状态、SSE 空闲保护及中途恢复 |
| ProgressDisplayVerificationMain | 上下文整理后的进度显示 |
| ContextBudgetVerificationMain | token/请求体上限、整理及恢复 |
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


ContextBudgetVerificationMain 覆盖上下文控制和聊天面板恢复，详见[上下文说明](CONTEXT.md)。


可选 GUI/模块及实际 Cellpose 验证见[宏执行说明](MACRO-EXECUTION.md)。在依赖齐全的 Windows 桌面上，可给构建命令加 `-VerifyMacroIntegration` 或 `-VerifyCellpose`。

可用 `-VerifyCellpose -CellposeFixture "C:\path\to\RGB-image.png"` 对本地 RGB 测试图补验非空 ROI 导出；测试会将副本调整为 3000×3000。图像不打包、不上传。省略图像参数时使用合成大图，可能得到零检出。

`-VerifyMacroIntegration` 也在隔离 JVM 中验证同名对话框、按控件 ID 提交数值、保存提示去重及原图保留。参数元数据测试需要已安装 Cellpose Appose 插件，但不执行推理。
