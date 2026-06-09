# Third-Party Notices

FlashTransfer source code is licensed under the MIT License. The following
third-party components are used when building the distributable applications.
All listed licenses permit commercial use, subject to their terms.

## Android application

| Component | Purpose | License |
| --- | --- | --- |
| ZXing Core 3.5.3 | QR code generation | Apache License 2.0 |
| Android SDK / Android Gradle Plugin | Build tools and platform APIs | Apache License 2.0 and Android SDK terms |

ZXing's Apache-2.0 license permits commercial use. Redistributors must provide
a copy of the license and retain applicable notices.

## Windows executable

| Component | Purpose | License |
| --- | --- | --- |
| Python 3 | Runtime | Python Software Foundation License 2 |
| PyInstaller | EXE packaging | GPL-2.0-or-later with a commercial distribution exception |
| OpenCV / opencv-python | QR code generation | Apache License 2.0 / MIT |
| FFmpeg bundled by opencv-python | OpenCV media runtime | LGPL-2.1 |
| NumPy | OpenCV runtime dependency | BSD-3-Clause |
| OpenBLAS and bundled numerical runtimes | NumPy runtime dependencies | BSD-3-Clause and runtime exceptions |
| psutil | Optional dependency collected by the packaged build | BSD-3-Clause |

The exact third-party binaries included in a Windows build depend on the local
Python environment and PyInstaller hooks. Before distributing an EXE, inspect
the generated bundle and include the full license files shipped with every
collected dependency.

## Compliance checklist for binary releases

1. Include this notice and the full applicable third-party license texts.
2. Retain copyright, attribution, and NOTICE files.
3. For the opencv-python wheel, include its `LICENSE.txt` and
   `LICENSE-3RD-PARTY.txt`; its wheels include FFmpeg under LGPL-2.1.
4. Include the NumPy wheel's `LICENSE.txt`, which also lists bundled runtime
   components.
5. Do not imply endorsement by third-party projects or use their trademarks as
   your product branding.

Full license copies used by the current Windows build are stored in the
[`licenses`](licenses) directory. Run `build_release.ps1` to create a release
package that includes these files.

This file is an engineering compliance summary, not legal advice.

## Upstream references

- PyInstaller license: https://pyinstaller.org/en/stable/license.html
- opencv-python licensing: https://github.com/opencv/opencv-python
- NumPy license: https://numpy.org/doc/stable/license
- ZXing license questions: https://github.com/zxing/zxing/wiki/License-Questions
- Python license: https://docs.python.org/3/license.html
