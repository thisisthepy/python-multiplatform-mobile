#include <stdio.h>
#include <Python.h>

int main() {
    Py_Initialize();
    PyObject *exc = PyExc_SystemError;
    PyObject *meta = PyObject_Type(exc);
    PyObject *name = PyObject_GetAttrString(meta, "__name__");
    const char *name_str = PyUnicode_AsUTF8(name);
    printf("Type: %p\n", meta);
    printf("Name: %s\n", name_str);
    Py_Finalize();
    return 0;
}
