# Project Structure

## Source Code

- `server.py`: Windows/Python server and desktop entry point.
- `web/`: browser interface served by the Windows application.
- `android/`: native Android application and bundled web interface.
- `shared_files/`: runtime transfer directory; only `.gitkeep` is tracked.

## Build

- `build_exe.py`: builds the Windows executable with PyInstaller.
- `build_release.ps1`: builds and assembles Windows and Android packages.
- `requirements-build.txt`: pinned Python build dependencies.

Generated directories including `build/`, `dist/`, `release/`, and
`android/app/build/` are ignored by Git.

## Legal And Community

- `LICENSE`: FlashTransfer source license.
- `THIRD_PARTY_NOTICES.md`: dependency and binary distribution obligations.
- `licenses/`: third-party license texts included with releases.
- `CONTRIBUTING.md`: contribution guide.
- `SECURITY.md`: vulnerability reporting policy.
