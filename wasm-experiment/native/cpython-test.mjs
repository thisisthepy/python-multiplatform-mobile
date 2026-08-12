// Test D -- Kotlin/Wasm against the real CPython 3.14.2 Emscripten build.
//
// Same two mechanisms as Test C, but the module on the other side is an interpreter:
//
//   control : @WasmImport bound to python.wasm's own exports (PyRun_SimpleString, ...)
//   data    : Kotlin's intrinsics.memory IS python.wasm's memory, so a char* from
//             PyUnicode_AsUTF8 is dereferenced in Kotlin with no copy and no JS
//
// Instantiation order is the same and comes from ES modules: cpython-wrapper.mjs has a top-level
// await, so CPython is up (and Py_InitializeEx has run) before Kotlin's import object is built.

import * as kotlin from './wasm-experiment.mjs';
import { wasmMemory, mod, exports_ } from './cpython-wrapper.mjs';

const line = (s = '') => console.log(s);
let failures = 0;
const check = (ok, label) => {
    line(`   ${ok ? 'PASS' : 'FAIL'} -- ${label}`);
    if (!ok) failures++;
};

line();
line('=== Test D: Kotlin/Wasm -> CPython 3.14.2 (wasm32-emscripten) ===');
line();
line(`python.wasm exports        ${Object.keys(exports_).length}`);
line(`CPython's memory           ${wasmMemory.buffer.byteLength / 65536} pages`);
line(`Kotlin's intrinsics.memory ${kotlin.memory.buffer.byteLength / 65536} pages`);
check(kotlin.memory.buffer === wasmMemory.buffer,
      'Kotlin instantiated against the memory CPython defined');
line();

// --- Kotlin writes Python source into CPython's heap and runs it --------------------------------
line('--- Kotlin -> CPython ---');
const rc = kotlin.pyExec('answer = 6 * 7\ngreeting = "hello-from-cpython-" + str(answer)\n');
line(`Kotlin: pyExec("answer = 6 * 7; greeting = ...") -> ${rc}`);
check(rc === 0, 'PyRun_SimpleString ran source Kotlin wrote straight into CPython memory');
check(!kotlin.pyErrorPending(), 'no Python error pending afterwards');
line();

// --- Kotlin reads the results back through the shared memory ------------------------------------
line('--- CPython -> Kotlin ---');
const answer = kotlin.pyGlobalInt('answer');
line(`Kotlin: pyGlobalInt("answer") -> ${answer}`);
check(answer === 42, 'int read back through PyDict_GetItemString + PyLong_AsLong');

const greeting = kotlin.pyGlobalString('greeting');
line(`Kotlin: pyGlobalString("greeting") -> ${JSON.stringify(greeting)}`);
check(greeting === 'hello-from-cpython-42',
      "Kotlin dereferenced PyUnicode_AsUTF8's char* directly -- no copy, no JS");
line();

// --- something that makes CPython grow its heap under Kotlin ------------------------------------
line('--- the interpreter grows its own memory ---');
const pagesBefore = wasmMemory.buffer.byteLength / 65536;
const rc2 = kotlin.pyExec('blob = bytearray(48 * 1024 * 1024)\nblob_len = len(blob)\n');
const pagesAfter = wasmMemory.buffer.byteLength / 65536;
line(`Kotlin: pyExec("bytearray(48 MiB)") -> ${rc2},  ${pagesBefore} -> ${pagesAfter} pages`);
check(rc2 === 0 && pagesAfter > pagesBefore, 'CPython allocated enough to grow the shared memory');
check(kotlin.pyGlobalInt('blob_len') === 48 * 1024 * 1024,
      'Kotlin still reads CPython correctly after the growth');
check(kotlin.pyGlobalString('greeting') === 'hello-from-cpython-42',
      'a pre-growth string still reads correctly');
line();

if (failures > 0) {
    line('=== VERDICT: FAIL ===');
    process.exit(1);
}
line('=== VERDICT: end to end ===');
line('Kotlin/Wasm called CPython 3.14.2 through direct wasm-to-wasm imports, wrote its source');
line('into CPython\'s heap and read its strings out of it, sharing one linear memory.');
line();

// ================================================================================================
const ms = (f) => { const t = performance.now(); f(); return performance.now() - t; };
const fmt = (t, n) => `${t.toFixed(2).padStart(9)} ms  (${(t * 1e6 / n).toFixed(1).padStart(8)} ns/op)`;

line('=== Measurement: crossing cost against a real interpreter ===');
const N1 = 5_000_000;
kotlin.measurePyErrOccurred(100_000);
const tErr = ms(() => kotlin.measurePyErrOccurred(N1));
line(`PyErr_Occurred, direct   ${fmt(tErr, N1)}   one crossing + a trivial C body`);

const N2 = 200_000;
kotlin.measurePyGlobalInt('answer', 10_000);
kotlin.measurePyGlobalIntInterned('answer', 10_000);
kotlin.measurePyGlobalIntHoisted('answer', 10_000);
const tGlobal = ms(() => kotlin.measurePyGlobalInt('answer', N2));
const tInterned = ms(() => kotlin.measurePyGlobalIntInterned('answer', N2));
const tHoisted = ms(() => kotlin.measurePyGlobalIntHoisted('answer', N2));
line(`read a global, naive     ${fmt(tGlobal, N2)}   2 malloc + 2 free + 2 string writes + 4 calls`);
line(`  + interned C strings   ${fmt(tInterned, N2)}   4 calls, names allocated once`);
line(`  + module/dict hoisted  ${fmt(tHoisted, N2)}   2 calls -- the floor`);
line();
line(`interning removes  ${(tGlobal - tInterned).toFixed(1).padStart(6)} ms of ${tGlobal.toFixed(1)}  ` +
     `(${(100 * (tGlobal - tInterned) / tGlobal).toFixed(0)}%)   -- pure Kotlin, no C shipped`);
line(`hoisting removes   ${(tInterned - tHoisted).toFixed(1).padStart(6)} ms more            ` +
     `(${(100 * (tInterned - tHoisted) / tGlobal).toFixed(0)}%)   -- also pure Kotlin`);
line(`left for a shim    ${tHoisted.toFixed(1).padStart(6)} ms                    ` +
     `(${(100 * tHoisted / tGlobal).toFixed(0)}%)   -- and most of that is CPython's own work`);
line();
line('A composed pmp_* call can only remove crossings, and a crossing costs the PyErr_Occurred');
line('figure above. Everything interning and hoisting take is available without shipping any C.');

process.exit(0);
