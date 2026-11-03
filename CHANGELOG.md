# Changelog

**English** | [简体中文](CHANGELOG.zh-CN.md)

## 0.5.5 — 2026-11-03

- Restore the user task after real TokenWindowChatMemory image eviction, including under-budget requests; retain the initial request and recent follow-ups per chat.
- Prevent synthetic summaries from replacing the task and label normalized tool-image messages as continuation evidence.
- Supplement missing SiliconFlow context lengths from the official public model page, without hardcoding model IDs; retain cached metadata on failure.
- Disclose the total model window, output reserve and metadata source. Distinguish token compaction from body-byte compaction and move the byte soft trigger to 90%.
- Add real image-eviction, independent-chat, fresh-image, history-serialization and large-page metadata regressions; thirteen local suites in total.

## 0.5.4 — 2026-11-03

- Distinguish waiting for API output from waiting for a tool result. After 60 seconds
  without new output/events, show a clearly labeled plugin status, elapsed idle time
  and last finished tool; this is not a claim of model or scientific progress.
- Apply the existing 30-minute timeout to idle SSE body reads after headers arrive.
  SSE keepalive comments do not count as model output. Active data events and long
  synchronous tool callbacks are allowed to continue.
- Preserve a paused assistant reply and local checkpoint on transport interruptions or empty final replies,
  keeping earlier activity and same-chat continuation without automatic tool replay.
- Timestamp new streamed archive events. Add local stalled-body, keepalive, active
  stream/tool and partial-progress regression checks; twelve local suites in total.

## 0.5.3 — 2026-11-03

- Route detailed compaction checkpoints to the live activity channel.
- Keep ordinary stage output concise and include a neutral recent tool-result excerpt.
- Preserve subsequent thinking/tool events and final-answer delivery after compaction.
- Reproduce 0.5.2 behavior and cover the fix in the original Fiji chat panel; eleven suites pass.

## 0.5.2 — 2026-11-03

- Configurable automatic/512K/1M context windows with a separate output reserve.
- Serialized UTF-8 body-size checks and a live context status window.
- Extractive context organization, full local message/progress archives and phase checkpoints.
- Recover context-size failures as visible paused tasks, retaining tool activity and allowing same-chat continuation.
- Ten local verification suites; real long-running API workflows still require manual integration verification.

## 0.5.1 — 2026-11-03

- Raise the default tool-calling budget from 300 to **1000 rounds per user message**
  for DeepSeek, SiliconFlow, Volcengine and NVIDIA.
- Keep explicitly saved custom limits; an upgrade does not overwrite them.
- Verify streaming completion after 1000 tool rounds and stopping before round 1001.
- Include English and Simplified Chinese documentation with language-switch links.
- License original contributions under PolyForm Noncommercial 1.0.0 and retain
  upstream BSD notices; include all notices in source and binary distributions.
- Make the Windows build accept the user's Fiji/JDK paths instead of a developer's
  fixed installation path. Production integration behavior otherwise matches 0.5.0.

## 0.5.0

- Add a higher-priority SciJava assistant execution service for the four providers.
- Add LLM Execution Settings with a default of 300 rounds and a saved custom limit.
- Preserve core tool handlers, image handling and conversation memory; delegate
  unrelated providers to the original Fiji Chat service.

## 0.4.0

- Add NVIDIA Chat Completions support and dynamic model discovery.
- Keep all four providers in one extension JAR.

## Earlier development

- Add DeepSeek, SiliconFlow and Volcengine support, dynamic catalogs and Ark
  manual endpoint selection.
- Correct key-manager future handling, empty-catalog startup behavior, multimodal
  token counting, model metadata parsing and client timeout configuration.
