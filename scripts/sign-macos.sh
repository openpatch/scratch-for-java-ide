#!/usr/bin/env bash
# Signs and notarizes the macOS DMG when Apple credentials are configured
# (secrets MACOS_CERTIFICATE (base64 .p12), MACOS_CERTIFICATE_PASSWORD,
# MACOS_SIGNING_IDENTITY, APPLE_ID, APPLE_TEAM_ID, APPLE_APP_PASSWORD).
set -euo pipefail
KEYCHAIN="$RUNNER_TEMP/signing.keychain-db"
security create-keychain -p temp "$KEYCHAIN"
security unlock-keychain -p temp "$KEYCHAIN"
echo "$MACOS_CERTIFICATE" | base64 --decode > "$RUNNER_TEMP/cert.p12"
security import "$RUNNER_TEMP/cert.p12" -k "$KEYCHAIN" -P "$MACOS_CERTIFICATE_PASSWORD" -T /usr/bin/codesign
security set-key-partition-list -S apple-tool:,apple: -s -k temp "$KEYCHAIN"
security list-keychains -d user -s "$KEYCHAIN"
for dmg in "$@"; do
  codesign --force --timestamp --options runtime --sign "$MACOS_SIGNING_IDENTITY" "$dmg"
  xcrun notarytool submit "$dmg" --apple-id "$APPLE_ID" --team-id "$APPLE_TEAM_ID" \
    --password "$APPLE_APP_PASSWORD" --wait
  xcrun stapler staple "$dmg"
done
