package python.multiplatform.ffi.types.basic

import python.multiplatform.ffi.PyObject
import python.multiplatform.ffi.PyType
import python.native.ffi.NativePointer

/**
 * Wrapper around a Python `complex` object.
 *
 * Kept intentionally minimal (per task scope: "if straightforward") since
 * `EmbedAPI` does not expose any `PyComplex_*` Stable ABI entry points --
 * only the generic `PyObject_*`/`PyNumber_*` protocol functions would be
 * available to implement this later (e.g. going through `complex(real, imag)`
 * via `PyObject_Call` against the builtin, and reading back `.real`/`.imag`
 * attributes rather than a dedicated C struct accessor).
 */
open class PyComplex(pointer: NativePointer, borrowed: Boolean) : PyObject(pointer, borrowed) {
    companion object {
        /** The `PyType` for `complex` (`builtins.complex`). */
        val TYPE: PyType by lazy { TODO("Not yet implemented") }

        /** Constructs a new `complex(real, imag)` object. */
        fun from(real: Double, imag: Double): PyComplex {
            TODO("Not yet implemented")
        }
    }

    val real: Double
        get() = TODO("Not yet implemented")

    val imag: Double
        get() = TODO("Not yet implemented")
}
