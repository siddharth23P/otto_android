#!/usr/bin/env bash
# The emulator smoke test (#14), run inside reactivecircus/android-emulator-runner.
#   $1: the build to test -- debug, or minified (release, shrunk, debug-signed)
# A scripted turn end to end (app/src/androidTest/.../SmokeTest.kt), a process death mid-turn and the
# notice after it. The APK's size is recorded here; its budget is release.yml's, on the release APKs.
# Logcat is kept for the artifact.
set -euo pipefail
build=${1:-debug}
pkg=dev.otto.phone
runner=$pkg.test/androidx.test.runner.AndroidJUnitRunner
cls=$pkg.SmokeTest
task=$(tr '[:lower:]' '[:upper:]' <<< "${build:0:1}")${build:1}
mkdir -p smoke

./gradlew --no-daemon -PembeddedPython=true -Pabi=x86_64 -PtestBuildType="$build" "install$task" "install${task}AndroidTest"

size=$(stat -c %s "app/build/outputs/apk/$build/app-$build.apk")
echo "APK ($build, x86_64): $((size / 1048576)) MB" | tee -a smoke/budgets.txt

service_on() {
  adb shell settings put secure enabled_accessibility_services "$pkg/$pkg.access.OttoAccessibilityService"
  adb shell settings put secure accessibility_enabled 1
}

# am instrument always exits 0: read its report.
instrument() {
  local out
  out=$(adb shell am instrument -w -r "$@" "$runner" | tee -a smoke/instrument.txt)
  if grep -q "FAILURES!!!\|INSTRUMENTATION_FAILED\|Process crashed" <<< "$out" || ! grep -q "INSTRUMENTATION_CODE: -1" <<< "$out"; then
    echo "::error::instrumentation failed: $*"
    return 1
  fi
}

adb logcat -c
service_on
status=0
instrument -e class "$cls#aScriptedTurnReadsThePhoneAsksAndAnswers" || status=1
adb logcat -d -s OttoSmoke | grep budget | tee -a smoke/budgets.txt || true

# Process death: the first run starts a slow turn and ends, which kills the app mid-turn.
if [ $status -eq 0 ]; then
  instrument -e otto.death start -e class "$cls#aSlowTurnStartsAndIsLeftRunning" || status=1
  adb shell am force-stop "$pkg"
  service_on
  instrument -e otto.death check -e class "$cls#theNextStartSaysTheTurnWasInterrupted" || status=1
fi

adb shell dumpsys accessibility > smoke/accessibility.txt || true
adb shell settings get secure enabled_accessibility_services >> smoke/accessibility.txt || true
adb logcat -d -v time -s OttoDevice OttoGuard OttoLink OttoTransport OttoEvent OttoOverlay OttoSmoke \
  python.stderr python.stdout AndroidRuntime TestRunner > smoke/logcat.txt || true
exit $status
