import re

with open("python-multiplatform/src/androidMain/kotlin/python/native/ffi/EmbedAPI.android.kt", "r") as f:
    text = f.read()

# Replace PyRun_String
old_pyrun_string = r"actual fun PyRun_String\(str: String, start: Int, globals: NativePointer, locals: NativePointer\): NativePointer\? = python\.native\.ffi\.bindings\.PyRun_String\(str, start, globals\.toPlatformPointer\(\), locals\.toPlatformPointer\(\)\)\.toNativePointer\(\) // 수동 추가"
new_pyrun_string = """actual fun PyRun_String(str: String, start: Int, globals: NativePointer, locals: NativePointer): NativePointer? {
    val ptr = encodeScratchUtf8(str)
    return python.native.ffi.bindings.PyRun_StringN(ptr, start, globals.toPlatformPointer(), locals.toPlatformPointer()).toNativePointer()
}"""
text = re.sub(old_pyrun_string, new_pyrun_string, text)

# Replace PyUnicode_AsUTF8
old_asutf8 = r"actual inline fun PyUnicode_AsUTF8\(unicode: NativePointer\): String\? = python\.native\.ffi\.bindings\.PyUnicode_AsUTF8\(unicode\.toPlatformPointer\(\)\) // 수동 추가"
new_asutf8 = """actual inline fun PyUnicode_AsUTF8(unicode: NativePointer): String? {
    val ptr = if (python.native.ffi.bindings.preferFastNative) python.native.ffi.bindings.PyUnicode_AsUTF8F(unicode.toPlatformPointer()) else python.native.ffi.bindings.PyUnicode_AsUTF8(unicode.toPlatformPointer())
    return if (ptr != 0L) python.native.ffi.bindings.ffiReadUtf8(ptr) else null
}"""
text = re.sub(old_asutf8, new_asutf8, text)

# Replace PyUnicode_FromString
old_fromstring = r"actual fun PyUnicode_FromString\(str: String\): NativePointer\? = python\.native\.ffi\.bindings\.PyUnicode_FromString\(str\)\.toNativePointer\(\)"
new_fromstring = """actual fun PyUnicode_FromString(str: String): NativePointer? {
    val ptr = encodeScratchUtf8(str)
    return (if (python.native.ffi.bindings.preferFastNative) python.native.ffi.bindings.PyUnicode_FromStringF(ptr) else python.native.ffi.bindings.PyUnicode_FromString(ptr)).toNativePointer()
}"""
text = re.sub(old_fromstring, new_fromstring, text)

# Replace PyObject_Str
old_pystr = r"actual fun PyObject_Str\(o: NativePointer\): NativePointer\? = python\.native\.ffi\.bindings\.PyObject_Str\(o\.toPlatformPointer\(\)\)\.toNativePointer\(\)"
new_pystr = "actual fun PyObject_Str(o: NativePointer): NativePointer? = python.native.ffi.bindings.PyObject_StrN(o.toPlatformPointer()).toNativePointer()"
text = re.sub(old_pystr, new_pystr, text)

# Replace PyObject_IsTrue
old_istrue = r"actual inline fun PyObject_IsTrue\(o: NativePointer\): Int = python\.native\.ffi\.bindings\.PyObject_IsTrue\(o\.toPlatformPointer\(\)\)"
new_istrue = "actual inline fun PyObject_IsTrue(o: NativePointer): Int = python.native.ffi.bindings.PyObject_IsTrueN(o.toPlatformPointer())"
text = re.sub(old_istrue, new_istrue, text)

# Replace PyObject_CallNoArgs
old_callnoargs = r"actual fun PyObject_CallNoArgs\(callable: NativePointer\): NativePointer\? = python\.native\.ffi\.bindings\.PyObject_CallNoArgs\(callable\.toPlatformPointer\(\)\)\.toNativePointer\(\)"
new_callnoargs = "actual fun PyObject_CallNoArgs(callable: NativePointer): NativePointer? = python.native.ffi.bindings.PyObject_CallNoArgsN(callable.toPlatformPointer()).toNativePointer()"
text = re.sub(old_callnoargs, new_callnoargs, text)

# Replace PyObject_CallObject
old_callobject = r"actual fun PyObject_CallObject\(callable: NativePointer, args: NativePointer\): NativePointer\? = python\.native\.ffi\.bindings\.PyObject_CallObject\(callable\.toPlatformPointer\(\), args\.toPlatformPointer\(\)\)\.toNativePointer\(\)"
new_callobject = "actual fun PyObject_CallObject(callable: NativePointer, args: NativePointer): NativePointer? = python.native.ffi.bindings.PyObject_CallObjectN(callable.toPlatformPointer(), args.toPlatformPointer()).toNativePointer()"
text = re.sub(old_callobject, new_callobject, text)

# Replace PyImport_AddModuleRef
old_addmoduleref = r"actual fun PyImport_AddModuleRef\(name: String\): NativePointer\? = python\.native\.ffi\.bindings\.PyImport_AddModuleRef\(name\)\.toNativePointer\(\)"
new_addmoduleref = """actual fun PyImport_AddModuleRef(name: String): NativePointer? {
    val ptr = internedUtf8(name)
    return python.native.ffi.bindings.PyImport_AddModuleRefN(ptr).toNativePointer()
}"""
text = re.sub(old_addmoduleref, new_addmoduleref, text)

with open("python-multiplatform/src/androidMain/kotlin/python/native/ffi/EmbedAPI.android.kt", "w") as f:
    f.write(text)
