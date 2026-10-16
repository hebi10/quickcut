#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd)"
CACHE="${QUICKCUT_NATIVE_CACHE:-$ROOT/build/native-smartcut}"
cd "$ROOT"
MANIFEST="$ROOT/scripts/native-smartcut-sources.json"
CRATE="$ROOT/native/quickcut-smartcut/Cargo.toml"
HOST_TESTS=false
if [[ "${1:-}" == --host-tests ]]; then
  HOST_TESTS=true
elif [[ $# -ne 0 ]]; then
  echo "Usage: $0 [--host-tests]" >&2
  exit 2
fi
for tool in python3 make nasm pkg-config rustup cargo; do
  command -v "$tool" >/dev/null || { echo "Missing build tool: $tool" >&2; exit 1; }
done
[[ -f "$CRATE" && -f "${CRATE%/*}/Cargo.lock" ]] || { echo "Native crate and locked dependencies are required" >&2; exit 1; }
read -r NDK_VERSION RUST_VERSION API < <(python3 - "$MANIFEST" <<'PYCODE'
import json, sys
p=json.load(open(sys.argv[1]))['toolchains']
print(p['ndk'], p['rust'], p['androidApi'])
PYCODE
)
NDK="${ANDROID_NDK_HOME:-${ANDROID_HOME:-${ANDROID_SDK_ROOT:-}}/ndk/$NDK_VERSION}"
[[ -f "$NDK/source.properties" ]] || { echo "Install Android NDK $NDK_VERSION or set ANDROID_NDK_HOME" >&2; exit 1; }
grep -Fq "Pkg.Revision = $NDK_VERSION" "$NDK/source.properties" || { echo "Android NDK version mismatch" >&2; exit 1; }
TOOLCHAIN="$NDK/toolchains/llvm/prebuilt/linux-x86_64"
[[ -x "$TOOLCHAIN/bin/clang" ]] || { echo "This script requires the Linux x86_64 NDK" >&2; exit 1; }
rustup toolchain install "$RUST_VERSION" --profile minimal --target aarch64-linux-android --target x86_64-linux-android
export RUSTUP_TOOLCHAIN="$RUST_VERSION"
mkdir -p "$CACHE/downloads" "$CACHE/sources" "$CACHE/logs"
python3 - "$MANIFEST" "$CACHE" <<'PYCODE'
import hashlib, json, pathlib, tarfile, urllib.request, sys
manifest=json.load(open(sys.argv[1])); cache=pathlib.Path(sys.argv[2])
for key in ('ffmpeg', 'x264'):
    item=manifest[key]; archive=cache/'downloads'/item['archive']
    if not archive.exists():
        temporary=archive.with_suffix(archive.suffix+'.partial')
        with urllib.request.urlopen(item['url'], timeout=120) as response, temporary.open('wb') as out:
            while block:=response.read(1024*1024): out.write(block)
        temporary.replace(archive)
    digest=hashlib.sha256(archive.read_bytes()).hexdigest()
    if digest!=item['sha256']: raise SystemExit(f'{key}: source archive SHA-256 mismatch')
    source=cache/'sources'/item['directory']
    if not source.exists():
        with tarfile.open(archive) as package:
            for member in package.getmembers():
                parts=pathlib.PurePosixPath(member.name).parts
                if not parts or parts[0]!=item['directory'] or '..' in parts:
                    raise SystemExit(f'{key}: unexpected archive path')
            package.extractall(cache/'sources', filter='data')
    print(f'{key}: pinned source verified')
PYCODE
FFMPEG_SOURCE="$CACHE/sources/ffmpeg-7.1.5"
X264_SOURCE="$CACHE/sources/x264-b35605ace3ddf7c1a5d67a2eb553f034aef41d55"
BUILD_KEY="$(cat "$0" "$MANIFEST" | sha256sum | cut -d' ' -f1)"
JOBS="${QUICKCUT_NATIVE_JOBS:-4}"
export CARGO_TARGET_DIR="$CACHE/cargo-target"
export LIBCLANG_PATH="${LIBCLANG_PATH:-$TOOLCHAIN/musl/lib}"

build_dependencies() (
  local abi="$1" target="$2" arch="$3"
  local prefix="$CACHE/prefix/$abi" build="$CACHE/objects/$abi"
  if [[ -f "$prefix/.build-key" && "$(cat "$prefix/.build-key")" == "$BUILD_KEY" ]]; then
    return
  fi
  rm -rf "$build" "$prefix"
  mkdir -p "$build/x264" "$build/ffmpeg" "$prefix"
  export PKG_CONFIG_PATH="$prefix/lib/pkgconfig" PKG_CONFIG_LIBDIR="$prefix/lib/pkgconfig" PKG_CONFIG_SYSROOT_DIR=""
  local cross_x264=() cross_ffmpeg=()
  if [[ "$abi" == host ]]; then
    export CC=gcc CXX=g++ AR=ar RANLIB=ranlib STRIP=strip
    cross_ffmpeg=(--target-os=linux --arch=x86_64)
  else
    export CC="$TOOLCHAIN/bin/${target}${API}-clang" CXX="$TOOLCHAIN/bin/${target}${API}-clang++"
    export AR="$TOOLCHAIN/bin/llvm-ar" RANLIB="$TOOLCHAIN/bin/llvm-ranlib" STRIP="$TOOLCHAIN/bin/llvm-strip"
    cross_x264=(--host="$target" --sysroot="$TOOLCHAIN/sysroot")
    cross_ffmpeg=(--target-os=android --arch="$arch" --enable-cross-compile --sysroot="$TOOLCHAIN/sysroot")
  fi
  cd "$build/x264"
  "$X264_SOURCE/configure" --prefix="$prefix" "${cross_x264[@]}" \
    --enable-static --enable-pic --disable-cli --disable-opencl \
    --disable-avs --disable-swscale --disable-lavf --disable-ffms --disable-gpac --disable-lsmash \
    --bit-depth=8 --chroma-format=420 --extra-cflags="-O2 -ffunction-sections -fdata-sections" \
    --extra-ldflags="-Wl,-z,max-page-size=16384"
  make -j"$JOBS"
  make install
  cd "$build/ffmpeg"
  "$FFMPEG_SOURCE/configure" --prefix="$prefix" "${cross_ffmpeg[@]}" \
    --cc="$CC" --cxx="$CXX" --ar="$AR" --ranlib="$RANLIB" --strip="$STRIP" \
    --pkg-config="$(command -v pkg-config)" --pkg-config-flags=--static \
    --enable-static --disable-shared --enable-pic --disable-programs --disable-doc --disable-debug \
    --disable-network --disable-autodetect --disable-everything --enable-gpl --enable-libx264 \
    --enable-protocol=file,pipe --enable-demuxer=mov --enable-muxer=mp4,mov \
    --enable-decoder=h264,aac --enable-encoder=libx264,aac --enable-parser=h264,aac \
    --enable-bsf=h264_mp4toannexb,aac_adtstoasc,extract_extradata --enable-filter=anull,aresample \
    --extra-cflags="-O2 -ffunction-sections -fdata-sections -I$prefix/include" \
    --extra-ldflags="-L$prefix/lib -Wl,-z,max-page-size=16384"
  make -j"$JOBS"
  make install
  printf '%s\n' "$BUILD_KEY" > "$prefix/.build-key"
)

build_android() (
  local abi="$1" target="$2" arch="$3"
  build_dependencies "$abi" "$target" "$arch" > "$CACHE/logs/dependencies-$abi.log" 2>&1 || {
    tail -80 "$CACHE/logs/dependencies-$abi.log" >&2; exit 1;
  }
  local prefix="$CACHE/prefix/$abi" linker_var="CARGO_TARGET_${target^^}_LINKER"
  linker_var="${linker_var//-/_}"
  export "$linker_var=$TOOLCHAIN/bin/${target}${API}-clang"
  export "CC_${target//-/_}=$TOOLCHAIN/bin/${target}${API}-clang"
  export "AR_${target//-/_}=$TOOLCHAIN/bin/llvm-ar"
  export PKG_CONFIG_PATH="$prefix/lib/pkgconfig" PKG_CONFIG_LIBDIR="$prefix/lib/pkgconfig"
  export PKG_CONFIG_ALLOW_CROSS=1 PKG_CONFIG_ALL_STATIC=1 FFMPEG_STATIC=1 PKG_CONFIG_SYSROOT_DIR=""
  export BINDGEN_EXTRA_CLANG_ARGS="-resource-dir=$TOOLCHAIN/lib/clang/18 --target=${target}${API} --sysroot=$TOOLCHAIN/sysroot -I$TOOLCHAIN/sysroot/usr/include/$target"
  export RUSTFLAGS="-C link-arg=-Wl,-z,max-page-size=16384 -C link-arg=-Wl,--no-undefined"
  cargo +"$RUST_VERSION" build --manifest-path "$CRATE" --locked --release --target "$target"
  local out="$ROOT/app/build/generated/nativeLibs/$abi/libquickcut_smartcut.so"
  mkdir -p "${out%/*}"
  cp "$CARGO_TARGET_DIR/$target/release/libquickcut_smartcut.so" "$out"
  "$TOOLCHAIN/bin/llvm-strip" --strip-unneeded "$out"
  python3 - "$out" <<'PYCODE'
import pathlib, struct, sys
p=pathlib.Path(sys.argv[1]); data=p.read_bytes()
if data[:6]!=b'\x7fELF\x02\x01': raise SystemExit('Expected ELF64 little-endian library')
phoff=struct.unpack_from('<Q',data,32)[0]; entsize,count=struct.unpack_from('<HH',data,54)
loads=[]
for i in range(count):
    entry=struct.unpack_from('<IIQQQQQQ',data,phoff+i*entsize)
    if entry[0]==1: loads.append(entry[-1])
if not loads or any(value<16384 for value in loads): raise SystemExit('Library lacks 16 KiB LOAD alignment')
print(f'{p.parent.name}: {p.stat().st_size} bytes, every LOAD segment aligned to 16 KiB')
PYCODE
)

if [[ "$HOST_TESTS" == true ]]; then
  build_dependencies host x86_64-linux-gnu x86_64 > "$CACHE/logs/dependencies-host.log" 2>&1 || {
    tail -80 "$CACHE/logs/dependencies-host.log" >&2; exit 1;
  }
  (
    export PKG_CONFIG_PATH="$CACHE/prefix/host/lib/pkgconfig" PKG_CONFIG_LIBDIR="$CACHE/prefix/host/lib/pkgconfig"
    export PKG_CONFIG_ALL_STATIC=1 FFMPEG_STATIC=1 PKG_CONFIG_SYSROOT_DIR=""
    unset RUSTFLAGS
    export BINDGEN_EXTRA_CLANG_ARGS="-resource-dir=$TOOLCHAIN/lib/clang/18"
    cargo +"$RUST_VERSION" test --manifest-path "$CRATE" --locked --release
  )
fi
build_android arm64-v8a aarch64-linux-android aarch64
build_android x86_64 x86_64-linux-android x86_64
python3 "$ROOT/scripts/collect-native-licenses.py"
