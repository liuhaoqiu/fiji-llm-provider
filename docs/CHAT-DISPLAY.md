# Chat presentation (0.5.8)

**English** | [简体中文](zh-CN/CHAT-DISPLAY.md)

The presentation is enabled automatically for DeepSeek, SiliconFlow, Volcengine
and NVIDIA assistants created by this extension. Restart Fiji after upgrading,
then reopen Fiji Chat and start a new request. No additional menu setting is
required. Existing completed conversations are kept as they were saved.

## Progress in the chat body

The **阶段进展 / Progress** section remains visible without opening the log.
The assistant can call `fiji_report_progress` with a brief user-facing milestone
and next step, in the user's language. The extension also shows script edits
and execution-state changes from supported tool results. Repeated polls returning
the same state do not produce repeated updates. Frequent edit notices are limited
to one every 30 seconds. Model-authored public text accompanying tool calls is
shown when that tool-calling round closes.

This reporting tool belongs to one assistant and does not publish to another
chat. It does not run another model or modify an image. It participates in the
normal tool-round accounting. The model decides when to give a descriptive
milestone; the extension's factual execution-state notices are automatic.

## Collapsible activity log

The existing **Thoughts and … tool uses** area retains thinking, tools, arguments
and raw results. Streamed body text is buffered until its round is classified
as intermediate or final. A control-only body callback preserves Fiji's stop
handling while adding no visible text; drafts also remain in the local archive.
Context compaction emits a short continuation notice in the body; its checkpoint
excerpts, JSON and archive diagnostics remain in the activity log/local record.

## Final report

After the last model round finishes, the final answer appears once under
**最终报告 / Final report**, following the accumulated progress. A final answer
is buffered for that round, rather than interleaved with earlier milestones.
The extension does not rewrite its conclusions. If transport or context limits
pause the task, the section is instead labeled **任务暂停 / Task paused**, with
any unfinished public draft preserved and labeled as partial.

Fiji saves the displayed progress/final text and the activity record using its
existing conversation format. Reopening a saved conversation retains both.
The model's final memory message remains the original final answer; UI headings
and automatic notices are not appended to that message. Local event archives
also retain presentation events. The extension does not call a separate hosted model to summarize progress;
reporting follows the normal tool-call flow.

Only the extension JAR changes. Fiji Chat and Script Editor remain installed
unchanged. See [release verification](VERIFICATION.md).
