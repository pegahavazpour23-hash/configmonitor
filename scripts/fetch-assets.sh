#!/usr/bin/env bash
# Downloads what is NOT stored in the repo:
#   1) libv2ray.aar  - the xray core (needed for the "accurate" test)  -> app/libs/
#   2) fonts         - Vazirmatn, Inter, JetBrains Mono (optional; the app falls back to system fonts)
set -euo pipefail
cd "$(dirname "$0")/.."
mkdir -p app/libs app/src/main/assets/fonts

auth=()
if [ -n "${GITHUB_TOKEN:-}" ]; then auth=(-H "Authorization: Bearer ${GITHUB_TOKEN}"); fi

echo "==> xray core (libv2ray.aar)"
if [ -f app/libs/libv2ray.aar ]; then
  echo "    already present"
else
  api="https://api.github.com/repos/2dust/AndroidLibXrayLite/releases/latest"
  json="$(curl -fsSL --retry 3 ${auth[@]+"${auth[@]}"} "$api")"
  url="$(printf '%s' "$json" | grep -o '"browser_download_url": *"[^"]*libv2ray\.aar"' | head -n1 | sed 's/.*"\(https[^"]*\)"$/\1/')"
  if [ -z "$url" ]; then
    url="$(printf '%s' "$json" | grep -o '"browser_download_url": *"[^"]*\.aar"' | head -n1 | sed 's/.*"\(https[^"]*\)"$/\1/')"
  fi
  if [ -z "$url" ]; then
    echo "!! no .aar found in the latest AndroidLibXrayLite release."
    echo "   Download libv2ray.aar manually from https://github.com/2dust/AndroidLibXrayLite/releases"
    echo "   and put it in app/libs/"
    exit 1
  fi
  echo "    downloading $url"
  curl -fL --retry 3 -o app/libs/libv2ray.aar "$url"
fi

fetch_font() {  # fetch_font <file-name> <url> [<fallback-url> ...]
  local name="$1"; shift
  local out="app/src/main/assets/fonts/$name"
  if [ -s "$out" ]; then echo "    $name already present"; return 0; fi
  for u in "$@"; do
    if curl -fsSL --retry 2 -o "$out.tmp" "$u" && [ "$(wc -c < "$out.tmp")" -gt 50000 ]; then
      mv "$out.tmp" "$out"; echo "    $name <- $u"; return 0
    fi
  done
  rm -f "$out.tmp"
  echo "    !! could not download $name (the app will use a system font instead)"
  return 0
}

echo "==> fonts"
fetch_font Vazirmatn.ttf \
  "https://github.com/google/fonts/raw/main/ofl/vazirmatn/Vazirmatn%5Bwght%5D.ttf" \
  "https://github.com/rastikerdar/vazirmatn/raw/master/fonts/variable/Vazirmatn%5Bwght%5D.ttf"
fetch_font Inter.ttf \
  "https://github.com/google/fonts/raw/main/ofl/inter/Inter%5Bopsz%2Cwght%5D.ttf" \
  "https://github.com/rsms/inter/raw/master/docs/font-files/InterVariable.ttf"
fetch_font JetBrainsMono.ttf \
  "https://github.com/google/fonts/raw/main/ofl/jetbrainsmono/JetBrainsMono%5Bwght%5D.ttf" \
  "https://github.com/JetBrains/JetBrainsMono/raw/master/fonts/variable/JetBrainsMono%5Bwght%5D.ttf"

echo "done."
