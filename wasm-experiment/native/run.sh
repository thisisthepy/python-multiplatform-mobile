#!/bin/bash
# Reproduces the experiments. Needs emsdk (~/emsdk) and the Node that Gradle downloaded.
#
#   Test A  @WasmImport binds to an Emscripten export.                            (still current)
#   Test C  that AND a shared linear memory, in one instantiation graph.          (the live result)
#   Test B  Emscripten importing a memory Kotlin exports.        (historical -- see the note below)
#
# Test B was the shape forced by Kotlin <= 2.4.10, which DEFINED and exported its linear memory.
# 2.4.20-Beta2 imports it instead, so there is no Kotlin-exported memory to hand over any more and
# Test B's premise is gone. It is kept behind RUN_LEGACY_B=1 only to re-read the old result against
# an old toolchain; it does not run by default and will not pass on 2.4.20-Beta2.
set -e
cd "$(dirname "$0")/.."
NODE=$(find ~/.gradle/nodejs -name node -type f -perm +111 | head -1)
source ~/emsdk/emsdk_env.sh > /dev/null 2>&1

EXPORTS='["_add_two","_get_static_message","_alloc_message","_str_len","_poke","_alloc_int_array","_malloc","_free"]'
RT='["UTF8ToString","wasmMemory","HEAP32","HEAPU8"]'
( cd native
  # -sALLOW_MEMORY_GROWTH matches how CPython links itself (configure.ac appends
  # "-sALLOW_MEMORY_GROWTH -sINITIAL_MEMORY=20971520" to LINKFORSHARED), so the shared memory
  # under test is one that can grow after Kotlin is already holding it.
  emcc probe.c -O2 -o probeA.mjs -sMODULARIZE -sEXPORT_ES6 -sALLOW_MEMORY_GROWTH \
       -sEXPORTED_FUNCTIONS="$EXPORTS" -sEXPORTED_RUNTIME_METHODS="$RT"
  if [ -n "$RUN_LEGACY_B" ]; then
    emcc probe.c -O2 -o probeB.mjs -sMODULARIZE -sEXPORT_ES6 -sIMPORTED_MEMORY -sALLOW_MEMORY_GROWTH \
         -sEXPORTED_FUNCTIONS="$EXPORTS" -sEXPORTED_RUNTIME_METHODS="$RT"
  fi )

: "${PMP_PYTHON_DIR:=/Volumes/macMini/wasm-build/cpython314/cross-build/wasm32-emscripten/build/python}"
HAVE_CPYTHON=0
[ -f "$PMP_PYTHON_DIR/python.mjs" ] && HAVE_CPYTHON=1

./gradlew compileDevelopmentExecutableKotlinWasmJs --console=plain
K=build/compileSync/wasmJs/main/developmentExecutable/kotlin
cp native/probeA.mjs native/probeA.wasm native/probeA-wrapper.mjs \
   native/direct-call-test.mjs native/combined-test.mjs "$K/"

# The Kotlin module @WasmImports from BOTH wrappers, so both specifiers must resolve even in the
# processes that only exercise one of them. Tests A and C get a stub: the imports are declared but
# never called, and an unused import is only type-checked, not entered.
cat > "$K/cpython-wrapper.mjs" <<'STUB'
// Stub for the Test A / Test C processes, which never call into CPython. Kotlin's import object
// must still have something to bind these names to. The real wrapper is native/cpython-wrapper.mjs.
const nope = () => { throw new Error('CPython imports are not wired up in this process'); };
export const wasmMemory = null, mod = null, exports_ = null;
export const PyRun_SimpleString = nope, PyImport_AddModule = nope, PyModule_GetDict = nope;
export const PyDict_GetItemString = nope, PyLong_AsLong = nope, PyUnicode_AsUTF8 = nope;
export const PyErr_Occurred = nope, pmalloc = nope, pfree = nope;
export default nope;
STUB

echo; echo "##### Test A: @WasmImport against an Emscripten export (Kotlin's own placeholder memory)"
"$NODE" "$K/direct-call-test.mjs"

echo; echo "##### Test C: direct calls AND shared memory in one graph"
# The entire integration: point Kotlin's intrinsics.memory import at Emscripten's memory.
# No binary patching -- Kotlin's memory import declares no maximum, so any memory satisfies it.
cp "$K/wasm-experiment.import-object.mjs" /tmp/import-object.pristine.mjs
python3 native/patch-import-object.py "$K/wasm-experiment.import-object.mjs"
"$NODE" "$K/combined-test.mjs"

echo; echo "##### Test D: the same two mechanisms against real CPython 3.14.2"
# Needs a CPython Emscripten build whose glue exposes wasmExports and wasmMemory. See
# /Volumes/macMini/wasm-build/build-cpython-emscripten.sh, then relink with
#   -sEXPORTED_RUNTIME_METHODS=FS,callMain,ENV,HEAPU32,TTY,wasmExports,wasmMemory
if [ "$HAVE_CPYTHON" = 1 ]; then
  # A second copy of the Kotlin output, patched to take CPython's memory instead of the toy's.
  # One module cannot take both, so the two tests are two processes over two patched copies.
  D=build/testD
  rm -rf "$D"; mkdir -p "$D"
  cp "$K"/*.mjs "$K"/*.js "$K"/*.wasm "$D/" 2>/dev/null || true
  cp /tmp/import-object.pristine.mjs "$D/wasm-experiment.import-object.mjs"
  cp native/cpython-wrapper.mjs native/cpython-test.mjs "$D/"
  cp "$PMP_PYTHON_DIR/python.mjs" "$PMP_PYTHON_DIR/python.wasm" "$D/"
  python3 native/patch-import-object.py "$D/wasm-experiment.import-object.mjs" ./cpython-wrapper.mjs
  PMP_PYTHON_DIR="$PMP_PYTHON_DIR" "$NODE" "$D/cpython-test.mjs"
else
  echo "SKIPPED -- no CPython build at $PMP_PYTHON_DIR"
fi

if [ -n "$RUN_LEGACY_B" ]; then
  echo; echo "##### Test B (historical): Emscripten importing Kotlin's exported memory"
  echo "      Premise: Kotlin defines and exports a memory. True on <= 2.4.10, false on 2.4.20-Beta2."
  cp "$K/wasm-experiment.wasm" /tmp/kotlin-orig.wasm
  python3 native/patch-memory-max.py /tmp/kotlin-orig.wasm "$K/wasm-experiment.wasm"
  "$NODE" native/shared-memory-test.mjs
fi
