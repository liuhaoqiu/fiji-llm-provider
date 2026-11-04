# Installation and upgrades

**English** | [简体中文](zh-CN/INSTALLATION.md)

## Before installing

Install [Fiji](https://imagej.net/software/fiji/) and enable the upstream
[Fiji Chat update site](https://github.com/fiji/fiji-llm#fiji-chat-quick-start).
The upstream instructions currently specify the unlisted site named `Fiji-chat`
with URL `https://sites.imagej.net/Fiji-chat/`. Restart Fiji after updating.

Confirm that **Help > Assistants > Fiji Chat...** exists before adding this
extension. Fiji LLM Provider depends on Fiji Chat; it is not a standalone chat
application and does not include Fiji or LangChain4j.

The release was built and tested with Windows 11, Fiji/ImageJ 2.18.0 / ImageJ
1.54p, Fiji Chat 0.2.0-SNAPSHOT, Java 21.0.7 and LangChain4j 1.21.0. Other
combinations are unverified. In particular, the `SNAPSHOT` name alone does not
identify identical upstream bytes; see [Verification](VERIFICATION.md).

## Install the release JAR

1. Save your images, scripts and analysis results, then close Fiji normally.
2. From this repository's GitHub **Releases** page, download
   `fiji-llm-provider-0.5.9.jar` and, optionally, `SHA256SUMS.txt`.
3. Locate your Fiji installation directory. It contains the launcher and a
   directory named `jars`.
4. Copy `fiji-llm-provider-0.5.9.jar` into that `jars` directory.
5. Keep exactly one active version of this extension. Move older
   `fiji-llm-provider-*.jar` and `fiji-deepseek-provider-*.jar` files outside
   `jars`, or append `.disabled` to their filenames.
6. Leave the original `fiji-llm-*.jar` and LangChain4j JARs in place.
7. Start Fiji. Check **Help > Assistants** for **Refresh LLM Models...** and
   **LLM Execution Settings...**.
8. Open **LLM Execution Settings...**. It should show
   **Fiji LLM Provider execution control: ACTIVE**. A new installation defaults
   to **1000** tool rounds per message.

Windows users can check the downloaded file with PowerShell:

```powershell
Get-FileHash -Algorithm SHA256 -LiteralPath .\fiji-llm-provider-0.5.9.jar
```

Compare the result to the entry for that filename in the release's
`SHA256SUMS.txt`. The source ZIP is for reading or building the project; do not
copy it into Fiji's `jars` directory.

## Configure a provider

Follow [First-time setup](USAGE.md#first-time-setup). Each provider needs its own
API key. No credential is included in this project or its release files.

## Upgrade from an older extension

Close Fiji before replacing extension JARs. Existing API keys and model caches
remain in Fiji's own storage. The DeepSeek provider retains its original service
and class names for compatibility with previous versions.

The **default** remains 1000 tool rounds per user message. An explicitly saved value
still takes precedence. If **LLM Execution Settings...** shows 300 after an
upgrade, change it to 1000 and save. Reopen Fiji Chat to rebuild an existing
assistant with the new setting. A running request is not changed mid-task.

## Upstream Fiji Chat updates

The extension JAR and its preferences are separate from the core JAR. A normal
replacement of the core file does not overwrite the saved round limit. However,
an upstream API change can break compatibility or require changes to the
assistant builder integration. After updating Fiji Chat, check the ACTIVE
indicator and run the source verification suite against the updated installation.

## Uninstall or roll back

Save your work and close Fiji. Move this extension's JAR out of `jars` and restart
Fiji. To roll back, restore one previously working extension JAR instead. Do not
enable two versions together. Uninstalling the extension restores Fiji Chat's
original provider list and assistant execution behavior; it does not delete the
key vault, conversations or saved extension preferences.
