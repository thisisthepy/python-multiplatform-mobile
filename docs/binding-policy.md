# 노출 정책 — 무엇이 Python 에서 보이는가

Kotlin 의 무엇을 함수 테이블에 등록해 Python 에 노출할지에 대한 규칙. 테이블 자체의 구조는
[`upcall-design.md`](upcall-design.md).

## 원칙: 블랙리스트

**모든 `public` 선언이 자동으로 등록된다. 빼고 싶은 것에 애노테이션을 붙인다.**

화이트리스트(`@PythonAPI` 를 붙인 것만 노출)는 **기각됐다.** 노출하려는 모든 멤버에 애노테이션을
다는 것은 지나치게 번거롭고, 라이브러리 작성자가 붙이기를 잊으면 조용히 안 보이게 된다.

```kotlin
class MyClass {
    fun doSomething(arg: String): String = "..."   // 자동 노출

    @PythonInternal
    fun internalHelper() { }                       // 제외

    private fun privateMethod() { }                // 애초에 대상 아님
}

@PythonInternal
class InternalUtility { }                          // 클래스 통째로 제외
```

애노테이션 이름은 **`@PythonInternal`** 로 한다. Kotlin 의 `internal` 과 의미가 자연스럽게 이어진다.
(`@ExcludeFromPython` 도 후보였으나 길다.)

## 등록 대상

| 대상 | 등록 여부 | 근거 |
|---|---|---|
| `public` 함수 | ✅ | 기본 원칙 |
| `public` 클래스 | ✅ | |
| **생성자** | ✅ | Python 에서 인스턴스를 만들어야 한다 |
| **프로퍼티** | ✅ getter. setter 는 `var` 만 | |
| **companion object 멤버** | ✅ 클래스의 정적 메서드처럼 노출 | companion 객체 자체를 따로 노출할 필요는 없다 |
| `private` / `protected` | ❌ | |
| **`internal`** | ❌ | JVM 바이트코드에서는 name-mangling 된 public 이지만 의미론적으로는 비공개다. KSP 의 `Modifier.INTERNAL` 로 걸러낸다 |
| **확장 함수** | ❌ | 수신자 없이 호출할 수 없고 Python 호출 규약과 맞지 않는다 |

## 라이브러리 자신은 통째로 제외한다

`python.native.ffi`, `python.multiplatform` 등 **이 라이브러리 자체 패키지는 KSP 설정의 exclude
목록으로 통째 제외**한다.

`PyObject`, `PyType`, `Python3`, 330개 FFI 바인딩이 전부 테이블에 올라가면 Python 에서 다시 부를
이유가 없는 것들로 테이블만 부푼다. 내부 클래스마다 `@PythonInternal` 을 붙이는 것은 비현실적이다.

## Python 으로 매핑할 수 없는 것들

| 대상 | 처리 | 근거 |
|---|---|---|
| **`suspend` 함수** | 자동 제외 | Python 에 대응 개념이 없고, 숨은 `Continuation` 인자가 있어 호출 자체가 불가능하다 |
| **`inline` + `reified`** | 자동 제외 | 컴파일 후 reified 타입 정보가 사라진다 |
| **값 클래스(value class)** | 등록하되 호출 시 실패 허용 | JVM 에서 언박싱된 형태로 노출되어 케이스가 갈린다. 완벽히 걸러내려면 복잡도가 크다 |

`suspend` 와 `reified` 는 KSP 에서 선언 수준으로 판별되므로 걸러내는 비용이 낮다.

## 결정 이력

블랙리스트 원칙은 설계 논의에서 직접 확정됐다. 세부 규칙(가시성, 확장 함수, 매핑 불가 타입, 애노테이션
이름)은 그 논의에서 다루지 않았고, 이후 판단으로 정한 것이다 — 구현하면서 뒤집힐 수 있다.

특히 다음 둘은 재고 여지가 있다:

- **확장 함수 제외** — 수신자를 첫 인자로 받는 Python 함수로 매핑하는 것은 원리적으로 가능하다. 지금은
  단순함을 택했다.
- **값 클래스** — "등록하고 실패하게 둔다"는 사용자에게 불친절하다. 실제 사용 양상을 보고 다시 정한다.
