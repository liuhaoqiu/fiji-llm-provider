# Automation compatibility (0.5.9)

**English** | [简体中文](zh-CN/AUTOMATION.md)

These additions apply to the four providers supplied by this extension. Existing Fiji Chat tools remain available; the assistant receives brief guidance to prefer these helpers where applicable. Model use of that guidance is not guaranteed.

## Completed macros and idle pools

An ordinary Java executor can create workers in the macro's thread group and keep them waiting for its next job after Cellpose finishes. The extension recognizes standard queue waits directly under `ThreadPoolExecutor.getTask/runWorker`, then checks again before completing the run. It does not stop or shut down the pool. Active jobs, queued work, scheduled/delayed jobs and unknown child threads remain awaited. A long provider request or a genuine unfinished computation can still take time.

## Dialogs and numeric values

`fiji_ui_windows_identify` lists stable `window_id` values, owners, image IDs, buttons and numeric `control_id` values. `fiji_ui_dialog_respond_by_id` targets one live dialog even when titles match. Stale IDs fail explicitly. An action being queued is not a claim that it completed; inspect the resulting window or tool state.

`fiji_ui_number_set_by_id` changes the numeric model, commits its editor and reports the actual value. Affirmative dialog actions also commit number editors first. Out-of-range or fractional integer values are rejected. Only supported Swing numeric controls are covered; third-party custom widgets may need another route.

`fiji_command_parameters` reports actual SciJava input names, types and constraints without executing the command. The installed Cellpose-SAM command exposes names such as `cell_diameter`, `min_size` and `image_title`. Static metadata does not guarantee that every third-party macro adapter supports every parameter; inspect committed values and actual results.

## Image closure

`fiji_image_close_by_id` closes a specific ImageJ image, independently of the active image. Repeated requests reuse its pending run, including while a save prompt is open. `fiji_image_close_status_by_id` distinguishes pending, blocked-by-dialog, closed, cancelled/not-closed and errors. A closed status requires the actual window to disappear.

`discard_changes` defaults to false. Set it true only for disposable temporary images created for the task. Preserve source images and final results. This mechanism does not globally dismiss dialogs or decide whether an unknown image is safe to discard.

No Fiji Chat or Script Editor JAR is replaced. No new API key, model request or scientific segmentation method is introduced by these helpers.
