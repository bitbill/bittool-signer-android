#!/bin/bash
# Proves a built APK is offline by construction (DECISIONS §6): no network permission, no networking or installer code
# in its bytecode (the R8 output includes every library), no socket calls in its native libraries, no Play services /
# Firebase / Play Core, no in-app updater. Run on every release candidate of both flavors:
#
#   tools/release/check-offline.sh app/build/outputs/apk/apk/release/app-apk-release.apk app/build/outputs/apk/play/release/app-play-release.apk
#
# An App Bundle is checked through its base module in APK layout (tools/release/artifact_checks.py aab-base), which
# tools/release/verify-release.sh does for app-play-release.aab. The Gradle build separately enforces the
# merged-manifest allowlist (app/build.gradle.kts check*Permissions). Exit status 0 only when every APK was read
# (permissions dumped, at least one dex found) and none has a finding.
set -u
SDK=${ANDROID_HOME:-$HOME/Library/Android/sdk}
AAPT=$(ls "$SDK"/build-tools/*/aapt2 2>/dev/null | sort -V | tail -1)
if [ -z "$AAPT" ] || [ ! -x "$AAPT" ]; then
  echo "aapt2 not found under $SDK/build-tools (set ANDROID_HOME)" >&2
  exit 2
fi
[ $# -gt 0 ] || { echo "usage: $0 APK..." >&2; exit 2; }
fail=0
for apk in "$@"; do
  echo "== $apk"
  bad=0
  if [ ! -f "$apk" ]; then echo "  ERROR no such file"; fail=1; continue; fi
  work=$(mktemp -d)
  unzip -q -o "$apk" 'classes*.dex' 'lib/*' -d "$work" 2>/dev/null
  if ! ls "$work"/classes*.dex >/dev/null 2>&1; then echo "  ERROR no classes*.dex in the APK"; bad=1; fi
  if ! dump=$("$AAPT" dump permissions "$apk" 2>&1); then echo "  ERROR aapt2 dump permissions failed: $dump"; bad=1; fi
  perms=$(printf '%s\n' "$dump" | grep -E "^uses-permission" | grep -oE "name='[^']+'" | sed "s/name='//; s/'//")
  for p in android.permission.INTERNET android.permission.ACCESS_NETWORK_STATE android.permission.ACCESS_WIFI_STATE \
           android.permission.CHANGE_NETWORK_STATE android.permission.CHANGE_WIFI_STATE \
           android.permission.REQUEST_INSTALL_PACKAGES android.permission.INSTALL_PACKAGES \
           android.permission.BLUETOOTH android.permission.BLUETOOTH_ADMIN android.permission.BLUETOOTH_CONNECT \
           android.permission.BLUETOOTH_SCAN android.permission.NFC android.permission.NEARBY_WIFI_DEVICES; do
    if printf '%s\n' "$perms" | grep -qx "$p"; then echo "  FORBIDDEN permission $p"; bad=1; fi
  done
  for t in 'Ljava/net/Socket;' 'Ljava/net/ServerSocket;' 'Ljava/net/DatagramSocket;' 'Ljava/net/URL;' \
           'Ljava/net/URLConnection;' 'Ljava/net/HttpURLConnection;' 'Ljavax/net/ssl/' 'Ljava/net/InetAddress;' \
           'Ljava/nio/channels/SocketChannel;' 'Landroid/net/ConnectivityManager;' 'Landroid/webkit/WebView;' \
           'Landroid/app/DownloadManager;' 'Landroid/content/pm/PackageInstaller;' 'Lokhttp3/' \
           'Lcom/google/android/gms/' 'Lcom/google/firebase/' 'Lcom/google/android/play/'; do
    if cat "$work"/classes*.dex 2>/dev/null | LC_ALL=C grep -a -q -F "$t"; then echo "  FORBIDDEN class reference $t"; bad=1; fi
  done
  for so in "$work"/lib/*/*.so; do
    [ -f "$so" ] || continue
    syms=$(strings -a "$so" | grep -x -E 'socket|connect|getaddrinfo|gethostbyname|sendto|recvfrom' | sort -u | tr '\n' ' ')
    if [ -n "$syms" ]; then echo "  FORBIDDEN socket symbols in ${so#$work/}: $syms"; bad=1; fi
  done
  rm -rf "$work"
  if [ $bad = 0 ]; then
    echo "  offline: no network permission, no networking code, no socket calls, no updater"
  else
    fail=1
  fi
done
[ $fail = 0 ] && echo "$# APK(s) offline: no network permission, no networking code, no socket calls, no updater"
exit $fail
