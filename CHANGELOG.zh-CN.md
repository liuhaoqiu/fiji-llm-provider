# 更新记录

[English](CHANGELOG.md) | **简体中文**

## 0.5.1 — 2026-11-03

- DeepSeek、SiliconFlow、Volcengine 和 NVIDIA 的默认工具调用预算从 300
  提高到每条用户消息 **1000 轮**。
- 保留明确保存的自定义值，升级不覆盖它们。
- 验证流式助手完成 1000 轮，并在工具执行第 1001 轮之前停止。
- 提供带语言切换链接的英文和简体中文文档。
- 原创部分采用 PolyForm Noncommercial 1.0.0，保留上游 BSD 声明；
  源码包和二进制包均包含许可文件。
- Windows 构建脚本接受用户指定的 Fiji/JDK 路径，移除开发者固定路径。
  除默认值外，运行集成行为与 0.5.0 一致。

## 0.5.0

- 为四个服务商添加更高优先级的 SciJava 助手执行服务。
- 添加 LLM Execution Settings，默认 300 轮，并保存自定义上限。
- 保留本体工具处理、图像处理和聊天记忆；其他服务商委托给原版助手服务。

## 0.4.0

- 添加 NVIDIA Chat Completions 支持和动态模型发现。
- 四个服务商维持在同一个扩展 JAR 中。

## 更早开发版本

- 添加 DeepSeek、SiliconFlow 和 Volcengine，支持动态目录及 Ark 手动接入点。
- 修复密钥管理器 Future 处理、空目录启动循环、多模态 token 估算、
  模型元数据解析及客户端超时配置。
