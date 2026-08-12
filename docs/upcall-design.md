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
| Android | FFM/JNI upcall |
| iOS / androidNative | `@CName` 심볼 + Python 쪽 `ctypes.CDLL(None)` |

iOS 는 Python 과 Kotlin/Native 가 같은 바이너리에 **정적 링킹**되므로, `ctypes.CDLL(None)` 으로 전역
심볼 테이블에서 `@CName` 심볼에 바로 닿는다. **별도 C 글루 코드가 필요 없다.** Python 쪽 인터페이스가
다른 플랫폼의 FFM 업콜과 동일해진다.

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
- **The result is a new reference.** Python takes ownership. Measured end to end through ctypes
  (`UpcallArgumentsTest.theReferenceReturnedToPythonIsTakenOverExactlyOnce`): a hundred calls move
  the returned object's refcount by zero.

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
| **iOS / androidNative** | `@CName("pm_upcall_invoke") fun(handle: Long, args: COpaquePointer?): COpaquePointer?` delegating to `UpcallTrampoline.invoke`, reached from Python via `ctypes.CDLL(None)`. Python and Kotlin/Native are one binary, so the symbol is already in the global table and no C glue is needed | cheapest of the three |
| **Android** | a JNI `static jlong` native method registered with `RegisterNatives`, and a C shim of `PyCFunction` shape that calls it — the boundary is primitives only (`androidMain/README.md`), which `(jlong, jlong) -> jlong` already is | one shim per shape |
| **wasm** | `@WasmExport` on the entry point plus `Table.set` to publish it, measured at 3.1 ns/call in `wasm-experiment` | already proven |

Nothing in that list touches `UpcallTrampoline`; each is the platform's existing upcall mechanism
pointed at it.

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
