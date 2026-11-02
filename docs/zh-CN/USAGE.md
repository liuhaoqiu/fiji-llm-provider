# 使用指南

[English](../USAGE.md) | **简体中文**

## 首次设置

1. 从计划使用的服务商获取 API 密钥。四家服务的密钥不能相互替代。
2. 打开 **Help > Assistants > Manage API Keys...**。
3. 选择 DeepSeek、SiliconFlow、Volcengine 或 NVIDIA，
   使用 Fiji Chat 已有的密钥管理器保存对应密钥。
4. 打开 **Help > Assistants > Refresh LLM Models...**，选择该服务并刷新模型。
   若密钥库已锁定，完成正常的解锁对话框；取消则结束，不自动再次刷新。
5. 打开 **Help > Assistants > Fiji Chat...** 并选择服务商与模型。
   已有聊天窗口可使用齿轮按钮打开模型设置。

密钥库主密码用于解锁本地凭据存储，**不是服务商 API 密钥**，不要填入 API 密钥栏。

| 服务商 | 密钥或账户入口 |
| --- | --- |
| DeepSeek | [DeepSeek 平台](https://platform.deepseek.com/) |
| SiliconFlow | [硅基流动控制台](https://cloud.siliconflow.cn/) |
| Volcengine Ark | [火山方舟控制台](https://console.volcengine.com/ark/) |
| NVIDIA | [NVIDIA Build](https://build.nvidia.com/)，打开模型后使用 Get API Key |

## 刷新与选择模型

模型 ID 从服务端 API 获取，不是编译时写死的列表。打开模型选择器会尝试刷新，
60 秒内的重复请求会合并。**Refresh LLM Models...** 强制发起新请求，
并说明结果来自实时响应还是缓存。刷新后重新打开模型设置。

成功刷新会替换整个列表，已下线模型因此消失。临时失败时保留最后成功的缓存，
并标明可能过时；有效的空响应会清空列表。首次使用且没有缓存时，
**Unlock / refresh ... models...** 选项会引导解锁、刷新和选择模型。

请选择服务商明确支持 **tool/function calling** 的模型。查看图像时还需要支持
图像输入。模型出现在目录里不代表你的账户一定具有调用权限。

### 火山引擎 Ark

如果已启用模型或推理接入点不在返回列表中，选择 **Enter model / Endpoint ID...**，
填写控制台中的完整模型 ID 或 ep-... 接入点 ID。最后一次手动输入会保存在本地，
刷新后仍可选择。该操作不会自动开通模型或授予账户权限。

### NVIDIA

插件自动使用 https://integrate.api.nvidia.com/v1 。
模型目录可能包含嵌入模型和其他非聊天模型，也可能缺少工具调用或视觉能力声明。
请选择支持 Chat Completions 和函数调用的模型。能公开读取目录不等于有推理权限，
鉴权或权限错误仍由模型 API 返回。

## 处理图像或脚本

在 Fiji 中打开图像，然后描述希望得到的结果。例如：

> 检查当前图像，说明显示范围，并建议合适的亮度和对比度调整。

需要修改时，可以这样描述：

> 在当前图像的副本上操作。编写一个调整显示对比度的 ImageJ 宏，
> 测试它，并解释改动。

可通过 Fiji Chat 的上下文控件附加图像或脚本。允许图像输入时，
使用 **AI can view images** 开关，并选择兼容模型。
该开关表示允许分享图像数据，不是对模型视觉能力的认证。

Fiji 工具可能修改打开的图像和脚本。请检查结果并保存需要保留的版本。
一次工具执行成功本身不能证明图像分析结果在科学上合理。

## 长任务与轮数设置

打开 **Help > Assistants > LLM Execution Settings...**。
0.5.1 默认每条用户消息 **1000 轮**，也可保存其他正整数。

一轮指包含工具请求的一次模型回复；同一回复可以包含多个工具调用，
因此界面显示的工具使用次数不一定等于轮数。每条新用户消息都会重新获得轮数预算。

保存设置后，重新打开 Fiji Chat，使已有助手采用新值。已保存的偏好优先于未来的
默认值。该设置影响本扩展的四个服务商，其他服务商保留本体的行为。

达到上限会结束当前调用，本版本不添加检查点或续跑按钮。
必要时先保存有用脚本，再在新消息中基于当前状态继续任务。
提高上限只提供更多执行空间，不保证模型一定收敛或回复更快。

## 本地保存的内容

- API 密钥存储在 Fiji Chat 原有的加密密钥库中。
- 工具调用轮数和手动输入的 Ark 接入点存入 SciJava 偏好设置。
- 模型目录缓存保存元数据，不保存 API 密钥。Windows 下位于
  %LOCALAPPDATA%/FijiChat/，文件名例如 deepseek-models-v1.json 和 nvidia-models-v1.json。
- 聊天记录仍由 Fiji Chat 负责管理。

发行包不包含上述用户数据。连接、模型列表和执行错误的处理见
[故障排查](TROUBLESHOOTING.md)。
