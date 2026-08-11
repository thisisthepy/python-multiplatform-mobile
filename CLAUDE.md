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

**같은 함정이 배경 실행에도 있다.** 위 예시처럼 `echo "EXIT=$?"` 로 끝내면 **스크립트 전체의 종료 코드는 `echo` 의 것(항상 0)** 이 된다. 포그라운드에서는 출력에 찍힌 `EXIT=` 값을 읽으니 문제가 없지만, `run_in_background` 로 돌리면 도구가 보고하는 것은 스크립트의 종료 코드 — 즉 `echo` 의 0 이다. **실패한 빌드가 성공으로 보고된다.** 배경 실행에서는 출력 파일의 `EXIT=` 줄을 직접 읽거나, `echo` 없이 gradle 을 마지막 명령으로 두어야 한다.

테스트 결과 집계는 종료 코드와 별개로 `build/test-results/<target>/*.xml` 을 직접 세는 것이 확실하다. 단, **집계 전에 해당 디렉터리를 지운다** — 크래시한 실행은 이전 실행의 XML 을 남겨두어 그 숫자를 보고하게 된다.

---

## 작업 환경 (2026-08 이후)

### 워크스페이스는 외장 SSD 에 있다

```
저장소        /Volumes/macMini/thisisthepy/PythonMultiplatform
참고 프로젝트  /Volumes/macMini/thisisthepy/compose-graal-hello   GraalVM 네이티브 이미지 참조
캐시          /Volumes/macMini/caches/{.gradle,.konan,emsdk}     홈에서 심볼릭 링크
```

**내부 SSD 는 여유가 거의 없다.** 옮길 수 있는 것은 전부 `/Volumes/macMini` 로 옮기고 심볼릭 링크를
건다. worktree, 빌드 산출물, 캐시, 새로 받는 SDK 모두 외장에 둔다. 내부에 무언가를 크게 만들기 전에
`df -h /` 를 확인한다.

### 자율 루프

지시가 없어도 **1시간 간격으로 깨어나 ROADMAP 의 미구현 항목을 이어서 구현한다.** 깨어날 때마다:

1. `git status --short` 로 트리 상태와 진행 중인 작업을 확인
2. 서브 에이전트 사용량이 복구되었는지 확인하고, **남은 쪽부터** 작업을 채운다
3. 병렬 가능한 작업은 worktree 로 분리해 동시에 돌린다
4. 끝난 작업은 직접 검증한 뒤 커밋·머지한다

**조율 세션의 토큰을 아낀다.** 조사·구현·대량 편집은 전부 서브 에이전트에게 넘기고, 이 세션은
분배·검증·커밋·머지만 한다. 직접 코드를 쓰는 것은 에이전트가 반복해서 틀리는 지점이나, 넘기는
비용이 실행 비용보다 큰 작은 수정에 한정한다.

## 서브 에이전트 운용

### 실행 수단은 셋이다

| 수단 | 호출 | 용도 |
|---|---|---|
| 현재 세션 | — | 조율 · 검증 · 커밋. **에이전트에게 커밋을 시키지 않는다** |
| Claude Code 2번 계정 | `CLAUDE_CONFIG_DIR=~/.claude-alt claude -p "..."` | 구현 작업. 1번과 **사용량 한도가 별도** |
| `agy` | `agy -p "..." --model ...` | 분석 · 조사, 그리고 Claude 한도가 남아 있을 때의 구현 |

**남은 사용량이 많은 쪽을 먼저 쓴다.** 한도는 계정별·모델 계열별로 따로 집계되므로, 작업을 배분하기
전에 각 수단에 짧은 프롬프트를 보내 살아 있는지 확인하고, 막힌 쪽은 건너뛴다. 한쪽이 막혀도 멈추지
않는다 — 남은 수단으로 계속 진행하고, 막힌 쪽은 복구되는 대로 다시 채운다.

2번 계정(`irack000@gmail.com`)은 `~/.claude-alt` 에 로그인되어 있고, 1번(`brew.airesearch@gmail.com`)을
덮어쓰지 않는다 — 양쪽 `-p` 호출이 동시에 성공하는 것으로 확인했다. 한쪽이 한도에 걸려도 다른 쪽으로 계속 진행한다.

### 서브 에이전트는 반드시 별도 프로세스로 띄운다

조율 세션의 턴 안에서 실행되는 서브 에이전트는 **그 턴이 끊기면 함께 죽는다.** 사용자가 답변 도중
메시지를 보내는 것은 정상적인 인터럽트인데, 그것만으로 몇 시간짜리 작업 세 개가 한꺼번에 사라졌다:

```
03:00:14   A · C   [Request interrupted by user]
03:13:15   D       [Request interrupted by user]
```

정지 시각이 인터럽트 시각과 초 단위로 일치했다. 처음에는 사용량 한도로 오진했는데, `rate_limit` 이
grep 에 걸린 것은 프롬프트와 설정 JSON 안의 단어였다.

**진행 상황을 묻는 것만으로 작업이 날아가는 구성은 잘못된 구성이다.** 오래 걸리는 작업은 예외 없이
배경 프로세스로 띄운다.

| 조율 세션이 끊길 때 | 함께 죽나 |
|---|---|
| 작성 중이던 답변 | 예 (의도된 동작) |
| 턴 안에서 실행되는 서브 에이전트 | **예 — 쓰지 말 것** |
| 배경 프로세스 (`claude -p`, `agy -p`, gradle) | 아니오 |

배경 프로세스로 띄우면 계정 분리와 인터럽트 내성이 한꺼번에 해결된다.

### 병렬화의 실제 상한은 git 이 아니라 기계다

`git worktree` 로 격리하면 **빌드 상태 충돌**은 사라진다 — 한 에이전트가 TDD 적색 단계로 트리를 깨면
같은 트리의 다른 에이전트가 자기 변경을 검증할 수 없는데, 실제로 그 일이 일어났다. 파일 소유권을
나누는 것만으로는 막히지 않는다. **TDD·대규모 작업은 worktree 로 격리한다.**

외장 SSD 로 옮긴 뒤 디스크 제약이 풀렸다 (2026-08 재측정):

```
디스크 여유   335 GB  (/Volumes/macMini)   이전 5.6 GB 에서 해소
RAM          16 GB
코어          8
```

이제 상한은 디스크가 아니라 RAM 과 기기다:

| | 동시 실행 상한 |
|---|---|
| 빌드·테스트를 도는 에이전트 | **3~4** (worktree 격리, Gradle/Kotlin 데몬 메모리가 한계) |
| 읽기 · 분석 · 문서만 하는 에이전트 | 추가로 3~4 |
| Android / iOS 기기 테스트 | **1** — 에뮬레이터가 2대뿐이고 패키지명이 같아 동시 설치가 충돌한다 |

**worktree 는 반드시 외장에 만든다** (`/Volumes/macMini/worktrees/<이름>`). 내부 SSD 에 만들면 금방 찬다.

계정을 늘리면 **띄울 수 있는 에이전트 수가 늘어나는 것이 아니라, 띄울 수 있는 둘을 더 안정적으로
채울 수 있게 된다.**

**작업 대부분은 `agy` 에 맡긴다.** Claude 서브 에이전트는 조율이 꼭 필요한 경우로 제한하고, 기본 실행 수단은 `agy` 로 둔다.

`agy` 에는 Gemini 계열뿐 아니라 **Claude 모델도 있다** (`claude-opus-4-6-thinking`, `claude-sonnet-4-6`). 모델을 작업 성격에 맞춰 고른다.

| 작업 성격 | 권장 모델 |
|---|---|
| 툴체인 배선, 설계 전제를 건드리는 작업 | Claude Code 2번 계정 |
| 근본 원인 추적, "이 결과가 무슨 뜻인가"가 중요한 작업 | `gemini-3.1-pro-high` |
| 대량 마이그레이션 · 기계적 리팩터 · 문서 동기화 · 측정 실행 | `gemini-3.6-flash-high` |
| 단순 반복 | `gemini-3.6-flash-medium` |

**등급을 낮춰도 되는 이유가 품질이 같아서가 아니다.** 2026-08 기준 하루치 관측에서, 에이전트 실패는
전부 *판단*에서 났고 기계적 작업에서는 나지 않았다 — 주석 처리된 죽은 코드를 live 로 읽음, 오염된
측정값을 유효한 것으로 보고, 항상 참인 단언 삽입, "판정 불가 0" 과신. **이것들이 전부 `pro` 등급에서
난 실수다.** 반대로 같은 등급이 67개 함수 마이그레이션을 14분에 끝냈다.

즉 등급을 낮춰 나빠지는 것은 판단이 필요한 작업뿐이고, 그런 작업은 상위 등급에서도 이미 틀린다.
조율 세션이 어차피 전부 검증하므로, 낮은 등급의 실수 비용은 검증 시간이지 깨진 코드가 아니다.

**모델 계열마다 한도가 따로 집계된다.** `gpt-oss-120b` 가 별도 풀인 것이 확인됐고(123시간 리셋),
flash 와 pro 도 마찬가지로 보인다. 따라서 기계적 작업을 flash 로 옮기는 것은 품질 문제이기 이전에
**총 처리량이 늘어나는 문제**다. 한쪽이 막히면 다른 계열로 옮겨 계속 돌린다.

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

### 에이전트 결과는 반드시 직접 검증한다

에이전트는 지시를 어긴다. 실제로 겪은 사례:

- 커밋 금지라고 했는데 `git checkout` 으로 남의 작업을 되돌림
- 담당 파일만 만지라고 했는데 `commonMain` 을 수정하고 `build.gradle.kts` 의 소스셋 구조를 바꿈
- 테스트 수정 금지라고 했는데 테스트를 고침
- 동작하던 구현 파일을 삭제하고 트리를 깨진 채로 남김

그러므로 보고 내용을 그대로 믿지 말고, **작업 후 항상 `git status --short` 로 범위를 벗어난 변경이 없는지 확인하고 빌드·테스트를 직접 재실행한다.** 되돌릴 수 있도록 검증된 상태를 미리 커밋해 두면 `git checkout -- <path>` 로 복구할 수 있다.

### 플랫폼별 규정은 각 소스셋의 README.md 에 있다

`python-multiplatform/src/<sourceSet>/README.md` 에 그 플랫폼에서 반드시 지켜야 할 것들이
측정 근거와 함께 정리되어 있다. **해당 소스셋을 건드리기 전에 읽는다.**

| 소스셋 | 핵심 규정 |
|---|---|
| `commonMain` | 계층 규약(객체 모델은 `bindings` 참조 금지), 모든 C API 호출에 GIL, 참조 규약 명시 |
| `desktopMain` | **`invoke` 금지, 무조건 `invokeExact`**. 포인터는 `ADDRESS` 아닌 `JAVA_LONG` |
| `androidMain` | `RegisterNatives` 로 바인딩, 규약을 API 레벨 + 함수별로 선택, 경계는 원시 타입만 |
| `artMain` | JNI 는 여기에만(`nativeMain` 은 iOS 와 공유), 조합 함수의 자리 |
| `nativeMain` | iOS 와 공유되므로 Android 전용 코드 금지, 경계가 없으므로 조합 불필요 |
| `iosMain` | 프레임워크에 stdlib 없음 → `PYTHONHOME` 필요, `SIMCTL_CHILD_` 접두사 |

미완료 작업과 그 근거는 저장소 루트의 `ROADMAP.md` 에 있다.

### 알려진 컴파일러 제약

`EmbedAPI.kt` 에는 `expect inline fun` 이 다수 있다. 이를 중간 소스셋(`jvmMain`)의 `expect`/`actual` 과 조합하면 Kotlin 2.0.20 에서 **`Internal error in file lowering`** 컴파일러 크래시가 발생한 사례가 있다. JVM 계열 통합을 설계할 때 이 제약을 먼저 확인할 것.

### 병렬 실행 시 주의

- **작업을 디렉터리 단위로 분할한다.** 여러 에이전트가 같은 파일을 만지면 서로 덮어쓴다.
- **동시에 도는 에이전트에게 커밋을 시키지 않는다.** git 인덱스 락이 충돌한다. 각자 작업만 하게 하고 커밋은 조율하는 쪽에서 한 번에 처리하거나, git worktree 로 격리한다.
- Gradle 데몬은 하나를 공유하므로 동시 빌드는 직렬화되어 느려진다. 감안하고 분배한다.

### agy 호출 시 주의

- **Claude 계열 모델에는 `--effort` 를 붙이면 안 된다.** `claude-opus-4-6-thinking` 과 `claude-sonnet-4-6` 모두
  붙이는 즉시 실행이 실패한다 (`invalid model selection: --effort is not supported for model "..."`).
  `--effort` 는 Gemini 계열에만 준다.
- 실행 후 로그 앞부분을 반드시 확인한다. 인자 오류는 즉시 종료되는데, 배경 실행이면 성공처럼 보인다.
- **사용량 한도는 Gemini 계열과 Claude 계열이 별도로 집계된다.** 한쪽이
  `Individual quota reached` 로 막혀도 다른 쪽은 그대로 쓸 수 있으니, 작업을 멈추지 말고 남은 계열로 계속 진행한다.

### 이 환경의 제약

- `timeout` 명령이 없다 (zsh, coreutils 미설치). 대신 각 도구의 자체 타임아웃 옵션을 쓴다.

---

## 업콜은 GraalVM 네이티브 이미지에서도 동작해야 한다

업콜(Python → Kotlin)은 JVM 위에서 도는 것으로 끝이 아니다. **GraalVM 네이티브 이미지로 빌드했을 때도
동작해야 한다.** 런타임 리플렉션이 닫힌 세계 가정 때문에 불가능하다는 것이 §7 설계가 빌드 타임 생성
테이블을 택한 이유이고, 그 선택이 실제로 성립하는지는 네이티브 이미지를 빌드해서만 확인된다.

- 참고 구현: `/Volumes/macMini/thisisthepy/compose-graal-hello` — Compose + GraalVM 네이티브 이미지가
  이미 도는 프로젝트. 빌드 배선을 여기서 가져온다.
- 툴체인: **Liberica Native Image Kit**
- 대상: 이 저장소의 `sample/` 앱에 네이티브 이미지 빌드 경로를 추가하고, 그 이미지에서 업콜이
  정상 동작하는지 검증한다.

JVM 에서만 통과하는 업콜은 완료가 아니다.

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
