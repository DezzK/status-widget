#!/usr/bin/env python3
#
# Copyright © 2026 Dezz (https://github.com/DezzK)
#
# This program is free software: you can redistribute it and/or modify
# it under the terms of the GNU General Public License as published by
# the Free Software Foundation, either version 3 of the License, or
# (at your option) any later version.
#
# This program is distributed in the hope that it will be useful,
# but WITHOUT ANY WARRANTY; without even the implied warranty of
# MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
# GNU General Public License for more details.
#
# You should have received a copy of the GNU General Public License
# along with this program.  If not, see <https://www.gnu.org/licenses/>.
#
"""Publish a status-widget release to the over-the-air update channel (a public Yandex.Disk folder the app reads).

    publish.py setup                        store the Yandex.Disk OAuth token in the macOS keychain
    publish.py status                       what the channel holds, the disk's free space
    publish.py publish VERSION [--notes-from-github | --notes [LANG=]FILE ...] [--apk FILE] [--yes]

CI runs `publish VERSION --notes-from-github --yes` when a GitHub release gets its description
(.github/workflows/publish-update.yml), with the token in the YANDEX_DISK_TOKEN secret; locally the token comes
from the keychain. A release the channel does not hold yet is uploaded notes first, then the APK. One it holds
already gets its notes brought up to date, and its package must be the very one on GitHub — a published APK is
never replaced.

The APK is the one CI built and attached to the GitHub release of VERSION (`gh release download`), unless --apk
names a local file. It is checked before anything is uploaded: named as the app looks for it, this app's package
at this version, reading this channel, not signed with a debug key.

The notes are plain UTF-8 text, shown in the app as written: the default ones (English) and a language's own,
which the app takes where it runs in that language. --notes-from-github makes them from the release's
description: the project's changelog is markdown, the Russian block, a `---` line, then the English block.

Stdlib only (the macOS /usr/bin/python3, 3.9). Every fact the app decides by — the channel link, the package's
file name, the layout, the version form, the notes' limit — is READ from the sources, never spelled here, so
this tool cannot publish something the app would read differently.

One-time setup: an OAuth app at https://oauth.yandex.ru/client/new under the account that owns the channel
folder (platform «Веб-сервисы», Redirect URI https://oauth.yandex.ru/verification_code, «Яндекс.Диск REST API»
with disk.info, disk.read and disk.write), then a token from
https://oauth.yandex.ru/authorize?response_type=token&client_id=<its ClientID>, then `publish.py setup`.
"""
from __future__ import annotations

import argparse
import getpass
import hashlib
import json
import os
import re
import subprocess
import sys
import tempfile
import time
import urllib.error
import urllib.parse
import urllib.request
from http import HTTPStatus
from pathlib import Path

# ── Where things are ─────────────────────────────────────────────────────────────────────────────────────────

ROOT = Path(__file__).resolve().parents[2]
APP_GRADLE = ROOT / "app/build.gradle"
CHANNEL_XML = ROOT / "app/src/main/res/values/update_channel.xml"
UPDATE_DIR = ROOT / "app/src/main/java/dezz/status/widget/update"
UPDATE_CHANNEL_JAVA = UPDATE_DIR / "UpdateChannel.java"
RELEASE_VERSION_JAVA = UPDATE_DIR / "ReleaseVersion.java"

#: The keychain item `setup` stores the disk's OAuth token in.
KEYCHAIN_TOKEN = "status-widget-yandex-disk"
#: Where CI hands the token over instead (a GitHub Actions secret); it wins over the keychain.
TOKEN_ENV = "YANDEX_DISK_TOKEN"
#: The languages of the blocks of a GitHub release's description, in order (None: the default notes).
GITHUB_NOTES_LANGUAGES = ("ru", None)
#: The line between those blocks.
GITHUB_NOTES_SEPARATOR = "---"
API = "https://cloud-api.yandex.net/v1/disk"
HTTP_TIMEOUT_S = 60
#: Entries per listing page (the API's ceiling is far above it).
PAGE = 200
UPLOAD_TIMEOUT_S = 600
#: How long the disk may take to report a fresh upload's digest.
DIGEST_WAIT_S = 180
#: The subject of the SDK's debug signing key — an APK signed with it cannot update a release install.
DEBUG_CERT_CN = "CN=Android Debug"


class Fail(Exception):
    """A problem that stops the run, with what to do about it."""


# ── Facts read from the sources ──────────────────────────────────────────────────────────────────────────────

def _source(path: Path) -> str:
    try:
        return path.read_text(encoding="utf-8")
    except OSError as e:
        raise Fail(f"cannot read {path}: {e}")


def _find(path: Path, pattern: str, what: str) -> re.Match:
    m = re.search(pattern, _source(path))
    if not m:
        raise Fail(f"{what} not found in {path.name} — this tool's anchor moved")
    return m


def java_string(path: Path, name: str) -> str:
    return re.sub(r"\\(.)", r"\1", _find(path, name + r'\s*=\s*"((?:[^"\\]|\\.)*)"', name).group(1))


def java_char(path: Path, name: str) -> str:
    return _find(path, name + r"\s*=\s*'(.)'", name).group(1)


def java_int(path: Path, name: str) -> int:
    """`name = <a product of integer literals>;` (32 * 1024)."""
    value = 1
    for factor in _find(path, name + r"\s*=\s*([0-9 *]+);", name).group(1).split("*"):
        value *= int(factor)
    return value


class Layout:
    """The channel as the app reads it (UpdateChannel, ReleaseVersion, the build's names)."""

    def __init__(self) -> None:
        self.link = _find(CHANNEL_XML, r'<string name="update_channel_folder"[^>]*>([^<]*)</string>',
                          "update_channel_folder").group(1).strip()
        self.package_name = _find(APP_GRADLE, r"def packageFileName = '([^']+)'", "packageFileName").group(1)
        self.app_id = _find(APP_GRADLE, r'namespace "([^"]+)"', "namespace").group(1)
        self.notes_name = java_string(UPDATE_CHANNEL_JAVA, "NOTES_NAME")
        self.notes_language_separator = java_char(UPDATE_CHANNEL_JAVA, "NOTES_LANGUAGE_SEPARATOR")
        self.notes_suffix = java_string(UPDATE_CHANNEL_JAVA, "NOTES_SUFFIX")
        self.notes_max_bytes = java_int(UPDATE_CHANNEL_JAVA, "NOTES_MAX_BYTES")
        self.package_suffix = java_string(UPDATE_CHANNEL_JAVA, "PACKAGE_SUFFIX")
        self.separator = java_char(UPDATE_CHANNEL_JAVA, "NAME_SEPARATOR")
        form = _find(RELEASE_VERSION_JAVA, r'FORM\s*=\s*Pattern\.compile\("((?:[^"\\]|\\.)*)"\)', "FORM").group(1)
        self.version_form = re.compile(form.replace("\\\\", "\\"))

    def version(self, text: str):
        """A comparable key for a version as the app orders them (ReleaseVersion.compareTo: the numbers, then a
        release above its pre-releases, then the suffix), or None when `text` is not a version."""
        m = self.version_form.fullmatch(text.strip())
        if not m:
            return None
        suffix = m.group(4)
        return int(m.group(1)), int(m.group(2)), int(m.group(3) or 0), suffix is None, suffix or ""

    def package_file(self, version: str) -> str:
        return f"{self.package_name}{self.separator}{version}{self.package_suffix}"

    def notes_file(self, language: str | None) -> str:
        """UpdateChannel.notesFile: the default notes' file for None, else the language's."""
        own = f"{self.notes_language_separator}{language}" if language else ""
        return f"{self.notes_name}{own}{self.notes_suffix}"

    def is_notes_file(self, name: str) -> bool:
        name = name.lower()
        return name == self.notes_file(None) or (
            name.startswith(self.notes_name + self.notes_language_separator) and name.endswith(self.notes_suffix))


# ── Tools ────────────────────────────────────────────────────────────────────────────────────────────────────

def run(cmd, capture: bool = True) -> str:
    proc = subprocess.run([str(c) for c in cmd], stdout=subprocess.PIPE if capture else None,
                          stderr=subprocess.PIPE if capture else None, text=True)
    if proc.returncode != 0:
        err = (proc.stderr or "").strip() if capture else ""
        raise Fail(f"{Path(str(cmd[0])).name} failed (exit {proc.returncode})" + (f": {err}" if err else ""))
    return proc.stdout if capture else ""


def build_tools() -> Path:
    sdk = Path(os.environ.get("ANDROID_HOME") or os.environ.get("ANDROID_SDK_ROOT")
               or Path.home() / "Library/Android/sdk")
    found = sorted((sdk / "build-tools").glob("*"),
                   key=lambda p: [int(x) if x.isdigit() else 0 for x in re.split(r"[.-]", p.name)])
    if not found:
        raise Fail(f"no build-tools under {sdk} — set ANDROID_HOME")
    return found[-1]


def sha256_of(path: Path) -> str:
    h = hashlib.sha256()
    with open(path, "rb") as f:
        for chunk in iter(lambda: f.read(1 << 20), b""):
            h.update(chunk)
    return h.hexdigest()


def check_apk(apk: Path, layout: Layout, version: str) -> None:
    """The APK is what the app will take for `version` — refused here rather than by every head unit."""
    if apk.name != layout.package_file(version):
        raise Fail(f"{apk.name}: the app looks for {layout.package_file(version)}")
    bt = build_tools()
    badging = run([bt / "aapt2", "dump", "badging", apk])
    m = re.search(r"package: name='([^']+)' versionCode='([^']+)' versionName='([^']*)'", badging)
    if not m:
        raise Fail(f"cannot read the package of {apk.name}")
    if m.group(1) != layout.app_id:
        raise Fail(f"{apk.name} is {m.group(1)}, not {layout.app_id}")
    if layout.version(m.group(3)) != layout.version(version):
        raise Fail(f"{apk.name} is version {m.group(3)}, not {version}")
    resources = run([bt / "aapt2", "dump", "resources", apk])
    m = re.search(r"string/update_channel_folder\n\s*\(\) \"?([^\"\n]*)", resources)
    if not m or m.group(1).strip() != layout.link:
        raise Fail(f"{apk.name} reads the channel {m.group(1) if m else '(none)'}, not {layout.link}")
    certs = run([bt / "apksigner", "verify", "--print-certs", apk])
    if DEBUG_CERT_CN in certs:
        raise Fail(f"{apk.name} is signed with the debug key — it cannot update a release install")
    print(f"{apk.name}: {layout.app_id} {version}, {apk.stat().st_size / 1e6:.1f} MB, release-signed, reads the channel")


def github_apk(layout: Layout, version: str, into: Path) -> Path:
    """The APK CI attached to the GitHub release of `version`."""
    name = layout.package_file(version)
    run(["gh", "release", "download", version, "--pattern", name, "--dir", into], capture=False)
    apk = into / name
    if not apk.is_file():
        raise Fail(f"the GitHub release {version} has no {name}")
    return apk


def plain_text(markdown: str) -> str:
    """A changelog's markdown as the app shows it — plain text: headings without their #, list items as bullets
    (nested ones indented), emphasis, code and links reduced to their text."""
    out = []
    for line in markdown.split("\n"):
        heading = re.match(r"\s{0,3}#{1,6}\s+(.*?)\s*#*\s*$", line)
        item = re.match(r"(\s*)[-*+]\s+(.*)$", line)
        if heading:
            line = heading.group(1)
        elif item:
            indent = len(item.group(1).expandtabs(2))
            line = " " * indent + ("◦ " if indent else "• ") + item.group(2)
        line = re.sub(r"!?\[([^\]]*)\]\([^)]*\)", r"\1", line)
        line = re.sub(r"(\*\*|__)(.+?)\1", r"\2", line)
        line = re.sub(r"(?<![\w*])\*(?=\S)(.+?)(?<=\S)\*(?![\w*])", r"\1", line)
        line = re.sub(r"`([^`]+)`", r"\1", line)
        out.append(line.rstrip())
    return re.sub(r"\n{3,}", "\n\n", "\n".join(out)).strip()


def github_notes(body: str) -> dict:
    """The notes a GitHub release's description makes, by language (GITHUB_NOTES_LANGUAGES). A description of one
    block alone (no translation) becomes the default notes, shown in every language."""
    blocks, current = [], []
    for line in body.replace("\r\n", "\n").split("\n"):
        if line.strip() == GITHUB_NOTES_SEPARATOR:
            blocks.append(current)
            current = []
        else:
            current.append(line)
    blocks.append(current)
    texts = [plain_text("\n".join(b)) for b in blocks]
    texts = [t for t in texts if t]
    if not texts:
        return {}
    if len(texts) == 1:
        return {None: texts[0]}
    if len(texts) != len(GITHUB_NOTES_LANGUAGES):
        raise Fail(f"the description has {len(texts)} blocks between «{GITHUB_NOTES_SEPARATOR}» lines; expected "
                   f"{len(GITHUB_NOTES_LANGUAGES)}: " + ", ".join(lang or "default" for lang in GITHUB_NOTES_LANGUAGES))
    return dict(zip(GITHUB_NOTES_LANGUAGES, texts))


def collect_notes(args, layout: Layout, version: str) -> dict:
    """The release's notes as the files to publish: {file name: UTF-8 bytes}."""
    texts = {}
    if args.notes_from_github:
        texts = github_notes(run(["gh", "release", "view", version, "--json", "body", "--jq", ".body"]))
        if not texts:
            raise Fail(f"the GitHub release {version} has no description yet")
    for spec in args.notes or []:
        language, _, path = spec.rpartition("=")
        try:
            texts[language or None] = Path(path).read_bytes().decode("utf-8").strip()
        except UnicodeDecodeError:
            raise Fail(f"{path} is not UTF-8")
        if not texts[language or None]:
            raise Fail(f"{path} is empty")
    files = {}
    for language, text in texts.items():
        data = text.encode("utf-8")
        if len(data) > layout.notes_max_bytes:
            raise Fail(f"the {language or 'default'} notes are {len(data)} bytes; the app reads at most "
                       f"{layout.notes_max_bytes}")
        files[layout.notes_file(language)] = data
    if files and None not in texts:
        print("!! no default notes: the app shows «what's new» only in " + ", ".join(sorted(texts)))
    return files


# ── Yandex.Disk ──────────────────────────────────────────────────────────────────────────────────────────────

def link_id(url: str) -> str:
    """A public link's identity, whatever host serves it (disk.yandex.ru/d/X, yadi.sk/d/X)."""
    m = re.search(r"/d/([^/?#]+)", url)
    return m.group(1) if m else url.rstrip("/")


class Disk:
    """The REST API: the public side for anyone, the owner's side with the OAuth token."""

    def __init__(self, token: str | None = None) -> None:
        self.token = token

    def request(self, method: str, url: str, ok=(HTTPStatus.OK,), data=None, headers=None, timeout=HTTP_TIMEOUT_S):
        req = urllib.request.Request(url, data=data, method=method, headers=dict(headers or {}))
        if self.token and url.startswith(API):
            req.add_header("Authorization", "OAuth " + self.token)
        try:
            with urllib.request.urlopen(req, timeout=timeout) as resp:
                body, status = resp.read(), resp.status
        except urllib.error.HTTPError as e:
            body, status = e.read(), e.code
        if status not in ok:
            try:
                detail = json.loads(body.decode("utf-8")).get("message", "")
            except (ValueError, UnicodeDecodeError):
                detail = ""
            raise Fail(f"Yandex.Disk: {method} {url.split('?')[0]} → HTTP {status} {detail}".rstrip())
        return status, (json.loads(body.decode("utf-8")) if body.strip() else {})

    @staticmethod
    def _q(**params) -> str:
        return urllib.parse.urlencode(params)

    # Public side
    def public_list(self, link: str, path: str = "/") -> list:
        items, offset = [], 0
        while True:
            _, d = self.request("GET", f"{API}/public/resources?" + self._q(public_key=link, path=path,
                                                                             limit=PAGE, offset=offset))
            page = d.get("_embedded", {})
            items += page.get("items", [])
            offset += len(page.get("items", []))
            if not page.get("items") or offset >= page.get("total", 0):
                return items

    # Owner's side
    def info(self) -> dict:
        return self.request("GET", f"{API}/")[1]

    def owned_path(self, link: str) -> str:
        offset = 0
        while True:
            _, d = self.request("GET", f"{API}/resources/public?" + self._q(type="dir", limit=PAGE, offset=offset))
            for item in d.get("items", []):
                if link_id(item.get("public_url", "")) == link_id(link):
                    return item["path"]
            offset += len(d.get("items", []))
            if len(d.get("items", [])) < PAGE:
                raise Fail(f"{link} is not a folder this token's account published — wrong account, or the "
                           "folder is no longer public")

    def meta(self, path: str) -> dict | None:
        status, d = self.request("GET", f"{API}/resources?" + self._q(path=path, fields="name,size,sha256,type"),
                                 ok=(HTTPStatus.OK, HTTPStatus.NOT_FOUND))
        return d if status == HTTPStatus.OK else None

    def mkdir(self, path: str) -> None:
        self.request("PUT", f"{API}/resources?" + self._q(path=path), ok=(HTTPStatus.CREATED,))

    def delete(self, path: str) -> None:
        """To the disk's trash, not for good."""
        self.request("DELETE", f"{API}/resources?" + self._q(path=path),
                     ok=(HTTPStatus.NO_CONTENT, HTTPStatus.ACCEPTED))

    def upload(self, local: Path, path: str, overwrite: bool = False) -> None:
        _, d = self.request("GET", f"{API}/resources/upload?" + self._q(path=path,
                                                                        overwrite=str(overwrite).lower()))
        size = local.stat().st_size
        with open(local, "rb") as f:
            self.request(d.get("method", "PUT"), d["href"], ok=(HTTPStatus.CREATED, HTTPStatus.ACCEPTED), data=f,
                         headers={"Content-Length": str(size), "Content-Type": "application/octet-stream"},
                         timeout=UPLOAD_TIMEOUT_S)
        deadline = time.time() + DIGEST_WAIT_S
        while True:
            m = self.meta(path)
            if m and m.get("sha256"):
                break
            if time.time() > deadline:
                raise Fail(f"{path}: the disk did not report the upload's digest within {DIGEST_WAIT_S} s")
            time.sleep(3)
        if m.get("size") != size or m["sha256"].lower() != sha256_of(local):
            raise Fail(f"{path}: the disk holds {m.get('size')} bytes, sha256 {m['sha256'][:16]}… — not the local file")


def disk_token() -> str:
    """CI's secret from the environment, else the token `setup` stored — read by `security`, so it never passes
    through argv."""
    token = os.environ.get(TOKEN_ENV, "").strip()
    if token:
        return token
    try:
        proc = subprocess.run(["security", "find-generic-password", "-s", KEYCHAIN_TOKEN, "-w"],
                              stdout=subprocess.PIPE, stderr=subprocess.DEVNULL, text=True)
        token = proc.stdout.strip() if proc.returncode == 0 else ""
    except FileNotFoundError:   # not macOS
        token = ""
    if not token:
        raise Fail(f"no Yandex.Disk token: set {TOKEN_ENV}, or store it in the keychain with `publish.py setup`")
    return token


def published_versions(layout: Layout) -> list:
    """The release folders the channel holds, newest first: (key, name)."""
    found = [(layout.version(i["name"]), i["name"]) for i in Disk().public_list(layout.link) if i.get("type") == "dir"]
    return sorted([f for f in found if f[0] is not None], reverse=True)


# ── Commands ─────────────────────────────────────────────────────────────────────────────────────────────────

def cmd_setup(args) -> int:
    if not sys.stdin.isatty():
        raise Fail("setup asks for the token: run it in a terminal")
    print(f"Type or paste the Yandex.Disk OAuth token when `security` asks for the password "
          f"(keychain item '{KEYCHAIN_TOKEN}').")
    proc = subprocess.run(["security", "add-generic-password", "-U", "-s", KEYCHAIN_TOKEN, "-a", getpass.getuser(),
                           "-l", "status-widget releases (Yandex.Disk)", "-w"])
    if proc.returncode != 0:
        raise Fail("the keychain did not take the token")
    layout, disk = Layout(), Disk(disk_token())
    info = disk.info()
    print(f"token: OK — {info.get('user', {}).get('display_name', '?')}, "
          f"{(info['total_space'] - info['used_space']) / 1e9:.2f} GB free")
    print(f"channel: {layout.link} → {disk.owned_path(layout.link)}")
    return 0


def cmd_status(args) -> int:
    layout = Layout()
    versions = published_versions(layout)
    print(f"channel  {layout.link}")
    if not versions:
        print("  (empty)")
    for i, (_, name) in enumerate(versions[:5]):
        files = Disk().public_list(layout.link, "/" + name) if i == 0 else []
        print(f"  {name}" + (": " + ", ".join(f["name"] for f in files) if files else ""))
    try:
        info = Disk(disk_token()).info()
        print(f"disk: {(info['total_space'] - info['used_space']) / 1e9:.2f} GB free")
    except Fail as e:
        print(f"disk: {e}")
    return 0


def cmd_publish(args) -> int:
    layout = Layout()
    version = args.version
    want = layout.version(version)
    if want is None:
        raise Fail(f"{version} is not a version the app reads")
    versions = published_versions(layout)
    held = next((name for key, name in versions if key == want), None)
    if held is None and versions and versions[0][0] > want and not args.allow_older:
        raise Fail(f"the channel holds a newer release, {versions[0][1]} — the app would never offer {version} "
                   "(--allow-older to go on anyway)")
    notes = collect_notes(args, layout, version)
    if not notes:
        print("!! no release notes: the app shows no «what's new» for this release")

    with tempfile.TemporaryDirectory(prefix="status-widget-publish-") as tmp:
        apk = Path(args.apk) if args.apk else github_apk(layout, version, Path(tmp))
        check_apk(apk, layout, version)
        size, sha = apk.stat().st_size, sha256_of(apk)
        for name, data in notes.items():
            (Path(tmp) / name).write_bytes(data)
        disk = Disk(disk_token())
        root = disk.owned_path(layout.link)
        if held is None:
            publish_release(disk, layout, f"{root}/{version}", apk, notes, Path(tmp), args.yes)
            folder_name = version
        else:
            sync_notes(disk, layout, f"{root}/{held}", held, apk.name, size, sha, notes, Path(tmp), args.yes)
            folder_name = held

    # What the app will see: the public side, read as it reads it.
    listed = {i["name"].lower(): i for i in Disk().public_list(layout.link, "/" + folder_name)}
    item = listed.get(apk.name.lower(), {})
    if item.get("size") != size or (item.get("sha256") or "").lower() != sha:
        raise Fail(f"{apk.name} is not as uploaded on the public side")
    for name, data in notes.items():
        if (listed.get(name, {}).get("sha256") or "").lower() != hashlib.sha256(data).hexdigest():
            raise Fail(f"{name} is not as uploaded on the public side")
    print(f"\nThe channel holds {version} as published.")
    return 0


def confirm(yes: bool) -> None:
    if yes:
        return
    if not sys.stdin.isatty() or input("Type 'publish' to go on: ").strip() != "publish":
        raise Fail("not published")


def publish_release(disk: Disk, layout: Layout, folder: str, apk: Path, notes: dict, tmp: Path, yes: bool) -> None:
    """A release the channel does not hold: its folder, the notes, then the package."""
    info = disk.info()
    free = info["total_space"] - info["used_space"]
    need = apk.stat().st_size + sum(len(d) for d in notes.values())
    if free < need:
        raise Fail(f"the disk has {free / 1e6:.0f} MB free; this release needs {need / 1e6:.0f} MB")
    print(f"Publishing to {folder}/: " + ", ".join(sorted(notes) + [apk.name]))
    confirm(yes)
    disk.mkdir(folder)
    # The notes first: the app takes a release the moment its package appears.
    for name in sorted(notes):
        print(f"uploading {name}")
        disk.upload(tmp / name, f"{folder}/{name}")
    print(f"uploading {apk.name} ({apk.stat().st_size / 1e6:.1f} MB)")
    disk.upload(apk, f"{folder}/{apk.name}")


def sync_notes(disk: Disk, layout: Layout, folder: str, held: str, apk_name: str, size: int, sha: str,
               notes: dict, tmp: Path, yes: bool) -> None:
    """A release the channel holds already: its package must be this one; its notes become these."""
    listed = {i["name"].lower(): i for i in Disk().public_list(layout.link, "/" + held) if i.get("type") == "file"}
    item = listed.get(apk_name.lower())
    if item is None:
        raise Fail(f"the channel's {held} holds no {apk_name} — finish or remove that release by hand")
    if item.get("size") != size or (item.get("sha256") or "").lower() != sha:
        raise Fail(f"the channel's {held} holds another {apk_name} than the GitHub release — a published "
                   "package is never replaced")
    upload = [n for n, d in notes.items()
              if (listed.get(n, {}).get("sha256") or "").lower() != hashlib.sha256(d).hexdigest()]
    stale = [i["name"] for n, i in listed.items() if layout.is_notes_file(n) and n not in notes]
    if not upload and not stale:
        print(f"The channel holds {held} with these notes already.")
        return
    print(f"Updating the notes of {folder}/: " + ", ".join(
        [f"upload {n}" for n in sorted(upload)] + [f"remove {n}" for n in sorted(stale)]))
    confirm(yes)
    for name in sorted(upload):
        print(f"uploading {name}")
        disk.upload(tmp / name, f"{folder}/{name}", overwrite=True)
    for name in stale:
        print(f"removing {name}")
        disk.delete(f"{folder}/{name}")


def main(argv=None) -> int:
    p = argparse.ArgumentParser(description=__doc__.split("\n")[0])
    sub = p.add_subparsers(dest="cmd", required=True)
    sub.add_parser("setup").set_defaults(fn=cmd_setup)
    sub.add_parser("status").set_defaults(fn=cmd_status)
    pub = sub.add_parser("publish")
    pub.add_argument("version", help="the release's tag, e.g. v2.13.0")
    pub.add_argument("--notes-from-github", action="store_true",
                     help="make the notes from the GitHub release's description")
    pub.add_argument("--notes", action="append", metavar="[LANG=]FILE",
                     help="notes in plain UTF-8 text: the default ones, or LANG's (ru=notes-ru.txt); repeatable")
    pub.add_argument("--apk", help="a local APK instead of the GitHub release's")
    pub.add_argument("--yes", action="store_true", help="do not ask before uploading")
    pub.add_argument("--allow-older", action="store_true", help="publish below a newer release")
    pub.set_defaults(fn=cmd_publish)
    args = p.parse_args(argv)
    try:
        return args.fn(args)
    except Fail as e:
        print(f"!! {e}", file=sys.stderr)
        return 1
    except KeyboardInterrupt:
        print("\n!! interrupted", file=sys.stderr)
        return 130


if __name__ == "__main__":
    sys.exit(main())
