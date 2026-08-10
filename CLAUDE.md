# CLAUDE.md

이 저장소에서 작업하는 에이전트가 반드시 지켜야 할 규정과, 작업에 필요한 프로젝트 컨텍스트를 정리한 문서입니다.

---

## 필수 규정 (MUST)

### 1. 커밋 메시지에 공동 저자(Co-Author)를 넣지 않는다

커밋 메시지에 `Co-Authored-By: Claude ...` 를 **절대 추가하지 않는다.**
`Generated with Claude Code` 같은 생성 도구 표기도 커밋 메시지와 PR 본문에 넣지 않는다.

기본 동작이 이 라인을 자동으로 붙이도록 되어 있더라도, 이 저장소에서는 해당 기본값보다 이 규정이 우선한다.

```
# 잘못된 예
Feat: Implement PyObject invoke operators

Co-Authored-By: Claude Opus 5 <noreply@anthropic.com>   <-- 금지

# 올바른 예
Feat: Implement PyObject invoke operators
```

### 2. 작업 브랜치

`develop` 및 `release` 에 직접 커밋하지 않는다. 작업용 브랜치를 만들어 그곳에 커밋하고 푸시한다.

### 3. 빌드 검증 시 파이프로 종료 코드를 읽지 않는다

Gradle 출력을 `| tail`, `| grep`, `| head` 로 넘긴 뒤 종료 코드를 읽으면 **파이프 마지막 명령의 종료 코드**를 읽게 되어, 실패한 빌드를 성공으로 오인한다. 반드시 파일로 리다이렉트한 뒤 `$?` 를 확인한다.

```bash
./gradlew <task> --console=plain > /tmp/build.log 2>&1; echo "EXIT=$?"
```

컴파일 에러는 `e: ` 로 시작하는 줄이다. `w: ` 경고(특히 inlining 관련)는 다수가 기존부터 존재하던 것이므로 무시해도 된다.

---

## 프로젝트 개요

CPython 3.13 을 Kotlin Multiplatform 에 임베딩하여 Kotlin ↔ Python 상호운용을 제공하는 라이브러리.

- `python-multiplatform/` — 라이브러리 모듈
- `sample/` — Compose Multiplatform 데모 앱
- `binary/` — 플랫폼별 CPython 배포본 아카이브 원본

### 소스셋 구조

`commonMain` 을 최상위로, `jvmMain`(→ `androidMain`, `desktopMain`) 과 `nativeMain`(→ `iosMain`, `artMain` → androidNative) 두 갈래로 나뉜다. 소스셋 계층은 `python-multiplatform/build.gradle.kts` 의 `sourceSets` 블록에서 수동으로 구성한다.

### FFI 계층

- `python/native/ffi/EmbedAPI.kt` — CPython Stable ABI 에 대한 `expect` 선언 (약 313개)
- 플랫폼별 `EmbedAPI.<platform>.kt` + `bindings.kt` 가 `actual` 구현
- `NativePointer` (value class) 로 플랫폼 포인터 표현을 통일

### 객체 모델 계층

`python/multiplatform/ffi/` 아래에 `PyObject` 를 최상위로 하는 Kotlin 래퍼 타입들이 위치한다. 설계 의도는 저장소 루트의 `python_for_kotlin_binding.mermaid` 클래스 다이어그램을 따른다.

---

## 빌드 및 검증

### 검증용 타깃

| 타깃 | 커버 범위 | 비고 |
|---|---|---|
| `:python-multiplatform:compileKotlinAndroidNativeArm64` | commonMain + nativeMain + artMain | Xcode 불필요. 가장 빠른 기본 검증 루프 |
| `:python-multiplatform:compileKotlinIosSimulatorArm64` | commonMain + nativeMain + iosMain | Xcode 라이선스 동의 필요 |
| `:python-multiplatform:compileKotlinDesktop` | commonMain + jvmMain + desktopMain | |

### 환경 참고사항

- 이 저장소에는 테스트 프레임워크가 없다. 임의로 도입하지 말 것.
- 기본 JDK 는 버전이 바뀔 수 있다. Desktop FFI 는 특정 JDK 버전에 종속되지 않도록 작성한다.
- Android `Cleaner` (`java.lang.ref.Cleaner`) 는 API 33+ 에서만 제공되므로, 그 미만 버전을 지원하는 한 `PhantomReference` 기반 대체 경로가 필요하다.
