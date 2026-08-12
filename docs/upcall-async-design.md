# 비동기 업콜 설계 — Python 이 Kotlin 의 `suspend` 를 부를 수 있는가

`docs/upcall-design.md` 가 만든 업콜 경로는 다섯 플랫폼에 다 붙었지만, **노출할 수 있는 것은
일반 함수뿐이다.** 이 문서는 왜 그런지, 무엇을 대신 할 수 있는지, 후보 중 무엇을 골랐는지,
그리고 지금 무엇까지 구현되어 있는지를 정리한다.

이 문서에서 **측정** 이라고 적힌 것은 이 저장소에서 실제로 돌려 본 것이다. **추론** 이라고 적힌
것은 코드와 문서를 읽어 얻은 것이고, 기기가 없어 확인하지 못했다. 둘을 섞지 않는다.

---

## 1. 막는 것은 구현이 아니라 C 의 호출 규약이다

    CPython 이 C 함수 포인터를 부른다        ml_meth: PyObject *(PyObject *self, PyObject *args)
      → 그 C 스택 프레임은 PyObject * 를 동기적으로 반환해야 한다
      → 중간에 suspend 하면 반환할 것이 없다

`runBlocking` 은 해결이 아니다. 그 스레드를 다시 붙잡는 것이므로 얻는 것이 없고, **Android 에서는
특히 나쁘다** — 업콜이 도착한 스레드는 CPython 이 만든 pthread 이고(`docs/upcall-design.md`
"Android's boundary runs the other way round"), 그것을 막으면 답을 기다리는 인터프리터 쪽이 함께
멈춘다. 즉 자기가 기다리는 대상을 자기가 막는 형태가 된다.

그러므로 남는 선택지는 하나뿐이다: **다른 것을 반환하고, 값은 나중에 전달한다.**

---

## 2. 지금 KSP 는 `suspend` 를 어떻게 다루는가 — 측정

`BindingPolicy.isExposedFunctionShape` 한 줄이 전부다.

    if (Modifier.SUSPEND in function.modifiers) return false

**조용히 건너뛴다.** 깨지지도, 잘못된 코드를 만들지도 않는다. 로그도 없다.

`ksp-fixtures/library/.../Suspending.kt` 를 넣고 생성된 조각을 직접 읽어 확인했다
(`Fragment_io_github_thisisthepy_ksp_fixtures_library.kt`). 각 형태마다 같은 스코프에 비-suspend
대조군을 두었으므로, "스코프 전체가 빠져서 통과"하는 경우와 구분된다.

| 선언 | 결과 | 대조군 |
|---|---|---|
| 최상위 `suspend fun` | 항목 없음 | `blockingTopLevel` 있음 |
| 클래스 멤버 `suspend fun` | 항목 없음. **클래스는 그대로 노출되고 생성자도 남는다** | `fetchBlocking` 있음 |
| companion 의 `suspend fun` | 항목 없음 | `createBlocking` 있음 |
| interface 의 `suspend fun` | 항목 없음 | `describe` 있음 |
| `object` 의 `suspend fun` | 항목 없음 | `pingBlocking` 있음 |
| `ReflectedClass.memberNames` | 빠진 이름은 여기에도 없다 | — |

사용자 입장에서 보이는 것은 Python 쪽 `AttributeError` 하나뿐이고, **그 함수가 보였지만 거부되었다는
신호는 어디에도 없다.** 이것이 이 동작의 실제 비용이다.

`ksp-fixtures/app/src/desktopTest/.../GeneratedSuspendTest.kt` 가 이 표를 고정한다. 비동기 규약이
생기면 이 테스트들이 깨지도록 되어 있다 — 침묵이 소리 없이 다른 침묵으로 바뀌지 않게 하는 것이
목적이다.

### 2.1 그런데 `suspend` **타입** 은 걸러지지 않는다 — 측정

정책이 읽는 것은 *선언* 의 modifier 다. `val h: suspend (Long) -> Long` 에는 그 modifier 가 없다.
그래서 다음 셋은 전부 노출된다:

| 선언 | 생성된 것 |
|---|---|
| `val suspendingHandler: suspend (Long) -> Long` | `STATIC_GETTER`, `TypeTag.OBJECT` |
| `fun makeHandler(): suspend (Long) -> Long` | `FUNCTION`, 반환 `TypeTag.OBJECT` |
| `fun runsHandler(h: suspend (Long) -> Long)` | 파라미터 `TypeTag.OBJECT`, 캐스트는 아래 |

파라미터 캐스트로 생성된 것은 소스에 선언이 존재하지 않는 분류자다:

    args[0] as kotlin.coroutines.SuspendFunction1<kotlin.Long, kotlin.Long>

**그런데 이것은 컴파일된다** — `:ksp-fixtures:library:compileKotlinDesktop` 과
`compileKotlinAndroidNativeArm64` 둘 다 `e:` 없이 통과한다. 컴파일만이 아니라 런타임 캐스트도
통과한다(`GeneratedSuspendTest.theGeneratedCastToASuspendingFunctionTypeHoldsAtRuntime`).

다만 **이것은 우회로가 아니다.** 건너간 것은 불투명한 핸들이고, 그 핸들을 부를 항목은 테이블
어디에도 없다. Python 은 그것을 들고 있다가 Kotlin 에 돌려주는 것 말고 할 수 있는 일이 없다.

---

## 3. 후보 셋

Kotlin 쪽에서 셋이 공유하는 절반은 같다: **동기 프레임 안에서 코루틴을 시작하고, 결과를 나중에
주소를 매길 수 있는 곳에 세워 둔다.** 다른 것은 그 결과가 Python 에 도달하는 방법뿐이다.

### (A) 핸들 + 폴링

업콜이 즉시 작업 핸들을 반환하고, Python 이 `is_done(h)` / `result(h)` 로 묻는다.

**무엇이 막는가**

1. **wasm 에서는 반드시 데드락한다 — 추론.** wasmJs 에는 스레드가 없다. Kotlin 코루틴의 재개는 JS
   마이크로태스크 큐를 통해서만 일어나고, CPython 인터프리터 자체가 같은 JS 스레드 위에서 돈다.
   `while not is_done(h): pass` 는 JS 이벤트 루프에 제어를 돌려주지 않으므로, 재개가 큐에 들어간
   채 영원히 실행되지 않는다. 이것은 폴링이 느리다는 문제가 아니라 **폴링이 진행을 막는다** 는
   문제다. 기기 없이 확인할 수 없어 추론으로 표시한다.
2. **KSP 가 `Deferred<T>` 를 노출할 수 없다 — 측정.** `BindingPolicy.hasRenderableSignature` 는
   타입 파라미터가 있는 선언을 전부 거부한다(`Box<T>` 로 이미 고정되어 있다). 그러므로
   `Deferred.await`, `Job.isCompleted` 같은 것을 그대로 테이블에 올릴 수 없고, 제네릭이 아닌 전용
   타입을 손으로 써야 한다. 막는 것은 아니지만 설계 비용이다.
3. `kotlinx.coroutines` 는 `python-multiplatform` 의 의존성이 아니다 — 측정
   (`gradle/libs.versions.toml` 에 좌표는 있으나 `sample` 의 swing 아티팩트에서만 쓰인다).
4. JVM·Native 에서는 동작하지만 코어 하나를 태운다. GIL 을 쥔 채 도는 폴링 루프는 스위치 간격마다
   놓았다 다시 잡으므로, 다른 Python 스레드까지 함께 느려진다.

### (B) 콜백 (다운콜)

Python 이 콜러블을 넘기고, 완료 시 Kotlin 이 그것을 부른다.

**무엇이 막는가 — 그리고 막지 않는 것**

- **완료 스레드가 GIL 을 쥘 수 있는가: 쥘 수 있다 — 측정.**
  `AsyncCompletionProbeTest.aKotlinCreatedThreadCanResolveAnAsyncioFutureTheInterpreterIsWaitingOn`
  이 이것을 직접 보인다. **Kotlin 이 만든** 스레드(CPython 이 만든 것이 아니므로 스레드 상태를
  받은 적이 없다)가 `withGIL` 로 GIL 을 얻고 Python 을 호출한다. 그것도 인터프리터의 메인
  스레드가 `loop.run_until_complete` 안에 들어가 있는 동안에 — 테스트는 그 스레드가
  `loop.is_running()` 이 `True` 인 것을 **직접 관측한 뒤에만** 통과하도록 되어 있다. 처음 판은
  이 조건 없이 4 ms 만에 통과했고, 그것은 경합 없는 경로를 통과한 것이라 아무 의미가 없었다.
- **Android 에서 ART attach 가 필요한가: 필요 없다 — 추론.** `409da6fc` 가 다룬 것은 **업콜**
  방향이다. 거기서 스레드는 CPython 이 만든 맨 pthread 라 ART 가 본 적이 없었다. 여기서는 완료
  스레드가 Kotlin/JVM 이 만든 스레드이므로 ART 가 이미 알고 있고, 필요한 것은
  `PyGILState_Ensure` 뿐이다. 즉 방향이 반대라 그 문제가 재발하지 않는다. 에뮬레이터를 쓰지
  않았으므로 추론이다.
- **진짜 어려운 점은 GIL 이 아니라 재진입이다.** 콜백은 완료 스레드 위에서 *즉시* 실행되므로,
  Python 사용자 코드가 메인 스레드가 하던 일과 동시에 임의의 지점에서 돈다. 이것은 Python
  `threading.Thread` 가 이미 하는 일과 같으므로 새로운 위험은 아니지만, **동기화 책임이 전부
  사용자에게 간다.** 그리고 코루틴이 동기적으로 완료되면(§5 의 빠른 경로) 콜백은 업콜 프레임
  *안에서* 실행된다 — GIL 을 이미 쥔 채로, C 가 위에 있는 상태에서. `PyGILState_Ensure` 는
  재진입 가능하므로 안전하지만, 규약이 그것을 명시해야 한다.
- Kotlin 이 Python 콜러블의 참조를 오래 들고 있어야 한다. 그 참조를 언제 놓는가는 이미 풀린
  문제다 — `PyObject` 의 지연 해제 큐와 eval 체크포인트(`GILScope.kt`,
  `Python3.drainPendingReleases`)가 그 일을 한다. 새 메커니즘이 아니다.

### (C) `asyncio` 연동

Kotlin 이 `Future` 를 만들어 Python 이벤트 루프에 넘기고, 완료 시
`loop.call_soon_threadsafe(fut.set_result, v)` 로 해소한다.

**과제가 지목한 질문 — `call_soon_threadsafe` 가 이 ABI 부분집합에 있는가: 있다 — 측정.**

특별한 진입점이 필요하지 않다. 필요한 것은 전부 이미 `EmbedAPI.kt` 에 있는 일반 호출뿐이다:

| 필요한 것 | `EmbedAPI` |
|---|---|
| `import asyncio` | `PyImport_ImportModule` ✔ |
| `loop.call_soon_threadsafe`, `fut.set_result`, ... | `PyObject_GetAttrString` ✔ |
| 그것들을 호출 | `PyObject_Call` / `PyObject_CallObject` / `PyObject_CallNoArgs` ✔ |

`AsyncCompletionProbeTest.everyApiThisNeedsIsAlreadyBoundSoAnAsyncioConventionAddsNoNewBinding`
이 이 경로를 그대로 밟아 `new_event_loop` / `create_future` / `call_soon_threadsafe` /
`run_until_complete` / `set_result` / `set_exception` / `done` / `result` / `cancel` 이 전부 도달
가능함을 확인한다. **(C) 는 새 바인딩을 하나도 추가하지 않는다.**

참고로 다른 경로였을 `Py_AddPendingCall` 은 Stable ABI 에는 있지만(`test_stable_abi_ctypes.py` 에
등재) **`EmbedAPI` 에는 바인딩되어 있지 않다** — 측정. 그리고 그것은 C 함수 포인터를 받으므로
`(long) -> int` 트램폴린 shape 이 하나 더 필요하다. 이미 `Panama` 의 어휘 안에 있는 shape 이지만,
(C) 가 그것 없이 되므로 필요하지 않다.

**무엇이 막는가**

- 루프가 **돌고 있어야** 한다. Python 메인 스레드가 `asyncio.run()` 안에 있지 않으면
  `call_soon_threadsafe` 는 큐에 넣기만 하고 아무도 꺼내지 않는다. 닫힌 루프면 예외를 던진다.
  즉 (C) 는 **애플리케이션이 async 로 짜여 있을 것을 요구한다.**
- wasm: 위 요구가 바로 그 플랫폼의 조건이다(§4).

---

## 4. wasm 이 실제로 결정한다

세 후보 모두 JVM·Native 에서는 동작한다. 갈리는 것은 wasm 이고, 그 이유는 하나다: **wasmJs 에는
스레드가 없고, Python 과 Kotlin 이 같은 JS 스레드를 공유한다.**

그래서 wasm 에서는 셋 다 같은 전제를 요구한다 — **Python 이 호스트 이벤트 루프에 제어를 돌려줘야
한다.** 그 전제 위에서:

| 후보 | wasm |
|---|---|
| (A) 폴링 | **성립하지 않는다.** 폴링 루프가 제어를 돌려주지 않으므로 전제를 스스로 깬다 |
| (B) 콜백 | 성립한다. Python 이 돌아간 뒤 마이크로태스크에서 콜백이 실행된다 |
| (C) asyncio | 성립한다. 단 루프가 JS 에 양보해야 하므로 재래식 selector 루프로는 부족하다 |

이것은 전부 추론이다. 이 워크스페이스에서 wasm 은 컴파일 검증만 하고 있고
(`docs/wasm-design.md` §"Still open"), 실행 경로를 만들지 않았다.

---

## 5. 고른 것: (C), 그리고 (B) 는 그 구현 수단이다

**(C) 를 목표 규약으로 하고, (B) 를 그 내부 메커니즘으로 쓴다. (A) 는 버린다.**

근거는 셋이다.

1. **(A) 는 wasm 에서 성립하지 않는다.** 나머지 넷에서 동작하는 규약을 만들고 wasm 만 예외로
   두는 것은, 이 저장소가 다섯 타깃을 하나의 규약으로 유지해 온 방식과 맞지 않는다.
2. **(C) 는 새 바인딩을 요구하지 않는다 — 측정.** 이것은 예상이 아니라 확인이다. 반면 (A) 는
   제네릭이 아닌 전용 타입과 그 멤버들을 테이블에 새로 올려야 한다.
3. **(C) 는 기계적으로 (B) 다.** `call_soon_threadsafe` 를 부르는 것은 완료 스레드가 Python
   콜러블을 부르는 다운콜이고, 그것이 정확히 (B) 다. 즉 둘 중 하나를 고르는 문제가 아니라,
   (B) 를 만들면 (C) 가 그 위에 얹히는 관계다. 사용자에게 노출하는 표면만 (C) 로 정한다.
   `await kotlin_fn(x)` 는 Python 사용자가 이미 아는 문법이고, 콜백은 아니다.

**빠른 경로는 규약보다 앞에 온다.** `suspend` 는 시그니처이지 suspend 하겠다는 약속이 아니다.
실제 suspension 지점에 닿지 않는 `suspend fun` 은 **`start` 가 반환하기 전에 이미 완료된다**
(`PendingCallTest.aBlockThatNeverSuspendsIsAlreadyCompleteBeforeStartReturns`, 측정). 그런 호출은
`Future` 도 이벤트 루프도 없이 실제 값을 그대로 반환하면 된다. 규약은 그 경로를 먼저 태우고,
정말로 suspend 한 경우에만 `Future` 를 만든다.

### 뒤집어지면 이 결론도 뒤집힌다

- wasm 실행 경로가 열렸는데 asyncio 루프를 JS 에 양보시킬 방법이 없다면, (C) 는 wasm 에서
  (B) 로 내려앉는다 — 그때는 (B) 를 공개 표면으로 올리는 편이 낫다.
- Android 에서 완료 스레드가 실제로 ART attach 를 요구하는 것으로 밝혀지면, §3(B) 의 "방향이
  반대라 재발하지 않는다"가 무너지고 `409da6fc` 의 pthread 키 destructor 를 다운콜 쪽에도
  복제해야 한다.

---

## 6. 구현된 것과 구현하지 않은 것

### 구현: `PendingCall` — 셋이 공유하는 절반

`python-multiplatform/src/commonMain/kotlin/python/multiplatform/ffi/upcall/PendingCall.kt`

동기 프레임 안에서 코루틴을 시작하고, 결과를 세워 두고, 하나의 리스너에게 전달한다.
`PendingCallTest`(commonTest, 9개) 가 고정한다.

- **`kotlinx.coroutines` 의존성이 없다.** `startCoroutine` 과 `Continuation` 은 `kotlin-stdlib`
  common 이다. 노출되는 `suspend fun` 은 *사용자의* 것이고, 그것이 쓰는 디스패처는 이미 사용자
  클래스패스에 있다. 시작하는 데는 stdlib 만 있으면 된다.
- **`EmptyCoroutineContext`.** 디스패처가 없으므로 본문은 실제 suspension 까지 호출 스레드에서
  돈다. §5 의 빠른 경로가 여기서 나온다. 대가도 여기서 나온다 — 첫 suspension 이전의 작업은
  **C 프레임 안에서, GIL 을 쥔 채** 돈다.
- **아무것도 밖으로 던지지 않는다.** 본문의 예외도 리스너의 예외도 필드에 세워 둔다. 위에 C 가
  있는 프레임이므로 `UpcallTrampoline` 과 같은 규칙이다.
- **새 핸들 기계가 없다.** `PendingCall` 은 평범한 Kotlin 객체이므로 `HandleTable` 이 이미
  세대 태그가 붙은 핸들을 주고, `UpcallTrampoline.releaseObject` 가 이미 Python 쪽 해제 수단을
  준다.
- **동기화하지 않는다.** 경계의 나머지와 같은 규약을 지키는 조건에서다: 모든 변경은 GIL 을 쥔
  스레드에서 일어난다. 이것은 추가 부담이 아니다 — Python 에 도달하려는 완료는 어차피 GIL 을
  잡아야 하므로, 그 스코프가 이미 이 필드들을 덮는다.

### 구현하지 않음: 전달 절반

`onCompleted` 는 셋이 붙는 자리이고, **아무것도 붙어 있지 않다.** 다음이 전부 미구현이다:

| 미구현 | 왜 |
|---|---|
| KSP 가 `suspend fun` 에 항목을 생성하는 것 | 규약이 정해지기 전에 만들면 잘못된 것을 만든다 |
| `CallableKind` 의 비동기 변종 | 위와 같다 |
| `asyncio.Future` 를 만들고 해소하는 런타임 코드 | 경로는 §3(C) 에서 확인했으나 Android·wasm 미확인 |
| Python 쪽 프록시가 `await` 를 이해하는 것 | 위와 같다 |

§2 의 KSP 동작은 **의도적으로 바꾸지 않았다.** 지금의 침묵이 최선이라서가 아니라, 그것을
고정해 두어야 규약이 생길 때 변화가 테스트 실패로 드러나기 때문이다.

---

## 7. 다음에 확인해야 할 것 (순서대로)

1. **Android 에뮬레이터에서 (B) 의 완료 스레드.** Kotlin 스레드가 `PyGILState_Ensure` 로 들어가
   Python 콜러블을 부를 때 ART attach 가 필요한가. §3(B) 의 추론이 맞는지가 이것 하나에 달려
   있다. `UpcallThreadAttachTest` 옆에 두면 된다.
2. **동기 완료 비율.** §5 의 빠른 경로가 실제로 얼마나 자주 타는가. `PendingCall.isDone` 을
   `start` 직후에 세는 것으로 측정 가능하고, 이 값이 높으면 규약의 복잡도 대부분을 드문 경로로
   미룰 수 있다.
3. **wasm 실행 경로.** §4 가 전부 추론이다. `docs/wasm-design.md` 의 미해결 항목이 풀리기 전에는
   확인할 수 없다.
4. **`suspend` 가 걸러졌음을 사용자에게 알리는 것.** §2 의 침묵은 규약이 생겨도 남는다 — 규약이
   덮지 못하는 형태(확장 수신자를 가진 `suspend fun` 등)는 여전히 조용히 빠진다. KSP 경고가
   맞는 자리다.
