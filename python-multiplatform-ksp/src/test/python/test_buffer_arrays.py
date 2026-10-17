"""Zero-copy buffer-protocol array parameters (issue #149, identity-safe part; SPEC N-8 / N-9).

A parameter annotated `array.array[float]`, `array.array[int]`, `npt.NDArray[np.float64]` or
`npt.NDArray[np.int64]` is a *buffer array*: the entry guard takes a `Py_buffer` export of the exact
type (1-D, C-contiguous, 8-byte items, format `d` or `q`/`l`, aligned, writable when the function
stores), holds it until the function returns on every exit, and every element access goes straight
to the buffer memory as one relaxed atomic 8-byte load or store. No copy-in, no write-back.

Everything here is single-threaded and differential against the interpreted function
(`__typedpython_interpreted__`): same result, same exception type and text, same final contents of
every argument. Tests that a path was *compiled* compare `__typedpython_deopts__` before and after,
so a function that silently stayed interpreted fails them (that is what the red run shows).

Not covered, on purpose: what a concurrent resize by another thread observes (open point of #149;
API.md "Buffer arrays").
"""
from __future__ import annotations

import array
import dataclasses
import importlib.machinery
import importlib.util
import sys
import textwrap

import numpy as np
import pytest

from typedpython import cbuild, cgen, frontend, verify

SOURCE = textwrap.dedent('''
    # typedpython: compiled
    import array
    import math
    import numpy as np
    import numpy.typing as npt


    def sum_ad(a: array.array[float]) -> float:
        s = 0.0
        for i in range(len(a)):
            s += a[i]
        return s


    def sum_aq(a: array.array[int]) -> int:
        s = 0
        for i in range(len(a)):
            s += a[i]
        return s


    def sum_nf(x: npt.NDArray[np.float64]) -> float:
        s: float = 0.0
        for i in range(len(x)):
            s += x[i]
        return s


    def sum_ni(x: npt.NDArray[np.int64]) -> int:
        s: int = 0
        for i in range(len(x)):
            s += x[i]
        return s


    def get_ad(a: array.array[float], i: int) -> float:
        return a[i]


    def get_aq(a: array.array[int], i: int) -> int:
        return a[i]


    def get_nf(x: npt.NDArray[np.float64], i: int) -> float:
        return x[i]


    def get_ni(x: npt.NDArray[np.int64], i: int) -> int:
        return x[i]


    def set_ad(a: array.array[float], i: int, v: float) -> None:
        a[i] = v


    def set_aq(a: array.array[int], i: int, v: int) -> None:
        a[i] = v


    def set_nf(x: npt.NDArray[np.float64], i: int, v: float) -> None:
        x[i] = v


    def set_ni(x: npt.NDArray[np.int64], i: int, v: int) -> None:
        x[i] = v


    def length_nf(x: npt.NDArray[np.float64]) -> int:
        return len(x)


    def length_ad(a: array.array[float]) -> int:
        return len(a)


    def scale_nf(x: npt.NDArray[np.float64], k: float) -> None:
        for i in range(len(x)):
            x[i] = x[i] * k


    def scale_ad(a: array.array[float], k: float) -> None:
        for i in range(len(a)):
            a[i] = a[i] * k


    def iota_ni(x: npt.NDArray[np.int64]) -> None:
        for i in range(len(x)):
            x[i] = i


    def iota_aq(a: array.array[int]) -> None:
        for i in range(len(a)):
            a[i] = i


    def copy_nf(dst: npt.NDArray[np.float64], src: npt.NDArray[np.float64]) -> None:
        for i in range(len(dst)):
            dst[i] = src[i]


    def copy_ad(dst: array.array[float], src: array.array[float]) -> None:
        for i in range(len(dst)):
            dst[i] = src[i]


    def cascade_nf(a: npt.NDArray[np.float64], b: npt.NDArray[np.float64]) -> None:
        for i in range(len(a)):
            a[i] = b[i] + 1.0


    def fill_n(x: npt.NDArray[np.float64], n: int) -> None:
        for i in range(n):
            x[i] = 1.0


    def mixed(l: list[float], x: npt.NDArray[np.float64]) -> None:
        for i in range(len(x)):
            x[i] = l[i]


    def three(a: npt.NDArray[np.float64], b: npt.NDArray[np.float64], c: npt.NDArray[np.float64],
              d: array.array[float]) -> None:
        for i in range(len(a)):
            a[i] = b[i] + c[i] + d[i]


    def half_nf(x: npt.NDArray[np.float64]) -> float:
        return x[0] / 2.0


    def div_ad(a: array.array[float]) -> float:
        return a[0] / a[1]


    def div_nf(x: npt.NDArray[np.float64]) -> float:
        return x[0] / x[1]


    def open_nf(x: npt.NDArray[np.float64], o: object) -> float:
        return x[0] + float(o.v)


    def rebind_view(x: npt.NDArray[np.float64]) -> None:
        y = x[:]
        y[0] = 5.0
''')

COMPILED = {
    "sum_ad", "sum_aq", "sum_nf", "sum_ni", "get_ad", "get_aq", "get_nf", "get_ni", "set_ad",
    "set_aq", "set_nf", "set_ni", "length_nf", "length_ad", "scale_nf", "scale_ad", "iota_ni",
    "iota_aq", "copy_nf", "copy_ad", "cascade_nf", "fill_n", "mixed", "three", "half_nf", "div_ad",
}
SKIPPED = {"div_nf", "open_nf", "rebind_view"}


def lower_verify(path):
    lowered = frontend.lower(path)
    return verify.verify(lowered)


def build_source(d, source, name="bufmod"):
    path = d / f"{name}.py"
    path.write_text(source)
    proved, diags = lower_verify(path)
    c = cgen.generate(proved, path, path.name)
    return path, proved, diags, c


def load_so(so):
    loader = importlib.machinery.ExtensionFileLoader(so.stem.split(".")[0], str(so))
    spec = importlib.util.spec_from_file_location(so.stem.split(".")[0], str(so), loader=loader)
    module = importlib.util.module_from_spec(spec)
    loader.exec_module(module)
    return module


@dataclasses.dataclass
class Built:
    proved: object
    diags: list
    c: str
    module: object


@pytest.fixture(scope="module")
def built(tmp_path_factory):
    d = tmp_path_factory.mktemp("bufmod")
    path, proved, diags, c = build_source(d, SOURCE)
    so = cbuild.build(c, path.stem, d / "out")
    return Built(proved, diags, c, load_so(so))


def assert_compiled(built, *names):
    have = {f.name for f in built.proved.functions}
    assert set(names) <= have, (sorted(set(names) - have), built.proved.skipped)


def deopts(m):
    return m.__typedpython_deopts__


def interp(m, name):
    return m.__typedpython_interpreted__[name]


def snap(a):
    return a.tolist() if hasattr(a, "tolist") else list(a)


def outcome(fn, args):
    try:
        return ("ok", fn(*args))
    except Exception as e:  # noqa: BLE001 - the type and text are the observable
        return ("raise", type(e), str(e))


def same_value(a, b):
    if a == b:
        return True
    return a != a and b != b       # nan


def differential(m, name, make, expect_deopt=False):
    """Run the compiled and the interpreted function on two fresh copies of the arguments; assert
    equal result (or equal exception type and text) and equal final contents of every argument."""
    ca, ia = make(), make()
    before = deopts(m)
    c = outcome(getattr(m, name), ca)
    after = deopts(m)
    i = outcome(interp(m, name), ia)
    assert c[0] == i[0], (c, i)
    if c[0] == "ok":
        assert same_value(c[1], i[1]), (c, i)
    else:
        assert c[1:] == i[1:], (c, i)
    for x, y in zip(ca, ia):
        if hasattr(x, "tolist") or isinstance(x, (array.array, list)):
            assert snap(x) == snap(y) or all(same_value(p, q) for p, q in zip(snap(x), snap(y))), (x, y)
    if expect_deopt:
        assert after == before + 1, "expected a deopt (an object-path call), got none"
    else:
        assert after == before, "unexpected deopt: the compiled path did not run"
    return c


F = [1.5, -2.25, 3.0, 4.5]
I = [3, -5, 7, 11]


def AD(v=F): return array.array("d", v)
def AQ(v=I): return array.array("q", v)
def NF(v=F): return np.array(v, dtype=np.float64)
def NI(v=I): return np.array(v, dtype=np.int64)


# --- what is compiled, and why the rest is not --------------------------------------------------

def test_compiled_set_and_skip_reasons(built):
    names = {f.name for f in built.proved.functions}
    assert names >= COMPILED, sorted(COMPILED - names)
    for n in SKIPPED:
        assert n in built.proved.skipped, n
    assert "verify/array-open" in built.proved.skipped["open_nf"]
    assert "numpy" in built.proved.skipped["div_nf"]
    assert "view" in built.proved.skipped["rebind_view"]


def test_ir_shape_marks_buffer_params(built):
    by = {f.name: f for f in built.proved.functions}
    p = by["sum_nf"].params[0]
    assert (p.buffer, p.stored) == ("numpy", False)
    assert by["sum_ad"].params[0].buffer == "array"
    assert by["scale_nf"].params[0].stored is True
    assert [q.buffer for q in by["mixed"].params if hasattr(q, "buffer")] == [None, "numpy"]


def test_no_limit_on_buffer_parameters(built):
    by = {f.name: f for f in built.proved.functions}
    assert sum(getattr(p, "buffer", None) is not None for p in by["three"].params) == 4
    assert "tp_cs_begin" not in wrapper_text(built.c, "three")
    n = 4
    x = [np.zeros(n) for _ in range(3)]
    d = array.array("d", [1.0] * n)
    x[1][:] = 2.0
    x[2][:] = 3.0
    built.module.three(x[0], x[1], x[2], d)
    assert x[0].tolist() == [6.0] * n


def wrapper_text(c, name):
    start = c.index(f"static PyObject *tp_wrap_{name}(")
    return c[start:c.index("\n}\n", start)]


# --- reads, writes, len ---------------------------------------------------------------------------

@pytest.mark.parametrize("name,make", [
    ("sum_ad", lambda: (AD(),)), ("sum_aq", lambda: (AQ(),)),
    ("sum_nf", lambda: (NF(),)), ("sum_ni", lambda: (NI(),)),
    ("sum_ad", lambda: (AD([]),)), ("sum_nf", lambda: (NF([]),)),
    ("sum_aq", lambda: (array.array("l", I),)),
    ("sum_ni", lambda: (np.array(I, dtype=np.longlong),)),
    ("length_nf", lambda: (NF(),)), ("length_ad", lambda: (AD([]),)),
])
def test_reads_and_len(built, name, make):
    differential(built.module, name, make)


@pytest.mark.parametrize("name,make", [
    ("get_ad", lambda: (AD(), 2)), ("get_ad", lambda: (AD(), -1)), ("get_ad", lambda: (AD(), -4)),
    ("get_aq", lambda: (AQ(), 3)), ("get_aq", lambda: (AQ(), -4)),
    ("get_nf", lambda: (NF(), 0)), ("get_nf", lambda: (NF(), -2)),
    ("get_ni", lambda: (NI(), 1)), ("get_ni", lambda: (NI(), -1)),
])
def test_index_and_negative_index(built, name, make):
    differential(built.module, name, make)


@pytest.mark.parametrize("name,make", [
    ("get_ad", lambda: (AD(), 4)), ("get_ad", lambda: (AD(), -5)), ("get_ad", lambda: (AD([]), 0)),
    ("get_aq", lambda: (AQ(), 99)), ("get_nf", lambda: (NF(), 4)), ("get_nf", lambda: (NF(), -5)),
    ("get_ni", lambda: (NI(), 7)),
    ("set_ad", lambda: (AD(), 4, 1.0)), ("set_aq", lambda: (AQ(), -5, 1)),
    ("set_nf", lambda: (NF(), 4, 1.0)), ("set_ni", lambda: (NI(), -5, 1)),
])
def test_index_errors_match_cpython_type_and_text(built, name, make):
    c = differential(built.module, name, make)
    assert c[0] == "raise" and c[1] is IndexError


@pytest.mark.parametrize("name,make", [
    ("set_ad", lambda: (AD(), 1, 9.5)), ("set_ad", lambda: (AD(), -1, 9.5)),
    ("set_aq", lambda: (AQ(), 0, -77)), ("set_nf", lambda: (NF(), 3, 0.125)),
    ("set_nf", lambda: (NF(), -4, 0.125)), ("set_ni", lambda: (NI(), 2, 2 ** 40)),
    ("scale_ad", lambda: (AD(), 2.0)), ("scale_nf", lambda: (NF(), -0.5)),
    ("iota_ni", lambda: (NI(),)), ("iota_aq", lambda: (AQ(),)),
    ("scale_nf", lambda: (NF([]), 2.0)),
    ("copy_nf", lambda: (NF(), NF([9.0, 8.0, 7.0, 6.0]))),
    ("copy_ad", lambda: (AD(), AD([9.0, 8.0, 7.0, 6.0]))),
])
def test_writes_are_visible_to_the_caller(built, name, make):
    differential(built.module, name, make)


def test_a_write_really_lands_in_the_callers_memory(built):
    assert_compiled(built, "set_nf", "scale_ad")
    x = NF()
    built.module.set_nf(x, 1, 42.0)
    assert x[1] == 42.0
    a = AD()
    built.module.scale_ad(a, 2.0)
    assert a.tolist() == [3.0, -4.5, 6.0, 9.0]


def test_scalar_results_are_python_numbers(built):
    # documented difference (API.md "Buffer arrays"): the compiled function returns a Python float
    # or int where the interpreted one returns np.float64 / np.int64.
    assert type(built.module.get_nf(NF(), 0)) is float
    assert type(built.module.get_ni(NI(), 0)) is int


# --- exceptions mid-loop --------------------------------------------------------------------------

def test_exception_mid_loop_keeps_earlier_writes(built):
    differential(built.module, "fill_n", lambda: (NF([0.0] * 3), 4))
    differential(built.module, "fill_n", lambda: (NF([0.0] * 5), 4))
    x = NF([0.0] * 3)
    with pytest.raises(IndexError):
        built.module.fill_n(x, 4)
    assert x.tolist() == [1.0, 1.0, 1.0]


def test_division_semantics_that_numpy_changes(built):
    # array.array elements are Python floats: ZeroDivisionError as interpreted
    differential(built.module, "div_ad", lambda: (AD([1.0, 0.0]),))
    differential(built.module, "div_ad", lambda: (AD([1.0, 4.0]),))
    # division by a nonzero constant is the same for np.float64
    differential(built.module, "half_nf", lambda: (NF([3.0]),))


# --- aliasing -------------------------------------------------------------------------------------

def test_two_views_of_one_numpy_array_see_each_others_writes(built):
    def make():
        base = np.zeros(8)
        return (base[1:], base[:-1], base)

    ca, ia = make(), make()
    before = deopts(built.module)
    built.module.cascade_nf(ca[0], ca[1])
    interp(built.module, "cascade_nf")(ia[0], ia[1])
    assert deopts(built.module) == before
    # a read sees the earlier write through the other view: base[k] = k (a copy-in would give
    # [0, 1, 1, 1, ...])
    assert ca[2].tolist() == ia[2].tolist() == [float(k) for k in range(8)]


def test_the_same_array_passed_twice(built):
    assert_compiled(built, "copy_nf", "copy_ad")
    x = NF()
    before = deopts(built.module)
    built.module.copy_nf(x, x)
    assert deopts(built.module) == before
    assert x.tolist() == F
    a = AD()
    before = deopts(built.module)
    built.module.copy_ad(a, a)
    assert deopts(built.module) == before
    assert a.tolist() == F


def test_buffer_and_list_parameters_together(built):
    l = [10.0, 20.0, 30.0, 40.0]
    x = NF([0.0] * 4)
    before = deopts(built.module)
    built.module.mixed(l, x)
    assert deopts(built.module) == before
    assert x.tolist() == l and l == [10.0, 20.0, 30.0, 40.0]
    # an array in place of the list is a deopt (guard), not an alias
    differential(built.module, "mixed", lambda: (AD([1.0] * 4), NF([0.0] * 4)), expect_deopt=True)


# --- guards: anything else takes the object path --------------------------------------------------

class SubArray(array.array):
    pass


class SubNd(np.ndarray):
    pass


def misaligned():
    raw = bytearray(8 * 5 + 1)
    return np.frombuffer(raw, dtype=np.float64, count=5, offset=1)


def readonly(v):
    x = NF(v)
    x.flags.writeable = False
    return x


@pytest.mark.parametrize("name,make", [
    ("sum_nf", lambda: (np.array(F, dtype=np.float32),)),
    ("sum_nf", lambda: (NF([1.0] * 8)[::2],)),
    ("length_nf", lambda: (np.zeros((2, 2)),)),
    ("sum_nf", lambda: (np.array(F, dtype=">f8"),)),
    ("sum_nf", lambda: (np.array(I, dtype=np.int64),)),
    ("sum_nf", lambda: (misaligned(),)),
    ("sum_nf", lambda: (NF(F).view(SubNd),)),
    ("sum_nf", lambda: (list(F),)),
    ("sum_nf", lambda: (AD(),)),
    ("sum_ni", lambda: (np.array(F, dtype=np.float64),)),
    ("sum_ni", lambda: (np.array([3, 5, 7], dtype=np.uint64),)),
    ("sum_ni", lambda: (np.array(I, dtype=np.int32),)),
    ("sum_aq", lambda: (array.array("Q", [1, 2]),)),
    ("sum_aq", lambda: (array.array("i", [1, 2]),)),
    ("sum_ad", lambda: (array.array("f", [1.0, 2.0]),)),
    ("sum_ad", lambda: (SubArray("d", F),)),
    ("sum_ad", lambda: (NF(),)),
    ("sum_ad", lambda: (None,)),
])
def test_wrong_kind_takes_the_object_path_and_is_still_correct(built, name, make):
    differential(built.module, name, make, expect_deopt=True)


def test_int64_overflow_in_a_numpy_sum_deopts_and_wraps_as_numpy(built):
    import warnings
    big = lambda: (np.array([2 ** 62, 2 ** 62, 2 ** 62], dtype=np.int64),)   # noqa: E731
    with warnings.catch_warnings():
        warnings.simplefilter("ignore")
        c = differential(built.module, "sum_ni", big, expect_deopt=True)
    assert c[0] == "ok" and c[1] == -(2 ** 62)          # wrapped, as the interpreter does


def test_readonly_buffer(built):
    # a reader works on a read-only numpy array without a deopt
    differential(built.module, "sum_nf", lambda: (readonly(F),))
    # a storing function deopts (no writable export), and the interpreted function raises
    c = differential(built.module, "scale_nf", lambda: (readonly(F), 2.0), expect_deopt=True)
    assert c[0] == "raise" and c[1] is ValueError


def test_wrong_arity_and_keywords_still_reach_cpython(built):
    assert_compiled(built, "sum_nf")
    with pytest.raises(TypeError):
        built.module.sum_nf()
    with pytest.raises(TypeError):
        built.module.sum_nf(NF(), NF())
    assert built.module.sum_nf(x=NF()) == sum(F)


# --- the export is released on every exit ----------------------------------------------------------

def can_resize(a):
    try:
        a.append(0.0)
    except BufferError:
        return False
    return True


def test_export_released_after_ok_error_and_deopt(built):
    m = built.module
    a = AD()
    m.sum_ad(a)
    assert can_resize(a)                                 # ok exit
    a = AD()
    with pytest.raises(IndexError):
        m.get_ad(a, 99)
    assert can_resize(a)                                 # error exit, entry through the impl
    a = AD()
    with pytest.raises(IndexError):
        m.set_ad(a, 99, 1.0)
    assert can_resize(a)
    q = array.array("q", [2 ** 62, 2 ** 62, 2 ** 62])
    before = deopts(m)
    assert m.sum_aq(q) == 3 * 2 ** 62                    # overflow of i64: deopt after entry
    assert deopts(m) == before + 1
    q.append(1)                                          # released before the interpreted redo
    q = array.array("q", [1, 2])
    d = array.array("d", [1.0])
    before = deopts(m)
    m.sum_aq(d)                                          # export taken, then the format check fails
    assert deopts(m) == before + 1
    assert can_resize(d)
    s = SubArray("d", F)
    m.sum_ad(s)                                          # type guard fails before any export
    assert can_resize(s)
    dst, src = AD(), AD()
    with pytest.raises(IndexError):
        m.copy_ad(dst, AD([1.0]))                        # the second export is held at the error
    assert can_resize(dst)
    dst, src = AD(), AD()
    m.copy_ad(dst, src)
    assert can_resize(dst) and can_resize(src)


def test_numpy_export_is_released(built):
    assert_compiled(built, "sum_nf", "get_nf", "sum_ni")
    m = built.module
    x = NF()
    base = sys.getrefcount(x)
    m.sum_nf(x)
    assert sys.getrefcount(x) == base
    with pytest.raises(IndexError):
        m.get_nf(x, 99)
    assert sys.getrefcount(x) == base
    m.sum_ni(x)                                          # dtype mismatch: export taken then dropped
    assert sys.getrefcount(x) == base


def exports_leak_after_error(out, c):
    so = cbuild.build(c, "bufmod", out)
    mod = load_so(so)
    a = AD()
    with pytest.raises(IndexError):
        mod.get_ad(a, 99)
    return not can_resize(a)


def test_mutation_dropping_release_on_the_error_path_is_caught(built, tmp_path):
    # the unmutated module releases (the assertion of the previous test); a build whose error path
    # does not call tp_buf_exit leaks the export, and the same observation shows it
    start = built.c.index("tp_error:", built.c.index("static PyObject *tp_wrap_get_ad("))
    end = built.c.index("tp_deopt:", start)
    mutated = built.c[:start] + built.c[start:end].replace("tp_buf_exit(", "tp_buf_leak(") \
        + built.c[end:]
    assert mutated != built.c
    mutated = mutated.replace(
        "static const char tp_source[]",
        "static inline void tp_buf_leak(tp_buf *b) { (void)b; }\nstatic const char tp_source[]", 1)
    assert exports_leak_after_error(tmp_path / "mutated", mutated)
    assert not exports_leak_after_error(tmp_path / "clean", built.c)


def test_plain_switch_builds_and_agrees(built, tmp_path):
    # -DTP_BUFFER_PLAIN: plain loads and stores instead of relaxed atomics (measurement only)
    assert "TP_BUFFER_PLAIN" in (cbuild.RUNTIME_DIR / "tp_runtime.h").read_text()
    so = cbuild.build(built.c, "bufmod", tmp_path / "plain",
                      flags=list(cbuild.DEFAULT_FLAGS) + ["-DTP_BUFFER_PLAIN"])
    m = load_so(so)
    x = NF()
    m.scale_nf(x, 2.0)
    assert x.tolist() == [v * 2 for v in F]
    assert m.sum_ni(NI()) == sum(I)


def test_atomic_access_is_the_default(built):
    text = (cbuild.RUNTIME_DIR / "tp_runtime.h").read_text()
    assert "#ifdef TP_BUFFER_PLAIN" in text
    plain, _, atomic = text.partition("#ifdef TP_BUFFER_PLAIN")[2].partition("#else")
    assert "__atomic_load_n" in atomic.split("#endif")[0] and "__atomic" not in plain
    # the generated code reaches the elements only through the helpers
    start = built.c.index("static int tp_impl_scale_nf(", built.c.index("static const char tp_source"))
    while not built.c[built.c.index(")", start) + 1:].startswith("\n{"):
        start = built.c.index("static int tp_impl_scale_nf(", start + 1)
    body = built.c[start:built.c.index("\n}\n", start)]
    assert "tp_buf_ld_f64(" in body and "tp_buf_st_f64(" in body and "->data[" not in body


# --- closedness (#203) ----------------------------------------------------------------------------

def test_function_that_would_run_user_code_stays_interpreted(built):
    assert_compiled(built, "sum_nf")
    class O:
        v = 1.0
    x = NF([2.0])
    assert built.module.open_nf(x, O()) == 3.0
    assert "open_nf" not in {f.name for f in built.proved.functions}


# --- a numpy nbody-like kernel ---------------------------------------------------------------------

NBODY = textwrap.dedent('''
    # typedpython: compiled
    import math
    import numpy as np
    import numpy.typing as npt

    SOLAR_MASS: float = 39.47841760435743


    def advance_nodiv(dt: float, steps: int, x: npt.NDArray[np.float64], y: npt.NDArray[np.float64],
                      z: npt.NDArray[np.float64], vx: npt.NDArray[np.float64],
                      vy: npt.NDArray[np.float64], vz: npt.NDArray[np.float64],
                      mass: npt.NDArray[np.float64]) -> None:
        for _ in range(steps):
            for i in range(5):
                for j in range(i + 1, 5):
                    dx: float = x[i] - x[j]
                    dy: float = y[i] - y[j]
                    dz: float = z[i] - z[j]
                    d2: float = dx * dx + dy * dy + dz * dz
                    mag: float = dt * math.sqrt(d2)
                    vx[i] -= dx * mass[j] * mag
                    vy[i] -= dy * mass[j] * mag
                    vz[i] -= dz * mass[j] * mag
                    vx[j] += dx * mass[i] * mag
                    vy[j] += dy * mass[i] * mag
                    vz[j] += dz * mass[i] * mag
            for i in range(5):
                x[i] += dt * vx[i]
                y[i] += dt * vy[i]
                z[i] += dt * vz[i]


    def advance(dt: float, steps: int, x: npt.NDArray[np.float64], y: npt.NDArray[np.float64],
                z: npt.NDArray[np.float64], vx: npt.NDArray[np.float64],
                vy: npt.NDArray[np.float64], vz: npt.NDArray[np.float64],
                mass: npt.NDArray[np.float64]) -> None:
        for _ in range(steps):
            for i in range(5):
                for j in range(i + 1, 5):
                    dx: float = x[i] - x[j]
                    dy: float = y[i] - y[j]
                    dz: float = z[i] - z[j]
                    d2: float = dx * dx + dy * dy + dz * dz
                    mag: float = dt / (d2 * math.sqrt(d2))
                    vx[i] -= dx * mass[j] * mag


    def energy(x: npt.NDArray[np.float64], y: npt.NDArray[np.float64], z: npt.NDArray[np.float64],
               vx: npt.NDArray[np.float64], vy: npt.NDArray[np.float64],
               vz: npt.NDArray[np.float64], mass: npt.NDArray[np.float64]) -> float:
        e: float = 0.0
        for i in range(5):
            e += 0.5 * mass[i] * (vx[i] * vx[i] + vy[i] * vy[i] + vz[i] * vz[i])
        return e
''')


def test_nbody_like_numpy_kernels(tmp_path):
    path, proved, diags, c = build_source(tmp_path, NBODY, "nbodynp")
    by = {f.name: f for f in proved.functions}
    assert {"advance_nodiv", "energy"} <= set(by), proved.skipped
    nbuf = {n: sum(getattr(p, "buffer", None) is not None for p in f.params) for n, f in by.items()}
    assert nbuf == {"advance_nodiv": 7, "energy": 7}
    # the real nbody divides by a non-constant: numpy would give inf where Python raises
    assert "advance" in proved.skipped and "numpy" in proved.skipped["advance"]
    so = cbuild.build(c, "nbodynp", tmp_path / "out")
    m = load_so(so)

    def bodies():
        rng = np.random.default_rng(1)
        return [rng.random(5) for _ in range(7)]

    ca, ia = bodies(), bodies()
    before = deopts(m)
    m.advance_nodiv(0.01, 20, *ca)
    interp(m, "advance_nodiv")(0.01, 20, *ia)
    assert deopts(m) == before
    for p, q in zip(ca, ia):
        assert p.tolist() == q.tolist()
    assert m.energy(*ca) == interp(m, "energy")(*ia)


# --- verifier rules, on hand-built IR (the verifier never trusts the front end) -------------------

def test_verifier_numpy_division_rule():
    from test_verify import F64 as VF64, F64A as VF64A, assert_accepted, assert_rejected, fn, lf
    from typedpython.ir import ArrayParam, BinOp, BinOpKind, Const, Index, Param, Return

    def divide(buffer, divisor, op=BinOpKind.TRUEDIV):
        return fn("f", params=[ArrayParam("a", VF64A, False, buffer), Param("d", VF64)], returns=VF64,
                  pure=True, body=[Return(BinOp(VF64, op, Index(VF64, "a", Const(Type_I64, 0)),
                                                divisor))])

    from typedpython.ir import Type
    Type_I64 = Type.I64
    assert_rejected("verify/buffer", divide("numpy", lf("d")))                       # variable divisor
    assert_rejected("verify/buffer", divide("numpy", Const(VF64, 0.0)))              # zero
    assert_rejected("verify/buffer", divide("numpy", Const(VF64, 2.0), BinOpKind.FLOORDIV))
    assert_rejected("verify/buffer", divide("numpy", Const(VF64, 2.0), BinOpKind.MOD))
    assert_accepted(divide("numpy", Const(VF64, 2.0)))                               # nonzero Const
    assert_accepted(divide("array", lf("d")))        # array.array elements are Python floats
    assert_accepted(divide(None, lf("d")))           # a list too


def test_verifier_buffer_kind_and_copy_rules():
    from test_verify import F64 as VF64, F64A as VF64A, assert_accepted, assert_rejected, fn
    from typedpython.ir import ArrayParam, Assign, CopyArray, Index, Return, Const, Type

    assert_rejected("verify/buffer", fn("f", params=[ArrayParam("a", VF64A, False, "memoryview")],
                                         returns=VF64, pure=True,
                                         body=[Return(Index(VF64, "a", Const(Type.I64, 0)))]))
    copy = fn("f", params=[ArrayParam("a", VF64A, False, "numpy")], returns=VF64, pure=True,
              locals_={"b": VF64A},
              body=[Assign("b", CopyArray(VF64A, "a")), Return(Index(VF64, "b", Const(Type.I64, 0)))])
    assert_rejected("verify/buffer", copy)


def test_verifier_buffer_params_are_not_limited_but_lists_still_are():
    from test_verify import F64 as VF64, F64A as VF64A, assert_accepted, assert_rejected, fn
    from typedpython.ir import ArrayParam, Index, Return, Const, Type

    def many(n, buffer):
        return fn("f", params=[ArrayParam(f"a{k}", VF64A, False, buffer) for k in range(n)],
                  returns=VF64, pure=True,
                  body=[Return(Index(VF64, "a0", Const(Type.I64, 0)))])

    assert_accepted(many(5, "array"))
    assert_accepted(many(3, "numpy"))
    assert_rejected("verify/structure", many(3, None))
