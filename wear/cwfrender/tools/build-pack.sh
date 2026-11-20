#!/bin/sh
# ============================================================================================
# PERSONAL PATCH - not part of upstream AAPS (branch local/personal, see .github/README.md).
# ============================================================================================
# Builds Pack (https://github.com/google/pack, Apache-2.0), the tool that turns a folder with an
# AndroidManifest.xml and res/ into an aligned and signed APK, with resources.arsc and string
# resources. The converter of designs to Watch Face Format faces (WffConvertRun) uses it on a dev
# machine. Nothing of it goes into the apps.
#
# Needs: git, and Rust (cargo) in ~/.cargo/bin or on the PATH. Pack's build needs protoc: on an
# Apple silicon Mac this script fetches the official release and checks its digest; elsewhere set
# PROTOC to a protoc binary.
#
# usage: sh wear/cwfrender/tools/build-pack.sh      prints the path of pack-cli on its last line;
#        the signing key of this machine is next to it, in dev-key.pem of the cache folder
set -e
PACK_COMMIT=7b60931e4058d892344ac57e49fcb582fd349b76
PROTOC_VERSION=36.2
PROTOC_SHA256_OSX_ARM=9cd98a532c5c5e0c4161314de0225de27e4c8a323917b6ea7b1b714d3ae23466
DIR="${AAPS_WFF_CACHE:-$HOME/.cache/aaps-wff}"
CARGO="$(command -v cargo || echo "$HOME/.cargo/bin/cargo")"
[ -x "$CARGO" ] || { echo "Rust is needed: install it with rustup (https://rustup.rs)"; exit 1; }
mkdir -p "$DIR"

if [ -z "$PROTOC" ]; then
    if [ "$(uname -s)-$(uname -m)" != "Darwin-arm64" ]; then echo "Set PROTOC to a protoc binary"; exit 1; fi
    PROTOC="$DIR/protoc-$PROTOC_VERSION/bin/protoc"
    if [ ! -x "$PROTOC" ]; then
        ZIP="$DIR/protoc-$PROTOC_VERSION.zip"
        curl -sL -o "$ZIP" "https://github.com/protocolbuffers/protobuf/releases/download/v$PROTOC_VERSION/protoc-$PROTOC_VERSION-osx-aarch_64.zip"
        echo "$PROTOC_SHA256_OSX_ARM  $ZIP" | shasum -a 256 -c - >/dev/null || { rm -f "$ZIP"; echo "protoc download does not match its digest"; exit 1; }
        unzip -q -o "$ZIP" -d "$DIR/protoc-$PROTOC_VERSION"
        rm -f "$ZIP"
    fi
fi

SRC="$DIR/pack"
[ -d "$SRC/.git" ] || git clone -q https://github.com/google/pack.git "$SRC"
if [ "$(git -C "$SRC" rev-parse HEAD)" != "$PACK_COMMIT" ]; then
    git -C "$SRC" fetch -q origin
    git -C "$SRC" checkout -q "$PACK_COMMIT"
fi
PROTOC="$PROTOC" "$CARGO" build -q --release --manifest-path "$SRC/Cargo.toml" -p pack-cli

# A signing key for the faces built on this machine, made once, so a face built again can update
# the one installed on the emulator. Pack reads the certificate and the key from one PEM file.
KEY="$DIR/dev-key.pem"
if [ ! -f "$KEY" ]; then
    openssl req -x509 -newkey rsa:2048 -nodes -days 10000 -subj "/CN=AAPS WFF dev" -keyout "$DIR/dev-key.tmp" -out "$DIR/dev-cert.tmp" 2>/dev/null
    cat "$DIR/dev-cert.tmp" "$DIR/dev-key.tmp" > "$KEY"
    rm -f "$DIR/dev-key.tmp" "$DIR/dev-cert.tmp"
fi
echo "$SRC/target/release/pack-cli"
