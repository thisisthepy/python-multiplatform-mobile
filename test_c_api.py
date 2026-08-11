import ctypes
try:
    print(ctypes.pythonapi.PyModule_GetName)
except Exception as e:
    print(e)
