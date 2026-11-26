# 0.5.23 verification

**English** | [简体中文](zh-CN/VERIFICATION.md)

**All 26 regression suites pass in one build.** New checks cover every change since 0.5.19: a stop during reasoning; DeepSeek image detail with unchanged memory, estimate and other providers; script evidence that follows a script across tab positions; exact script file saving with no silent overwrite; macro keys resolved by ImageJ's own option parser for the real Cellpose-SAM command; bounded waits on run-status tools; both report languages in the presentation protocol; no chat-body line for script edits; and commands that accept a preprocessed channel while the analysis bundle still refuses it.

Four real tasks on one image were run with DeepSeek during development. They exercised full-detail images, bilingual reports, macro keys without a dialog, script file saving and the shorter chat body.

**Not verified.** The bounded wait was tested with simulated status tools, not Fiji Chat's real ones. The preprocessed-channel path was not taken in a real task. The native GUI suites were not run for this release. `detail=high` is checked in the request handed to the SDK, not in a network capture. None of these checks concerns scientific accuracy: in the four real tasks the model chose different methods and reported 3022, 3012, 5230 and 3697 cells for the same image.

Automated tests use synthetic models and tools and local servers. They do not use API keys, hosted inference or user images.

Tested environment: Windows 11, Java 21.0.7, Fiji/ImageJ 2.18.0 / 1.54p, Fiji Chat 0.2.0-SNAPSHOT commit 1dea483ee7285efb86689902b4cae56dcd66a549, LangChain4j 1.21.0, Script Editor 1.4.3. Future upstream builds require compatibility checks. Private logs/images, credentials and dependency JARs are not bundled.

Original contributions retain **PolyForm Noncommercial 1.0.0**, with upstream BSD notices retained.

JAR SHA-256: `d7603edde405d2108663a482b2260d88c67d49ed7e9739cddd9fff27732a0c2b`
