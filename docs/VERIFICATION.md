# 0.5.18 verification

**English** | [简体中文](zh-CN/VERIFICATION.md)

The final build passed **24 local regression suites** and installed-JAR checks for service/class origin, error rendering, display-failure isolation, image/multipart callbacks and chat/history presentation. The real-receipt regression reproduces the wrong badge in unchanged 0.5.17 and passes in 0.5.18. Tests cover cleanup after export, later edits, failed exports, multiple exports, paused output, unsupported languages and request isolation. Native GUI suites were not repeated for this display-only change.

Two subsequent user-run tasks were independently reviewed: a nuclei task delivered **267 labels = 267 CSV rows = 267 ROIs**; a cells-and-nuclei task delivered **283/258 labels**, each matching its CSV/ROI counts. File hashes, source pixels, measurements and exported QC were checked. The second task also compacted context and continued through execution, export and final output. These establish observed engineering consistency, not biological accuracy or clean-state replay. Model-generated script/API mistakes and report arithmetic errors still occurred.

Observed complete task durations were about **7 min 15 s** and **16 min 30 s**, respectively. They involve different tasks and model decisions and are not a controlled speed comparison or a speedup claim for 0.5.18.

Tested on Windows 11, Java 21.0.7, Fiji/ImageJ 2.18.0 / 1.54p, Fiji Chat `0.2.0-SNAPSHOT` commit `f73180448c64c83edeb995dd96156d1dd68f7896`, LangChain4j 1.21.0 and Script Editor 1.4.3. Future upstream builds require compatibility checks; provider failures/timeouts remain possible. Private task logs, images and credentials are not included.

Original contributions retain **PolyForm Noncommercial 1.0.0**, with upstream BSD notices retained.

JAR SHA-256: `ce41b04ed9308735f7a4a2583beda824c05bd81b8e635509d7f94de9a7eee45d`
