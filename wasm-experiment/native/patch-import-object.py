#!/usr/bin/env python3
"""Point Kotlin's memory import at Emscripten's memory.

Kotlin 2.4.20-Beta2 imports its linear memory instead of defining it (verified by parsing the
binary: `memory definitions: 0`, and `intrinsics.memory` present as both a memory import with
limits {min:0, max:none} and an externref global import). The generated glue fills that import
with a throwaway placeholder:

    intrinsics: {
        memory: new WebAssembly.Memory({ initial: 0 }),
        tag: wasmTag
    },

Replacing that one expression with the Emscripten module's memory is the entire integration.
docs/wasm-design.md claims exactly this; this script is what tests the claim.

Note what is NOT needed any more:
  * no binary patching -- Kotlin's import declares no maximum, so any concrete memory satisfies
    it. The old `patch-memory-max.py` existed because the roles were reversed (Kotlin supplied an
    unbounded memory to an Emscripten import that declared a maximum), and that direction is
    unsatisfiable. This direction is trivially satisfiable.
  * no instantiation cycle -- Emscripten is instantiated first, so Kotlin can @WasmImport its
    exports in the same graph.
"""
import re
import sys

PLACEHOLDER = re.compile(r"memory:\s*new WebAssembly\.Memory\(\{[^}]*\}\)")

def main(path: str, wrapper: str = "./probeA-wrapper.mjs") -> int:
    src = open(path, encoding="utf-8").read()

    # The generated file already imports the wrapper under a base64-of-the-specifier identifier.
    m = re.search(
        r"^import \* as (\w+) from '" + re.escape(wrapper) + r"';", src, re.M
    )
    if not m:
        print(f"patch-import-object: could not find the {wrapper} import", file=sys.stderr)
        return 1
    ident = m.group(1)

    if not PLACEHOLDER.search(src):
        print("patch-import-object: placeholder memory expression not found -- the glue shape "
              "changed, or this Kotlin version exports its memory instead of importing it",
              file=sys.stderr)
        return 1

    patched = PLACEHOLDER.sub(f"memory: {ident}.wasmMemory", src, count=1)
    open(path, "w", encoding="utf-8").write(patched)
    print(f"patch-import-object: intrinsics.memory <- {ident}.wasmMemory  (1 substitution)")
    return 0

if __name__ == "__main__":
    sys.exit(main(*sys.argv[1:]))
