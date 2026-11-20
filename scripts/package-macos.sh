#!/bin/bash
set -euo pipefail

if [[ "$(uname -s)" != Darwin || "$(uname -m)" != arm64 || $# -ne 1 ]]; then
    echo "Usage on Apple Silicon: bash scripts/package-macos.sh path/to/VNCL.jar" >&2
    exit 1
fi

jar=$1
repo=$(cd -- "$(dirname -- "$0")/.." && pwd)
output="$repo/dist/macos-arm64"
version=$(/usr/bin/unzip -p "$jar" META-INF/MANIFEST.MF |
    /usr/bin/awk '/^Implementation-Version: / { sub(/^Implementation-Version: /, ""); match($0, /^[0-9]+(\.[0-9]+){0,2}/); print substr($0, 1, RLENGTH) }')
if [[ -z "$version" ]]; then
    echo "The launcher JAR has no numeric version in its manifest." >&2
    exit 1
fi

mkdir -p "$output"
if [[ -e "$output/VNCL.app" || -L "$output/VNCL.app" ]]; then
    echo "Move the existing $output/VNCL.app before packaging." >&2
    exit 1
fi
stage=$(mktemp -d "$output/.vncl-package.XXXXXX")
trap 'rm -rf -- "$stage"' EXIT
app="$stage/VNCL.app"
contents="$app/Contents"
mkdir -p "$contents/MacOS" "$contents/Resources" "$stage/VNCL.iconset"
cp "$jar" "$contents/Resources/VNCL.jar"

# Keep the bundle artwork for Finder and the Dock when the app is closed.
source_icon="$stage/icon-mac.png"
/usr/bin/unzip -p "$contents/Resources/VNCL.jar" assets/img/icon-mac.png > "$source_icon"
for size in 16 32 128 256 512; do
    /usr/bin/sips -z "$size" "$size" "$source_icon" \
        --out "$stage/VNCL.iconset/icon_${size}x${size}.png" >/dev/null
    /usr/bin/sips -z "$((size * 2))" "$((size * 2))" "$source_icon" \
        --out "$stage/VNCL.iconset/icon_${size}x${size}@2x.png" >/dev/null
done
/usr/bin/iconutil -c icns "$stage/VNCL.iconset" -o "$contents/Resources/VNCL.icns"

# JavaFX sets the running Dock icon directly. Add the inset seen on the closed
# app icon (54 px out of 66 px) to the runtime artwork's 512 px canvas.
/usr/bin/sips -z 416 416 "$source_icon" --out "$stage/dock-icon.png" >/dev/null
/usr/bin/sips -p 512 512 "$stage/dock-icon.png" \
    --out "$contents/Resources/VNCL-dock.png" >/dev/null

cat > "$contents/Info.plist" <<PLIST
<?xml version="1.0" encoding="UTF-8"?>
<!DOCTYPE plist PUBLIC "-//Apple//DTD PLIST 1.0//EN" "https://www.apple.com/DTDs/PropertyList-1.0.dtd">
<plist version="1.0">
<dict>
    <key>CFBundleExecutable</key><string>VNCL</string>
    <key>CFBundleIdentifier</key><string>org.vncl.launcher</string>
    <key>CFBundleName</key><string>VNCL</string>
    <key>CFBundlePackageType</key><string>APPL</string>
    <key>CFBundleIconFile</key><string>VNCL.icns</string>
    <key>CFBundleShortVersionString</key><string>$version</string>
    <key>CFBundleVersion</key><string>$version</string>
    <key>LSRequiresNativeExecution</key><true/>
    <key>NSHighResolutionCapable</key><true/>
</dict>
</plist>
PLIST

cat > "$contents/MacOS/VNCL" <<'LAUNCHER'
#!/bin/bash
set -euo pipefail

show_error() {
    echo "$1" >&2
    /usr/bin/osascript - "$1" <<'APPLESCRIPT'
on run argv
    display alert "VNCL" message (item 1 of argv) as critical
end run
APPLESCRIPT
    exit 1
}

java_home=${HMCL_JAVA_HOME:-}
if [[ -z "$java_home" ]]; then
    java_home=$(/usr/libexec/java_home -F -a arm64 -v 21 2>/dev/null ||
        /usr/libexec/java_home -F -a arm64 2>/dev/null || true)
fi
if [[ -z "$java_home" || ! -x "$java_home/bin/java" ]] ||
    ! /usr/bin/arch -arm64 "$java_home/bin/java" -XshowSettings:properties -version 2>&1 |
        /usr/bin/awk '$1 == "os.arch" { arm = ($3 == "aarch64" || $3 == "arm64") }
                      $1 == "java.specification.version" { supported = ($3 + 0 >= 17) }
                      END { exit !(arm && supported) }'; then
    show_error "请安装 Apple Silicon（ARM64）版 Java 17 或以上版本，推荐 Java 21。"
fi

resources=$(cd -- "$(dirname -- "$0")/../Resources" && pwd)
data_dir=${HMCL_USER_HOME:-"$HOME/Library/Application Support/vncl"}
if ! mkdir -p "$data_dir" || ! cd -- "$data_dir"; then
    show_error "无法打开 VNCL 数据目录：$data_dir"
fi

exec /usr/bin/arch -arm64 "$java_home/bin/java" -Xdock:name=VNCL -Xdock:icon="$resources/VNCL-dock.png" \
    "-Dvncl.videos.dir=$PWD/videos" -jar "$resources/VNCL.jar" "$@"
LAUNCHER

chmod 755 "$contents/MacOS/VNCL"
/usr/bin/plutil -lint "$contents/Info.plist"
/usr/bin/codesign --sign - "$app"
/usr/bin/codesign --verify --strict "$app"
mv "$app" "$output/"
echo "$output/VNCL.app"
