# Why a pure C API embedder never collects cycles, and never merges a refcount

Two things this document is careful about, because it corrects a previous document that was not:

- **CPython file:line citations are inherited, not verified here.** No CPython source is vendored in
  this repo and none was fetched for this write-up. The line numbers below come from an earlier
  source trace against the 3.14 series (which is what this build pins —
  `gradle.properties: pythonVersion=3.14.7`). Treat them as pointers, not as evidence. Where a
  claim matters, §3 and §4 back it with a measurement taken here instead.
- **Every number in §3 and §5 was measured on this machine**, on `desktopTest`, macOS arm64,
  CPython 3.14.7, both the default and the `-PpythonFreeThreaded=true` builds.

## 0. Summary

Two symptoms that looked unrelated share one cause, and a third that looked like the same thing is
not.

- **The cyclic collector never runs.** An embedder that only ever calls `PyObject_Call` and friends
  never triggers a generational collection, however much it allocates. True on both builds. (§1)
- **Free-threaded, a completed `Py_DecRef` can leave the object alive.** The count is correct and
  the decrement happened, but `tp_dealloc` has not run and the memory is not back. (§2)

  These two are the same thing: CPython schedules both jobs by setting a bit on the *eval breaker*,
  and the only code that reads those bits is `_Py_HandlePending`, which the evaluation loop calls
  and nothing else does. No bytecode frame, no checkpoint; no checkpoint, no collection and no
  merge.
- **`CycleCollectionTest`'s free-threaded failure is *not* the same thing**, and an eval-loop
  checkpoint provably does not fix it. It is deferred reference counting on the heap type, and what
  materialises a deferred reference is a collection, not a checkpoint. (§3)

## 1. `_Py_ScheduleGC` only sets a bit

Allocation-driven collection has not been a direct call since 3.12. `_Py_ScheduleGC` sets
`_PY_GC_SCHEDULED_BIT` on the eval breaker and returns:

| build | function | file:line (inherited) |
|---|---|---|
| global lock | `_Py_ScheduleGC` | `Python/gc.c:1967` |
| free-threaded | `_Py_ScheduleGC` | `Python/gc_free_threading.c:2700` |

The bit's only reader is `_Py_HandlePending`, which calls `_Py_RunGC` when it is set
(`Python/ceval_gil.c:1397`). `_Py_HandlePending` in turn has one caller family: the
`_CHECK_PERIODIC` / `_CHECK_PERIODIC_IF_NOT_YIELD_FROM` uops in `Python/bytecodes.c`, which open
every Python-level frame (`RESUME`) and close every call instruction. There is no public entry
point to it.

So **allocation schedules a collection that only the evaluation loop will ever perform.** An
embedder that executes no Python-level frame accumulates the scheduled bit forever and its cycles
are never broken. This is not a free-threading property; it is the same on both builds, and it is
why `gc.collect()` (or the C `PyGC_Collect`) has been the only thing that has ever collected
anything in this library's tests.

### `Py_MakePendingCalls` is not a substitute

It is the obvious Stable ABI candidate and it does not work. It forwards to
`_PyEval_MakePendingCalls` (`Python/ceval_gil.c:1034`), which handles `handle_signals` and
`make_pending_calls` and returns. It never looks at `_PY_GC_SCHEDULED_BIT`, and on a free-threaded
build it never looks at the merge bit either.

This one is checked here rather than taken on trust:
`EvalCheckpointTest.testDrainPendingReleasesReclaimsWhatTheCleanerGaveBack` calls
`Py_MakePendingCalls`, asserts it returned 0, and asserts the reference count did not move.

## 2. Free-threading: biased reference counting, and the queue only the eval loop drains

`Py_DECREF` on a free-threaded build is not one operation. It branches on whether the decrementing
thread is the object's owner, recorded in `ob_tid`:

- **Owner.** `ob_ref_local` is decremented non-atomically. If it reaches zero and the shared count
  is zero too, `tp_dealloc` runs immediately, on this thread.
- **Not the owner.** The decrement goes to `ob_ref_shared` atomically. If that count was already at
  zero — i.e. this decrement is the one that takes the object to zero — `tp_dealloc` *cannot* run
  here, because the object belongs to another thread. It is pushed onto that thread's queue by
  `_Py_brc_queue_object` (`Objects/object.c:413`, reached from the inline decref in
  `Include/refcount.h:363`).

The queue is drained by `_Py_brc_merge_refcounts`, whose only caller is, again, `_Py_HandlePending`
(`Python/ceval_gil.c:1388`), behind `_PY_EVAL_EXPLICIT_MERGE_BIT`.

**And once a collection has run, every surviving object becomes a non-owner case for everybody.**
`gc_free_threading.c` resets `ob_tid` to 0 on survivors (`Python/gc_free_threading.c:205`), so
afterwards no thread matches and every further decrement of those objects takes the shared path.

That is the mechanism behind the ROADMAP §9 measurement: 1000 wrappers released by the JVM cleaner,
2003 `Py_DecRef` calls demonstrably made, and `sys.getrefcount` frozen at 1002 through 50 forced
JVM GCs, 500 further C API round trips and two seconds of wall clock — then one trivial bytecode
frame released all thousand at once.

## 3. `CycleCollectionTest`: two defects, and the one that is *not* a checkpoint problem

`CycleCollectionTest.testHandleReleasedWhenProxyDiesWithoutCycle` was the last free-threaded
failure. It has two independent causes, and the guard assertion — "the type's count must rise by
exactly `rounds` while the instances are alive, otherwise the probe is not reading a refcount" —
caught both, exactly as it was written to.

### 3a. The probe read the wrong bytes

`ob_refcnt` is at offset 0 only on a build with the global lock. Free-threaded CPython lays the
header out differently:

```c
/* Include/object.h, Py_GIL_DISABLED */
struct _object {
    uintptr_t  ob_tid;          /* +0,  8 bytes -- owning thread id, NOT a refcount */
    uint16_t   ob_flags;        /* +8              */
    PyMutex    ob_mutex;        /* +10             */
    uint8_t    ob_gc_bits;      /* +11             */
    uint32_t   ob_ref_local;    /* +12, 4 bytes    */
    Py_ssize_t ob_ref_shared;   /* +16, 8 bytes, shifted by _Py_REF_SHARED_SHIFT (2),
                                        low 2 bits are state flags */
    PyTypeObject *ob_type;      /* +24             */
};
```

`Py_REFCNT` there is `ob_ref_local + (ob_ref_shared >> 2)`. Reading eight bytes at offset 0 yields
`ob_tid` — which is why the failure reported a ten-digit number that was *identical* before and
after creating 100 instances: measured here, `6171668704` both times. The layout above was
confirmed against this build by dumping the header (see 3b); it is not taken from documentation.

The probe now reads the right fields on each build.

### 3b. Correcting the correction: heap types **are** deferred-reference-counted

This section exists because two successive diagnoses of this failure were wrong in opposite
directions, and only a direct measurement settles it.

ROADMAP §9 originally said:

> Fixing the offset would not save the test. Its premise is that "each live instance of a heap type
> holds one reference to that type", and free-threaded CPython gives heap types **deferred
> reference counting** [...] The invariant simply does not exist there, so `tp_dealloc`'s
> obligation to release the type reference cannot be checked this way at all.

A later source trace proposed the opposite: that this was wrong, that `_PyGC_BITS_DEFERRED` is set
only on modules, top-level functions, descriptors and immortal objects, that nothing marks a
`PyType_FromSpec` heap type or its instances as deferred, and that the real cause was queued BRC
decrements that an eval-loop checkpoint would flush.

**Measured, both of those are partly wrong, and the practical conclusion belongs to the first.**
Dumping the proxy type's header on the free-threaded build, before and after 100 instantiations:

| | `ob_tid` (+0) | `ob_gc_bits` (+11) | `ob_ref_local` (+12) | `ob_ref_shared` (+16) |
|---|---|---|---|---|
| fresh type | `0x16d9b70e0` | `0x41` | 2 | `0x3ffffffffffffffd` |
| 100 instances alive | `0x16d9b70e0` | `0x41` | 2 | `0x3ffffffffffffffd` |
| after `drainPendingReleases()` | `0x16d9b70e0` | `0x41` | 2 | `0x3ffffffffffffffd` |
| after `PyGC_Collect()` | `0x16d9b70e0` | `0x41` | 2 | `0x400000000000018d` |
| after 100 `Py_DecRef` → 100 `tp_dealloc` | `0` | `0x41` | 0 | `0x4000000000000007` |

Reading that off:

- `ob_gc_bits == 0x41` is `_PyGC_BITS_TRACKED | _PyGC_BITS_DEFERRED`. The heap type **is** marked
  deferred.
- `ob_ref_shared == 0x3ffffffffffffffd` is `(0x0fffffffffffffff << 2) | 1`: a shared count of
  `2^60 - 1` = `PY_SSIZE_T_MAX / 8`, which is the deferred sentinel, with the
  `_Py_REF_MAYBE_WEAKREF` flag. Two independent signals agreeing.
- **Creating 100 instances changed nothing.** `PyType_GenericAlloc` increments the type through
  `_Py_INCREF_TYPE`, which is a no-op for a deferred type on its owning thread. The header is
  bit-identical.
- **An eval-loop checkpoint changed nothing either.** This is the decisive one: the proposed fix
  does not work, and cannot, because there is no queued decrement to merge. The increments were
  never made in the first place.
- **A collection is what makes deferred references real.** `ob_ref_shared` rose by exactly
  `100 << 2`. This is `gc_free_threading.c` converting deferred references into counted ones as it
  walks.
- **And then `tp_dealloc` gives back exactly 100.** Accounting for the flag bits moving from
  `_Py_REF_MAYBE_WEAKREF` (1) to `_Py_REF_MERGED` (3) and `ob_ref_local` merging to zero, the total
  goes from `1152921504606847077` back to `1152921504606846977` — the pre-instantiation value,
  exactly.

So the original ROADMAP claim was right about the *mechanism* (heap types are deferred) and wrong
about the *consequence* (that the invariant does not exist and cannot be checked). The invariant
holds on both builds. Free-threaded it is merely not **observable** until a collection has
materialised the deferred references. The later trace was right that instances are ordinary
reference-counted objects — but the test reads the *type's* count, not an instance's, so that
correction did not touch the failing assertion.

`CycleCollectionTest` therefore takes a `PyGC_Collect()` on either side of its instantiation loop,
free-threaded only. The assertions are unchanged: the same "rises by exactly 100" and "returns to
exactly where it started" are asserted on both builds, and both now hold.

### What this means for `abi3t`

The conclusion drawn from the wrong premise — "so `abi3t` making `PyObject` an incomplete type
would sink this test" — does not follow either. The test does need to know the header layout, which
is a real cost of a direct probe and the one place in this repo that reads inside a `PyObject`. But
that is a property of the probe, not of the invariant, and it would be equally solvable by counting
through `sys.getrefcount` at the price of the argument's own temporary reference. The library
proper stays clean: `ProxyTypeFactory` writes only into memory it obtained from
`PyObject_GetTypeData`.

## 4. What this repo does about §1 and §2

`Python3.drainPendingReleases()` reaches a checkpoint the cheapest way there is: it calls a cached,
already-compiled, zero-argument Python function whose body is `pass`, through
`PyObject_CallNoArgs`. That is one `RESUME`, hence one `_CHECK_PERIODIC`, hence one
`_Py_HandlePending` — one BRC merge, one QSBR sweep, and one scheduled collection if one was
scheduled.

It is deliberately **not** `PyRun_SimpleString("pass")` or `exec("pass")`:

- `PyRun_SimpleString` runs `PyErr_Print()` on failure, which clears the error indicator. This repo
  has twice had unrelated tests die because a helper wiped an indicator its caller had not read
  yet; `Python3.exec` is built on `PyRun_String` for exactly that reason.
- `exec("pass")` recompiles a module on every call — 23× the cached call, §5.

`withGIL` takes one automatically at its outermost entry, behind two gates: at most one per
`Python3.autoDrainInterval` outermost scopes, and skipped entirely unless `ReleaseCounter.released`
has moved since the last one. It defaults to 32 on free-threaded builds and 0 (off) otherwise.
`reachEvalCheckpointHoldingGIL` additionally declines while `PyErr_Occurred()` is non-null, for the
same error-indicator reason.

It is not taken from the cleaner. The queue that needs merging belongs to the thread that *owns*
the object; a cleaner owns nothing, so a checkpoint there would run Python on a cleaner thread (the
ROADMAP §1 deadlock) in order to drain an empty queue.
`EvalCheckpointTest.testCleanerActivityAloneTakesNoCheckpoint` pins the suppression.

`PyGC_Collect` is the one Stable ABI function that reclaims on behalf of *another* thread, because
`gc_collect_internal` stops the world and walks every thread state, calling
`_PyObject_MergePerThreadRefcounts` and `merge_queued_objects` for each. It costs a heap walk
rather than a queue pop — §5 — and, as §3b shows, it is also the only thing that materialises a
deferred reference.

## 5. Measurements

`EvalCheckpointTest.testCheckpointCostAgainstTheAlternatives`, free-threaded desktop build, macOS
arm64. The test asserts the *ordering*, not the absolute numbers: the ordering is a property of the
design and would break the moment the checkpoint started compiling or allocating.

| | ns/op | |
|---|---:|---|
| `withGIL { }` — attach and detach, nothing else | 170.51 | the floor |
| `Python3.drainPendingReleases()` | 302.70 | the checkpoint, ~132 ns over the floor |
| `withGIL { Py_MakePendingCalls() }` | 197.34 | cheap, and does not merge |
| `Python3.exec("pass")` | 7 037.04 | 23× the checkpoint — it recompiles |
| `withGIL { PyGC_Collect() }` | 224 625.00 | 742× the checkpoint — heap walk, but drains every thread |

At the default `autoDrainInterval = 32` the automatic checkpoint amortises to about 4 ns on a
~170 ns outermost scope, and a workload that drops no wrappers skips it on a field compare.

`PyGC_Collect` being ~740× the checkpoint is also the cost `CycleCollectionTest` pays for the two
collections §3b requires: ~0.45 ms for the whole test, free-threaded only.

## 6. What is still true, and what to watch

- The scheduled-GC half of §1 affects **both** builds. Nothing in this repo currently depends on
  allocation-driven collection, and `autoDrainInterval` defaults to 0 with the global lock, so the
  default configuration still never runs a generational collection on its own. That is a choice,
  not an oversight, and reversing it is one assignment.
- `EvalCheckpointTest` asserts that `Py_MakePendingCalls` does *not* merge. If CPython ever changes
  its mind, that test fails and says so.
- The header layout in §3a is a private detail read in exactly one place, and that read is guarded
  by an assertion that fails loudly if the offsets stop being right. It already has, twice.
- The deferred sentinel gives roughly `2^60` of headroom, so nothing here is at risk of the count
  actually reaching zero. But note the asymmetry `ProxyTypeFactory` lives with: its hand-written
  `tp_dealloc` releases the type through the public `Py_DecRef`, which is deferred-unaware, while
  the matching increment went through the deferred-aware `_Py_INCREF_TYPE` and did not happen. That
  balances out once a collection has materialised the references — measured above — but it is
  balanced by the collector, not by the two calls.

## 7. GC Accumulation and Reentrancy Measurement

Measurements taken to verify the consequences of §1 and the safety of the checkpoint confirm:

- **Cyclic Garbage Accumulation (GIL Build)**: A workload creating 10,000 cyclic object groups entirely through the C API (never running a Python bytecode evaluation loop) with `autoDrainInterval = 0` accumulates **20,000+** cyclic garbage objects indefinitely. Because no evaluation loop runs, `_Py_ScheduleGC`'s scheduled bit is never checked by `_CHECK_PERIODIC`, and cyclic GC never occurs.
- **`autoDrainInterval` Effectiveness**: When running the exact same C API workload but with `autoDrainInterval = 32`, `python-multiplatform` evaluates a dummy function (`__pmp_eval_checkpoint__`) to force the evaluation loop to run periodically. This allows `_CHECK_PERIODIC` to see the scheduled bit, trigger `_Py_RunGC()`, and reclaim the cyclic garbage (leaving only the few objects accumulated since the last checkpoint, rather than 20,000+).
- **`__del__` Reentrancy Risk**: Executing `PyGC_Collect()` or processing a checkpoint can invoke `__del__` methods. A scenario where `__del__` directly calls back into Kotlin (via Panama upcalls) was executed (`GCSchedulingMeasurementTest.testReentrancyDuringCheckpoint`). The reentrancy is safe on the GIL build: it does not deadlock, it does not loop infinitely, and it successfully executes the Kotlin upcall while the garbage collection is in progress.
