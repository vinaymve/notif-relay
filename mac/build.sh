#!/usr/bin/env bash
# Builds "Notif Relay.app" into mac/build. Pass --install to copy it to /Applications and launch it.
set -euo pipefail
cd "$(dirname "$0")"

swift build -c release
APP="build/Notif Relay.app"
rm -rf "$APP"
mkdir -p "$APP/Contents/MacOS"
cp .build/release/NotifRelay "$APP/Contents/MacOS/"
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
