#!/bin/sh
# Builds the addons, starts a real Minecraft client and plays through the in-game tests.
# Without a display (a server, GitHub Actions) the game runs in Xvfb, a virtual screen.
# Only some scenarios:  INGAMETEST_ONLY=hotbar ./run-tests.sh
set -eu
here=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
addons=$(sed -n 's/^addons=//p' "$here/gradle.properties" | tr ',' ' ')
baritone="baritone-api-fabric-$(sed -n 's/^baritone_version=//p' "$here/gradle.properties").jar"

mkdir -p "$here/libs"
[ -f "$here/libs/$baritone" ] || cp "$here/../loadchest/libs/$baritone" "$here/libs/"

for addon in $addons; do
    echo "=== Building $addon"
    [ -f "$here/../$addon/libs/$baritone" ] || cp "$here/libs/$baritone" "$here/../$addon/libs/"
    (cd "$here/../$addon" && sh gradlew clean build)
done

echo "=== Running the in-game tests"
cd "$here"
status=0
if [ -z "${DISPLAY:-}" ]; then
    xvfb-run -a -s "-screen 0 1280x720x24" sh gradlew runClientGameTest || status=$?
else
    sh gradlew runClientGameTest || status=$?
fi
cat "$here/build/run/clientGameTest/ingametest-report.txt" 2>/dev/null || true
exit "$status"
