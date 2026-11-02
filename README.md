# Fiji LLM Provider

**English** | [简体中文](README.zh-CN.md)

Add **DeepSeek, SiliconFlow, Volcengine Ark and NVIDIA** to
[Fiji Chat](https://github.com/fiji/fiji-llm) with one extension JAR.

The extension discovers model IDs from each service, uses Fiji Chat's existing
API-key manager, and provides a configurable assistant tool-round limit with a
default of **1000 rounds per user message**. It extends Fiji Chat through SciJava
services; it does not replace the original Fiji Chat JAR.

**Current version: 0.5.1.** This is an independent extension, not an official
release from Fiji or any model provider.

## Example workflow

Fiji Chat using **DeepSeek Flash** to create and test an ImageJ macro for
brightness and contrast adjustment. The screenshot shows the image before
adjustment (left), the adjusted display (center), and the macro editor and
assistant conversation.

[![Fiji Chat with DeepSeek Flash: brightness and contrast adjustment workflow](docs/fiji-chat-contrast-brightness-demo.png)](docs/fiji-chat-contrast-brightness-demo.png)

*Click the image to view the full-resolution screenshot.*

## Features

- Four hosted providers in Fiji Chat's model chooser.
- Dynamic model lists, with cached results when refresh temporarily fails.
- Manual model / inference endpoint entry for Volcengine Ark.
- Shared response timeout configuration for long model requests.
- Multimodal message handling and image-tool normalization where required.
- A configurable tool-calling round limit for the four added providers.
- Nine local verification suites requiring no live API key.

| Provider | API base | Protocol | Model discovery |
| --- | --- | --- | --- |
| DeepSeek | `https://api.deepseek.com` | Responses | `GET /models` |
| SiliconFlow | `https://api.siliconflow.cn/v1` | Chat Completions | `GET /models?sub_type=chat` |
| Volcengine Ark | `https://ark.cn-beijing.volces.com/api/v3` | Chat Completions | `GET /models`, plus manual model / endpoint ID |
| NVIDIA | `https://integrate.api.nvidia.com/v1` | Chat Completions | `GET /models` |

## Requirements

You need a working **Fiji installation with Fiji Chat installed**, plus an API key
for each hosted service you intend to use. A model must support tool/function
calling to perform Fiji actions; image input additionally requires a suitable
vision-capable model.

The verified environment is Windows 11, Fiji/ImageJ 2.18.0 / ImageJ 1.54p,
Fiji Chat `0.2.0-SNAPSHOT`, Java 21.0.7, and LangChain4j 1.21.0. The extension is
compiled for Java 17, but that does not establish compatibility with other Fiji
dependency sets or operating systems. See [compatibility and verification](docs/VERIFICATION.md).

## Installation

1. Install Fiji Chat first. Follow the upstream
   [Fiji Chat Quick Start](https://github.com/fiji/fiji-llm#fiji-chat-quick-start).
   It currently uses the `Fiji-chat` update site at
   `https://sites.imagej.net/Fiji-chat/`.
2. Save your work and **close Fiji**.
3. Download `fiji-llm-provider-0.5.1.jar` from the [Releases page](https://github.com/liuhaoqiu/fiji-llm-provider/releases).
   Optionally compare its SHA-256 with `SHA256SUMS.txt` in the same release.
4. Copy the JAR into the **`jars` directory inside your Fiji installation**.
   Do not replace `fiji-llm-*.jar`, which belongs to Fiji Chat itself.
5. If upgrading, move older `fiji-llm-provider-*.jar` or
   `fiji-deepseek-provider-*.jar` files out of `jars`, or rename them so they no
   longer end in `.jar`. Keep only one active extension version.
6. Restart Fiji. Under **Help > Assistants**, confirm that **Refresh LLM Models...**
   and **LLM Execution Settings...** are available.

The JAR is the installable artifact. You do not need to compile or install the
source ZIP to use it. Detailed instructions are in [Installation](docs/INSTALLATION.md).

## First use

1. Open **Help > Assistants > Manage API Keys...**.
2. Select **DeepSeek**, **SiliconFlow**, **Volcengine**, or **NVIDIA**, and save the
   API key issued by that service. The vault unlock password is different from
   a provider API key.
3. Open **Help > Assistants > Refresh LLM Models...**, select the same provider,
   and refresh. Unlock the key vault if prompted.
4. Open **Help > Assistants > Fiji Chat...**. Choose the provider and a model,
   or use the chat window's gear button to change an existing selection.
5. Open an image or script in Fiji and ask the assistant to work with it. For
   example: “Inspect the active image and suggest suitable brightness and
   contrast settings.”

After refreshing, reopen the model chooser to see the updated list. To send
images, enable Fiji Chat's image-sharing control and select a model supporting
image input. The image-sharing control is not a guarantee of model capability.

For Ark endpoint IDs, NVIDIA catalog caveats and example workflows, see
[Usage](docs/USAGE.md).

## Tool-round limit

Open **Help > Assistants > LLM Execution Settings...**.

- The default in **0.5.1 is 1000 rounds per user message**.
- You can save any positive integer appropriate to your task.
- One model reply containing several tool calls counts as **one round**. The
  displayed tool-use count can therefore differ from the round count.
- Reopen Fiji Chat after saving to apply the setting to an existing chat window.
  A running request keeps the budget with which it started.
- A new user message gets a fresh budget. Reaching the limit still ends the
  current invocation; this release does not add automatic resume or checkpoints.

Existing saved settings take priority over the new default. If you previously
saved 300 in version 0.5.0, set 1000 in the menu to adopt the new value. The menu
should report **Fiji LLM Provider execution control: ACTIVE**. Other providers,
including the built-in ChatGPT, Claude, Gemini and Ollama integrations, continue
to use the original Fiji Chat assistant service.

## Timeouts

All four providers configure a 30-minute model-response wait and a separate
15-second connection timeout. Model-list requests use a 5-second timeout.
These are client settings for individual requests, not a guarantee that a
provider will keep a request alive or finish a task. Server-side limits, HTTP
errors, network failures and the tool-round budget are separate concerns.

## Updating Fiji Chat

The extension and its saved preferences are separate from the Fiji Chat JAR.
Replacing the core JAR alone does not reset the configured round limit. However,
changes to Fiji Chat or LangChain4j interfaces can require an extension update.
The ACTIVE indicator confirms service selection, not compatibility with every
future upstream behavior. Rebuild and rerun the tests after a core upgrade.

## Build from source

On Windows, use **PowerShell 7** and **JDK 21 or newer** with an existing compatible
Fiji installation. No Maven repository, API key or live inference request is
required by the build.

```powershell
pwsh -NoProfile -File .\build.ps1 -FijiRoot "C:\path\to\Fiji"
```

If the bundled JDK cannot be found, supply it explicitly:

```powershell
pwsh -NoProfile -File .\build.ps1 -FijiRoot "C:\path\to\Fiji" -JdkHome "C:\path\to\jdk-21"
```

The script compiles against Fiji's installed JARs, runs all nine verification
suites, and writes `build/fiji-llm-provider-0.5.1.jar` only after they pass.
The timeout suite deliberately waits at least 65 seconds. Tests use local
servers and synthetic models/tools; they do not process your open images or
change your saved tool-round preference. See [Building](docs/BUILDING.md).

## Troubleshooting and contributions

See [Troubleshooting](docs/TROUBLESHOOTING.md), [implementation notes](docs/DEVELOPMENT.md)
and [the changelog](CHANGELOG.md). When reporting an issue, include the extension,
Fiji Chat and Java versions, provider name and sanitized error text. Do not include
API keys, vault passwords, conversation exports or private images.

## License

[PolyForm Noncommercial 1.0.0](LICENSE.txt) for original extension contributions.
This source-available release permits the purposes defined in that license; it
does not add a requirement to publish downstream source code. Read the explicit
permissions for educational and other listed organizations in the license.

Assistant builder wiring adapted from [Fiji LLM](https://github.com/fiji/fiji-llm)
retains its upstream BSD 2-Clause notice. Dependencies are not bundled. See
[Licensing](docs/LICENSING.md) and [NOTICE.txt](NOTICE.txt) for scope and attribution.
