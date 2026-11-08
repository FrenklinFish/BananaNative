"""The Vulkan library's GlobalToStorage raw helpers after promote_locals (vk_android_spv.py simplify_helpers, gmem_sink), on compute shaders with helpers shaped as the
translator's (GlobalToStorage.cs TryGenerateRawStorageOp):

- the helper since a319e773: the entry chosen last (a private word), the 2 MiB chunk's hint (word 6 of entry chunk & 8191), the 13-step binary search over the 8192-entry
  pool table, the inPool check, the bitmap write log (read, then AtomicOr of the entry's bit) and the access through a physical pointer. It keeps every branch (none is
  the branch around the access alone) and every one of the 13 search steps;
- the older shape (one inPool branch around the log and the access), with the bitmap log: it loses that branch, takes the table's sink (entry 0, words 6 and 7) for an
  address in no pool, and its log OR writes nothing then (ORs 0); the 13 search steps stay;
- a plain helper with a branch, which keeps it.

Every search step must stay: a search cut to 6 steps finds only the first 64 of the table's entries (Donkey Kong Bananza's terrain records sat past them; the cut
came back once with a merge: docs/reference/shaders.md, A search cut that came back). Each result passes spirv-val.

test_translator_chain: the search as the translator emits it (a 2 MiB-chunk hint first, then 13 steps from 4096, each step's index a value of its own, so that
after promote_locals each OpSelect takes the one before it as its false operand): all 13 steps stay. test_loop_search: loop_pool_search makes that chain one loop
(DontUnroll) of one step; the helper, run by a small SPIR-V interpreter before and after over a table of 300 pools, finds every pool (entries past 64, an address in
a 2 MiB chunk a pool covers only partly, so no hint) and the sink for an address in no pool, as a scan of the pools does."""
from pathlib import Path
import shutil
import subprocess
import sys
import tempfile
import unittest

HERE = Path(__file__).resolve().parent
sys.path.insert(0, str(HERE.parents[0] / 'tools'))
import vk_android_spv as spv  # noqa: E402

STEPS = 13   # GlobalToStorage.GmemMapLog2 (dmsh_abi.h DMSH_GMEM_MAP_ENTRIES 8192)

AT = 'any(bvec2(map.w[{e} + 1] < hi, all(bvec2(map.w[{e} + 1] == hi, map.w[{e}] <= lo))))'           # the address at or above the entry's start
BELOW = 'any(bvec2(hi < map.w[{e} + 3], all(bvec2(hi == map.w[{e} + 3], lo < map.w[{e} + 2]))))'      # the address below the entry's end
# each step a value of its own, as the translator writes them (one store each, so promote_locals makes them SSA values: the selects chain directly)
SEARCH = '\n'.join(f'''            int c{s} = i{s + 1} + {1 << s}; int i{s} = {AT.format(e=f'c{s} * 8')} ? c{s} : i{s + 1};''' for s in range(STEPS - 1, -1, -1))
ADDR = '''        uint hl = lo + map.w[f + 4];
        uint hh = hi + map.w[f + 5] + (hl < lo ? 1u : 0u);'''
LOG = '''        uint word = sel >> 5u, bit = 1u << (sel & 31u);
        if ((glog.w[word] & bit) == 0u) atomicOr(glog.w[word], bit);'''

HEAD = '''#version 450
#extension GL_EXT_buffer_reference : require
#extension GL_EXT_shader_explicit_arithmetic_types_int64 : require
layout(local_size_x = 64) in;
layout(set = 0, binding = 0, std430) readonly buffer Map { uint w[]; } map;
layout(set = 0, binding = 1, std430) buffer Log { uint w[]; } glog;
layout(set = 0, binding = 2, std430) buffer Io { uint w[]; } io;
layout(buffer_reference, std430, buffer_reference_align = 4) buffer Word { uint v; };
uint g_last = 0u;
'''

# The helper since a319e773 (the last entry, the hint, the search; then the inPool check, the bitmap log, the access)
FIND_NEW = f'''    uint sel = g_last;
    int le = int(sel) * 8;
    if (!all(bvec2({AT.format(e='le')}, {BELOW.format(e='le')}))) {{
        uint chunk = (hi << 11) | (lo >> 21);
        uint hint = map.w[(chunk & 8191u) * 8u + 6u];
        uint hidx = hint & 8191u;
        sel = hidx;
        if (!all(bvec3(hidx != 0u, (hint >> 13) == chunk, hi < 256u))) {{
            int i{STEPS} = 0;
{SEARCH}
            sel = uint(i0);
        }}
        g_last = sel;
        int fs = int(sel) * 8;
        if (!{BELOW.format(e='fs')}) return@RET@;
    }}
    int f = int(sel) * 8;'''

NEW = HEAD + f'''
void gstore(uint lo, uint hi, uint v)
{{
{FIND_NEW.replace('@RET@', '')}
{LOG}
{ADDR}
        Word(packUint2x32(uvec2(hl, hh))).v = v;
}}

uint gload(uint lo, uint hi)
{{
{FIND_NEW.replace('@RET@', ' 0u')}
{ADDR}
        return Word(packUint2x32(uvec2(hl, hh))).v;
}}
'''

# The older shape: the search, then one inPool branch around the log and the access
FIND_OLD = f'''    int i{STEPS} = 0;
{SEARCH}
    uint sel = uint(i0);
    int f = i0 * 8;
    bool inPool = {BELOW.format(e='f')};'''

OLD = HEAD + f'''
void gstore(uint lo, uint hi, uint v)
{{
{FIND_OLD}
    if (inPool) {{
{LOG}
{ADDR}
        Word(packUint2x32(uvec2(hl, hh))).v = v;
        return;
    }}
    return;
}}

uint gload(uint lo, uint hi)
{{
{FIND_OLD}
    if (inPool) {{
{ADDR}
        return Word(packUint2x32(uvec2(hl, hh))).v;
    }}
    return 0u;
}}
'''

TAIL = '''
uint plain(uint a)
{
    if (a > 7u) { io.w[a] = a; return a * 3u; }
    return a + 1u;
}

void main()
{
    uint i = gl_GlobalInvocationID.x;
    gstore(io.w[i * 2], io.w[i * 2 + 1], i);
    io.w[i] = gload(io.w[i * 2 + 1], io.w[i * 2]) + plain(i);
}
'''


XSTEPS = 13   # the translator's search: steps 4096 .. 1

XSEARCH = '\n'.join(f'''        int c{k} = i{k} + {1 << (XSTEPS - 1 - k)}; uint sl{k} = map.w[c{k} * 8], sh{k} = map.w[c{k} * 8 + 1];
        int i{k + 1} = any(bvec2(sh{k} < hi, all(bvec2(sh{k} == hi, sl{k} <= lo)))) ? c{k} : i{k};''' for k in range(XSTEPS))
XSHADER = f'''#version 450
#extension GL_EXT_buffer_reference : require
#extension GL_EXT_shader_explicit_arithmetic_types_int64 : require
layout(local_size_x = 64) in;
layout(set = 0, binding = 0, std430) readonly buffer Map {{ uint w[]; }} map;
layout(set = 0, binding = 2, std430) buffer Io {{ uint w[]; }} io;
layout(buffer_reference, std430, buffer_reference_align = 4) buffer Word {{ uint v; }};

uint gload(uint lo, uint hi)
{{
    int hint = int(map.w[(((hi << 11) | (lo >> 21)) & 8191u) * 8u + 6u]);
    int idx = hint & 8191;
    if (!((hint & 8191) != 0 && uint(hint) >> 13 == ((hi << 11) | (lo >> 21)) && hi < 256u)) {{
        int i0 = 0;
{XSEARCH}
        idx = i{XSTEPS};
    }}
    int f = idx * 8;
    uint el = map.w[f + 2], eh = map.w[f + 3];
    bool inPool = any(bvec2(hi < eh, all(bvec2(hi == eh, lo < el))));
    if (inPool) {{
{ADDR}
        return Word(packUint2x32(uvec2(hl, hh))).v;
    }}
    return 0u;
}}

void main()
{{
    uint i = gl_GlobalInvocationID.x;
    io.w[i] = gload(io.w[i * 2], io.w[i * 2 + 1]);
}}
'''


def functions(m):
    """{function id: its instructions} of the non-entry functions"""
    ent = m.entry_function(); out = {}
    for f in [x[2] for x in m.ins if x[0] & 0xffff == spv.OP_FUNCTION and x[2] != ent]:
        a, e = m.function_range(f); out[f] = m.ins[a:e + 1]
    return out


def search_steps(body):
    """The longest chain of selects each taking the one before as its 'else' (the search: index = at or above ? candidate : index)"""
    sel = {x[2]: x for x in body if x[0] & 0xffff == spv.OP_SELECT}
    def depth(i):
        n = 0
        while i in sel and n <= len(sel): n += 1; i = sel[i][5]
        return n
    return max((depth(i) for i in sel), default=0)


def ops(body): return [x[0] & 0xffff for x in body]


@unittest.skipUnless(shutil.which('glslangValidator') and shutil.which('spirv-val'), 'needs glslang and SPIRV-Tools')
class GmemHelpers(unittest.TestCase):
    def build(self, src):
        """(the module as glslang made it, the module after promote_locals, checked by spirv-val)"""
        with tempfile.TemporaryDirectory() as t:
            d = Path(t)
            (d / 's.comp').write_text(src + TAIL)
            r = subprocess.run(['glslangValidator', '-V', '--target-env', 'vulkan1.2', str(d / 's.comp'), '-o', str(d / 's.spv')], capture_output=True, text=True)
            self.assertEqual(r.returncode, 0, r.stdout + r.stderr)
            before = spv.Module((d / 's.spv').read_bytes())
            m = spv.Module((d / 's.spv').read_bytes())
            spv.promote_locals(m)
            (d / 'r.spv').write_bytes(m.data())
            r = subprocess.run(['spirv-val', '--target-env', 'vulkan1.3', str(d / 'r.spv')], capture_output=True, text=True)
            self.assertEqual(r.returncode, 0, r.stdout + r.stderr)
        return before, spv.Module(m.data())

    def raw_and_plain(self, m):
        raw, plain = [], []
        for body in functions(m).values():
            (raw if spv.OP_CONVERT_U_TO_PTR in ops(body) else plain).append(body)
        self.assertEqual((len(raw), len(plain)), (2, 1))
        self.assertIn(spv.OP_BRANCH_CONDITIONAL, ops(plain[0]), 'a plain helper keeps its branch')
        return raw

    def test_helper_keeps_every_step(self):
        """The helper since a319e773: all 13 search steps and every branch stay (no branch is the one around the access alone); the log's OR is untouched"""
        before, m = self.build(NEW)
        branches = sorted(ops(b).count(spv.OP_BRANCH_CONDITIONAL) for b in self.raw_and_plain(before))
        raw = self.raw_and_plain(m)
        self.assertEqual(sorted(ops(b).count(spv.OP_BRANCH_CONDITIONAL) for b in raw), branches, 'every branch kept')
        ors = 0
        for body in raw:
            self.assertEqual(search_steps(body), STEPS, 'every search step stays: a shorter search finds only the first entries of the table')
            sel = {x[2] for x in body if x[0] & 0xffff == spv.OP_SELECT}
            for x in body:
                if x[0] & 0xffff == spv.OP_ATOMIC_OR: ors += 1; self.assertNotIn(x[6], sel, 'the log is as the translator wrote it')
        self.assertEqual(ors, 1, 'the store logs its entry')

    def test_inpool_branch_with_bitmap_log(self):
        """The older shape: the inPool branch goes, an address in no pool takes the sink (entry 0, words 6 and 7) and the bitmap log ORs 0 then; all 13 steps stay"""
        _, m = self.build(OLD)
        mapvar = next(v['id'] for v in m.resources() if v['binding'] == 0)
        consts = {i: v[0] for i, (_, v) in m.consts.items()}
        ors = 0
        for body in self.raw_and_plain(m):
            self.assertEqual(search_steps(body), STEPS, 'every search step stays')
            words = [consts.get(x[5]) for x in body if x[0] & 0xffff == spv.OP_ACCESS_CHAIN and len(x) == 6 and x[3] == mapvar]
            self.assertEqual(sorted(w for w in words if w is not None), [6, 7], 'the sink: entry 0, words 6 and 7')
            sel = {x[2]: x for x in body if x[0] & 0xffff == spv.OP_SELECT}
            defs = {x[2]: x for x in body if len(x) > 2}
            cu = next(x for x in body if x[0] & 0xffff == spv.OP_CONVERT_U_TO_PTR)
            cc = defs[defs[cu[3]][3]]
            self.assertTrue(all(c in sel for c in cc[3:5]), 'both address words chosen between the pool\'s and the sink\'s')
            n = 0
            for x in body:
                if x[0] & 0xffff == spv.OP_ATOMIC_OR:
                    n += 1
                    self.assertIn(x[6], sel, 'the log\'s OR writes nothing for an address in no pool')
                    self.assertEqual(m.consts[sel[x[6]][5]][1], [0])
            ors += n
            # the inPool branch is gone; what is left is the log's own (read the word, OR the bit when it is not set)
            self.assertEqual(ops(body).count(spv.OP_BRANCH_CONDITIONAL), n)
        self.assertEqual(ors, 1, 'the store logs its entry')

    def test_translator_chain(self):
        with tempfile.TemporaryDirectory() as t:
            d = Path(t)
            (d / 's.comp').write_text(XSHADER)
            subprocess.run(['glslangValidator', '-V', '--target-env', 'vulkan1.2', str(d / 's.comp'), '-o', str(d / 's.spv')], check=True, capture_output=True)
            m = spv.Module((d / 's.spv').read_bytes())
            spv.promote_locals(m)
            (d / 'r.spv').write_bytes(m.data())
            r = subprocess.run(['spirv-val', '--target-env', 'vulkan1.3', str(d / 'r.spv')], capture_output=True, text=True)
            self.assertEqual(r.returncode, 0, r.stdout + r.stderr)
        m = spv.Module(m.data())
        (body,) = functions(m).values()
        self.assertIn(spv.OP_CONVERT_U_TO_PTR, [x[0] & 0xffff for x in body])
        sel = [x for x in body if x[0] & 0xffff == spv.OP_SELECT]
        chain = [sel[0]]
        for x in sel[1:]:
            if x[5] == chain[-1][2]: chain.append(x)
        self.assertEqual(m.consts[chain[0][5]][1], [0], 'the search starts at entry 0')
        self.assertEqual(len(chain), XSTEPS, 'every step of the search kept, each taking the step before it as its false operand')

    def test_loop_search(self):
        with tempfile.TemporaryDirectory() as t:
            d = Path(t)
            (d / 's.comp').write_text(XSHADER)
            subprocess.run(['glslangValidator', '-V', '--target-env', 'vulkan1.2', str(d / 's.comp'), '-o', str(d / 's.spv')], check=True, capture_output=True)
            m = spv.Module((d / 's.spv').read_bytes())
            spv.promote_locals(m)
            unrolled = spv.Module(m.data())
            self.assertEqual(spv.loop_pool_search(m), 1)
            self.assertEqual(spv.loop_pool_search(spv.Module(m.data())), 0, 'nothing left to make a loop of')
            (d / 'r.spv').write_bytes(m.data())
            r = subprocess.run(['spirv-val', '--target-env', 'vulkan1.3', str(d / 'r.spv')], capture_output=True, text=True)
            self.assertEqual(r.returncode, 0, r.stdout + r.stderr)
        m = spv.Module(m.data())
        (body,) = functions(m).values()
        loops = [x for x in body if x[0] & 0xffff == spv.OP_LOOP_MERGE]
        self.assertEqual(len(loops), 1)
        self.assertEqual(loops[0][3], spv.LOOP_DONT_UNROLL)
        mapvar = next(v['id'] for v in m.resources() if v['binding'] == 0)
        n_map = sum(1 for x in body if x[0] & 0xffff == spv.OP_ACCESS_CHAIN and x[3] == mapvar)
        (ubody,) = functions(unrolled).values()
        self.assertEqual(sum(1 for x in ubody if x[0] & 0xffff == spv.OP_ACCESS_CHAIN and x[3] == mapvar) - n_map, 2 * (XSTEPS - 1), 'one step\'s two table reads, not 13')
        table, pools, sink = pool_table(300)
        hinted = lambda a: table[((a >> 21) & 8191) * 8 + 6] >> 13 == (a >> 21) & 0x7ffff and table[((a >> 21) & 8191) * 8 + 6] & 8191
        addrs = [0x0f_ffff_fff0, pools[-1][1] + 0x100]
        for p in (1, 2, 63, 64, 65, 66, 128, 200, 255, 299, 300):
            s_, e_, _ = pools[p - 1]
            addrs += [s_, s_ + 4, (s_ + e_) // 2 & ~3, e_ - 4, e_, e_ + 0x1000]
        part = next(a for a in range(pools[199][0], pools[199][1], 0x10000) if not hinted(a))   # pool 200: a chunk it covers only partly
        addrs.append(part)
        self.assertTrue(any(hinted(a) for a in addrs), 'some addresses are found by the hint')
        for a in addrs:
            want = next((word_at(a + dl) for s_, e_, dl in pools if s_ <= a < e_), None)
            got = [run_helper(mod, table, a & 0xffffffff, a >> 32) for mod in (unrolled, m)]
            self.assertEqual(got[1], got[0], f'address {a:#x}: the loop as the unrolled search')
            if want is None: self.assertIn(got[1], (0, word_at(sink)), f'address {a:#x} in no pool: 0 or the sink')
            else: self.assertEqual(got[1], want, f'address {a:#x}')


def pool_table(n):
    """The GMEM_MAP table of n pools (entries 1..n, sorted, 4.5 MiB every 6 MiB from 64 GiB, each with its own delta), entry 0 holding the sink (words 6 and 7), the
    entries after the last never at or below an address, a hint (word 6: chunk << 13 | entry) for every 2 MiB chunk a pool covers whole → (words, [(start, end, delta)], sink)"""
    w = [0] * (8192 * 8)
    sink = 0x7_0000_0000
    w[6], w[7] = sink & 0xffffffff, sink >> 32
    pools = []
    for p in range(1, 8192):
        if p <= n:
            s = 0x10_0000_0000 + (p - 1) * 0x60_0000 + 0x8000; e = s + 0x48_0000; dl = 0x1_0000_0000 + p * 0x10_0000
            pools.append((s, e, dl))
            w[p * 8:p * 8 + 6] = [s & 0xffffffff, s >> 32, e & 0xffffffff, e >> 32, dl & 0xffffffff, dl >> 32]
        else: w[p * 8:p * 8 + 2] = [0xffffffff, 0xffffffff]
    for p, (s, e, _) in enumerate(pools, 1):
        for c in range((s + 0x1fffff) >> 21, e >> 21):
            if (c + 1) << 21 <= e: w[(c & 8191) * 8 + 6] = (c << 13 | p) & 0xffffffff
    return w, pools, sink


def word_at(addr): return (addr * 0x9e3779b97f4a7c15 >> 17) & 0xffffffff   # what the interpreter's memory holds at a physical address


def run_helper(m, table, lo, hi):
    """The module's one non-entry function run with (lo, hi) over the table (binding 0) by a small SPIR-V interpreter: its return value"""
    (body,) = functions(m).values()
    mapvar = next(v['id'] for v in m.resources() if v['binding'] == 0)
    M = 0xffffffff
    def sg(v): return v - (1 << 32) if v & 0x80000000 else v
    val = {i: (v[0] if len(v) == 1 else v) for i, (_, v) in m.consts.items()}
    for x in m.ins:
        if x[0] & 0xffff in (41, 42): val[x[2]] = x[0] & 0xffff == 41   # OpConstantTrue / False
    mem = {}
    labels = {x[1]: k for k, x in enumerate(body) if x[0] & 0xffff == spv.OP_LABEL}
    params = [x for x in body if x[0] & 0xffff == spv.OP_FUNCTION_PARAMETER]
    for x, v in zip(params, (lo, hi)):
        if m.op(x[1]) == spv.OP_TYPE_POINTER: mem[('v', x[2])] = v; val[x[2]] = ('v', x[2])
        else: val[x[2]] = v
    BIN = {128: lambda a, b: (a + b) & M, 130: lambda a, b: (a - b) & M, 132: lambda a, b: (a * b) & M, 194: lambda a, b: a >> b, 196: lambda a, b: (a << b) & M,
           197: lambda a, b: a | b, 198: lambda a, b: a ^ b, 199: lambda a, b: a & b, 170: lambda a, b: a == b, 171: lambda a, b: a != b, 176: lambda a, b: a < b,
           178: lambda a, b: a <= b, 177: lambda a, b: sg(a) < sg(b), 179: lambda a, b: sg(a) <= sg(b), 172: lambda a, b: a > b, 173: lambda a, b: sg(a) > sg(b),
           174: lambda a, b: a >= b, 175: lambda a, b: sg(a) >= sg(b), 166: lambda a, b: a or b, 167: lambda a, b: a and b, 164: lambda a, b: a == b, 165: lambda a, b: a != b}
    k = labels[body[next(i for i, x in enumerate(body) if x[0] & 0xffff == spv.OP_LABEL)][1]]
    blk = prev = None
    for _ in range(100000):
        x = body[k]; op = x[0] & 0xffff; k += 1
        if op == spv.OP_LABEL: prev, blk = blk, x[1]; continue
        if op in (spv.OP_SELECTION_MERGE, spv.OP_LOOP_MERGE): continue
        if op == spv.OP_PHI: val[x[2]] = next(val[v] for v, p in zip(x[3::2], x[4::2]) if p == prev); continue
        if op == spv.OP_VARIABLE: val[x[2]] = ('v', x[2]); continue
        if op == spv.OP_BRANCH: k = labels[x[1]]; continue
        if op == spv.OP_BRANCH_CONDITIONAL: k = labels[x[2] if val[x[1]] else x[3]]; continue
        if op == spv.OP_RETURN_VALUE: return val[x[1]]
        if op == spv.OP_STORE: mem[val[x[1]]] = val[x[2]]; continue
        r = x[2]
        if op == spv.OP_LOAD:
            p = val[x[3]]
            val[r] = table[p[1]] if p[0] == 'map' else word_at(p[1]) if p[0] == 'phys' else mem[p]
        elif op == spv.OP_ACCESS_CHAIN:
            b = val.get(x[3])
            if isinstance(b, tuple) and b[0] == 'phys' and len(x) == 5 and val[x[4]] == 0: val[r] = b   # Word(p).v
            else:
                assert x[3] == mapvar and len(x) == 6, x
                val[r] = ('map', val[x[5]])
        elif op in (spv.OP_BITCAST, spv.OP_COPY_OBJECT):
            v = val[x[3]]
            val[r] = v[0] | v[1] << 32 if isinstance(v, tuple) else v
        elif op == spv.OP_CONVERT_U_TO_PTR: val[r] = ('phys', val[x[3]])
        elif op == spv.OP_COMPOSITE_CONSTRUCT: val[r] = tuple(val[i] for i in x[3:])
        elif op == 154: val[r] = any(val[x[3]])   # OpAny
        elif op == 155: val[r] = all(val[x[3]])   # OpAll
        elif op == 168: val[r] = not val[x[3]]   # OpLogicalNot
        elif op == spv.OP_SELECT: val[r] = val[x[4]] if val[x[3]] else val[x[5]]
        elif op in BIN: val[r] = BIN[op](val[x[3]], val[x[4]])
        else: raise AssertionError(f'interpreter: opcode {op}')
    raise AssertionError('interpreter: no return')


if __name__ == '__main__':
    unittest.main()
