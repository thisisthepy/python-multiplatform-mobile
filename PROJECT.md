# Python Multiplatform — 프로젝트 개요

Kotlin Multiplatform 에 CPython 을 임베딩해, **Kotlin 과 Python 이 서로의 라이브러리를 양방향으로
사용**할 수 있게 하는 것이 목표다. 모든 지원 플랫폼에서 동작하고, 경계를 넘는 오버헤드를 최소화한다.

이 문서는 전체 그림과 문서 지도다. 각 주제의 상세는 `docs/` 아래 계층 문서를 본다.

---

## 두 방향

이 프로젝트의 모든 설계는 두 방향으로 갈린다. 둘은 **직교**하며 메커니즘이 완전히 다르다.

| 방향 | 부르는 이름 | 메커니즘 | 상세 |
|---|---|---|---|
| Kotlin → Python | **다운콜** | CPython Stable ABI 함수 호출 | [`docs/downcall-design.md`](docs/downcall-design.md) |
| Python → Kotlin | **업콜** | 빌드 타임 생성 함수 테이블 + 진입점 1개 | [`docs/upcall-design.md`](docs/upcall-design.md) |

```
            ┌──────────────────────────────────────────┐
   Kotlin   │  PyObject / PyType / PyList / ...         │
   코드      │            ↓ 다운콜                        │
            │  expect fun PyList_Size(...)  (약 330개)   │
            └──────────────────────────────────────────┘
                          ↕
            ┌──────────────────────────────────────────┐
   Python   │  프록시 객체  →  업콜 진입점 1개  →  함수 테이블 │
   코드      │            ↑ 업콜                          │
            └──────────────────────────────────────────┘
```

---

## 플랫폼별 구현 수단

| 플랫폼 | Kotlin 실행 환경 | 다운콜 | 업콜 진입 |
|---|---|---|---|
| iOS | Kotlin/Native | cinterop (직접 호출) | `@CName` 심볼 + `ctypes.CDLL(None)` |
| androidNative | Kotlin/Native | cinterop (직접 호출) | 동상 |
| Desktop | JVM (HotSpot) | Panama (FFM) | FFM upcall stub |
| Android | ART | shape 트램폴린 | FFM/JNI upcall |
| WASM | Kotlin/Wasm | JS 경유 (미구현) | JS 경유 (미구현) |

**진입점 뒤는 모든 플랫폼이 같은 함수 테이블을 쓴다.** 플랫폼별 Python→Kotlin 바인딩 코드는 없다.

---

## 문서 지도

| 문서 | 내용 |
|---|---|
| [`docs/architecture.md`](docs/architecture.md) | 소스셋 계층, 타입 래퍼 구조, 검증 수단 |
| [`docs/downcall-design.md`](docs/downcall-design.md) | 시그니처 shape 14종, 플랫폼별 다운콜 메커니즘 |
| [`docs/upcall-design.md`](docs/upcall-design.md) | 함수 테이블, 프록시 핸들 캐싱, KSP, 모듈 수집 |
| [`docs/binding-policy.md`](docs/binding-policy.md) | 무엇을 Python 에 노출하는가 (블랙리스트 원칙) |
| [`docs/threading-and-abi.md`](docs/threading-and-abi.md) | GIL, free-threading, `abi3t`, 스레드 상태 관리 |
| [`docs/android-ffm-design.md`](docs/android-ffm-design.md) | Android FFM, PanamaPort 분석, GraalVM Native Image 제약 |
| [`docs/wasm-design.md`](docs/wasm-design.md) | WASM 실현 가능성과 제약 |
| [`docs/python-version-acquisition.md`](docs/python-version-acquisition.md) | CPython 바이너리 취득 파이프라인 |

작업 규칙과 환경 제약은 [`CLAUDE.md`](CLAUDE.md) 에 있다.

---

## 현재 상태

### 동작하는 것

- CPython Stable ABI 약 330개 바인딩 (`expect`/`actual`, 4개 플랫폼)
- 타입 래퍼: `PyObject`, `PyType`, `PyException`, 기본 타입 6종, 컬렉션 5종, 이터레이터, `PyModule`, 변환 계층
- **테스트 97개 전부 통과** — iOS 시뮬레이터에서 실제 CPython 을 구동해 검증
- Gradle 이 CPython 을 플랫폼별로 내려받아 검증·추출 (인터프리터를 저장소에 벤더링하지 않음)
- 네 타깃 컴파일: `compileKotlinDesktop`, `compileKotlinAndroidNativeArm64`, `compileDebugKotlinAndroid`, `iosSimulatorArm64Test`

### 진행 중

- 스레드 상태 관리: 메커니즘과 라우팅은 완료. **초기화 후 GIL 해제는 아직 켜지 않았다** — 라우팅 커버리지가 완전함을 먼저 증명해야 한다. 상세는 [`docs/threading-and-abi.md`](docs/threading-and-abi.md)

### 미착수

- 업콜 전체 (함수 테이블, KSP 프로세서, 프록시 타입)
- JVM shape 통합 (Desktop + Android 다운콜 일원화)
- Desktop `invokeExact` 전환 — 현재 305곳이 `MethodHandle.invoke` 라 호출마다 박싱한다
- WASM
- 양방향 객체 수명 관리 — Kotlin GC 와 CPython 참조 카운팅이 서로를 모른다

---

## 큰 결정들

| 결정 | 근거 |
|---|---|
| 병렬성은 **free-threading**, 멀티 인터프리터 아님 | 인터프리터별 GIL 은 C 확장이 `Py_mod_multiple_interpreters` 를 선언해야 하고, 안 한 확장은 import 자체가 실패한다 |
| free-threading 은 **3.15t 부터** | 3.13/3.14 free-threaded 빌드에는 Limited API 가 없다. `abi3t`(PEP 803, Final)가 3.15 부터다 |
| Python→Kotlin 은 **런타임 리플렉션 아님** | Kotlin/Native 에 리플렉션이 사실상 없고, GraalVM Native Image 는 closed-world 다 |
| 노출은 **블랙리스트** | 모든 `public` 자동 등록, `@PythonInternal` 로 명시적 제외 |
| PanamaPort **미사용** | 라이선스(GPLv2+CE 벤더링)와 ART 내부 구조 의존에 따른 유지보수 부채 |
| LLVM JIT **불필요** | 우리 시그니처엔 구조체 값 전달·가변인자가 0개라, 참조 구현조차 스텁 없이 직접 패치하는 경로를 탄다 |
