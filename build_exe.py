"""Build helper that avoids an inaccessible Windows user-site directory."""

from __future__ import annotations

import site
import sys

from PyInstaller.__main__ import run


site.getusersitepackages = lambda: ""

run(
    [
        "--noconfirm",
        "--clean",
        "--onefile",
        "--name",
        "FlashTransfer",
        "--add-data",
        "web;web",
        "server.py",
    ]
)
