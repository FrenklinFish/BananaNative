#!/usr/bin/env python3
"""A small SPIR-V interpreter for one workgroup of a translated mesh module (vk_android_build.py's, as `spirv-dis --raw-id` prints it), for tools that compare
two modules on the same inputs (mesh_prepass_pairs.py). It runs the module's lanes in lockstep at barriers and subgroup operations and records what each output
variable holds at the end: no rasterizer, no task stage (the task payload is read as inputs).

Inputs are deterministic pseudo-random values keyed by where they are read, so two modules that read the same thing get the same value:
  uniform buffer `<name>` row r component c (`cp_c<n>` / `fp_c<n>`: the name's last part, renamed by `cmap`), storage buffer `<name>` word w (`cp_s<n>`, renamed by
  `smap`), the task payload by its access path, texture reads by their coordinates, raw global memory by address. The driver's segment `c0` reads 0 at rows 112-130
  (storage buffer bases: the word index is the offset) and 0x7fffffff as their sizes. Floats come mostly from [-4, 4), now and then a small integer.
Values are 32-bit patterns (floats as their bits), booleans, or tuples of them. An instruction outside the set below raises Unsupported.
"""
import hashlib, math, re, struct
from typing import Any

M32 = 0xffffffff
BLOCK_LIMIT = 2_000_000   # basic blocks one workgroup may run (all lanes together)


class Unsupported(Exception):
    pass


def f2b(x):
    try: return struct.unpack('<I', struct.pack('<f', x))[0]
    except OverflowError: return 0x7f800000 if x > 0 else 0xff800000
def b2f(b): return struct.unpack('<f', struct.pack('<I', b & M32))[0]
def r32(x): return 0x7fc00000 if x != x else f2b(x)   # a double rounded to float bits
def s32(b): b &= M32; return b - (1 << 32) if b & 0x80000000 else b
def isnan_b(b): return (b & 0x7f800000) == 0x7f800000 and (b & 0x7fffff) != 0


def rnd(*key):
    return int.from_bytes(hashlib.blake2b(repr(key).encode(), digest_size=4).digest(), 'little')


def randf(v):
    """mostly floats in [-4, 4), one in eight a small integer"""
    if v & 7 == 0: return v & 0xffff
    return f2b(((v >> 3) / float(1 << 29) - 0.5) * 8.0)


def float_bits(v):
    """spirv-dis's text of a 32-bit float constant → its bits (NaN and infinity as hex floats with exponent 128)"""
    m = re.match(r'(-?)0x1\.([0-9a-f]+)p\+128$', v)
    if m: return (0x80000000 if m.group(1) else 0) | 0x7f800000 | (int(m.group(2).ljust(6, '0')[:6], 16) >> 1 & 0x7fffff)
    if v in ('inf', '+inf', '0x1p+128'): return 0x7f800000
    if v in ('-inf', '-0x1p+128'): return 0xff800000
    try: x = float(v)
    except ValueError: x = float.fromhex(v)
    return struct.unpack('<I', struct.pack('<f', x))[0] if x != 0 and abs(x) < 1.17549435e-38 else f2b(x)


class Module:
    """A module's disassembly (spirv-dis --raw-id): types, constants, globals, functions, names and decorations"""
    def __init__(self, text):
        self.names, self.deco, self.types, self.consts, self.gvars, self.funcs, self.pos = {}, {}, {}, {}, {}, {}, {}
        self.entry, self.local_size = None, 32
        f = b = None
        for line in text.splitlines():
            line = line.strip()
            if not line or line.startswith(';'): continue
            m = re.match(r'(%\d+) = (Op\w+)\s*(.*)', line)
            m2 = None if m else re.match(r'(Op\w+)\s*(.*)', line)
            if not m and not m2: continue
            rid, op, rest = m.groups() if m else (None,) + m2.groups()  # pyright: ignore[reportOptionalMemberAccess]
            a = re.findall(r'"[^"]*"|\S+', rest)
            if op == 'OpName': self.names[a[0]] = a[1].strip('"')
            elif op in ('OpDecorate', 'OpMemberDecorate'): self.deco.setdefault(a[0], []).append(a[1:])
            elif op == 'OpEntryPoint': self.entry = a[1]
            elif op == 'OpExecutionMode' and a[1] == 'LocalSize': self.local_size = int(a[2]) * int(a[3]) * int(a[4])
            elif op.startswith('OpType'): self.types[rid] = (op, a)
            elif f is None and (op.startswith(('OpConstant', 'OpSpecConstant')) or op == 'OpUndef'): self.consts[rid] = (op, a)
            elif op == 'OpVariable' and f is None: self.gvars[rid] = a
            elif op == 'OpFunction': f = rid; self.funcs[f] = {'params': [], 'blocks': {}, 'order': []}
            elif op == 'OpFunctionParameter': self.funcs[f]['params'].append(rid)
            elif op == 'OpFunctionEnd': f = b = None
            elif op == 'OpLabel': b = rid; self.funcs[f]['blocks'][b] = []; self.funcs[f]['order'].append(b)
            elif f is not None and b is not None:
                self.funcs[f]['blocks'][b].append((rid, op, a)); self.pos[rid] = len(self.pos)
        self.builtin = {k: d[1] for k, ds in self.deco.items() for d in ds if d[0] == 'BuiltIn'}
        self.location = {k: int(d[1]) for k, ds in self.deco.items() for d in ds if d[0] == 'Location'}
        self.cval: dict[str, Any] = {k: self.const(k) for k in self.consts}

    def const(self, k):
        op, a = self.consts[k]
        if op in ('OpConstant', 'OpSpecConstant'):
            return float_bits(a[1]) if self.types[a[0]][0] == 'OpTypeFloat' else int(a[1], 0) & M32
        if op in ('OpConstantTrue', 'OpSpecConstantTrue'): return op == 'OpConstantTrue'   # a specialization constant reads false (dm_gl_depth off, alpha test off)
        if op in ('OpConstantFalse', 'OpSpecConstantFalse'): return False
        if op in ('OpConstantComposite', 'OpSpecConstantComposite'): return tuple(self.const(x) for x in a[1:])
        if op in ('OpConstantNull', 'OpUndef'): return self.zero(a[0])
        raise Unsupported(op)

    def zero(self, t):
        op, a = self.types[t]
        if op == 'OpTypeBool': return False
        if op == 'OpTypeVector': return tuple(self.zero(a[0]) for _ in range(int(a[1])))
        if op == "OpTypeArray": return tuple(self.zero(a[0]) for _ in range(int(self.cval[a[1]])))
        if op == 'OpTypeStruct': return tuple(self.zero(x) for x in a)
        return 0

    def var_names(self, prefix):
        """global variables whose name starts with prefix (cp_s: storage buffers, cp_c: uniform buffers), by name"""
        return sorted(n for n in self.names.values() if n.startswith(prefix) and n[len(prefix):].isdigit())

    def outputs(self):
        """{location: variable} of the module's user Output variables, and its Position output (a built-in variable or a block with a Position member; None if none)"""
        locs, pos = {}, None
        for v, a in self.gvars.items():
            if a[1] != 'Output': continue
            if v in self.location: locs[self.location[v]] = v
            elif self.builtin.get(v) == 'Position' or self._has_position(self.types[a[0]][1][1]): pos = v
        return locs, pos

    def _has_position(self, t):
        while self.types.get(t, ('',))[0] == 'OpTypeArray': t = self.types[t][1][0]
        return self.types.get(t, ('',))[0] == 'OpTypeStruct' and any(d[1:] == ['BuiltIn', 'Position'] for d in self.deco.get(t, []))


class Ptr:
    __slots__ = ('mem', 'key', 'path')
    def __init__(self, mem, key, path): self.mem, self.key, self.path = mem, key, path


class Env:
    """one workgroup's shared state and its data sources"""
    def __init__(self, mod, wg, seed, smap, cmap):
        self.mod, self.wg, self.seed, self.smap, self.cmap = mod, wg, seed, smap, cmap
        self.shared, self.out, self.counts, self.blocks = {}, [], None, 0

    def cb(self, name, row, comp):
        n = name.split('_', 1)[1] if '_' in name else name
        n = self.cmap.get(n, n)
        if n == 'c0' and 112 <= row <= 130: return 0x7fffffff if comp == 1 else 0   # storage buffer bases 0, sizes large
        return randf(rnd('cb', self.seed, n, row, comp))

    def sb(self, name, word):
        if word == 3: return 0x7fffffff   # an instance record's count word
        n = name.split('_', 1)[1] if '_' in name else name
        return randf(rnd('sb', self.seed, self.smap.get(n, n), word))


def getpath(v, path):
    """an element of a composite; an index past its end reads 0 (out-of-bounds access is undefined on the GPU)"""
    for i in path:
        if not isinstance(v, tuple) or not 0 <= i < len(v): return 0
        v = v[i]
    return v


def setpath(base, path, v):
    """a composite with one element replaced; a write past its end is dropped"""
    if not path: return v
    if not isinstance(base, tuple) or not 0 <= path[0] < len(base): return base
    l = list(base); l[path[0]] = setpath(l[path[0]], path[1:], v); return tuple(l)


def fdiv(x, y):
    a, b = b2f(x), b2f(y)
    if b == 0: return 0x7fc00000 if a != a or a == 0 else 0x7f800000 ^ ((x ^ y) & 0x80000000)
    return r32(a / b)
def cvt_fu(x):
    f = b2f(x)
    return 0 if f != f else (M32 if f > 0 else 0) if math.isinf(f) else max(0, min(M32, int(f)))
def cvt_fs(x):
    f = b2f(x)
    if f != f: return 0
    if math.isinf(f): return 0x7fffffff if f > 0 else 0x80000000
    return max(-(1 << 31), min((1 << 31) - 1, int(f))) & M32
def nmax(x, y): return y if isnan_b(x) else x if isnan_b(y) else x if b2f(x) >= b2f(y) else y
def nmin(x, y): return y if isnan_b(x) else x if isnan_b(y) else x if b2f(x) <= b2f(y) else y


BIN = {
    'OpIAdd': lambda x, y: (x + y) & M32, 'OpISub': lambda x, y: (x - y) & M32, 'OpIMul': lambda x, y: (x * y) & M32,
    'OpUDiv': lambda x, y: x // y if y else 0, 'OpUMod': lambda x, y: x % y if y else 0,
    'OpBitwiseAnd': lambda x, y: x & y, 'OpBitwiseOr': lambda x, y: x | y, 'OpBitwiseXor': lambda x, y: x ^ y,
    'OpShiftLeftLogical': lambda x, y: (x << (y & 31)) & M32, 'OpShiftRightLogical': lambda x, y: x >> (y & 31),
    'OpShiftRightArithmetic': lambda x, y: (s32(x) >> (y & 31)) & M32,
    'OpIEqual': lambda x, y: x == y, 'OpINotEqual': lambda x, y: x != y,
    'OpULessThan': lambda x, y: x < y, 'OpULessThanEqual': lambda x, y: x <= y, 'OpUGreaterThan': lambda x, y: x > y, 'OpUGreaterThanEqual': lambda x, y: x >= y,
    'OpSLessThan': lambda x, y: s32(x) < s32(y), 'OpSLessThanEqual': lambda x, y: s32(x) <= s32(y),
    'OpSGreaterThan': lambda x, y: s32(x) > s32(y), 'OpSGreaterThanEqual': lambda x, y: s32(x) >= s32(y),
    'OpLogicalAnd': lambda x, y: x and y, 'OpLogicalOr': lambda x, y: x or y, 'OpLogicalEqual': lambda x, y: x == y, 'OpLogicalNotEqual': lambda x, y: x != y,
    'OpFAdd': lambda x, y: r32(b2f(x) + b2f(y)), 'OpFSub': lambda x, y: r32(b2f(x) - b2f(y)), 'OpFMul': lambda x, y: r32(b2f(x) * b2f(y)), 'OpFDiv': fdiv,
    'OpFOrdGreaterThanEqual': lambda x, y: b2f(x) >= b2f(y), 'OpFOrdGreaterThan': lambda x, y: b2f(x) > b2f(y),
    'OpFOrdLessThan': lambda x, y: b2f(x) < b2f(y), 'OpFOrdLessThanEqual': lambda x, y: b2f(x) <= b2f(y),
    'OpFOrdEqual': lambda x, y: b2f(x) == b2f(y), 'OpFOrdNotEqual': lambda x, y: b2f(x) != b2f(y) and not isnan_b(x) and not isnan_b(y),
    'OpFUnordNotEqual': lambda x, y: not b2f(x) == b2f(y), 'OpFUnordEqual': lambda x, y: not b2f(x) != b2f(y),
    'OpFUnordLessThan': lambda x, y: not b2f(x) >= b2f(y), 'OpFUnordGreaterThanEqual': lambda x, y: not b2f(x) < b2f(y),
    'OpFUnordGreaterThan': lambda x, y: not b2f(x) <= b2f(y), 'OpFUnordLessThanEqual': lambda x, y: not b2f(x) > b2f(y),
}
UN = {
    'OpFNegate': lambda x: x ^ 0x80000000, 'OpLogicalNot': lambda x: not x, 'OpNot': lambda x: ~x & M32, 'OpSNegate': lambda x: -x & M32,
    'OpBitCount': lambda x: bin(x).count('1'), 'OpBitReverse': lambda x: int('{:032b}'.format(x)[::-1], 2), 'OpIsNan': isnan_b,
    'OpIsInf': lambda x: (x & 0x7fffffff) == 0x7f800000, 'OpConvertUToF': lambda x: r32(float(x)), 'OpConvertSToF': lambda x: r32(float(s32(x))),
    'OpConvertFToU': cvt_fu, 'OpConvertFToS': cvt_fs, 'OpBitcast': lambda x: x, 'OpCopyObject': lambda x: x,
}


def ext1(name, xs):
    f = lambda i: b2f(xs[i])
    if name == 'Fma': return r32(math.fma(f(0), f(1), f(2))) if all(map(math.isfinite, (f(0), f(1), f(2)))) else r32(f(0) * f(1) + f(2))
    if name == 'NClamp' or name == 'FClamp': return nmin(nmax(xs[0], xs[1]), xs[2])
    if name in ('NMax', 'FMax'): return nmax(xs[0], xs[1])
    if name in ('NMin', 'FMin'): return nmin(xs[0], xs[1])
    if name == 'FAbs': return xs[0] & 0x7fffffff
    if name == 'Sqrt': x = f(0); return 0x7fc00000 if x < 0 or x != x else r32(math.sqrt(x))
    if name == 'InverseSqrt':
        x = f(0)
        return 0x7fc00000 if x != x or x < 0 else 0x7f800000 if x == 0 else r32(1.0 / math.sqrt(x))
    if name == 'Exp2':
        x = f(0)
        try: return r32(2.0 ** x) if x == x else 0x7fc00000
        except OverflowError: return 0x7f800000
    if name == 'Log2':
        x = f(0)
        return 0x7fc00000 if x != x or x < 0 else 0xff800000 if x == 0 else r32(math.log2(x)) if math.isfinite(x) else 0x7f800000
    if name in ('Sin', 'Cos'): x = f(0); return r32(getattr(math, name.lower())(x)) if math.isfinite(x) else 0x7fc00000
    if name in ('Trunc', 'Floor', 'Ceil'):
        x = f(0); return r32(float(getattr(math, name.lower())(x))) if math.isfinite(x) else xs[0]
    if name == 'Fract': x = f(0); return r32(x - math.floor(x)) if math.isfinite(x) else 0x7fc00000
    if name == 'SMax': return xs[0] if s32(xs[0]) >= s32(xs[1]) else xs[1]
    if name == 'SMin': return xs[0] if s32(xs[0]) <= s32(xs[1]) else xs[1]
    if name == 'UMax': return max(xs[0], xs[1])
    if name == 'UMin': return min(xs[0], xs[1])
    if name == 'SAbs': return abs(s32(xs[0])) & M32
    if name == 'FindUMsb': return (xs[0].bit_length() - 1) & M32 if xs[0] else M32
    if name == 'FindILsb': return ((xs[0] & -xs[0]).bit_length() - 1) & M32 if xs[0] else M32
    raise Unsupported('GLSL.std.450 ' + name)


class Lane:
    def __init__(self, env, lane):
        self.env, self.mod, self.lane, self.private = env, env.mod, lane, {}

    def gv(self, vid):
        sc = self.mod.gvars[vid][1]
        mem = {'Input': 'in', 'Output': 'out', 'Workgroup': 'shared', 'TaskPayloadWorkgroupEXT': 'shared', 'Private': 'private', 'Uniform': 'cb',
               'StorageBuffer': 'sb', 'UniformConstant': 'img'}.get(sc)
        if not mem: raise Unsupported('storage class ' + sc)
        return Ptr(mem, vid, ())

    def load(self, p, tid):
        m, env = self.mod, self.env
        if p.mem in ('func', 'private'):
            if p.mem == 'func':
                fv, ft, rid = p.key; base = fv.get(rid)
                if base is None: base = m.zero(ft[rid])
            else:
                base = self.private.get(p.key)
                if base is None: base = m.zero(m.types[m.gvars[p.key][0]][1][1])
            return getpath(base, p.path)
        if p.mem == 'shared':
            v = env.shared.get((p.key, p.path))
            if v is None:
                if m.gvars[p.key][1] == 'TaskPayloadWorkgroupEXT': return randf(rnd('payload', env.seed, p.path))
                return m.zero(tid)
            return v
        if p.mem == 'in':
            b = m.builtin.get(p.key)
            v = {'WorkgroupId': (env.wg, 0, 0), 'LocalInvocationIndex': self.lane, 'SubgroupLocalInvocationId': self.lane, 'LocalInvocationId': (self.lane, 0, 0),
                 'GlobalInvocationId': (env.wg * m.local_size + self.lane, 0, 0), 'SubgroupLtMask': ((1 << self.lane) - 1, 0, 0, 0),
                 'SubgroupEqMask': (1 << self.lane, 0, 0, 0), 'NumWorkgroups': (4096, 1, 1), 'SubgroupSize': m.local_size}.get(b)
            if v is None: raise Unsupported('built-in input ' + str(b))
            return getpath(v, p.path)
        if p.mem == 'cb':
            name = m.names.get(p.key, p.key)
            if name == 'support_buffer': return m.zero(tid)
            row = p.path[1] if len(p.path) > 1 else 0
            if len(p.path) >= 3: return env.cb(name, row, p.path[2])
            return tuple(env.cb(name, row, c) for c in range(4))
        if p.mem == 'sb': return env.sb(m.names.get(p.key, p.key), p.path[1] if len(p.path) > 1 else 0)
        if p.mem == 'phys': return randf(rnd('phys', env.seed, p.key))
        if p.mem == 'img': return ('img', p.key)
        raise Unsupported('load from ' + p.mem)

    def store(self, p, v):
        m, env = self.mod, self.env
        if p.mem == 'func':
            fv, ft, rid = p.key; fv[rid] = setpath(fv.get(rid, m.zero(ft[rid])), p.path, v)
        elif p.mem == 'private':
            self.private[p.key] = setpath(self.private.get(p.key, m.zero(m.types[m.gvars[p.key][0]][1][1])), p.path, v)
        elif p.mem == 'shared': env.shared[(p.key, p.path)] = v
        elif p.mem == 'out': env.out.append((p.key, p.path, v))
        elif p.mem in ('sb', 'phys'): pass   # memory writes are not compared
        else: raise Unsupported('store to ' + p.mem)

    def run(self):
        return (yield from self.call(self.mod.entry, []))

    def call(self, fid, args):
        m = self.mod; f = m.funcs[fid]
        saved = getattr(self, 'fvars', None), getattr(self, 'ftypes', None), getattr(self, 'vals', None)
        self.fvars, self.ftypes, self.vals = {}, {}, dict(zip(f['params'], args))
        blk, prev, ret = f['order'][0], None, None
        while True:
            nxt = None
            for rid, op, a in f['blocks'][blk]:
                if op == 'OpPhi':
                    for k in range(1, len(a), 2):
                        if a[k + 1] == prev: self.vals[rid] = self.v(a[k]); break
                    else: raise Unsupported('OpPhi without the block it came from')
                    continue
                if op in ('OpSelectionMerge', 'OpLoopMerge', 'OpMemoryBarrier', 'OpEmitMeshTasksEXT', 'OpLine', 'OpNoLine'): continue
                if op == 'OpBranch': nxt = a[0]; break
                if op == 'OpBranchConditional': nxt = a[1] if self.v(a[0]) else a[2]; break
                if op == 'OpSwitch':
                    sel = self.v(a[0]); nxt = a[1]
                    for k in range(2, len(a), 2):
                        if int(a[k], 0) & M32 == sel: nxt = a[k + 1]; break
                    break
                if op == 'OpReturn': nxt = 'RET'; break
                if op == 'OpReturnValue': ret = self.v(a[0]); nxt = 'RET'; break
                if op in ('OpUnreachable', 'OpKill', 'OpTerminateInvocation'): nxt = 'RET'; break
                if op == 'OpVariable':
                    self.ftypes[rid] = m.types[a[0]][1][1]
                    self.fvars[rid] = self.v(a[2]) if len(a) > 2 else m.zero(self.ftypes[rid])
                    self.vals[rid] = Ptr('func', (self.fvars, self.ftypes, rid), ()); continue
                if op == 'OpFunctionCall':
                    r = yield from self.call(a[1], [self.v(x) for x in a[2:]])
                    if rid: self.vals[rid] = r
                    continue
                if op == 'OpControlBarrier': yield ('barrier', 1e18); continue
                if op == 'OpStore': self.store(self.v(a[0]), self.v(a[1])); continue
                if op == 'OpSetMeshOutputsEXT': self.env.counts = (self.v(a[0]), self.v(a[1])); continue
                if op == 'OpGroupNonUniformBallot': self.vals[rid] = yield ('ballot', m.pos[rid], bool(self.v(a[2]))); continue
                if op == 'OpGroupNonUniformShuffle': self.vals[rid] = yield ('shuffle', m.pos[rid], self.v(a[2]), self.v(a[3])); continue
                if op == 'OpGroupNonUniformBroadcastFirst': self.vals[rid] = yield ('first', m.pos[rid], self.v(a[2])); continue
                if op in ('OpGroupNonUniformAll', 'OpGroupNonUniformAny'): self.vals[rid] = yield (op[-3:].lower(), m.pos[rid], bool(self.v(a[2]))); continue
                self.vals[rid] = self.exec(op, a)
            if nxt == 'RET': break
            prev, blk = blk, nxt
            self.env.blocks += 1
            if self.env.blocks > BLOCK_LIMIT: raise Unsupported('more than %d blocks run in one workgroup (a loop that does not end on these inputs)' % BLOCK_LIMIT)
        self.fvars, self.ftypes, self.vals = saved
        return ret

    def v(self, x) -> Any:
        if self.vals is not None and x in self.vals: return self.vals[x]
        m = self.mod
        if x in m.cval: return m.cval[x]
        if x in m.gvars: return self.gv(x)
        raise Unsupported('value ' + x)

    def exec(self, op, a):
        V, t = self.v, a[0]
        if op in ('OpAccessChain', 'OpInBoundsAccessChain'):
            p = V(a[1]); return Ptr(p.mem, p.key, p.path + tuple(V(x) for x in a[2:]))
        if op == 'OpLoad': return self.load(V(a[1]), t)
        if op == 'OpConvertUToPtr': return Ptr('phys', V(a[1]), ())
        if op == 'OpCompositeExtract':
            v = V(a[1])
            for i in a[2:]: v = v[int(i)]
            return v
        if op == 'OpVectorExtractDynamic': v = V(a[1]); i = V(a[2]); return v[i] if 0 <= i < len(v) else 0
        if op == 'OpVectorInsertDynamic':
            v = list(V(a[1])); i = V(a[3])
            if 0 <= i < len(v): v[i] = V(a[2])
            return tuple(v)
        if op == 'OpCompositeConstruct': return tuple(V(x) for x in a[1:])
        if op == 'OpVectorShuffle': c = V(a[1]) + V(a[2]); return tuple(c[int(i)] if int(i) != M32 else 0 for i in a[3:])
        if op == 'OpCompositeInsert': return setpath(V(a[2]), tuple(int(i) for i in a[3:]), V(a[1]))
        if op == 'OpSelect':
            c, x, y = V(a[1]), V(a[2]), V(a[3])
            if isinstance(c, tuple): return tuple(xx if cc else yy for cc, xx, yy in zip(c, x, y))
            return x if c else y
        if op == 'OpExtInst':
            xs = [V(x) for x in a[3:]]
            if isinstance(xs[0], tuple): return tuple(ext1(a[2], [x[i] for x in xs]) for i in range(len(xs[0])))
            return ext1(a[2], xs)
        if op in BIN:
            x, y, f = V(a[1]), V(a[2]), BIN[op]
            return tuple(f(p, q) for p, q in zip(x, y)) if isinstance(x, tuple) else f(x, y)
        if op in UN:
            x, f = V(a[1]), UN[op]
            return tuple(f(p) for p in x) if isinstance(x, tuple) else f(x)
        if op == 'OpUMulExtended': p = V(a[1]) * V(a[2]); return (p & M32, p >> 32 & M32)
        if op == 'OpBitFieldUExtract': x, o, c = V(a[1]), V(a[2]), V(a[3]); return x >> o & ((1 << c) - 1) if c else 0
        if op == 'OpBitFieldSExtract':
            x, o, c = V(a[1]), V(a[2]), V(a[3])
            if not c: return 0
            r = x >> o & ((1 << c) - 1); return (r - (1 << c) if r >> (c - 1) & 1 else r) & M32
        if op == 'OpBitFieldInsert':
            b, ins, o, c = V(a[1]), V(a[2]), V(a[3]), V(a[4]); mask = ((1 << c) - 1) << o
            return (b & ~mask | ins << o & mask) & M32
        if op in ('OpAtomicIAdd', 'OpAtomicOr', 'OpAtomicAnd', 'OpAtomicUMax', 'OpAtomicUMin', 'OpAtomicExchange'):
            p = V(a[1]); old = self.load(p, t); x = V(a[4])
            new = {'OpAtomicIAdd': (old + x) & M32, 'OpAtomicOr': old | x, 'OpAtomicAnd': old & x, 'OpAtomicUMax': max(old, x), 'OpAtomicUMin': min(old, x),
                   'OpAtomicExchange': x}[op]
            self.store(p, new); return old
        if op == 'OpSampledImage': return ('simg', V(a[1]))
        if op in ('OpImageSampleExplicitLod', 'OpImageSampleImplicitLod', 'OpImageFetch', 'OpImageRead', 'OpImageGather'):
            c = V(a[2]); return tuple(randf(rnd('tex', self.env.seed, c, k)) for k in range(4))
        if op in ('OpImageQuerySizeLod', 'OpImageQuerySize'): return (256, 256)
        if op == 'OpUndef': return self.mod.zero(t)
        raise Unsupported(op)


def run(mod, wg=0, seed=1, smap=None, cmap=None):
    """Run workgroup wg of the module with input seed seed. smap renames storage buffers, cmap uniform buffers (the other module's names for the same data).
    → the Env: .out the output stores in order [(variable, access path, value)], .counts (vertices, primitives) from OpSetMeshOutputsEXT (None: not run)"""
    env = Env(mod, wg, seed, smap or {}, cmap or {})
    gens = [Lane(env, i).run() for i in range(mod.local_size)]
    pend = {}
    def adv(i, val, first=False):
        try: pend[i] = next(gens[i]) if first else gens[i].send(val)
        except StopIteration: pend.pop(i, None)
    for i in range(len(gens)): adv(i, None, True)
    while pend:
        lo = min(r[1] for r in pend.values())
        grp = [i for i, r in pend.items() if r[1] == lo]
        k = pend[grp[0]][0]
        if k == 'barrier':
            for i in grp: adv(i, None)
        elif k == 'ballot':
            mask = sum(1 << i for i in grp if pend[i][2])
            for i in grp: adv(i, (mask & M32, mask >> 32 & M32, 0, 0))
        elif k == 'shuffle':
            vals = {i: pend[i][2] for i in grp}; src = {i: pend[i][3] for i in grp}
            for i in grp: adv(i, vals.get(src[i], 0))
        elif k == 'first':
            v = pend[min(grp)][2]
            for i in grp: adv(i, v)
        elif k in ('all', 'any'):
            r = (all if k == 'all' else any)(pend[i][2] for i in grp)
            for i in grp: adv(i, r)
    return env


def per_vertex(env, var, member=None):
    """{vertex: [x, y, z, w]} an output variable holds after a run (stores in order; member: the block member, for a per-vertex block); vertices past the count left out"""
    acc = {}
    for k, path, val in env.out:
        if k != var or not path: continue
        vtx, rest = path[0], path[1:]
        if member is not None:
            if not rest or rest[0] != member: continue
            rest = rest[1:]
        cur = acc.setdefault(vtx, [0, 0, 0, 0x3f800000])
        if not rest: cur[:len(val)] = list(val)
        else: cur[rest[0]] = val
    n = env.counts[0] if env.counts else 0
    return {v: tuple(x) for v, x in acc.items() if v < n}
