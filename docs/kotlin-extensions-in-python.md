# Kotlin extension functions, value classes, and the Python surface

Compose is the first library whose API this binder has to reach that is *mostly* extension
functions over value-class parameters. `Modifier.padding(16.dp)` is the shape, and neither half of
it — the extension receiver nor the `Dp` — survives into JVM bytecode as itself.

This file records what the Compose artefacts actually contain, what the artefact walker binds from
them today, and what the Python surface should look like. Everything under "Measured" was counted
from real jars on 2026-08-14 and the commands are reproducible from §1. Everything under a
"Judged" heading is a decision, and the alternatives that were rejected are named.

---

## 1. What was measured, and with what

The build pins Compose Multiplatform **1.6.11** (`gradle/libs.versions.toml`, `compose-plugin`),
so the primary corpus is the 19 `org.jetbrains.compose.*` desktop jars the Gradle cache had
resolved. As a cross-check the same tool was run over the 19 `classes.jar` extracted from the
Jetpack AAR set the cache also holds (`androidx.compose.ui` 1.6.7, `foundation` 1.6.7,
`material3` 1.2.1).

    corpus A   19 jars   org.jetbrains.compose.*  1.6.11 (desktop)      51,605 JVM methods
    corpus B   19 jars   androidx.compose.*       1.6.7 / 1.2.1 (AAR)

The tool is ASM 9.9 (already in the cache, the version the plugin uses) plus
**`kotlin-metadata-jvm` 2.0.20**, which was *not* in the Gradle cache and was fetched from Maven
Central. Metadata is what makes the counts trustworthy: it gives the Kotlin name, the Kotlin
visibility, whether a parameter is an extension receiver, the unerased parameter types, and
whether a parameter has a default. None of those are recoverable from descriptors, which is the
whole point of this document. Scratch tree: `/Volumes/macMini/tmp/composescan`.

The current worker's numbers in §3 are **not** a re-implementation of its filter. The repository's
own `ArtifactScanner.kt`, `JvmDescriptors.kt` and `ArtifactRendering.kt` were copied out
unmodified, compiled standalone with `kotlin-compiler-embeddable` 2.0.20 and run over the same
jars. Sanity check that the harness works: the same binary returns 9 entries from
`kotlin-stdlib-2.0.20.jar` (all `kotlin.jvm.internal` plumbing).

---

## 2. Measured — the size of the surface

### 2.1 Overall

| | corpus A |
|---|---|
| JVM methods | 51,605 |
| Kotlin functions carrying `@Metadata` | 13,721 |
| … public | 9,954 |
| … public **and top-level** (file facade / multi-file part) | 1,411 |
| … public top-level **extension** functions | **604**, over 136 distinct receiver types |
| … of those 604, JVM name is value-class-mangled | **151 (25.0%)** |

### 2.2 `Modifier`

| | corpus A | corpus B |
|---|---|---|
| functions with a `Modifier` extension receiver | 330 | 321 |
| … public | 251 | 252 |
| … internal | 40 | 37 |
| … private | 39 | 32 |
| public **top-level** (the chainable API) | **177** | — |
| … mangled | **52 (29.4%)** | — |
| public **member** extensions (need a dispatch receiver) | 74, over 32 owner classes | — |

The two ecosystems agree to within one function, so the numbers are a property of Compose and not
of one packaging of it.

Within the 177:

- 130 distinct Kotlin names; **33 names carry more than one overload** (max 5).
- 435 parameters, of which **254 (58%) declare a default**; 82 of the 177 have at least one.
- 1 is `inline`; none is `operator` or `infix`.
- Parameter composition: 31 take only primitives/`String` (or nothing), 52 take at least one value
  class, 79 take at least one object or collection, 66 take at least one lambda.

### 2.3 The named composables

Metadata arity excludes the synthetic parameters; the JVM descriptor does not.

| Kotlin | JVM name | mangled | Kotlin params | JVM params |
|---|---|---|---|---|
| `material3.Text(String, …)` | `Text-fLXpl1I` | yes | 16 | 19 |
| `material3.Text(String, …)` | `Text--4IGK_g` | yes | 17 | 20 |
| `material3.Text(AnnotatedString, …)` | `Text--4IGK_g` | yes | 17 | 20 |
| `material3.Text(AnnotatedString, …)` | `Text-IbK3jfQ` | yes | 18 | 21 |
| `material3.Button` | `Button` | no | 10 | 12 |
| `material3.Card` (clickable) | `Card` | no | 9 | 11 |
| `material3.Card` (container) | `Card` | no | 6 | 8 |
| `foundation.layout.Column` | `Column` | no | 4 | 6 |
| `foundation.layout.Row` | `Row` | no | 4 | 6 |

`Text` is mangled because `color`, `fontSize`, `letterSpacing` and `lineHeight` are value classes;
`Button`, `Card`, `Column`, `Row` are not mangled because none of their parameters is.

**`Text--4IGK_g` appears twice in the same class with different descriptors.** That is not an
anomaly — across corpus A, **108 mangled JVM names are ambiguous within their own class**. The
mangling suffix is a hash of the *value-class parameter signature only*, so it is shared by
unrelated functions: `-3ABfNKs` is worn by `padding`, `size`, `width`, `height`, `requiredSize`,
`requiredWidth`, `requiredHeight`, `times` and `surfaceColorAtElevation`, all of which take a
single `Dp`; `-VpY3zN4` is worn by thirteen functions taking two `Dp`s.

This retires an approach rather than complicating it. Any binder that *looks a function up by
name* — Java reflection, a Python `__dict__` sweep, the 2024 `pythonx-compose` probe that scanned
`ButtonKt.__dict__` for keys beginning `"Button-"` — cannot address a Compose function
unambiguously even in principle. This binder does not look functions up by name: it emits Kotlin
source that says `Modifier.padding(16.dp)` and lets `kotlinc` do the mangling. The mangled name
never appears in anything we generate.

### 2.4 Value classes

111 value classes in corpus A. Underlying types: 65 `Int`, 25 `Long`, 3 `IntArray`, 3 `Float`,
3 `Any`, 2 `FloatArray`, 1 `ULong`, 1 `Short`, 7 wrapping another reference type (`Composer` ×2,
`Constraints`, `Shape`, `AtomicReference`, `MutableState`, `Operations`), 1 generic.

The ones that matter, with their real underlying type and the visibility of the constructor that
takes it:

| value class | wraps | field | constructor |
|---|---|---|---|
| `ui.unit.Dp` | `Float` | `value` | **PUBLIC**`(Float)` |
| `ui.unit.TextUnit` | `Long` | `packedValue` | INTERNAL |
| `ui.unit.DpSize` / `DpOffset` / `IntSize` / `IntOffset` | `Long` | `packedValue` | INTERNAL |
| `ui.unit.Constraints` | `Long` | `value` | INTERNAL |
| `ui.geometry.Offset` / `Size` / `CornerRadius` | `Long` | `packedValue` | INTERNAL |
| `ui.graphics.Color` | `ULong` | `value` | **PUBLIC**`(ULong)` |
| `ui.graphics.TransformOrigin` | `Long` | `packedValue` | INTERNAL |
| `ui.semantics.Role` | `Int` | `value` | PRIVATE |
| `ui.text.style.TextAlign` | `Int` | `value` | INTERNAL |

**`Sp` does not exist.** The premise that `Dp` and `Sp` both wrap `Float`, and therefore collide
under raw-number coercion, is wrong twice over. `16.sp` produces a `TextUnit`, and `TextUnit` is a
**packed** `Long`:

    getSp(float) = pack(0x1_0000_0000L, value)      // observed in TextUnitKt bytecode

The high word is the unit tag (`1` = Sp, `2` = Em, `0` = Unspecified) and the low word is the raw
float bits. A raw Python `16` handed to a `TextUnit` parameter therefore has tag `0` and decodes
as `TextUnit.Unspecified` — not "16 of the wrong unit", but *silently no value at all*.

Only **three** value classes in all of Compose wrap a `Float`: `Dp`, `text.style.BaselineShift`,
and `LineHeightStyle.Alignment`. Ambiguity between float-shaped units is a non-problem; packing is
the problem.

`Color` is the same trap in a different place. Its constructor is public and takes `ULong`, but
what a Kotlin author writes is the *factory function* `ColorKt.Color(Int)`, whose whole body is a
32-bit left shift:

    Color(int) = ULong(value) shl 32                // observed in ColorKt bytecode

So `0xFFFF0000` means red **through the factory** and means transparent-black **through the
constructor**. The 2024 notebook's `color=0xFFFF0000` worked only because a hand-written Kotlin
shim (`Material3.kt`) declared `color: Long` and called `Color(color)` on the Kotlin side.

Value classes appearing as parameters of the 177 public top-level `Modifier` extensions, by
occurrence: `Dp` 56, `Role` 11, `Color` 8, `TextUnit` 4, `TransformOrigin` 4, `DpSize` 2,
`BlurredEdgeTreatment` 2, `MarqueeAnimationMode` 1, `CompositingStrategy` 1, `PointerEventType` 1.
**`Dp` alone is 62% of all value-class parameter occurrences on `Modifier`.**

### 2.5 Name collisions — far rarer than assumed, and the first count was wrong

Counted naively over *every* public extension function, 41 `(receiver, name)` pairs appear in more
than one package, which would have made a "require qualification on collision" rule
unaffordable. That count is an artefact. Most of it is **member** extension functions — `Dp.toPx`
is declared inside the `Density` interface, `Modifier.align` inside `RowScope` — which metadata
records again in every implementing class. They are not competing top-level declarations and
Kotlin's import scope never sees them as such.

Restricted to public **top-level** extensions, which is the set Kotlin's import scope actually
governs:

- 507 distinct `(receiver, name)` pairs
- **5** are declared in more than one package:

| receiver.name | packages |
|---|---|
| `Modifier.contextMenuOpenDetector` | `foundation`, `material` |
| `Modifier.minimumInteractiveComponentSize` | `material`, `material3` |
| `Double.times` | `ui.geometry`, `ui.unit` |
| `Float.times` | `ui.geometry`, `ui.unit` |
| `Int.times` | `ui.geometry`, `ui.unit` |

- 11 more pairs are split across two file facades *within* one package, which is an overload set,
  not a collision.

Two of the five are `material` versus `material3`, which an application does not mix. The rule
costs five names in the whole library.

### 2.6 `@Composable`

500 public top-level `@Composable` functions in corpus A (counted on the exact annotation
descriptor; matching on the substring `Composable` inflates this to 524 by catching
`ComposableTarget` and friends). 183 are mangled.

Every one of them gains synthetic JVM parameters — `$composer`, one or two `$changed` bitmasks,
zero to two `$default` bitmasks:

    +2 params : 80      +3 params : 349      +4 params : 62      +5 params : 9

**The overlap with extension handling is one function.** Of the 500, 41 are extension functions,
and exactly one of those is a `Modifier` extension (`ui.ExternalDrag_desktopKt.onExternalDrag`).
The remaining 40 sit on `Transition`, `InfiniteTransition`, `InteractionSource`, `RowScope`,
`ColumnScope` and window scopes. Composables and chainable modifiers are, empirically, two
disjoint problems.

---

## 3. Measured — what the artefact walker binds from Compose today

**Zero. From all 19 jars of corpus A, and all 19 of corpus B.**

Not "a small subset", not "the unmangled ones". `ArtifactScanner.scanJar` returns an empty list for
every Compose artefact. Running the funnel stage by stage over corpus A's 51,605 JVM methods:

| after | surviving |
|---|---|
| all JVM methods | 51,605 |
| class binary name has no `$` | 30,897 |
| class is public, not synthetic/annotation | 27,514 |
| `@Metadata` kind is `class` (k=1) or absent | 21,220 |
| method is public **and** static | 3,381 |
| not synthetic, not bridge | 935 |
| not `<init>` / `<clinit>` | 935 |
| no `$` in method name | 918 |
| **no `-` in method name** | **190** |
| every parameter and return type admitted by `boundaryTypeOf` | **0** |
| unique name within its class | 0 |

Two independent gates each zero Compose on their own.

**The metadata-kind gate.** A Kotlin top-level function compiles into a file facade (`k=2`) or a
multi-file part (`k=5`), and the walker declines both because their JVM names are not names Kotlin
can write. That drops **all 1,411 public top-level Compose functions**, mangled or not —
`ButtonKt.Button` and `PaddingKt.padding` alike. The entire top-level Compose API is behind this
one gate.

**The type gate.** Of the 190 methods that reach it, none survives: every one takes or returns
something that is not a primitive, `String`, `byte[]` or `void`.

**The `-` filter is not what is blocking Compose.** It removes 728 of the 918 methods that reach
it, but the 190 it lets through die at the next gate anyway. This matters for how the gap is
described: the mangling filter is being replaced right now by metadata-driven understanding, and
when it is gone the Compose number will still be zero until the facade gate and the type gate move
too. The 52-of-177 mangling figure in §2.2 is therefore not a measure of what is unreachable — it
is a measure of **how much surface the metadata work opens that a descriptor-only walker could
never have named**.

`Modifier.padding` is the whole argument in one function:

| JVM name | mangled | Kotlin parameters |
|---|---|---|
| `padding` | no | `paddingValues: PaddingValues` |
| `padding-3ABfNKs` | yes | `all: Dp` |
| `padding-VpY3zN4` | yes | `horizontal: Dp = …, vertical: Dp = …` |
| `padding-qDBjuR0` | yes | `start: Dp = …, top: Dp = …, end: Dp = …, bottom: Dp = …` |

The one unmangled overload is the one a Python user is least likely to want.

### 3.1 The rule that will hurt next: dropping ambiguous overloads

The walker drops a name entirely when more than one binding would carry it. Applied to `Modifier`
that costs **33 of 130 names**, and the casualty list is the API's centre of gravity:

    absoluteOffset background blur border clickable combinedClickable composed
    consumeWindowInsets contextMenuOpenDetector focusOrder graphicsLayer height
    minimumInteractiveComponentSize offset onClick padding paddingFrom paddingFromBaseline
    pointerInput progressSemantics pullRefresh requiredHeight requiredSize requiredWidth
    scale scrollable selectable shadow size toggleable transformable triStateToggleable width

Overload dispatch is a larger problem than name collision (§2.5: five cases) and is unsolved here.
Python's own answer — one function that inspects its arguments — is available because the Python
side is a dispatcher, not a one-to-one mirror; see §4.4.

---

## 4. Judged — the Python surface

### 4.1 An extension function becomes a method on its receiver's proxy

Adopted, as proposed. Chaining then costs nothing: each of the 177 `Modifier` extensions returns
`Modifier`, so `Modifier.padding(16).background(...)` is ordinary Python method chaining with no
combinator machinery. This is what the 2024 attempt never reached —
`pythonx/compose/ui/modifier.py` is a copy of the Button wrapper in which `padding()` composes
nothing and `fill_max_size()` returns `self`.

Not every receiver can take a method, and the split was counted:

| receiver of the 604 public top-level extensions | count | Python placement |
|---|---|---|
| ordinary class or interface | 514 | method on the proxy type |
| Compose value class | 41 | method on the value-class proxy |
| Kotlin collection | 29 | module-level function |
| Kotlin primitive or `String` | 20 | module-level function |

Python cannot attach a method to `int`, so `Int.toDp` and its 19 siblings become
`pythonx.compose.ui.unit.to_dp(x)`. That is a real loss of fidelity and it is confined to 20
functions.

### 4.2 `Modifier` as one name for both the type and the empty modifier

`Modifier` in Kotlin is an interface, and `Modifier` as an *expression* is `Modifier.Companion`.
The relevant observation is that **`Modifier$Companion` implements `androidx.compose.ui.Modifier`**
— it is an instance of the very type it is the companion of, and it is the empty modifier that
starts every chain.

So Python does not need to reconcile two things. It needs one class whose *class object* also
behaves like an instance. A metaclass gives that directly:

```python
class _ModifierMeta(type):
    def padding(cls, *args, **kwargs): return cls.EMPTY.padding(*args, **kwargs)
    ...

class Modifier(metaclass=_ModifierMeta):
    def padding(self, *args, **kwargs) -> "Modifier": ...
```

`Modifier.padding(16)` resolves on the metaclass and delegates to the companion singleton;
`m.padding(16)` resolves on the instance; `def f(m: Modifier)` still names a type. All three
spellings work, and the generated `.pyi` can state all three (§4.5).

**Rejected: binding the module-level name `Modifier` to the companion instance.** It is simpler at
runtime and reads identically in the common case, but it destroys `Modifier` as an annotation —
a type checker cannot use an instance in `m: Modifier` — and the stubs exist precisely so that a
type checker can.

### 4.3 Collisions demand qualification; they do not get silently resolved

Confirmed, and now with a price attached: **five names** in the whole of Compose (§2.5). The
default binding is the unqualified name; when two packages contribute the same
`(receiver, name)`, neither wins and both are reachable only through a qualified accessor. The
same reasoning as dropping ambiguous overloads — a silent choice is a wrong call that never
reports itself — but the cost here is now known to be negligible rather than assumed to be.

What is lost relative to Kotlin is real and should be said plainly: Kotlin's import scope means an
extension is invisible unless imported, so an application that imports only `material3` never sees
`material`'s `minimumInteractiveComponentSize`. Python attaches everything discovered, so the
surface is the union across all resolved artefacts. Qualification is the price of that union.

### 4.4 Value classes: accept both spellings, but do not infer which by machine

This was framed as a usability choice once mangling stopped being an obstacle. It is not only that.
Measurement says a blanket rule in either direction is wrong.

**The evidence against blanket raw coercion.** For `TextUnit`, raw `16` decodes as `Unspecified`
(§2.4). For `Color`, raw `0xFFFF0000` is not red. For `Offset`, `Size`, `DpSize`, `IntSize`,
`IntOffset`, `DpOffset`, `TransformOrigin`, `CornerRadius`, `Constraints` — every `packedValue`
class, 20 of the 25 `Long`-backed ones — a user-meaningful number is not the stored number. None of
these failures raises; they render wrong.

**The evidence against blanket `dp(16)`.** `Dp` is 62% of value-class parameter occurrences on
`Modifier`, it wraps a plain `Float`, and its constructor is public and *is* the identity on that
float. Forcing a wrapper call there buys no safety at all.

**A machine rule was sought and found insufficient.** "Coerce iff the value class has a public
constructor taking exactly its underlying type" is checkable from metadata and selects 36 of 111.
It correctly excludes `TextUnit`, `Offset`, `Size`, `DpSize`, `DpOffset`, `TextAlign` (INTERNAL)
and `Role` (PRIVATE). **It admits `Color`, which is the second-worst case in the library.** `Color`
passes because its `ULong` constructor is public; it fails in practice because what a Kotlin author
writes is the shifting factory `Color(Int)`, and metadata cannot distinguish a constructor that
stores from one that packs. Whether a wrapper packs is a semantic fact with no bytecode witness.

Adopted:

1. **Every value-class parameter accepts its proxy** — `Modifier.padding(dp(16))` always works.
2. **Raw primitives are accepted only for an explicit allowlist**, seeded with `Dp` and extended by
   hand with evidence. `Dp` covers most of the ergonomic benefit on its own.
3. **Everything not on the allowlist rejects a raw number with an error that names the constructor
   to call.** `Modifier.padding` accepting `16` while `Text(font_size=16)` raises "expected
   TextUnit; write sp(16)" is an inconsistency, and it is the honest one: the two are different
   because `Dp` and `TextUnit` are genuinely different, and hiding that produces a UI that silently
   renders with no font size.
4. **`Color` is on the reject list despite passing the constructor test**, and the allowlist
   mechanism is manual precisely so that this exception has somewhere to live.

Residual risk, stated rather than mitigated: the allowlist is a human judgement per type, and a new
Compose release adding a float-wrapping unit will not be caught automatically. The failure mode of
getting it wrong is a silent visual defect, not an exception.

### 4.5 `.pyi`

Generation belongs to the Gradle plugin (`docs/ecosystem.md` §5b), and it reads the same metadata
as the binder, so the stub and the binding cannot drift. Extension-as-method appears as an ordinary
method, and the metaclass of §4.2 is expressible:

```python
class _ModifierMeta(type):
    @overload
    def padding(cls, all: Dp | float) -> Modifier: ...
    @overload
    def padding(cls, horizontal: Dp | float = ..., vertical: Dp | float = ...) -> Modifier: ...
    @overload
    def padding(cls, padding_values: PaddingValues) -> Modifier: ...

class Modifier(metaclass=_ModifierMeta):
    @overload
    def padding(self, all: Dp | float) -> Modifier: ...
    ...
```

Three things follow from the measurements:

- **`@overload` is mandatory, not optional.** 33 of 130 `Modifier` names carry more than one
  overload, and they are the load-bearing ones (§3.1). A generator that emits one signature per
  name will mistype `padding`, `size`, `background`, `border` and `clickable`.
- **`Dp | float` is how the allowlist shows up in the stub**, and a type on the reject list is
  stubbed as the proxy alone. The stub is then the documentation for §4.4's asymmetry.
- **Defaults must be carried.** 58% of `Modifier` extension parameters declare one; a stub with
  everything required would be wrong about most of the API. Metadata's `declaresDefaultValue` is
  the source; the *value* is not in metadata, so stubs emit `= ...`.

### 4.6 `@Composable` needs no new machinery here

Two measurements settle this. First, the extension and composable problems barely intersect: 1 of
500 (§2.6). Second, `docs/ecosystem.md` §5b already decided that a composable's synthetic
parameters are threaded as ordinary values by the Python adapter. Nothing in extension-as-method
disturbs that, because a `Modifier` extension is not composable and a composable is not a
`Modifier` extension.

What does need care is the arity mismatch: metadata reports Kotlin arity, the descriptor reports
2–5 more. Any code that derives arity from the descriptor will be wrong for all 500. Metadata
arity is the correct source, and the generated Kotlin passes the composer explicitly.

---

## 5. What the 2024 attempt paid, for comparison

`pythonx-compose` needed a **hand-written Kotlin shim** to make Compose reachable at all:
`Material3.kt`, 228 lines, 20 functions, **16 `@JvmName` annotations** whose only job is to
suppress mangling, 4 wrappers taking `color: Long` so that `Color(...)` could be applied on the
Kotlin side, and 3 taking raw `Float` sizes.

That shim also invented API. `Card(modifier=…, corner_radius=20, color=…)` in `UI.ipynb` is not
Compose — `material3.Card` has no `corner_radius` parameter in either of its overloads (§2.3). The
shim absorbed shape and colour into flat keyword arguments per component, by hand, which is exactly
the per-function wrapping `docs/ecosystem.md` §5b rules out.

The modifier chain was never built. `pythonx/compose/ui/modifier.py` lines 20–22 probe
`ButtonKt.__dict__` for a key starting `"Button-"` — a name-based lookup, which §2.3 shows cannot
be made unambiguous for Compose no matter how carefully it is written.

---

## 6. Open

- **Scoped modifiers.** 74 public member extensions on `Modifier` (`RowScope.weight`,
  `ColumnScope.align`, `BoxScope.matchParentSize`, `LazyItemScope.fillParentMaxWidth`, …) need a
  dispatch receiver that Kotlin supplies implicitly from the enclosing lambda. Python has no
  implicit receiver, so `Modifier.weight(1f)` cannot work as written; the scope has to be passed
  into the content callable and the call spelled against it. No shape has been chosen.
- **Overload dispatch.** §3.1: the current "drop ambiguous names" rule costs 33 of 130 `Modifier`
  names. A Python-side dispatcher that inspects argument count and type is the obvious answer and
  interacts badly with §4.4 — if `padding(16)` and `padding(padding_values)` are one Python
  function, raw-primitive acceptance is also what selects the overload.
- **The type gate.** §3 shows it is the second of two independent blockers, and metadata work
  addresses only the first. Binding a parameter typed `Shape`, `Brush` or `PaddingValues` needs an
  object-handle boundary type that `boundaryTypeOf` does not have. 79 of the 177 `Modifier`
  extensions take at least one, and 66 take a lambda.
- **Whether the allowlist of §4.4 should be data or code**, and where a downstream consumer adds to
  it for a library this repository has never seen.
- **klib.** Everything here is JVM. `docs/ecosystem.md` §5b records that
  `LibraryAbiReader` exposes extension receivers and unerased types from klibs, which suggests the
  same design carries to iOS and androidNative, but no Compose klib was read for this document.
