# Plan: bump-version

- [x] Step 1: Inspect codebase and ideate in notes.md
- [x] Step 2: Update AndroidManifest.xml (versionCode 5, versionName 1.4.0)
- [x] Step 3: Update assets/index.html UI version string and CURRENT_VERSION
- [x] Step 4: Build APK via bash build.sh
- [x] Step 5: Install APK to emulator-5554 (adb -s emulator-5554 install -r vato.apk)
- [x] Step 6: Verify installed version via dumpsys and run test_mobile_app.sh
- [x] Step 7: Commit changes cleanly to master
