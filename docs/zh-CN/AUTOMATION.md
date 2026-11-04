# 自动化兼容（0.5.9）

[English](../AUTOMATION.md) | **简体中文**

这些扩展工具适用于本插件提供的四家服务商。原有 Fiji Chat 工具继续保留；插件向助手补充简短使用指引，但模型是否正确使用仍受其能力影响。

## 宏完成后的线程池等待

普通 Java 线程池可能在宏的线程组内创建工作线程，Cellpose 结束后仍长期等待新任务。插件依据真实标准队列等待栈与 `ThreadPoolExecutor.getTask/runWorker` 位置识别空闲线程，并再次检查后结束宏状态。不会关闭线程池或停止线程。仍在执行、排队、计划延迟任务以及未知子线程继续等待；模型请求或真正未完成的计算仍可能耗时。

## 对话框和数值参数

`fiji_ui_windows_identify` 列出稳定 `window_id`、所属窗口、图像 ID、按钮及数值 `control_id`。`fiji_ui_dialog_respond_by_id` 按 ID 定位一个可见对话框，支持同名窗口；过期 ID 会明确报错。返回动作已排队不代表执行完成，需要回读窗口或工具状态。

`fiji_ui_number_set_by_id` 更新数值模型、提交编辑器并返回真实数值。确认对话框前也会先提交数值编辑。超范围值及整数控件的小数输入会被拒绝。覆盖支持的 Swing 数值控件，第三方自定义控件可能需要其他操作方式。

`fiji_command_parameters` 查询真实 SciJava 输入名、类型和约束，不运行命令。当前安装的 Cellpose-SAM 例如使用 `cell_diameter`、`min_size`、`image_title`。静态元数据不能保证所有第三方宏适配器均支持全部参数；应核对提交值和实际结果。

## 图像关闭

`fiji_image_close_by_id` 按图像 ID 关闭指定 ImageJ 图像，不依赖当前活动图像。重复请求复用进行中的关闭操作，包括保存提示尚未处理时。`fiji_image_close_status_by_id` 区分等待、被对话框阻塞、已关闭、取消/未关闭及错误；窗口实际消失后才报告关闭。

`discard_changes` 默认 false。只有任务创建、允许丢弃的临时图像才应设为 true，保留原图和最终结果。不会全局自动关闭提示，也不会替模型判断未知图像能否丢弃。

不替换 Fiji Chat 或 Script Editor JAR，不新增模型请求，也不改变科学分割方法。
