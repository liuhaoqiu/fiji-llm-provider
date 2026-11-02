# Changelog

**English** | [简体中文](CHANGELOG.zh-CN.md)

## 0.5.1 — 2026-11-03

- Raise the default tool-calling budget from 300 to **1000 rounds per user message**
  for DeepSeek, SiliconFlow, Volcengine and NVIDIA.
- Keep explicitly saved custom limits; an upgrade does not overwrite them.
- Verify streaming completion after 1000 tool rounds and stopping before round 1001.
- Include English and Simplified Chinese documentation with language-switch links.
- License original contributions under PolyForm Noncommercial 1.0.0 and retain
  upstream BSD notices; include all notices in source and binary distributions.
- Make the Windows build accept the user's Fiji/JDK paths instead of a developer's
  fixed installation path. Production integration behavior otherwise matches 0.5.0.

## 0.5.0

- Add a higher-priority SciJava assistant execution service for the four providers.
- Add LLM Execution Settings with a default of 300 rounds and a saved custom limit.
- Preserve core tool handlers, image handling and conversation memory; delegate
  unrelated providers to the original Fiji Chat service.

## 0.4.0

- Add NVIDIA Chat Completions support and dynamic model discovery.
- Keep all four providers in one extension JAR.

## Earlier development

- Add DeepSeek, SiliconFlow and Volcengine support, dynamic catalogs and Ark
  manual endpoint selection.
- Correct key-manager future handling, empty-catalog startup behavior, multimodal
  token counting, model metadata parsing and client timeout configuration.
