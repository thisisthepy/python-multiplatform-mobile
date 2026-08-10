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
