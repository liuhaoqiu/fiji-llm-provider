# Execution status and editor compatibility (0.5.13)

**English** | [简体中文](zh-CN/STATUS.md)

This local update improves error/state reporting for the four extension providers. It adds no scientific method selection, analysis-specific QC policy, tool approvals or automatic retries. Default limits and provider settings are retained.

## Saved-script badge (0.5.18)

The final report uses the most recent successfully exported script snapshot. Later cleanup, edits or tab renames do not change that saved identity; failed exports do not replace it. The badge explicitly refers to saved bytes, not the current editor. Without an export, previous latest-script behavior is retained. See [release notes](releases/v0.5.18.md).

## Busy and failed starts

A conflicting start returns `status: busy`, `started: false`, the requested script ID and the blocking script name/ID when known. If Fiji Chat has already supplied its run ID, `blocking_run_id` and a matching status-tool recommendation are included. During initial startup or for editor tasks started outside this service, no run ID is invented. Existing work is neither cancelled nor replayed. Startup failures retain their cause chain in the activity log and a bounded explanation in public progress.

## Scope of script-version evidence

The exact-byte checker records this extension's macro execution. Ordinary scripts continue through Fiji Chat's original execution service and return `not_monitored`, `execution_evidence_supported: false` from the checker. This means the checker does not cover that language; it does not mean the script never ran or failed. Its execution receipt remains authoritative for output/status. Macro edits continue to invalidate the hash match. Neither kind of receipt certifies clean-state replay or scientific correctness.

## Script Editor 1.4.3 compatibility

The original editor's final error-highlighting step looks up its currently selected pane. In an isolated real Groovy reproduction, creating a new tab while a script runs causes `ErrorParser.parse` to throw `Writer is null` after the body finishes. When `fiji_script_create` finds that the most recent editor has active editor tasks, it now creates the new script in a separate editor window. It leaves the original editor's selected tab intact and returns `editor_placement: separate_window_existing_editor_running`.

This avoids the observed plugin-created-tab trigger without patching the Script Editor JAR or replacing the ordinary-script engine. Manual tab changes and other editor APIs remain upstream behavior; this is not a universal fix for all error-highlighter races. Genuine script errors and partial output remain visible.

## API wait and display failures (0.5.17)

Activity monitoring uses tool start/finish metadata and API activity. The experimental 0.5.13 background receipt parser was removed in 0.5.15; it is not a current feature. Optional status/presentation failures are isolated from tool-result delivery. Original tool payloads and genuine execution errors remain intact.

Progress distinguishes ordinary input dialogs from receipts containing explicit error_dialog text. The bounded reason is rendered as inline code, preserving angle-bracket markers. See [0.5.17 notes](releases/v0.5.17.md).
