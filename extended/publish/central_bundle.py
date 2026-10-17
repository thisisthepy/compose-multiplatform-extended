#!/usr/bin/env python3
"""Turns what a local Maven publish left behind into a Maven Central bundle, and checks it.

Usage:
  central_bundle.py stage    --from <repo> [--from <repo>...] --out <dir> [--project <owner/repo>]
  central_bundle.py sign     <dir> [--key <id>]
  central_bundle.py validate <dir> [--version <v>] [--require <group:artifact>...]
                                   [--verify-signatures]
  central_bundle.py zip      <dir> <bundle.zip>

The build stays where it is (publish-local.sh, the Amper window modules, the Gradle plugin
build); this tool only reads the Maven repositories they wrote to. It is the same file in
thisisthepy/compose-multiplatform-core-extended and thisisthepy/compose-multiplatform-extended,
and a change to one is made to the other.

stage    copies every component under org/thisisthepy from each repository into <dir>. When
         two repositories hold the same file with different content, the one named first
         wins and the other is reported: the macOS build is named first because its root
         modules list every target. Then each POM gets what Central asks for and a local
         build does not always write (name, description, url, licenses, developers, scm);
         url and scm always name the repository the artifacts are built from, since that is
         where their sources are. A component that is not a bare POM gets an empty javadoc
         jar when it has none, and an empty sources jar when it has none; both are listed.
sign     writes .md5, .sha1, .sha256 and .sha512 beside every file, and an ASCII-armoured
         detached signature (.asc) made with gpg, which must hold the secret key already.
         GPG_PASSPHRASE, when set, is the key's passphrase.
validate checks <dir> the way Central's validation does, and a little more:
         - every group is under org.thisisthepy and matches the POM, which names the
           artifact and version of its directory;
         - no version is a SNAPSHOT or a local -dev build, every version ends in -ext.<N>,
           and with --version every one is exactly that;
         - every POM has name, description, url, a license with name and url, a developer,
           and scm with url and connection;
         - every component that is not a bare POM has its main file, a sources jar and a
           javadoc jar;
         - every file has .asc, .md5, .sha1, .sha256 and .sha512, and the checksums match;
         - every org.thisisthepy dependency a POM names, and every org.thisisthepy variant a
           Gradle module points at, is in the bundle at that version, so nothing on Central
           sends a consumer to a coordinate nobody published;
         - every Gradle plugin a jar declares has its marker POM;
         - every --require coordinate is present.
         With --verify-signatures each .asc is checked with gpg. Exits 1 on any failure.
zip      writes the bundle Central's upload API takes: the directory in Maven layout.
"""

import argparse
import filecmp
import hashlib
import json
import os
import re
import shutil
import subprocess
import sys
import xml.etree.ElementTree as ET
import zipfile

PREFIX = "org.thisisthepy"
POM_NS = "http://maven.apache.org/POM/4.0.0"
XSI_NS = "http://www.w3.org/2001/XMLSchema-instance"
CHECKSUMS = {"md5": hashlib.md5, "sha1": hashlib.sha1, "sha256": hashlib.sha256, "sha512": hashlib.sha512}
SIDE_FILES = tuple("." + ext for ext in CHECKSUMS) + (".asc",)
SKIPPED = ("maven-metadata", "_remote.repositories", ".lastUpdated")
VERSION_RULE = re.compile(r"^\d+(\.\d+)*(-[A-Za-z0-9.]+)?-ext\.\d+$")
DEVELOPER = {"id": "thisisthepy", "name": "thisisthepy", "url": "https://github.com/thisisthepy"}
LICENSE = {"name": "The Apache License, Version 2.0", "url": "https://www.apache.org/licenses/LICENSE-2.0.txt"}

ET.register_namespace("", POM_NS)
ET.register_namespace("xsi", XSI_NS)


def q(tag):
    return f"{{{POM_NS}}}{tag}"


def components(root):
    """(group, artifact, version, directory) for every directory holding <artifact>-<version>.pom."""
    for directory, _, files in os.walk(root):
        relative = os.path.relpath(directory, root).split(os.sep)
        if len(relative) < 3:
            continue
        group, artifact, version = ".".join(relative[:-2]), relative[-2], relative[-1]
        if f"{artifact}-{version}.pom" in files:
            yield group, artifact, version, directory


def primary_files(directory):
    return sorted(
        name for name in os.listdir(directory)
        if not name.endswith(SIDE_FILES) and not any(s in name for s in SKIPPED)
        and os.path.isfile(os.path.join(directory, name))
    )


# --- stage -------------------------------------------------------------------------------

def stage(sources, out, project):
    os.makedirs(out, exist_ok=True)
    conflicts = []
    for source in sources:
        base = os.path.join(source, *PREFIX.split("."))
        if not os.path.isdir(base):
            print(f"note  {source} holds nothing under {PREFIX}")
            continue
        for group, artifact, version, directory in components(source):
            if not group.startswith(PREFIX):
                continue
            target = os.path.join(out, os.path.relpath(directory, source))
            os.makedirs(target, exist_ok=True)
            for name in primary_files(directory):
                src, dst = os.path.join(directory, name), os.path.join(target, name)
                if os.path.exists(dst):
                    if not filecmp.cmp(src, dst, shallow=False):
                        conflicts.append(f"{os.path.relpath(dst, out)}: kept the copy from an earlier --from, not {source}")
                    continue
                shutil.copy2(src, dst)
    for line in conflicts:
        print(f"note  {line}")

    url = f"https://github.com/{project}"
    added = []
    for group, artifact, version, directory in components(out):
        packaging = complete_pom(os.path.join(directory, f"{artifact}-{version}.pom"), artifact, url, project)
        if packaging == "pom":
            continue
        for classifier in ("sources", "javadoc"):
            jar = os.path.join(directory, f"{artifact}-{version}-{classifier}.jar")
            if not os.path.exists(jar):
                empty_jar(jar, classifier, f"{group}:{artifact}:{version}")
                added.append(os.path.relpath(jar, out))
    for line in added:
        print(f"empty {line}")
    print(f"staged {sum(1 for _ in components(out))} component(s) in {out}, {len(added)} empty jar(s) added")


def child(parent, tag):
    element = parent.find(q(tag))
    if element is None:
        element = ET.SubElement(parent, q(tag))
    return element


def text_of(parent, path):
    element = parent.find("/".join(q(p) for p in path.split("/")))
    return (element.text or "").strip() if element is not None else ""


def complete_pom(path, artifact, url, project):
    tree = ET.parse(path)
    pom = tree.getroot()
    if not text_of(pom, "name"):
        child(pom, "name").text = artifact
    if not text_of(pom, "description"):
        child(pom, "description").text = f"{artifact}, built from {project}"
    child(pom, "url").text = url
    licenses = child(pom, "licenses")
    if not any(text_of(l, "name") and text_of(l, "url") for l in licenses.findall(q("license"))):
        for stale in list(licenses):
            licenses.remove(stale)
        license = ET.SubElement(licenses, q("license"))
        for key, value in LICENSE.items():
            ET.SubElement(license, q(key)).text = value
    developers = child(pom, "developers")
    if not any(text_of(d, "id") or text_of(d, "name") for d in developers.findall(q("developer"))):
        developer = ET.SubElement(developers, q("developer"))
        for key, value in DEVELOPER.items():
            ET.SubElement(developer, q(key)).text = value
    scm = child(pom, "scm")
    for stale in list(scm):
        scm.remove(stale)
    ET.SubElement(scm, q("connection")).text = f"scm:git:{url}.git"
    ET.SubElement(scm, q("developerConnection")).text = f"scm:git:ssh://git@github.com/{project}.git"
    ET.SubElement(scm, q("url")).text = url
    ET.indent(tree, space="  ")
    tree.write(path, encoding="UTF-8", xml_declaration=True)
    return text_of(pom, "packaging") or "jar"


def empty_jar(path, classifier, coordinate):
    with zipfile.ZipFile(path, "w", zipfile.ZIP_DEFLATED) as jar:
        jar.writestr("META-INF/MANIFEST.MF", "Manifest-Version: 1.0\r\n\r\n")
        jar.writestr("README.txt", f"{coordinate} publishes no {classifier} of its own; this jar is empty on purpose.\n")


# --- sign --------------------------------------------------------------------------------

def sign(out, key):
    passphrase = os.environ.get("GPG_PASSPHRASE")
    count = 0
    for _, _, _, directory in components(out):
        for name in primary_files(directory):
            path = os.path.join(directory, name)
            with open(path, "rb") as f:
                data = f.read()
            for ext, algorithm in CHECKSUMS.items():
                with open(f"{path}.{ext}", "w") as f:
                    f.write(algorithm(data).hexdigest())
            command = ["gpg", "--batch", "--yes", "--armor", "--detach-sign", "--output", f"{path}.asc"]
            if key:
                command += ["--local-user", key]
            if passphrase:
                command += ["--pinentry-mode", "loopback", "--passphrase-fd", "0"]
            subprocess.run(command + [path], input=(passphrase or "").encode(), check=True)
            count += 1
    print(f"signed and checksummed {count} file(s)")


# --- validate ----------------------------------------------------------------------------

def present(out, group, artifact, version):
    base = os.path.join(out, *group.split("."), artifact, version, f"{artifact}-{version}")
    return os.path.isfile(base + ".pom") or os.path.isfile(base + ".module")


def validate(out, version, required, verify):
    errors = []
    found = list(components(out))
    if not found:
        errors.append(f"{out}: no component in the bundle")
    seen = set()
    for group, artifact, ver, directory in found:
        where = f"{group}:{artifact}:{ver}"
        seen.add(f"{group}:{artifact}")
        pom_path = os.path.join(directory, f"{artifact}-{ver}.pom")
        try:
            pom = ET.parse(pom_path).getroot()
        except ET.ParseError as error:
            errors.append(f"{where}: the POM does not parse ({error})")
            continue

        if not (group == PREFIX or group.startswith(PREFIX + ".")):
            errors.append(f"{where}: the group is outside {PREFIX}, which is the namespace Central lets us publish to")
        pom_group = text_of(pom, "groupId") or text_of(pom, "parent/groupId")
        for field, expected, actual in (("groupId", group, pom_group), ("artifactId", artifact, text_of(pom, "artifactId")),
                                        ("version", ver, text_of(pom, "version") or text_of(pom, "parent/version"))):
            if actual != expected:
                errors.append(f"{where}: the POM's {field} is '{actual}', its directory says '{expected}'")
        if "SNAPSHOT" in ver.upper():
            errors.append(f"{where}: a SNAPSHOT version, which Central refuses")
        elif ver.endswith("-dev"):
            errors.append(f"{where}: a local -dev build, which is never a release")
        elif not VERSION_RULE.match(ver):
            errors.append(f"{where}: the version does not end in -ext.<N>")
        if version and ver != version:
            errors.append(f"{where}: the version is not {version}, the version being released")

        for field in ("name", "description", "url", "scm/url", "scm/connection"):
            if not text_of(pom, field):
                errors.append(f"{where}: the POM has no {field.replace('/', ' ')}")
        if not any(text_of(l, "name") and text_of(l, "url") for l in pom.findall(f"{q('licenses')}/{q('license')}")):
            errors.append(f"{where}: the POM has no license with a name and a url")
        if not any(text_of(d, "id") or text_of(d, "name") for d in pom.findall(f"{q('developers')}/{q('developer')}")):
            errors.append(f"{where}: the POM has no developer")

        packaging = text_of(pom, "packaging") or "jar"
        stem = f"{artifact}-{ver}"
        files = set(primary_files(directory))
        if packaging != "pom":
            main = {"jar": ".jar", "bundle": ".jar", "maven-plugin": ".jar"}.get(packaging, "." + packaging)
            if stem + main not in files and not any(f.startswith(stem + ".") and f not in (stem + ".pom", stem + ".module") for f in files):
                errors.append(f"{where}: no main artifact ({stem}{main}) for packaging {packaging}")
            for classifier in ("sources", "javadoc"):
                if f"{stem}-{classifier}.jar" not in files:
                    errors.append(f"{where}: no {classifier} jar")

        for name in files:
            path = os.path.join(directory, name)
            with open(path, "rb") as f:
                data = f.read()
            for ext, algorithm in CHECKSUMS.items():
                side = f"{path}.{ext}"
                if not os.path.isfile(side):
                    errors.append(f"{where}: {name} has no .{ext}")
                    continue
                with open(side) as f:
                    content = f.read().split()
                if not content or content[0].lower() != algorithm(data).hexdigest():
                    errors.append(f"{where}: {name}.{ext} does not match the file")
            if not os.path.isfile(path + ".asc"):
                errors.append(f"{where}: {name} has no .asc signature")
            elif verify:
                result = subprocess.run(["gpg", "--batch", "--verify", path + ".asc", path], capture_output=True)
                if result.returncode != 0:
                    errors.append(f"{where}: {name}.asc does not verify: {result.stderr.decode(errors='replace').strip()}")

        for dependency in pom.findall(f"{q('dependencies')}/{q('dependency')}"):
            d_group, d_artifact, d_version = (text_of(dependency, k) for k in ("groupId", "artifactId", "version"))
            if d_group.startswith(PREFIX) and d_version and "${" not in d_version and not d_version.startswith(("[", "(")):
                if not present(out, d_group, d_artifact, d_version):
                    errors.append(f"{where}: depends on {d_group}:{d_artifact}:{d_version}, which is not in the bundle")

        module = os.path.join(directory, f"{stem}.module")
        if os.path.isfile(module):
            errors += check_module(out, where, module, directory)

        for name in files:
            if name.endswith(".jar"):
                errors += check_plugin_markers(out, where, os.path.join(directory, name), ver)

    for coordinate in required:
        if coordinate not in seen:
            errors.append(f"{coordinate}: required, and not in the bundle")

    for error in errors:
        print(f"FAIL  {error}")
    print(f"{len(found)} component(s) checked, {len(errors)} problem(s)")
    return 1 if errors else 0


def check_module(out, where, path, directory):
    errors = []
    try:
        with open(path) as f:
            metadata = json.load(f)
    except ValueError as error:
        return [f"{where}: {os.path.basename(path)} is not JSON ({error})"]
    for variant in metadata.get("variants", []):
        at = variant.get("available-at")
        if at:
            group = at.get("group", "")
            if group.startswith(PREFIX) and not present(out, group, at.get("module", ""), at.get("version", "")):
                errors.append(f"{where}: variant {variant.get('name')} points at "
                              f"{group}:{at.get('module')}:{at.get('version')}, which is not in the bundle")
        for file in variant.get("files", []):
            if not os.path.isfile(os.path.join(directory, file.get("url", ""))):
                errors.append(f"{where}: variant {variant.get('name')} lists {file.get('url')}, which is not in the bundle")
        for dependency in variant.get("dependencies", []):
            group = dependency.get("group", "")
            requires = (dependency.get("version") or {}).get("requires", "")
            if group.startswith(PREFIX) and requires and not requires.startswith(("[", "(")):
                if not present(out, group, dependency.get("module", ""), requires):
                    errors.append(f"{where}: variant {variant.get('name')} depends on "
                                  f"{group}:{dependency.get('module')}:{requires}, which is not in the bundle")
    return errors


def check_plugin_markers(out, where, jar, version):
    errors = []
    try:
        with zipfile.ZipFile(jar) as archive:
            names = archive.namelist()
    except zipfile.BadZipFile:
        return [f"{where}: {os.path.basename(jar)} is not a valid jar"]
    for name in names:
        match = re.match(r"^META-INF/gradle-plugins/(.+)\.properties$", name)
        if match:
            plugin = match.group(1)
            if not present(out, plugin, f"{plugin}.gradle.plugin", version):
                errors.append(f"{where}: declares the Gradle plugin {plugin}, and its marker "
                              f"{plugin}:{plugin}.gradle.plugin:{version} is not in the bundle")
    return errors


# --- zip ---------------------------------------------------------------------------------

def bundle(out, path):
    count = 0
    with zipfile.ZipFile(path, "w", zipfile.ZIP_DEFLATED) as archive:
        for directory, _, files in os.walk(out):
            for name in sorted(files):
                full = os.path.join(directory, name)
                archive.write(full, os.path.relpath(full, out))
                count += 1
    print(f"wrote {path}: {count} file(s)")


def main():
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    commands = parser.add_subparsers(dest="command", required=True)
    p = commands.add_parser("stage")
    p.add_argument("--from", dest="sources", action="append", required=True)
    p.add_argument("--out", required=True)
    p.add_argument("--project", default="thisisthepy/compose-multiplatform-core-extended")
    p = commands.add_parser("sign")
    p.add_argument("dir")
    p.add_argument("--key")
    p = commands.add_parser("validate")
    p.add_argument("dir")
    p.add_argument("--version")
    p.add_argument("--require", action="append", default=[])
    p.add_argument("--verify-signatures", action="store_true")
    p = commands.add_parser("zip")
    p.add_argument("dir")
    p.add_argument("bundle")
    args = parser.parse_args()
    if args.command == "stage":
        stage([os.path.expanduser(s) for s in args.sources], args.out, args.project)
    elif args.command == "sign":
        sign(args.dir, args.key)
    elif args.command == "validate":
        return validate(args.dir, args.version, args.require, args.verify_signatures)
    elif args.command == "zip":
        bundle(args.dir, args.bundle)
    return 0


if __name__ == "__main__":
    sys.exit(main())
