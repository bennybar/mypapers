# My Papers (iOS, SwiftUI)

A native iOS 26 port of the Android app in `../app` — SwiftUI with Liquid Glass (glass toolbars, the
paper's floating action bar, chips, the add button and search). Same papers, same Google Drive folder:
both apps read and write the same `<id>.json` + `<id>.file` in the Drive app-data folder.

- Bundle id `com.bennybar.mypapers` (share extension `.share`, app group `group.com.bennybar.mypapers`),
  team 57L2ULXB7Z, automatic signing, iOS 26+.
- One dependency: GoogleSignIn-iOS (SPM). Google iOS OAuth client in the same Cloud project as Android.
- Project from `project.yml` (XcodeGen): `xcodegen generate` after adding files.

```sh
xcodegen generate
xcodebuild -scheme MyPapers -destination 'platform=iOS Simulator,name=iPhone 18 Pro' test
# Onto the phone (connected, unlocked):
xcodebuild -scheme MyPapers -destination 'id=<device udid>' -allowProvisioningUpdates build
xcrun devicectl device install app --device <device udid> <DerivedData>/Build/Products/Debug-iphoneos/MyPapers.app
```

Layout: `Model/` (Paper, store, Drive JSON, files), `Logic/` (search, filters, expiry, duplicates, format),
`Sync/` (Google account, Drive client and sync), `AI/` (OpenAI/proxy client, inputs and close-ups, prompts,
jobs), `Gmail/` (scan and import), `UI/` (screens), `MyPapersShare/` (Share → My Papers).

Debug-only launch arguments for simulator checks: `-open paper|settings|gmail|trash`, `-style cards|compact|grid|timeline`.
