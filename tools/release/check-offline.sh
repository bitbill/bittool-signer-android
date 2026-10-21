#!/bin/bash
# Proves a built APK is offline by construction (DECISIONS §6): no network permission, no networking or installer code
# in its bytecode (the R8 output includes every library), no socket calls in its native libraries, no Play services /
# Firebase / Play Core, no in-app updater. Run on every release candidate of both flavors:
#
#   tools/release/check-offline.sh app/build/outputs/apk/apk/release/app-apk-release.apk app/build/outputs/apk/play/release/app-play-release.apk
#
# The Gradle build separately enforces the merged-manifest allowlist (app/build.gradle.kts check*Permissions).
set -u
SDK=${ANDROID_HOME:-$HOME/Library/Android/sdk}
AAPT=$(ls "$SDK"/build-tools/*/aapt2 2>/dev/null | sort -V | tail -1)
fail=0
for apk in "$@"; do
  echo "== $apk"
  work=$(mktemp -d)
  unzip -q -o "$apk" 'classes*.dex' 'lib/*' -d "$work"
  perms=$("$AAPT" dump permissions "$apk" 2>/dev/null | grep -oE "name='[^']+'" | sed "s/name='//; s/'//")
  for p in android.permission.INTERNET android.permission.ACCESS_NETWORK_STATE android.permission.ACCESS_WIFI_STATE \
           android.permission.CHANGE_NETWORK_STATE android.permission.REQUEST_INSTALL_PACKAGES \
           android.permission.BLUETOOTH android.permission.BLUETOOTH_CONNECT android.permission.NFC; do
    if printf '%s\n' "$perms" | grep -qx "$p"; then echo "  FORBIDDEN permission $p"; fail=1; fi
  done
  for t in 'Ljava/net/Socket;' 'Ljava/net/ServerSocket;' 'Ljava/net/DatagramSocket;' 'Ljava/net/URL;' \
           'Ljava/net/URLConnection;' 'Ljava/net/HttpURLConnection;' 'Ljavax/net/ssl/' 'Ljava/net/InetAddress;' \
           'Ljava/nio/channels/SocketChannel;' 'Landroid/net/ConnectivityManager;' 'Landroid/webkit/WebView;' \
           'Landroid/app/DownloadManager;' 'Landroid/content/pm/PackageInstaller;' 'Lokhttp3/' \
           'Lcom/google/android/gms/' 'Lcom/google/firebase/' 'Lcom/google/android/play/'; do
    if cat "$work"/classes*.dex | LC_ALL=C grep -a -q -F "$t"; then echo "  FORBIDDEN class reference $t"; fail=1; fi
  done
  for so in "$work"/lib/*/*.so; do
    syms=$(strings -a "$so" | grep -x -E 'socket|connect|getaddrinfo|gethostbyname|sendto|recvfrom' | sort -u | tr '\n' ' ')
    if [ -n "$syms" ]; then echo "  FORBIDDEN socket symbols in ${so#$work/}: $syms"; fail=1; fi
  done
  [ $fail = 0 ] && echo "  offline: no network permission, no networking code, no socket calls, no updater"
done
exit $fail
