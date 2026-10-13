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
"""The publisher's own rules: how a GitHub changelog becomes the app's notes, and that the notes' file names it
reads from UpdateChannel are the ones the app looks for. From the repository root:
`python3 -m unittest discover -s tools/release`."""
from __future__ import annotations

import hashlib
import tempfile
import unittest
from pathlib import Path
from unittest import mock

import publish


class PlainTextTest(unittest.TestCase):

    def test_markdown_becomes_the_text_the_app_shows(self):
        md = "## New\n\n### Icons\n\nThe **icon** switch, see [docs](https://x.y) and `code`.\n\n" \
             "- one *two*\n  - nested\n* star"
        self.assertEqual("New\n\nIcons\n\nThe icon switch, see docs and code.\n\n"
                         "• one two\n  ◦ nested\n• star", publish.plain_text(md))

    def test_blank_runs_collapse_and_the_ends_are_trimmed(self):
        self.assertEqual("a\n\nb", publish.plain_text("\n\na\n\n\n\nb\n\n"))

    def test_words_with_asterisks_inside_are_left_alone(self):
        self.assertEqual("5*3*2 and a_b_c", publish.plain_text("5*3*2 and a_b_c"))


class GithubNotesTest(unittest.TestCase):

    def test_the_russian_block_then_the_english_one(self):
        notes = publish.github_notes("## Новое\r\n\r\n- пункт\r\n\r\n---\r\n\r\n## New\n\n- item\n")
        self.assertEqual({"ru": "Новое\n\n• пункт", None: "New\n\n• item"}, notes)

    def test_one_block_is_the_default_notes(self):
        self.assertEqual({None: "Новое"}, publish.github_notes("## Новое\n"))

    def test_no_description_is_no_notes(self):
        self.assertEqual({}, publish.github_notes("  \n"))

    def test_more_blocks_than_languages_is_refused(self):
        with self.assertRaises(publish.Fail):
            publish.github_notes("a\n---\nb\n---\nc")


class LayoutTest(unittest.TestCase):
    """Pinned against UpdateChannelTest, which states the same names on the app's side."""

    def test_the_notes_files_the_app_looks_for(self):
        layout = publish.Layout()
        self.assertEqual("release-notes.txt", layout.notes_file(None))
        self.assertEqual("release-notes-ru.txt", layout.notes_file("ru"))
        self.assertTrue(layout.is_notes_file("Release-Notes-RU.txt"))
        self.assertFalse(layout.is_notes_file("status-widget-v2.13.0.apk"))

    def test_the_package_the_app_looks_for(self):
        self.assertEqual("status-widget-v2.13.0.apk", publish.Layout().package_file("v2.13.0"))


class SyncNotesTest(unittest.TestCase):
    """A release the channel holds already: its notes follow the description, its package is never replaced."""

    APK = "status-widget-v2.13.0.apk"

    def setUp(self):
        self.layout = publish.Layout()
        self.tmp = tempfile.TemporaryDirectory()
        self.dir = Path(self.tmp.name)
        self.disk = mock.Mock(spec=publish.Disk)

    def tearDown(self):
        self.tmp.cleanup()

    def sync(self, listed, notes, apk_sha="a" * 64):
        for name, data in notes.items():
            (self.dir / name).write_bytes(data)
        with mock.patch.object(publish.Disk, "public_list", return_value=listed):
            publish.sync_notes(self.disk, self.layout, "disk:/updates/v2.13.0", "v2.13.0", self.APK, 10, apk_sha,
                               notes, self.dir, yes=True)

    @staticmethod
    def file(name, data=b"", size=None, sha=None):
        return {"name": name, "type": "file", "size": len(data) if size is None else size,
                "sha256": sha or hashlib.sha256(data).hexdigest()}

    def test_changed_notes_are_uploaded_and_stale_ones_removed(self):
        listed = [self.file(self.APK, size=10, sha="a" * 64), self.file("release-notes.txt", b"old"),
                  self.file("Release-Notes-DE.txt", b"alt")]
        self.sync(listed, {"release-notes.txt": b"new", "release-notes-ru.txt": b"\xd0\xbd"})
        uploaded = sorted(c.args[1] for c in self.disk.upload.call_args_list)
        self.assertEqual(["disk:/updates/v2.13.0/release-notes-ru.txt", "disk:/updates/v2.13.0/release-notes.txt"],
                         uploaded)
        self.assertTrue(all(c.kwargs.get("overwrite") for c in self.disk.upload.call_args_list))
        self.disk.delete.assert_called_once_with("disk:/updates/v2.13.0/Release-Notes-DE.txt")

    def test_notes_as_published_change_nothing(self):
        self.sync([self.file(self.APK, size=10, sha="a" * 64), self.file("release-notes.txt", b"same")],
                  {"release-notes.txt": b"same"})
        self.disk.upload.assert_not_called()
        self.disk.delete.assert_not_called()

    def test_another_package_is_never_replaced(self):
        with self.assertRaises(publish.Fail):
            self.sync([self.file(self.APK, size=10, sha="b" * 64)], {"release-notes.txt": b"new"})
        self.disk.upload.assert_not_called()


if __name__ == "__main__":
    unittest.main()
