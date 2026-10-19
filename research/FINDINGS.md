# Google Docs Android → Paper Docs implementation notes

## Target and evidence

Inspected 2026-10-18. User supplied an APKM and an extracted split-APK directory
under `~/Downloads/com.google.android.apps.docs.editors.docs/`. The selected
artifact was `base.apk`; split resources and native libraries were not inspected.

| Fact | Source |
| --- | --- |
| Package `com.google.android.apps.docs.editors.docs` | Android SDK `aapt2 dump badging` |
| Version `1.26.411.02.90`, code `220709602` | SDK badging |
| Minimum SDK 32; target/compile SDK 37 | SDK badging |
| SHA-256 `a3255bf6c47356b37eb1a4cd8c9ea108f1fbbe9625da450101505dc56099e2e2` | REA subject identity |
| APK archive inventory completed | REA `ev_e98cc1bc53db71824044adfa2541f1179f580b367689cd1127b8a3fd482e5243` |
| Nested inventory evidence | REA `ev_0380b821e64c906915c232493149a59c08753a6b651f2e6af1e74a07f2298d3f` |

The complete REA output is retained locally as `base-artifact.json`, with a small
reviewable summary in `artifact-summary.json`. `summarize_apk.py` reproduces the
supplemental SDK reports. Those reports are **SDK observations, not REA Evidence**.

REA `inspect-android-package` returned `capability_unavailable`: the separate
`jadx-headless-mcp` 0.7.1 JAR is absent. This blocks package/class/method inspection
through that provider. No methods were decompiled and the Google APK was not run.
Archive inventory alone does not establish application semantics. In particular,
this corrects the earlier assumption that REA manifest inspection needs no JADX.

## Findings ledger

| Question | Evidence and conclusion | Status / limit |
| --- | --- | --- |
| Which main workflows are declared? | SDK manifest lines 274–279 declare `HomescreenActivity` and `TemplatePickerActivity`; 866 declares `KixEditorActivity`. | Answered for static declarations; actual navigation unobserved. |
| Are file operations represented? | Manifest declares `RenameActivity` (991), `MakeACopyDialogActivity` (1204), `ExportDocumentActivity` (1142), `SendAsExportedActivity` (1148), and `PrintActivity` (245). | Declared components only; no inference about their internal implementation. |
| What entry formats are declared? | Manifest lines 879–895 contain VIEW/EDIT filters for DOCX, DOC and Word templates; other import activities declare TXT and ODT. | Static intent filters; conversion fidelity and offline availability unknown. |
| What editing controls are represented? | Resource table names include `action_bar_find_and_replace`, `action_bar_undo`, `format_font_family_label`, `format_font_size_label`, `format_heading_style_label`, and `format_paragraph_heading_label`. | Resource names corroborate concepts; they do not prove screen visibility. |
| What do users actually do? | Official Android help describes creation/templates, formatting, find/replace, local offline saves, export and sharing; links below. | Documented behavior, not runtime evidence of this APK version. |
| What persistence/collaboration algorithm does Google use? | Not established by the available analysis. | Unresolved; no claims about Google internals. |
| What exact visual layout does this APK render? | No authenticated runtime or screenshot evidence collected. | Unresolved; the UI below is an original Docs-inspired design. |

REA limitations retained: central-directory-only ZIP inventory, case-folding name
collisions, and bounded static hypotheses. The base APK contains six DEX files
and 4,541 ZIP members in the supplemental ZIP inventory. Names are not proof of
executed features, security enforcement, or architectural completeness.

## Public behavior references

All are official Google help with the Android platform selected:

- [Create/open/rename/copy/save](https://support.google.com/docs/answer/49114?hl=en&co=GENIE.Platform%3DAndroid)
- [Text and paragraph formatting](https://support.google.com/docs/answer/1663349?hl=en&co=GENIE.Platform%3DAndroid)
- [Heading styles](https://support.google.com/docs/answer/116338?hl=en&co=GENIE.Platform%3DAndroid)
- [Lists](https://support.google.com/docs/answer/3300615?hl=en&co=GENIE.Platform%3DAndroid)
- [Offline documents](https://support.google.com/docs/answer/6388102?hl=en&co=GENIE.Platform%3DAndroid)
- [Find and replace](https://support.google.com/docs/answer/62754?hl=en&co=GENIE.Platform%3DAndroid)
- [Supported formats](https://support.google.com/docs/answer/7068618?hl=en&co=GENIE.Platform%3DAndroid)
- [Trash and restore](https://support.google.com/docs/answer/6023494?hl=en&co=GENIE.Platform%3DAndroid)
- [Sharing roles](https://support.google.com/docs/answer/2494822?hl=en&co=GENIE.Platform%3DAndroid)
- [Comments](https://support.google.com/docs/answer/65129?hl=en&co=GENIE.Platform%3DAndroid)

## Reconstruction choices

Paper Docs uses Kotlin, Jetpack Compose, a native Android rich-text editor,
SQLite transactions, and a versioned span format. These are our implementation
choices, not recovered Google source or architecture. All local documents work
offline. The initial implementation covers the library, templates, text editing,
autosave, local notes and revisions, trash, import and export.

Google account sign-in, Drive synchronization, remote permissions, multiplayer
editing, shared comments, suggestions and Gemini are not established or provided
by this reconstruction. They require a separately integrated backend. The app
labels private notes and on-device storage explicitly and shares actual files.
