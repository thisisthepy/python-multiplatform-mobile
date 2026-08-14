# The cost table

Kotlin ↔ Python 경계의 비용을, **여섯 타깃 전부에 대해 한 번에** 뽑아 놓은 표.

`docs/upcall-design.md` 와 `docs/downcall-design.md` 는 각자의 조사 과정에서 나온 표를 갖고 있고,
그 표들은 **조사가 끝난 시점의 기록**이다. 이 문서는 다르다 — 여기 있는 표는 사람이 옮겨 적은 것이
아니라 `benchmarks/cost_table.py` 가 실행 결과에서 직접 렌더링한 것이고, 아래 "다시 뽑는 법" 한
줄이면 통째로 갱신된다.

이 분리가 필요한 이유는 이 저장소가 이미 두 번 당했기 때문이다.

1. **조건 없는 숫자.** 워밍업 3,000 에서 잰 값들이 워밍업 표기 없이 표에 들어갔고, 나중에
   그 값들이 전부 "호스트 JIT 이 얼마나 데워져 있었는가"의 함수였다는 것이 드러났다
   (`docs/downcall-design.md`, "The benchmark was measuring the benchmark").
2. **부하 걸린 기계.** 같은 커밋이 672 ns 와 1076 ns 를 오갔고 bisect 한 회차를 통째로 버렸다.

그래서 이 하네스는 두 가지를 강제한다. **숫자와 조건을 같은 산출물에 함께 기록하고, 바쁜 기계에서는
아예 측정을 거부한다.**

---

## 다시 뽑는 법

```bash
ANDROID_HOME=/Users/ibrew/Library/Android/sdk ./benchmarks/cost-table.sh --runs 3
```

끝이다. 이 한 줄이 여섯 타깃을 **차례로** 돌리고, 결과를 `benchmarks/results/cost-table-<시각>.json`
과 같은 이름의 `.tsv` 로 남기고, 아래 마커 사이를 다시 렌더링한다.

### 전제 조건

| 조건 | 없으면 |
|---|---|
| **기계가 조용할 것** — 1분 load average 가 4.0 미만 | 수집이 **거부된다.** `--allow-busy` 로 넘길 수 있으나, 그 경우 산출물과 표 머리에 오염 표시가 붙고 그 숫자는 인용 대상이 아니다 |
| `ANDROID_HOME` 이 platform-tools 가 있는 SDK 를 가리킬 것 | ART 두 타깃과 `androidNativeArm64` 가 **skipped 로 기록된다** (조용히 빠지지 않는다) |
| 에뮬레이터 `pmp_api26`, `pmp_api36` 이 떠 있을 것 | 해당 타깃이 skipped 로 기록되고, 띄우는 명령이 출력과 JSON 에 함께 남는다 |
| Xcode 라이선스 동의 | `iosSimulatorArm64` 가 실패로 기록된다 |
| 다른 에이전트·빌드가 돌지 않을 것 | load ceiling 에 걸린다 |

에뮬레이터 두 대는 **동시에 쓸 수 없다.** 하네스는 `ANDROID_SERIAL` 로 한 대씩 고정해 직렬로 돌고,
`connectedDebugAndroidTest` 의 출력 디렉터리를 매 실행 전에 지워 다른 기기의 이전 결과가 이번 실행의
것으로 읽히지 않게 한다.

에뮬레이터를 띄우는 명령:

```bash
$ANDROID_HOME/emulator/emulator -avd pmp_api36 -no-snapshot-save -no-boot-anim &
$ANDROID_HOME/emulator/emulator -avd pmp_api26 -no-snapshot-save -no-boot-anim &
```

### 부분 실행

```bash
./benchmarks/cost-table.sh --targets desktop,wasmJs       # 기기 없이 되는 것만
./benchmarks/cost-table.sh --targets artApi26            # 에뮬레이터가 하나만 붙었을 때
./benchmarks/cost-table.sh render                         # 가장 최근 JSON 을 다시 렌더링만
python3 benchmarks/cost_table.py render --check           # 문서가 최신 JSON 과 어긋나면 exit≠0
```

`--targets` 로 좁히는 것은 **타깃**을 좁히는 것이지 스위트를 좁히는 것이 아니다. 하네스는 어떤
경우에도 `--tests` 를 쓰지 않는다 — 스위트를 좁히면 측정 영역 자체가 바뀌고, 이 저장소는 그것으로
한 회차를 버렸다 (양 끝이 1300~1400 ns 로 올라갔다).

### 나눠 뜬 것을 하나의 표로 합치기

에뮬레이터는 한 번에 한 대만 쓸 수 있으므로, 여섯 타깃이 한 번의 실행으로 다 채워지지 않는 경우가
정상이다. 그럴 때는 각각 뜨고 렌더링할 때 합친다.

```bash
./benchmarks/cost-table.sh collect --targets desktop,iosSimulatorArm64,wasmJs --out /tmp/host.json
./benchmarks/cost-table.sh collect --targets artApi36,androidNativeArm64     --out /tmp/a36.json
./benchmarks/cost-table.sh collect --targets artApi26                        --out /tmp/a26.json
python3 benchmarks/cost_table.py render --in /tmp/host.json,/tmp/a36.json,/tmp/a26.json
```

**커밋이 다른 cut 을 합치는 것은 거부된다.** 서로 다른 트리에서 나온 행을 한 표에 놓는 것이
`downcall-design.md` 가 한 절(`Closed: repository drift, not run-to-run noise`)을 통째로 써서
추적한 바로 그 실패다. `--allow-mixed` 로 넘길 수 있으나, 그러면 표 머리에 커밋 불일치가 명시되고
어느 타깃이 어느 cut 에서 왔는지가 함께 렌더링된다.

---

## 여섯 타깃과 그 태스크

| 타깃 | Gradle 태스크 | 결과 위치 | 기기 |
|---|---|---|---|
| `desktop` | `:python-multiplatform:desktopTest` | `build/test-results/desktopTest/*.xml` 의 `<system-out>` | 불필요 |
| `iosSimulatorArm64` | `:python-multiplatform:iosSimulatorArm64Test` | `build/test-results/iosSimulatorArm64Test/*.xml` | simctl 시뮬레이터 |
| `wasmJs` | `:python-multiplatform:wasmJsNodeTest` | `build/test-results/wasmJsNodeTest/*.xml` | 불필요 (Node) |
| `androidNativeArm64` | `:python-multiplatform:androidNativeArm64Test` | `build/test-results/androidNativeArm64Test/*.xml` | **에뮬레이터** (`pmp_api36`) |
| `artApi26` | `:python-multiplatform:connectedDebugAndroidTest` | `build/outputs/androidTest-results/connected/debug/<기기>/logcat-*.txt` | **에뮬레이터** (`pmp_api26`) |
| `artApi36` | `:python-multiplatform:connectedDebugAndroidTest` | 〃 | **에뮬레이터** (`pmp_api36`) |

기본 여섯 외에 `androidNativeArm64Api26` 이 하나 더 선언되어 있다 — `upcall-design.md` 의 표가
androidNative 를 API 레벨별로 나눠 기록하기 때문이고, `--targets` 로 이름을 대면 돈다.

**ART 만 결과를 읽는 경로가 다르다.** AGP 가 쓰는 JUnit XML 에는 `<system-out>` 이 없다 —
251개 testcase 에 0개였다. 대신 AGP 는 테스트별 `logcat-<클래스>-<메서드>.txt` 를 옆에 남기고,
벤치마크의 `println` 은 거기 `I System.out:` 줄로 들어간다. 하네스는 그 줄에서 접두사를 떼고 읽는다.

---

## 무엇이 측정되고, 어디서 오는가

| 파일 | 소스셋 | 재는 것 | 파서가 읽는 형태 |
|---|---|---|---|
| `overhead/Benchmark.kt` | `commonTest` | 하네스 자체 (`run`/`measure`) — 벤치마크가 아니다 | — |
| `overhead/BenchmarkTest.kt` | `commonTest` | 포인터 박싱, refcount 왕복, 문자열 마샬링(8/256/8192자), 정수 마샬링, `PyObject` 래퍼, `PyObject_GetAttrString` | `--- Benchmark Report ---` 표 |
| `native/ffi/UpcallBoundaryCostTest.kt` | `commonTest` | **업콜 1건**과 그것을 재는 데 필요한 다섯 기준선(같은 실행 안에서) | `--- Upcall boundary cost: <플랫폼> ---` 블록 |
| `ffi/upcall/GeneratedProxyCostTest.kt` | `commonTest` | 생성된 프록시 계층이 붙이는 비용 | `--- Generated proxy cost... ---` 블록 (JSON·TSV 에는 들어가나 아래 표에는 쓰지 않는다) |
| `reflection/UpcallOverheadTest.kt` | `commonTest` | 이름 조회 vs 캐시된 핸들, 인자 배열 비용 | `--- Benchmark Report ---` 표 |
| `desktopTest/DesktopOverheadBenchmark.kt` | `desktopTest` | Panama 전환 비용 | desktop 에서만 |
| `wasmJsTest/WasmMarshallingOverheadTest.kt` | `wasmJsTest` | `internedUtf8` vs `scratchUtf8` vs malloc/copy/free | wasmJs 에서만 |
| `androidInstrumentedTest/JniOverheadBenchmark.kt` | `androidInstrumentedTest` | JNI 호출 규약별 전환 비용 | ART 에서만 |
| `androidInstrumentedTest/CompositionBenchmark.kt` | `androidInstrumentedTest` | 호출당 크로싱 vs 조합된 `artMain` 호출 1건 | ART 에서만 |
| `androidInstrumentedTest/UpcallOverheadTest.kt` | `androidInstrumentedTest` | Android 업콜의 attach 비용 | ART 에서만 |

**다운콜은** `BenchmarkTest` 와 `UpcallBoundaryCostTest.measureDowncalls()` 에서,
**업콜은** `UpcallBoundaryCostTest` 와 `reflection/UpcallOverheadTest` 에서 나온다.
아래 표 두 개는 앞의 둘만 쓴다 — 여섯 타깃 전부에서 같은 모양으로 도는 것이 그 둘이기 때문이다.

파서는 라벨을 하드코딩하지 않는다. 위 블록 형식(`--- 제목: 플랫폼 ---`, 조건 줄, 들여쓴 `라벨  값 ns`)
을 따르는 것은 전부 JSON·TSV 로 들어간다. 표에 어떤 라벨을 쓸지는 렌더러가 고른다.

### 워밍업은 타깃의 성질이 아니라 **테스트 클래스**의 성질이다

`Benchmark.printReport` 로 출력하는 클래스가 여럿이고, 각자 워밍업이 다르다 —
`BenchmarkTest` 는 100,000, `reflection/UpcallOverheadTest` 는 `Benchmark.run` 의 기본값 1,000,
`GeneratedProxyCostTest` 는 5,000. 그래서 하네스는 모든 행에 **출력한 테스트 클래스**를 함께
기록하고, 렌더러는 `BenchmarkTest` 행만 그 표에 넣는다. 나머지는 "출력한 테스트" 열을 달아 별도
표로 나간다. 워밍업이 다른 행을 한 워밍업 캡션 아래 한 열에 놓는 것이 이 표를 한 번 무효화한
바로 그 오류이므로, 그 섞임은 구조적으로 막아 두었다.

### 파서가 우회하고 있는 출력 결함 하나

`UpcallBoundaryCostTest.report()` 는 라벨을 54칸으로 패딩하는데 다섯 라벨이 그보다 길다. 그래서
값이 라벨에 붙어 출력된다:

```
  PyObject_CallObject on a Python def (downcall, same shape)137.06 ns
    ...Python3.withPython { } with nothing in it, for scale53.37 ns
```

공백 구분자를 요구하는 파서는 **다운콜 표를 이루는 다섯 행을 통째로 놓친다.** 하네스는 값을 뒤쪽
단위(`ns`)에 앵커해 읽으므로 영향받지 않는다. 다만 사람이 읽는 출력으로서는 결함이고,
`commonTest` 파일을 고치는 일이라 여기서는 손대지 않고 기록만 해 둔다 — 고칠 때는 `padEnd(54)` 를
가장 긴 라벨(58자)보다 크게 올리면 된다.

---

## 얼마나 걸리는가

조용한 기계 기준의 근거 있는 추정. **호스트 두 타깃만 실제로 측정했고**, 나머지는 근거를 밝힌
추정이다.

| 타깃 | 1회 벽시계 | 근거 |
|---|---|---|
| `desktop` | ~50초 | 측정 (load 7.7, Gradle 캐시 warm). 조용하면 더 짧다 |
| `wasmJs` | ~25초 | 측정 (load 8.6, 같은 조건) |
| `iosSimulatorArm64` | 수 분 | 미측정. 시뮬레이터 부팅 + Kotlin/Native 테스트 링크가 지배적이고, 스위트 자체는 `downcall-design.md` 가 19.38 초로 기록 |
| `androidNativeArm64` | 수 분 | 미측정. 링크 + 60 MB 표준 라이브러리 push (`--sync` 라 2회차부터는 비교만) + 기기 실행 |
| `artApi26` / `artApi36` | 각 수 분 | 미측정. APK 조립·설치 + 251개 계측 테스트 (기록상 스위트 자체는 38~40초) |

캐시가 warm 하면 **여섯 타깃 1회가 대략 15~25분**, `--runs 3` 이면 링크·설치가 재사용되므로
선형보다는 덜 늘어 **40분~1시간** 정도로 본다. 콜드 캐시(새 worktree, Kotlin/Native 링크부터)면
여기에 링크 시간이 더 붙는다. **이 추정은 측정 두 개와 문서에 기록된 스위트 실행 시간에서 나온
것이고, 기기 세 타깃은 이 작업에서 한 번도 돌리지 않았다.**

---

## 산출물

| 파일 | 내용 |
|---|---|
| `benchmarks/results/cost-table-<시각>.json` | 실행별 전체 기록 — 조건, 스위트 집계, 파싱된 모든 지표, 건너뛴 타깃과 그 이유 |
| `benchmarks/results/cost-table-<시각>.tsv` | 같은 것의 long-format. `target run status source section label value unit iterations platform commit contaminated` |
| `benchmarks/results/logs/<타깃>-run<N>-<시각>.log` | 그 실행의 Gradle 출력 전체 |
| 이 문서의 마커 사이 | 위 JSON 에서 렌더링된 표 |

표에 남는 측정 조건: **커밋 해시**와 그 제목, 브랜치, **워킹 트리가 더러운지**, 수집 시각, 호스트
CPU·코어 수·OS, **시작/종료 load average 와 ceiling**, 타깃당 실행 횟수, `UpcallBoundaryCostTest`
가 스스로 출력한 **반복 횟수와 워밍업 횟수**, 소스에서 읽은 `BenchmarkTest` 의 `WARMUP` /
`WARMUP_BULK`, **기기·시뮬레이터 식별자**(AVD 이름, 시리얼, API 레벨, ABI / 부팅된 시뮬레이터),
플랫폼이 스스로 보고한 이름, 스위트 통과·실패·스킵 수, 벽시계 시간, 그리고 태스크 종료 코드.

`BenchmarkTest` 의 워밍업만 소스에서 읽는다 — 그 테스트는 자기 워밍업을 출력하지 않기 때문이다.
(`UpcallBoundaryCostTest` 는 출력한다.) 워밍업 없는 숫자가 이 표를 한 번 무효화했으므로 추정하지
않고, 읽지 못하면 그 사실을 기록한다.

---

<!-- COST-TABLE:GENERATED BEGIN -- everything between these markers is written by benchmarks/cost_table.py; edit the script, not this -->

_아직 한 번도 렌더링되지 않았다. 위 "다시 뽑는 법" 을 조용한 기계에서 실행하면 이 자리에 표가 들어온다._

<!-- COST-TABLE:GENERATED END -->
