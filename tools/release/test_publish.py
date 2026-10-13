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
import io
import tempfile
import urllib.error
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


class BringUpToDateTest(unittest.TestCase):
    """The channel's folder of a release ends up holding these notes and this package: a new release notes
    first, a publish cut short finished, a published one's notes synced — and its package never replaced."""

    APK = "status-widget-v2.13.0.apk"
    FOLDER = "disk:/updates/v2.13.0"

    def setUp(self):
        self.layout = publish.Layout()
        self.tmp = tempfile.TemporaryDirectory()
        self.dir = Path(self.tmp.name)
        self.apk = self.dir / self.APK
        self.apk.write_bytes(b"0123456789")
        self.sha = hashlib.sha256(b"0123456789").hexdigest()
        self.disk = mock.Mock(spec=publish.Disk)
        self.disk.info.return_value = {"total_space": 10 ** 9, "used_space": 0}

    def tearDown(self):
        self.tmp.cleanup()

    def bring(self, listed, notes):
        for name, data in notes.items():
            (self.dir / name).write_bytes(data)
        held = None if listed is None else {i["name"].lower(): i for i in listed}
        publish.bring_up_to_date(self.disk, self.layout, self.FOLDER, held, self.apk, self.sha, notes, self.dir,
                                 yes=True)

    @staticmethod
    def file(name, data=b""):
        return {"name": name, "type": "file", "size": len(data), "sha256": hashlib.sha256(data).hexdigest()}

    def uploaded(self):
        return [c.args[1].rsplit("/", 1)[-1] for c in self.disk.upload.call_args_list]

    def test_a_new_release_gets_its_folder_its_notes_and_then_its_package(self):
        self.bring(None, {"release-notes.txt": b"en", "release-notes-ru.txt": b"ru"})
        self.disk.mkdir.assert_called_once_with(self.FOLDER)
        self.assertEqual(["release-notes-ru.txt", "release-notes.txt", self.APK], self.uploaded())

    def test_a_publish_cut_short_is_finished(self):
        self.bring([self.file("release-notes-ru.txt", b"ru")], {"release-notes.txt": b"en", "release-notes-ru.txt": b"ru"})
        self.disk.mkdir.assert_not_called()
        self.assertEqual(["release-notes.txt", self.APK], self.uploaded())

    def test_changed_notes_are_uploaded_and_stale_ones_removed(self):
        self.bring([self.file(self.APK, b"0123456789"), self.file("release-notes.txt", b"old"),
                    self.file("Release-Notes-DE.txt", b"alt")],
                   {"release-notes.txt": b"new", "release-notes-ru.txt": b"ru"})
        self.assertEqual(["release-notes-ru.txt", "release-notes.txt"], self.uploaded())
        self.assertTrue(all(c.kwargs.get("overwrite") for c in self.disk.upload.call_args_list))
        self.disk.delete.assert_called_once_with(self.FOLDER + "/Release-Notes-DE.txt")

    def test_a_release_as_published_changes_nothing(self):
        self.bring([self.file(self.APK, b"0123456789"), self.file("release-notes.txt", b"same")],
                   {"release-notes.txt": b"same"})
        self.disk.upload.assert_not_called()
        self.disk.delete.assert_not_called()

    def test_another_package_is_never_replaced(self):
        with self.assertRaises(publish.Fail):
            self.bring([self.file(self.APK, b"another one")], {"release-notes.txt": b"new"})
        self.disk.upload.assert_not_called()


class UploadRetryTest(unittest.TestCase):
    """The uploader's transient failures are tried again with a fresh link; a lasting one stops the run."""

    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.file = Path(self.tmp.name) / "release-notes.txt"
        self.file.write_bytes(b"notes")
        sha = hashlib.sha256(b"notes").hexdigest()
        self.disk = publish.Disk("token")
        self.disk.meta = mock.Mock(return_value={"size": 5, "sha256": sha})
        self.links = []
        self.puts = 0

    def tearDown(self):
        self.tmp.cleanup()

    def answer(self, *failures):
        """request() that hands out upload links and fails the PUTs with `failures`, in order, then succeeds."""
        failures = list(failures)

        def request(method, url, **kwargs):
            if method == "GET":
                self.links.append(url)
                return 200, {"href": f"https://uploader/{len(self.links)}", "method": "PUT"}
            self.puts += 1
            if failures:
                raise failures.pop(0)
            return 201, {}
        self.disk.request = request

    def test_a_transient_failure_is_tried_again_with_a_fresh_link_that_overwrites(self):
        self.answer(publish.Transient("HTTP 502"))
        with mock.patch.object(publish.time, "sleep"):
            self.disk.upload(self.file, "disk:/updates/v2.13.0/release-notes.txt")
        self.assertEqual(2, self.puts)
        self.assertIn("overwrite=false", self.links[0])
        self.assertIn("overwrite=true", self.links[1])

    def test_a_lasting_failure_stops_the_run(self):
        self.answer(*[publish.Transient("HTTP 502")] * publish.UPLOAD_TRIES)
        with mock.patch.object(publish.time, "sleep"), self.assertRaises(publish.Transient):
            self.disk.upload(self.file, "disk:/updates/v2.13.0/release-notes.txt")
        self.assertEqual(publish.UPLOAD_TRIES, self.puts)

    def test_a_refusal_is_not_tried_again(self):
        self.answer(publish.Fail("HTTP 409"))
        with self.assertRaises(publish.Fail):
            self.disk.upload(self.file, "disk:/updates/v2.13.0/release-notes.txt")
        self.assertEqual(1, self.puts)


class TransientTest(unittest.TestCase):
    """What is worth trying again: the server's 5xx and a lost connection — not a refusal."""

    def fails_with(self, error):
        with mock.patch.object(publish.urllib.request, "urlopen", side_effect=error):
            with self.assertRaises(publish.Fail) as caught:
                publish.Disk().request("PUT", "https://uploader/x")
        return caught.exception

    @staticmethod
    def http(code):
        return urllib.error.HTTPError("https://uploader/x", code, "", {}, io.BytesIO(b""))

    def test_a_server_error_is_transient(self):
        self.assertIsInstance(self.fails_with(self.http(502)), publish.Transient)

    def test_a_lost_connection_is_transient(self):
        self.assertIsInstance(self.fails_with(urllib.error.URLError("reset")), publish.Transient)

    def test_a_refusal_is_not(self):
        self.assertNotIsInstance(self.fails_with(self.http(409)), publish.Transient)


if __name__ == "__main__":
    unittest.main()
