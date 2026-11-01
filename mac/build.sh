#!/usr/bin/env bash
# Builds "Notif Relay.app" into mac/build. Pass --install to copy it to /Applications and launch it.
set -euo pipefail
cd "$(dirname "$0")"

# Universal binary so the same .app runs on Apple Silicon and Intel Macs.
swift build -c release --arch arm64 --arch x86_64
BIN="$(swift build -c release --arch arm64 --arch x86_64 --show-bin-path)/NotifRelay"
APP="build/Notif Relay.app"
rm -rf "$APP"
mkdir -p "$APP/Contents/MacOS" "$APP/Contents/Resources"
cp "$BIN" "$APP/Contents/MacOS/"
cp Resources/* "$APP/Contents/Resources/"
cp Info.plist "$APP/Contents/"
codesign --force --sign - "$APP"
echo "Built $APP"

if [[ "${1:-}" == "--install" ]]; then
  pkill -x NotifRelay || true
  rm -rf "/Applications/Notif Relay.app"
  cp -R "$APP" /Applications/
  open "/Applications/Notif Relay.app"
  echo "Installed to /Applications"
fi
