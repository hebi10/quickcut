#!/usr/bin/env python3
"""Check JAR integrity, pinned payload signer, source version and native ABIs."""
import argparse
import hashlib
from pathlib import Path
import subprocess
import sys
import xml.etree.ElementTree as ET
import zipfile

BUNDLETOOL_SHA256 = '378b5434cd1378bef6b2bc527b8c7f0ff2584b273830335bce54d6d0813c8584'
ANDROID = '{http://schemas.android.com/apk/res/android}'


def run(command):
    result = subprocess.run(command, capture_output=True, text=True)
    if result.returncode:
        raise ValueError('Bundle verification command failed: ' + Path(command[0]).name)
    return result.stdout


def verify_tool(path):
    with path.open('rb') as stream:
        if hashlib.file_digest(stream, 'sha256').hexdigest() != BUNDLETOOL_SHA256:
            raise ValueError('bundletool does not match the pinned official release digest')


def verify(bundle, tool, version, code, pin):
    # jarsigner checks the archive; JarFile additionally rejects unsigned added
    # payloads and multiple signers that jarsigner's normal exit code permits.
    run(['java', '-m', 'jdk.jartool/sun.security.tools.jarsigner.Main', '-verify', str(bundle)])
    run(['java', str(Path(__file__).with_name('VerifyBundleSignatures.java')), str(bundle), pin])
    manifest = ET.fromstring(run(['java', '-jar', str(tool), 'dump', 'manifest', '--bundle=' + str(bundle), '--module=base']))
    if (manifest.get('package') != 'com.hebi.quickcut'
            or manifest.get(ANDROID + 'versionName') != version
            or manifest.get(ANDROID + 'versionCode') != str(code)):
        raise ValueError('Bundle package/version differs from the committed release source')
    with zipfile.ZipFile(bundle) as archive:
        names = archive.namelist()
        if len(names) != len(set(names)):
            raise ValueError('Duplicate bundle entries')
        abis = {n.split('/')[2] for n in names if n.startswith('base/lib/') and n.endswith('.so')}
        if abis != {'arm64-v8a', 'x86_64'}:
            raise ValueError('Bundle must include exactly arm64-v8a and x86_64 native libraries')
        for abi in abis:
            name = f'base/lib/{abi}/libquickcut_smartcut.so'
            if name not in names or not archive.getinfo(name).file_size:
                raise ValueError('Missing native smart-cut engine in bundle: ' + abi)
    print(f'QuickCut signed AAB verified: {version} / {code}; arm64-v8a and x86_64')


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--bundle', type=Path, required=True)
    parser.add_argument('--bundletool', type=Path, required=True)
    parser.add_argument('--version', required=True)
    parser.add_argument('--version-code', type=int, required=True)
    args = parser.parse_args()
    verify_tool(args.bundletool)
    pin = (Path(__file__).parent.parent / 'config/release-certificate.sha256').read_text().strip()
    verify(args.bundle, args.bundletool, args.version, args.version_code, pin)


if __name__ == '__main__':
    try:
        main()
    except (ValueError, OSError, ET.ParseError, zipfile.BadZipFile) as error:
        print(f'::error::Signed bundle rejected: {error}', file=sys.stderr)
        sys.exit(1)
