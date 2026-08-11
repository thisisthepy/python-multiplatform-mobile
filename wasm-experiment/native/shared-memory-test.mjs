// Test B -- can Emscripten import the linear memory that Kotlin/Wasm exports, so that an address
// produced by C reads back in Kotlin as the bytes C wrote?
//
// This is the decisive question for ROADMAP §10. If it holds, string marshalling on WASM is not
// cheaper, it is absent: Kotlin dereferences CPython's char* directly instead of copying through
// JS. Every measurement on Android and desktop found marshalling, not crossing count, to be the
// dominant cost, so this is the one that matters.
//
// Order is forced, and is the whole point:
//   1. instantiate Kotlin      -- it defines and exports a memory (0 pages, empty)
//   2. grow that memory        -- Emscripten's module declares a 256-page minimum
//   3. instantiate Emscripten with env.memory = Kotlin's memory
//
// Note what this order costs: Kotlin is instantiated first, so it cannot @WasmImport anything
// from Emscripten. Direct wasm-to-wasm calls and shared memory are mutually exclusive in one
// instantiation graph, as long as Kotlin is the side that owns the memory.

import * as kotlin from '../build/compileSync/wasmJs/main/developmentExecutable/kotlin/wasm-experiment.mjs';
import probeFactory from './probeB.mjs';

const line = (s) => console.log(s);

line('');
line('=== Test B: Emscripten importing Kotlin\'s exported memory ===');

const memory = kotlin.memory;
line(`kotlin memory pages before grow  ${memory.buffer.byteLength / 65536}`);

const NEEDED = 256; // probeB.wasm declares (import "env" "memory" (memory 256 ...))
const have = memory.buffer.byteLength / 65536;
if (have < NEEDED) memory.grow(NEEDED - have);
line(`kotlin memory pages after grow   ${memory.buffer.byteLength / 65536}`);

let probe;
try {
    probe = await probeFactory({ wasmMemory: memory });
} catch (e) {
    line(`FAILED to instantiate Emscripten against Kotlin's memory: ${e}`);
    process.exit(1);
}
line('emscripten instantiated against it: OK');
line('');

// --- C produces an address, Kotlin reads it -------------------------------------------------
const staticAddr = probe._get_static_message();
line(`C: get_static_message() -> 0x${staticAddr.toString(16)}`);

const seenByKotlin = kotlin.readCStringAt(staticAddr);
line(`Kotlin: readCStringAt(same address) -> ${JSON.stringify(seenByKotlin)}`);

const expected = 'hello-from-the-cpython-side';
const readOk = seenByKotlin === expected;
line(`   ${readOk ? 'PASS' : 'FAIL'} -- Kotlin ${readOk ? 'sees' : 'does NOT see'} what C wrote`);
line('');

// --- C mallocs, Kotlin writes, C reads back -------------------------------------------------
const heapAddr = probe._alloc_message('placeholder-------------------');
line(`C: alloc_message() -> 0x${heapAddr.toString(16)} (malloc'd, not static)`);

kotlin.writeCStringAt(heapAddr, 'written-by-kotlin');
const lenFromC = probe._str_len(heapAddr);
const backFromC = probe.UTF8ToString(heapAddr);
line(`Kotlin: writeCStringAt(addr, "written-by-kotlin")`);
line(`C: str_len(addr) -> ${lenFromC}, UTF8ToString(addr) -> ${JSON.stringify(backFromC)}`);

const writeOk = backFromC === 'written-by-kotlin' && lenFromC === 17;
line(`   ${writeOk ? 'PASS' : 'FAIL'} -- C ${writeOk ? 'sees' : 'does NOT see'} what Kotlin wrote`);
line('');

line('=== VERDICT ===');
if (readOk && writeOk) {
    line('Kotlin and CPython-side C share one linear memory. No copying, no JS in the data path.');
    line('Kotlin dereferences a C address with Pointer(addr) and gets the bytes C put there.');
} else {
    line('The memories are not shared; the JS-bridge design stands.');
    process.exit(1);
}
