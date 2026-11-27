# 0.6.0 verification

**English** | [简体中文](zh-CN/VERIFICATION.md)

**All 27 regression suites pass in one build.** The new suite covers the output folder. New checks cover every change since 0.5.23: folder numbering claimed by creating the folder; reports, scripts, steps and manifest written without replacing anything; standard results that copy the source only when it is not a file; files in folders a script created next to the input; the literal-folder warning; a switched-off feature, an unsaved image, an unwritable folder and failed writes that leave the analysis untouched; the note for a Boolean macro option written as 1 and its silence for correct options; long lists in very large tool results cut with counts while text, short results and a switched-off setting leave results whole.

The six native GUI suites were run on the release build, together with two real calls of the installed Cellpose command from a macro: one with `usegpu=1`, which ran unchanged and was reported, and one with `usegpu=true`.

Five real tasks on one image were run with DeepSeek on builds of this version. They exercised the output folder, the report and step log, the manifest including files a macro left next to the image, the standard result set with and without a source copy, the smaller bundle result, and the bounded status wait on runs of up to six minutes.

**Not verified.** List shortening did not occur in a real task after it was added; it was tested with three archived results of real tasks (552,848, 519,790 and 59,247 characters) and with an archived 22,098-character list of interface controls, which is sent whole. The Boolean option warning did not occur in a real task, because the models wrote the option correctly once the parameter tool said how. The two new check boxes in LLM Execution Settings were not inspected on screen. A GPU was requested in the real tasks; the Cellpose command does not report the device it used. None of these checks concerns scientific accuracy: in the five real tasks the model reported 4304, 4306, 4296, 4296 and 4306 cells for the same image, and in each of them dense clumps of cells remained partly unsegmented.

Automated tests use synthetic models and tools and local servers. They do not use API keys, hosted inference or user images.

Tested environment: Windows 11, Java 21.0.13.1, Fiji/ImageJ 2.18.0 / 1.54p, Fiji Chat 0.2.0-SNAPSHOT commit 1dea483ee7285efb86689902b4cae56dcd66a549, LangChain4j 1.21.0, Script Editor 1.4.3. Future upstream builds require compatibility checks. Private logs/images, credentials and dependency JARs are not bundled.

Original contributions retain **PolyForm Noncommercial 1.0.0**, with upstream BSD notices retained.

JAR SHA-256: `74f2ceba22951f836aa6b01fbad99e1e9bf42f7be008044eed2be55e9f01983f`
