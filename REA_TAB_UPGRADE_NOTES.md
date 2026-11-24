# REA tab integration update

This update intentionally changes only the REA workbench screen and the REA-specific Android tool installer:

- `app/src/main/java/com/arin/rea/ReaWorkbenchScreen.kt`
- `app/src/main/java/com/arin/terminal/Linux.kt` (REA Android analysis helper installer only)

The rest of the Arin application source is preserved from v0.9.98.

## REA tab changes

- Adds a visible toolchain/capability status panel.
- Adds a full REA setup/repair action and an on-device diagnostics action.
- Adds guided investigations for APK/split APK, native libraries, Flutter/React Native/web bundles, network captures, feature tracing, and GitHub tool research.
- Keeps the one-APK + plain-language instruction workflow.
- Adds evidence, confidence, original-file preservation, checkpoint, rebuild, signing and validation requirements to the autonomous task prompt.
- Clearly reports phone/desktop provider limitations and signing identity constraints.

## Installer change

The REA-specific Android tools installer now resolves the latest official `jadx-headless-mcp` GitHub release at installation time and verifies the downloaded JAR against the SHA-256 published by that release (asset digest or checksum asset). It refuses an unverified download. The REA npm install already uses `npm install --global rea-agents`, which installs the current npm release when setup runs.

## Verification status

- ZIP integrity: checked after packaging.
- Kotlin syntax scan: no Kotlin parser errors detected.
- Full Android Gradle compilation: not completed in this environment because `services.gradle.org` could not be resolved (network/DNS unavailable). Build on the phone and provide any Gradle/Kotlin error output for a follow-up fix.
- No APK build is claimed by this update.
