package python.multiplatform.ffi.utils

import python.multiplatform.ffi.types.modules.PyModule

/**
 * Import-related helpers that go beyond the single
 * [python.multiplatform.ffi.Python3.import] entry point (module reloading,
 * inspecting `sys.modules`, adding a module without fully importing it).
 *
 * Backed by `PyImport_ReloadModule`, `PyImport_GetModuleDict`,
 * `PyImport_AddModule`, all present in `EmbedAPI`.
 */
object PyImport {
    /** `importlib.reload(module)`. */
    fun reload(module: PyModule): PyModule {
        TODO("Not yet implemented")
    }

    /** Looks up an already-imported module by name in `sys.modules`, without importing it. */
    fun getModuleOrNull(name: String): PyModule? {
        TODO("Not yet implemented")
    }

    /** Whether [name] is currently present in `sys.modules`. */
    fun isImported(name: String): Boolean {
        TODO("Not yet implemented")
    }
}
