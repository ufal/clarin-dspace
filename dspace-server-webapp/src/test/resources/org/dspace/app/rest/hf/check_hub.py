#!/usr/bin/env python3
"""Drive the ``huggingface_hub`` client against the DSpace HF facade.

Usage: check_hub.py <repo_id> <expected_size>

Exits 0 when every check passes, 1 (with a diagnostic message on stdout) otherwise.
Deliberately avoids ``assert`` (stripped under ``python -O``) in favour of explicit
checks that raise or print-and-exit.
"""
import os
import re
import sys

import huggingface_hub
from huggingface_hub import hf_hub_download, model_info, snapshot_download

SHA_RE = re.compile(r"^[0-9a-f]{40}$")


def fail(message):
    print("FAIL: " + message)
    sys.exit(1)


def main():
    if len(sys.argv) != 3:
        fail("usage: check_hub.py <repo_id> <expected_size>")

    repo_id = sys.argv[1]
    expected_size = int(sys.argv[2])

    print("huggingface_hub version: " + huggingface_hub.__version__)

    info = model_info(repo_id)
    sha = info.sha
    if not isinstance(sha, str) or not SHA_RE.match(sha):
        fail("model_info(%s).sha is not a 40-char lowercase hex string: %r" % (repo_id, sha))
    print("sha: " + sha)

    filenames = [sibling.rfilename for sibling in info.siblings]
    print("siblings: " + ", ".join(filenames))
    if not filenames:
        fail("model_info(%s) reported no siblings" % repo_id)

    for filename in filenames:
        path = hf_hub_download(repo_id, filename)
        size = os.path.getsize(path)
        if size != expected_size:
            fail("hf_hub_download(%s, %s) produced %d bytes, expected %d"
                 % (repo_id, filename, size, expected_size))
        print("hf_hub_download OK: %s (%d bytes)" % (filename, size))

    snapshot_dir = snapshot_download(repo_id)
    print("snapshot_download dir: " + snapshot_dir)
    checked = 0
    for root, _dirs, files in os.walk(snapshot_dir, followlinks=True):
        for name in files:
            if not name.endswith(".gguf"):
                continue
            full_path = os.path.join(root, name)
            size = os.path.getsize(full_path)
            if size != expected_size:
                fail("snapshot file %s is %d bytes, expected %d" % (full_path, size, expected_size))
            print("snapshot file OK: %s (%d bytes)" % (full_path, size))
            checked += 1
    if checked == 0:
        fail("snapshot_download(%s) produced no .gguf files" % repo_id)

    print("OK")
    sys.exit(0)


if __name__ == "__main__":
    main()
