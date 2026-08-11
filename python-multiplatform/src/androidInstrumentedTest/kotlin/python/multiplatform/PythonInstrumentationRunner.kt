package python.multiplatform

import androidx.test.runner.AndroidJUnitRunner
import python.native.ffi.PythonOnDevice

/**
 * Brings up CPython before any test class is loaded.
 *
 * `commonTest` is platform-agnostic by construction: its fixture reaches straight for
 * `Python3.initialize()`. On desktop and the iOS simulator that is enough, because the build
 * hands those runs a `PYTHONHOME` with a real standard library behind it. On Android the stdlib
 * lives inside the APK as assets and has to be unpacked to app-private storage first — without
 * that, `Py_Initialize()` does not fail, it *aborts the process* with "Failed to import encodings
 * module", taking every other test on the device with it.
 *
 * Doing it in the runner rather than in a `@Before` keeps `commonTest` free of Android knowledge,
 * which it has to be — it also compiles for iOS and desktop.
 *
 * [PythonOnDevice.ensureInitialised] is idempotent, so the instrumented tests that call it
 * themselves are unaffected.
 */
class PythonInstrumentationRunner : AndroidJUnitRunner() {

    /**
     * `onStart` runs on the instrumentation thread — the same thread that then executes the
     * tests. That matters: `Python3.initialize()` leaves the GIL held by whichever thread called
     * `Py_Initialize()`, so initialising here (rather than in `onCreate`, which runs on the main
     * thread) keeps the GIL on the thread that is about to use it.
     */
    override fun onStart() {
        PythonOnDevice.ensureInitialised()
        super.onStart()
    }
}
