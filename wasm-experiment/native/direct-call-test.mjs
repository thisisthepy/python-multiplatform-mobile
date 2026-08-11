import * as kotlin from './wasm-experiment.mjs';
import { add_two } from './probeA-wrapper.mjs';
console.log('');
console.log('=== Test A: @WasmImport bound to an Emscripten export ===');
console.log('the value handed to Kotlin\'s import object:', String(add_two).slice(0, 60).replace(/\n/g, ' '));
const r = kotlin.callCAddTwo(40, 2);
console.log(`Kotlin called cAddTwo(40, 2) -> ${r}`);
console.log(r === 42 ? '   PASS -- Kotlin/Wasm called Emscripten-compiled C through @WasmImport'
                     : '   FAIL');
process.exit(r === 42 ? 0 : 1);
