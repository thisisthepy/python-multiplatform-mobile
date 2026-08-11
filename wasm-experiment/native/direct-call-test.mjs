import * as kotlin from './wasm-experiment.mjs';
import { add_two } from './probeA-wrapper.mjs';
console.log('');
console.log('=== Test A: @WasmImport bound to an Emscripten export ===');
console.log('the value handed to Kotlin\'s import object:', String(add_two).slice(0, 60).replace(/\n/g, ' '));
const r = kotlin.callCAddTwo(40, 2);
console.log(`Kotlin called cAddTwo(40, 2) -> ${r}`);
console.log(r === 42 ? '   PASS -- Kotlin/Wasm called Emscripten-compiled C through @WasmImport'
                     : '   FAIL');
console.log('');
console.log('=== Performance Test: Direct Wasm Call vs JS Trampoline ===');
const ITERATIONS = 10000000;

// Warmup
kotlin.measureDirect(10000);
kotlin.measureTrampoline(10000);

const startDirect = performance.now();
kotlin.measureDirect(ITERATIONS);
const timeDirect = performance.now() - startDirect;
console.log(`Direct wasm call (${ITERATIONS} iters): ${timeDirect.toFixed(2)} ms`);

const startTrampoline = performance.now();
kotlin.measureTrampoline(ITERATIONS);
const timeTrampoline = performance.now() - startTrampoline;
console.log(`JS trampoline call (${ITERATIONS} iters): ${timeTrampoline.toFixed(2)} ms`);

const ratio = timeTrampoline / timeDirect;
console.log(`JS trampoline is ${ratio.toFixed(2)}x slower`);

process.exit(0);
