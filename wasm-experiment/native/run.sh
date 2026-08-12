#!/bin/bash
# Reproduces the experiments. Needs emsdk (~/emsdk) and the Node that Gradle downloaded.
#
#   Test A  @WasmImport binds to an Emscripten export.                            (still current)
#   Test C  that AND a shared linear memory, in one instantiation graph.          (the live result)
#   Test D  both mechanisms against real CPython 3.14.2.
#   Test E  upcalls: Emscripten-side C re-entering Kotlin through a function pointer.
#   Test F  upcalls against the real interpreter: a Kotlin @WasmExport as a PyCFunction.
#   Test G  a real compiled (Rust/PyO3) pyemscripten_2026_0 wheel, against the ABI build and,
#           as a negative control, the stock build.                              (no Kotlin involved)
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

EXPORTS='["_add_two","_get_static_message","_alloc_message","_str_len","_poke","_alloc_int_array","_malloc","_free","_call_fp","_call_fp_n","_c_add","_c_add_fp","_fp_is_two_arg","_fp_is_one_arg","_fp_is_zero_arg"]'
RT='["UTF8ToString","wasmMemory","HEAP32","HEAPU8","wasmTable","addFunction","removeFunction"]'
( cd native
  # -sALLOW_MEMORY_GROWTH matches how CPython links itself (configure.ac appends
  # "-sALLOW_MEMORY_GROWTH -sINITIAL_MEMORY=20971520" to LINKFORSHARED), so the shared memory
  # under test is one that can grow after Kotlin is already holding it.
  #
  # -sALLOW_TABLE_GROWTH is what addFunction needs, and what Test E needs to put a Kotlin export
  # into the table. -mgc is for __builtin_wasm_test_function_pointer_signature, the wasm-gc ref.test
  # CPython's own PY_CALL_TRAMPOLINE dispatches on (Python/emscripten_trampoline_inner.c).
  emcc probe.c -O2 -mgc -o probeA.mjs -sMODULARIZE -sEXPORT_ES6 -sALLOW_MEMORY_GROWTH \
       -sALLOW_TABLE_GROWTH \
       -sEXPORTED_FUNCTIONS="$EXPORTS" -sEXPORTED_RUNTIME_METHODS="$RT"
  if [ -n "$RUN_LEGACY_B" ]; then
    emcc probe.c -O2 -mgc -o probeB.mjs -sMODULARIZE -sEXPORT_ES6 -sIMPORTED_MEMORY -sALLOW_MEMORY_GROWTH \
         -sALLOW_TABLE_GROWTH \
         -sEXPORTED_FUNCTIONS="$EXPORTS" -sEXPORTED_RUNTIME_METHODS="$RT"
  fi )

# Defaults to the ABI-matched build (build-cpython-abi.sh), which is the one that can load a
# pyemscripten_2026_0 wheel. The stock build is still at .../cpython314/... if you want to compare;
# Tests D and F pass against either.
: "${PMP_PYTHON_DIR:=/Volumes/macMini/wasm-build/cpython314-abi/cross-build/wasm32-emscripten/build/python}"
HAVE_CPYTHON=0
[ -f "$PMP_PYTHON_DIR/python.mjs" ] && HAVE_CPYTHON=1

./gradlew compileDevelopmentExecutableKotlinWasmJs --console=plain
K=build/compileSync/wasmJs/main/developmentExecutable/kotlin
cp native/probeA.mjs native/probeA.wasm native/probeA-wrapper.mjs \
   native/direct-call-test.mjs native/combined-test.mjs native/upcall-test.mjs "$K/"

# Test E reaches Kotlin's @WasmExport functions, which the generated entry module does not bind.
python3 native/patch-export-raw.py "$K/wasm-experiment.mjs"

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
export const PyCFunction_NewEx = nope, PyLong_FromLong = nope, PyTuple_Size = nope;
export const PyTuple_GetItem = nope, PyDict_SetItemString = nope;
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
  cp native/cpython-wrapper.mjs native/cpython-test.mjs native/cpython-upcall-test.mjs "$D/"
  cp "$PMP_PYTHON_DIR/python.mjs" "$PMP_PYTHON_DIR/python.wasm" "$D/"
  python3 native/patch-import-object.py "$D/wasm-experiment.import-object.mjs" ./cpython-wrapper.mjs
  python3 native/patch-export-raw.py "$D/wasm-experiment.mjs"
  PMP_PYTHON_DIR="$PMP_PYTHON_DIR" "$NODE" "$D/cpython-test.mjs"

  echo; echo "##### Test F: the upcall against the real interpreter"
  PMP_PYTHON_DIR="$PMP_PYTHON_DIR" "$NODE" "$D/cpython-upcall-test.mjs"
else
  echo "SKIPPED -- no CPython build at $PMP_PYTHON_DIR"
fi

echo; echo "##### Test E: upcalls -- Emscripten-side C re-entering Kotlin/Wasm"
# Same instantiation graph as Test C (Kotlin's import object already points at Emscripten's memory
# from the patch above), so the upcall runs with the memory shared, as it would in production.
"$NODE" "$K/upcall-test.mjs"

echo; echo "##### Test G: a real compiled wheel (pydantic-core, Rust/PyO3) -- does pyemscripten_2026_0 load?"
# docs/wasm-design.md argues the unwinding ABI (-fwasm-exceptions -sSUPPORT_LONGJMP=wasm, present in
# build-cpython-abi.sh, absent from the stock build) is what a compiled pyemscripten_2026_0 wheel
# needs at *load* time, not just at build time. This is the test that settles it: no Kotlin involved,
# just each CPython Emscripten build's own python.sh importing and calling into the same .so.
WHEEL_DIR=/Volumes/macMini/wasm-build/wheels
WHEEL_NAME=pydantic_core-2.48.0-cp314-cp314-pyemscripten_2026_0_wasm32.whl
WHEEL_PATH="$WHEEL_DIR/$WHEEL_NAME"
mkdir -p "$WHEEL_DIR"
if [ ! -f "$WHEEL_PATH" ]; then
  echo "fetching $WHEEL_NAME from PyPI..."
  WHEEL_URL=$(python3 -c "
import json, urllib.request
d = json.load(urllib.request.urlopen('https://pypi.org/pypi/pydantic-core/json'))
for f in d['releases']['2.48.0']:
    if 'pyemscripten' in f['filename']:
        print(f['url']); break
")
  curl -sL "$WHEEL_URL" -o "$WHEEL_PATH"
fi
WHEEL_X="$WHEEL_DIR/x"
if [ ! -d "$WHEEL_X/pydantic_core" ]; then
  mkdir -p "$WHEEL_X"
  python3 -m zipfile -e "$WHEEL_PATH" "$WHEEL_X"
fi

WHEEL_TEST_PY='
import sys
sys.path.insert(0, "'"$WHEEL_X"'")
import pydantic_core
print("pydantic_core imported OK, version:", pydantic_core.__version__)
from pydantic_core import SchemaValidator, core_schema
v = SchemaValidator(core_schema.int_schema())
print("validate_python(\"42\") ->", v.validate_python("42"))
try:
    v.validate_python("not an int")
    print("FAIL -- no exception raised")
except Exception as e:
    print("raised:", type(e).__name__)
'

echo "--- ABI build ($PMP_PYTHON_DIR) -- expected to import and run ---"
if [ "$HAVE_CPYTHON" = 1 ]; then
  ( cd "$PMP_PYTHON_DIR" && chmod +x python.sh && ./python.sh -c "$WHEEL_TEST_PY" )
else
  echo "SKIPPED -- no ABI CPython build at $PMP_PYTHON_DIR"
fi

STOCK_PYTHON_DIR=/Volumes/macMini/wasm-build/cpython314/cross-build/wasm32-emscripten/build/python
echo; echo "--- stock build (negative control) -- expected to fail with a LinkError on the tag import ---"
if [ -f "$STOCK_PYTHON_DIR/python.mjs" ]; then
  ( cd "$STOCK_PYTHON_DIR" && chmod +x python.sh && ./python.sh -c "$WHEEL_TEST_PY" ) \
    && echo "UNEXPECTED -- stock build loaded the compiled wheel too" \
    || echo "expected failure reproduced above -- confirms the ABI flags are what gate this"
else
  echo "SKIPPED -- no stock CPython build at $STOCK_PYTHON_DIR"
fi

if [ -n "$RUN_LEGACY_B" ]; then
  echo; echo "##### Test B (historical): Emscripten importing Kotlin's exported memory"
  echo "      Premise: Kotlin defines and exports a memory. True on <= 2.4.10, false on 2.4.20-Beta2."
  cp "$K/wasm-experiment.wasm" /tmp/kotlin-orig.wasm
  python3 native/patch-memory-max.py /tmp/kotlin-orig.wasm "$K/wasm-experiment.wasm"
  "$NODE" native/shared-memory-test.mjs
fi
