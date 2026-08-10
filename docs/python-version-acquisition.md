# CPython Version Acquisition Research

## 1. Desktop Platforms (macOS, Linux, Windows)

The project currently uses `python-build-standalone` (maintained by Astral). For Python 3.14 and 3.15, this project continues to publish pre-built self-contained binaries. 

*   **Release Tags:** Releases are tagged by date (e.g., `20260807`).
*   **Asset Naming Scheme:** The asset naming follows the pattern: `cpython-<python-version>+<build-date>-<target>-<flavour>.<ext>`.
    *   Targets include: `aarch64-apple-darwin`, `x86_64-apple-darwin`, `x86_64-unknown-linux-gnu`, `x86_64-pc-windows-msvc`, `aarch64-pc-windows-msvc`.
    *   Flavours include: `install_only`, `install_only_stripped`, and `full` (which also includes debug symbols, pgo/lto variants). The archives are available in `.tar.gz` and `.tar.zst` formats.
*   **URL Pattern:** `https://github.com/astral-sh/python-build-standalone/releases/download/<release-tag>/<asset-name>`

## 2. Free-threaded Variants

`python-build-standalone` publishes free-threaded builds for Python 3.14 and 3.15.
*   **Naming:** The string `freethreaded` is injected into the asset name before the flavour, e.g., `cpython-3.14.7+20260807-aarch64-apple-darwin-freethreaded-install_only.tar.gz`.
*   **ABI and Libraries:** The ABI suffix is `t`, meaning the shared libraries will be named `libpython3.14t.dylib` or `libpython3.14t.so`.
*   **Availability:** They are available for all major desktop targets (macOS, Linux, Windows).

## 3. Android and iOS Support

*   **Upstream Status:** PEP 730 (iOS) and PEP 738 (Android) established these as Tier 3 supported platforms in CPython, starting from Python 3.13. Official source code now supports building for these mobile OSes.
*   **`python-build-standalone` Status:** Despite upstream support, `python-build-standalone` **does not** publish prebuilt binaries for `android` or `ios`. 
*   **Conclusion:** It is not currently achievable to drop the kivy toolchain (or manual prebuilt) dependency for iOS and Android by switching to `python-build-standalone`. We must continue using the existing committed assets or kivy toolchains for mobile platforms.

## 4. Stable ABI Implications (PEP 803)

*   **The Constraint:** This project relies entirely on the CPython Stable ABI for its ~330 bindings.
*   **Python 3.13 & 3.14:** The traditional Stable ABI (`abi3`) **does not** support free-threaded (GIL-disabled) builds. If we attempt to use a free-threaded 3.14 build, our bindings relying on `abi3` will fail or crash due to opaque structural changes in `PyObject`.
*   **Python 3.15 (PEP 803):** Python 3.15 introduces `abi3t`, a new variant of the Stable ABI specifically for free-threaded builds.
*   **Conclusion:** We cannot use free-threaded variants with the Stable ABI in 3.14. We must wait for (or test against) 3.15 to utilize `abi3t` for our bindings.
