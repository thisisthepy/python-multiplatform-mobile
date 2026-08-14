# `pythonx` — adapting generated Kotlin bindings without wrapping them one at a time

The 2024 `pythonx-compose` wrote a Python class per Compose component. 37 files, 28 of them empty,
and the one that mattered most — `pythonx/compose/ui/modifier.py` — is a copy of the Button wrapper
in which `padding()` composes nothing and `fill_max_size()` returns `self`. The chain was never
built. That is the cost this document exists to remove.

`docs/ecosystem.md` §5b already decided the shape: `pythonx.*` is ours, `androidx.*` is the original
Kotlin, the wrapping happens in Python, and the layer *adapts generically* rather than enumerating.
This file works out what "generically" has to mean given what the runtime actually offers today, and
names the places where it does not offer enough.

Everything under **Read** was read from the working copies on 2026-08-14 and the file is named.
Everything under **Measured** was produced by running a tool and the command is given. Everything
under **Judged** is a decision with its alternatives named. Everything under **Open** is not decided,
and no sentence in this file fills one of those in.

Sources read for this document:

    PythonMultiplatform  python-multiplatform/src/commonMain/kotlin/python/multiplatform/ffi/upcall/PythonProxySource.kt
                         python-multiplatform/src/commonMain/kotlin/python/multiplatform/ffi/upcall/UpcallTrampoline.kt
                         python-multiplatform/src/commonMain/kotlin/python/multiplatform/reflection/{ExposedCallable,ReflectedClass,ObjectReference}.kt
                         python-multiplatform-gradle-plugin/src/main/kotlin/python/multiplatform/gradle/artifact/{ArtifactScanner,ArtifactRendering,JvmDescriptors,KotlinMetadata}.kt
                         docs/ecosystem.md §5b, docs/kotlin-extensions-in-python.md
    pythonx-compose      UI.ipynb (all 57 cells), pythonx/compose/runtime/__init__.py,
                         pythonx/compose/material3/{buttons,cards,text}.py, pythonx/compose/ui/{__init__,modifier,alignment}.py,
                         pythonx/compose/layout/{__init__,arrangement}.py, pythonx/compose/wrapper/__init__.py

---

## 1. Read — what the 2024 code did, and the part of it that cannot have run

Two conventions coexist in `pythonx-compose`, and they disagree.

**The library source is strict snake_case with a hand-written map back to camelCase.**
`pythonx/compose/material3/text.py` declares `font_size`, `letter_spacing`, `on_text_layout`,
`soft_wrap`, `max_lines`, `min_lines` and then rebuilds a kwargs dict spelling them `fontSize`,
`letterSpacing`, `onTextLayout`, `softWrap`, `maxLines`, `minLines`. `buttons.py` does the same for
`on_click`, `content_padding`, `interaction_source`, five times over — the five button variants are
five copies of one 60-line body differing only in the name they look up.

**The notebook is not that code.** `docs/ecosystem.md` §3 established that `UI.ipynb` runs against
PyREPL's hand-written Kotlin shim, and the parameter names confirm it independently: the notebook
writes `onclick`, the library writes `on_click`. **The library wins**; see §3's naming decision.

The call these wrappers make cannot reach real AndroidX:

```python
self.__kotlin_composable(**kwargs, c=self.composer, changed=1)
```

`c` and `changed` are the names PyREPL's `Material3.kt` gave its own explicit parameters. A real
Compose composable's synthetic parameters are named `$composer` and `$changed`, which are not Python
identifiers, so no keyword call can address them. Whatever `pythonx/compose/material3/*.py` was
tested against, it was not `androidx.compose.material3`. (Read, not run — no chaquopy runtime was
available here.)

One more piece is worth keeping. `pythonx/compose/wrapper/__init__.py` is four lines:

```python
class KotlinWrapper:
    def __new__(cls, kotlin_object):
        kotlin_object.__class__ = cls
        return kotlin_object
```

It reclasses a live Java object onto a Python class to give it methods. That trick is chaquopy-only
and does not survive the move to this repository's binder — a Kotlin object reaches Python as an
`ObjectReference` integer (`HandleTable`), and an `int` has no assignable `__class__`. Every place
`alignment.py` and `arrangement.py` use it needs a different mechanism.

---

## 2. Read — what `PythonProxySource` already does, and what it does not

### 2.1 Injection into `sys.modules` is why no import hook is needed *after* `install()`

`_pm_module(name)` builds a `types.ModuleType`, puts it in `sys.modules` under the full dotted Kotlin
name, and recursively creates and links its parents. CPython's import machinery answers a
`sys.modules` hit before it consults any finder, so `from fixture.library import greet` works with no
`sys.meta_path` entry at all.

Entry names come from the producers as Kotlin fully-qualified names — `ArtifactScanner`
builds `"$owner.${function.kotlinName}"`, where `owner` is the *package* for a file facade or
multi-file part. So `androidx.compose.material3.Text` is the name a bound Compose composable would
carry, and `import androidx.compose.material3` is what it makes importable. That is
`docs/ecosystem.md` §5b's "a Kotlin FQN means the original Kotlin", already implemented.

### 2.2 Judged — what the `pythonx` prefix adds: nothing, and that is the point

`pythonx` is an ordinary Python package. It does not need `sys.modules` injection, a finder, or any
generation, because it is source we write. The two namespaces do not mix (§5b), and mechanically they
do not even use the same publication route: `androidx.*` is injected, `pythonx.*` is imported.

What `pythonx` *does* need from `PythonProxySource` is that the Kotlin FQN it delegates to is
reachable at the moment `pythonx` asks. Which is §2.3.

### 2.3 Judged — laziness needs two mechanisms, and the existing measurement does not forbid either

`PythonProxySource.install()` is eager and whole-table: `render` walks every entry, emits one
`_pm_h_N = _pm_lookup(...)` and one `def` per entry, and the caller `exec`s the lot. For the fixture
tables this repository has, that is right. For Compose it is not: `docs/kotlin-extensions-in-python.md`
§2.1 counts 1,411 public top-level functions in the desktop corpus alone, and §2.6 counts 500 public
top-level composables. Every one would be a `_pm_resolve` string lookup and a Python function object
built before the first frame renders. (The install cost has **not** been measured — `GeneratedProxyCostTest`
prices per-call and per-read, not install. That measurement is missing.)

Two different things have to become lazy, and they need different hooks:

| what | hook | why the other one cannot do it |
|---|---|---|
| the module `androidx.compose.material3` existing at all | `sys.meta_path` finder | `import x.y.z` fails before any attribute is touched, so a module `__getattr__` never runs |
| the attribute `Text` inside it | module `__getattr__` (PEP 562) | a finder runs once per module; it cannot defer the 200 names inside one |

**The 551–587 ns figure in `PythonProxySource`'s KDoc does not argue against this.** That measurement
is about a `__getattr__` that answers *every* read of a live Kotlin property, where the raised-and-discarded
`AttributeError` is paid per access. A lazy binder's `__getattr__` fires once, `setattr`s the result into the
module dict, and is never consulted for that name again — every later read is an ordinary dict hit. The
descriptor-versus-hook decision recorded there is about live properties and should not be transplanted.
(Judged from the mechanism; the one-time cost of a lazy `__getattr__` on this repository's own boundary
has not been measured.)

### 2.4 Read — the metadata the Python side cannot obtain

This is the finding that constrains everything in §4. The two names a host must bind are
`_pm_resolve(name_bytes) -> handle` and `_pm_invoke(handle, args_tuple) -> result`. That is the whole
Python-facing surface. `_pm_resolve` answers with an integer; there is no way to ask anything *about*
a declaration.

And `ExposedCallable` does not carry it either:

| what an adapter needs | in `ExposedCallable`? | where it is lost |
|---|---|---|
| parameter **names** | **no** — the class has `name`, `arity`, `paramTypes`, `returnType`, `kind`, `isSuspend`, `callable` | never collected; `kotlin-metadata-jvm` has `KmValueParameter.name`, KSP has it too |
| whether a parameter is an **extension receiver** | **no** | `ResolvedFunction.isExtension` exists and is used to pick the call syntax, then dropped — `ArtifactCallable` has no such field |
| the receiver's **type** | **no** | same place |
| the declared **type name** of a parameter (`Dp` vs `Color`) | **no** — collapsed to a `TypeTag` | `resolveKotlinType` resolves a value class to its *underlying* `BoundaryType`, so a `Dp` parameter is `TypeTag.FLOAT` |
| whether a parameter **has a default** | **no** | `declaresDefaultValue` is readable from metadata and is not read |
| the **overload set** for a name | **no** | `ArtifactScanner` drops a name entirely when more than one binding would carry it (`groupBy { it.name }.filterValues { it.size == 1 }`) |

Consequences, in order of how much they hurt:

1. **`Text(text=..., color=...)` cannot be assembled.** Mapping keyword arguments onto positional
   slots requires parameter names, and nothing on either side of the boundary has them. This is not a
   `pythonx` design choice — it is arithmetic. Without parameter names the Pythonic API is positional
   only, which for a 16-parameter composable is not an API.
2. **"An extension function becomes a method on its receiver's proxy"** (`kotlin-extensions` §4.1)
   has no input. The table cannot say which entries are extensions or on what.
3. **The value-class allowlist** (`kotlin-extensions` §4.4) cannot be enforced in Python, because
   Python cannot see that a parameter is a `Dp` rather than a `Float`. See §4.4 — it turns out the
   allowlist mostly does not need to be enforced there.

### 2.5 Open — where `pythonx`'s own `.py` files live on iOS, androidNative and wasm

`PythonProxySource`'s KDoc gives, as its first reason for rendering Python at run time rather than
shipping a `.py`, that **there is no resource path to put a `.py` on**: Kotlin/Native has no
`getResourceAsStream`, and `sys.path` on iOS, androidNative and wasm points into the per-platform
CPython trees under `src/nativeInterop/cinterop/lib/…`. Getting a generated file into each of those
and onto `sys.path` before the first import is called "a per-platform packaging problem this
repository has not solved for anything."

`pythonx` is exactly that problem, one size larger: not one generated file but a package of
hand-written ones. `grep` finds no `sys.path` manipulation and no site-packages staging anywhere in
the Kotlin sources; the only hits are benchmarks reading `sys.path` as a test fixture.

Candidates, none chosen and none tried:

- `pypackpack`'s `bundle` type `resource`, which its SPEC defines as the handoff format to this
  repository — and which `docs/ecosystem.md` §4 item 5 records as a three-line placeholder.
- Freezing `pythonx` into the CPython trees the way the stdlib already is.
- Embedding the source as Kotlin string constants and `exec`ing it, i.e. the route
  `PythonProxySource` took, applied to hand-written code. This works everywhere today and is
  hostile to editing, diffing and shipping a package to PyPI, which `pyproject.toml` says
  `pycomposeui` is meant to do.

Until one exists, `pythonx` is a desktop-and-Android library whatever else this document decides.

---

## 3. Measured — the name conventions `UI.ipynb` actually uses

Every identifier in the notebook's 57 cells, classified. This is the empirical basis for §4.1's rule,
and it is worth reading before assuming "snake_case everything".

| Kotlin | notebook | class |
|---|---|---|
| `Text`, `Button`, `Card`, `Column`, `Row`, `Spacer`, `TextField`, `Icon` | same | composable → **PascalCase kept** |
| `Modifier`, `Alignment`, `Arrangement` | same | type → **PascalCase kept** |
| `Alignment.Horizontal.End`, `Arrangement.Center` | same | nested type / constant → **PascalCase kept** |
| `rememberSaveable` | `remember_saveable` | free function → **snake_case** |
| `fontSize` | `font_size` | parameter → snake_case |
| `contentDescription` | `content_description` | parameter → snake_case |
| `verticalArrangement`, `horizontalArrangement`, `horizontalAlignment` | `vertical_arrangement`, `horizontal_arrangement`, `horizontal_alignment` | parameter → snake_case |
| `onClick` | `on_click` in `pythonx`, **`onclick`** in the notebook | parameter → snake_case; **`pythonx` is the reference, not the notebook** |
| `State.getValue` / `setValue` | `getValue()` / `setValue()` | method on a Kotlin object → **camelCase kept** |
| — | `corner_radius`, `text_state` | **invented**; `material3.Card` has no `corner_radius` in either overload |

Five Kotlin-derived multi-word parameters are snake_case; one (`onclick`) is not. The library source
in `pythonx-compose` spells that same parameter `on_click` in all five button wrappers.

**Decided: `pythonx` is the reference, and the notebook is not.** `on_click` is what the rule
produces and what the library already writes, so `onclick` is not a spelling to support, alias or
carry forward — it is one cell of a teaching notebook disagreeing with the library it teaches. A
table derived from `pythonx` needs no exception list here, which is the point: an exception that
exists only in prose is one the generator and the adapter cannot both honour.

**Judged — the rule.**

    type, object, enum entry, composable          PascalCase, unchanged
    function, method, property, parameter         snake_case
    package segment                               unchanged (already lowercase)

`getValue`/`setValue` are *not* grandfathered: they are ordinary methods and become `get_value`/
`set_value`. Keeping them would mean the rule is "snake_case except on objects that came back from
Kotlin", which is a rule a reader has to know the provenance of a value to apply.

The composable exception is not a special case for Compose. A `@Composable` is PascalCase because it
is a *declaration of a UI element*, and both Kotlin and the notebook treat it as a type-like name;
PEP 8 has no opinion that outranks matching the documentation of the library being wrapped. The cost
is that `pythonx` has two casing rules keyed on a property of the declaration, and the adapter must
know which — `@Composable` is an annotation the walker can read, so this is answerable, but it is one
more thing the table would have to carry (§2.4).

**Judged — the conversion runs Kotlin → Python, and the runtime runs it backwards.**

The `.pyi` generator (plugin, build time) converts Kotlin → Python: it has the Kotlin name and emits
the Python one. The adapter (runtime) is handed the Python name by the caller and must find the
Kotlin one. Those are opposite directions of one function, and snake→camel is not injective in
general (`URLDecode`, `zIndex`, a Kotlin name that already contains an underscore).

Two ways to close it, and the second is the recommendation:

- **Rule-based reverse.** `__getattr__` converts `fill_max_width` → `fillMaxWidth` and calls
  `_pm_resolve` once. Cheap, no enumeration, `dir()` returns nothing useful, and irregular names fail.
- **Rule-based forward, at build time, plus a generated name map for the exceptions.** The plugin
  already walks every declaration to emit stubs; it can detect the cases where the forward rule is
  not invertible and emit only those into a small map the adapter consults on miss.

Whichever is chosen, there is a checkable invariant that should be a test: **every name the `.pyi`
generator emits must resolve through the adapter's reverse rule.** If it does not, the stub promises
an API the runtime does not have, which is the failure mode that makes stubs worse than none.

---

## 4. Judged — the adaptation layer

### 4.1 What a module `__getattr__` looks up and what it caches

```python
# pythonx/_adapt.py — one implementation, no per-declaration code
def __getattr__(name):                      # in pythonx/compose/material3/__init__.py
    kotlin = _kotlin_name(name, _MODULE)    # 'androidx.compose.material3.Text'
    obj    = _adapt(kotlin)                 # resolve + build the callable
    globals()[name] = obj                   # never consulted again for this name
    return obj
```

Three things are cached, at three lifetimes:

| cached | key | lifetime |
|---|---|---|
| the adapted callable | Python name, in the module dict | until interpreter finalisation |
| the handle from `_pm_resolve` | inside the closure | same |
| the receiver-method table for a proxy type | Kotlin receiver type name | built once per type, see §4.2 |

The `pythonx.compose.material3` module is a **facade over `androidx.compose.material3`**, not a
mirror of it: names it does not adapt fall through to `AttributeError` and the Kotlin FQN import is
always available as an escape hatch. That is the property that keeps `pythonx` from having 37 files.

### 4.2 Attaching an extension function as a method on its receiver

`kotlin-extensions` §4.1 adopted this and it is right: each of the 177 public top-level `Modifier`
extensions returns `Modifier`, so `Modifier.padding(16).background(...)` is ordinary Python method
chaining with no combinator machinery.

The mechanism in Python is unremarkable — a proxy class per receiver type, and a `__getattr__` on
*its type* that resolves `receiver_type + '.' + kotlin_name`, binds, and installs the method on the
class so the second instance pays nothing.

The mechanism in Kotlin is missing (§2.4 rows 2 and 3). The adapter is handed
`androidx.compose.foundation.layout.padding` with `arity = 2` and `paramTypes = [OBJECT, FLOAT]`, and
nothing tells it that slot 0 is a receiver of type `androidx.compose.ui.Modifier` rather than an
ordinary first parameter. **Something has to carry `isExtension` and the receiver's Kotlin type name
across the boundary before §4.1 can be built at all.** `ArtifactScanner` has both at the moment it
constructs the call expression and discards them one line later.

### 4.3 The `Modifier` metaclass

`kotlin-extensions` §4.2 established the shape and the reason: `Modifier$Companion` *implements*
`androidx.compose.ui.Modifier`, so one Python class whose class object also behaves like an instance
covers all three spellings — `Modifier.padding(16)`, `m.padding(16)`, `def f(m: Modifier)`.

Two additions from reading the 2024 code:

- **It replaces `from pythonx.compose.ui import Modifier, modifier`.** The notebook imports a
  lowercase `modifier` instance beside the class precisely because `Modifier()` and `Modifier` were
  two different things there. The metaclass removes the second name, and removing it is the point:
  `modifier` as a shared module-level mutable-looking singleton is a trap in a UI library.
- **It is generic, not `Modifier`-specific.** The same metaclass shape applies to any Kotlin
  interface whose companion implements it. Whether any *other* Compose type has that shape was not
  counted.

### 4.4 Value classes — the coercion already happens, and it happens in Kotlin

**Read.** `resolveKotlinType` (`KotlinMetadata.kt`) resolves a value-class parameter to the
`BoundaryType` of its *underlying* type, and `valueClassBoundaryType` renders the read as
`androidx.compose.ui.unit.Dp((args[0] as Double).toFloat())`. So on the parameter side the wrapping is
already generated Kotlin, and `TypeTag` for a `Dp` parameter is `FLOAT`.

This changes `kotlin-extensions` §4.4's conclusion less than it changes where it is enforced:

- **A raw Python number reaching a `Dp` parameter is already correct**, with no Python-side rule, and
  `Modifier.padding(16)` needs nothing built for it.
- **`TextUnit` and every `packedValue` class is not a hazard on this path — it is unbound.** Their
  constructors are `INTERNAL` (`kotlin-extensions` §2.4), and `resolveKotlinType` returns `null` for a
  parameter whose value class has no public constructor. The silent-`Unspecified` failure §4.4 warns
  about cannot occur through a walked binding, because there is no walked binding.
- **`Color` is unbound too, for a second reason.** Its constructor is public but takes `ULong`, and
  `kotlinPrimitiveBoundaryTypeOf` has no `kotlin/ULong` entry, so the underlying type resolves to
  `null`. The "constructor is public" test that §4.4 worried would admit `Color` does not admit it
  here. (Read from source. Not re-run over Compose — `kotlin-extensions` §3 already reports the
  walker binds zero from these jars, for reasons upstream of this.)

**Judged.** The allowlist stays a build-time concept, not a Python one. The Python side gets a
`dp(16)`-style proxy only where a *proxy object* is genuinely needed — that is, where a value class
appears in **return** position, or where a Python user wants to hold one — and unwraps it to the
underlying primitive before the call. The asymmetry §4.4 accepted (`padding(16)` fine,
`font_size=16` rejected) arrives for free: `padding` binds and `Text`'s `fontSize` does not.

The residual risk §4.4 names — a future Compose release adding a public-constructor float wrapper
that packs — is unchanged and still lives at the plugin.

### 4.5 Open — defaults, and why they are the load-bearing blocker

**Measured** (`javap -p` over `material3-desktop-1.6.11.jar` and `foundation-layout-desktop-1.6.11.jar`
from the Gradle cache, i.e. `kotlin-extensions-in-python.md`'s corpus A):

| function | Kotlin params | required params |
|---|---|---|
| `material3.Text` | 16–18 | 1 |
| `material3.Button` | 10 | 2 (`onClick`, `content`) |
| `material3.Card` (container) | 6 | 1 (`content`) |
| `foundation.layout.Column` | 4 | 1 (`content`) |

`kotlin-extensions` §2.2 counts the same property across the `Modifier` surface: **254 of 435
parameters (58%) declare a default**, and 82 of the 177 functions have at least one.

A Python caller writing `Text("hi", color=red)` is skipping fourteen parameters, and the values it
would have to supply instead are `TextStyle.Default`, `TextDecoration` and friends — objects the type
gate does not bind, so Python cannot construct them to pass them. **"Supply every argument" is not
available.** Defaults are therefore not an ergonomic nicety here; without them the bound surface is
unusable even where it binds.

`ExposedCallable` has one `arity` and one `callable`. What could close the gap, none verified:

| candidate | why it might not work |
|---|---|
| one entry per **arity prefix** (Kotlin defaults are trailing, so calling with the first *k* arguments is always legal) | *N*+1 entries instead of 2^*N*, and it cannot express "pass `text` and `font_size`, skip `modifier` and `color`", which is how Compose is written |
| **exhaustive presence branching** in the generated lambda | 2^15 branches for `Text` |
| calling the compiler's **`$default` synthetic** from generated Kotlin | it is `ACC_SYNTHETIC`, so Kotlin source cannot name it; and `javap -p` over `ButtonKt` shows **no `Button$default` exists at all** — a `@Composable` carries its `$default` mask as a declared trailing parameter instead (§5.2), which is a different mechanism |
| a **generated Kotlin wrapper per function** that restates the defaults | the defaults are expressions in the callee's scope; metadata carries the *flag*, never the value (`kotlin-extensions` §4.5) |

The `@Composable` case may be luckier than the general case precisely because of §5.2 — the mask is a
real parameter there. Whether that is reachable is §5.4.

---

## 5. `@Composable`

### 5.1 Read — what the 2024 `Composable` class does

`pythonx/compose/runtime/__init__.py` is a decorator class holding a single `__composer` registered
from Kotlin (`ComposeApp.__init__(composer)`), and `__call__` refuses to run if it is unset:

```python
if self.composer is None:
    raise RuntimeError("Composer for Composable must be registered before Composition starts.")
```

It then inspects `self.compose.__code__.co_varnames` to find where `content` sits, pops it from args
or kwargs, wraps a raw `ComposableLambdaImpl` in `KotlinComposable` if that is what arrived, and calls
through. `KotlinComposable.compose` is the interesting line:

```python
ComposableWrapper(self.content, args, self.composer, 1)
```

`ComposableWrapper` is `io.github.thisisthepy.pycomposeui.RuntimeKt.composableWrapper` — a
hand-written Kotlin function that takes a `ComposableLambda`, the argument list, a `Composer` **as an
ordinary value**, and a `changed` int. That is the shape `docs/ecosystem.md` §5b describes as "the
Python wrapper passes the composer as a value".

So the 2024 design has exactly one composer for the whole application, set once, and every composable
call uses it with `changed = 1`. Whether that produces correct recomposition was not verified here
and is not obviously true — `$changed` is a per-call-site bitmask about which arguments are known
static, and a constant `1` tells the runtime something specific about slot 0.

### 5.2 Measured — the synthetic parameters, and a correction to `kotlin-extensions` §2.3

`javap -p`, corpus A jars, top-level parameter counts split at the top level (generics ignored):

| function | Kotlin params | JVM params, counted here | JVM params, `kotlin-extensions` §2.3 |
|---|---|---|---|
| `Text-fLXpl1I` | 16 | **20** | 19 |
| `Text--4IGK_g` (String) | 17 | **21** | 20 |
| `Text--4IGK_g` (AnnotatedString) | 17 | **21** | 20 |
| `Text-IbK3jfQ` | 18 | **22** | 21 |
| `Button` | 10 | **13** | 12 |
| `Card` (clickable) | 9 | **12** | 11 |
| `Card` (container) | 6 | **9** | 8 |
| `Column` | 4 | **7** | 6 |
| `Row` | 4 | **7** | 6 |

Every one of the nine rows is one higher than recorded. The trailing parameters of `Button` are
`Composer, int, int` and of `Text-fLXpl1I` are `Composer, int, int, int` — that is `$composer`, one or
two `$changed` masks, and **`$default`**. The distribution in §2.6 (`+2: 80, +3: 349, +4: 62, +5: 9`)
was not re-derived; if it is shifted the same way, `+2` would mean "composable with no defaulted
parameter" and the nine functions above would all be `+3` or `+4`, which is what was measured. The
design conclusion §4.6 draws — *never derive arity from the descriptor; metadata arity is the source*
— is unaffected and strengthened.

Reproduce with:

    unzip -o -q "$GRADLE_CACHE/.../material3-desktop-1.6.11.jar" 'androidx/compose/material3/ButtonKt.class'
    javap -p androidx/compose/material3/ButtonKt.class

### 5.3 Read — the contradiction inside `docs/ecosystem.md`

§4 item 1 says: *"`@Composable` cannot be an exposed callable … a generated entry for a widget would
not compile … This is the smallest and most blocking item."*

§5b says: *"a `@Composable` function is, after compilation, an ordinary function taking `$composer`
and `$changed`; a binding generated from the artefact exposes that signature as it is … So nothing on
the Kotlin side needs a `@Composable` callable type."*

**§5b's resolution does not hold given how bindings are produced.** The workers generate *Kotlin
source* and let `kotlinc` compile it (agent-rules §12, and `ArtifactRendering.renderEntry` shows the
generated lambda body verbatim). Kotlin source cannot:

- call a `@Composable` from a non-`@Composable` lambda — the Compose plugin rejects it; and
- name `$composer`, `$changed` or `$default`, which are not parameters of the Kotlin declaration at
  all. They exist only in the compiled form, which is the form §5b's argument reasons about and the
  form the generator does not emit.

Both facts are properties of the generator, not of Compose, which is why §5b's reading of the
bytecode is correct and its conclusion still does not follow. The 2024 code got away with it because
chaquopy calls *JVM methods by name and descriptor*, where the synthetic parameters are ordinary
arguments — the very approach `kotlin-extensions` §2.3 retires, since 108 mangled JVM names are
ambiguous within their own class.

### 5.4 Open — the one candidate that might reconcile them

`androidx.compose.runtime.internal.composableLambdaInstance` builds a `ComposableLambda` from a
`@Composable` block, and `ComposableLambda.invoke(p1, composer, changed)` is an ordinary
non-composable method. That is precisely the door `RuntimeKt.composableWrapper` walked through in
2024. So a generated fragment could, in principle, be:

```kotlin
private val bindButton = composableLambdaInstance(/* key */ 0, /* tracked */ true) { args: Array<Any?> ->
    androidx.compose.material3.Button(onClick = { … }, modifier = args[1] as Modifier, …)
}
// the ExposedCallable stays `(Array<Any?>) -> Any?`:
{ args -> bindButton.invoke(args, args.last() as Composer, 1) }
```

which would keep `ExposedCallable.callable` unchanged, let `kotlinc` supply `$composer`, `$changed`
**and `$default`** at a real call site — closing §4.5 for composables specifically — and let Python
pass the composer as a value exactly as §5b says.

**Not verified. Nothing here was compiled.** What has to be checked, in order:

1. That `composableLambdaInstance` is callable from generated code at all — it is in an `internal`
   package and annotated `@ComposeCompilerApi`; whether that is an opt-in warning or a hard error for
   a consumer's generated source is unknown.
2. That the group key can be generated stably. Compose identifies slots by key; a colliding or
   changing key across builds corrupts the slot table rather than failing.
3. That `changed = 1` is not simply wrong. It is a bitmask over argument staticness, and a constant
   is a claim about arguments the generator has not inspected. The conservative value (`0`, meaning
   "nothing known") is the thing to try first.
4. That an `Array<Any?>` parameter does not defeat skipping. Compose skips a composable when its
   arguments compare equal; a fresh array per call never does, so **every Python-driven composable
   would recompose on every frame regardless of whether anything changed.** This is a performance
   property, measurable, and it should be measured before the shape is adopted.

Until (1) is answered, `docs/ecosystem.md` §4 item 1 remains the blocking item it says it is, and
§5b's paragraph asserting otherwise should be treated as unresolved rather than as settled.

### 5.5 Judged — arity is where the real risk sits, and it is not a collision risk

`kotlin-extensions` §2.6 measured that of 500 composables, 41 are extension functions and exactly one
is a `Modifier` extension. Composables and chainable modifiers are two disjoint problems, so the
adapter can treat them as two code paths with one shared name resolver and no arbitration between
them. The `Modifier`-extension composable (`ui.ExternalDrag_desktopKt.onExternalDrag`) is a single
name and can be excluded by name if it is ever in the way.

---

## 6. Open — the lifetime of a Python callable across recomposition

`docs/ecosystem.md` records this as unverified. This section says what "verify" means, because the
mechanism turns out to be half-present already.

**Read — a Python callable can already cross into Kotlin.** `UpcallTrampoline.toKotlinObject` handles
`TypeTag.OBJECT` in the *parameter* direction by testing whether Python sent an integer: if it did,
it is a `HandleTable` handle for a Kotlin object; if it did not, the value is wrapped as
`PyObject(value, borrowed = true)` and the wrapper takes its own reference. So a `content=lambda: …`
reaches a Kotlin parameter declared `PyObject` today, with a reference of its own.

**Read — the walker will not produce such a parameter.** `resolveKotlinType` has no `OBJECT`
fallback, so an artefact-walked binding can never take one; only a KSP-generated entry from the
consumer's own Kotlin can (KSP has `isPyObjectType`). A `content:` parameter on a walked
`androidx.compose.material3.Button` is therefore unreachable regardless of everything else in §5.

**What has to be verified, and none of it has been.** Compose stores a lambda in the slot table and
calls it again on a later recomposition, possibly many frames later. That crosses three boundaries
this repository has measured separately and never together:

1. **Release.** When Compose drops the slot, does anything tell the Kotlin holder? `RememberObserver.onForgotten`
   is the only hook that reports it. A `PyObject` held by an object that does not implement it is
   leaked for the life of the process — the same class of defect
   `ProxyHandleLifetimeTest` was written for, in the opposite direction (Kotlin object held by
   Python). A test of the same shape is needed: compose, drop, recompose, assert the Python refcount
   came back.
2. **The GIL on the release path.** `Py_DecRef` requires the GIL. `onForgotten` runs on whatever
   thread Compose is applying changes on. Whether that thread holds the GIL — and if not, whether the
   decref can be queued to one that does — is not answered anywhere in `docs/object-lifetime.md` or
   `docs/gil-parking-investigation.md` for this direction.
3. **Re-entrancy.** The call is Kotlin composition → Python callable → Kotlin composable → Python
   `content` lambda. The GIL is held across a Kotlin composition that may itself block or hop
   threads. `docs/gil-parking-investigation.md` exists because this repository has already been bitten
   by GIL parking; this is a new place to be bitten.
4. **Identity across recomposition.** A Python `lambda` is a new object every time the enclosing
   Python function runs, so `equals` is false and Compose cannot skip. Combined with §5.4 item 4, the
   likely outcome is that nothing ever skips. Whether that is tolerable is a measurement, not an
   opinion, and the measurement does not exist.

Item 1 is the one that fails silently and should be tested first.

---

## 7. Judged — what is Python code and what is a generated artefact

The dividing rule: **if it differs per Kotlin declaration it is generated or resolved at run time; if
it is the same rule for every declaration it is `pythonx` Python source.** Everything the 2024 attempt
got wrong is on the wrong side of that line — a file per component is per-declaration code written by
hand.

| product | who makes it | when | where it lives | consumer |
|---|---|---|---|---|
| `FunctionTableFragment` (consumer's own source) | KSP processor | build | generated Kotlin, compiled into the app | `UpcallTable` |
| `ArtifactTable` fragments (resolved jars/klibs, AndroidX included) | Gradle plugin artefact walker | build | generated Kotlin, compiled into the app | `UpcallTable` |
| `.pyi` stubs, carrying the Pythonic names §3 produces | Gradle plugin (long term: `toolchain`) | build | `src/<sourceSet>/generated/meta/` | the IDE only — never imported |
| proxy modules under Kotlin FQNs (`androidx.compose.material3`) | `PythonProxySource.render` | run time, `exec` | `sys.modules` | `pythonx`, and any user who wants the original |
| `_pm_settle` / `_pm_watch` / `_pm_module` / `_pm_static_property` support | `PythonProxySource.support` | run time, `exec` | `__main__` | the generated proxies |
| `_pm_resolve` / `_pm_invoke` | per-platform bootstrap (Kotlin/C) | run time | `__main__` | everything above |
| **`pythonx/`** — name conversion, module `__getattr__`, receiver-method attachment, the `Modifier` metaclass, value-class proxies, the composable call convention, the `content=` adapter | **written by hand, once** | — | a shipped Python package (§2.5: no route on 3 of 5 targets) | the application author |

Two consequences worth stating explicitly.

**`pythonx.compose.material3` must not be a file per component.** It is a module with a
`__getattr__` and a package name. If a future `pythonx` grows `buttons.py`, `cards.py`, `text.py`,
the design has failed in exactly the way the 2024 one did, and the count of empty files is the
symptom to watch for.

**`pythonx` may contain genuinely non-mechanical code, and that is where the line actually bends.**
The notebook needs `main.App`, `App.update(NewComposable)` for hot-swapping a composable from a
Jupyter cell, and `App.messages` as live state. None of that is a mapping of a Kotlin declaration —
it is an application-lifecycle facility. It belongs in `pythonx` as hand-written Python (over a small
hand-written Kotlin surface), and it should be kept visibly separate from the adapter so that "wrote
a wrapper by hand" stays a visible event rather than a habit.

---

## 8. Open

Collected, in the order that unblocks the most.

1. **Parameter names across the boundary** (§2.4). Nothing keyword-based is possible without them.
   Either extend `ExposedCallable`, or emit a side table with one more bootstrap entry point; the
   trade is per-entry footprint on five targets against a second thing that can disagree with the
   first. Not decided.
2. **`isExtension` and the receiver type across the boundary** (§2.4, §4.2). `ArtifactScanner` has
   both and drops them. Without them `kotlin-extensions` §4.1 has no input.
3. **Defaults** (§4.5). Four candidates, all with a named reason they may not work. This is the item
   that decides whether a bound Compose surface is usable at all.
4. **`composableLambdaInstance` as the composable call route** (§5.4), and the four checks it needs.
   Answering (1) of those four resolves the contradiction in §5.3 one way or the other.
5. **Where `pythonx`'s `.py` files live on iOS, androidNative and wasm** (§2.5). Today: nowhere.
6. **Python-callable lifetime across recomposition** (§6), starting with `RememberObserver.onForgotten`.
7. **Install cost** (§2.3). `PythonProxySource.install()` has never been measured, on any table size.
   The argument for laziness is currently structural, not measured.
8. **`kotlin-extensions` §2.3's JVM parameter column** (§5.2) is one low on all nine rows; whether
   §2.6's `+2/+3/+4/+5` distribution shares the error was not re-derived.
9. **Scoped modifiers** — inherited unchanged from `kotlin-extensions` §6. 74 public member
   extensions on `Modifier` need a dispatch receiver Python has no implicit form for. No shape chosen.
10. **Overload dispatch** — inherited unchanged. The walker currently drops 33 of 130 `Modifier`
    names. A Python-side dispatcher needs (1) to exist first, since it selects on argument names.
