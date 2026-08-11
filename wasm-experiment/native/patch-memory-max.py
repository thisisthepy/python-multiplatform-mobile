"""Give Kotlin's exported memory a maximum, so an Emscripten import can accept it.

Kotlin emits its memory as `WasmLimits(0, null)` -- zero initial pages and NO maximum. Wasm
requires a supplied memory's limits to sit inside the ones the importer declared, and Emscripten
always declares a maximum, so an unbounded memory can never satisfy it:

    LinkError: Import #0 module="env" function="memory": memory import has no maximum limit

Nothing else is wrong with the pairing. This rewrites section 5 from `{min: 0}` to
`{min: 0, max: 32768}` (Emscripten's default MAXIMUM_MEMORY) to show that, and to isolate the blocker to a
single value the compiler chooses. Section sizes are length-prefixed and nothing in the binary
holds an absolute offset, so growing the section by three bytes is safe.
"""
import sys


def uleb(b, i):
    r = s = 0
    while True:
        x = b[i]
        i += 1
        r |= (x & 0x7F) << s
        s += 7
        if not x & 0x80:
            return r, i


def enc(n):
    out = bytearray()
    while True:
        b = n & 0x7F
        n >>= 7
        out.append(b | (0x80 if n else 0))
        if not n:
            return bytes(out)


def patch(src, dst, max_pages=32768):
    f = open(src, "rb").read()
    i = 8
    while i < len(f):
        start = i
        sid = f[i]
        i += 1
        size, i = uleb(f, i)
        end = i + size
        if sid == 5:
            j = i
            n, j = uleb(f, j)
            assert n == 1, f"expected one memory, found {n}"
            flags = f[j]
            j += 1
            mn, j = uleb(f, j)
            if flags & 1:
                mx, j = uleb(f, j)
                print(f"memory already bounded: min={mn} max={mx}; nothing to do")
                return False
            body = b"\x01" + b"\x01" + enc(mn) + enc(max_pages)
            new = bytes([5]) + enc(len(body)) + body
            out = f[:start] + new + f[end:]
            open(dst, "wb").write(out)
            print(f"patched memory: min={mn} max=None  ->  min={mn} max={max_pages}")
            print(f"  {src} ({len(f)} bytes) -> {dst} ({len(out)} bytes)")
            return True
        i = end
    raise SystemExit("no memory section found")


if __name__ == "__main__":
    patch(sys.argv[1], sys.argv[2])
