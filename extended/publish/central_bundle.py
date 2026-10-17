#!/usr/bin/env python3
"""Turns what a local Maven publish left behind into a Maven Central bundle, and checks it.

Usage:
  central_bundle.py stage    --from <repo> [--from <repo>...] --out <dir> [--project <owner/repo>]
                             [--dev-suffix=<-dev.sha>]
  central_bundle.py sign     <dir> [--key <id>] [--checksums-only]
  central_bundle.py validate <dir> [--version <v> | --dev=<-dev.sha>] [--require <group:artifact>...]
                                   [--verify-signatures | --unsigned]
  central_bundle.py zip      <dir> <bundle.zip>
  central_bundle.py put      <dir> <repository url>

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
         With --dev-suffix every staged version V becomes V<suffix>, everywhere it is named:
         directories, file names, each POM's own version and the versions of the fork's
         artifacts it depends on, and in each Gradle module the component, its variants'
         available-at targets, file names and urls, and dependency versions. A reference left
         at V would send a consumer of the dev build back to the release.
sign     writes .md5, .sha1, .sha256 and .sha512 beside every file, and an ASCII-armoured
         detached signature (.asc) made with gpg, which must hold the secret key already.
         GPG_PASSPHRASE, when set, is the key's passphrase. --checksums-only writes the
         checksums and no signatures, for a repository that does not ask for them.
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
         With --verify-signatures each .asc is checked with gpg; with --unsigned no .asc is
         asked for. --dev=<suffix> checks a dev build instead of a release: every version ends
         in <suffix>, and no POM or Gradle module names a fork artifact at any version
         without the suffix. Exits 1 on any failure.
zip      writes the bundle Central's upload API takes: the directory in Maven layout.
put      uploads <dir> to a Maven repository over HTTP PUT (GitHub Packages), each component's
         files first and its POM last, then a maven-metadata.xml per artifact that keeps the
         versions already there. Reads MAVEN_USERNAME and MAVEN_PASSWORD. A version that is
         already in the repository is refused by GitHub Packages, so a rerun fails loudly.
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
import base64
import urllib.error
import urllib.request
import xml.etree.ElementTree as ET
import zipfile

PREFIX = "org.thisisthepy"
POM_NS = "http://maven.apache.org/POM/4.0.0"
XSI_NS = "http://www.w3.org/2001/XMLSchema-instance"
CHECKSUMS = {"md5": hashlib.md5, "sha1": hashlib.sha1, "sha256": hashlib.sha256, "sha512": hashlib.sha512}
SIDE_FILES = tuple("." + ext for ext in CHECKSUMS) + (".asc",)
SKIPPED = ("maven-metadata", "_remote.repositories", ".lastUpdated")
VERSION_RULE = re.compile(r"^\d+(\.\d+)*(-[A-Za-z0-9.]+)?-ext\.\d+$")
DEV_SUFFIX = re.compile(r"^-dev\.[0-9a-f]{7,40}$")
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

def stage(sources, out, project, dev_suffix=None):
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
    if dev_suffix:
        rename_versions(out, dev_suffix)

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


def rename_versions(out, suffix):
    if not DEV_SUFFIX.match(suffix):
        sys.exit(f"error: a dev suffix is -dev.<commit>, not '{suffix}'")
    found = list(components(out))
    mapping = {(g, a, v): v + suffix for g, a, v, _ in found}

    def new(group, artifact, version):
        return mapping.get((group, artifact, version), version)

    for group, artifact, version, directory in found:
        target = new(group, artifact, version)
        old_stem, new_stem = f"{artifact}-{version}", f"{artifact}-{target}"
        pom_path = os.path.join(directory, old_stem + ".pom")
        tree = ET.parse(pom_path)
        pom = tree.getroot()
        child(pom, "version").text = target
        for dependency in pom.iter(q("dependency")):
            version_element = dependency.find(q("version"))
            if version_element is not None and version_element.text:
                version_element.text = new(text_of(dependency, "groupId"), text_of(dependency, "artifactId"),
                                           version_element.text.strip())
        tree.write(pom_path, encoding="UTF-8", xml_declaration=True)

        module_path = os.path.join(directory, old_stem + ".module")
        if os.path.isfile(module_path):
            with open(module_path) as f:
                metadata = json.load(f)
            rename_in_module(metadata, new, old_stem, new_stem)
            with open(module_path, "w") as f:
                json.dump(metadata, f, indent=2)

        for name in os.listdir(directory):
            if name.startswith(old_stem):
                os.rename(os.path.join(directory, name), os.path.join(directory, new_stem + name[len(old_stem):]))
        os.rename(directory, os.path.join(os.path.dirname(directory), target))
    print(f"renamed {len(found)} component(s) to <version>{suffix}")


def rename_reference(reference, new):
    """A {group, module, version, url} reference to another component, as available-at and a
    target module's component block hold."""
    group, module, version = reference.get("group", ""), reference.get("module", ""), reference.get("version", "")
    target = new(group, module, version)
    if target == version:
        return
    reference["version"] = target
    if "url" in reference:
        reference["url"] = (reference["url"].replace(f"/{version}/", f"/{target}/")
                            .replace(f"{module}-{version}.", f"{module}-{target}."))


def rename_in_module(metadata, new, old_stem, new_stem):
    component = metadata.get("component", {})
    if "url" in component:
        rename_reference(component, new)
    else:
        component["version"] = new(component.get("group", ""), component.get("module", ""), component.get("version", ""))
    for variant in metadata.get("variants", []):
        if "available-at" in variant:
            rename_reference(variant["available-at"], new)
        for file in variant.get("files", []):
            for key in ("name", "url"):
                if file.get(key, "").startswith(old_stem):
                    file[key] = new_stem + file[key][len(old_stem):]
        for key in ("dependencies", "dependencyConstraints"):
            for dependency in variant.get(key, []):
                constraint = dependency.get("version") or {}
                for kind in ("requires", "strictly", "prefers"):
                    if kind in constraint:
                        constraint[kind] = new(dependency.get("group", ""), dependency.get("module", ""), constraint[kind])


def fork_references(pom, metadata):
    """(where, group, module, version) for every fork artifact a POM or Gradle module names."""
    for dependency in pom.iter(q("dependency")):
        group = text_of(dependency, "groupId")
        if group.startswith(PREFIX):
            yield "POM dependency", group, text_of(dependency, "artifactId"), text_of(dependency, "version")
    if metadata is None:
        return
    component = metadata.get("component", {})
    yield "module component", component.get("group", ""), component.get("module", ""), component.get("version", "")
    for variant in metadata.get("variants", []):
        at = variant.get("available-at")
        if at:
            yield f"variant {variant.get('name')}", at.get("group", ""), at.get("module", ""), at.get("version", "")
        for key in ("dependencies", "dependencyConstraints"):
            for dependency in variant.get(key, []):
                constraint = dependency.get("version") or {}
                for kind in ("requires", "strictly", "prefers"):
                    if kind in constraint:
                        yield (f"variant {variant.get('name')} {key[:-1] if key.endswith('s') else key}",
                               dependency.get("group", ""), dependency.get("module", ""), constraint[kind])


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

def sign(out, key, checksums_only=False):
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
            count += 1
            if checksums_only:
                continue
            command = ["gpg", "--batch", "--yes", "--armor", "--detach-sign", "--output", f"{path}.asc"]
            if key:
                command += ["--local-user", key]
            if passphrase:
                command += ["--pinentry-mode", "loopback", "--passphrase-fd", "0"]
            subprocess.run(command + [path], input=(passphrase or "").encode(), check=True)
    print(f"{'checksummed' if checksums_only else 'signed and checksummed'} {count} file(s)")


# --- validate ----------------------------------------------------------------------------

def present(out, group, artifact, version):
    base = os.path.join(out, *group.split("."), artifact, version, f"{artifact}-{version}")
    return os.path.isfile(base + ".pom") or os.path.isfile(base + ".module")


def validate(out, version, required, verify, dev=None, unsigned=False):
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
        elif dev:
            # A dev build carries whatever versions the build has (the window modules' own
            # line among them until every line is one), each with the suffix.
            base = ver[:-len(dev)] if ver.endswith(dev) else ""
            if not base or "-dev" in base:
                errors.append(f"{where}: a dev build's version is <version>{dev}")
        elif ver.endswith("-dev") or "-dev." in ver:
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
            if unsigned:
                pass
            elif not os.path.isfile(path + ".asc"):
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
        metadata = None
        if os.path.isfile(module):
            errors += check_module(out, where, module, directory)
            try:
                with open(module) as f:
                    metadata = json.load(f)
            except ValueError:
                pass
        if dev:
            for kind, r_group, r_module, r_version in fork_references(pom, metadata):
                if r_group.startswith(PREFIX) and r_version and not r_version.endswith(dev):
                    errors.append(f"{where}: its {kind} names {r_group}:{r_module}:{r_version}, a fork version "
                                  f"without {dev}, which would pull a consumer of this dev build back to a release")

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


def put(out, url):
    user, password = os.environ.get("MAVEN_USERNAME"), os.environ.get("MAVEN_PASSWORD")
    if not (user and password):
        sys.exit("error: MAVEN_USERNAME and MAVEN_PASSWORD must be set")
    auth = "Basic " + base64.b64encode(f"{user}:{password}".encode()).decode()
    url = url.rstrip("/")

    def request(method, path, data=None):
        req = urllib.request.Request(f"{url}/{path}", data=data, method=method, headers={"Authorization": auth})
        for attempt in range(3):
            try:
                with urllib.request.urlopen(req, timeout=300) as response:
                    return response.read()
            except urllib.error.HTTPError as error:
                if method == "GET" and error.code == 404:
                    return None
                if error.code < 500 or attempt == 2:
                    sys.exit(f"error: {method} {path}: HTTP {error.code} {error.read().decode(errors='replace')[:500]}")
            except urllib.error.URLError as error:
                if attempt == 2:
                    sys.exit(f"error: {method} {path}: {error}")

    artifacts = {}
    count = 0
    for group, artifact, version, directory in components(out):
        names = sorted(os.listdir(directory), key=lambda n: (n.split(".")[-1] in CHECKSUMS or n.endswith(".asc"),
                                                              ".pom" in n, n))
        for name in names:
            with open(os.path.join(directory, name), "rb") as f:
                request("PUT", os.path.relpath(os.path.join(directory, name), out).replace(os.sep, "/"), f.read())
            count += 1
        artifacts.setdefault((group, artifact), []).append(version)
        print(f"put   {group}:{artifact}:{version}")

    for (group, artifact), versions in artifacts.items():
        path = "/".join(group.split(".") + [artifact, "maven-metadata.xml"])
        existing = request("GET", path)
        known = []
        if existing:
            try:
                known = [v.text for v in ET.fromstring(existing).iter("version") if v.text]
            except ET.ParseError:
                known = []
        known += [v for v in versions if v not in known]
        body = ("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n<metadata>\n"
                f"  <groupId>{group}</groupId>\n  <artifactId>{artifact}</artifactId>\n  <versioning>\n"
                f"    <latest>{known[-1]}</latest>\n    <versions>\n"
                + "".join(f"      <version>{v}</version>\n" for v in known)
                + "    </versions>\n  </versioning>\n</metadata>\n").encode()
        request("PUT", path, body)
        for ext, algorithm in (("md5", hashlib.md5), ("sha1", hashlib.sha1)):
            request("PUT", f"{path}.{ext}", algorithm(body).hexdigest().encode())
    print(f"put {count} file(s) for {len(artifacts)} artifact(s) to {url}")


def main():
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    commands = parser.add_subparsers(dest="command", required=True)
    p = commands.add_parser("stage")
    p.add_argument("--from", dest="sources", action="append", required=True)
    p.add_argument("--out", required=True)
    p.add_argument("--project", default="thisisthepy/compose-multiplatform-core-extended")
    p.add_argument("--dev-suffix")
    p = commands.add_parser("sign")
    p.add_argument("dir")
    p.add_argument("--key")
    p.add_argument("--checksums-only", action="store_true")
    p = commands.add_parser("validate")
    p.add_argument("dir")
    p.add_argument("--version")
    p.add_argument("--require", action="append", default=[])
    p.add_argument("--verify-signatures", action="store_true")
    p.add_argument("--unsigned", action="store_true")
    p.add_argument("--dev")
    p = commands.add_parser("zip")
    p.add_argument("dir")
    p.add_argument("bundle")
    p = commands.add_parser("put")
    p.add_argument("dir")
    p.add_argument("url")
    args = parser.parse_args()
    if args.command == "stage":
        stage([os.path.expanduser(s) for s in args.sources], args.out, args.project, args.dev_suffix)
    elif args.command == "sign":
        sign(args.dir, args.key, args.checksums_only)
    elif args.command == "validate":
        if args.dev and not DEV_SUFFIX.match(args.dev):
            sys.exit(f"error: a dev suffix is -dev.<commit>, not '{args.dev}'")
        return validate(args.dir, args.version, args.require, args.verify_signatures, args.dev, args.unsigned)
    elif args.command == "put":
        put(args.dir, args.url)
    elif args.command == "zip":
        bundle(args.dir, args.bundle)
    return 0


if __name__ == "__main__":
    sys.exit(main())
