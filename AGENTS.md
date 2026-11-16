# Agents

Commands are run from the repository root. The Gradle wrapper is the build. JDK 17. Release version names and version codes are derived in `app/build.gradle.kts` from git tags; do not hand-edit that encoding, the signing config, or `.github/workflows/release.yml`.

## Build

```bash
./gradlew :app:assembleDebug
./gradlew :app:assembleRelease :app:bundleRelease -Papp.allowUnsignedRelease=true
```

`assembleRelease` and `bundleRelease` run R8 and `lintVitalRelease`. Without a release keystore, `-Papp.allowUnsignedRelease=true` signs with the debug key. That output is for verification only.

## Test

```bash
./gradlew :app:testDebugUnitTest -Papp.skipScreenshotTests=true
./gradlew :app:testDebugUnitTest -Papp.skipScreenshotTests=true -Papp.testShard=1/6 -Papp.testForks=1
./gradlew :app:verifyRoborazziDebug --tests '*ScreenshotTest*'
```

Unit tests are JVM tests (Robolectric). Screenshot tests compare against `screenshots/`. Do not use `Thread.sleep` in new tests; drive time with `kotlinx-coroutines-test` (`runTest`, `UnconfinedTestDispatcher`, `advanceTimeBy`).

## Lint and static analysis

```bash
./gradlew :app:lintDebug
./gradlew :app:detekt
./gradlew :app:checkReleasePayloadBudget -Papp.allowUnsignedRelease=true
```

These are blocking. CI's aggregate check is the job named `CI` in `.github/workflows/ci.yml`. It requires Build, Release build (R8), Lint, Detekt, Unit tests (1/6 through 6/6), Screenshot tests, and Proof.

- Lint: `warningsAsErrors` and `abortOnError` are on, and `checkReleaseBuilds` is on. Findings already listed in `app/lint-baseline.xml` are allowed. A new finding fails. Refresh the baseline only when the finding is accepted: `./gradlew :app:updateLintBaseline`.
- Detekt: default rules, `ignoreFailures` off. Findings already listed in `app/detekt-baseline.xml` are allowed. A new finding fails. Refresh with `./gradlew :app:detektBaseline`.
- Payload budget: `app/size-baseline.properties` records `releaseApkBytes` and `releaseAabBytes`. `checkReleasePayloadBudget` fails when either artifact is more than 5% larger. Update the file when a size increase is intentional.

Do not add a `CLAUDE.md`.
