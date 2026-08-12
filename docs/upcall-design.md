# 업콜 설계 — Python → Kotlin

Python 코드가 Kotlin 함수·클래스를 가져다 실행하고, 인스턴스화하고, 상속하는 방향. 반대 방향은
[`downcall-design.md`](downcall-design.md).

무엇을 노출할지는 [`binding-policy.md`](binding-policy.md).

**현재 미구현이다.** 이 문서는 확정된 설계와, 아직 열려 있는 지점을 구분해 기록한다.

## 런타임 리플렉션을 쓰지 않는다

이유가 두 겹이고, 두 번째가 더 근본적이다.

1. **GraalVM Native Image** 는 closed-world 라 런타임 리플렉션이 등록 없이는 동작하지 않는다.
2. **Kotlin/Native 에는 리플렉션이 사실상 없다.** `::class.simpleName` 정도이고 멤버 열거가 안 된다.
   iOS 와 androidNative 는 GraalVM 과 무관하게 애초에 리플렉션 경로가 없다.

멀티플랫폼 라이브러리인 이상 **빌드 타임에 생성한 테이블 말고는 선택지가 없다.**

## 구조

```
Python 사용자 코드        obj.baz(1, 2)
        ↓
프록시 타입              우리가 PyType_FromSpec 으로 만든 힙 타입.
                        인스턴스 데이터에 핸들을 담고 있다.
        ↓
업콜 진입점 (플랫폼당 1개)  invoke(handle, args)
        ↓
Kotlin 함수 테이블        핸들 → 이미 해석된 호출 대상
```

Python 사용자 코드에는 문자열도 정수도 드러나지 않는다.

## 핸들 해석은 Kotlin 이, 캐시는 프록시 객체가

ObjC 가 빠른 이유는 테이블이 있어서가 아니라, **셀렉터가 인터닝된 포인터라 매번 문자열을 찾지 않기
때문**이다. `objc_msgSend` 는 클래스 메서드 캐시에서 몇 개 명령어로 IMP 를 찾아 점프한다.

같은 성질을 이렇게 얻는다:

| 시점 | 하는 일 |
|---|---|
| 최초 1회 | Python 이 이름(`"Bar.baz"`)을 넘기고, **Kotlin 이 테이블에서 해석**해 핸들을 돌려준다 |
| 이후 매 호출 | 프록시 객체가 자기 인스턴스 데이터에 담아둔 핸들을 그대로 넘긴다 |

핵심은 **호출마다 문자열을 넘기면 어떤 테이블 설계로도 ObjC 수준에 도달할 수 없다**는 것이다:

| 방식 | 호출당 비용 |
|---|---|
| 매번 문자열 전달 | `PyUnicode` → UTF-8 변환(할당) + FFI 경계 + 해시 + 문자열 비교 → 수백 ns |
| 최초만 해석, 이후 핸들 | FFI 경계 + 테이블 조회 → 10~20ns 목표 |

테이블 항목은 `KFunction.call` 이 아니어야 한다. `KFunction.call` 은 호출마다 인자 개수와 타입을
다시 검사하고 박싱해 50~100ns 가 든다. JVM 에서는 `MethodHandle`, Kotlin/Native 에서는 생성된 함수
참조를 쓴다. 후자는 컴파일 타임에 직접 호출로 낮아진다.

## 왜 트램폴린이 적어도 되는가

CPython 콜백 규약은 **전부 관련 객체를 인자로 넘긴다.** 헤더에서 확인했다:

| 슬롯 | 시그니처 | 식별 정보 |
|---|---|---|
| `PyCFunction` | `(PyObject *self, PyObject *args)` | `self` |
| `destructor` | `(PyObject *self)` | `self` |
| `getter` / `setter` | `(PyObject *self, void *closure)` | **명시적 closure 인자** |
| `initproc` | `(PyObject *self, args, kwargs)` | `self` |
| `newfunc` | `(PyTypeObject *type, args, kwargs)` | `type` |

즉 **라우팅을 함수 포인터가 아니라 데이터로 한다.** 소수의 공유 트램폴린이 무제한의 Kotlin 대상을
서비스할 수 있고, **런타임 코드 생성(LLVM JIT, libffi 클로저)이 필요 없다.** JPype·PyO3·JEP 가 전부
이 방식이다.

업콜 shape 는 4종으로 수렴하며, 그 4종은 다운콜의 14종에 **이미 포함**된다. 업콜 때문에 추가되는
트램폴린이 없다.

런타임 코드 생성이 실제로 필요해지는 경우는 하나뿐이다 — **컨텍스트 인자가 전혀 없는 순수 C 콜백**
(`qsort` 비교자 등)에 Kotlin 함수 포인터를 넘겨야 할 때. CPython 객체 모델에는 해당이 없다.

## 상속과 인스턴스화

`PyType_FromSpec` 으로 힙 타입을 만들면:

- Python 이 그 타입을 상속하면 `tp_new`/`tp_init` 은 우리 공유 트램폴린을 그대로 물려받는다
- 오버라이드한 메서드는 그냥 Python 함수다
- Kotlin 이 가상 메서드를 부를 때는 `PyObject_CallMethod` 가 일반 MRO 조회를 한다

**새 네이티브 스텁이 하나도 추가되지 않는다.**

## 플랫폼별 진입점

진입점 뒤는 전부 같은 테이블이다.

| 플랫폼 | 진입 방법 |
|---|---|
| Desktop | FFM `Linker.upcallStub` |
| Android | `PyMethodDef` whose `ml_meth` is a C shim in `jni_onload.def`, calling Kotlin/JVM through JNI |
| iOS / androidNative | `PyMethodDef` + `PyCMethod_New` (`@CName` 심볼은 androidNative 에서만 유효) |

원래 여기에는 "iOS 는 Python 과 Kotlin/Native 가 같은 바이너리이므로 `ctypes.CDLL(None)` 으로 `@CName`
심볼에 바로 닿는다"고 적혀 있었다. **측정해 보니 iOS 에서는 양쪽 절반이 모두 성립하지 않는다.**
[아래 표](#what-each-platform-still-owes)에 측정값이 있다. 실제 경로는
`nativeMain` 의 `python.native.ffi.UpcallEntry` 이며, C 글루가 필요 없다는 결론 자체는 유지된다 —
`PyMethodDef` 를 Kotlin/Native 가 직접 채우기 때문이다.

---

## The trampoline that carries arguments

Written for desktop, settled for everyone. `python.multiplatform.ffi.upcall.UpcallTrampoline` is
`commonMain` and depends on nothing platform-specific; what each platform still owes is only the
few lines that give Python the *address* of an entry point.

Before this, the table carried arity and a per-argument `TypeTag` and nothing read them. Both
desktop stubs were `(long) -> long`, so the only entries Python could reach were zero-argument ones
returning a `Long` — Python could call Kotlin but could not pass it anything (ROADMAP §13).

### The shape count is one, and it was already implied

Counted the way the downcall side was counted, and it converges harder: **argument passing needs
exactly one new C shape.**

    long pm_invoke(long callableHandle, PyObject *args) -> PyObject *      // (long, long) -> long

Arity and types travel inside the tuple and inside the table entry, never in the C signature. A
stub specialised per signature would need one per `(arity, tag-vector)` — unbounded, and
impossible to pre-generate for a closed world. This is the same argument as
[왜 트램폴린이 적어도 되는가](#왜-트램폴린이-적어도-되는가), applied one level down.

The remaining CPython slots were already in the vocabulary, which is why the total is one and not
five:

| Slot | C signature | Carrier shape | Status |
|---|---|---|---|
| `PyCFunction` | `PyObject *(PyObject *self, PyObject *args)` | `(long, long) -> long` | **added** |
| `getter` | `PyObject *(PyObject *self, void *closure)` | `(long, long) -> long` | same one |
| `initproc` / `setter` / `traverse` | `int(long, long, long)` | `(long, long, long) -> int` | already there |
| `inquiry` (`tp_clear`) | `int(PyObject *self)` | `(long) -> int` | already there |
| `destructor` | `void(PyObject *self)` | `(long) -> void` | already there |
| name → handle | `long(const char *)` | `(long) -> long` | already there |

`newfunc` (`PyObject *(type, args, kwargs)`) is the only one still unaccounted for, and only if
`tp_new` is ever bound directly rather than reached through `PyCFunction`.

The `PyCFunction` correspondence is deliberate: when the generated proxy type lands, `self` takes
the callable handle's place in the same stub and **no new shape appears then either**.

### What crosses, per tag

The `args: Array<Any?>` an `ExposedCallable` receives has exactly one representation per `TypeTag`,
which is what `python-multiplatform-ksp`'s `TypeShape.castExpression` already narrows from:

| `TypeTag` | Python side | Kotlin side |
|---|---|---|
| `INT` | `int` | `Long` (a declared `Int`/`Short`/`Byte` is narrowed by generated code) |
| `FLOAT` | `float` | `Double` |
| `BOOLEAN` | `bool` | `Boolean` — a distinct Python type, so not folded into `INT` |
| `STRING` | `str` | `String` |
| `BYTES` | `bytes` | `ByteArray` |
| `UNIT` | `None` | `Unit` |
| `OBJECT` | `int` handle, **or** any Python object | the Kotlin instance from `HandleTable`, **or** a `PyObject` |
| — | `None` | `null`, whatever the tag |

`OBJECT` is the one tag that is two things, and it is resolved by what Python actually sent rather
than by the tag: Python cannot hold a Kotlin reference on any target, so a Kotlin object crosses as
an `ObjectReference` integer; anything else is a Python object and reaches a parameter declared as
`PyObject`.

`BYTES` is correct and slow — an item at a time through `PyObject_GetItem`, because
`PyBytes_AsString` is bound here as a NUL-terminated UTF-8 *string* read and destroys exactly the
payloads `ByteArray` exists for. `PyBytes_AsStringAndSize` is in no platform's `EmbedAPI` yet; when
it is, both directions collapse to one call.

### Two conventions that are not negotiable

- **Arguments are borrowed.** `PyTuple_GetItem` lends, so a `PyObject` built over one takes
  `borrowed = true`. `borrowed = false` gives back a reference nobody ever took — one per call —
  and the free lands somewhere unrelated. That is the bug that crashed this repo twice.
- **The result is a new reference.** Python takes ownership. Measured end to end through each
  platform's real entry point (`UpcallEntryTest.theReferenceReturnedToPythonIsTakenOverExactlyOnce`,
  `commonTest`): a hundred calls move the returned object's refcount by zero.

And one that is not about references: **nothing may be thrown out of a trampoline.** The return
path is C; a Kotlin exception crossing a Panama upcall stub terminates the VM and on Kotlin/Native
terminates the process. Every failure leaves as `NULL` with the error indicator set.

### The GIL is not the caller's to promise

`withGIL` skips `PyGILState_Ensure` when the thread's nesting depth is already non-zero. That is
right for Kotlin-side code and **wrong for an entry point from C**, because C may have dropped the
GIL inside a scope that is still open. `ctypes.CFUNCTYPE` does precisely that — it releases the GIL
around the foreign call, unlike `PYFUNCTYPE` — so the first version of this trampoline segfaulted
in `_PyThreadState_GET` (`PyErr_Occurred+0x1c`) on a thread whose own counter said it held the GIL.

A trampoline therefore takes its own `PyGILState_Ensure`/`Release` pair unconditionally. Every
platform's entry point needs this, not just desktop's.

### What each platform still owes

Only the address-publishing step; the marshalling is shared.

| Platform | What is needed | Cost |
|---|---|---|
| **Desktop** | done — `Panama.createUpcallStubII_L`, `UpcallStub.invokeWithArgsStubAddr` | — |
| **iOS / androidNative** | done — `python.native.ffi.UpcallEntry` (`nativeMain`), a `PyMethodDef` whose `ml_meth` is a `staticCFunction` over `UpcallTrampoline.invoke` and whose `self` carries the handle. `UpcallEntryTest` (`nativeTest`) runs it on the iOS simulator and, since `androidNativeArm64Test` exists, on `pmp_api26` and `pmp_api36` as well | cheapest of the three |
| **Android** | done — `python.native.ffi.UpcallEntry` / `UpcallCallbacks` (`androidMain`) plus the `pmp_upcall_*` shims in `artMain/cinterop/jni_onload.def`. `UpcallEntryTest` (`androidInstrumentedTest`) runs it on `pmp_api26` and `pmp_api36` | one shim per shape, plus a JNI upcall per call |
| **wasm** | `@WasmExport` on the entry point plus `Table.set` to publish it, measured at 3.1 ns/call in `wasm-experiment` | already proven |

Nothing in that list touches `UpcallTrampoline`; each is the platform's existing upcall mechanism
pointed at it.

The "Cost" column above is per-platform prose, not a comparison. For one measured next to the others
— desktop, iOS, wasmJs and Android in the same units, from the same test — see "One upcall, across
all five platforms" below. In particular the wasm row's **3.1 ns is the bare `call_indirect`
mechanism with no arguments** and is not what an upcall carrying an argument tuple costs.

### Android's boundary runs the other way round, and `RegisterNatives` is not it

The Android row above used to read "a JNI `static jlong` native method registered with
`RegisterNatives`, and a C shim of `PyCFunction` shape that calls it". `RegisterNatives` binds a
JVM `external fun` to a C function — it is the **downcall** direction, and no arrangement of it
lets C call Kotlin. What Python needs here is the opposite: a `PyMethodDef`'s `ml_meth` has to be a
real C function pointer, and Kotlin/JVM on ART can produce none.

So on Android the entry points are C functions in `artMain/cinterop/jni_onload.def`
(`pmp_upcall_invoke_meth` and the `_pm_resolve` / `_pm_bind` / `_pm_release` trio), and *they* call
Kotlin, with `CallStaticLongMethod` against `python/native/ffi/UpcallCallbacks` — a class looked up
once in `JNI_OnLoad` and held as a global ref. `RegisterNatives` still appears, but only for the
one cold `upcallPublish(long)` that installs the bootstrap into a namespace dict.

That is not a new mechanism. It is exactly the inversion the cycle-collecting proxy type already
makes for `tp_traverse`/`tp_clear` (`ProxyCallbacks`), down to reusing `pmp_attach` — so the third
consequence falls out with it: **a Python worker thread is a bare pthread ART has never seen**.
Every `threading.Thread` is one, so `GetEnv` fails and the shim must
`AttachCurrentThreadAsDaemon`. `UpcallEntryTest.anUpcallArrivesOnAThreadCPythonCreatedRatherThanFailingToFindTheJvm`
runs the upcall inside a `threading.Thread` and asserts the thread it landed on is not the
instrumentation thread, so a pass cannot come from `GetEnv` having succeeded after all.

#### The attach is paid once per thread, and the reason it used to be once per call was wrong

This paragraph used to end "and detach again on the way out. Skipping the detach is not an option —
ART aborts the process when a thread it knows about exits without detaching." The first run of
`UpcallOverheadTest` priced that decision, and it was the most expensive thing on the Android upcall
path by an order of magnitude:

| per upcall | API 26 | API 36 |
|---|---|---|
| instrumentation thread (ART already knows it) | 1307–1695 ns | 5307–6693 ns |
| Python worker thread, steady state | 59712–63293 ns | 19820–31219 ns |
| **the attach/detach pair** | **58405–61598 ns** | **14514–24526 ns** |
| upcall / downcall of the same shape, worker | 44.59–47.93x | 11.69–13.23x |

(Two runs per device, in each table below; where they differ the range is given, because a single
emulator reading is not a measurement.)

The justification does not survive contact with ART. `Thread::ThreadExitCallback`
(`runtime/thread.cc`, same shape in `android-8.0.0_r1` and in `main`) logs a warning on its first
invocation and only reaches `LOG(FATAL)` on a second one — and the only thing that arms a second
invocation is a `pthread_setspecific` in an `#else` branch that Android does not compile, because
on Android it restores `__get_tls()[TLS_SLOT_ART_THREAD_SELF]` instead. Bionic clears a key's value
before running its destructor and never re-reads it, so the callback fires at most once and the
abort is unreachable.

Read, then run: `UpcallThreadAttachTest.aThreadThatExitsWithoutDetachingLeaksItsPeerRatherThanAbortingArt`
attaches a bare pthread as a daemon, lets it exit without detaching, and continues — on both
`pmp_api26` and `pmp_api36`, with no `Native thread exiting without having called
DetachCurrentThread` line in logcat at all.

**The detach is still mandatory, for a different reason.** The same test then asks whether the peer
became reclaimable, and it did not: an attachment that is never given back leaves a
`java.lang.Thread` in the thread list as a GC root, with a recorded stack the pthread has already
unmapped. So the detach moved from call exit to *thread death* — a `pthread_key_create` destructor
(`pmp_thread_exit_detach`), which is the case ART's own warning text points at. It defers to its
second invocation rather than detaching on its first, so it cannot run before ART's own exit
callback whatever order bionic visits the keys in.

Re-measured, same test, same devices:

| per upcall | API 26 | API 36 |
|---|---|---|
| instrumentation thread | 1280–1327 ns | 2982–5086 ns |
| Python worker thread, steady state | **1209–1329 ns** | **2301–3086 ns** |
| worker minus instrumentation thread | −118 to +49 ns | −681 to −2000 ns |
| upcall / downcall of the same shape, worker | 44.59–47.93x → **0.99–1.09x** | 11.69–13.23x → **2.00–2.34x** |

The attach is no longer visible in the steady state at all: on both devices the difference between
a Python worker and a thread ART already knows now straddles zero, i.e. it is inside the run-to-run
spread rather than being a cost. On API 26 an upcall from a Python worker is the same price as a
downcall of the same shape.

**The first upcall on each worker still pays the attach in full** — 56–142 µs, larger than the
per-call charge it replaced — and that is the honest shape of the cost now: once per thread, not
once per call. A worker that upcalls once is no better off than before; a worker that upcalls in a
loop is 20–50x better off.

#### What holding the attachments costs, and why it is not a new risk

Keeping a thread attached makes ART's collector responsible for it: the peer is a GC root, the
thread carries a JNI local reference table, and every `SuspendAll` walks the thread list. So "what
if there are hundreds of Python workers?" is a fair question to ask of this change.

It is largely the wrong question, because **peak simultaneous attachments do not change.** Under
the per-call scheme every worker that is upcalling at a given instant is attached at that instant;
what the destructor changes is how long each attachment is *resident*, not how many can exist at
once. The ceiling either way is the number of live Python threads, which the application already
pays for in pthread stacks.

What does change is that an attachment now outlives the call, so the release has to be reliable.
`UpcallThreadAttachTest.manyConcurrentWorkersAreEachAttachedOnceAndAllReleased` holds 32 Python
workers on a `threading.Barrier` so that all 32 are attached simultaneously, checks each one sees a
single ART thread across the barrier and that no two share one, and then requires every peer to
become reclaimable once the workers finish. Green on both devices.

The instrument for that matters as much as the result. It reads GC reachability of the peer, not
`ThreadGroup.enumerate`, because the first version did use `enumerate` and it is **unusable on API
26**: it does not report the peer of an attached native thread even while that thread is running and
upcalling, so every release assertion passed while measuring nothing. A positive control — assert
the peer is *un*reclaimable while its worker is deliberately parked mid-upcall — is what caught it,
and it is kept in the test for that reason.

The GIL rule is unchanged and is what makes this safe: CPython holds the GIL when it calls a
`PyCFunction`, so the C shims take nothing, and `UpcallTrampoline` still takes its own
`PyGILState_Ensure`/`Release` pair unconditionally on the Kotlin side. `PyGILState_Ensure` is bound
as **ordinary JNI** on Android (`PyGILState_EnsureN`), which is what keeps a thread blocked on the
GIL from stalling the JVM collector — a `@FastNative` or `@CriticalNative` binding of it would
deadlock the two runtimes against each other the moment upcalls exist. `androidMain/README.md`
step 3 says this in the abstract; this is the path that makes it concrete.

Cost, which the row's "one shim per shape" understated: one JNI upcall per call, plus an attach the
*first* time Python calls from a thread it created. Both are now measured — see the table above and
`UpcallOverheadTest`. Neither is on the marshalling path.

### One upcall, across all five platforms

Everything above prices Android, because Android had a question the others do not: whether the
attach a Python worker needs is paid once per thread or once per call. That left the upcall path
measured on exactly one target, while the downcall path has been comparable across all of them since
`overhead/BenchmarkTest` — so "is the upcall expensive?" had no answer that could be given per
platform.

`UpcallBoundaryCostTest` (`commonTest`) is `UpcallOverheadTest` with the attach half removed and
nothing else changed: same loop shape, same warmup counts (10 000 iterations after 3 000 of warmup),
the same three baselines measured in the same run (plus the two controls the next heading
introduces), and the same printed-not-asserted policy. It sits
in `commonTest` because every seam it needs is already common — `bindUpcallOrNull` (the per-platform
binding step `UpcallEntryTest` introduced), `UpcallTrampoline`, and the `expect` C API — so one copy
runs on every target.

| Platform | upcall | downcall, same shape | trampoline alone | upcall / downcall | upcall / trampoline |
|---|---|---|---|---|---|
| **desktop** (JVM 21.0.12, macOS arm64) | 861–1313 ns | 315–527 ns | 170–269 ns | 2.49–2.89x | 4.88–5.51x |
| **iOS simulator** (arm64) | 2263–2502 ns | 1599–1826 ns | 1928–2139 ns | 1.33–1.43x | 1.15–1.22x |
| **wasmJs** (Node) | 703–1075 ns | 230–369 ns | 228–271 ns | 2.56–3.36x | 3.04–3.97x |
| **androidNative** (`pmp_api36`, arm64) | 3339–3598 ns | 2170–2222 ns | 2805–3469 ns | 1.52–1.62x | 1.03–1.23x |
| **androidNative** (`pmp_api26`, arm64) | 3314–4140 ns | 2636–5554 ns | 2843–4863 ns | 0.62–1.34x | 0.71–1.20x |
| *Android API 26* †| *1209–1329 ns* | *not recorded* | *not recorded* | *0.99–1.09x* | *not recorded* |
| *Android API 36* †| *2301–3086 ns* | *not recorded* | *not recorded* | *2.00–2.34x* | *not recorded* |

Ranges are min–max over five runs of the whole suite (four for wasmJs); a single reading is not a
measurement. **The rows in italics marked † are quoted, not re-measured** — from commit `409da6fc`
and the two tables above, which is why the columns those did not record are blank rather than
inferred. The two Android rows are two different emulators, not two runs of one, and so are the two
androidNative rows — the same two emulators, in fact, which is why the ART and Kotlin/Native figures
for one API level can be read against each other.

The two italic rows' upcall and ratio columns are both the *Python worker thread, steady state* row,
taken together so the two halves of the ratio belong to the same measurement. That row is the right cross-platform
analogue precisely because of what the section above established: with the attach amortised, the
difference between a Python worker and a thread ART already knows straddles zero (the
instrumentation-thread figures are 1280–1327 ns and 2982–5086 ns), so the worker no longer carries a
cost the other four platforms have no equivalent of. Its ratio is the one that was recorded.

**`pmp_api26`'s androidNative row is noisy and is left noisy.** One of its five runs came in at
5554 ns for the downcall and 4863 ns for the trampoline where the other four sat at 2636–3245 and
2843–3672; that single run is what widens its two ratio columns to 0.62–1.34x and 0.71–1.20x. It is
not dropped, because the min–max convention here exists precisely to show that an emulator's spread
can be larger than the effect being measured. Read `pmp_api36`'s row for the shape and `pmp_api26`'s
for how much confidence an emulator supports.

**The androidNative row was empty because the target had no test *run* task**, only
`androidNativeArm64TestBinaries` — KGP registers an execution task only where it knows how to reach
a host (`KotlinNativeTest` for the build machine, `KotlinNativeSimulatorTest` for simctl), and an
Android device is neither. `commonTest` compiled for the target on every build and had never once
been executed on it.

`:python-multiplatform:androidNativeArm64Test` (see `build.gradle.kts`) is that missing task. It
pushes `test.kexe` to `/data/local/tmp`, pushes the CPython prefix beside it because
`Py_Initialize()` aborts the process rather than failing without a standard library, runs the binary
under the Kotlin/Native runner's TeamCity logger, and parses the service messages back into JUnit XML
under `build/test-results/androidNativeArm64Test/` so this target is counted the same way as every
other. With no serial given it runs on every connected device whose ABI matches, which is where the
two rows above come from. The figures are five runs of the whole 252-test suite on each.

**Nothing new broke when the suite finally ran there** — 252 tests, 0 failures, on both emulators,
in all five runs. That is worth stating because the precedent pointed the other way: ROADMAP §11b
attached this same suite to Android/ART for the first time and it died at the 2nd test and again at
the 12th, surfacing two real defects. androidNative shares `nativeMain` with iOS, and iOS has been
running the suite all along, so the shared code was already exercised; what had never been exercised
was `artMain` and the androidNative `cinterop` bindings, and those came up clean.

#### The trampoline column needed a control before it meant anything

"Upcall minus trampoline is the boundary" is only true if the two differ by the boundary alone, and
in the obvious arrangement they do not. `UpcallTrampoline` takes its own `PyGILState_Ensure`/
`Release` pair **unconditionally** — see "The GIL is not the caller's to promise" — so driving it
from a bare Kotlin thread pays a real GIL acquisition per call, while Python drives it already
holding the GIL. The first run made that concrete: on iOS the trampoline alone came out *more*
expensive than the whole upcall through it, i.e. the boundary priced negative (0.77–0.81x).

So it is measured twice, differing in exactly that one thing, with an empty `Python3.withPython { }`
beside them for scale:

| | desktop | iOS simulator | wasmJs | androidNative `pmp_api36` | androidNative `pmp_api26` |
|---|---|---|---|---|---|
| trampoline, caller holding nothing | 252–507 ns | 2764–3109 ns | 280–307 ns | 3861–4093 ns | 4383–5068 ns |
| trampoline, GIL already held | 170–269 ns | 1928–2139 ns | 228–271 ns | 2805–3469 ns | 2843–4863 ns |
| `Python3.withPython { }`, empty | 124–152 ns | 725–982 ns | 79–81 ns | 937–1010 ns | 1193–1298 ns |

The **GIL-held** row is the one the table above uses, because it is the one the Python-driven
numerator is comparable with.

androidNative is the second target to make the case for this control on its own: without it the
boundary prices negative there too (0.86–0.88x on `pmp_api36`, 0.68–0.86x on `pmp_api26`), for the
same reason as iOS and not because anything about the boundary differs. The gap between its two
trampoline rows — roughly a microsecond — is one uncontended GIL round trip on an emulator, and it
is charged to downcalls exactly as much as to upcalls.

#### Where the differences actually come from

**iOS has the highest absolute upcall of the three originally measured here and the lowest ratio,
and neither is about the boundary.** (androidNative, added later, is higher still on both counts of
absolute cost — see below; the explanation is the same one, which is the point.)
There is no runtime boundary on that target at all — a `PyMethodDef` whose `ml_meth` is a
`staticCFunction` in the same binary. What is expensive is the per-call scaffolding every C API call
shares: an empty `withPython` scope costs 725–982 ns there against 124–152 ns on desktop and 79–81 ns
on wasm, and `Py_IncRef + Py_DecRef` — two calls, two GIL scopes, no marshalling — costs 1551–2032 ns
against 165–326 ns and 166–228 ns. The denominator is inflated by the same thing as the numerator,
which is exactly why the ratio comes out near 1: an iOS upcall is barely more than an iOS downcall,
and both are expensive for a reason that has nothing to do with upcalls. This is where the work is
if the iOS number is to move, not in `UpcallEntry`.

**Desktop is the opposite shape.** Its trampoline with the GIL held is 170–269 ns, and the
Python-driven figure is 861–1313 ns, so 600–1000 ns per call is the Panama upcall stub plus the
`ctypes` shim in front of it — a real boundary, and the largest one measured here in *relative*
terms (4.88–5.51x). One caveat is genuine and is not measured away: on desktop `_pm_bound` is a
Python `lambda *a: _pm_invoke(_pm_h, a)` over a `ctypes.CFUNCTYPE`, where the other targets bind a
`PyCFunction` directly. Desktop therefore pays one extra Python call (26.7–27.2 ns, printed as the
pure-Python callee) plus ctypes' own argument conversion inside every figure in its row. **Desktop's
row is an upper bound on its boundary cost**, not a measurement of the boundary alone.

**wasm's 3.1 ns is not this number, and was never claiming to be.** The "already proven" row in the
platform table quotes `wasm-experiment`'s `call_indirect` figure, which is the *mechanism* with no
arguments. The argument-passing path measured here is 703–1075 ns, of which 228–271 ns is the
trampoline; the remaining 470–800 ns is the Python-side callable plus the crossing with a real
argument tuple. Two orders of magnitude between the two is not a contradiction — they measure
different things — but the 3.1 ns figure must not be quoted for an upcall that carries arguments.

**wasm's scaffolding is the cheapest and by far the most stable of the three** (79.50, 80.15, 80.50,
80.69 ns for an empty scope across four runs), which follows from there being no OS thread machinery
under it.

**androidNative is iOS's shape, moved onto an emulator.** It has the highest absolute upcall
measured here (3.3–4.1 µs) and, on `pmp_api36`, a ratio of 1.52–1.62x — and neither figure is about
the boundary, because on this target there is no boundary either: the same `nativeMain`
`PyMethodDef` whose `ml_meth` is a `staticCFunction` in the same binary. What it shares with iOS is
the reason both are expensive: an empty `withPython` scope costs 937–1010 ns here against 124–152 ns
on desktop, and `Py_IncRef + Py_DecRef` costs 2010–2132 ns against 165–326 ns. The per-call
scaffolding is the whole story on both native targets, and it is where the work is if either number
is to move.

Two differences from iOS are worth keeping separate from that. Its ratio sits *above* iOS's
1.33–1.43x rather than at it, because its downcall denominator (2170–2222 ns) is cheaper relative to
its upcall than iOS's is — the numerator is inflated by scaffolding on both, the denominator less so
here. And these figures are from an emulator on Apple Silicon, not hardware: `pmp_api26`'s spread
(above) is the honest width of that, and no androidNative figure here should be quoted as a
device number.

Nothing in the test asserts a duration. A wall-clock threshold on a shared build machine, an
emulator or a Node host is a flake generator, and this repo has had exactly that failure; what is
asserted is structural — the name bound, the loop demonstrably reached Kotlin, and every figure came
back positive rather than zero from a clock with no resolution, which is the failure mode that would
otherwise report the upcall as free.

### The `@CName` + `ctypes.CDLL(None)` route, measured

The row above used to read "`@CName` symbol, reached from Python via `ctypes.CDLL(None)`; one
binary, so the symbol is already in the global table". Both halves were checked when the entry
point was actually built, and on iOS **neither one holds**.

*Is the `@CName` symbol in the binary?* `nm` on each artifact this project produces, for the same
three functions (`pm_upcall_invoke`, `pm_upcall_resolve`, `pm_upcall_release_object`):

| Binary | Result |
|---|---|
| androidNative `libmultiplatform_python3.14.so` | **exported** (`T` in `nm -D`) |
| iOS `PythonMultiplatform.framework` | **absent** — a Kotlin/Native framework's export set is the Objective-C surface plus the Konan runtime, and a `@CName` alias is not in it |
| Kotlin/Native test executable, either target | **absent**, and absent from the per-file caches as well, so it is never emitted rather than dropped at link time |

*Can Python call an address at all?* Only where `_ctypes` exists. This project's iOS
`Python.framework` exports `PyInit__abc` … `PyInit_time` and ships no `lib-dynload` at all, so
`import ctypes` raises `ModuleNotFoundError` there. Android's CPython does ship `_ctypes.so`.

So the route is real for androidNative and unavailable on iOS in both directions at once. The
`@CName` functions are kept because they are what a C host embeds against and what `ctypes`
reaches on Android, and `UpcallEntry.invokeAddress` publishes the same addresses without
depending on the link — `UpcallEntryTest.theRawEntryPointsAreCallableCFunctionsOfTheDocumentedShape`
calls all three through the C ABI that way.

What replaces it is not a workaround but the destination: a `PyMethodDef` is what the generated
proxy type installs anyway, and desktop's `ctypes` shim (in `UpcallEntryTest`, `commonTest`) exists
precisely to stand in for a `PyCFunction` slot. Kotlin/Native fills the struct itself, so the claim
that no C glue is needed survives — it was the reason for the claim that did not.

## 테이블 생성 — KSP

- 우리가 **KSP 프로세서를 아티팩트로 배포**한다
- 사용자가 자기 빌드에 `ksp(...)` 를 추가한다 — **사용자 모듈에서도 KSP 가 돌아야 한다.** 사용자의
  Kotlin 클래스를 Python 에 노출하는 것이 목표이므로 필수다
- 모듈마다 자기 테이블 조각을 생성한다

### 미해결: 모듈 조각 수집

조각들을 런타임에 모으는 방법이 플랫폼마다 갈린다.

| 플랫폼 | 사정 |
|---|---|
| JVM (Desktop/Android) | `ServiceLoader` 사용 가능 |
| **Kotlin/Native (iOS)** | **`ServiceLoader` 없음.** 최상위 `object` 는 지연 초기화되므로, 아무도 참조하지 않는 조각은 **초기화 자체가 일어나지 않아 등록되지 않는다** |

후보:

1. **생성된 애그리게이터** — 최종 앱 모듈의 KSP 가 모든 조각을 아는 통합 객체를 만들고 명시적으로
   참조한다. 컴파일 타임에 확정되므로 트리 쉐이킹과도 맞는다. **다만 앱 모듈에도 KSP 가 붙어야 하고,
   라이브러리는 조각만 만들고 앱이 모으는 2단계 구조가 된다.**
2. `@EagerInitialization` — Kotlin/Native 에 있으나 실험적이고 트리 쉐이킹을 무력화할 위험이 있다
3. 사용자에게 명시적 등록 호출을 요구 — 가장 단순하지만 수동이다

**1번이 유력하다.**

### 미해결: 트리 쉐이킹

테이블이 모든 `public` 을 참조하면 Native Image 의 데드코드 제거가 무력화되어 바이너리가 부푼다.

이 문제는 **업콜에만 해당한다.** 다운콜은 CPython 심볼을 찾는 구조라 무관하다.

방향으로 제시된 것: 테이블에 `KFunction<*>` 참조 대신 **람다(함수 리터럴)** 를 넣으면 도달 가능성
분석이 개별 함수 단위로 이뤄질 수 있다. 위의 애그리게이터 방식(모듈 단위 분리)도 같은 문제를 완화한다.

확정된 답은 아직 없다. 업콜 구현에 착수할 때 결론을 내야 한다.

## 스레드 상태

업콜은 Python 스레드에서 Kotlin 으로 들어오므로, 반대 방향의 스레드 상태 문제가 있다.
[`threading-and-abi.md`](threading-and-abi.md) 를 본다.

---

## Cycle collection is part of the table's job

Settled after the fact, and it changes what the generator emits.

A Python proxy holding a Kotlin object that holds a Python object forms a cycle neither
collector can break on its own: CPython's traversal stops at the opaque handle, and the JVM sees
the handle map as a live root. Reference counting cannot help — it never frees cycles in any
language, which is why CPython carries a cyclic collector at all.

The fix is to make the Kotlin side's Python references visible to `tp_traverse`, so the proxy's
traverse reaches through the handle and enumerates the `PyObject`-typed fields of the Kotlin
object. KSP already walks every exposed class to build the call table and knows those field
types at that point, so a traverse function per class is one more generated entry rather than a
new mechanism.

This is why the reflection-free, build-time table matters beyond dispatch cost: a
reflection-based binding cannot afford this, which is why mature ones document "do not create
cycles" instead of solving it.

Full mechanism, and the three parts that remain hard — traverse running during collection,
`tp_clear` having to mutate Kotlin state, and cycles that close on the Kotlin side — are in
`docs/object-lifetime.md`.
