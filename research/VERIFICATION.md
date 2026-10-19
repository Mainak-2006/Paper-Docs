# Build and runtime verification

Environment: Linux, Android Studio JBR 21.0.10, Gradle 8.13, AGP 8.13.2,
Kotlin 2.2.21, Android SDK 36.1, Pixel 10 Pro XL emulator running API 37.

The final verification command and generated reports are documented in the root
README. Tests exercise the independent implementation, not the Google APK.

## Result — 2026-10-18

The checked-in wrapper successfully ran:

```bash
JAVA_HOME=/opt/android-studio/jbr ./gradlew \
  :app:assembleDebug :app:testDebugUnitTest \
  :app:connectedDebugAndroidTest :app:lintDebug --console=plain
```

- Debug APK: **built successfully**.
- JVM tests: **3 passed**.
- On-device tests: **11 passed, 0 failed, 0 skipped**.
- Lint: **passed**, with informational dependency-version warnings.
- The end-to-end UI test writes text through the real native editor, waits for
  its saved indicator, returns to the library, searches/reopens the document,
  recreates the Activity, and verifies the text again.

The verified APK is `app/build/outputs/apk/debug/app-debug.apk`.

Runtime screenshots:

- `library.png`: first-run library, template cards, real document preview.
- `editor.png`: native editor displaying the welcome document and saved state.

The test stack explicitly pins Espresso 3.7.0. Compose's default transitive
Espresso 3.5.0 calls an InputManager method removed in the API 37 emulator; the
newer version resolves that test-runtime incompatibility.

Lint's dependency-update suggestions are informational. The project uses a
compatible pinned toolchain rather than automatically upgrading dependencies.
No lint baseline or error suppression was introduced.

## Cloud features — 2026-10-18

Google sign-in, Firestore sync, live collaboration, Drive backup, and the expanded
DOCX pipeline were added after the offline build above.

```bash
JAVA_HOME=/opt/android-studio/jbr ./gradlew \
  :app:assembleDebug :app:testDebugUnitTest \
  :app:connectedDebugAndroidTest :app:lintDebug --console=plain
```

- Debug APK: built.
- JVM tests: **3 passed** (word count, style validation, hyperlink marks).
- On-device tests: **11 passed, 0 failed**.
- Lint: passed.

New coverage:

- DOCX round-trip preserves headings, restores bullet/number markers from real Word
  numbering definitions, and keeps hyperlink targets across the `document.xml.rels`
  relationship indirection.
- The writer emits `word/numbering.xml`, `word/styles.xml`, `<w:numPr>`, and an
  external hyperlink relationship.
- Sync conflict rules: a newer remote copy is adopted; a simultaneous edit is flagged
  as a conflict with the local version snapshotted; both resolution paths behave.

Not verified here: anything requiring live Firebase credentials. Sign-in, Firestore,
and Drive were implemented and compile-checked but could not be exercised against the
real services, because the project still needs an OAuth client and a registered SHA-1
fingerprint. See `docs/CLOUD_SETUP.md`.
