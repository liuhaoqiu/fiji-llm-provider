# 0.5.19 verification

**English** | [简体中文](zh-CN/VERIFICATION.md)

**26 distinct regression suites passed**: 25 suites in the full build plus a separately compiled/run JSON/SSE wire suite. **Seven installed-JAR checks passed.** The exact observed DSML-contaminated name reproduces the interruption against unchanged 0.5.18 and recovers in 0.5.19.

Coverage includes all four provider service configurations in synchronous and streaming flows; malformed/ordinary unknown names; mixed valid/invalid batches; original call correlation and memory; no guessed execution or completed-action replay; preserved public progress and one final response; existing finite round limits; unchanged original-service behavior; real local Chat Completions JSON/SSE round trips; multimodal callbacks, display-failure isolation, error rendering and chat/history presentation.

Tests used synthetic models/tools and local servers. **No hosted inference or user image analysis was run for this fix.** Native GUI suites were not repeated. These checks verify the integration, not scientific accuracy or successful self-correction by every remote model. No inference or segmentation speedup is claimed.

Tested environment: Windows 11, Java 21.0.7, Fiji/ImageJ 2.18.0 / 1.54p, Fiji Chat 0.2.0-SNAPSHOT commit f73180448c64c83edeb995dd96156d1dd68f7896, LangChain4j 1.21.0, Script Editor 1.4.3. Future upstream builds require compatibility checks. Private logs/images, credentials and dependency JARs are not bundled.

Original contributions retain **PolyForm Noncommercial 1.0.0**, with upstream BSD notices retained.

JAR SHA-256: `5b9d16f43cea468673f07d14ec78edf83355e9c234320b3852db03148702513d`
