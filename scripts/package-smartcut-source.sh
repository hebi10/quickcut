#!/usr/bin/env bash
# Produce the corresponding source for the exact CI checkout, never a worktree copy.
set -euo pipefail
repo_root="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd)"
python3 - "$repo_root" "${1:-$repo_root/build/source-delivery}" <<'PY'
import hashlib
import io
import json
import os
from pathlib import Path
import shutil
import subprocess
import sys
import tarfile
import tempfile
import urllib.error
import urllib.request
import xml.etree.ElementTree as ET
import zipfile
from concurrent.futures import ThreadPoolExecutor

repo = Path(sys.argv[1]).resolve()
destination = Path(sys.argv[2]).resolve()
cache = Path(os.environ.get("QUICKCUT_NATIVE_CACHE", repo / "build/native-smartcut")).resolve()

def run(args, **kwargs):
    return subprocess.run(args, cwd=repo, check=True, **kwargs)

def read(args):
    return run(args, stdout=subprocess.PIPE, text=True).stdout.strip()

def sha256(path):
    digest = hashlib.sha256()
    with path.open("rb") as source:
        for block in iter(lambda: source.read(1024 * 1024), b""):
            digest.update(block)
    return digest.hexdigest()

# Generated native outputs may be untracked; tracked modifications must never
# create a bundle claiming to describe a different commit.
run(["git", "diff", "--quiet", "HEAD", "--"])
head = read(["git", "rev-parse", "HEAD"])
tree = read(["git", "rev-parse", "HEAD^{tree}"])
epoch = int(read(["git", "show", "-s", "--format=%ct", "HEAD"]))
name = "quickcut-corresponding-source-" + head[:12]
destination.mkdir(parents=True, exist_ok=True)

with tempfile.TemporaryDirectory(prefix="quickcut-source-") as temporary:
    temp = Path(temporary)
    stage = temp / name
    stage.mkdir()
    snapshot = temp / "checkout.tar"
    run(["git", "archive", "--format=tar", "-o", str(snapshot), "HEAD"])
    with tarfile.open(snapshot) as archive:
        archive.extractall(stage, filter="data")

    # A tracked key or local environment file is a release error, not something
    # to quietly include or hide from the source manifest.
    forbidden = []
    for path in stage.rglob("*"):
        if path.is_file() and (path.name in {"local.properties", "keystore.properties", ".env"}
                              or path.name.startswith(".env.")
                              or path.suffix.lower() in {".jks", ".keystore", ".p12", ".pfx"}):
            forbidden.append(str(path.relative_to(stage)))
    if forbidden:
        raise RuntimeError("Refusing signing/private files in source: " + ", ".join(forbidden))

    # Copy exact, verified upstream source archives, not a native build directory
    # that may contain object files, local configuration, or patched cache state.
    native_inputs = json.loads((stage / "scripts/native-smartcut-sources.json").read_text())
    rust_version = native_inputs["toolchains"]["rust"]
    pins = [(native_inputs[name]["archive"], native_inputs[name]["sha256"])
            for name in ("ffmpeg", "x264")]
    native_downloads = stage / "build/native-smartcut/downloads"
    native_downloads.mkdir(parents=True)
    for filename, expected in pins:
        source = cache / "downloads" / filename
        if not source.is_file() or sha256(source) != expected:
            raise RuntimeError("Missing or mismatched pinned native source: " + filename)
        shutil.copyfile(source, native_downloads / filename)
    native_notice_path = Path("build/generated/smartcutAssets/licenses/NATIVE-DEPENDENCIES.txt")
    if not (repo / native_notice_path).is_file():
        raise RuntimeError("Build native dependency notices before packaging source")
    (stage / native_notice_path).parent.mkdir(parents=True, exist_ok=True)
    shutil.copyfile(repo / native_notice_path, stage / native_notice_path)
    native_libraries = []
    for abi in ("arm64-v8a", "x86_64"):
        library = repo / "app/build/generated/nativeLibs" / abi / "libquickcut_smartcut.so"
        if not library.is_file():
            raise RuntimeError("Build the native library before packaging source: " + abi)
        native_libraries.append({"abi": abi, "sha256": sha256(library)})

    manifest = stage / "native/quickcut-smartcut/Cargo.toml"
    lock = manifest.with_name("Cargo.lock")
    if not manifest.is_file() or not lock.is_file():
        raise RuntimeError("The committed JNI manifest and Cargo.lock are required")
    # Use the frozen checkout's lockfile. cargo vendor includes dependency
    # license files and checksums, and excludes target/ and user Cargo settings.
    vendor = stage / "native/cargo-vendor"
    vendor_config = subprocess.run(
        ["cargo", "+" + rust_version, "vendor", "--locked", "--versioned-dirs", "--manifest-path", str(manifest), str(vendor)],
        cwd=stage, check=True, stdout=subprocess.PIPE, text=True,
    ).stdout.replace(str(vendor), "native/cargo-vendor")
    config = stage / ".cargo/config.toml"
    if config.exists():
        raise RuntimeError("Review existing Cargo configuration before adding vendor replacement")
    config.parent.mkdir(exist_ok=True)
    config.write_text(vendor_config, encoding="utf-8")
    subprocess.run(["cargo", "+" + rust_version, "metadata", "--offline", "--locked", "--format-version", "1",
                    "--manifest-path", str(manifest)], cwd=stage, check=True, stdout=subprocess.DEVNULL)

    # These libraries are bundled into the APK; they are not Android's system
    # libraries. Capture sources of the actually selected release dependencies,
    # including transitive AndroidX, Compose, Media3 and Kotlin modules.
    dependency_dir = stage / "third_party/runtime-sources"
    dependency_dir.mkdir(parents=True)
    init_script = temp / "collect-runtime-sources.gradle"
    init_script.write_text(r'''
import org.gradle.jvm.JvmLibrary
import org.gradle.language.base.artifact.SourcesArtifact
import org.gradle.api.artifacts.result.ResolvedArtifactResult
import org.gradle.api.artifacts.result.ComponentArtifactsResult
import groovy.json.JsonOutput
import java.security.MessageDigest
import java.util.HexFormat
import java.util.zip.ZipFile

gradle.projectsEvaluated {
    def app = gradle.rootProject.project(':app')
    app.tasks.register('collectQuickCutReleaseSources') {
        doLast {
            def output = new File(app.providers.gradleProperty('quickcutSourceDependencyDir').get())
            output.mkdirs()
            def runtime = app.configurations.getByName('releaseRuntimeClasspath')
            def binaries = runtime.incoming.artifacts.artifacts
            def ids = binaries.collect { it.id.componentIdentifier }.unique()
            def result = app.dependencies.createArtifactResolutionQuery().forComponents(ids)
                .withArtifacts(JvmLibrary, SourcesArtifact).execute()
            def rows = []
            def missing = []
            result.components.each { component ->
                if (!(component instanceof ComponentArtifactsResult)) {
                    missing.add(component.id.displayName)
                    return
                }
                def sources = component.getArtifacts(SourcesArtifact).findAll { it instanceof ResolvedArtifactResult }
                // Guava deliberately publishes this exact conflict-avoidance JAR
                // with metadata only and no sources artifact. Verify the binary;
                // no other missing source JAR is accepted by this exception.
                if (sources.empty && component.id.displayName ==
                    'com.google.guava:listenablefuture:9999.0-empty-to-avoid-conflict-with-guava') {
                    def matching = binaries.findAll { it.id.componentIdentifier == component.id }
                    if (matching.size() != 1) throw new GradleException('Unexpected empty-artifact binary count')
                    def binary = matching.first().file
                    def digest = HexFormat.of().formatHex(MessageDigest.getInstance('SHA-256').digest(binary.bytes))
                    if (digest != 'b372a037d4230aa57fbeffdef30fd6123f9c0c2db85d0aced00c91b974f33f99') {
                        throw new GradleException('The known empty Guava artifact changed')
                    }
                    new ZipFile(binary).withCloseable { archive ->
                        def allowed = ['META-INF/MANIFEST.MF',
                            'META-INF/maven/com.google.guava/listenablefuture/pom.xml',
                            'META-INF/maven/com.google.guava/listenablefuture/pom.properties']
                        archive.entries().each { entry ->
                            if (!entry.isDirectory() && !allowed.contains(entry.name)) {
                                throw new GradleException('Unexpected code/resource in empty Guava artifact: ' + entry.name)
                            }
                        }
                    }
                    def target = new File(output, 'guava-listenablefuture-empty-artifact.txt')
                    target.text = 'This exact Maven placeholder contains only manifest/publication metadata, no code or resources.\n' +
                        'Component: ' + component.id.displayName + '\nBinary SHA256: ' + digest + '\n' +
                        'The publisher provides no sources JAR for this empty conflict-avoidance artifact.\n'
                    rows.add([component: component.id.displayName, source: target.name, sourceArtifact: null,
                        artifactKind: 'verified-empty-conflict-placeholder',
                        binaries: [[name: binary.name, path: binary.absolutePath]]])
                    return
                }
                if (sources.empty) missing.add(component.id.displayName)
                sources.each { source ->
                    def prefix = component.id.displayName.replaceAll('[^A-Za-z0-9._-]', '_')
                    def target = new File(output, prefix + '__' + source.file.name)
                    target.bytes = source.file.bytes
                    def matching = binaries.findAll { it.id.componentIdentifier == component.id }
                    rows.add([component: component.id.displayName, source: target.name, sourceArtifact: source.file.name,
                        binaries: matching.collect { [name: it.file.name, path: it.file.absolutePath] }])
                }
            }
            new File(output, 'resolution.json').text = JsonOutput.prettyPrint(JsonOutput.toJson(rows))
            if (!missing.empty) throw new GradleException('Missing runtime dependency sources: ' + missing.join(', '))
            if (rows.empty) throw new GradleException('No release runtime dependency sources were collected')
        }
    }
}
''', encoding="utf-8")
    gradle = ["gradle"] if shutil.which("gradle") else ["bash", "./gradlew"]
    run(gradle + ["--no-daemon", "-I", str(init_script),
         "-PquickcutSourceDependencyDir=" + str(dependency_dir), ":app:collectQuickCutReleaseSources"])
    resolution = json.loads((dependency_dir / "resolution.json").read_text())
    metadata_dir = dependency_dir / "maven-metadata"
    metadata_dir.mkdir()
    def collect_metadata(row):
        group, artifact, version = row["component"].split(":")
        repository = "https://dl.google.com/dl/android/maven2/" if group.startswith("androidx.") else "https://repo.maven.apache.org/maven2/"
        base = repository + group.replace(".", "/") + "/" + artifact + "/" + version + "/"
        stem = artifact + "-" + version
        prefix = group + "__" + stem
        row["sourceArtifactUrl"] = base + row["sourceArtifact"] if row["sourceArtifact"] else None
        row["metadata"] = {}
        for extension in ("pom", "module"):
            url = base + stem + "." + extension
            try:
                with urllib.request.urlopen(url, timeout=60) as response:
                    data = response.read()
            except urllib.error.HTTPError as error:
                if extension == "module" and error.code == 404:
                    continue  # Older Maven publications legitimately have no Gradle module metadata.
                raise
            path = metadata_dir / (prefix + "." + extension)
            path.write_bytes(data)
            row["metadata"][extension] = {"file": "maven-metadata/" + path.name,
                                           "url": url, "sha256": sha256(path)}
            if extension == "pom":
                pom = ET.fromstring(data)
                ns = {"m": "http://maven.apache.org/POM/4.0.0"}
                row["upstreamScm"] = {key: pom.findtext("m:scm/m:" + key, namespaces=ns)
                                      for key in ("url", "connection", "tag")}
            else:
                row["publishedBy"] = json.loads(data).get("createdBy", {})
        return row
    with ThreadPoolExecutor(max_workers=8) as executor:
        resolution = list(executor.map(collect_metadata, resolution))
    source_locations = json.loads((stage / "third_party/runtime-source-locations.json").read_text())
    directions = ["# QuickCut runtime dependency source locations\n\n",
        "The archive contains the complete QuickCut/JNI/modified SmartCut source, pinned native sources, "
        "vendored Rust crates, and selected runtime source JARs. Runtime JARs alone do not include every "
        "upstream resource, manifest or project build script. Those project sources remain available at "
        "the official locations below; this file accompanies the APK/source delivery.\n\n",
        "The immutable AndroidX archive URLs contain the whole source tree including project build scripts. "
        "Alternatively use `git clone <repository>` followed by `git checkout <commit>`. "
        "Do not replace a pinned revision with the current main branch.\n\n"]
    for row in resolution:
        group, artifact, version = row["component"].split(":")
        directions.append("## " + row["component"] + "\n\n")
        if row.get("artifactKind") == "verified-empty-conflict-placeholder":
            directions.append("Verified empty conflict-avoidance artifact: no executable code or resources. "
                              "The publisher supplies no sources JAR. Binary hash and ZIP-entry verification: "
                              "`third_party/runtime-sources/" + row["source"] + "`.\n\n")
        else:
            directions.append("Included published sources: `third_party/runtime-sources/" + row["source"] + "`.\n\n")
            directions.append("Exact Maven sources: " + row["sourceArtifactUrl"] + "\n\n")
        entry = source_locations["androidx"].get(row["component"]) or source_locations["androidx"].get(group + ":" + version)
        if entry:
            repository = source_locations["repository"]
            row["projectSource"] = {"repository": repository, "commit": entry["commit"],
                "archive": repository + "/+archive/" + entry["commit"] + ".tar.gz",
                "evidence": "https://developer.android.com/jetpack/androidx/releases/" + entry["releasePage"] + "#" + entry.get("releaseAnchor", version)}
        elif group == "androidx.media3" and version in source_locations["media3"]:
            entry = source_locations["media3"][version]
            row["projectSource"] = {**entry, "archive": entry["repository"] + "/archive/" + entry["commit"] + ".tar.gz"}
        if row.get("projectSource"):
            directions.append("Full project sources: " + row["projectSource"]["archive"] + "\n\n")
            directions.append("Revision: `" + row["projectSource"]["commit"] + "`.\n\n")
            if row["projectSource"].get("evidence"):
                directions.append("Official release/version evidence: " + row["projectSource"]["evidence"] + "\n\n")
        else:
            scm = row["upstreamScm"]
            directions.append("Publisher's project source location: " + (scm.get("url") or scm.get("connection") or "see the included publication POM") + "\n\n")
            if scm.get("tag"):
                directions.append("Publisher's SCM tag: `" + scm["tag"] + "`.\n\n")
            directions.append("The included POM/module records the exact publication version and available SCM/build metadata; "
                              "this row does not assert an unverified source commit.\n\n")
        directions.append("Publication metadata: `third_party/runtime-sources/" + row["metadata"]["pom"]["file"] + "`.\n\n")
    locations_file = stage / "UPSTREAM-SOURCE-LOCATIONS.md"
    locations_file.write_text("".join(directions), encoding="utf-8")
    notice_dir = dependency_dir / "binary-notices"
    notice_dir.mkdir()
    def copy_binary_notices(jar, prefix):
        for member in jar.infolist():
            leaf = Path(member.filename).name
            if leaf.upper().startswith(("LICENSE", "NOTICE", "COPYING")) and not member.is_dir():
                safe_name = prefix + "__" + member.filename.replace("/", "_")
                (notice_dir / safe_name).write_bytes(jar.read(member))
            elif member.filename == "classes.jar":
                # Android AAR copyright notices are commonly in the nested JAR.
                with zipfile.ZipFile(io.BytesIO(jar.read(member))) as classes:
                    copy_binary_notices(classes, prefix + "__classes")
    for row in resolution:
        row["sourceSha256"] = sha256(dependency_dir / row["source"])
        for binary in row["binaries"]:
            binary_path = Path(binary.pop("path"))
            binary["sha256"] = sha256(binary_path)
            if zipfile.is_zipfile(binary_path):
                with zipfile.ZipFile(binary_path) as jar:
                    copy_binary_notices(jar, row["source"].removesuffix(".jar"))
    (dependency_dir / "resolution.json").write_text(json.dumps(resolution, indent=2) + "\n")

    identity = {
        "gitCommit": head, "gitTree": tree, "gitCommitTimestamp": epoch,
        "smartCutCommit": "510ee91614a29f9c7fd6a446b75e9767f0df3d8a",
        "cargoLockSha256": sha256(lock),
        "nativeSourceArchives": [{"name": n, "sha256": h} for n, h in pins],
        "nativeLibraries": native_libraries,
        "toolchains": native_inputs["toolchains"],
        "runtimeSourceModules": len(resolution),
        "rustc": read(["rustc", "+" + rust_version, "--version"]),
        "cargo": read(["cargo", "+" + rust_version, "--version"]),
        "signingSecretsIncluded": False,
    }
    (stage / "SOURCE-IDENTITY.json").write_text(json.dumps(identity, indent=2) + "\n")
    (stage / "SOURCE-README.txt").write_text(
        "QuickCut corresponding source for commit " + head + "\n\n"
        "See docs/20_NATIVE_SMART_CUT.md for license scope, build and own-key installation.\n"
        "The native source archives, Rust crates and selected Android runtime sources are included.\n"
        "Android SDK/NDK, JDK, Rust toolchain and Gradle are external general-purpose build tools.\n"
        "Native input archives are under build/native-smartcut/downloads; Cargo is vendored.\n"
        "Gradle may download its selected Android dependencies and build plugins during a build;\n"
        "their runtime sources and binary notices are under third_party/runtime-sources.\n"
        "Additional dependency project resources/build scripts: UPSTREAM-SOURCE-LOCATIONS.md.\n"
        "That file distinguishes included sources from separately available official project sources.\n"
        "No production signing key is required to build and install your own signed version.\n"
        "Recipients may modify and redistribute under the included license terms.\n"
    )
    files = sorted(path for path in stage.rglob("*") if path.is_file() and not path.is_symlink())
    (stage / "SHA256SUMS").write_text("".join(sha256(p) + "  " + str(p.relative_to(stage)) + "\n" for p in files))
    output = destination / (name + ".tar.gz")
    def stable(info):
        info.uid = info.gid = 0
        info.uname = info.gname = ""
        info.mtime = epoch
        return info
    with tarfile.open(output, "w:gz") as archive:
        archive.add(stage, arcname=name, filter=stable)
    digest = sha256(output)
    (destination / (output.name + ".sha256")).write_text(digest + "  " + output.name + "\n")
    (destination / "source-identity.json").write_text(json.dumps(identity, indent=2) + "\n")
    shutil.copyfile(locations_file, destination / "UPSTREAM-SOURCE-LOCATIONS.md")
    print("Corresponding source: " + str(output))
    print("SHA256: " + digest)
    print("Runtime source modules: " + str(len(resolution)))
PY
