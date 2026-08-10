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

### 3. 테스트 주도 개발 (TDD)

기능 구현은 **테스트를 먼저 작성한 뒤** 진행한다. 테스트 없이 구현부터 하지 않는다.

- 테스트 목표의 밑그림은 저장소 루트의 `python_for_kotlin_binding.mermaid` 를 참고한다. 단, 이 다이어그램은 **확정 스펙이 아니다.** 타입 간 관계와 제공해야 할 기능의 범위를 참고하는 용도이며, 구체적인 메서드 이름과 시그니처는 구현하면서 달라질 수 있다.
- 동작 검증만 하지 말고 **오버헤드가 큰 지점을 드러내는 측정 테스트**를 함께 둔다. FFI 호출 1건당 비용, 포인터 박싱, 참조 카운팅 왕복, 문자열 마샬링, 컬렉션 변환이 주요 관찰 대상이다. 코드베이스의 `@HighOverheadNativeCall` 애노테이션이 이미 이 의도를 표시하고 있다.
- 구현 전 단계의 실패 테스트와, 회귀로 인한 실패를 구분할 수 있게 작성한다.

### 4. 빌드 검증 시 파이프로 종료 코드를 읽지 않는다

Gradle 출력을 `| tail`, `| grep`, `| head` 로 넘긴 뒤 종료 코드를 읽으면 **파이프 마지막 명령의 종료 코드**를 읽게 되어, 실패한 빌드를 성공으로 오인한다. 반드시 파일로 리다이렉트한 뒤 `$?` 를 확인한다.

```bash
./gradlew <task> --console=plain > /tmp/build.log 2>&1; echo "EXIT=$?"
```

컴파일 에러는 `e: ` 로 시작하는 줄이다. `w: ` 경고(특히 inlining 관련)는 다수가 기존부터 존재하던 것이므로 무시해도 된다.

---

## 서브 에이전트 운용

작업을 분담할 때 Claude 서브 에이전트만 쓰지 말고 **`agy` 를 함께 사용한다.** `agy` 는 Gemini 계열까지 쓸 수 있어 모델을 섞을 수 있다.

### agy 사용법

`~/.local/bin/agy` 에 설치되어 있다. 비대화형 실행은 `-p` (= `--print`) 를 쓴다.

```bash
agy -p "<프롬프트>" --model gemini-3.1-pro-high --print-timeout 30m
```

주요 옵션:

| 옵션 | 용도 |
|---|---|
| `-p`, `--print` | 프롬프트 1건을 비대화형으로 실행하고 결과를 출력 |
| `--model` | 모델 지정 (`agy models` 로 목록 확인) |
| `--effort` | 추론 강도 (`low` \| `medium` \| `high`) |
| `--print-timeout` | print 모드 대기 시간. **기본 5분이라 긴 작업은 반드시 늘려야 한다** |
| `--add-dir` | 워크스페이스에 디렉터리 추가 (반복 지정 가능) |
| `--dangerously-skip-permissions` | 권한 프롬프트 자동 승인 |
| `--output-format` | `text` \| `json` \| `stream-json` |

사용 가능한 모델 (`agy models`): Gemini 3.6 Flash (low/medium/high), Gemini 3.5 Flash, Gemini 3.1 Pro (low/high), Claude Sonnet 4.6, Claude Opus 4.6 (Thinking), GPT-OSS 120B.

`agy agents` 는 현재 비어 있다 — 별도 등록된 에이전트 프로필은 없다.

### 병렬 실행 시 주의

- **작업을 디렉터리 단위로 분할한다.** 여러 에이전트가 같은 파일을 만지면 서로 덮어쓴다.
- **동시에 도는 에이전트에게 커밋을 시키지 않는다.** git 인덱스 락이 충돌한다. 각자 작업만 하게 하고 커밋은 조율하는 쪽에서 한 번에 처리하거나, git worktree 로 격리한다.
- Gradle 데몬은 하나를 공유하므로 동시 빌드는 직렬화되어 느려진다. 감안하고 분배한다.

### 이 환경의 제약

- `timeout` 명령이 없다 (zsh, coreutils 미설치). 대신 각 도구의 자체 타임아웃 옵션을 쓴다.

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

- 테스트는 `kotlin-test` 기반으로 각 소스셋의 `*Test` 에 둔다.
- 기본 JDK 는 버전이 바뀔 수 있다. Desktop FFI 는 특정 JDK 버전에 종속되지 않도록 작성한다.
- PanamaPort 를 의존성으로 추가하지 않는다.
- Android `Cleaner` (`java.lang.ref.Cleaner`) 는 API 33+ 에서만 제공되므로, 그 미만 버전을 지원하는 한 `PhantomReference` 기반 대체 경로가 필요하다.
