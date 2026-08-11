# Object lifetime across the boundary

Two runtimes, two memory models, and no shared view between them. This is what has been
settled, what is measured, and what is still open.

## Kotlin holding Python — solved and verified

CPython counts references; a Kotlin wrapper takes one on construction and gives it back when it
closes.

    Python creates the object       refcount 1
    a Kotlin wrapper takes it       refcount 2
    Python drops its reference      refcount 1, still alive
    the Kotlin wrapper closes       refcount 0, freed

`RefCountTest` checks this in both directions, because both failure modes are silent: releasing
one too few leaks, and releasing one too many frees an object still in use — that crash lands
somewhere unrelated, which is how the interpreter corruption during the lifetime work first
appeared, as a segfault inside `Py_Finalize` far from its cause.

The wrapper's own release is currently **explicit only**. GC-driven release is implemented but
blocked: `Python3.initialize()` still holds the GIL, so a cleaner thread calling
`PyGILState_Ensure` blocks forever. See ROADMAP §1 and §4.

## Python holding Kotlin — the handle table

There is no counterpart to `Py_INCREF` for a JVM or Kotlin/Native object, so something on the
Kotlin side has to act as a GC root:

    private val liveHandles = HashMap<Long, Any>()   // handle -> Kotlin object

    fun register(obj: Any): Long { val h = next++; liveHandles[h] = obj; return h }
    fun release(h: Long)        { liveHandles.remove(h) }

Python holds only the integer handle; the map holds the object. This is what JNI's
`NewGlobalRef` does, made explicit.

A note for anyone reading older discussion: Panama has no equivalent of `NewGlobalRef`, and it
does not need one. Panama is an API for *native memory*, not for JVM object lifetime — a
`MemorySegment` holding an address does not keep a Kotlin object alive. The GC root is an
ordinary Kotlin data structure, not anything Panama provides. Looking for the answer inside
Panama was looking at the wrong layer.

## Cycles

### Why reference counting cannot do it

Not a boundary problem — an inherent limit of reference counting, in any language:

    a = []; b = []
    a.append(b); b.append(a)
    del a, b        # each still holds one, count never reaches 0

This is why CPython carries a cyclic collector on top of refcounting. So "refcounting cannot
free cycles" is not a defeat; it was never refcounting's job.

### What the cyclic collector needs

CPython's algorithm is simple, and it turns on one requirement:

1. each tracked object's `tp_traverse` enumerates the `PyObject`s it holds
2. references discovered that way are subtracted from the refcount
3. if the remainder is zero, the object is kept alive only from inside the cycle — collectable
4. `tp_clear` drops those references and breaks the cycle

**Everything depends on step 1 being able to see through whatever holds the reference.**

### The cycle we can create

    P  ->  PK (Python proxy)  ->  [handle]  ->  K (Kotlin)  ->  PyObject wrapper  ->  P

Without help this never collects, and each collector has a defensible reason:

- CPython's GC traverses `PyObject`s. `PK` holds an opaque integer, so traversal stops there and
  `P` looks externally referenced.
- The JVM's GC collects cycles fine, but `K` is held by a strong entry in the handle map, which
  is a GC root.

Each side sees a live root it cannot reason about, and concludes the other is responsible.

### The resolution

Make the Kotlin object's Python references visible to `tp_traverse`. `PK`'s traverse reaches
through the handle and enumerates the `PyObject`-typed fields of `K`; CPython then sees the
cycle as internal and can collect it.

**This is cheap here specifically because the exposure table is generated at build time.** KSP
already walks every exposed class to build the function table, and at that point it knows which
fields are `PyObject`-typed. A traverse entry is one more generated function:

    // generated
    fun traverse_MyClass(obj: MyClass, visit: (Long) -> Unit) {
        obj.someField?.let { visit(it.pointer.raw()) }
        obj.otherField?.let { visit(it.pointer.raw()) }
    }

No reflection — which is the whole reason the table exists. A reflection-based binding would
find this expensive; that is why mature ones (JPype and similar) document "do not create
cycles" instead of solving it. We are not in that position.

### What is still hard

**`tp_traverse` runs during collection.** CPython requires it not to allocate and not to run
arbitrary code, and ours has to call into the JVM. The generated traverse must be restricted to
field reads and `visit` calls — no allocation, no user code, no locking beyond what is already
held.

**`tp_clear` has to mutate Kotlin state.** Breaking the cycle means nulling `K`'s `PyObject`
fields from inside a collection. A `val` field cannot be cleared at all, so either exposed
reference-holding fields must be `var`, or clearing goes through a generated accessor that
knows how.

**Cycles that close on the Kotlin side.** `K1 -> P -> K2 -> K1` needs the JVM's collector to see
through `P`, and there is no traverse hook on that side. Worse, the handle map holds `K2`
strongly, so it is a root regardless.

The likely answer is to make the handle map hold **weakly**, with the strong reference supplied
by the Python proxy for as long as it lives — so "Python stopped using it" becomes "the JVM may
collect it". That inverts who owns the strength and has not been worked through.

### Status of an older claim

An earlier discussion concluded that "cycles are handled by each language's GC" on the strength
of the four-step refcount sequence at the top of this document. That sequence is correct, and it
is implemented and tested — but it describes the **non-cyclic** case. Step 4, "the Kotlin side
releases", is exactly what a cycle prevents from ever happening. The claim does not follow, and
the same discussion walked it back a few messages later.

## Conversion caching, and where it stops

`PyContext` chooses how far a Python value is converted toward Kotlin, per
`ConversionStrategy`. That part is complete: `withContext` swaps and restores the strategy even
on throw, and all five variants dispatch.

`PyValue` is not. The lazy conversion it exists for is a stub:

    fun toKotlin(): T {
        if (cachedNativeValue == null) {
            // TODO: Implement the conversion logic to Kotlin type T
        }
        return cachedNativeValue!!      // NPE if the cache is empty
    }

It works today only because the basic types bypass it — `PyInt`, `PyFloat` and friends convert
inside their own `cachedNativeValue` accessors and never reach the stub. Anything without a
dedicated wrapper, and any genuine cache miss, would fail. `ConversionTest` covers strategy
switching and the shape difference between `RAW` and `NATIVE`, and does not cover this path at
all.

This matters for lifetime because a cached native value and the `PyObject` it came from have
different lifetimes: caching a converted value while releasing the source is fine, caching a
value that still points at Python-owned memory is not. Finishing `PyValue` needs that rule
stated per type, not just the conversion filled in.
