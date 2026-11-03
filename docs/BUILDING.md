# Building and verifying

**English** | [简体中文](zh-CN/BUILDING.md)

## Supported build procedure

The supplied build script is verified on **Windows with PowerShell 7 and JDK 21**.
It uses the JARs from a local Fiji installation with Fiji Chat already installed.
The plugin is compiled with `--release 17`; runtime compatibility still depends
on the complete Fiji / LangChain4j dependency set.

```powershell
pwsh -NoProfile -File .\build.ps1 -FijiRoot "C:\path\to\Fiji"
```

To choose a JDK explicitly:

```powershell
pwsh -NoProfile -File .\build.ps1 -FijiRoot "C:\path\to\Fiji" -JdkHome "C:\path\to\jdk-21"
```

Without `-JdkHome`, the script looks for a bundled Fiji JDK, then `JAVA_HOME`, then
`javac.exe` on `PATH`. It requires JDK 21 or newer for the supplied packaging
procedure. Only JDK 21.0.7 was used for release verification.

The script does not download dependencies, install the result into Fiji, restart
Fiji, or change API keys and saved tool-round settings. The tests run in separate
Java processes with synthetic inputs and loopback HTTP servers.

## Build outputs

After all ten suites pass, the output is:

```text
build/fiji-llm-provider-0.5.5.jar
```

Intermediate classes and Java argument files are kept under a fresh
`build/run-.../` directory. Fresh directories prevent old classes from silently
entering a new JAR. Installed copies of this extension are excluded from the
compile and test classpaths. Argument files avoid Windows command-line limits.

The version comes from `MANIFEST.MF`. The packaging timestamp is fixed for this
release. Resource and license bytes are preserved by `.gitattributes`. A build
using a different JDK or upstream dependency set may produce different bytes.

## Test suites

| Suite | Coverage |
| --- | --- |
| `VerificationMain` | Provider contracts and SciJava discovery |
| `KeyManagerVerificationMain` | Real command/module futures, unlock sequencing and cancellation |
| `StartupVerificationMain` | Core startup loop and setup/cancellation regression |
| `ModelCatalogVerificationMain` | Dynamic IDs, refresh, removal, metadata, cache and failures |
| `MultimodalVerificationMain` | Image / multi-content token counting and memory |
| `TransportVerificationMain` | Responses requests, SSE, tools and auth errors |
| `MultiProviderVerificationMain` | Chat Completions providers, reasoning, images and endpoint persistence |
| `AssistantExecutionVerificationMain` | Actual service selection, tool-round limits and settings |
| `ProviderTimeoutVerificationMain` | Delayed headers, SSE keep-alives, timeout configuration and errors |

The timeout suite intentionally waits at least 65 seconds. The assistant suite
uses the real LangChain4j loop and deterministic local models/tools, including
1000-round completion and enforcement before tool round 1001. No paid inference
request is part of this verification.

## Updating dependencies or adding code

The extension does not bundle Fiji Chat or LangChain4j. It depends on their
installed interfaces; there is no Maven build that silently substitutes a
different snapshot. Rebuild and run the tests against each new target environment.

The SciJava plugin index is checked in at
`src/main/resources/META-INF/json/org.scijava.plugin.Plugin`. Compilation uses
`-proc:none`; when adding or renaming a plugin, update that index together with
its Java annotation and discovery tests.

The historical Java package `sc.fiji.llm.deepseek` contains all four providers.
Keeping it preserves preferences and existing integrations. See
[Implementation notes](DEVELOPMENT.md) for the rationale behind the compatibility fixes.


The tenth suite, `ContextBudgetVerificationMain`, covers the context and UI recovery behavior documented in [Context](CONTEXT.md).
