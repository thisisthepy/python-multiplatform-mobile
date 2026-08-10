# 다운콜 설계 — Kotlin → Python

Kotlin 코드가 CPython Stable ABI 함수를 호출하는 방향. 반대 방향은
[`upcall-design.md`](upcall-design.md).

## 시그니처 shape 조사 결과

`EmbedAPI.kt` 의 약 330개 `expect` 선언을 전수 분석한 결과다.

| 항목 | 결과 |
|---|---|
| 전체 함수 | 330 |
| **구분되는 ABI shape** | **14** |
| 최대 인자 수 | 6 (`PyErr_WarnExplicit`) |
| 부동소수점 관련 함수 | 6개뿐 |
| 정수/부동소수 **혼합** 시그니처 | **0** |
| 가변인자 | **0** |
| 구조체 값 전달 | **0** |

부동소수점을 쓰는 함수는 `PyLong_FromDouble`, `PyLong_AsDouble`, `PyFloat_FromDouble`,
`PyFloat_AsDouble`, `PyFloat_GetMax`, `PyFloat_GetMin` 여섯 개가 전부다.

shape 를 가르는 기준은 arm64(AAPCS64)와 x86-64(SysV) 모두에서 같다 — **정수·포인터가 한 레지스터
뱅크, 부동소수점이 다른 뱅크**를 쓰므로, (정수형 인자 수, 부동형 인자 수, 반환 종류) 가 shape 다.
혼합 시그니처가 0개라 트램폴린의 C 프로토타입이 단순해진다.

**결론: 14개 트램폴린이면 CPython Stable ABI 전체를 덮는다.**

## 플랫폼별 메커니즘

### iOS / androidNative — cinterop

Kotlin/Native 가 헤더를 읽어 직접 호출 코드를 낸다. **오버헤드가 없고 손댈 것도 없다.** 이미 최적이다.

### Desktop — Panama

`desktopMain/.../PanamaBackend.kt` 가 `java.lang.foreign`(JDK 22+, 19~21 은 preview)과
`jdk.incubator.foreign`(JDK 16~18)을 **둘 다 리플렉션으로만** 접근한다. 어느 API 도 직접 import 하지
않으므로 **어떤 JDK 에서도 컴파일**되고, 런타임에 백엔드를 고른다.

리플렉션은 초기화 때 한 번만 돌고 이후는 캐시된 `MethodHandle` 을 쓴다.

**알려진 성능 문제**: 호출부 305곳이 `MethodHandle.invoke(...) as Long` 형태다. `invoke` 는 호출
지점에서 `asType` 변환을 하고, Kotlin 에서는 반환 타입이 `Any!` 로 보이므로 **호출마다 박싱/언박싱이
발생**한다. Panama 의 핵심 장점(JIT 인라인 가능, 할당 없는 다운콜)이 사라진다. `invokeExact` 는 호출
지점의 정적 시그니처가 핸들 타입과 정확히 일치해야 하는데, shape 14종으로 고정하면 그 조건을 맞추기
훨씬 쉬워진다.

### Android(ART) — shape 트램폴린 (미구현)

ART 는 런타임에 임의 시그니처의 네이티브 호출을 만들 수 없다. 그래서 shape 마다 트램폴린을 **미리
컴파일**해두고, 대상 함수 포인터를 첫 인자로 받는다:

```kotlin
// Kotlin/Native 로 컴파일해 .so 에 넣는다 (NDK 불필요)
@CName("...downcallJ_J")
fun downcallJ_J(fn: Long, a0: Long): Long =
    fn.toCPointer<CFunction<(Long) -> Long>>()!!.invoke(a0)
```

주소는 런타임에 무엇이든 될 수 있고, **고정되는 것은 시그니처 형태뿐**이다.

도달 방법은 두 단계로 나눈다:

| 단계 | 경로 | 비용 |
|---|---|---|
| 1 | 일반 JNI → 트램폴린 → 간접 호출 | JNI 전환 비용 포함 |
| 2 | ART 엔트리 포인트 패치 + `@CriticalNative` → 트램폴린 | 간접 분기 1회만 |

1단계는 에뮬레이터 없이 컴파일 검증이 되고, 2단계는 같은 트램폴린 코드를 그대로 쓰면서 도달 방법만
바꾼다. `@CriticalNative` 는 `JNIEnv` 도 `jclass` 도 GC 스레드 상태 전환도 없어 ART 에서 가장 빠른
네이티브 규약이다.

**LLVM JIT 은 필요 없다.** 참조 구현(PanamaPort)조차 단순 시그니처에는 스텁을 만들지 않고 직접
패치하는 경로를 탄다 (`BulkLinker.requireNativeStub` 이 `false` 를 반환). LLVM 스텁은 구조체 값
전달·가변인자처럼 레지스터 배치를 손봐야 할 때만 필요한데, 우리 시그니처엔 둘 다 0개다.

## JVM 통합 — Desktop + Android 를 한 벌로

현재 JVM 쪽은 330개 `actual` 이 **두 벌** 있다. 여기에 플랫폼별 `bindings` 선언까지 더하면 손으로
유지하는 선언이 1,300개에 가깝다. 바인딩 하나를 추가하려면 네 계층을 맞춰야 한다.

shape 를 도입하면 플랫폼별 표면이 **14개로 줄고** 330개 `actual` 은 `jvmMain` 에 한 벌만 남는다:

```
commonMain   expect fun PyList_Size(list: NativePointer): Long

jvmMain      actual fun PyList_Size(list) = downcallJ_J(sym.PyList_Size, list.toRawValue())
             internal expect fun downcallJ_J(fn: Long, a0: Long): Long     ← shape 14개
 ├── desktopMain  internal actual → 캐시된 Panama 핸들
 └── androidMain  internal actual → shape 트램폴린
```

**인터페이스나 추상 클래스를 쓰지 않는다.** `expect`/`actual` 이 같은 일을 하는 언어 기능이고,
인터페이스를 두면 호출마다 가상 디스패치가 붙는다. `expect`/`actual` 은 컴파일 타임에 확정된다.

검증된 절차:

1. 대상 함수의 commonMain `expect` 에서 `inline` 제거
2. `androidMain`/`desktopMain` 의 `actual` 삭제
3. `jvmMain` 에 `actual` 하나 + shape 함수 호출
4. shape 함수를 `internal expect` 로 두고 각 플랫폼이 `internal actual` 제공

함수 하나(`PyList_Size`)로 4개 타깃 전부 컴파일되는 것까지 실험으로 확인했다.

## GraalVM Native Image

Native Image 는 **FFM 다운콜을 빌드 타임에 등록**해야 한다. `reflect-config.json` 으로는 안 되고,
커스텀 `Feature` 의 `duringSetup` 단계에서 `RuntimeForeignAccess.registerForDowncall(descriptor, options)`
를 호출해야 하며, `-H:+ForeignAPISupport` 로 활성화하는 **실험적** 기능이다.

여기서도 shape 14종이 유리하다 — `Feature` 하나에서 `registerForDowncall` 을 14번 부르면 끝난다.
임의 시그니처를 런타임에 만드는 구조였다면 애초에 불가능하다.

우선순위는 낮다. 상세는 [`android-ffm-design.md`](android-ffm-design.md).

## 스레드 상태

다운콜은 전부 GIL(또는 free-threaded 빌드의 스레드 상태)을 요구한다.
[`threading-and-abi.md`](threading-and-abi.md) 를 본다.

---

## 개정: Android 는 shape 트램폴린만으로 풀리지 않는다

위의 shape 설계는 **Desktop 에는 유효하지만 Android 에는 그대로 적용되지 않는다.** 설계 논의에서
드러난 사실들을 기록한다.

### 확인된 버그: 현재 Android JNI 배선이 어긋나 있다

`@CName` 이 만드는 C 함수는 선언한 Kotlin 인자만 받는다:

```
Java_python_native_ffi_bindings_PyList_1Size(jlong list)
```

그런데 `androidMain/bindings.kt` 는 이를 **일반 JNI 메서드**로 선언한다. 일반 JNI 메서드를 ART 는
이렇게 호출한다:

```
Java_..._PyList_1Size(JNIEnv* env, jobject thiz, jlong list)
```

**인자가 두 칸 밀린다.** `list` 자리에 `JNIEnv*` 가 들어간다. 확인 결과 `@CName` 익스포트 중
`JNIEnv` 를 받는 것은 0개이고, `@CriticalNative`/`@FastNative` 도 어디에도 없다.

즉 **Android JVM 경로는 컴파일만 되고 런타임에 동작하지 않는다.** 실행된 적이 없어 드러나지 않았다.

### ART 의 세 가지 호출 규약

| | 일반 JNI | `@FastNative` | `@CriticalNative` |
|---|---|---|---|
| 전환 오버헤드 | 115 ns | 35 ns | 25 ns |
| C 시그니처 | `(JNIEnv*, jobject, args…)` | 동일 | **`(args…)` 만** |
| 객체 인자·반환 | 가능 | 가능 | **원시 타입만** |
| 정적/인스턴스 | 둘 다 | 둘 다 | **정적만** |
| JVM 으로 콜백 | 가능 | 가능 | **불가** |
| 실행 중 GC | 허용 | **차단** | **차단** |

shape 트램폴린은 정적이고 `Long`/`Double` 만 주고받으므로 `@CriticalNative` 에 정확히 맞는다. 그리고
우리 `@CName` 익스포트가 `JNIEnv` 를 받지 않는다는 사실이 곧 `@CriticalNative` 규약과 일치하므로,
**애노테이션을 붙이는 것이 성능 개선인 동시에 위 버그의 수정**이다.

`@CriticalNative` 는 API 34 부터 공식 SDK 에 포함된다. minSdk 26 구간에서는 `RegisterNatives` 로
등록해야 한다 — `RegisterNatives` 는 호출 규약이 아니라 바인딩 수단이므로 둘은 대안 관계가 아니다.

### critical 계열은 업콜과 공존할 수 없다

`Linker.Option.critical()` 과 `@CriticalNative` 는 제약이 같다. 짧고 블로킹하지 않아야 하며,
**콜백으로 JVM 에 되돌아오면 안 된다.** 위반은 정의되지 않은 동작이고 런타임이 보호해 주지 않는다.

이 프로젝트는 **업콜이 핵심 요구사항**이므로 이 제약이 결정적이다:

| 함수 | critical |
|---|---|
| `PyList_Size`, `Py_IncRef`, `PyLong_FromLongLong` | 적용 가능 |
| `PyObject_Call`, `PyRun_String`, `PyImport_ImportModule` | **불가** — Python 코드를 실행하며 Kotlin 으로 되돌아올 수 있다 |

가장 뜨거운 호출들이 정확히 critical 을 못 쓰는 쪽이다. 그리고 **shape 는 이를 구분하지 못한다** —
`downcallII_I` 하나가 `PyList_GetItem`(가능)과 `PyObject_Call`(불가)을 함께 덮는다. 시그니처가
같아도 성격이 반대다.

### Desktop Panama 와 Android Panama 는 성능이 다르다

Desktop 과 Android 를 같은 방식으로 통일하려고 Android 에 FFM 을 얹어도, **API 형태만 통일되고 성능
특성은 통일되지 않는다.**

| | Desktop (HotSpot) | Android (PanamaPort 류) |
|---|---|---|
| 스텁 생성 | **VM 이 기계어로 생성** | ART 메서드 엔트리 포인트를 패치 |
| JIT 가시성 | `MethodHandle` 을 C2 가 인라인·특수화 | ART JIT 는 그만큼 못 함 |
| 중간 계층 | 없음 | `MethodHandle` + 자체 변환 계층 |
| 인자 마샬링 | VM 생성 코드 안 | Java/Kotlin 코드 |
| 전환 생략 | `Linker.Option.critical()` | `@CriticalNative` (대응됨) |

**전환 비용 자체는 대응되지만, 그 위 계층에서 갈린다.** Desktop 의 Panama 는 런타임의 일부이고,
Android 의 Panama 는 런타임인 척하는 라이브러리다. 같은 바닥 위에 계층이 더 있고 JIT 가 약하므로
Android 쪽이 더 빠를 수는 없다. 다만 **얼마나 다른지는 측정된 바 없다.**

우리 desktop 구현은 현재 `Linker.Option.critical()` 을 쓰지 않는다 —
`PanamaBackend.kt` 가 `downcallHandle(..., emptyOptions)` 로 기본 경로를 탄다.

### 미결정: 조립 단위의 범위

경계 통과가 비싸고 뜨거운 호출에는 critical 도 못 쓰므로, **통과 횟수 자체를 줄이는 것**이 확실한
접근이다. 원래 설계 의도가 이것이었다 — `bindings` 의 저수준 함수들을 `Python3.kt` 같은 **바인더
레벨 연산으로 조립**해 `artMain` 에서 내보내고, `androidMain` 은 그 굵은 단위를 호출한다.
`artMain/JniExport.kt` 의 `initialize`/`finalize`/`internalIsInitialized` 가 그 형태로 남아 있다.

조립 단위는 길고 업콜 가능성이 있으므로 일반 JNI 를 쓴다. 통과가 적으면 개당 115 ns 도 감당된다.
반대로 shape 트램폴린은 짧고 콜백이 없으므로 `@CriticalNative` 를 쓴다. **둘은 대립이 아니라 층이
다르다** — 조립이 통과 횟수를 줄이고, critical 이 남은 통과를 싸게 한다.

정해야 할 것은 **조립 단위를 어느 층위로 잡는가**이다. 지금 객체 모델(`PyObject`, `PyType`, 컬렉션)이
`commonMain` 에 있고 그 안에서 원시 `expect` 를 호출하므로, Android 에서 조립하려면 그 연산 자체가
플랫폼별로 갈라져야 한다. 후보:

1. 바인더 API 를 `expect`/`actual` 로 올리고, Android 는 `artMain` 조립 함수를 호출하는 얇은
   포워더가 된다 — 로직은 Kotlin/Native 에 한 벌
2. `commonMain` 에 로직을 두되 Android 만 별도 경로 — 중복 발생
3. 조립을 `Python3` 수준으로 한정하고 `PyObject`/컬렉션은 잘게 둔다 — 절충

이 결정 전에는 `EmbedAPI.jvm.kt` 통합을 진행할 수 없다. Android 가 조립 단위를 쓰면 잘게 쪼갠 330개
`expect` 를 구현하지 않게 되기 때문이다.
