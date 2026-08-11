import re

with open("python-multiplatform/src/androidMain/kotlin/python/native/ffi/bindings.kt", "r") as f:
    text = f.read()

funcs_to_comment = [
    r"external fun PyRun_String\(str: String, start: Int, globals: JNIPointer, locals: JNIPointer\): JNIPointer\?",
    r"external fun PyUnicode_AsUTF8\(unicode: JNIPointer\): String\?",
    r"external fun PyUnicode_FromString\(str: String\): JNIPointer\?",
    r"external fun PyObject_Str\(o: JNIPointer\): JNIPointer\?",
    r"external fun PyObject_IsTrue\(o: JNIPointer\): Int",
    r"external fun PyObject_CallNoArgs\(callable: JNIPointer\): JNIPointer\?",
    r"external fun PyObject_CallObject\(callable: JNIPointer, args: JNIPointer\): JNIPointer\?",
    r"external fun PyImport_AddModuleRef\(name: String\): JNIPointer\?"
]

for func in funcs_to_comment:
    text = re.sub(r'(\s+)(' + func + r')', r'\1// \2', text)

# Add new fast and ordinary methods
new_decls = """
    @JvmStatic @dalvik.annotation.optimization.CriticalNative external fun PyUnicode_AsUTF8(unicode: Long): Long
    @JvmStatic @dalvik.annotation.optimization.FastNative external fun PyUnicode_AsUTF8F(unicode: Long): Long
    @JvmStatic @dalvik.annotation.optimization.CriticalNative external fun PyUnicode_FromString(str: Long): Long
    @JvmStatic @dalvik.annotation.optimization.FastNative external fun PyUnicode_FromStringF(str: Long): Long

    @JvmStatic external fun PyImport_AddModuleRefN(name: Long): Long
    @JvmStatic external fun PyRun_StringN(str: Long, start: Int, globals: Long, locals: Long): Long
    @JvmStatic external fun PyObject_StrN(o: Long): Long
    @JvmStatic external fun PyObject_IsTrueN(o: Long): Int
    @JvmStatic external fun PyObject_CallNoArgsN(callable: Long): Long
    @JvmStatic external fun PyObject_CallObjectN(callable: Long, args: Long): Long
"""

# Insert before the last closing brace
last_brace = text.rfind("}")
text = text[:last_brace] + new_decls + "\n}\n"

with open("python-multiplatform/src/androidMain/kotlin/python/native/ffi/bindings.kt", "w") as f:
    f.write(text)
