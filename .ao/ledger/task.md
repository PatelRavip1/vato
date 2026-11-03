# Task: bump-version

Update Android app version to 1.4.0:
1. In AndroidManifest.xml: set android:versionCode='5' and android:versionName='1.4.0'.
2. Also check assets/index.html or any UI headers displaying app version and update to 1.4.0 if present.
3. Build the APK with 'bash build.sh'.
4. Install to the running emulator: 'adb -s emulator-5554 install -r vato.apk'.
5. Verify installed version: 'adb -s emulator-5554 shell dumpsys package com.vato.qwenchat | grep -iE versionCode|versionName'.
6. Commit changes cleanly to master.
