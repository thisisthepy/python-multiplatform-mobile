// Stands in for CPython: owns data in linear memory and hands back addresses, exactly as
// PyUnicode_AsUTF8 or a composed shim would.
#include <emscripten.h>
#include <stdint.h>
#include <stdlib.h>
#include <string.h>

static const char kStatic[] = "hello-from-the-cpython-side";

EMSCRIPTEN_KEEPALIVE int add_two(int a, int b) { return a + b; }

// Returns an address into THIS module's linear memory. If the memory is shared, Kotlin can read
// the bytes at this address directly; if it is not, Kotlin reads its own zeroes.
EMSCRIPTEN_KEEPALIVE const char* get_static_message(void) { return kStatic; }

EMSCRIPTEN_KEEPALIVE char* alloc_message(const char* src) {
    char* p = (char*)malloc(strlen(src) + 1);
    strcpy(p, src);
    return p;
}

EMSCRIPTEN_KEEPALIVE int str_len(const char* p) { return (int)strlen(p); }

EMSCRIPTEN_KEEPALIVE void poke(char* p, int i, int v) { p[i] = (char)v; }

// Stands in for the bulk case -- PyList_GET_ITEM over a list, or any C-owned array of i32 that
// Kotlin wants to walk. This is the one place the composed-shim argument was still expected to
// survive, so it needs a measurement rather than an assumption.
EMSCRIPTEN_KEEPALIVE int* alloc_int_array(int n) {
    int* a = (int*)malloc((size_t)n * sizeof(int));
    for (int i = 0; i < n; i++) a[i] = i;
    return a;
}

// ------------------------------------------------------------------------------------------------
// Test E -- upcalls. Stands in for CPython calling a function pointer it was handed: tp_new, a
// PyCFunction in a PyMethodDef, a getset closure. On wasm a "C function pointer" is an index into
// the module's indirect function table, and the call is a `call_indirect` with a static type.
//
// The question the design has open is what has to sit at that index for Kotlin/Wasm to be reached,
// and what the crossing costs. Everything below takes the index as a plain int, exactly as CPython
// would.
// ------------------------------------------------------------------------------------------------

typedef int (*two_arg_i)(int, int);
typedef int (*one_arg_i)(int);
typedef int (*zero_arg_i)(void);

EMSCRIPTEN_KEEPALIVE int call_fp(int fp, int a, int b) {
    return ((two_arg_i)(uintptr_t)fp)(a, b);
}

/** N indirect calls from inside C. This is the crossing cost with no JS in the measurement loop. */
EMSCRIPTEN_KEEPALIVE int call_fp_n(int fp, int n) {
    two_arg_i f = (two_arg_i)(uintptr_t)fp;
    int sum = 0;
    for (int i = 0; i < n; i++) sum += f(i, 1);
    return sum;
}

/** Control: a C function of the same shape, called through the same indirect path. */
EMSCRIPTEN_KEEPALIVE int c_add(int a, int b) { return a + b; }
EMSCRIPTEN_KEEPALIVE int c_add_fp(void) { return (int)(uintptr_t)(two_arg_i)&c_add; }

// CPython 3.14's PY_CALL_TRAMPOLINE dispatches on arity with
// __builtin_wasm_test_function_pointer_signature (a wasm-gc ref.test on the table entry) --
// see Python/emscripten_trampoline_inner.c. If a Kotlin export sitting in the table does not
// answer that test, CPython cannot call it at all, whatever else works. So run the same test.
EMSCRIPTEN_KEEPALIVE int fp_is_two_arg(int fp) {
    void* func = (void*)(uintptr_t)fp;
    return __builtin_wasm_test_function_pointer_signature((two_arg_i)func) ? 1 : 0;
}
EMSCRIPTEN_KEEPALIVE int fp_is_one_arg(int fp) {
    void* func = (void*)(uintptr_t)fp;
    return __builtin_wasm_test_function_pointer_signature((one_arg_i)func) ? 1 : 0;
}
EMSCRIPTEN_KEEPALIVE int fp_is_zero_arg(int fp) {
    void* func = (void*)(uintptr_t)fp;
    return __builtin_wasm_test_function_pointer_signature((zero_arg_i)func) ? 1 : 0;
}
