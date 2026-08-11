import re

with open("python-multiplatform/src/artMain/cinterop/jni_onload.def", "r") as f:
    text = f.read()

# Add extern declarations
externs = """
extern jlong PyObject_Str(jlong);
extern jint PyObject_IsTrue(jlong);
extern jlong PyObject_CallNoArgs(jlong);
extern jlong PyObject_CallObject(jlong, jlong);
extern const char* PyUnicode_AsUTF8(jlong);
extern jlong PyUnicode_FromString(const char*);
"""
text = text.replace("extern void PyErr_Clear(void);", "extern void PyErr_Clear(void);\n" + externs)

# Add wrappers
wrappers = """
static jlong f_PyImport_AddModuleRefN(JNIEnv *e, jclass c, jlong n) { return PyImport_AddModuleRef((const char*)n); }
static jlong f_PyRun_StringN(JNIEnv *e, jclass c, jlong str, jint start, jlong globals, jlong locals) { return PyRun_String((const char*)str, start, globals, locals); }
static jlong f_PyObject_StrN(JNIEnv *e, jclass c, jlong o) { return PyObject_Str(o); }
static jint f_PyObject_IsTrueN(JNIEnv *e, jclass c, jlong o) { return PyObject_IsTrue(o); }
static jlong f_PyObject_CallNoArgsN(JNIEnv *e, jclass c, jlong callable) { return PyObject_CallNoArgs(callable); }
static jlong f_PyObject_CallObjectN(JNIEnv *e, jclass c, jlong callable, jlong args) { return PyObject_CallObject(callable, args); }
static jlong f_PyUnicode_AsUTF8(JNIEnv *e, jclass c, jlong unicode) { return (jlong)PyUnicode_AsUTF8(unicode); }
static jlong f_PyUnicode_FromString(JNIEnv *e, jclass c, jlong str) { return PyUnicode_FromString((const char*)str); }
"""
text = text.replace("static jlong f_PyErr_Occurred(JNIEnv *e, jclass c) { return PyErr_Occurred(); }", "static jlong f_PyErr_Occurred(JNIEnv *e, jclass c) { return PyErr_Occurred(); }\n" + wrappers)

# Add to methods array
new_methods = """
        {"PyUnicode_AsUTF8", "(J)J", (void *)&PyUnicode_AsUTF8},
        {"PyUnicode_AsUTF8F", "(J)J", (void *)&f_PyUnicode_AsUTF8},
        {"PyUnicode_FromString", "(J)J", (void *)&PyUnicode_FromString},
        {"PyUnicode_FromStringF", "(J)J", (void *)&f_PyUnicode_FromString},

        {"PyImport_AddModuleRefN", "(J)J", (void *)&f_PyImport_AddModuleRefN},
        {"PyRun_StringN", "(JIJJ)J", (void *)&f_PyRun_StringN},
        {"PyObject_StrN", "(J)J", (void *)&f_PyObject_StrN},
        {"PyObject_IsTrueN", "(J)I", (void *)&f_PyObject_IsTrueN},
        {"PyObject_CallNoArgsN", "(J)J", (void *)&f_PyObject_CallNoArgsN},
        {"PyObject_CallObjectN", "(JJ)J", (void *)&f_PyObject_CallObjectN},
"""
text = text.replace('        {"PyObject_GetAttrStringN", "(JJ)J", (void *)&f_PyObject_GetAttrString},', '        {"PyObject_GetAttrStringN", "(JJ)J", (void *)&f_PyObject_GetAttrString},\n' + new_methods)

# Update method count
m = re.search(r'RegisterNatives\(env, clazz, methods, (\d+)\);', text)
if m:
    old_count = int(m.group(1))
    new_count = old_count + 10
    text = text.replace(f'RegisterNatives(env, clazz, methods, {old_count});', f'RegisterNatives(env, clazz, methods, {new_count});')

with open("python-multiplatform/src/artMain/cinterop/jni_onload.def", "w") as f:
    f.write(text)
