#!/bin/bash
#
# Give a jpackage-built DMG our own artwork instead of the stock Java icons.
#
#   set-dmg-icon.sh <path/to/foo.dmg> <path/to/icon.icns>
#
# jpackage only honours `--icon` for the .app *inside* the image. The disk image itself
# gets two other icons that `--icon` never touches:
#
#   1. the mounted volume's icon (Finder sidebar / desktop / the install window). jpackage
#      copies its bundled JavaApp.icns into the volume as `.VolumeIcon.icns`, so the
#      install window a user sees is branded with a Java coffee cup. Overriding it the
#      supported way needs a `<PackageName>-volume.icns` in jpackage's `--resource-dir`,
#      but the Compose plugin owns that directory and wipes it on every run
#      (AbstractJPackageTask.prepareWorkingDir), so there is no hook to drop a file in.
#      We patch the finished image instead.
#   2. the .dmg *file's* icon in Finder. Nothing sets this at all.
#
# Both are fixed here. Note the file icon (2) lives in the file's resource fork, so it
# survives a local copy but NOT an HTTP upload/download — a DMG pulled from a GitHub
# release shows the generic disk-image icon again. The volume icon (1) is real content
# inside the image and always survives; that is the one users actually see.
#
set -euo pipefail

dmg=$1
icns=$2

[[ -f $dmg ]] || { echo "set-dmg-icon: no such DMG: $dmg" >&2; exit 1; }
[[ -f $icns ]] || { echo "set-dmg-icon: no such icon: $icns" >&2; exit 1; }

# Work next to the DMG so the final swap is a rename on the same volume, not a 300MB copy.
work=$(mktemp -d "${dmg%.dmg}.iconwork.XXXXXX")
mnt="$work/mnt"
shadow="$work/shadow"

cleanup() {
  # Best-effort: an already-detached volume is fine, a busy one gets forced.
  if [[ -d $mnt ]]; then
    hdiutil detach "$mnt" -quiet 2>/dev/null || hdiutil detach "$mnt" -force -quiet 2>/dev/null || true
  fi
  rm -rf "$work"
}
trap cleanup EXIT

mkdir -p "$mnt"

# A shadow file makes the read-only compressed image writable without unpacking it first:
# our writes land in the shadow, and `hdiutil convert -shadow` merges them in one pass.
hdiutil attach "$dmg" -owners off -nobrowse -noverify -shadow "$shadow" -mountpoint "$mnt" -quiet

volume_icon="$mnt/.VolumeIcon.icns"
if [[ ! -f $volume_icon ]]; then
  echo "set-dmg-icon: $dmg has no .VolumeIcon.icns — did jpackage stop writing one?" >&2
  exit 1
fi

# Overwrite in place rather than `cp`: that keeps the existing com.apple.FinderInfo
# ('icnC' creator) on the file and the custom-icon flag on the volume root, both of which
# jpackage already set via SetFile. Replacing the file would drop them and we'd need the
# Xcode command line tools to put them back.
chmod u+w "$volume_icon"
cat "$icns" > "$volume_icon"

# Detach before converting; Spotlight/fseventsd can hold the volume briefly.
for attempt in 1 2 3; do
  if hdiutil detach "$mnt" -quiet 2>/dev/null; then break; fi
  [[ $attempt -eq 3 ]] && hdiutil detach "$mnt" -force -quiet
  sleep 1
done
rmdir "$mnt" 2>/dev/null || true

# UDZO == "UDIF read-only compressed (zlib)", the format jpackage ships.
hdiutil convert "$dmg" -shadow "$shadow" -format UDZO -o "$work/out.dmg" -quiet
mv -f "$work/out.dmg" "$dmg"

# Finally the .dmg file's own Finder icon. NSWorkspace writes the resource fork and sets the
# custom-icon flag for us, so this needs no Xcode tooling. Best-effort on purpose: it needs an
# AppKit-capable session, which a CI runner may not have, and the resource fork it writes is
# stripped by artifact upload anyway — not worth failing a release build over.
if ! DMG_PATH=$dmg ICNS_PATH=$icns osascript -l JavaScript -e '
  ObjC.import("AppKit");
  var env = $.NSProcessInfo.processInfo.environment;
  var dmg = ObjC.unwrap(env.objectForKey("DMG_PATH"));
  var icns = ObjC.unwrap(env.objectForKey("ICNS_PATH"));
  var image = $.NSImage.alloc.initWithContentsOfFile(icns);
  if (!image.js) throw new Error("could not read icon: " + icns);
  if (!$.NSWorkspace.sharedWorkspace.setIconForFileOptions(image, dmg, 0)) {
    throw new Error("could not set Finder icon on: " + dmg);
  }
' >/dev/null 2>&1; then
  echo "set-dmg-icon: warning — could not set the Finder icon on the .dmg file (volume icon is set)" >&2
fi

echo "set-dmg-icon: branded $(basename "$dmg")"
