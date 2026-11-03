# Context budgets and progress recovery

**English** | [简体中文](zh-CN/CONTEXT.md)

Context controls are available in **Help > Assistants > LLM Execution Settings...**.
The default tool-round limit remains 1000. Reopen Fiji Chat after saving settings.

## Choose a context window

- **Context window tokens = 0:** use the model catalog metadata; if unavailable,
  use a conservative 32,768-token window. A manually selected window is clamped
  to the known model window. This control cannot enlarge a provider's model.
- Enter **524288** for a 512K window, or **1048576** for a 1M window.
- **Reserved output tokens:** defaults to 65,536, clamped to the model output
  maximum and at most a quarter of the selected window. The input budget is
  the total window minus this reserve. The request's output maximum is capped
  accordingly; a smaller explicit output maximum is preserved.
- **Maximum request body MiB = 0:** use 48 MiB for the official DeepSeek API,
  and a configurable local fallback of 16 MiB for the other three providers.
  The fallback is not a claim about their server limits. Enter a positive value
  to override the local cap. One MiB is 1,048,576 bytes.

The official DeepSeek model catalog currently reports a 1,048,576-token context.
Its [vision documentation](https://api-docs.deepseek.com/guides/vision/) separately
specifies a 48 MiB request-body limit, including inline Base64 images. These are
different limits. A 1M-token context does not permit an arbitrarily large HTTP body.

## Monitor a running task

Open **Help > Assistants > LLM Context Status...**. The window updates every second
and shows the most recently active assistant's estimated input tokens, effective
input budget, measured serialized JSON request bytes, and the actual input token
count reported by the last completed API response when available.

Token estimates use the installed gpt-4o tokenizer as an approximation. DeepSeek
image allowance follows its current documented 1024-token upper bound per image;
other providers retain a conservative image allowance. Opaque reasoning data is
estimated conservatively. Exact future input tokens are not available before the
provider processes the request. API usage is a measurement of the last completed
request, while UTF-8 request-body bytes are measured locally before transmission.

## Automatic context organization

At approximately 80% of the input budget or 90% of the byte cap, the extension
archives the original messages, then creates a smaller working context. It keeps
system instructions, the current task and recent complete tool-call batches.
Earlier activity becomes bounded **extractive excerpts**, with references to the
exact archived messages. A large text tool result can also become an explicitly
marked excerpt; recent images and the latest opaque reasoning are not silently
rewritten. Tool-call IDs and their results stay together.

A phase checkpoint is saved before the working memory is replaced. Detailed checkpoint notices stay in the continuously updating activity log. The
answer pane receives only concise stage outputs with a recent tool-result excerpt,
so a long checkpoint does not displace the live log. Notices are rate limited.
Excerpts are not an independent verification of task success. The assistant must
inspect the current scripts, images and exact results before claiming completion.

If a request still exceeds a context/body limit, the chat receives a visible
**Task paused — not completed** checkpoint, including the task, recent activity
and archive location. The original task and completed tool records are preserved
instead of triggering Fiji Chat's failed-turn rollback. Reply **continue** in the
same conversation; the next request uses a tighter budget. Completed tools are not
automatically replayed by this mechanism. A single task/image/tool batch that
cannot safely fit still pauses with its checkpoint. Recognized transport interruptions are handled as described below; other errors retain normal error handling.

## Full local record

Records are stored under `%LOCALAPPDATA%/FijiChat/context-archives/<session-id>/`
(or `~/.cache/FijiChat/context-archives/` if LOCALAPPDATA is unavailable):

- `message-*.json`: full messages, including image data and reasoning attributes.
- `index.txt`: message-addition order.
- `stream-events.jsonl`: received text/thinking callbacks, including partial output
  from a stream that failed before a complete response arrived.
- `checkpoint-*.md`: successive phase records; `checkpoint.md` is the latest.

The archive is separate from the smaller model memory. It can contain private
images and scripts; it is not uploaded as a release asset and is not automatically
deleted when chat memory is cleared. It can consume substantial disk space.
Archive-write failure stops processing rather than claiming that a full record
was preserved. No API headers or key-vault credentials are deliberately recorded.
This is same-conversation context recovery, not automatic restoration of Fiji,
macro execution or external processes after an application restart.

## Validation scope

Local checks exercise exact UTF-8 boundaries, all four HTTP builder factories,
sync/SSE paths, growing real assistant loops, complete archived images, automatic
organization of large tool results, the original Fiji chat panel and its saved
activity record, and same-chat continuation without replaying completed tools.
No live API key is used. A real long-running microscopy task remains a manual
integration check before treating this as verified for a specific workflow.

## Waiting for output and interrupted streams (0.5.5)

After 60 seconds without new model output or tool events, the plugin adds a
clearly labeled status in the activity log, at most once per minute. It reports
waiting for API output or waiting for a tool result, the idle time and last
finished tool. A connection or spinner is not evidence of task progress. The
first notice also adds one short line in the answer area. These messages are
plugin observations, not model reasoning or a scientific completion report.

**Help > Assistants > LLM Context Status...** also shows the current activity
state and seconds since the last observed output/tool event. Tool-argument chunks
count as model activity, even before a full tool call can be displayed.

The existing 30-minute timeout now also bounds an idle SSE response body after
HTTP headers arrive. Data events reset that timeout; keepalive comments do not.
Long synchronous tool callbacks do not trigger the network idle timeout. This is
an inactivity bound for each request, not a 30-minute cap on the entire task.

On recognized transport interruptions or an empty final reply, the plugin saves
and displays a **Task paused — not completed** checkpoint instead of treating
an empty answer as success or rolling back earlier progress. Continue in the same
conversation when ready. It does not automatically retry requests or replay tools.
An interrupted request may already have reached the provider. New stream archive
events include UTC timestamps. The plugin cannot make a stalled upstream service
produce an answer; live provider behavior still needs integration verification.

## Task retention and metadata (0.5.5)

A new chat has independent memory and its own archive directory. Opening a new
window does not remove tool schemas, environment context or image costs from a
request. A 1M-token model can still encounter a separate HTTP body-size budget.
The first image alone can be many MiB after PNG/Base64 serialization.

When the SiliconFlow API omits context lengths, the extension supplements them
with the public structured data from [SiliconFlow's official model page](https://www.siliconflow.cn/models).
Model IDs remain authoritative from the API; the website never adds selectable
models. Metadata is cached for up to 24 hours; **Help > Assistants > Refresh LLM Models...**
forces both refreshes. The public metadata request has a five-second timeout and
sends no API credentials or conversation data. On failure the last valid cache
is retained. If both sources lack a window, the conservative 32,768-token fallback
remains and is identified in **LLM Context Status...**, alongside the total model
window and output reserve. Manual limits still work and known model limits apply.

The initial user request and up to three recent follow-up instructions are pinned
outside the evicting model memory. Every request restores missing pins, even when
it is below the compaction threshold. The latest user instruction takes precedence
when the task changes. Generated summaries cannot replace the human task. Pins
are per assistant/chat, and are not shared across chats. If the pinned content
itself cannot fit, processing pauses rather than silently removing the task.

Tool images moved into a user-role message for Chat Completions are explicitly
labeled as tool evidence for the existing task. They do not represent a new or
empty user request. Compaction identifies token pressure versus body-byte pressure;
the body soft trigger is now 90%, leaving headroom for actual transport encoding.
These checks preserve task content but cannot guarantee that a hosted model will
always follow it. Live scientific workflow results still require verification.
