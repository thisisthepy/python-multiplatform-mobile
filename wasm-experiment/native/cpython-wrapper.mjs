// Test D -- the real thing. Boots the CPython 3.14.2 Emscripten build and re-exports its raw wasm
// functions as ES module bindings, which is the shape @WasmImport(module, name) resolves against.
//
// Everything here is the same pattern as probeA-wrapper.mjs; the only difference is that the
// module on the other side is a 10 MB CPython instead of a 4 KB toy.
//
// The one build change CPython needed: `wasmExports` and `wasmMemory` added to
// -sEXPORTED_RUNTIME_METHODS. Those are JS-glue settings, not in PEP 783's ABI-sensitive list, so
// adding them does not cost the pyemscripten platform. Without them the 7000+ wasm exports are
// present in the binary but not reachable from JS, and there is nothing to hand to @WasmImport.

import EmscriptenModule from "./python.mjs";
import fs from "node:fs";

const PYTHON_DIR = process.env.PMP_PYTHON_DIR;
if (!PYTHON_DIR) throw new Error("PMP_PYTHON_DIR must point at the CPython build directory");

const M = await EmscriptenModule({
    noInitialRun: true,
    thisProgram: PYTHON_DIR + "/python.sh",
    arguments: [],
    preRun(Module) {
        globalThis.Module = Module;
        for (const dir of fs.readdirSync("/")
                            .filter((d) => !["dev", "lib", "proc"].includes(d))
                            .map((d) => "/" + d)) {
            Module.FS.mkdirTree(dir);
            Module.FS.mount(Module.FS.filesystems.NODEFS, { root: dir }, dir);
        }
        Module.FS.chdir(PYTHON_DIR);
        Object.assign(Module.ENV, process.env);
        delete Module.ENV.PATH;
    },
});

const E = M.wasmExports;

// Bring the interpreter up from JS. Kotlin could do this too -- Py_InitializeEx is in E -- but
// what Test D is proving is the call and memory path, not who types the first call.
E.Py_InitializeEx(0);

export const wasmMemory = M.wasmMemory;
export const mod = M;
export const exports_ = E;

// The subset Test D drives. Every one of these is a raw wasm export.
export const PyRun_SimpleString = E.PyRun_SimpleString;
export const PyImport_AddModule = E.PyImport_AddModule;
export const PyModule_GetDict = E.PyModule_GetDict;
export const PyDict_GetItemString = E.PyDict_GetItemString;
export const PyLong_AsLong = E.PyLong_AsLong;
export const PyUnicode_AsUTF8 = E.PyUnicode_AsUTF8;
export const PyErr_Occurred = E.PyErr_Occurred;
export const pmalloc = E.malloc;
export const pfree = E.free;
export default E.PyRun_SimpleString;
