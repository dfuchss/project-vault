#!/usr/bin/env bash
# Wrap the Compose/jpackage app-image (bin/<launcher> + lib/) into a portable AppImage.
# jpackage itself only emits .deb/.rpm on Linux, so we assemble an AppDir and run
# appimagetool. Runs on CI (no FUSE) via --appimage-extract-and-run.
set -euo pipefail

APP_NAME="Project Vault"
APP_IMAGE_DIR="app/build/compose/binaries/main/app/${APP_NAME}"
APPDIR="build/ProjectVault.AppDir"

# appimagetool and the produced AppImage must match the build host's arch. CI's
# ubuntu-latest is x86_64; this also works on aarch64 runners/containers.
ARCH="$(uname -m)"
OUT="dist/ProjectVault-linux-${ARCH}.AppImage"

if [ ! -d "$APP_IMAGE_DIR" ]; then
  echo "error: app-image not found at $APP_IMAGE_DIR (run :app:createDistributable first)" >&2
  exit 1
fi

mkdir -p dist build
rm -rf "$APPDIR"
mkdir -p "$APPDIR"

# The jpackage app-image (bin/, lib/) becomes the AppDir payload.
cp -a "${APP_IMAGE_DIR}/." "$APPDIR/"

# AppRun launches the jpackage launcher, resolving the real path when mounted.
cat > "$APPDIR/AppRun" <<EOF
#!/bin/bash
HERE="\$(dirname "\$(readlink -f "\$0")")"
exec "\$HERE/bin/${APP_NAME}" "\$@"
EOF
chmod +x "$APPDIR/AppRun"

# Desktop entry + icon at the AppDir root are required by appimagetool.
cp app/icons/app-icon.png "$APPDIR/project-vault.png"
cat > "$APPDIR/project-vault.desktop" <<'EOF'
[Desktop Entry]
Type=Application
Name=Project Vault
Comment=Local-first personal finance analyzer
Exec=project-vault
Icon=project-vault
Categories=Office;Finance;
Terminal=false
EOF

# Fetch appimagetool once (arch-matched).
TOOL="build/appimagetool"
if [ ! -x "$TOOL" ]; then
  curl -fsSL -o "$TOOL" \
    "https://github.com/AppImage/appimagetool/releases/download/continuous/appimagetool-${ARCH}.AppImage"
  chmod +x "$TOOL"
fi

# --appimage-extract-and-run avoids needing FUSE (unavailable on CI runners/containers).
ARCH="$ARCH" "$TOOL" --appimage-extract-and-run "$APPDIR" "$OUT"
echo "Built $OUT"
