# 安装与升级

[English](../INSTALLATION.md) | **简体中文**

## 安装前

安装 [Fiji](https://imagej.net/software/fiji/)，并按照
[Fiji Chat 上游说明](https://github.com/fiji/fiji-llm#fiji-chat-quick-start)
添加更新站点。目前站点名为 Fiji-chat，地址为
https://sites.imagej.net/Fiji-chat/ ，添加并更新后需重启 Fiji。

在安装扩展前，确认 **Help > Assistants > Fiji Chat...** 已存在。
本扩展依赖 Fiji Chat，不是独立聊天程序，也不包含 Fiji 或 LangChain4j。

本版本在 Windows 11、Fiji/ImageJ 2.18.0 / ImageJ 1.54p、
Fiji Chat 0.2.0-SNAPSHOT、Java 21.0.7、LangChain4j 1.21.0 上构建并验证。
其他组合未验证；同名 SNAPSHOT 文件也可能包含不同代码，详见
[验证记录](VERIFICATION.md)。

## 安装发行版 JAR

1. 保存图像、脚本和分析结果，然后正常关闭 Fiji。
2. 从项目的 [Releases 页面](https://github.com/liuhaoqiu/fiji-llm-provider/releases)
   下载 fiji-llm-provider-0.5.8.jar；可同时下载 SHA256SUMS.txt。
3. 找到 Fiji 安装目录，其中应包含启动程序和 jars 文件夹。
4. 将下载的 JAR 复制到 jars 文件夹。
5. 只保留一个启用的扩展版本。将旧版 fiji-llm-provider-*.jar 和
   fiji-deepseek-provider-*.jar 移出 jars，或在文件名末尾添加 .disabled。
6. 保留原有的 fiji-llm-*.jar 与 LangChain4j JAR。
7. 启动 Fiji，在 **Help > Assistants** 下检查
   **Refresh LLM Models...** 和 **LLM Execution Settings...**。
8. 打开 **LLM Execution Settings...**，应显示
   **Fiji LLM Provider execution control: ACTIVE**。
   无历史设置的新安装默认每条消息 1000 轮。

Windows 用户可用 PowerShell 计算校验值：

    Get-FileHash -Algorithm SHA256 -LiteralPath ./fiji-llm-provider-0.5.8.jar

与同版本 SHA256SUMS.txt 中相应文件的值比较。源码 ZIP 用于查看或构建项目，
不要将它复制进 jars。

## 配置服务商

参照[首次设置](USAGE.md#首次设置)。每个服务商需要各自的 API 密钥；
本项目和发行文件不包含任何用户凭据。

## 从旧版本升级

替换扩展 JAR 前必须关闭 Fiji。API 密钥和模型缓存仍存储在 Fiji 自己的位置。
DeepSeek 保留原有服务名和类名，以兼容早期版本配置。

默认值仍为每条用户消息 1000 轮，但已明确保存的值仍优先。若升级后菜单显示 300，
请改为 1000 并保存，然后重新打开 Fiji Chat。运行中的任务不会中途改变上限。

## 更新 Fiji Chat 本体

本扩展和设置独立于本体 JAR，正常替换本体不会覆盖轮数偏好。
但上游接口变化可能破坏兼容性，或要求修改助手构建逻辑。
更新后检查 ACTIVE 状态，并针对新安装运行源码验证。

## 卸载或回退

保存工作，正常关闭 Fiji，将扩展 JAR 移出 jars 后重启。
回退时放回一个此前可用的版本，不要同时启用两个版本。
卸载扩展会恢复 Fiji Chat 原有服务商和执行行为，不会删除密钥库、
聊天记录或已保存的扩展设置。
