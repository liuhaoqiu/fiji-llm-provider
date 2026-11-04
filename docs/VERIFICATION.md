# Release verification: 0.5.8

**English** | [简体中文](zh-CN/VERIFICATION.md)

Sixteen local suites passed using the release build script. The new presentation
suite runs the actual LangChain4j AI-service loop with deterministic streaming
responses, real tool execution, and Fiji's installed ChatMessagePanel.
It checks that a milestone is visible before the next model/tool round; public
intermediate messages and reporting-tool updates remain in the body; raw thinking
and tool payloads stay folded; repeated running polls are deduplicated; and the
final report is separately headed and delivered once. Display text and activity
are round-tripped through Fiji's serialized conversation-message schema and
shown in a reopened panel. Final model memory stays unchanged.

Other checks cover assistant-local reporting and validation, one presentation
instruction per request, Fiji cancellation handles, context-compaction display,
unfinished public drafts on transport failure, per-chat task retention, all four
providers, model catalogs, multimodal transport, real 1000/1001 round boundaries
and delayed/idle streams. The small-budget fixture reserves room for the new
presentation instruction before exercising context growth over actual tools.

Additional real editor/module GUI checks passed, including the inherited stack
selector, consecutive macros, a 31-second wait, duplicates and input/error dialogs.
The macro execution classes are byte-identical to the verified 0.5.7 JAR. Actual
Cellpose inference was not repeated for this presentation change; the preceding
large-RGB Cellpose result is recorded in the [0.5.7 report](releases/v0.5.7-verification.md).
No hosted inference, API key or private image was used in this release's tests.

JAR SHA-256: `83b55decbf150bd942459fa11a25726d043468f96397d3b215238f33a31a0681`

Unmodified Fiji Chat SHA-256: `4d9925076bb17fb02cb10830b6385d44101edc90ab28b9d967c21a02e03a8eca`

Unmodified Script Editor SHA-256: `08f73d26b7cdda0e7f30cc8754ace7c297e6bcbe0ebe365d504315507e850351`

Environment: Windows 11; Fiji/ImageJ 2.18.0 / 1.54p; Fiji Chat 0.2.0-SNAPSHOT;
Script Editor 1.4.3; Java 21.0.7; LangChain4j 1.21.0; compilation target Java 17.
The display separates content; it does not validate or rewrite model conclusions.
Live hosted-model adoption of the new reporting tool was not tested. See
[chat presentation](CHAT-DISPLAY.md) for behavior and usage.
