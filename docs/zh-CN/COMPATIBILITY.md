# 0.5.12 的 Fiji Chat 兼容性检查

[English](../COMPATIBILITY.md) | **简体中文**

检查日期：2026-11-04。环境：Windows 11、Java 21.0.7、Fiji/ImageJ 2.18.0 / 1.54p、LangChain4j 1.21.0、Script Editor 1.4.3。两个 Fiji Chat 构建都标为 `0.2.0-SNAPSHOT`，须用构建提交和 JAR 哈希区分。

| Fiji Chat 构建 | 提交 | JAR SHA-256 |
| --- | --- | --- |
| 已安装基线 | `15e95c9a0de360c3fc108001d7ac006462c150fc` | `4d9925076bb17fb02cb10830b6385d44101edc90ab28b9d967c21a02e03a8eca` |
| 已下载更新，隔离验证 | `f73180448c64c83edeb995dd96156d1dd68f7896` | `7f18e1f885cd798898516c8dafcd00fa7c0762cd44187eb4ba1a620d021874b4` |

0.5.12 在这两个本体构建下分别通过 **21 组本地验证和 5 组隔离 GUI 集成检查**，完整源码也能针对更新包重新编译。检查的六组相关公共接口未改变；类来源检查确认测试实际加载了更新包本体和已安装的 0.5.12 助手服务。未修改原版 Fiji Chat 或 Script Editor JAR，本次检查也未将待更新本体应用到用户正在使用的安装环境。

[上游差异](https://github.com/fiji/fiji-llm/compare/15e95c9a0de360c3fc108001d7ac006462c150fc...f73180448c64c83edeb995dd96156d1dd68f7896)主要涉及 Gemini 模型文档解析和欢迎页。这是对上述确切构建的兼容检查，不保证未来所有 SNAPSHOT。测试使用合成图像、命令及本地 HTTP 服务，未使用在线推理、API 密钥或用户图像，也未评价生物学分割准确性。
