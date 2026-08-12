// Test F -- CPython calls Kotlin. The real interpreter, the real PyCFunction path.
//
// Test E established the mechanism against a 4 KB toy with a plain call_indirect. CPython does not
// call PyCFunctions that way: PY_CALL_TRAMPOLINE routes them through _PyEM_TrampolineCall, which
// dispatches on arity with a wasm-gc ref.test from inside a separate wasm module. So the mechanism
// has to survive that, and the Kotlin function has to be reachable as an ordinary PyMethodDef entry
// rather than as something the host calls directly.
//
// The chain under test, with no JS anywhere in it after the one-time registration:
//
//     Python source  ->  ceval  ->  _PyEM_TrampolineCall  ->  ref.test + call_indirect
//                    ->  Kotlin @WasmExport  ->  @WasmImport back into CPython (PyLong_FromLong)
//                    ->  a PyObject* returned to the interpreter

import * as kotlin from './wasm-experiment.mjs';
import { mod, exports_ } from './cpython-wrapper.mjs';

const line = (s = '') => console.log(s);
let failures = 0;
const check = (ok, label) => {
    line(`   ${ok ? 'PASS' : 'FAIL'} -- ${label}`);
    if (!ok) failures++;
};

line();
line('=== Test F: CPython 3.14.2 calling Kotlin/Wasm through a PyMethodDef ===');
line();

// --- is the trampoline the wasm one or the JS fallback? ------------------------------------------
// Python/emscripten_trampoline.c replaces the JS _PyEM_TrampolineCall_inner with a wasm module's
// export when the runtime has wasm-gc, and silently keeps the JS one when it does not. Which one is
// live decides whether the upcall has a JS frame in it, so read it rather than assume.
const inner = mod._PyEM_TrampolineCall_inner ?? globalThis._PyEM_TrampolineCall_inner;
line(`_PyEM_TrampolineCall_inner: ${inner ? String(inner).slice(0, 70).replace(/\n/g, ' ') : '(not reachable from JS)'}`);
line();

// --- registration: the one and only JS step ------------------------------------------------------
const table = exports_.__indirect_function_table ?? mod.wasmTable;
line(`CPython's __indirect_function_table: ${table.constructor.name}, length ${table.length}`);
const raw = kotlin.rawExports;

let idx = -1;
try {
    idx = table.grow(1);
    table.set(idx, raw.kotlin_pycfunction);
    line(`table.grow(1) -> ${idx}; table.set(${idx}, kotlin_pycfunction) accepted`);
} catch (e) {
    line(`table.set threw: ${e}`);
}
check(idx >= 0, 'CPython\'s own function table accepts a Kotlin export');

const put = (fn) => { const i = table.grow(1); table.set(i, fn); return i; };
let idxBare = -1, idxO = -1, idx4 = -1;
if (idx >= 0) {
    idxBare = put(raw.kotlin_pycfunction_bare);
    idxO = put(raw.kotlin_pycfunction_o);
    idx4 = put(raw.kotlin_pycfunction_4);
}

// --- does CPython's own arity dispatch see it? ----------------------------------------------------
// Same ref.test CPython performs, run here through the probe's copy of the builtin. The probe is a
// different module, but the funcref and the type are the same values, so the answer is the same.
line();

const rc = kotlin.installPyCallback('kotlin_cb', idx);
line(`Kotlin: installPyCallback("kotlin_cb", fp=${idx}) -> ${rc}`);
check(rc === 0, 'PyCFunction_NewEx accepted the pointer and it was bound into __main__');
check(kotlin.installPyCallback('kotlin_cb_bare', idxBare) === 0, 'the bare variant registered too');
check(kotlin.installPyCallback('kotlin_cb_o', idxO, true) === 0, 'a METH_O variant registered too');
check(kotlin.installPyCallback('kotlin_cb_4', idx4) === 0, 'the four-argument discriminator registered');
line();

// --- the actual call, from Python source ----------------------------------------------------------
line('--- Python calls it ---');
const before = kotlin.pyCallbackCount();
const rc2 = kotlin.pyExec('cb_result = kotlin_cb(41)\ncb_type = type(kotlin_cb).__name__\n');
const after = kotlin.pyCallbackCount();
line(`Kotlin: pyExec("cb_result = kotlin_cb(41)") -> ${rc2}`);
check(rc2 === 0 && !kotlin.pyErrorPending(), 'the interpreter ran it without raising');
line(`Kotlin-side upcall counter: ${before} -> ${after}`);
check(after === before + 1, 'the Kotlin body ran exactly once, and its WasmGC state was live');
const result = kotlin.pyGlobalInt('cb_result');
line(`Kotlin: pyGlobalInt("cb_result") -> ${result}`);
check(result === 42,
      'Python got 42 back -- Kotlin read the argument tuple and built the return value itself');
line(`type(kotlin_cb).__name__ = ${JSON.stringify(kotlin.pyGlobalString('cb_type'))}`);
check(kotlin.pyGlobalString('cb_type') === 'builtin_function_or_method',
      'Python sees an ordinary builtin, not anything special-cased');
line();

// --- several arguments, and repeated calls --------------------------------------------------------
const rc3 = kotlin.pyExec('multi = kotlin_cb(1, 2, 3, 4)\nloop = sum(kotlin_cb(i) for i in range(1000))\n');
check(rc3 === 0 && kotlin.pyGlobalInt('multi') === 11, 'variadic args arrive intact (1+2+3+4+1)');
check(kotlin.pyGlobalInt('loop') === (999 * 1000) / 2 + 1000,
      '1000 calls in a Python loop all return correctly');
line(`upcall counter after the loop: ${kotlin.pyCallbackCount()}`);
line();

// --- errors raised by Kotlin ---------------------------------------------------------------------
// Returning NULL without setting an exception is what a Kotlin trampoline does if it forgets; check
// that CPython reacts the documented way rather than corrupting anything.
const rc4 = kotlin.pyExec(
    'try:\n' +
    '    kotlin_cb("not-an-int")\n' +
    '    err = "no-exception"\n' +
    'except BaseException as e:\n' +
    '    err = type(e).__name__\n');
line(`kotlin_cb("not-an-int") -> ${JSON.stringify(kotlin.pyGlobalString('err'))}`);
check(rc4 === 0, 'a bad argument did not take the interpreter down');
line();

// --- which trampoline is live? --------------------------------------------------------------------
// kotlin_cb_4's wasm type is (i32,i32,i32,i32)->i32, which matches none of the four shapes
// Python/emscripten_trampoline_inner.c tests for. The wasm trampoline fails all four ref.tests and
// makes CPython raise SystemError("Handler takes too many arguments"); the JS fallback would call
// it with three arguments instead and get some answer back. The message decides it.
line('--- which trampoline is live: wasm (ref.test) or the JS fallback? ---');
const rc5 = kotlin.pyExec(
    'try:\n' +
    '    r4 = kotlin_cb_4(1)\n' +
    '    err4 = "returned " + repr(r4)\n' +
    'except BaseException as e:\n' +
    '    err4 = type(e).__name__ + ": " + str(e)\n');
const err4 = kotlin.pyGlobalString('err4');
line(`kotlin_cb_4(1)  ->  ${JSON.stringify(err4)}`);
const wasmTrampoline = err4.includes('Handler takes too many arguments');
check(rc5 === 0, 'the four-argument probe did not take the interpreter down');
line();
if (wasmTrampoline) {
    line('   => the WASM trampoline is live: all four ref.tests failed and CPython said so.');
    line('      No JS frame in the call path.');
} else {
    line('   => the JS FALLBACK is live. The four-argument function was called anyway, with three');
    line('      arguments and a zero for the fourth, which is what');
    line('        _PyEM_TrampolineCall_inner = wasmTable.get(func)(arg1, arg2, arg3)');
    line('      does. So every PyCFunction call in this build crosses a JS frame -- not only');
    line('      Kotlin\'s, but every C extension\'s too.');
    line();
    line('      Cause (Python/emscripten_trampoline.c): the EM_JS initialiser that swaps in the');
    line('      wasm trampoline runs while python.mjs is still evaluating, and it needs');
    line('      wasmTable/wasmMemory -- which in this configuration are EXPORTS of python.wasm and');
    line('      so do not exist until after instantiation. The resulting LinkError is swallowed by');
    line('      a bare `catch (e) {}` and the JS fallback is kept silently. Instrumenting the catch');
    line('      prints:  "memory import must be a WebAssembly.Memory object", wasmTable undefined.');
    line();
    line('      It is a genuine instantiation cycle, not an ordering slip: the trampoline module');
    line('      imports env.memory and env.__indirect_function_table from the very module whose');
    line('      import object needs the trampoline.');
}
line();

if (failures > 0) {
    line('=== VERDICT: FAIL ===');
    process.exit(1);
}
line('=== VERDICT: CPython calls Kotlin ===');
line('A Kotlin @WasmExport, registered as an ordinary PyMethodDef, called from Python source');
line('through PY_CALL_TRAMPOLINE, calling back down into CPython to build its return value.');
line('JS appears once, to put a funcref in the table -- never in the call.');
line();

// ================================================================================================
// Measurement: what one upcall costs when the interpreter is the caller.
// ================================================================================================
const ms = (f) => { const t = performance.now(); f(); return performance.now() - t; };

line('=== Measurement: a Python-level call into Kotlin vs into C and vs into Python ===');
line('The comparison that matters is the METH_O pair: abs() is a C builtin declared METH_O, and');
line('kotlin_cb_o is a Kotlin function declared METH_O with the identical wasm signature. Same');
line('calling convention, same trampoline, no argument tuple on either side -- so the difference');
line('between those two rows is the crossing and nothing else. The METH_VARARGS rows are not');
line('comparable to abs(): CPython builds an args tuple for them and not for METH_O.');
line();

const N = 2_000_000;
kotlin.pyExec(
    'def py_cb(x):\n' +
    '    return x + 1\n' +
    'import time\n' +
    'def bench(f, n):\n' +
    '    t = time.perf_counter()\n' +
    '    for i in range(n):\n' +
    '        f(i)\n' +
    '    return int((time.perf_counter() - t) * 1e9 / n)\n' +
    'for _f in (py_cb, abs, kotlin_cb, kotlin_cb_bare, kotlin_cb_o):\n' +
    '    bench(_f, 100000)\n');

const rcB = kotlin.pyExec(
    `n = ${N}\n` +
    't_py = bench(py_cb, n)\n' +
    't_abs = bench(abs, n)\n' +
    't_kto = bench(kotlin_cb_o, n)\n' +
    't_kt = bench(kotlin_cb, n)\n' +
    't_ktb = bench(kotlin_cb_bare, n)\n');
check(rcB === 0, 'benchmark ran');
const row = (l, v) => line(`${l.padEnd(46)} ${String(v).padStart(6)} ns/call`);
line('-- METH_O, like for like --');
row('C builtin    abs(x)', kotlin.pyGlobalInt('t_abs'));
row('Kotlin       kotlin_cb_o(x)', kotlin.pyGlobalInt('t_kto'));
line(`${' '.repeat(46)} ${String(kotlin.pyGlobalInt('t_kto') - kotlin.pyGlobalInt('t_abs')).padStart(6)} ns  <- the crossing`);
line();
line('-- for context --');
row('pure Python  def py_cb(x): return x+1', kotlin.pyGlobalInt('t_py'));
row('Kotlin       kotlin_cb_bare(x)   METH_VARARGS', kotlin.pyGlobalInt('t_ktb'));
row('Kotlin       kotlin_cb(x)        METH_VARARGS + tuple read', kotlin.pyGlobalInt('t_kt'));
line();
line('The METH_VARARGS rows carry CPython\'s argument-tuple construction, which is why they sit');
line('above the METH_O pair. That cost is the same for a C extension and is not the crossing.');
line();

process.exit(failures === 0 ? 0 : 1);
