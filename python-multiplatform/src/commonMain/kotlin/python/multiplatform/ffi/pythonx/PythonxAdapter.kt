package python.multiplatform.ffi.pythonx

import python.multiplatform.ffi.Python3
import python.multiplatform.reflection.ExposedCallable
import python.multiplatform.reflection.UpcallTable

/**
 * `pythonx` -- the hand-written Python that adapts the Kotlin declarations, and the generated table
 * it reads.
 *
 * `docs/pythonx-adapter-design.md` §7 draws one line through this whole area: **if it differs per
 * Kotlin declaration it is generated or resolved at run time; if it is the same rule for every
 * declaration it is `pythonx` Python source.** This object is where the two meet, and it keeps them
 * apart on purpose:
 *
 * | | what | who wrote it |
 * |---|---|---|
 * | [SOURCE] | the finder, the name rule, the overload dispatcher, the receiver proxies | a person, once. It names no Kotlin declaration anywhere |
 * | [renderTable] | one row per entry in [UpcallTable] | this function, from the table |
 *
 * The 2024 `pythonx-compose` put both on the wrong side of that line -- a Python file per Compose
 * component, 37 of them, 28 empty -- and `docs/pythonx-adapter-design.md` §1 measures what it cost:
 * `padding()` composed nothing and `fill_max_size()` returned `self`, because a per-declaration
 * wrapper is written once and then never again. Nothing here is per-declaration.
 *
 * ### What the Python needs from the host, and what it does not
 *
 * The same two names [python.multiplatform.ffi.upcall.PythonProxySource] needs -- `_pm_resolve` and
 * `_pm_invoke` in `__main__` -- plus `_pm_release` if handles are to be given back. It does **not**
 * need `PythonProxySource.install()`: the eager whole-table render and this lazy one are two
 * independent consumers of the same boundary, and `PythonxAdapterTest` installs only this one.
 * That is §2.3's laziness claim made executable rather than argued.
 *
 * ### Why the source is a Kotlin string and not a `.py` file
 *
 * The same reason [python.multiplatform.ffi.upcall.PythonProxySource] gives, and §2.5 restates for
 * this layer specifically: **there is no resource path to put a `.py` on.** Kotlin/Native has no
 * `getResourceAsStream`, and `sys.path` on iOS, androidNative and wasm points into the per-platform
 * CPython trees under `src/nativeInterop/cinterop/lib/...`. `Python3.exec` works on all five today
 * and nothing else does.
 *
 * This is the third candidate §2.5 lists, and its cost is named there and not disputed here: the
 * source is hostile to editing, diffing and shipping to PyPI, which `pythonx-compose`'s
 * `pyproject.toml` says it is eventually meant to do. **§2.5 stays open.** What is *not* paid is
 * duplication: this is the only copy, so there is nothing for a `.py` beside it to drift from.
 *
 * One mechanical constraint follows from the delivery route and is worth stating where it can be
 * broken: [SOURCE] is handed to Python inside an `r"""..."""` literal, so it must contain no triple
 * double-quote and must not end in a backslash. Its docstrings are `'''` for exactly that reason.
 * [render] checks it rather than leaving the failure to a `SyntaxError` at `exec` time.
 *
 * ### GraalVM native image
 *
 * Unchanged from `PythonProxySource`: this renders a *Python* string out of data that is already in
 * a statically-emitted Kotlin table. No reflection, no class loading, no dynamic Kotlin. The closed
 * world stays closed. (Unverified here: no native image was built for this change.)
 */
object PythonxAdapter {

    /**
     * The adaptation layer itself: hand-written Python, the same for every table.
     *
     * Read it as a file -- it is one, and the indentation the Kotlin literal adds is removed by
     * `trimIndent`. What it contains, in the order the design asks for it:
     *
     * | | `docs/pythonx-adapter-design.md` |
     * |---|---|
     * | `_Finder` / `_Loader` | §2.3, the hook a module `__getattr__` cannot replace |
     * | the module `__getattr__` the loader installs | §4.1, adapted once and then a dict hit |
     * | `to_python_name` / `to_kotlin_name` / `kotlin_name_for` | §3, forward by rule, backward by index |
     * | `_Overloads` | `docs/kotlin-extensions-in-python.md` §3.1, the dispatcher that has to live here |
     * | `_proxy_type` / `_Hybrid` | §4.2, an extension as a method on its receiver, both spellings |
     * | `_coerce`'s allowlist | §4.4, `Dp` yes, a packed value class no |
     */
    val SOURCE: String = """
        # GENERATED-FREE: this file is hand-written Python. See PythonxAdapter.kt.
        #
        # `pythonx` -- the adaptation layer over the Kotlin declarations the upcall table exposes.
        #
        # `docs/pythonx-adapter-design.md` §7 draws the line this file lives on: *if it differs per Kotlin
        # declaration it is generated or resolved at run time; if it is the same rule for every declaration
        # it is `pythonx` Python source.* Nothing here mentions a Kotlin declaration by name. What arrives
        # per declaration is the table `PythonxAdapter.renderTable` emits into `_register_table`, and the
        # only per-library knowledge is the package map and the value-class allowlist, both of which are
        # data and both of which a consumer can extend at run time.

        import importlib.machinery as _machinery
        import sys as _sys
        import types as _types

        __path__ = []  # a package with no directory: submodules come from _Finder below

        _ROOT = 'pythonx'


        # --------------------------------------------------------------------------- the raw boundary

        _BOUNDARY = {}


        def _no_release(_handle):
            # A host that bound no `_pm_release` gets the behaviour that existed before anything released:
            # a leak, rather than a NameError raised inside `__del__` where nothing can act on it. Same
            # policy as `PythonProxySource._pm_no_release`.
            return 0


        def _boundary():
            if not _BOUNDARY:
                _main = _sys.modules['__main__']
                _resolve_fn = getattr(_main, '_pm_resolve', None)
                _invoke_fn = getattr(_main, '_pm_invoke', None)
                if _resolve_fn is None or _invoke_fn is None:
                    raise RuntimeError(
                        'the raw upcall entry points are not bound: define _pm_resolve(name_bytes) -> '
                        'handle and _pm_invoke(handle, args_tuple) -> result before installing pythonx'
                    )
                _BOUNDARY['resolve'] = _resolve_fn
                _BOUNDARY['invoke'] = _invoke_fn
                _BOUNDARY['release'] = getattr(_main, '_pm_release', _no_release)
            return _BOUNDARY


        def _resolve(kotlin_name):
            # `bytes`, not `str`: desktop reaches its resolver through
            # `ctypes.CFUNCTYPE(c_long, c_char_p)` and `c_char_p` refuses a `str` outright. Every other
            # bootstrap accepts both. Same reasoning as `PythonProxySource._pm_lookup`.
            handle = _boundary()['resolve'](kotlin_name.encode('utf-8'))
            if handle == -1:
                raise AttributeError('no exposed Kotlin declaration named ' + kotlin_name)
            return handle


        # --------------------------------------------------------------------------- the table

        class _Decl:
            '''One row of what `PythonxAdapter.renderTable` sent, plus its resolved handle.'''

            __slots__ = (
                'kotlin_name', 'package', 'leaf', 'base', 'suffix', 'arity', 'kind', 'is_suspend',
                'param_names', 'param_tags', 'param_type_names', 'return_tag', 'return_type_name',
                'is_extension', 'receiver_type_name', 'param_has_default', 'handle',
            )

            def __init__(self, row):
                self.update(row)

            def update(self, row):
                # In place, and reusing the object: everything already adapted -- a `_Binding` in a module
                # dict, a `_Hybrid` on a proxy type -- points at this object, and a table reinstall must
                # refresh what they see rather than leave them looking at the previous epoch's row.
                (self.kotlin_name, self.arity, self.kind, self.is_suspend, self.param_names,
                 self.param_tags, self.param_type_names, self.return_tag, self.return_type_name,
                 self.is_extension, self.receiver_type_name, self.param_has_default) = row
                self.package, _, self.leaf = self.kotlin_name.rpartition('.')
                self.base, _, self.suffix = self.leaf.partition('__')
                self.handle = None

            def python_name(self):
                return to_python_name(self.leaf)

            def signature(self):
                parts = []
                for index in range(self.arity):
                    name = self.param_names[index] if self.param_names else 'a' + str(index)
                    if name == '<receiver>':
                        continue
                    type_name = self.param_type_names[index] if self.param_type_names else self.param_tags[index]
                    part = to_python_name(name) + ': ' + _simple_name(type_name)
                    if self.omittable(index):
                        # The same `= ...` a `.pyi` writes, and for the same reason: a refusal that
                        # listed an optional parameter as if it were required would send the caller
                        # looking for a value they never had to supply.
                        part += ' = ...'
                    parts.append(part)
                return to_python_name(self.leaf) + '(' + ', '.join(parts) + ')'

            def omittable(self, index):
                '''Whether slot [index] may be left out of a call.

                Read from `ExposedCallable.paramHasDefault`, which is a statement about the **binding**
                and not quite about the Kotlin declaration: it marks the slots the generated Kotlin
                body has a call expression for that does not mention them. Those are the same set for
                everything the walker binds today, and they part company where the generator declined
                to enumerate the omission sets -- see `ArtifactScanner.MAX_OMITTABLE_PARAMETERS`. The
                binding's answer is the one that matters here, because it is the one that decides
                whether Kotlin will actually reach a default.
                '''
                if not self.param_has_default or index >= len(self.param_has_default):
                    return False
                return bool(self.param_has_default[index])

            def bound_handle(self):
                if self.handle is None:
                    self.handle = _resolve(self.kotlin_name)
                return self.handle


        _TABLE = {}          # kotlin fqn -> _Decl
        _MODULES = []        # the pythonx.* modules the loader has built
        _BY_PACKAGE = {}     # kotlin package -> {python name -> [_Decl]}
        _BY_RECEIVER = {}    # kotlin receiver type -> {python name -> [_Decl]}
        _PACKAGES_SEEN = set()


        def _register_table(rows):
            '''Called by the generated fragment. Per-declaration data, produced by Kotlin, consumed here.

            Idempotent, and **destructive of everything already adapted**, because a reinstall is a new
            table: `CallableHandle` packs the table epoch precisely so that a handle cached across one is
            detected rather than silently invoked against a different function, and every name this layer
            has resolved is such a cached handle. `UpcallTable.install` bumps that epoch; this is the other
            half of that contract.
            '''
            _invalidate()
            _BY_PACKAGE.clear()
            _BY_RECEIVER.clear()
            _PACKAGES_SEEN.clear()
            present = set()
            for row in rows:
                kotlin_name = row[0]
                decl = _TABLE.get(kotlin_name)
                if decl is None:
                    decl = _Decl(row)
                    _TABLE[kotlin_name] = decl
                else:
                    decl.update(row)
                present.add(kotlin_name)
                _index(_BY_PACKAGE.setdefault(decl.package, {}), decl)
                if decl.is_extension and decl.receiver_type_name:
                    _index(_BY_RECEIVER.setdefault(decl.receiver_type_name, {}), decl)
                segments = decl.package.split('.')
                for count in range(1, len(segments) + 1):
                    _PACKAGES_SEEN.add('.'.join(segments[:count]))
            for gone in [name for name in _TABLE if name not in present]:
                del _TABLE[gone]


        def _invalidate():
            '''Drops every handle, adapted name and attached method, so the next read resolves again.'''
            for decl in _TABLE.values():
                decl.handle = None
            for module in _MODULES:
                for name in module._pythonx_adapted:
                    module.__dict__.pop(name, None)
                module._pythonx_adapted = []
            for cls in _PROXY_TYPES.values():
                for name in cls._pythonx_attached:
                    type.__delattr__(cls, name)
                cls._pythonx_attached = []
            # The host rebinds `_pm_resolve`/`_pm_invoke` per interpreter and, in this repository's
            # fixture, per test. Re-reading them costs one attribute lookup per install.
            _BOUNDARY.clear()


        def _index(table, decl):
            # Two keys per declaration: the base name, which an overload set shares, and the explicit
            # `name__Types` spelling, which is always exactly one declaration. `docs/kotlin-extensions-in-
            # python.md` §3.1 -- the explicit name is the caller saying which, and it has to keep working
            # whether or not the dispatcher can decide.
            table.setdefault(to_python_name(decl.base), []).append(decl)
            if decl.suffix:
                table.setdefault(to_python_name(decl.leaf), []).append(decl)


        def bound_names():
            '''Every Kotlin fully-qualified name the adapter knows about.'''
            return list(_TABLE)


        # --------------------------------------------------------------------------- names (§3)

        _KOTLIN_PRIMITIVES = frozenset((
            'kotlin.Byte', 'kotlin.Short', 'kotlin.Int', 'kotlin.Long', 'kotlin.Float', 'kotlin.Double',
            'kotlin.Boolean', 'kotlin.Char', 'kotlin.String', 'kotlin.Unit', 'kotlin.Any',
        ))


        def to_python_name(kotlin_name):
            '''Kotlin -> Python, the forward direction, which is the one the `.pyi` generator runs.

            A name that starts with an upper-case letter is a type, an object, an enum entry or a
            composable, and stays PascalCase; everything else is a function, a method, a property or a
            parameter, and becomes snake_case. The `__Types` suffix of an overload is a list of Kotlin type
            names and is not touched.
            '''
            base, sep, suffix = kotlin_name.partition('__')
            if not base or base[0].isupper():
                return kotlin_name
            out = []
            for index, ch in enumerate(base):
                if ch.isupper():
                    previous = base[index - 1] if index else ''
                    following = base[index + 1] if index + 1 < len(base) else ''
                    # A boundary is where a run of capitals starts or where it ends: `zIndex` -> `z_index`,
                    # `toURLString` -> `to_url_string`.
                    if index and (not previous.isupper() or (following and not following.isupper())):
                        out.append('_')
                    out.append(ch.lower())
                else:
                    out.append(ch)
            return ''.join(out) + sep + suffix


        def to_kotlin_name(python_name):
            '''Python -> Kotlin, by rule alone.

            **This is not injective and the adapter does not rely on it.** `to_url_string` comes back as
            `toUrlString`, which is not a declaration anybody wrote. It is the last resort under
            `kotlin_name_for`, which consults the index the forward rule built first -- that index is the
            "map of exceptions" `docs/pythonx-adapter-design.md` §3 asks the plugin to emit, except that it
            is derived from the table at run time and so cannot drift from it.
            '''
            base, sep, suffix = python_name.partition('__')
            if not base or base[0].isupper():
                return python_name
            parts = base.split('_')
            head = parts[0]
            tail = ''.join(part[:1].upper() + part[1:] for part in parts[1:])
            return head + tail + sep + suffix


        def kotlin_name_for(kotlin_package, python_name):
            '''The Kotlin declaration a Python name in [kotlin_package] means, or `None`.

            Index first, rule second. `docs/pythonx-adapter-design.md` §3's invariant -- every name the
            stub generator emits must resolve through the adapter -- is a statement about this function.
            '''
            decls = _BY_PACKAGE.get(kotlin_package, {}).get(python_name)
            if decls:
                if len(decls) == 1:
                    return decls[0].kotlin_name
                return [decl.kotlin_name for decl in decls]
            candidate = kotlin_package + '.' + to_kotlin_name(python_name)
            if candidate in _TABLE:
                return candidate
            return None


        def _simple_name(qualified):
            return qualified.rpartition('.')[2] if qualified else '?'


        def _declared_type_name(value):
            '''The Kotlin type name an owned OBJECT value declares, from whichever owner produced it.

            Two owners exist, and neither's shape is optional, so this does not assume either one.

            `PythonProxySource`'s `_PmObject` -- the owner a *walked* or KSP-rendered function result
            comes back wrapped in -- is deliberately **one shared class for every handle it owns**;
            its own KDoc's cost table is why (a per-type subclass was ~2--9x more expensive to
            construct and destruct, measured). Being shared, it cannot carry the type name on the
            *class*, so `_pm_own` stamps it on the *instance* instead (`_pm_type`).

            A `pythonx` proxy (`_proxy_type`) is the opposite on purpose: it is one class **per**
            Kotlin type, because `_attach` needs a distinct class to hang each receiver's extension
            methods off (`_BY_RECEIVER` is keyed on it). That is already known at class-definition
            time, so it is a *class* attribute (`_pythonx_type_name`), not an instance one.

            Before this, `_coerce` read `type(value)._pythonx_type_name` unconditionally and crashed
            -- `AttributeError: type object '_PmObject' has no attribute '_pythonx_type_name'` --
            the first time a walked result (an `_PmObject`) was passed into a `pythonx`-adapted
            call, because `_PmObject` never had that attribute and was never going to: giving it one
            would mean a class per type, which is the cost `_PmObject` exists to avoid.
            '''
            declared = getattr(type(value), '_pythonx_type_name', None)
            if declared is not None:
                return declared
            return getattr(value, '_pm_type', None)


        # --------------------------------------------------------------------------- packages (§5b)

        _PACKAGES = {}


        def register_package(python_prefix, kotlin_prefix):
            '''`pythonx.compose` means `androidx.compose`. The only per-library knowledge in this file.'''
            _PACKAGES[python_prefix] = kotlin_prefix


        register_package('pythonx.compose', 'androidx.compose')
        register_package('pythonx.kotlin', 'kotlin')


        def kotlin_package_for(module_name):
            '''The Kotlin package a `pythonx.*` module name stands for, or `None`.'''
            best = None
            for python_prefix, kotlin_prefix in _PACKAGES.items():
                if module_name == python_prefix or module_name.startswith(python_prefix + '.'):
                    if best is None or len(python_prefix) > len(best[0]):
                        best = (python_prefix, kotlin_prefix)
            if best is None:
                return None
            python_prefix, kotlin_prefix = best
            return kotlin_prefix + module_name[len(python_prefix):]


        # --------------------------------------------------------------------------- value classes (§4.4)

        _VALUE_CLASS_ALLOWLIST = set(('androidx.compose.ui.unit.Dp',))


        def allow_raw_primitive(kotlin_type_name):
            '''Extend the allowlist. Manual by design: whether a wrapper *packs* has no bytecode witness.'''
            _VALUE_CLASS_ALLOWLIST.add(kotlin_type_name)


        def _is_value_class_over_primitive(tag, type_name):
            # The table cannot say "value class" -- but a parameter whose marshalling tag is a primitive
            # while its *declared* type is not a Kotlin primitive is one, and that is enough. `Dp` is
            # FLOAT/`androidx.compose.ui.unit.Dp`; `zIndex`'s parameter is FLOAT/`kotlin.Float`.
            if type_name is None:
                return False
            if tag not in ('INT', 'FLOAT', 'BOOLEAN', 'STRING'):
                return False
            return type_name not in _KOTLIN_PRIMITIVES


        # --------------------------------------------------------------------------- proxies

        class _ValueProxy:
            '''`dp(16)` -- a value class held in Python, unwrapped to its underlying primitive on the way in.'''

            __slots__ = ('kotlin_type_name', 'raw')

            def __init__(self, kotlin_type_name, raw):
                self.kotlin_type_name = kotlin_type_name
                self.raw = raw

            def __repr__(self):
                return _simple_name(self.kotlin_type_name) + '(' + repr(self.raw) + ')'


        def dp(value):
            '''The `Dp` spelling `docs/kotlin-extensions-in-python.md` §4.4 requires to always work.'''
            return _ValueProxy('androidx.compose.ui.unit.Dp', float(value))


        _PROXY_TYPES = {}
        _EMPTY_FACTORIES = {}


        def register_empty(kotlin_type_name, kotlin_factory_name):
            '''Where a chain starts.

            `Modifier` as an *expression* is `Modifier.Companion`, an object instance, and the artefact
            walker binds functions -- so the empty modifier has no bound name and `pythonx` cannot invent
            one. Registering the factory by name is the seam; a proxy type with none refuses the
            class-object spelling with a message that says so rather than guessing.
            '''
            _EMPTY_FACTORIES[kotlin_type_name] = kotlin_factory_name


        class _BoundMember:
            '''An extension applied to a receiver: literally the module-level callable with slot 0 filled.'''

            __slots__ = ('_fn', '_receiver', '__name__')

            def __init__(self, fn, receiver, name):
                self._fn = fn
                self._receiver = receiver
                self.__name__ = name

            def __call__(self, *args, **kwargs):
                return self._fn(self._receiver, *args, **kwargs)


        class _Hybrid:
            '''`Modifier.padding(16)` and `m.padding(16)`, from one descriptor.

            `docs/pyi-generation-design.md` §4.3 measured the metaclass alternative failing at run time: a
            plain `def` on a metaclass is a *non-data* descriptor, so `type.__getattribute__` searches the
            class's own MRO first and `Modifier.padding(16)` binds `16` to `self`. A descriptor in the class
            body is found for both spellings and is told which one it is by `obj`.
            '''

            __slots__ = ('_fn', '_name')

            def __init__(self, fn, name):
                self._fn = fn
                self._name = name

            def __get__(self, obj, owner=None):
                if obj is None:
                    obj = owner.empty()
                return _BoundMember(self._fn, obj, self._name)


        class _ProxyMeta(type):

            def __getattr__(cls, name):
                # Only reached for a name the class does not have yet; once `_attach` puts a `_Hybrid` in
                # the class dict, ordinary lookup answers and this never runs again for that name.
                if name.startswith('_'):
                    raise AttributeError(name)
                if not _attach(cls, name):
                    raise AttributeError(
                        'no Kotlin extension named ' + name + ' on ' + cls._pythonx_type_name
                    )
                return getattr(cls, name)

            def __repr__(cls):
                return "<pythonx proxy for '" + cls._pythonx_type_name + "'>"


        def _attach(cls, python_name):
            '''Installs one extension as a method on [cls], and reports whether there was one.'''
            decls = _BY_RECEIVER.get(cls._pythonx_type_name, {}).get(python_name)
            if not decls:
                return False
            setattr(cls, python_name, _Hybrid(_callable_for(python_name, decls), python_name))
            cls._pythonx_attached.append(python_name)
            return True


        def _proxy_type(kotlin_type_name):
            '''The Python class that owns a handle to a Kotlin [kotlin_type_name], created once.'''
            existing = _PROXY_TYPES.get(kotlin_type_name)
            if existing is not None:
                return existing

            release = _boundary()['release']

            def __init__(self, handle):
                self._pm_handle = handle

            def __del__(self, _release=release):
                # The other half of `HandleTable`'s contract, and the thing
                # `docs/kotlin-extensions-in-python.md` §3.2 recorded as missing: "three handles leak per
                # run of that test, deliberately, because owning them is what §4.1's proxy is for and
                # §4.1's proxy does not exist yet." This is that proxy.
                handle = getattr(self, '_pm_handle', None)
                if handle is not None:
                    _release(handle)

            def __getattr__(self, name):
                if name.startswith('_'):
                    raise AttributeError(name)
                if not _attach(type(self), name):
                    raise AttributeError(
                        'no Kotlin extension named ' + name + ' on ' + type(self)._pythonx_type_name
                    )
                return getattr(self, name)

            def __repr__(self):
                return '<' + _simple_name(kotlin_type_name) + ' handle=' + repr(self._pm_handle) + '>'

            @classmethod
            def empty(cls):
                factory = _EMPTY_FACTORIES.get(kotlin_type_name)
                if factory is None:
                    raise TypeError(
                        'no empty ' + _simple_name(kotlin_type_name) + ' is bound: call '
                        "pythonx.register_empty('" + kotlin_type_name + "', '<kotlin function name>') "
                        'or start the chain from a value Kotlin handed you'
                    )
                return _wrap(_boundary()['invoke'](_resolve(factory), ()), kotlin_type_name)

            body = {
                '__init__': __init__,
                '__del__': __del__,
                '__getattr__': __getattr__,
                '__repr__': __repr__,
                'empty': empty,
                '_pythonx_type_name': kotlin_type_name,
                '_pythonx_attached': [],
                '__qualname__': _simple_name(kotlin_type_name),
            }
            cls = _ProxyMeta(_simple_name(kotlin_type_name), (object,), body)
            _PROXY_TYPES[kotlin_type_name] = cls
            return cls


        def _wrap(result, kotlin_type_name):
            '''A `TypeTag.OBJECT` result: a handle integer, given an owner.'''
            if kotlin_type_name is None or not isinstance(result, int):
                return result
            return _proxy_type(kotlin_type_name)(result)


        # --------------------------------------------------------------------------- calling

        def _coerce(value, tag, type_name, decl, slot, strict):
            '''One argument on its way in. Returns the marshalled value, or raises when `strict`.'''
            if isinstance(value, _ValueProxy):
                if type_name is not None and value.kotlin_type_name != type_name:
                    return _refuse(
                        strict,
                        'expected ' + _simple_name(type_name) + ' but got ' +
                        _simple_name(value.kotlin_type_name),
                    )
                return value.raw
            if tag == 'OBJECT':
                handle = getattr(value, '_pm_handle', None)
                if handle is not None:
                    declared = _declared_type_name(value)
                    # `declared is None` means the owner could not say what it holds -- an `_PmObject`
                    # built by a rendered *class*'s `__init__` (no `_pm_own` call, so no `_pm_type`)
                    # rather than by a function's owned result. That is not a refusal: a bare `int`
                    # handle a few lines below is already trusted with no type check at all in strict
                    # mode, so an owned-but-untyped value gets the same trust rather than a stricter
                    # rule than the boundary's own currency.
                    if type_name is not None and declared is not None and declared != type_name:
                        return _refuse(
                            strict,
                            'expected ' + _simple_name(type_name) + ' but got ' + _simple_name(declared),
                        )
                    return handle
                if isinstance(value, int) and not isinstance(value, bool):
                    # A bare handle, the raw boundary's own currency -- accepted when the caller already
                    # said which declaration they meant, and **not** allowed to vote in a dispatch: an
                    # integer is indistinguishable from a number, so letting it match an OBJECT slot would
                    # make every arity-matched overload a candidate for `padding(m, 16)`.
                    if strict:
                        return value
                    return _refuse(strict, 'a bare handle cannot select an overload')
                return _refuse(strict, 'expected a ' + _simple_name(type_name) + ' handle')
            if tag in ('INT', 'FLOAT'):
                if isinstance(value, bool) or not isinstance(value, (int, float)):
                    return _refuse(strict, 'expected a number for ' + _simple_name(type_name or tag))
                if _is_value_class_over_primitive(tag, type_name):
                    if type_name not in _VALUE_CLASS_ALLOWLIST:
                        # §4.4: a packed value class decodes a raw number as something else entirely and
                        # renders wrong without raising. `TextUnit(16)` is `Unspecified`, not 16 of
                        # anything. Refusing is the only answer that reports itself.
                        return _refuse(
                            strict,
                            'a raw number is not accepted for ' + _simple_name(type_name) +
                            ': it is a value class and not on pythonx allowlist, so ' + repr(value) +
                            ' would be reinterpreted rather than converted',
                        )
                return float(value) if tag == 'FLOAT' else int(value)
            if tag == 'BOOLEAN':
                if not isinstance(value, bool):
                    return _refuse(strict, 'expected a bool')
                return value
            if tag == 'STRING':
                if not isinstance(value, str):
                    return _refuse(strict, 'expected a str')
                return value
            if tag == 'BYTES':
                if not isinstance(value, (bytes, bytearray)):
                    return _refuse(strict, 'expected bytes')
                return bytes(value)
            return value


        class _Mismatch(Exception):
            pass


        _NO_MATCH = object()


        def _refuse(strict, message):
            if strict:
                raise _Mismatch(message)
            return _NO_MATCH


        def _bind(decl, args, kwargs, strict):
            '''Maps a Python call onto [decl]'s positional slots.

            Returns `(slots, defaults_used)`, or `_NO_MATCH` when not `strict`; raises `_Mismatch`
            when `strict`.

            `defaults_used` is how many slots this call left to Kotlin, and it exists for `_Overloads`
            -- see there. It is not a diagnostic.
            '''
            if len(args) > decl.arity:
                return _refuse(strict, 'takes ' + str(decl.arity) + ' arguments, got ' + str(len(args)))
            slots = list(args) + [_NO_MATCH] * (decl.arity - len(args))
            if kwargs:
                if not decl.param_names:
                    return _refuse(strict, 'the producer supplied no parameter names, so it is positional only')
                for key, value in kwargs.items():
                    index = -1
                    for slot, name in enumerate(decl.param_names):
                        if name != '<receiver>' and to_python_name(name) == key:
                            index = slot
                            break
                    if index < 0:
                        return _refuse(strict, 'has no parameter named ' + key)
                    if slots[index] is not _NO_MATCH:
                        return _refuse(strict, 'got two values for ' + key)
                    slots[index] = value
            defaults_used = 0
            for index, value in enumerate(slots):
                omittable = decl.omittable(index)
                if value is _NO_MATCH or (value is None and omittable):
                    if not omittable:
                        missing = decl.param_names[index] if decl.param_names else 'argument ' + str(index)
                        return _refuse(strict, 'no value for ' + to_python_name(missing))
                    # `None` is the whole mechanism, and it is not a value being passed: the generated
                    # Kotlin body tests `args[i] == null` and takes a branch whose call expression does
                    # not mention this parameter at all, so the *compiler* supplies the default.
                    # `docs/pythonx-adapter-design.md` §4.5 -- metadata carries the flag and never the
                    # expression, so this is the only place the default value can come from.
                    #
                    # It costs nothing that was previously possible. `UpcallTrampoline.toKotlin` maps
                    # `None` to `null` before it looks at the tag, `resolveKotlinType` declines a
                    # nullable primitive and a nullable value class outright, and `_coerce` below
                    # refuses `None` for an OBJECT slot -- so no call that used to reach Kotlin passed
                    # a `None` in a slot this now reads as an omission.
                    slots[index] = None
                    defaults_used += 1
                    continue
                tag = decl.param_tags[index] if decl.param_tags else 'OBJECT'
                type_name = decl.param_type_names[index] if decl.param_type_names else None
                coerced = _coerce(value, tag, type_name, decl, index, strict)
                if coerced is _NO_MATCH:
                    return _NO_MATCH
                slots[index] = coerced
            return (tuple(slots), defaults_used)


        class _Binding:
            '''One Kotlin declaration, resolved once, callable from Python.'''

            __slots__ = ('_decl', '__name__', '__qualname__')

            def __init__(self, decl):
                self._decl = decl
                self.__name__ = decl.python_name()
                self.__qualname__ = decl.kotlin_name

            def __call__(self, *args, **kwargs):
                decl = self._decl
                try:
                    bound, _ = _bind(decl, args, kwargs, True)
                except _Mismatch as mismatch:
                    raise TypeError(decl.signature() + ': ' + str(mismatch)) from None
                return _wrap(
                    _boundary()['invoke'](decl.bound_handle(), bound),
                    decl.return_type_name if decl.return_tag == 'OBJECT' else None,
                )


        class _Overloads:
            '''The dispatcher `docs/kotlin-extensions-in-python.md` §3.1 says has to live here.

            The walker refuses to arbitrate between overloads because it has only a name to go on; Python
            has the arguments. Selection is on argument count, on keyword names, and on declared type --
            and when that still does not separate them, this refuses and names the candidates rather than
            picking one, which is the same rule the walker applies for the same reason.

            **Defaults made almost every call ambiguous, so there is one more rule.** Once
            `padding(horizontal =, vertical =)` accepts a single argument, `padding(m, 16)` binds against
            it, against `padding(all =)` and against `padding(start =, top =, end =, bottom =)` -- three
            candidates for a call that has exactly one obvious meaning. The tie-break is Kotlin's own and
            not an invention here: **a candidate that fills no default beats one that does**, and the
            comparison extends to a count so that it is a total order. `Modifier.padding(16.dp)` resolves
            to `padding(all:)` in Kotlin for the same reason it does here.

            Where the fewest-defaults score is *equal*, nothing has changed: this still refuses and names
            the candidates. It also parts company with `kotlinc` in one direction and does so knowingly --
            Kotlin reports `padding()` with no arguments as ambiguous between the two- and four-`Dp`
            overloads, where this picks the two-`Dp` one because it fills fewer. Both reach
            `padding(0.dp, 0.dp)`; refusing a call every candidate agrees about would be the worse answer.
            '''

            __slots__ = ('_decls', '__name__')

            def __init__(self, name, decls):
                self._decls = decls
                self.__name__ = name

            def __call__(self, *args, **kwargs):
                matched = []
                for decl in self._decls:
                    bound = _bind(decl, args, kwargs, False)
                    if bound is not _NO_MATCH:
                        matched.append((decl, bound[0], bound[1]))
                if matched:
                    fewest = min(entry[2] for entry in matched)
                    matched = [entry for entry in matched if entry[2] == fewest]
                if len(matched) == 1:
                    decl, bound, _ = matched[0]
                    return _wrap(
                        _boundary()['invoke'](decl.bound_handle(), bound),
                        decl.return_type_name if decl.return_tag == 'OBJECT' else None,
                    )
                candidates = ', '.join(decl.signature() for decl in self._decls)
                spellings = ', '.join(to_python_name(decl.leaf) for decl in self._decls)
                if not matched:
                    raise TypeError(
                        'no overload of ' + self.__name__ + ' accepts these arguments. Candidates: ' +
                        candidates + '. Call one by name: ' + spellings
                    )
                raise TypeError(
                    'the arguments to ' + self.__name__ + ' match more than one overload (' +
                    ', '.join(entry[0].signature() for entry in matched) +
                    '). Nothing arbitrates -- call one by name: ' + spellings
                )


        def _callable_for(python_name, decls):
            if len(decls) == 1:
                return _Binding(decls[0])
            return _Overloads(python_name, decls)


        # --------------------------------------------------------------------------- modules (§2.3)

        def _adapt(kotlin_package, python_name):
            decls = _BY_PACKAGE.get(kotlin_package, {}).get(python_name)
            if decls:
                return _callable_for(python_name, decls)
            qualified = kotlin_package + '.' + python_name
            if python_name[:1].isupper() and (qualified in _BY_RECEIVER or qualified in _PROXY_TYPES):
                # A type, not a declaration: `pythonx.compose.ui.Modifier` is the receiver proxy.
                return _proxy_type(qualified)
            candidate = kotlin_package + '.' + to_kotlin_name(python_name)
            if candidate in _TABLE:
                return _Binding(_TABLE[candidate])
            return None


        def _module_getattr(module, kotlin_package):
            def __getattr__(name):
                if name.startswith('__') and name.endswith('__'):
                    raise AttributeError(name)
                adapted = _adapt(kotlin_package, name)
                if adapted is None:
                    raise AttributeError(
                        "module '" + module.__name__ + "' has no attribute '" + name +
                        "' (nothing named that is exposed from Kotlin package " + kotlin_package + ')'
                    )
                # Adapted once. Every later read is an ordinary module-dict hit and this never runs again
                # for this name -- which is why the 551-587 ns figure for a live-property `__getattr__`
                # does not price this one.
                setattr(module, name, adapted)
                module._pythonx_adapted.append(name)
                return adapted

            return __getattr__


        def _module_dir(kotlin_package):
            def __dir__():
                names = set(_BY_PACKAGE.get(kotlin_package, {}))
                for type_name in _BY_RECEIVER:
                    package, _, leaf = type_name.rpartition('.')
                    if package == kotlin_package:
                        names.add(leaf)
                return sorted(names)

            return __dir__


        class _Loader:

            def __init__(self, kotlin_package):
                self._kotlin_package = kotlin_package

            def create_module(self, spec):
                return None  # the default module object is exactly right

            def exec_module(self, module):
                module.__path__ = []
                module._pythonx_adapted = []
                _MODULES.append(module)
                module.__getattr__ = _module_getattr(module, self._kotlin_package)
                module.__dir__ = _module_dir(self._kotlin_package)
                module._pythonx_kotlin_package = self._kotlin_package


        class _Finder:
            '''The half a module `__getattr__` cannot do.

            `import pythonx.compose.material3` fails *before* any attribute is touched, so laziness inside
            a module is not enough to make the module lazy. A finder answers the import, and the
            `__getattr__` the loader installs answers the names inside it -- two hooks,
            `docs/pythonx-adapter-design.md` §2.3.
            '''

            def find_spec(self, fullname, path=None, target=None):
                if fullname != _ROOT and not fullname.startswith(_ROOT + '.'):
                    return None
                kotlin_package = kotlin_package_for(fullname)
                if kotlin_package is None or kotlin_package not in _PACKAGES_SEEN:
                    # Not "every pythonx.* name exists": a package nothing is bound under is not
                    # importable, so a typo is a ModuleNotFoundError at the import rather than an
                    # AttributeError several lines later.
                    return None
                return _machinery.ModuleSpec(fullname, _Loader(kotlin_package), is_package=True)


        def _install_finder():
            for finder in _sys.meta_path:
                if isinstance(finder, _Finder):
                    return
            _sys.meta_path.append(_Finder())


        _install_finder()
    """.trimIndent()

    /**
     * Puts [SOURCE] into `sys.modules` as the package `pythonx`, exactly once per interpreter.
     *
     * The module object is built here rather than by an import, because there is no file for an
     * import to find (see the class KDoc). `__path__ = []` is what makes it a *package*, which is
     * what lets `pythonx.compose.ui` be a submodule at all; the finder [SOURCE] installs answers
     * for those.
     *
     * Guarded on `sys.modules` rather than on a Kotlin flag: this repository's test fixture
     * finalizes and re-initialises the interpreter between tests, and a Kotlin flag would then say
     * "installed" about an interpreter that no longer exists.
     */
    private val DELIVERY: String = """
        import sys as _px_sys
        import types as _px_types

        if 'pythonx' not in _px_sys.modules:
            _px_mod = _px_types.ModuleType('pythonx')
            _px_mod.__path__ = []
            _px_sys.modules['pythonx'] = _px_mod
            exec(compile(_px_src, 'pythonx/__init__.py', 'exec'), _px_mod.__dict__)
            del _px_mod
        del _px_src, _px_sys, _px_types
    """.trimIndent()

    /**
     * Renders the whole installable source: [SOURCE], its delivery, and one row per entry.
     *
     * A pure function from entries to text, for the reason `PythonProxySource.render` is one --
     * `PythonxTableSourceTest` pins it in `commonTest` with no interpreter anywhere, so the
     * generated half is a readable artefact rather than a behaviour.
     */
    fun render(entries: List<ExposedCallable>): String {
        // The delivery route is an `r"""..."""` Python literal, so a triple double-quote inside
        // [SOURCE] would end it early and a trailing backslash would escape its closing quote.
        // Both would surface as a SyntaxError with a line number inside a string nobody can see.
        require(!SOURCE.contains("\"\"\"")) { "the pythonx source must not contain a triple double-quote" }
        require(!SOURCE.endsWith("\\")) { "the pythonx source must not end in a backslash" }
        return buildString {
            appendLine("_px_src = r\"\"\"")
            appendLine(SOURCE)
            appendLine("\"\"\"")
            appendLine(DELIVERY)
            appendLine()
            appendLine(renderTable(entries))
        }
    }

    /**
     * The generated half: one row per [ExposedCallable], and nothing else.
     *
     * Every field here is one `docs/pythonx-adapter-design.md` §2.4 recorded as *missing* from the
     * boundary -- parameter names, whether a slot is an extension receiver, the receiver's type, the
     * **declared** type of a parameter as opposed to its marshalling tag, and whether a parameter
     * has a default. They are on [ExposedCallable] now, and this is what carries them the last step,
     * into Python, where §2.4's three consequences are decided:
     *
     * 1. `paramNames` is what makes `padding(horizontal=8, vertical=4)` possible at all.
     * 2. `isExtension` + `receiverTypeName` is what lets an extension become a method on its
     *    receiver, and therefore what makes the chain work.
     * 3. `paramTypeNames` is what lets the allowlist be enforced in Python: a `FLOAT` tag over a
     *    declared type that is not `kotlin.Float` is a value class over a primitive, which is the
     *    only machine-checkable form of "this might pack".
     *
     * A third entry point (`_pm_describe`) on all five bootstraps would have been the alternative.
     * This costs no platform code and cannot disagree with the table, because it *is* the table.
     *
     * 4. `paramHasDefault` is what closes §4.5. It was carried and unused while every generated
     *    Kotlin body passed every argument; a walked body now carries one call expression per subset
     *    of its defaulted parameters and picks between them on `args[i] == null`
     *    (`ArtifactScanner.presenceBranchedCall`), so this column is the list of slots `_bind` may
     *    fill with that sentinel. Nothing about the row *shape* changed to do it, which is why it
     *    was worth carrying before anything read it.
     */
    fun renderTable(entries: List<ExposedCallable>): String = buildString {
        appendLine("import pythonx as _px_pythonx")
        appendLine("_px_pythonx._register_table((")
        entries.forEach { entry ->
            append("    (")
            append(entry.name.quoted())
            append(", ${entry.arity}")
            append(", ${entry.kind.name.quoted()}")
            append(", ${entry.isSuspend.py()}")
            append(", ${entry.paramNames.map { it.quoted() }.tuple()}")
            append(", ${entry.paramTypes.map { it.name.quoted() }.tuple()}")
            append(", ${entry.paramTypeNames.map { it.quoted() }.tuple()}")
            append(", ${entry.returnType.name.quoted()}")
            append(", ${entry.returnTypeName?.quoted() ?: "None"}")
            append(", ${entry.isExtension.py()}")
            append(", ${entry.receiverTypeName?.quoted() ?: "None"}")
            append(", ${entry.paramHasDefault.map { it.py() }.tuple()}")
            appendLine("),")
        }
        appendLine("))")
        // Eager, and the only eager thing here: a target whose bootstrap publishes no entry points
        // must fail at install with the message that says so, rather than at the first attribute
        // read several files away. Everything else -- every handle, every proxy type, every module
        // -- is resolved on first use.
        appendLine("_px_pythonx._boundary()")
        append("del _px_pythonx")
    }

    /**
     * Renders the adapter for whatever [UpcallTable] currently holds and `exec`s it.
     *
     * @return the source that was executed, so a caller can log or inspect exactly what ran.
     */
    fun install(): String {
        val source = render(UpcallTable.entries())
        Python3.exec(source)
        return source
    }

    private fun Boolean.py(): String = if (this) "True" else "False"

    private fun List<String>.tuple(): String = when (size) {
        0 -> "()"
        1 -> "(${this[0]},)"
        else -> "(${joinToString(", ")})"
    }

    private fun String.quoted(): String =
        "'" + replace("\\", "\\\\").replace("'", "\\'") + "'"
}
