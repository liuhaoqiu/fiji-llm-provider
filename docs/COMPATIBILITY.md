# Fiji Chat compatibility checked for 0.5.12

**English** | [简体中文](zh-CN/COMPATIBILITY.md)

Checked on 2026-11-04 with Windows 11, Java 21.0.7, Fiji/ImageJ 2.18.0 / 1.54p, LangChain4j 1.21.0 and Script Editor 1.4.3. Both Fiji Chat builds identify as `0.2.0-SNAPSHOT`; the build commit and JAR hash distinguish them.

| Fiji Chat build | Commit | JAR SHA-256 |
| --- | --- | --- |
| Installed baseline | `15e95c9a0de360c3fc108001d7ac006462c150fc` | `4d9925076bb17fb02cb10830b6385d44101edc90ab28b9d967c21a02e03a8eca` |
| Downloaded update tested in isolation | `f73180448c64c83edeb995dd96156d1dd68f7896` | `7f18e1f885cd798898516c8dafcd00fa7c0762cd44187eb4ba1a620d021874b4` |

The 0.5.12 extension passed **21 local verification suites and five isolated GUI integrations** with each core build. Its full source also compiled against the downloaded update. Six relevant public API surfaces were unchanged, and class-origin probes confirmed that the update core and the installed 0.5.12 assistant service were actually loaded in the isolated checks. The original Fiji Chat and Script Editor JARs were not patched. The downloaded update was not applied to the running user installation as part of this audit.

The [upstream changes](https://github.com/fiji/fiji-llm/compare/15e95c9a0de360c3fc108001d7ac006462c150fc...f73180448c64c83edeb995dd96156d1dd68f7896) mainly concern Gemini model-document parsing and the welcome page. This is a check of these exact binaries, not a guarantee for future snapshots. Tests used synthetic images, commands and local HTTP servers; no hosted inference, API credentials or user images were used. Biological segmentation accuracy was not evaluated.
