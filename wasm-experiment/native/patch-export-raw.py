#!/usr/bin/env python3
"""Re-export the Kotlin instance's raw wasm exports from the generated entry module.

Kotlin's generated `wasm-experiment.mjs` destructures the exports it knows about -- the @JsExport
adapters -- and drops the instance on the floor. @WasmExport functions are in the wasm export
section but have no JS binding, so Test E cannot reach them.

This appends one line. It is a test-harness convenience, not something the design depends on: in a
real integration the same value is available wherever the module is instantiated.
"""
import sys
from pathlib import Path

path = Path(sys.argv[1])
text = path.read_text()
marker = "export const rawExports"
if marker in text:
    print(f"   already patched: {path.name}")
    sys.exit(0)
if "const exports = wasmInstance.exports" not in text:
    sys.exit(f"unexpected shape: no `const exports = wasmInstance.exports` in {path}")
path.write_text(text + "\n// added by patch-export-raw.py (Test E)\nexport const rawExports = exports;\n")
print(f"   patched {path.name}: exposed rawExports")
