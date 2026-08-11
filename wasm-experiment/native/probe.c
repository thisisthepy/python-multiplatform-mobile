// Stands in for CPython: owns data in linear memory and hands back addresses, exactly as
// PyUnicode_AsUTF8 or a composed shim would.
#include <emscripten.h>
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
