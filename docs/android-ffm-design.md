# Android FFM Design Document (PanamaPort Analysis)

This document provides an implementation design for replacing the existing Kotlin/Native JNI bridge on Android with a Panama-style FFM approach, based on an analysis of the PanamaPort reference implementation.

## 1. The Bootstrap Problem

**Question:** Calling `libLLVM.so` from Java itself requires an FFI mechanism, but FFI is what LLVM is being used to build. How does PanamaPort break this circularity?

**Answer:** PanamaPort breaks the circularity by bypassing the need for a native stub entirely for simple function signatures. In `BulkLinker.java` (lines 436-492), the method `requireNativeStub` evaluates whether an LLVM trampoline is necessary. For functions whose signatures map cleanly to primitive types and pointers (like `LONG_AS_WORD` or `BOOL_AS_INT`) and use standard JNI or `@CriticalNative` (`CallType.CRITICAL`), `requireNativeStub` evaluates to `false`.

For these functions, the "irreducible primitive" is `ArtMethodUtils.registerNativeMethod()`. This method takes a Java `Method` object and a raw C memory address (from `dlsym`), and directly injects the C pointer into the internal Android Runtime (ART) `ArtMethod` struct, replacing its native entry point. This bottoms out entirely on Unsafe memory writes and reflection to locate the struct in memory. It requires zero native FFI machinery to bootstrap.

## 2. ART Method Patching

**Question:** How does `ArtMethodUtils.registerNativeMethod` actually work? What ART-internal structures does it touch, how does it locate them, and how version-sensitive is this across Android releases?

**Answer:** 
It works by directly modifying the C++ `ArtMethod` struct used by ART. 
1. It locates the `ArtMethod` memory address by parsing the `java.lang.reflect.Method` object using `Reflection.getArtMethod(Executable)` (which uses Unsafe to extract the hidden `artMethod` pointer).
2. It overwrites the `data_` (or `entry_point_from_jni_`) field of the struct with the raw C function pointer using `AndroidUnsafe.putWordN()`.

This is **extremely version-sensitive**. In `ArtMethodUtils.java` (lines 48-115), PanamaPort hardcodes four different memory layouts for the `ArtMethod` struct:
- `art_method_8xx_layout` (Android 8.0, 8.1)
- `art_method_9_layout` (Android 9)
- `art_method_11_10_layout` (Android 10, 11)
- `art_method_16p1_12_layout` (Android 12 to 15/16)

These layouts explicitly track C++ field additions/removals across ART versions (e.g., `dex_cache_resolved_methods_` or `hotness_count_` unions).

## 3. Hidden API Restrictions

**Question:** Android restricts reflective access to non-SDK interfaces. How does PanamaPort get around that, and what are the consequences?

**Answer:** PanamaPort completely bypasses Android's Hidden API checks by avoiding standard Java reflection (`Class.getDeclaredMethods`, `VMRuntime.setHiddenApiExemptions`) entirely. 
In `Reflection.java` (lines 48-100 and 350-650), it manually parses the internal C++ `mirror::Class` struct (`ClassMirror`) to extract the native arrays of `methods` and `fields`. It then uses Unsafe to instantiate a fake `MethodHandleImpl`, sets its internal pointer to the hidden `ArtMethod`, and uses `MethodHandles.reflectAs` to convert it back into a usable `Method` or `Field` object.

**Consequences:** 
- It requires **no manifest flags**.
- It **will break** on any future Android version where the internal memory layout of `mirror::Class` or `MethodHandleImpl` changes, requiring continuous maintenance of offset tables.

## 4. Minimum Viable Subset for THIS Project

### Downcalls
Required for ~330 CPython functions. Since these signatures only use `void`, `Int`, `Long`, `Double`, opaque pointers, and `String` (which can be passed as `const char*` pointers), they map perfectly to Java `@CriticalNative` methods (using `long` for pointers). LLVM is not required for this.

### Upcalls
Required for letting Python call back into Kotlin. 
PanamaPort's upcall path (`_AndroidLinkerImpl.java` lines 954-1100) generates an LLVM C stub that:
1. Obtains the `JNIEnv*` (via `ENVGetter.java` which looks up thread-locals or calls `AttachCurrentThread`).
2. Packages the native registers/arguments.
3. Invokes JNI `CallStaticVoidMethod` on a generated Java stub.
4. The Java stub converts the raw pointers to `MemorySegment` objects, invokes the target `MethodHandle`, and writes the return value back to native memory using raw Unsafe writes.
5. The LLVM stub checks for exceptions via JNI `ExceptionCheck` and aborts if one is found.

### VarHandle
**No, a VarHandle backport is NOT unavoidable.** 
In PanamaPort's own upcall path (`_AndroidLinkerImpl.java` lines 1334-1353), the return value is written back to native memory using `UpcallHelper.putInt`, `putAddress`, etc., which rely exclusively on `AndroidUnsafe.putIntN`. It does not use `VarHandle` for argument marshalling. Since this target project has its own `NativePointer` abstraction, we can avoid `VarHandle` entirely and use direct Unsafe accesses.

### Struct-by-value and variadic calls
CPython's Stable ABI intentionally avoids struct-by-value to maintain binary compatibility, relying exclusively on opaque pointers (e.g., `PyObject*`, `PyThreadState*`). Variadic calls (like `PyArg_ParseTuple`) exist, but modern bridges generally prefer array-based or vectorcall alternatives (`PyObject_Vectorcall`, `PyObject_CallObject` with tuples). The Stable ABI does not strictly force us to implement struct-by-value or variadic FFI handling.

### Memory-lifetime management
Upcall stubs must outlive individual calls, effectively matching the lifetime of the Python interpreter, as CPython stores these function pointers in long-lived structures (like `PyTypeObject.tp_new`). The minimum required is a simple list of allocated native memory blocks (stubs) that are freed upon `Py_Finalize`.

**What can be dropped from PanamaPort:**
- Everything in the `LLVM/` module (JIT is unnecessary, see Question 6).
- All 40 files in `Core/src/openjdk/` (`Arena`, `MemorySegment`, `GroupLayout`, etc.).
- The `VarHandles` module.

**What is kept:**
- `Unsafe/src/main/java/com/v7878/unsafe/ArtMethodUtils.java` (Method patching).
- `Unsafe/src/main/java/com/v7878/unsafe/Reflection.java` (Hidden API bypass).
- `Unsafe/src/main/java/com/v7878/unsafe/foreign/LibDL.java` (ELF parsing to locate `dlopen`/`dlsym`).
- `AndroidUnsafe.java`.

## 5. A Cheaper Bootstrap for this Project?

**Question:** Could the existing Kotlin/Native layer supply `dlopen`/`dlsym` and the LLVM entry points directly?

**Answer:** Yes, technically Kotlin/Native could export a generic `@CName("my_dlopen")` and `@CName("my_dlsym")` wrapper. 
**Why it would NOT work (or is highly undesirable):** The primary motivation for adopting a Panama-style FFM approach on Android is to entirely eliminate the Kotlin/Native Android build target (and the associated JNI bridge maintenance). If we retain `libmultiplatform_python3.13.so` simply to bootstrap FFI, we fail to eliminate the Kotlin/Native Android compilation step. Furthermore, as shown in Question 1, `dlopen` can be bootstrapped via `LibDL.java` parsing the Android linker's ELF symbol table, meaning the Kotlin/Native bridge is completely unnecessary.

## 6. Is LLVM Even Necessary Here?

### Downcalls
**No.** Because the ~330 CPython functions have fixed, primitive/pointer-only signatures, we can map them directly to Java `@CriticalNative` methods. `ArtMethodUtils.registerNativeMethod` can patch the `dlsym` address directly into the Java method. 
- **Binary size:** Drops `libLLVM.so` dependency.
- **Startup cost:** Instant (no JIT compilation).
- **Android-version fragility:** Avoids relying on the undocumented/restricted `libLLVM.so` on Android.

### Upcalls
**No.** CPython upcalls (callbacks) also have a small, fixed set of signature shapes (e.g., `PyCFunction`, `destructor`, `getter`). Instead of using LLVM to JIT-compile a unique C trampoline for every Java method at runtime, we can pre-generate a tiny NDK C library containing a handful of generic C trampolines. 
These trampolines can look up the correct Java callback (e.g., using a method ID attached to the Python `self` object via a `PyCapsule`) and invoke it via JNI `CallStaticVoidMethod`.

**Hybrid viability:** A pure-Java `@CriticalNative` approach for downcalls (patched via `ArtMethodUtils`), combined with a tiny NDK-compiled C library containing 3-4 static trampolines for upcalls, is the most robust, performant, and maintainable solution.

## 7. Licensing

**Question:** Which of the 40 vendored OpenJDK files (under GPLv2) in `Core/src/openjdk/` would a reimplementation actually need?

**Answer:** **None.** 
Because this project has its own `NativePointer` abstraction and does not need to expose a public FFM API, we do not need `MemorySegment`, `Arena`, `MemorySessionImpl`, or `ValueLayout`. Memory reads/writes can be done directly using `Unsafe`, exactly as PanamaPort's internal `UpcallHelper` does. 

## 8. Recommended Plan

**Stage 1: Core Patching Machinery**
- Port `AndroidUnsafe`, `Reflection`, `ArtMethodUtils`, and `LibDL` from PanamaPort.
- Verify that `LibDL` can locate `dlopen`/`dlsym` and that `ArtMethodUtils` can bind `Py_GetVersion` to a `@CriticalNative` Java method.
- *Risk/Effort:* Moderate. *Verification:* Requires a real Android emulator/device to verify `ArtMethodUtils` struct offsets.

**Stage 2: Downcall Generation**
- Write a code generator to emit `@CriticalNative` Kotlin declarations for the 330 CPython functions.
- Write the Kotlin initialization code that iterates over these methods, calls `dlsym`, and registers them using `ArtMethodUtils`.
- *Risk/Effort:* Low. *Verification:* Emulator/device required.

**Stage 3: Upcall Trampolines**
- Create a minimal NDK C project containing generic C trampolines for the required CPython callback shapes (e.g., `PyCFunction`).
- Implement the JNI lookup to route the C call back to the Kotlin `NativePointer` callback registry.
- *Risk/Effort:* Moderate. (Requires re-introducing a tiny NDK build, but vastly simpler than LLVM JIT).

**Stage 4: Migration & Cleanup**
- Reroute all Python calls in the Kotlin shared code to use the new `@CriticalNative` downcalls.
- Delete the old Kotlin/Native Android JNI bridge code and Android-specific Kotlin/Native build targets.
- *Risk/Effort:* High effort, low risk. *Verification:* Run the existing multiplatform test suite on Android.
