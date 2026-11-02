# Using Fiji LLM Provider

**English** | [简体中文](zh-CN/USAGE.md)

## First-time setup

1. Obtain an API key from the provider you want to use. The four services use
   different credentials; a key for one service does not authenticate another.
2. In Fiji, open **Help > Assistants > Manage API Keys...**.
3. Choose **DeepSeek**, **SiliconFlow**, **Volcengine**, or **NVIDIA**, and save
   the corresponding service API key using Fiji Chat's existing key manager.
4. Open **Help > Assistants > Refresh LLM Models...**, select that service and
   refresh its model list. If the vault is locked, complete the normal unlock
   dialog. Canceling exits without starting another refresh.
5. Open **Help > Assistants > Fiji Chat...** and select the service and model.
   In an existing chat, use the gear button to open the model settings.

The vault master password unlocks local credential storage. It is **not** a
provider API key and should not be entered into a provider's API-key field.

| Service | Key / account starting point |
| --- | --- |
| DeepSeek | [DeepSeek platform](https://platform.deepseek.com/) |
| SiliconFlow | [SiliconFlow cloud console](https://cloud.siliconflow.cn/) |
| Volcengine Ark | [Volcengine console](https://console.volcengine.com/ark/) |
| NVIDIA | [NVIDIA Build](https://build.nvidia.com/) — open a model and use Get API Key |

## Refreshing and choosing models

Model IDs come from the service API, rather than a compiled list. Opening the
model chooser attempts a refresh, with requests within 60 seconds coalesced.
**Refresh LLM Models...** forces a new request and reports whether its result is
current or cached. Reopen model settings after refreshing.

A successful refresh replaces the list, so removed model IDs disappear. A
temporary failure retains the last successful catalog and labels it as possibly
out of date. A valid empty response clears the catalog. On first use without a
catalog, an **Unlock / refresh ... models...** setup choice guides you through
authentication and refresh.

Choose a model whose provider documents **tool/function calling** support. For
image inspection, also choose a model accepting images. A model appearing in a
catalog does not prove that your account has permission to invoke it.

### Volcengine Ark

If your enabled model or inference endpoint is absent from the returned list,
select **Enter model / Endpoint ID...**. Enter the exact model ID or `ep-...`
endpoint from your Ark console. The last manually entered ID is retained locally
and remains available after a catalog refresh. This does not enable models or
grant account permissions automatically.

### NVIDIA

The plugin uses `https://integrate.api.nvidia.com/v1` automatically. The catalog
can include embeddings and other non-chat models, and may not declare tool or
vision capabilities. Select a model supporting the Chat Completions endpoint and
function calling. Public model-list access does not establish inference access;
authentication or permission errors are still reported by the model API.

## Working with an image or script

Open an image in Fiji, then describe the outcome you want. For example:

> Inspect the active image. Explain its current display range and suggest an
> appropriate brightness and contrast adjustment.

For a task involving modification:

> Work on a duplicate of the active image. Create an ImageJ macro that adjusts
> the display contrast, test it, and explain what changed.

You can attach the relevant image or script through Fiji Chat's context controls.
When allowing image input, use the chat's **AI can view images** control and a
compatible model. That control expresses permission to share image data; it is
not a capability certification for the selected model.

Fiji tools can alter open images and scripts. Review the generated result and
save the version you intend to keep. A successful tool call does not, by itself,
establish that an image-processing result is scientifically appropriate.

## Controlling long tasks

Open **Help > Assistants > LLM Execution Settings...**. The default in 0.5.1 is
**1000 rounds per user message**. You can save a different positive integer.

A round is a model reply containing tool requests. Several tool calls can belong
to one round, so the chat's displayed tool-use count is not necessarily the round
count. Every new user message starts with a fresh round budget.

Save the setting, then reopen Fiji Chat to apply it to an existing assistant.
Your saved preference takes precedence over future defaults. This controls the
four providers supplied by this extension; other providers retain the core
assistant's behavior.

Reaching the limit ends the current invocation. This version does not add a
checkpoint or resume button. Save useful scripts and continue from their current
state in a new message if necessary. Raising a limit gives the model more room;
it does not guarantee convergence or faster responses.

## What is stored locally

- API keys use Fiji Chat's existing encrypted key vault.
- The tool-round value and a manually selected Ark endpoint use SciJava preferences.
- Model catalog caches contain model metadata, not API keys. On Windows they
  reside under `%LOCALAPPDATA%\FijiChat\` with filenames such as
  `deepseek-models-v1.json` and `nvidia-models-v1.json`.
- Conversation handling remains the responsibility of Fiji Chat.

The release package does not include any of these local data files. See
[Troubleshooting](TROUBLESHOOTING.md) for connection, model-list and execution errors.
