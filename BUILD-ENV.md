# BUILD-ENV — ReTranslate (fresh-start) toolchain

Everything lives under `~/workspace/tools/` (survives VM restarts). Nothing via apt.
Adapted from the parked project's proven recipe (`translate-you-fork/TranslateYou-Novels/BUILD-ENV.md`).

## Versions (pinned per PLAN.md §2 — upgrade only between phases)
- JDK: Eclipse Temurin **17.0.21+9** at `~/workspace/tools/jdk17`
- Android SDK root: `~/workspace/tools/android-sdk`
  - platform **android-36** (r2, installed 2026-10-27 from `platform-36_r02.zip` via curl — sdkmanager cannot download in this VM; see the old BUILD-ENV for why) + android-37.0 also present
  - build-tools **36.0.0** / 37.0.0, platform-tools 37.0.1
- Gradle **8.11.1** (project wrapper; distribution seeded into GRADLE_USER_HOME via curl —
  hash dir `wrapper/dists/gradle-8.11.1-bin/bpt9gzteqjrbo1mjrsomdt32c` — plus a standalone
  copy at `~/workspace/tools/gradle-8.11.1`)
- AGP **8.7.3**, Kotlin **2.0.21** (Compose compiler plugin), Compose BOM **2024.12.01**
- Project: compileSdk/targetSdk **36**, minSdk **26**, applicationId `app.retranslate`
- GRADLE_USER_HOME: `~/workspace/tools/gradle-home` — carries the egress-proxy systemProps
  and `-Xmx2g`. **If the proxy password rotates, update that file** (current value lives in
  the VM env vars `http_proxy`/`https_proxy`).

## Build (exact)
```bash
cd ~/workspace/fresh-start/ReTranslate
export JAVA_HOME=~/workspace/tools/jdk17
export ANDROID_HOME=~/workspace/tools/android-sdk
export ANDROID_SDK_ROOT=~/workspace/tools/android-sdk
export GRADLE_USER_HOME=~/workspace/tools/gradle-home
export JAVA_TOOL_OPTIONS="-Djava.net.preferIPv6Addresses=true"
./gradlew --no-daemon :app:assembleDebug
./gradlew --no-daemon :app:testDebugUnitTest
```

## Sandbox quirks (do not rediscover these the hard way)
1. **IPv6 loopback:** the sandbox resets IPv4 loopback, which kills Gradle daemon dispatch;
   `JAVA_TOOL_OPTIONS="-Djava.net.preferIPv6Addresses=true"` (above) routes JVMs via ::1.
2. **Egress CA rotates on every VM restart** — re-import before the first build on a fresh VM:
   ```bash
   export JAVA_HOME=~/workspace/tools/jdk17
   $JAVA_HOME/bin/keytool -delete -alias hatch-egress-ca -keystore $JAVA_HOME/lib/security/cacerts -storepass changeit 2>/dev/null
   $JAVA_HOME/bin/keytool -importcert -trustcacerts -noprompt -alias hatch-egress-ca \
     -file /usr/local/share/ca-certificates/hatch-egress-ca.crt \
     -keystore $JAVA_HOME/lib/security/cacerts -storepass changeit
   ```
   (The keytool "use -cacerts" warning is harmless.)
3. **/tmp is a 512 MB tmpfs** — stage downloads in `~/workspace/tools/staging`, never /tmp.
4. RAM ≈ 8 GB: always `--no-daemon`, 2 GB heap cap (already in gradle-home/gradle.properties).
5. Also required: Muse web app → Settings → Permissions → Direct network protocols →
   "Other TCP connections" = ON (set by the user 2026-10-26).
