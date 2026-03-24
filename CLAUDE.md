# Label Finder

## Versioning

- **versionCode** format: `yymmddNNN` — two-digit year, month, day, then a zero-padded 3-digit build number (e.g. `260324001`)
- **versionName** format: `yyyy.mm.dd.NNN` — full year with dots, same zero-padded 3-digit build number (e.g. `2026.03.24.001`)
- Android versionCode is a 32-bit int (max 2,147,483,647), so the year uses 2 digits in versionCode but 4 in versionName
- Increment the NNN suffix for each release build on the same day

## Release Builds

- Signing config reads keystore passwords from `local.properties` (`KEYSTORE_PASSWORD`, `KEY_PASSWORD`)
- Keystore file: `app/keystore.jks` (gitignored)
- Build command: `.\gradlew.bat bundleRelease`
- Output: `app\build\outputs\bundle\release\app-release.aab`
