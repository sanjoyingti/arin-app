# REA Workbench (v0.9.97)

Open **Settings → Tools → Open REA Workbench**. Choose one of the guided workflows, enter a target path or feature description, and tap **Run this investigation**. Arin opens the dedicated **Reverse engineering** chat with the REA MCP tools enabled. Attach APKs, source ZIPs, or supported capture files in that chat using the attachment button. Attachments are copied into the app's REA-accessible files area.

## Workflows

- Inspect an APK: manifest, signature, resources, classes, strings and supported JADX analysis.
- Analyze an Android project: source/Gradle inventory, architecture and build blockers.
- Review network captures: only formats and content actually supported by installed REA tools; TLS-encrypted traffic remains metadata-only.
- Trace and recreate a feature: evidence-led analysis followed by an independent implementation plan.
- Research GitHub implementations: use current web search, prioritize official upstream projects, check release status, license and Android/ARM64 compatibility, then inspect candidate source with REA.

## Setup and device limitations

Use **Install and verify REA** from the workbench or **Settings → Linux → Install Linux + REA**. The bundled setup installs Node.js/REA plus Android helpers (JDK/JADX/Apktool where available) in the app's PRoot Linux. Check the live setup log and the REA tool connection status; a UI button is not proof that a tool works. APK static analysis may work on supported ARM64 PRoot configurations, but native analysis providers such as Ghidra/IDA/Hopper, ADB device access, live browser instrumentation and some capture workflows may require desktop software or additional setup.

REA reports evidence and limitations; it does not restore original protected source code or decrypt TLS sessions. Do not use it to extract credentials, defeat DRM, bypass subscriptions/authentication, or modify server-side entitlements. For a private service, use documented APIs and credentials supplied by its owner.
