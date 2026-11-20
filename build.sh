#!/bin/bash
set -euo pipefail
SDK=${ANDROID_SDK:-/opt/android-sdk}
BT=$SDK/build-tools/35.0.0
PLAT=$SDK/platforms/android-35/android.jar
cd "$(dirname "$0")"
rm -rf out gen classes dexout compiled.flata base.apk aligned.apk app-release.apk
mkdir -p gen classes dexout

echo "[1/7] aapt2 compile resources"
"$BT/aapt2" compile --dir res -o compiled.flata

echo "[2/7] aapt2 link"
"$BT/aapt2" link -o base.apk -I "$PLAT" \
  --manifest AndroidManifest.xml -A assets compiled.flata \
  --java gen --min-sdk-version 26 --target-sdk-version 35 \
  --version-code 1 --version-name 1.0

echo "[3/7] javac"
javac --release 17 -nowarn -cp "$PLAT" -d classes $(find src gen -name '*.java')

echo "[4/7] d8"
"$BT/d8" --release --lib "$PLAT" --min-api 26 --output dexout $(find classes -name '*.class')

echo "[5/7] add classes.dex to apk"
python3 -c "
import zipfile,shutil
shutil.copy('base.apk','staged.apk')
z=zipfile.ZipFile('staged.apk','a',zipfile.ZIP_DEFLATED)
z.write('dexout/classes.dex','classes.dex')
z.close()
print('dex added')"

echo "[6/7] zipalign"
"$BT/zipalign" -p -f 4 staged.apk aligned.apk

echo "[7/7] sign"
# Supply your own keystore: KEYSTORE=path KS_ALIAS=alias KSPASS=... ./build.sh
: "${KEYSTORE:?set KEYSTORE to your signing keystore}"; : "${KSPASS:?set KSPASS}"
"$BT/apksigner" sign --ks "$KEYSTORE" --ks-key-alias "${KS_ALIAS:-release}" \
  --ks-pass env:KSPASS --key-pass env:KSPASS --out app-release.apk aligned.apk
"$BT/apksigner" verify app-release.apk
ls -l app-release.apk
