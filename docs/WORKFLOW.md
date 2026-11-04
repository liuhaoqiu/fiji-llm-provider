# Workflow tools (0.5.10)

**English** | [简体中文](zh-CN/WORKFLOW.md)

These helpers address observed workflow failures: repeated parameter harvesting, reusing a painted QC image, measuring label numbers as intensities and delivering a final script edited after its successful run. They preserve the provider/model choice and default 1000 rounds. Scientific parameters remain the assistant/user's decision.

## Explicit inputs and a reusable macro

Use `fiji_command_parameters` to discover the exact command class and case-sensitive inputs. `fiji_command_run_with_inputs` accepts `command`, `inputs_json` and `image_id`, initializes the real command, then applies typed explicit values. It does not use input harvesting or persisted-input preprocessing. Poll `fiji_command_inputs_status` until terminal; do not start a duplicate run while pending. A plugin's own internal UI or errors may still appear. Third-party command postprocessors are skipped; inspect outputs and the receipt.

The receipt includes effective inputs, original image ID, before/after pixel hashes, elapsed command time and newly observed image IDs. Elapsed time includes any work/wait inside the command; it is not a GPU-kernel timer. `actual_device=not_reported_by_command` explicitly distinguishes a requested CUDA/GPU configuration from independently confirmed backend hardware.

`fiji_command_macro_template` returns a complete ImageJ macro scaffold for a command that creates exactly one new 2D label image. Its public runtime bridges preserve the active original, copy an analysis channel, run typed inputs, select the sole newly observed image from the receipt, and measure against the original. Run the exact final script before claiming it executed. The scaffold requires this extension 0.5.10 or newer; it is not a plugin-independent macro. It fails explicitly if zero or multiple new images are observed, including concurrent external activity.

For Cellpose v3 the class is `fiji.plugin.appose.cellpose.cp3.CellposeAppose`. Example input names include `cp_model`, `cell_diameter`, `torchVersion`, `useGPU`, and `return_ROIs`; the typed bridge uses Java input names rather than lowercased legacy macro options. Always read installed metadata. The `nuclei` v3 model is distinct from Cellpose-SAM. Select diameter, size and thresholds for the image; this release does not impose a biological cutoff. Set `return_ROIs=false` if ROI export is unnecessary, to avoid appending to an existing ROI Manager through that third-party command.

## Original image and label measurements

`fiji_image_extract_channel` copies a single 2D plane into an independent image. RGB channels are 1=red, 2=green, 3=blue; grayscale uses 1. The source is retained. Extracted channels carry a source hash and their own initial hash; the new analysis helpers reject silently modified extractions and helper-tagged QC displays. Original Fiji tools and arbitrary macros can still modify images, so this is not a universal write lock.

`fiji_label_measure` requires separate `source_image_id`, `label_image_id` and `source_channel`. It counts unique nonzero integer labels, even with gaps in numbering; measures pixel area, raw source intensity, centroids and edge contacts; and applies no area filter. Source and labels must have matching dimensions and one plane. This checks dimensions, not biological alignment.

The tool writes a uniquely named CSV under the local context-archive directory and displays a separate measurement table and QC image. QC boundaries are a non-destructive overlay on a copy. Existing global Results and ROI Manager are retained by this helper. Source-signal fraction and mask-area fraction have distinct denominators; neither is object recall or a guarantee of accuracy. No automated debris removal or biological interpretation is performed.

## Exact script execution evidence

Macro receipts include `executed_source_sha256`. `fiji_script_validation_status` compares active editor bytes with the latest recorded execution for that script in the current JVM session. Editing invalidates a match; failed runs never count as success. The final report displays an independent execution-evidence line when relevant tool evidence is available.

An exact-byte successful run is not proof of replay from a clean image/ROI/Results state or of scientific correctness. Evidence is session-local; after restarting, a script needs a new execution record. Renaming without changing bytes does not invalidate an otherwise matching execution.

## Scope and validation

The new assistant tools apply to the four added providers. The original Fiji Chat and Script Editor JARs are unchanged. Macro bridge entry points require this extension's SciJava context. Tests use synthetic commands/images plus actual Cellpose parameter initialization without inference; the user should repeat a real task to evaluate model tool adoption and scientific performance.
