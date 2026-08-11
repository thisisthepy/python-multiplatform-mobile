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

*   **Android:** Prebuilt artifacts for Android are provided natively by python.org. These archives are plain NDK cross-compilations (e.g., CPython's own build tree). They can be found at `https://www.python.org/ftp/python/<version>/python-<version>-<arch>-linux-android.tar.gz`.
*   **iOS:** Prebuilt artifacts for iOS are provided by the BeeWare project via their `Python-Apple-support` repository, which are CPython's own iOS build layouts (e.g. `prefix: iOS/Frameworks/...`). They are located at `https://github.com/beeware/Python-Apple-support/releases`. The kivy toolchains are **not** the source of these artifacts.
*   **python-build-standalone Status:** `python-build-standalone` has moved from indygreg to Astral (`https://github.com/astral-sh/python-build-standalone`). It **does not** publish prebuilt binaries for `android` or `ios`. 
*   **Prebuilt-cpython Status:** The `python/prebuilt-cpython` repository is currently a planning repository containing no artifacts, so it is not a source.
*   **Conclusion:** We acquire Android artifacts directly from python.org, and iOS artifacts from BeeWare's Python-Apple-support, eliminating the need for kivy toolchain dependency.

## 4. Stable ABI Implications (PEP 803)

*   **The Constraint:** This project relies entirely on the CPython Stable ABI for its ~330 bindings.
*   **Python 3.13 & 3.14:** The traditional Stable ABI (`abi3`) **does not** support free-threaded (GIL-disabled) builds. If we attempt to use a free-threaded 3.14 build, our bindings relying on `abi3` will fail or crash due to opaque structural changes in `PyObject`.
*   **Python 3.15 (PEP 803):** Python 3.15 introduces `abi3t`, a new variant of the Stable ABI specifically for free-threaded builds.
*   **Conclusion:** We cannot use free-threaded variants with the Stable ABI in 3.14. We must wait for (or test against) 3.15 to utilize `abi3t` for our bindings.

## 5. Security and Integrity Verification

*   **Desktop:** `python-build-standalone` provides a `SHA256SUMS` file with every release. The build script verifies the downloaded archive against this manifest.
*   **Android (python.org):** The official python.org distribution publishes `.sig`, `.crt`, and `.sigstore` files alongside the Android tarballs (e.g., `python-<version>-<arch>-linux-android.tar.gz.sigstore`). They do not publish a plain checksum manifest. Full Sigstore verification directly inside Gradle is unreasonable without shelling out to external tools (like `sigstore-python` or the `cosign` CLI) to verify the certificate chain and OIDC identity. Because of this, we currently lock the expected SHA-256 digest in our own repository lockfile (`python-checksums.properties`) to ensure integrity. If full Sigstore verification is desired in the future, it would require executing an external verification tool against those published `.sigstore` bundles.
*   **iOS (BeeWare):** The `Python-Apple-support` releases do not provide any checksums or signatures. Integrity is similarly enforced via our internal lockfile.
