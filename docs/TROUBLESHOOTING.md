# Troubleshooting

**English** | [简体中文](zh-CN/TROUBLESHOOTING.md)

| Symptom | What to check |
| --- | --- |
| New menus or providers are missing | Confirm Fiji Chat is installed, the extension is in `jars`, exactly one extension version is active, and Fiji was restarted. |
| `NoClassDefFoundError` / `NoSuchMethodError` | Check the verified Fiji Chat and LangChain4j versions. An upstream API change may require rebuilding or adapting the extension. |
| Model list is empty or stale | Run Refresh LLM Models for the correct service, unlock its key vault and inspect the refresh result. A stale cache does not mean a removed model is still callable. |
| A password dialog appears | This is Fiji Chat's vault unlock workflow. Use the vault password, not the API key. Canceling should end the command. |
| HTTP 401 | Verify the provider-specific API key and the selected service. |
| HTTP 403 | Check account/model permissions. A visible catalog does not prove inference access. |
| Model not found | Refresh the list; for Ark, verify the exact model or inference endpoint ID. |
| Model rejects tools or images | Choose a model with the necessary documented capabilities. Not every catalog entry is a chat or vision model. |
| `request timed out` / HTTP 504 | Check provider status and network connectivity. The configured client timeout does not override upstream server limits. |
| `exceeded ... tool calling round trips` | Inspect LLM Execution Settings. Existing saved values override the 1000-round default. Save a new value and reopen Fiji Chat. |
| Execution settings report NOT ACTIVE | Another service may have taken priority, or the extension may be incompatible with the updated core. Recheck installation and run verification. |

## After a failed long task

Save any useful script still open in the editor. This extension does not add
failed-turn persistence or automatic resumption. Attach the current script and
describe the remaining work in a new message. A new message gets a new round
budget; it does not automatically undo tools that already ran.

## If an input dialog is not visible

Look for another Fiji-owned window or a dialog hidden behind other windows.
Cancel the pending command with Escape if necessary, then reopen its menu.
Avoid terminating Fiji while images or scripts have unsaved changes.

## Reporting an issue

Include the extension version, Fiji/ImageJ and Java versions, Fiji Chat and
LangChain4j JAR versions, selected provider/model, a short reproduction and the
relevant error text. Redact API keys, vault passwords and private paths. Share
synthetic images or a minimal macro when possible. Raw conversation exports may
contain image data or private task content and are usually unnecessary.
