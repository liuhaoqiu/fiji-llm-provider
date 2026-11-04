# 0.5.17 verification

**English** | [简体中文](zh-CN/VERIFICATION.md)

## Validation and scope

**24 local regression suites passed** for the final 0.5.17 build. The unchanged 0.5.16 build fails the new literal-rendering regression, and 0.5.17 passes using Fiji Chat's actual renderer. Installed-JAR probes verify service/class origin, rendering, image/multipart callbacks, injected display-failure isolation and chat/history presentation. The earlier 0.5.15 baseline passed six isolated native GUI integrations; these were not rerun for the one-line 0.5.17 presentation change. Unit/integration suites use synthetic tools/images and local servers; the real user-run checks above are separate observations.

Verified environment: Windows 11, Java 21.0.7, Fiji/ImageJ 2.18.0 / 1.54p, Fiji Chat `0.2.0-SNAPSHOT` at commit `f73180448c64c83edeb995dd96156d1dd68f7896`, LangChain4j 1.21.0 and Script Editor 1.4.3. The original Fiji Chat and Script Editor JARs are unmodified. This covers the tested binaries, not every future upstream snapshot. Provider-side errors/timeouts can still occur; execution evidence does not certify clean-state replay or scientific accuracy.

Original contributions remain **PolyForm Noncommercial 1.0.0**, with upstream BSD notices retained. No API keys or private task archives are included; the reviewed task images remain local.

JAR SHA-256: `5d670c592c2b85825b77869a4d7c4badda7fc421c281a2b3dfff755b99dfd27f`
