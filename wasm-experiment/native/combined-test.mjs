// Test C -- direct wasm-to-wasm calls AND a shared linear memory, in one instantiation graph.
//
// This is the configuration docs/wasm-design.md wanted and had written off as unavailable. Under
// Kotlin 2.4.10 the two were mutually exclusive:
//
//     Kotlin      @WasmImport          needs Emscripten's exports at instantiation time
//     Emscripten  -sIMPORTED_MEMORY    needs Kotlin's memory before that      -> cycle
//
// Kotlin 2.4.20-Beta2 imports its linear memory rather than defining it, which reverses the
// second arrow and dissolves the cycle:
//
//     1. Emscripten instantiates first and defines the memory      (probeA-wrapper.mjs, top-level await)
//     2. Kotlin's import object takes BOTH that memory and those exports
//     3. Kotlin instantiates
//
// ES module evaluation order does step 1 for us: `wasm-experiment.import-object.mjs` imports
// `./probeA-wrapper.mjs`, so the wrapper's top-level await settles before the import object is
// built. `patch-import-object.py` then does the only edit the integration needs -- swapping the
// placeholder `new WebAssembly.Memory({ initial: 0 })` for the wrapper's `wasmMemory`.
//
// Note what is NOT here: no -sIMPORTED_MEMORY, no binary patching. Emscripten is built exactly as
// it would be on its own; Kotlin is the side that adapts.

import * as kotlin from './wasm-experiment.mjs';
import { add_two, wasmMemory, mod } from './probeA-wrapper.mjs';

const line = (s = '') => console.log(s);
let failures = 0;
const check = (ok, label) => {
    line(`   ${ok ? 'PASS' : 'FAIL'} -- ${label}`);
    if (!ok) failures++;
};

// The Kotlin `js(...)` bodies for the copy-path benchmarks reach Emscripten through this global.
globalThis.__emModule = mod;

line();
line('=== Test C: direct @WasmImport calls + shared memory, one graph ===');
line();

// --- the graph itself -----------------------------------------------------------------------
const kotlinMemory = kotlin.memory;
line(`Emscripten's memory object     ${wasmMemory.constructor.name}, ${wasmMemory.buffer.byteLength / 65536} pages`);
line(`Kotlin's intrinsics.memory     ${kotlinMemory.constructor.name}, ${kotlinMemory.buffer.byteLength / 65536} pages`);
check(kotlinMemory === wasmMemory || kotlinMemory.buffer === wasmMemory.buffer,
      'Kotlin instantiated against the very memory Emscripten defined');
line();

// --- half 1: the control path, direct ----------------------------------------------------------
line('--- control path ---');
line(`the value handed to Kotlin's import object: ${String(add_two).slice(0, 60).replace(/\n/g, ' ')}`);
const r = kotlin.callCAddTwo(40, 2);
line(`Kotlin called cAddTwo(40, 2) -> ${r}`);
check(r === 42, '@WasmImport reached Emscripten-compiled C, with the memory shared');
line();

// --- half 2: the data path, shared -------------------------------------------------------------
line('--- data path ---');
const staticAddr = mod._get_static_message();
const seenByKotlin = kotlin.readCStringAt(staticAddr);
line(`C:      get_static_message() -> 0x${staticAddr.toString(16)}`);
line(`Kotlin: readCStringAt(that address) -> ${JSON.stringify(seenByKotlin)}`);
check(seenByKotlin === 'hello-from-the-cpython-side', 'Kotlin dereferenced a C address, no copy');

const heapAddr = mod._alloc_message('placeholder-------------------');
kotlin.writeCStringAt(heapAddr, 'written-by-kotlin');
const lenFromC = mod._str_len(heapAddr);
const backFromC = mod.UTF8ToString(heapAddr);
line(`C:      alloc_message() -> 0x${heapAddr.toString(16)} (malloc'd)`);
line(`Kotlin: writeCStringAt(addr, "written-by-kotlin")`);
line(`C:      str_len -> ${lenFromC}, UTF8ToString -> ${JSON.stringify(backFromC)}`);
check(backFromC === 'written-by-kotlin' && lenFromC === 17, 'C read back what Kotlin wrote');
line();

// --- the memory grows underneath Kotlin ---------------------------------------------------
// CPython links with -sALLOW_MEMORY_GROWTH, so this will happen in production on the first
// allocation that outruns INITIAL_MEMORY. A JS TypedArray view detaches when that happens;
// the question is whether Kotlin, which holds the memory as a wasm import rather than a view,
// keeps working. Nothing in the design docs checks this.
line('--- memory growth ---');
const pagesBefore = wasmMemory.buffer.byteLength / 65536;
const big = mod._malloc(64 * 1024 * 1024);
const pagesAfter = wasmMemory.buffer.byteLength / 65536;
line(`C: malloc(64 MiB) -> 0x${big.toString(16)},  ${pagesBefore} pages -> ${pagesAfter} pages`);
check(pagesAfter > pagesBefore, 'the shared memory actually grew');
check(kotlin.readCStringAt(staticAddr) === 'hello-from-the-cpython-side',
      'Kotlin still reads the pre-growth address correctly');
kotlin.writeCStringAt(big, 'past-the-old-end');
check(mod.UTF8ToString(big) === 'past-the-old-end',
      'Kotlin writes into memory that did not exist when it was instantiated');
line();

if (failures > 0) {
    line('=== VERDICT: FAIL ===');
    process.exit(1);
}

line('=== VERDICT: both hold at once ===');
line('Direct wasm-to-wasm control path and a shared linear memory, in a single instantiation');
line('graph. The cycle that forced JS trampolines is gone.');
line();

// ================================================================================================
// Measurements
// ================================================================================================
const ms = (f) => { const t = performance.now(); f(); return performance.now() - t; };
const fmt = (t, n) => `${t.toFixed(2).padStart(9)} ms  (${(t * 1e6 / n).toFixed(1).padStart(7)} ns/op)`;

line('=== Measurement 1: control path -- what the removed cycle was costing ===');
line('Same call, same process. The trampoline is what option 2 of the old design forced.');
const CALLS = 10_000_000;
kotlin.measureDirect(100_000); kotlin.measureTrampoline(100_000); // warm up
const tDirect = ms(() => kotlin.measureDirect(CALLS));
const tTramp = ms(() => kotlin.measureTrampoline(CALLS));
line(`direct @WasmImport   ${fmt(tDirect, CALLS)}`);
line(`JS trampoline        ${fmt(tTramp, CALLS)}`);
line(`                     trampoline is ${(tTramp / tDirect).toFixed(2)}x the direct call, ` +
     `${((tTramp - tDirect) * 1e6 / CALLS).toFixed(1)} ns per crossing saved`);
line();

line('=== Measurement 2: data path -- shared memory vs copying a C string through JS ===');
line('All loops run in Kotlin and end with a Kotlin String built from the same C address. The');
line('copied path is what docs/wasm-design.md assumed was forced; the shared paths are what the');
line('shared memory makes possible. Two string lengths, because marshalling scales with length.');
line();

// A long string, built by Kotlin into a C malloc'd buffer so both readers see the same bytes.
const LONG = 4000;
const longAddr = mod._malloc(LONG + 1);
kotlin.writeCStringAt(longAddr, 'x'.repeat(LONG));
check(mod._str_len(longAddr) === LONG, `long string staged in C memory (${LONG} bytes)`);

for (const [label, addr, len, iters] of [['27 bytes', staticAddr, 27, 1_000_000],
                                         [`${LONG} bytes`, longAddr, LONG, 20_000]]) {
    // warm up every path
    kotlin.measureSharedStrlen(addr, 1000);
    kotlin.measureSharedByteArrayCopy(addr, 1000);
    kotlin.measureSharedStringRead(addr, 1000);
    kotlin.measureSharedStringAscii(addr, 1000);
    kotlin.measureSharedStringReadNaive(addr, 1000);
    kotlin.measureCopiedStringRead(addr, 1000);

    const tStrlen = ms(() => kotlin.measureSharedStrlen(addr, iters));
    const tBytes = ms(() => kotlin.measureSharedByteArrayCopy(addr, iters));
    const tFast = ms(() => kotlin.measureSharedStringRead(addr, iters));
    const tAscii = ms(() => kotlin.measureSharedStringAscii(addr, iters));
    const tNaive = ms(() => kotlin.measureSharedStringReadNaive(addr, iters));
    const tCopy = ms(() => kotlin.measureCopiedStringRead(addr, iters));

    line(`--- ${label}, ${iters} iterations ---`);
    line(`shared, scan only     ${fmt(tStrlen, iters)}  walk to the NUL, build nothing`);
    line(`shared, -> ByteArray  ${fmt(tBytes, iters)}  scan + copy, no decode (the PyBytes case)`);
    line(`shared, -> String     ${fmt(tFast, iters)}  ByteArray + decodeToString()`);
    line(`shared, ASCII String  ${fmt(tAscii, iters)}  CharArray + concatToString()`);
    line(`shared, StringBuilder ${fmt(tNaive, iters)}  append(Char) per byte -- the naive way`);
    line(`copied through JS     ${fmt(tCopy, iters)}  UTF8ToString -> JS string -> Kotlin String`);
    line(`                      best shared String / copied = ` +
         `${(Math.min(tFast, tAscii, tNaive) / tCopy).toFixed(2)}x` +
         `   (>1 means the JS copy WINS)`);
    line();
}

line('=== Measurement 3: bulk -- N i32 out of a C-owned array ===');
line('The case the composed shim was still expected to be needed for.');
const N = 1000;
const arr = mod._alloc_int_array(N);
const REPS = 20_000;
kotlin.measureSharedBulkRead(arr, N); kotlin.measureCopiedBulkRead(arr, N);
const expectedSum = (N * (N - 1)) / 2;
const gotShared = kotlin.measureSharedBulkRead(arr, N);
const gotCopied = kotlin.measureCopiedBulkRead(arr, N);
check(gotShared === expectedSum && gotCopied === expectedSum,
      `both readers agree on the array contents (sum ${expectedSum})`);
const tBulkShared = ms(() => { for (let i = 0; i < REPS; i++) kotlin.measureSharedBulkRead(arr, N); });
const tBulkCopied = ms(() => { for (let i = 0; i < REPS; i++) kotlin.measureCopiedBulkRead(arr, N); });
const OPS = REPS * N;
line(`shared memory, ${N} i32  ${fmt(tBulkShared, OPS)}   per element`);
line(`via JS HEAP32,  ${N} i32  ${fmt(tBulkCopied, OPS)}   per element`);
line(`                     ${(tBulkCopied / tBulkShared).toFixed(2)}x`);
line();

line('=== What this means for composition ===');
line('A composed shim exists to trade N boundary crossings for 1. With the memory shared and the');
line('control path direct, a crossing costs the "direct @WasmImport" figure above -- so the');
line('question is whether saving that many nanoseconds justifies C code that has to be rebuilt');
line('and shipped. That is the same arithmetic that closed desktop composition in ROADMAP §6.');

process.exit(failures === 0 ? 0 : 1);
