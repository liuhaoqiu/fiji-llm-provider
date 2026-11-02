# 实现说明

[English](../DEVELOPMENT.md) | **简体中文**

本项目通过 SciJava 扩展 Fiji Chat 的服务商与助手执行服务。
0.5.1 使用一个 JAR，包含四个服务商及可配置的助手执行服务：

| 服务商 | API 地址 | 聊天协议 | 模型发现 |
| --- | --- | --- | --- |
| DeepSeek | https://api.deepseek.com | Responses | GET /models |
| SiliconFlow | https://api.siliconflow.cn/v1 | Chat Completions | GET /models?sub_type=chat |
| Volcengine Ark | https://ark.cn-beijing.volces.com/api/v3 | Chat Completions | GET /models，另支持手动模型/接入点 |
| NVIDIA | https://integrate.api.nvidia.com/v1 | Chat Completions | GET /models |

模型 ID 从服务端获取，不编译进插件。每家服务在 Fiji Chat 已有加密密钥库中
有独立条目。使用 Manage API Keys 配置密钥，再使用 Refresh LLM Models 刷新，
最后在 Fiji Chat 选择服务商和模型。密钥库主密码只用于解锁，不是 API 密钥。

从 Fiji DeepSeek Provider 升级时，DeepSeek 的服务名、类名、密钥条目及缓存路径
保持不变。安装 0.5.1 前停用旧版 fiji-deepseek-provider-*.jar 或
fiji-llm-provider-*.jar，避免重复注册。旧的 Refresh DeepSeek Models 菜单
由统一的 Refresh LLM Models 替代。

## 工具调用轮数

所测试 Fiji Chat 本体使用 LangChain4j 默认的 100 轮限制。
扩展通过公开 SciJava 服务机制以更高优先级注册 ProviderAssistantService，
为四个新增服务商设置 maxToolCallingRoundTrips，默认值为 **1000**。
一轮是一次包含工具请求的模型回复；同一回复中的多个工具仍只算一轮。
每条用户消息获得新的预算，该预算与 HTTP 超时独立。

通过 **Help > Assistants > LLM Execution Settings...** 设置正整数上限。
窗口显示扩展服务是否 ACTIVE。保存后重新打开 Fiji Chat；运行中请求和已构建助手
保留旧值，取消不保存。SciJava PrefService 使用稳定的 AssistantExecutionSettings
类及 maxToolCallingRoundTrips 键，存储命名空间不含版本号；非法值回退为 1000。

只有本扩展的 AbstractCatalogProvider 实例使用覆盖行为；其他服务商委托给已安装的
DefaultAssistantService。保留原工具和错误处理、图像隐私状态、请求参数、
图像工具结果规范化及传入的聊天记忆。不替换本体 JAR 类、不修改模型提示词、
不重放失败请求，也不添加失败轮次持久化或恢复界面。

本体更新本身不会重置独立保存的上限，但兼容性仍取决于 SciJava 服务入口和
Fiji/LangChain4j 接口。四个新增服务商使用与当前本体相应的构建逻辑，
因此上游构建逻辑变化仍需审查。ACTIVE 只证明服务被选中，不保证未来所有行为兼容。
升级后应对目标 Fiji 重新运行 build.ps1。

执行测试检查实际服务选择、聊天界面的服务注入和真实 100/1000 轮行为，
无须真实 API 密钥。如果另一服务替代本服务，设置窗口显示 NOT ACTIVE，
不会声称配置已经应用。接口不兼容时可能需要重新构建或适配。
移除此扩展会恢复本体服务及其上限。

## 超时策略

四家服务的同步和流式工厂统一采用 30 分钟响应等待、15 秒连接超时。
SiliconFlow 此前保留 Chat Completions 的默认 60 秒；Volcengine 已使用较长等待。
DeepSeek Responses 客户端此前没有显式配置这些超时，因此不是同样的 60 秒截断；
现通过 HTTP 层明确配置。Responses 模型构建器没有 timeout setter，
仅修改 Chat Completions 构建器无法覆盖 DeepSeek。

模型列表请求仍为 5 秒。30 分钟是客户端单次请求配置，不是服务可用性保证，
也不是整个工具工作流的时长上限；一个工作流可能包含多次请求。
服务端 504、连接错误、排队限制仍可能发生。DeepSeek 文档说明等待时可返回空行或
SSE 保活，并可能在十分钟内未开始推理时关闭请求。本修复未更改重试行为。

## NVIDIA

在 integrate.api.nvidia.com 后自动添加 /v1 前缀。通过 NVIDIA Build 的 Get API Key
获取密钥，保存到独立 NVIDIA 条目，再选择 NVIDIA 刷新模型。

2026-11-02 曾在不使用凭据的情况下获取公开 NVIDIA 目录，成功解析当时的 85 个 ID。
目录含嵌入等非聊天模型，且未声明能力；刷新界面因此提示选择支持 Chat Completions
和函数调用的模型。图像分析还需选择支持图像输入的模型。
插件不根据名称猜测能力，也不编译模型白名单。目录可见性不证明推理权限；
401/403 仍可见。验证未使用真实 NVIDIA 推理密钥。

## 动态目录与能力

打开选择器时请求刷新，60 秒内重复请求合并；Refresh LLM Models 强制刷新并说明
实时结果或缓存状态。刷新后重新打开模型设置。成功刷新替换整个列表，
已退役模型不会因与旧列表合并而重新出现。

可用元数据决定图像支持和记忆大小。缺少图像元数据表示 UNKNOWN，
不能证明支持或不支持。SiliconFlow 模型列表文档包含 ID，但不保证视觉或上下文元数据。
允许发送图像时，应自行选择合适视觉模型。

Chat Completions 使用 Fiji 已有图像规范化：图像工具结果变为用户图像内容，
工具回复保持文本；工具轮次间保留推理内容。采样和思考控制使用服务端默认值。
DeepSeek 继续使用 Responses 协议及已声明的 reasoning effort 元数据。
记忆 token 数是估算值，包含图像预留，并非服务商精确分词器；
未知上下文大小时使用 32768 token 记忆预算。

## 火山引擎 Ark

支持 Enter model / Endpoint ID...，可填写不在 /models 中的已开通模型或 ep-... 接入点。
最后一次手动 ID 保存在 SciJava 偏好中，重启后仍可选择。这是用户提供的配置，
不是插件编造的模型名。列表可见性不保证账户已开通或可以调用。

明确的非 LLM/VLM 域元数据和 Shutdown/Retired 状态会在提供时被过滤；
未知能力不根据名称猜测。解析 Ark 嵌套的 modalities.input_modalities 与 token_limits。
明确声明图像支持时予以识别，但 Ark 的仅文本声明暂视为 UNKNOWN：
2026-11-02 实际 API 中 DeepSeek V4.1 Flash 的目录信息与宣称多模态支持的官方模型页
冲突。插件不通过硬编码模型名解决这一上游差异。

## 缓存与解锁

Windows 的成功响应缓存在 %LOCALAPPDATA%/FijiChat/<service>-models-v1.json，
service 为小写 deepseek、siliconflow、volcengine 或 nvidia。
只保存模型元数据，不保存 API 密钥；密钥由 Fiji Chat 原有密钥库提供。

密钥库锁定时，刷新命令打开原生密钥管理器。首次无目录时不提供编译内置模型兜底，
而显示 Unlock / refresh SERVICE models... 设置操作，清除 Fiji Chat 自动运行标记，
引导用户完成密钥管理、强制刷新和模型选择。任何步骤取消都会退出，
不重新打开命令；设置标签不会作为模型 ID 发送。

密钥管理器是 SciJava DynamicCommand。通过 ModuleService 启动并等待实际 Future，
包括对话框和预处理。CommandService.run 对 DynamicCommand 会启动命令但返回 null，
所以不能通过它等待鉴权；取消则不刷新。

网络、HTTP 或解析失败时保留最后成功的目录，明确显示缓存过时状态；
有效空响应会清空列表。请求 5 秒超时，模型设置可能在刷新时短暂等待。
新模型名称及现有文档定义的元数据无须重新构建即可支持；
未来不兼容的 API 或结构变化仍可能要求更新。

## 构建与验证设计

本扩展不打包 Fiji 或 LangChain4j，针对已安装的 fiji-llm-0.2.0-SNAPSHOT.jar 接口编译。
上游升级后应重新构建并验证。

SciJava 插件索引显式纳入源码，因为此 Windows Fiji 自带的注解处理器在 JDK 21 下
无法重写索引输出。构建验证覆盖服务商、命令和服务发现；添加插件时需同步注解和索引。
API 密钥只由现有加密密钥库管理，不应写入源码、构建命令或日志。

build.ps1 使用本地 Fiji 依赖完成编译、打包和九组本地验证。
密钥管理测试使用真实 CommandService、ModuleService 和隔离的无图形上下文，
复现 null Future 并验证等待、刷新顺序及取消。启动测试直接执行已安装
Fiji_Chat 的 initialize/run 方法，使用隔离设置和服务。

测试夹具使用本地 HTTP 服务器和合成密钥/模型 ID，不访问付费 API 或用户密钥库。
覆盖三种 Chat Completions 路由、独立鉴权头、同步/SSE、推理和工具轮次、
图像负载原样传递、错误、缓存来源隔离、四服务解锁顺序及手动接入点持久化。

超时测试实际延迟响应头 65 秒：原版 Chat Completions 流式客户端在 60 秒失败，
未配置超时的原版 Responses 客户端完成；更新后的八个模型工厂均完成。
另外四个 SSE 场景先发送 65 秒保活注释，再完成响应。
两种协议均保留 HTTP 504 错误；还检查有效超时值、没有静默重放及 Ark 嵌套元数据。

自动验证不使用真实账户密钥。夹具覆盖已观察到的数据结构和传输行为；
真实账户权限、模型能力及任务质量需要另外的在线验证。

## 官方参考资料

以下资料在 2026-11-02 核对；本说明保留当时依据，未来接口可能变化。

- https://api-docs.siliconflow.cn/docs/api/models-get
- https://docs.siliconflow.cn/docs/api/chat-completions-post
- https://docs.volcengine.com/docs/ark/chat-api?lang=zh
- https://docs.volcengine.com/docs/ark/model-list
- https://docs.volcengine.com/docs/ark/sdk-common-examples?lang=zh
- https://api-docs.deepseek.com/quick_start/rate_limit/
- https://docs.siliconflow.cn/docs/userguide/faqs/error-code
- https://docs.api.nvidia.com/nim/docs/api-quickstart
- https://docs.api.nvidia.com/nim/reference/llm-apis
- https://docs.api.nvidia.com/nim/reference/stepfun-ai-step-5-preview-infer
- https://docs.nvidia.com/nim-operator/latest/guardrail.html
