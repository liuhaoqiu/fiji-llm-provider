# Implementation notes

**English** | [简体中文](zh-CN/DEVELOPMENT.md)

SciJava provider and assistant-execution extension for Fiji Chat.

Version 0.5.1 is one extension JAR containing four service providers and their
configurable assistant execution service:

| Service | Base URL | Chat protocol | Model discovery |
| --- | --- | --- | --- |
| DeepSeek | `https://api.deepseek.com` | Responses | `GET /models` |
| SiliconFlow | `https://api.siliconflow.cn/v1` | Chat Completions | `GET /models?sub_type=chat` |
| Volcengine Ark | `https://ark.cn-beijing.volces.com/api/v3` | Chat Completions | `GET /models`, plus manual Model / Endpoint ID |
| NVIDIA | `https://integrate.api.nvidia.com/v1` | Chat Completions | `GET /models` |

Model IDs are fetched from the service, never compiled into the extension.
Each service has its own key entry in Fiji Chat's existing encrypted vault.
Use `Help > Assistants > Manage API Keys...` to configure each service, then
`Help > Assistants > Refresh LLM Models...`, select the service and refresh.
Select DeepSeek, SiliconFlow, Volcengine or NVIDIA in Fiji Chat's service chooser.
The vault master password unlocks the vault; it is not a service API key.

Upgrading from Fiji DeepSeek Provider keeps the DeepSeek service name, provider
class, key-vault entry and cache path, so existing configuration remains valid.
Disable previous `fiji-deepseek-provider-*.jar` or `fiji-llm-provider-*.jar` versions before installing
`fiji-llm-provider-0.5.1.jar`; loading both creates duplicate providers.
The former `Refresh DeepSeek Models...` menu is replaced by the unified menu.

### Tool-calling round limit

Fiji Chat's installed assistant used LangChain4j's default 100-round limit.
The extension now registers `ProviderAssistantService` through SciJava's public
service mechanism with higher priority. It uses `maxToolCallingRoundTrips` with
a default of **1000** for this extension's four providers. A round is one model
response requesting tools; several tools in that response still count as one
round. A new user message gets a new budget. This is independent of HTTP timeout.

Use **Help > Assistants > LLM Execution Settings...** to change the positive
integer limit. The dialog reports whether this extension's service is ACTIVE.
Save, then reopen Fiji Chat to rebuild an existing assistant. Running requests
and already-built assistants retain their previous limit. Cancel does not save.
Settings are stored using SciJava `PrefService` under the stable
`AssistantExecutionSettings` class / `maxToolCallingRoundTrips` key; the storage
namespace contains no release version. An invalid stored value falls back to 1000.

Only `AbstractCatalogProvider` instances supplied by this extension use the
override. Other providers delegate to the installed `DefaultAssistantService`.
The existing tool/error handlers, image privacy state, request parameters,
image-tool normalization and supplied conversation memory are retained. The
extension does not replace classes in the Fiji Chat JAR, change model prompts,
replay failed requests, or add failed-turn persistence/resume UI.

The limit and extension JAR are separate from Fiji Chat's own files, so replacing
the core JAR does not itself reset the saved value. Compatibility still depends
on the SciJava service entry point and Fiji/LangChain4j APIs. This service mirrors
the current core builder wiring for the four added providers; upstream builder
changes need review. An ACTIVE label confirms service selection, not compatibility
with every future behavior. After an upgrade, rerun `build.ps1` against that Fiji
installation. The execution test verifies actual service selection, the chat
UI's service injection, and real 100/1000-round behavior without a real API key.
If another service supersedes this one, settings report NOT ACTIVE and do not
claim to apply a limit. Incompatible APIs may require rebuilding/adapting the
extension. Removing this extension restores the original service and its limit.

All four providers share one explicit timeout policy:
30 minutes for model responses, with connection setup bounded to 15 seconds.
Both synchronous and streaming factories use it. SiliconFlow previously retained
Chat Completions' 60-second default; Volcengine already used the longer policy.
DeepSeek's Responses client did not configure these timeouts at all, so it did
not have the same 60-second cutoff; it now gets explicit HTTP-level settings.
The Responses model builder has no timeout setter, so configuring only the
Chat Completions model builder would leave DeepSeek unaffected.

Model-list requests still time out after five seconds. The 30-minute policy is
a client setting, not a guarantee of service availability or maximum workflow
duration. A tool workflow can make multiple model requests. Service-side 504s,
connection failures, and upstream queuing limits still apply. DeepSeek documents
that it may send empty lines or SSE keep-alives while waiting and close a request
that has not started inference after ten minutes. No retry behavior was changed.

NVIDIA uses the `/v1` API prefix on the supplied integrate.api.nvidia.com origin.
Create an NVIDIA API key from https://build.nvidia.com/ (Get API Key), save it
under the distinct `NVIDIA` entry in Fiji's key manager, and select NVIDIA in
Refresh LLM Models. The master vault password is not the NVIDIA API key.
The public NVIDIA model catalog was fetched without credentials on 2026-11-02
and parsed successfully (85 IDs at that time). It contains embedding and other
non-chat models and does not declare model capabilities. The refresh dialog
therefore reminds users to choose a Chat Completions model supporting function
calling. For image analysis, also choose a model supporting image input. The
plugin does not infer those abilities from names or compile a model allowlist.
Catalog visibility does not prove permission to run inference; 401/403 errors
remain visible. No real NVIDIA inference key was used during verification.

Opening the model chooser requests a refresh, coalescing repeated requests
within 60 seconds. `Help > Assistants > Refresh LLM Models...` forces an
immediate refresh and shows whether the result is current or cached. Reopen
model settings afterwards. Successful refreshes replace the entire list, so
retired models disappear instead of being merged back in.

Available metadata drives image support and memory limits. Missing image
metadata is UNKNOWN, not proof of image support or its absence. SiliconFlow's
documented model-list response contains IDs but no guaranteed vision or context
metadata. Choose an appropriate vision model when allowing Fiji to send images.
Chat Completions uses Fiji's existing image normalizer: image tool results become
user image content while tool replies remain text. Reasoning content is retained
across tool turns. Sampling and thinking controls use server defaults. DeepSeek
continues to use its Responses protocol and declared reasoning effort metadata.
Memory tokens remain an approximation (including the image allowance), not the
providers' exact tokenizer. Unknown context size uses a 32,768-token memory budget.

Volcengine supports `<Enter model / Endpoint ID...>` for an enabled model or an
`ep-...` inference endpoint that may not appear in `/models`. The last manually
entered ID is saved in SciJava preferences and remains selectable after restart.
This is a user-configured override, not an ID invented by the plugin. Model-list
visibility does not guarantee that the account has enabled or may call a model.
Explicit non-LLM/VLM domain metadata and Shutdown/Retired status are filtered
when supplied; unknown capabilities are not inferred from model names.
Ark's nested `modalities.input_modalities` and `token_limits` fields are read.
An explicit image declaration is recognized, but a text-only Ark listing is
treated as UNKNOWN: on 2026-11-02 the live API's DeepSeek V4.1 Flash listing
conflicted with the official model page, which advertised multimodal support.
The plugin does not hardcode model names to resolve this upstream discrepancy.

The last successful response is cached at
`%LOCALAPPDATA%\FijiChat\<service>-models-v1.json` on Windows, using lowercase
`deepseek`, `siliconflow`, `volcengine` or `nvidia`. Only model metadata
is cached, never API keys. The API key comes from Fiji Chat's existing vault.
If the vault is locked, the refresh command opens Fiji's own key manager.
On first use, there is deliberately no compiled-in model fallback. If no
catalog is available, the chooser presents `<Unlock / refresh SERVICE models...>`
as a setup action, clears Fiji Chat's auto-run flag, and guides the user through
the normal key manager, a forced refresh, and a model choice. Canceling any step
exits without reopening the command. The setup label is never sent as a model ID.
The key manager is a SciJava DynamicCommand. It is launched through ModuleService
and its actual future is awaited, including dialogs and preprocessing. Calling
CommandService.run for a DynamicCommand starts it but returns null, so that API
cannot be used to wait for authentication. Canceling exits without refreshing.
Network/HTTP/parse errors retain the last successful catalog with an explicit
stale-cache status. A valid empty response clears the list. Requests time out
after five seconds. Model settings may wait briefly while refreshing.

New names and the documented metadata are supported without rebuilding. A
future incompatible API/schema change can still require a plugin update.

The extension intentionally does not bundle Fiji or LangChain4j dependencies.
It is compiled against the installed `fiji-llm-0.2.0-SNAPSHOT.jar` ABI and
should be rebuilt and revalidated after Fiji Chat upgrades.

The SciJava plugin index is checked in explicitly because the annotation
processor bundled with this Windows Fiji installation cannot rewrite its index
output under JDK 21. Provider, command and service discovery are exercised by
the build checks; new plugins must update both annotations and the index.

The API key is managed by Fiji Chat's existing encrypted key vault. Do not put
the key in source files, build commands, or logs.

Run `build.ps1` using the installed Fiji JDK to compile, package and execute all
nine offline verification suites. The key-manager regression uses the actual
SciJava CommandService and ModuleService in an isolated headless context to
reproduce the null future and verify waiting, refresh sequencing and cancellation.
The startup regression directly exercises the
installed Fiji_Chat initialize/run methods with isolated preferences and services.
Test fixtures use a local HTTP server and synthetic keys/model IDs. They do not
call paid APIs or inspect the user's vault. Added coverage checks all three Chat
Completions routes, distinct authentication headers, sync/SSE, reasoning and tool
round trips, exact image payload preservation, errors, cache source isolation,
four-service unlock sequencing and persisted manual Endpoint IDs.
The timeout regression actually holds HTTP response headers for 65 seconds.
The original Chat Completions streaming client times out at 60 seconds; the
original Responses client, with no configured timeout, completes the same delay.
All eight updated provider/model factories complete the delay. Four further SSE
cases send keep-alive comments for 65 seconds before completing. HTTP 504 errors
remain visible for both protocols. The regression also checks effective timeout
values, absence of silent request replay, and Ark's nested metadata schema.

Automated tests use no live account API key. Fixtures cover observed API metadata
shapes and transport behavior. Account permissions, model capabilities and
real-task quality require separate live validation. No paid API call is made
by automated verification.

Official references checked on 2026-11-02:

- https://api-docs.siliconflow.cn/docs/api/models-get
- https://docs.siliconflow.cn/docs/api/chat-completions-post
- https://docs.volcengine.com/docs/ark/chat-api?lang=zh
- https://docs.volcengine.com/docs/ark/model-list
- https://docs.volcengine.com/docs/ark/sdk-common-examples?lang=zh
- https://api-docs.deepseek.com/quick_start/rate_limit/
- https://docs.siliconflow.cn/docs/userguide/faqs/error-code
- https://docs.api.nvidia.com/nim/docs/api-quickstart
- https://docs.api.nvidia.com/nim/reference/llm-apis
- https://docs.api.nvidia.com/nim/reference/stepfun-ai-step-5-preview-infer
- https://docs.nvidia.com/nim-operator/latest/guardrail.html
