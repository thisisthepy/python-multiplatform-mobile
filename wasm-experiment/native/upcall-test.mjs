// Test E -- upcalls: Emscripten-side C re-entering Kotlin/Wasm through a function pointer.
//
// docs/wasm-design.md §5 and §7 step 3 specify one mechanism: @JsExport the Kotlin trampoline, wrap
// it in a JS closure, hand the closure to Emscripten's addFunction, and give CPython the table
// index it returns. §"Unresolved" records that the path "goes through JS, unmeasured, and likely
// worse than every other platform" -- and that the whole of §7 is blocked on knowing.
//
// This measures that mechanism and one the design does not consider: putting the Kotlin export
// itself into Emscripten's table with WebAssembly.Table.set. A funcref is a funcref regardless of
// which instance produced it, so if call_indirect accepts the type there is no JS in the path at
// all.
//
// The last block runs CPython's own arity-dispatch test (the wasm-gc ref.test in
// Python/emscripten_trampoline_inner.c) against each index, because PY_CALL_TRAMPOLINE means a
// PyCFunction that fails that test is uncallable no matter what else works.

import * as kotlin from './wasm-experiment.mjs';
import { mod } from './probeA-wrapper.mjs';

const line = (s = '') => console.log(s);
let failures = 0;
const check = (ok, label) => {
    line(`   ${ok ? 'PASS' : 'FAIL'} -- ${label}`);
    if (!ok) failures++;
};

line();
line('=== Test E: upcalls -- CPython-side C calling back into Kotlin/Wasm ===');
line();

// --- what Kotlin actually exports --------------------------------------------------------------
const raw = kotlin.rawExports;
const names = Object.keys(raw).filter(n => n.startsWith('kotlin_upcall'));
line(`Kotlin module exports matching kotlin_upcall*: ${JSON.stringify(names)}`);
check(names.length === 4, '@WasmExport put the four trampolines in the wasm export section');
const kAdd = raw.kotlin_upcall_add;
line(`typeof raw.kotlin_upcall_add = ${typeof kAdd}`);
line(`String(raw.kotlin_upcall_add) = ${String(kAdd).slice(0, 60).replace(/\n/g, ' ')}`);
if (typeof WebAssembly.Function !== 'undefined' && kAdd instanceof WebAssembly.Function) {
    line(`WebAssembly.Function.type() = ${JSON.stringify(WebAssembly.Function.type(kAdd))}`);
} else {
    line('(not a WebAssembly.Function instance -- reflection unavailable in this runtime)');
}
line();

const table = mod.wasmTable;
line(`Emscripten's __indirect_function_table: ${table.constructor.name}, length ${table.length}`);

// --- mechanism 1: the Kotlin export goes straight into Emscripten's table ------------------------
line();
line('--- mechanism 1: WebAssembly.Table.set(idx, <Kotlin wasm export>) ---');
let idxDirect = -1;
try {
    idxDirect = table.grow(1);
    table.set(idxDirect, kAdd);
    line(`table.grow(1) -> ${idxDirect}; table.set(${idxDirect}, kotlin_upcall_add) accepted`);
} catch (e) {
    line(`table.set threw: ${e}`);
}
check(idxDirect >= 0, 'a cross-instance funcref is accepted by Emscripten\'s table');

let gotDirect = null;
if (idxDirect >= 0) {
    try {
        gotDirect = mod._call_fp(idxDirect, 40, 2);
        line(`C: call_fp(${idxDirect}, 40, 2) -> ${gotDirect}`);
    } catch (e) {
        line(`C: call_fp threw: ${e}`);
    }
}
check(gotDirect === 42, 'C reached Kotlin through call_indirect');

// --- mechanism 2: the shape the design specified -------------------------------------------------
line();
line('--- mechanism 2: addFunction(<JS closure calling @JsExport>) ---');
const jsClosure = (a, b) => kotlin.kotlinUpcallAddViaJs(a, b);
const idxJs = mod.addFunction(jsClosure, 'iii');
line(`addFunction(closure, 'iii') -> ${idxJs}`);
const gotJs = mod._call_fp(idxJs, 40, 2);
line(`C: call_fp(${idxJs}, 40, 2) -> ${gotJs}`);
check(gotJs === 42, 'the design\'s addFunction path also reaches Kotlin');

// --- mechanism 3: addFunction handed the wasm export rather than a closure -----------------------
line();
line('--- mechanism 3: addFunction(<Kotlin wasm export>) ---');
let idxAddFnWasm = -1, gotAddFnWasm = null;
try {
    idxAddFnWasm = mod.addFunction(kAdd, 'iii');
    gotAddFnWasm = mod._call_fp(idxAddFnWasm, 40, 2);
    line(`addFunction(kotlin_upcall_add, 'iii') -> ${idxAddFnWasm}, call_fp -> ${gotAddFnWasm}`);
} catch (e) {
    line(`threw: ${e}`);
}
check(gotAddFnWasm === 42, 'addFunction accepts an exported wasm function directly');

// --- control: a C function through the same indirect path ---------------------------------------
line();
const idxC = mod._c_add_fp();
const gotC = mod._call_fp(idxC, 40, 2);
line(`--- control: C's own c_add at table index ${idxC}, call_fp -> ${gotC} ---`);
check(gotC === 42, 'control path works');

// --- does the WasmGC heap survive a frame C pushed? ----------------------------------------------
line();
line('--- reentrancy: WasmGC allocation and Kotlin state, from inside the upcall ---');
if (idxDirect >= 0) {
    const idxAlloc = table.grow(1);
    table.set(idxAlloc, raw.kotlin_upcall_alloc);
    // kotlin_upcall_alloc is (i32) -> i32; call_fp passes two args, so use the one-arg path via a
    // second entry typed for it. call_fp's static type is (i32,i32)->i32, so this must go through
    // its own C helper -- reuse call_fp with a two-arg export instead and allocate inside route.
    const idxRoute = table.grow(1);
    table.set(idxRoute, raw.kotlin_upcall_route);
    kotlin.registerUpcallValue(7, 'routed-through-a-cpython-frame');
    const scratch = mod._malloc(128);
    const n = mod._call_fp(idxRoute, 7, scratch);
    const readBack = mod.UTF8ToString(scratch);
    line(`Kotlin registry[7] = "routed-through-a-cpython-frame"`);
    line(`C: call_fp(route, ctx=7, out=0x${scratch.toString(16)}) -> ${n}`);
    line(`C: UTF8ToString(out) -> ${JSON.stringify(readBack)}`);
    check(n === 30 && readBack === 'routed-through-a-cpython-frame',
          'the upcall read Kotlin heap state by context integer and wrote to CPython\'s memory');

    const miss = mod._call_fp(idxRoute, 999, scratch);
    check(miss === -1, 'an unrouted context returns cleanly rather than trapping');

    // GC allocation inside the upcall, called through the one-arg table entry via ref.test below.
    line(`(kotlin_upcall_alloc parked at index ${idxAlloc} for the arity-dispatch check)`);
    globalThis.__idxAlloc = idxAlloc;
}
line();

// --- CPython's own arity dispatch ----------------------------------------------------------------
// Python/emscripten_trampoline_inner.c decides how to call a PyCFunction with
// __builtin_wasm_test_function_pointer_signature -- a wasm-gc ref.test on the table entry. If a
// Kotlin export does not answer it, CPython cannot call it whatever else works.
line('--- CPython\'s PY_CALL_TRAMPOLINE arity dispatch (wasm-gc ref.test), run on each index ---');
const probeIdx = [
    ['C c_add            (i32,i32)->i32', idxC],
    ['Kotlin, table.set  (i32,i32)->i32', idxDirect],
    ['Kotlin, addFunction closure       ', idxJs],
    ['Kotlin, addFunction wasm export   ', idxAddFnWasm],
    ['Kotlin kotlin_upcall_alloc (i32)->i32', globalThis.__idxAlloc ?? -1],
];
for (const [label, i] of probeIdx) {
    if (i < 0) { line(`${label}  index unavailable`); continue; }
    const two = mod._fp_is_two_arg(i), one = mod._fp_is_one_arg(i), zero = mod._fp_is_zero_arg(i);
    line(`${label}  idx ${String(i).padStart(5)}   two_arg=${two} one_arg=${one} zero_arg=${zero}`);
}
check(mod._fp_is_two_arg(idxDirect) === 1 && mod._fp_is_one_arg(idxDirect) === 0,
      'CPython\'s trampoline identifies the Kotlin export as exactly (self, args)');

// The other side of the same coin: call_indirect is statically typed, so calling the one-arg export
// through the two-arg helper traps rather than coercing. A Kotlin trampoline whose signature does
// not match the slot CPython will call it from is a runtime trap, not a compile error.
let trapped = false;
if ((globalThis.__idxAlloc ?? -1) >= 0) {
    try { mod._call_fp(globalThis.__idxAlloc, 1, 2); } catch (e) {
        trapped = true;
        line(`calling the (i32)->i32 export through call_fp's (i32,i32)->i32 slot: ${e.constructor.name}: ${e.message}`);
    }
    check(trapped, 'a signature mismatch traps -- Kotlin trampoline types must match exactly');
}
if ((globalThis.__idxAlloc ?? -1) >= 0) {
    check(mod._fp_is_one_arg(globalThis.__idxAlloc) === 1,
          'and tells a one-argument Kotlin export apart from a two-argument one');
}
line();

if (failures > 0) {
    line('=== VERDICT: FAIL ===');
    process.exit(1);
}

// ================================================================================================
// Measurements -- the loop runs inside C, so no JS appears in the timed region for mechanism 1.
// ================================================================================================
const ms = (f) => { const t = performance.now(); f(); return performance.now() - t; };
const fmt = (t, n) => `${t.toFixed(2).padStart(9)} ms  (${(t * 1e6 / n).toFixed(1).padStart(7)} ns/call)`;

line('=== Measurement: cost of one upcall ===');
line('N indirect calls issued from C. The control is a C function reached the same way, so the');
line('difference is what crossing into Kotlin costs over staying inside Emscripten.');
line();

const N = 10_000_000;
// Only two-argument entries: call_fp_n's static type is (i32,i32)->i32 and call_indirect traps on
// anything else -- which is itself worth recording, since it is the same trap CPython would take.
const rows = [
    ['C -> C            (control)', idxC],
    ['C -> Kotlin, table.set     ', idxDirect],
    ['C -> Kotlin, addFunction JS', idxJs],
];
if (idxAddFnWasm >= 0 && idxAddFnWasm !== idxDirect) {
    rows.push(['C -> Kotlin, addFunction wasm', idxAddFnWasm]);
} else {
    line(`(mechanism 3 not timed separately: addFunction deduplicated it onto index ${idxAddFnWasm},`);
    line(` the very entry mechanism 1 installed -- so it is the same call, not a second path.)`);
    line();
}
for (const [, i] of rows) mod._call_fp_n(i, 100_000); // warm up
const times = {};
for (const [label, i] of rows) {
    if (i < 0) continue;
    const t = ms(() => mod._call_fp_n(i, N));
    times[label] = t;
    line(`${label}  ${fmt(t, N)}`);
}
line();
const base = times['C -> C            (control)'];
for (const [label, i] of rows) {
    if (i < 0 || label.startsWith('C -> C')) continue;
    const t = times[label];
    line(`${label}  ${(t / base).toFixed(2)}x the C control, ` +
         `${((t - base) * 1e6 / N).toFixed(1)} ns of crossing`);
}
line();

// The downcall figures from Test C, for the comparison that decides §7's shape.
line('For comparison, the downcall direction measured in Test C: direct @WasmImport 5.0 ns/call,');
line('JS trampoline 13.6-16.9 ns/call.');
line();

process.exit(0);
