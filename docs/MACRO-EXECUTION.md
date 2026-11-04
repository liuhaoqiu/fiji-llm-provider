# Macro execution compatibility (0.5.7)

**English** | [简体中文](zh-CN/MACRO-EXECUTION.md)

Cellpose may finish quickly while Fiji Chat keeps reporting the macro as
`running`. In the reproduced case, Script Editor had returned from evaluation
and was waiting for associated threads during cleanup. More polling, tool rounds
or context capacity did not resolve that wait.

The extension now provides a higher-priority `ScriptExecutionService` for
`.ijm` macro runs. It reads the active visible editor's current source and uses
the same SciJava script-module/parameter-processing pipeline. An independent
evaluation group distinguishes macro work from editor/console maintenance.
Completion waits for evaluation and macro-created child threads, including
sleeping children. No thread is forcibly stopped or classified as disposable by
its name. Non-macro scripts delegate to Fiji Chat's original service.

Version 0.5.6 could still wait forever after large-image processing: shared JVM
common-pool workers inherit their first submitter's thread group but remain alive
while idle. Version 0.5.7 excludes only actual common-pool workers parked in the
pool's idle wait. Active workers, ordinary sleeping children and private pools
remain awaited. Unrelated activity elsewhere in the common pool does not delay
completion. A still-pending run reports `pending_child_threads` for diagnosis.
ImageJ stack windows also create an inherited slice-selector event loop. Its
idle `Object.wait` inside `ij.gui.StackWindow.run` is excluded; active selector
updates are still awaited. Neither exemption uses thread names or stops threads.

Runs retain IDs, bounded initial waits, status polling, console/ImageJ output,
environment changes and dialog/error reporting. Waiting longer than 30 seconds
returns `running` with `wait_expired`; it does not cancel or rerun the macro.
Input dialogs remain blocked; dismissing a macro error does not turn it into
success. The tool result identifies this backend as `Fiji LLM Provider macro module`.
The macro tools' run status is the authoritative status for this path, rather
than Script Editor's original Running title. This compatibility service applies
to the shared Fiji Chat macro tools, regardless of the selected LLM provider.

Install only the updated extension JAR and restart Fiji. Keep Fiji Chat and
Script Editor JARs installed. Existing stuck invocations are not repaired in
place; save scripts, labels, ROIs and result tables before restarting.

Do not infer segmentation quality from execution success. If a macro explicitly
launches independent work outside its evaluation group, inspect that external
job's own status before claiming it is finished. Unknown persistent children
inside the group remain running; the extension does not guess that they are safe
to kill. Stopping chat does not undo completed tools.

## Verification

Fifteen local suites passed. A cold-process fixture reproduces the 0.5.6 wait and
checks renamed shared workers, active tasks, unrelated activity and private pools.
Additional isolated GUI/module tests cover repeated
macro runs, a real stack-window selector and normal window closure, as well as
a real 31-second wait, duplicate prevention, input/error dialogs and
original script-service delegation. A real Cellpose invocation on a synthetic
128×128 image returned `success` in approximately 10.5 seconds including process
startup. The same fixture through the original service printed its completion
marker but still returned `running` after the 30-second initial wait.

Large-image checks additionally run actual Cellpose on 3000×3000 RGB, export
ROIs, write a count table and run a subsequent macro. A local preview copy from
the reported task supplements the synthetic fixtures; it is neither uploaded nor
distributed. These checks use installed Cellpose-Appose 0.1.24 and the nuclei
model, without a hosted inference API. They verify execution, not biological
segmentation accuracy. Exact results are in [release verification](VERIFICATION.md).

The default build runs local suites. On a Windows desktop with the matching Fiji
dependencies, add `-VerifyMacroIntegration` for GUI/module checks; add
`-VerifyCellpose` for the actual Cellpose fixture. The latter needs the installed
Cellpose plugin/environment/model and may download missing components.
Optionally supply `-CellposeFixture "C:\path\to\RGB-image.png"` with
`-VerifyCellpose` to verify nonempty ROI export using your own local image.
