#!/bin/bash
# Reproduces both experiments. Needs emsdk (~/emsdk) and the Node that Gradle downloaded.
set -e
cd "$(dirname "$0")/.."
NODE=$(find ~/.gradle/nodejs -name node -type f -perm +111 | head -1)
source ~/emsdk/emsdk_env.sh > /dev/null 2>&1

EXPORTS='["_add_two","_get_static_message","_alloc_message","_str_len","_poke","_malloc","_free"]'
RT='["UTF8ToString"]'
( cd native
  emcc probe.c -O2 -o probeA.mjs -sMODULARIZE -sEXPORT_ES6 -sEXPORTED_FUNCTIONS="$EXPORTS" -sEXPORTED_RUNTIME_METHODS="$RT"
  emcc probe.c -O2 -o probeB.mjs -sMODULARIZE -sEXPORT_ES6 -sIMPORTED_MEMORY -sALLOW_MEMORY_GROWTH \
       -sEXPORTED_FUNCTIONS="$EXPORTS" -sEXPORTED_RUNTIME_METHODS="$RT" )

./gradlew compileDevelopmentExecutableKotlinWasmJs --console=plain
K=build/compileSync/wasmJs/main/developmentExecutable/kotlin
cp native/probeA.mjs native/probeA.wasm native/probeA-wrapper.mjs native/direct-call-test.mjs "$K/"

echo; echo "##### Test A: @WasmImport against an Emscripten export"
"$NODE" "$K/direct-call-test.mjs"

echo; echo "##### Test B: Emscripten importing Kotlin's exported memory"
# Kotlin declares its memory with no maximum, which no Emscripten import can accept. Patch it.
cp "$K/wasm-experiment.wasm" /tmp/kotlin-orig.wasm
python3 native/patch-memory-max.py /tmp/kotlin-orig.wasm "$K/wasm-experiment.wasm"
"$NODE" native/shared-memory-test.mjs
