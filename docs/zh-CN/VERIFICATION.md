# 0.5.10 发行验证

[English](../VERIFICATION.md) | **简体中文**

**十九组本地测试和三项隔离 GUI 集成通过**。新增验证执行真实 SciJava 动态命令及 ImageJ 宏桥接，在初始化后应用明确类型参数，在命令执行前拒绝无效输入；核对实际安装的 Cellpose v3 初始化后的直径/cu126/GPU 输入，但不运行推理。生成的宏框架已针对合成标签命令端到端回放，并保留原图像素。

图像验证覆盖精确提取红通道、非零唯一标签及编号空缺、从原图测强度而不是读标签值、边缘对象、原图像素不变、非破坏叠加及独立 CSV。真实显示表格/QC 时已有全局 Results 和 ROI Manager 保持不变。真实宏编辑器验证记录精确代码哈希、编辑后失效和可直接响应的对话框 ID；最终报告独立显示未验证脚本状态。

原有十八组验证继续通过，包括展示/历史/取消、宏线程收尾、任务保留、上下文预算、四家服务商、真实 1000/1001 轮边界及延迟/空闲流。未使用托管推理、API 密钥或私人图像。此次未重跑实际 Cellpose 推理，不声明科学分割准确性；明确 GPU 参数不等于已确认实际设备。仍需真实任务测试模型能否有效采用工具。

JAR SHA-256：`0e2e45617c5cdb85865f28680a73dd2ea696a5ce1815dd3975df70cfe6269a28`

未修改 Fiji Chat SHA-256：`4d9925076bb17fb02cb10830b6385d44101edc90ab28b9d967c21a02e03a8eca`

未修改 Script Editor SHA-256：`08f73d26b7cdda0e7f30cc8754ace7c297e6bcbe0ebe365d504315507e850351`

环境：Windows 11，Fiji/ImageJ 2.18.0 / 1.54p，Fiji Chat 0.2.0-SNAPSHOT，Script Editor 1.4.3，Cellpose Appose 0.1.24，Java 21.0.7，LangChain4j 1.21.0；编译目标 Java 17。详见[工作流行为与限制](WORKFLOW.md)。
