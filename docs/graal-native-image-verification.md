# GraalVM Native Image Upcall Verification Log

## Step 0: Merge develop
- Command: `git merge --no-edit develop`
- Result: Merged successfully into `work/graal-upcall` (exit code 0).

## Step 1: Toolchain Location
- Found Liberica Native Image Kit at: `/Volumes/macMini/toolchains/bellsoft-liberica-vm-full-openjdk25-25.0.4`
- Version: `OpenJDK 25.0.4 Liberica-NIK-25.0.4-1 (build 25.0.4+10-LTS)`
- Executable: `/Volumes/macMini/toolchains/bellsoft-liberica-vm-full-openjdk25-25.0.4/Contents/Home/bin/native-image`
- Notes: Default system JDK is Java 21 (`/Users/ibrew/Library/Java/JavaVirtualMachines/jdk-21.0.12+8/Contents/Home`). Setting `JAVA_HOME` to JDK 25 directly causes Gradle Daemon's internal Kotlin compiler script parser to fail (`JavaVersion.parse("25.0.4")`). Therefore, `JAVA_HOME` must remain JDK 21 while specifying `-Porg.gradle.java.installations.paths=/Volumes/macMini/toolchains/bellsoft-liberica-vm-full-openjdk25-25.0.4` for Gradle Toolchain launcher to use JDK 25 for native compile.

## Step 2: JVM Sanity Check (`:sample:runNativeImageUpcallDemo`)
- Command: `JAVA_HOME=/Users/ibrew/Library/Java/JavaVirtualMachines/jdk-21.0.12+8/Contents/Home ./gradlew :sample:runNativeImageUpcallDemo --console=plain -Porg.gradle.java.installations.paths=/Volumes/macMini/toolchains/bellsoft-liberica-vm-full-openjdk25-25.0.4`
- Result: SUCCESS (exit code 0, 42s)
- Summary:
  - `KOTLIN: table = 41 entries, 4 classes, from io_github_thisisthepy_sample`
  - `PYTHON: resolved handle = 4294967324`
  - `PYTHON: invoke result = 7`
  - `PYTHON: @PythonInternal entry resolves to -1`
  - `PYTHON: UPCALL_OK`
  - `PYTHON: PROXY_OK`

## Step 3: GraalVM Native Image Build (`:sample:nativeCompile`)
- Command: `JAVA_HOME=/Users/ibrew/Library/Java/JavaVirtualMachines/jdk-21.0.12+8/Contents/Home ./gradlew :sample:nativeCompile --console=plain -Porg.gradle.java.installations.paths=/Volumes/macMini/toolchains/bellsoft-liberica-vm-full-openjdk25-25.0.4`
- Result: SUCCESS (exit code 0, 46s total build time, 41.7s native-image generation time)
- Output Binary: `/Volumes/macMini/worktrees/graal-upcall/sample/build/native/nativeCompile/upcall-native-demo` (18.98 MB executable)
- Analysis Stats:
  - 4,312 types, 5,003 fields, and 20,604 methods reachable
  - 1,323 types registered for reflection
  - 28 downcalls and 5 upcalls registered for foreign access

## Step 4: GraalVM Native Image Execution & Output Verification (`:sample:runNativeUpcallDemo`)
- Command: `JAVA_HOME=/Users/ibrew/Library/Java/JavaVirtualMachines/jdk-21.0.12+8/Contents/Home ./gradlew :sample:runNativeUpcallDemo --console=plain -Porg.gradle.java.installations.paths=/Volumes/macMini/toolchains/bellsoft-liberica-vm-full-openjdk25-25.0.4`
- Result: SUCCESS (exit code 0)

### Five-Line Comparison with ROADMAP §7 Baseline

| Output Line | Baseline (ROADMAP §7) | Current Observed Output (GraalVM Native Image) | Difference & Judgment |
|---|---|---|---|
| 1 | `KOTLIN: table = 19 entries, 3 classes, from io_github_thisisthepy_sample` | `KOTLIN: table = 41 entries, 4 classes, from io_github_thisisthepy_sample` | **Normal change (정상)**: KSP generated entries for newly added `Greeter` class and its members (methods, getters, setters, companion object, suspending functions) in `ExposedToPython.kt`. |
| 2 | `PYTHON: resolved handle = 4294967314` | `PYTHON: resolved handle = 4294967324` | **Normal change (정상)**: Runtime handle address varies per run / table size. |
| 3 | `PYTHON: invoke result = 7` | `PYTHON: invoke result = 7` | **Identical (동일)**: `DemoCounter.press()` ran 7 times and returned 7 via upcall. |
| 4 | `PYTHON: @PythonInternal entry resolves to -1` | `PYTHON: @PythonInternal entry resolves to -1` | **Identical (동일)**: Opt-out policy strictly enforced in closed-world binary. |
| 5 | `PYTHON: UPCALL_OK` | `PYTHON: UPCALL_OK` | **Identical (동일)**: Python -> Kotlin upcall path completed successfully inside native image. |

- Additional Marker Observed: `PYTHON: PROXY_OK` (verifying generated Python proxy module, Panama stubs, asyncio coroutine completion thread in native image).

## Final Assessment
- Build Succeeded: **YES**
- Upcall Path Functional in GraalVM Native Image: **YES**
- Exposure Policy (`@PythonInternal`) Preserved: **YES**
- Regression Detected: **NO** (All output differences are expected expansions from KSP table generation of newly introduced bindings).
