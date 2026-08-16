package python.multiplatform.env

import platform.Foundation.NSBundle

/**
 * iOS's answer to "where did the payload land": `python/` inside the app bundle's resource
 * directory — **if** the Xcode project was told to copy it there, which nothing in this repository
 * or in `toolchain` does yet.
 *
 * ### What exists and what does not
 *
 * `toolchain`'s `49da1d8` *produces* the payload for iOS and says plainly that it stops there:
 * "iOS is produced and not attached: a native framework has no Gradle resource mechanism, and its
 * resources go through an Xcode phase in a project file this plugin does not own." So the staged
 * directory sits at `build/pythonStaging/ios/python` in the consumer's Gradle build and nothing
 * carries it into the `.app`.
 *
 * The runtime half is here and is complete: once the directory is in the bundle, this finds it and
 * `Python3.initialize()` puts it on `sys.path` with no further work. What is missing is exactly
 * three things, and they are packaging rather than runtime:
 *
 * 1. **A Copy Bundle Resources build phase** in the consumer's Xcode project referencing
 *    `$(SRCROOT)/../<module>/build/pythonStaging/ios/python`, run after the Gradle build phase
 *    that produces it, with "Create folder references" semantics — a *group* would flatten the
 *    package directories and every `__init__.py` would collide.
 * 2. **A build-order dependency** on that Gradle task, so the phase does not copy an empty or
 *    stale directory on a clean checkout. `iosApp/` in this repository drives Gradle from a Run
 *    Script phase already, which is where the ordering has to be expressed.
 * 3. **A device/simulator run that proves it**, which is not the same check as desktop's. See
 *    `iosMain/README.md`: a path under this workspace's external volume does not merely fail for
 *    an *installed* app, it parks `open$NOCANCEL` forever with 0% CPU. A payload read out of the
 *    app's own bundle is not subject to that, which is precisely why the answer has to be the
 *    bundle and not a `PYTHONHOME`-adjacent workspace path.
 *
 * Until (1) and (2) exist this returns an empty list on every run, and no test in this repository
 * can distinguish "the phase is missing" from "the code is wrong" — which is why the *desktop*
 * path is the one driven end to end.
 *
 * ### `resourcePath`, not `bundlePath`
 *
 * They are the same directory on iOS and different on macOS (`Contents/Resources`). Asking for the
 * resource path is what a Copy Bundle Resources phase actually fills, on either.
 */
internal actual fun discoverStagedPayloadRoots(): List<String> {
    val resources = NSBundle.mainBundle.resourcePath ?: return emptyList()
    val candidate = "$resources/${PythonPayload.PAYLOAD_ROOT}"
    // pathIsAccessible is nativeMain's access(path, R_OK) -- the same probe PythonHomeCheck uses,
    // so "readable" means the same thing on both paths.
    return if (pathIsAccessible(candidate)) listOf(candidate) else emptyList()
}
