"""SPIR-V reading and the few rewrites the Android Vulkan library needs (vk_android_build.py): the descriptor set / binding renumbering into the Vulkan ABI
(dm/gfx/nvn/dmsh_abi_vk.h), vertex outputs that start as (0, 0, 0, 1), the GL depth and alpha test constants, the integer-output and dual-source variants, the
viewport mask of an emulated passthrough geometry shader, and the facts the reflection reads (resources, vertex inputs, colour outputs, capabilities). The translator's modules are taken as they come; every rewrite keeps the module valid for spirv-val --target-env vulkan1.3."""
import struct

MAGIC = 0x07230203
# opcodes
OP_NAME, OP_EXTENSION, OP_EXT_INST_IMPORT, OP_EXT_INST, OP_ENTRY_POINT, OP_EXECUTION_MODE, OP_CAPABILITY = 5, 10, 11, 12, 15, 16, 17
OP_TYPE_VOID, OP_TYPE_BOOL, OP_TYPE_INT, OP_TYPE_FLOAT, OP_TYPE_VECTOR, OP_TYPE_IMAGE, OP_TYPE_SAMPLER, OP_TYPE_SAMPLED_IMAGE = 19, 20, 21, 22, 23, 25, 26, 27
OP_TYPE_ARRAY, OP_TYPE_RUNTIME_ARRAY, OP_TYPE_STRUCT, OP_TYPE_POINTER = 28, 29, 30, 32
OP_CONSTANT, OP_CONSTANT_COMPOSITE, OP_FUNCTION, OP_FUNCTION_END, OP_VARIABLE, OP_LOAD, OP_STORE = 43, 44, 54, 56, 59, 61, 62
OP_ACCESS_CHAIN, OP_IN_BOUNDS_ACCESS_CHAIN, OP_DECORATE, OP_MEMBER_DECORATE = 65, 66, 71, 72
OP_COMPOSITE_EXTRACT, OP_COMPOSITE_INSERT, OP_COPY_OBJECT, OP_BITCAST, OP_FADD, OP_FMUL = 81, 82, 83, 124, 129, 133
OP_EMIT_VERTEX, OP_EMIT_STREAM_VERTEX, OP_LABEL, OP_RETURN, OP_RETURN_VALUE, OP_IMAGE_TEXEL_POINTER = 218, 220, 248, 253, 254, 60
OP_SPEC_CONSTANT_FALSE, OP_SELECT, OP_IMAGE_READ, OP_IMAGE_WRITE = 49, 169, 98, 99
# decorations, storage classes, builtins
DEC_SPEC_ID, DEC_BLOCK, DEC_BUFFER_BLOCK, DEC_BUILTIN, DEC_LOCATION, DEC_BINDING, DEC_SET = 1, 2, 3, 11, 30, 33, 34
SC_UNIFORM_CONSTANT, SC_INPUT, SC_UNIFORM, SC_OUTPUT, SC_FUNCTION, SC_PUSH_CONSTANT, SC_STORAGE_BUFFER = 0, 1, 2, 3, 7, 9, 12
BUILTIN_POSITION = 0
# VkDescriptorType
VK_SAMPLER, VK_COMBINED, VK_SAMPLED_IMAGE, VK_STORAGE_IMAGE, VK_UNIFORM_TEXEL, VK_STORAGE_TEXEL, VK_UNIFORM_BUFFER, VK_STORAGE_BUFFER = 0, 1, 2, 3, 4, 5, 6, 7
DIM_BUFFER = 5
EXEC_MODELS = {0: 'vs', 1: 'hs', 2: 'ds', 3: 'gs', 4: 'ps', 5: 'cs', 5364: 'ts', 5365: 'ms'}
CAPS = {1: 'Shader', 2: 'Geometry', 3: 'Tessellation', 25: 'ImageGatherExtended', 27: 'StorageImageMultisample', 34: 'ImageCubeArray', 35: 'SampleRateShading',
        43: 'Sampled1D', 44: 'Image1D', 45: 'SampledCubeArray', 46: 'SampledBuffer', 47: 'ImageBuffer', 48: 'ImageMSArray', 49: 'StorageImageExtendedFormats', 50: 'ImageQuery',
        51: 'DerivativeControl', 52: 'InterpolationFunction', 54: 'GeometryStreams', 4427: 'DrawParameters', 9: 'Float16', 10: 'Float64', 11: 'Int64', 12: 'Int64Atomics', 22: 'Int16', 39: 'Int8', 32: 'ClipDistance', 33: 'CullDistance', 53: 'TransformFeedback',
        55: 'StorageImageReadWithoutFormat', 56: 'StorageImageWriteWithoutFormat', 57: 'MultiViewport', 61: 'GroupNonUniform', 62: 'GroupNonUniformVote',
        63: 'GroupNonUniformArithmetic', 64: 'GroupNonUniformBallot', 65: 'GroupNonUniformShuffle', 66: 'GroupNonUniformShuffleRelative', 67: 'GroupNonUniformClustered',
        68: 'GroupNonUniformQuad', 4423: 'SubgroupBallotKHR', 4431: 'SubgroupVoteKHR', 5251: 'GeometryShaderPassthroughNV', 5254: 'ShaderViewportIndexLayerEXT',
        5255: 'ShaderViewportMaskNV', 5347: 'PhysicalStorageBufferAddresses', 5363: 'FragmentShaderSampleInterlockEXT', 5372: 'FragmentShaderShadingRateInterlockEXT',
        5378: 'FragmentShaderPixelInterlockEXT', 5379: 'DemoteToHelperInvocation', 6022: 'CooperativeMatrixKHR', 6033: 'AtomicFloat32AddEXT',
        5013: 'ImageGatherBiasLodAMD', 5568: 'IntegerFunctions2INTEL', 5283: 'MeshShadingEXT'}


def string_at(words, i):
    """A nul-terminated literal string starting at words[i] → (text, index of the word after it)"""
    out = bytearray()
    while i < len(words):
        b = struct.pack('<I', words[i]); i += 1
        if 0 in b: out += b[:b.index(0)]; break
        out += b
    return out.decode('utf-8', 'replace'), i


def lit_string(text):
    b = text.encode() + b'\0'
    b += b'\0' * (-len(b) % 4)
    return list(struct.unpack('<%dI' % (len(b) // 4), b))


class Module:
    def __init__(self, data):
        if len(data) % 4 or len(data) < 20: raise ValueError('not a SPIR-V module (length)')
        w = list(struct.unpack('<%dI' % (len(data) // 4), data))
        if w[0] != MAGIC: raise ValueError('not a SPIR-V module (magic)')
        self.header, self.ins, i = w[:5], [], 5
        while i < len(w):
            n = w[i] >> 16
            if not n or i + n > len(w): raise ValueError('malformed SPIR-V instruction at word %d' % i)
            self.ins.append(w[i:i + n]); i += n
        self.index()

    def index(self):
        self.names, self.decos, self.types, self.consts, self.vars, self.caps, self.exts = {}, {}, {}, {}, {}, [], []
        self.entry = None; self.modes = []
        for x in self.ins:
            op = x[0] & 0xffff
            if op == OP_NAME: self.names[x[1]] = string_at(x, 2)[0]
            elif op == OP_DECORATE: self.decos.setdefault(x[1], {})[x[2]] = x[3:]
            elif op == OP_CAPABILITY: self.caps.append(x[1])
            elif op == OP_EXTENSION: self.exts.append(string_at(x, 1)[0])
            elif op == OP_ENTRY_POINT: self.entry = x
            elif op == OP_EXECUTION_MODE: self.modes.append(x[2:])
            elif 19 <= op <= 39: self.types[x[1]] = x
            elif op == OP_CONSTANT: self.consts[x[2]] = (x[1], x[3:])
            elif op == OP_VARIABLE and len(x) >= 4: self.vars[x[2]] = (x[1], x[3])

    def data(self):
        return struct.pack('<%dI' % (5 + sum(len(x) for x in self.ins)), *self.header, *(v for x in self.ins for v in x))

    def new_id(self):
        i = self.header[3]; self.header[3] += 1; return i

    @property
    def model(self): return EXEC_MODELS.get(self.entry[1]) if self.entry else None

    def interface(self):
        _, k = string_at(self.entry, 3)
        return self.entry[k:]

    def op(self, ident): return self.types[ident][0] & 0xffff if ident in self.types else None

    def pointee(self, ptr):   # OpTypePointer → (storage class, type)
        t = self.types[ptr]; return t[2], t[3]

    def strip_arrays(self, t):
        """(element type, descriptor count): count 0 for a runtime array"""
        count = 1
        while self.op(t) in (OP_TYPE_ARRAY, OP_TYPE_RUNTIME_ARRAY):
            x = self.types[t]
            if self.op(t) == OP_TYPE_RUNTIME_ARRAY: count = 0; t = x[2]; continue
            n = self.consts.get(x[3], (0, [0]))[1][0]
            count *= n; t = x[2]
        return t, count

    def int_type(self, t):   # (width, signed) of an OpTypeInt, or None
        x = self.types.get(t)
        return (x[2], x[3]) if x and x[0] & 0xffff == OP_TYPE_INT else None

    def scalar_of(self, t):   # the component type of a scalar or vector
        return self.types[t][2] if self.op(t) == OP_TYPE_VECTOR else t

    def find_type(self, op, *operands):
        for i, x in self.types.items():
            if x[0] & 0xffff == op and x[2:] == list(operands): return i
        return None

    def add_type(self, op, *operands):
        """An existing type with these operands, or a new one placed after the last type / constant declaration"""
        i = self.find_type(op, *operands)
        if i is not None: return i
        i = self.new_id(); x = [(2 + len(operands)) << 16 | op, i, *operands]
        self._insert_decl(x); self.types[i] = x
        return i

    def add_const(self, ty, value):
        for i, (t, v) in self.consts.items():
            if t == ty and v == [value]: return i
        i = self.new_id(); x = [4 << 16 | OP_CONSTANT, ty, i, value]
        self._insert_decl(x); self.consts[i] = (ty, [value])
        return i

    def _insert_decl(self, x):   # after the last type, constant or global variable: before the first function
        at = next(k for k, y in enumerate(self.ins) if y[0] & 0xffff == OP_FUNCTION)
        self.ins.insert(at, x)

    def resources(self):
        """Descriptor variables → [{id, name, set, binding, desc, count, dim, rw}] in declaration order"""
        out = []
        for vid, (ptr, sc) in self.vars.items():
            d = self.decos.get(vid, {})
            if DEC_BINDING not in d: continue
            _, t = self.pointee(ptr)
            elem, count = self.strip_arrays(t)
            e = {'id': vid, 'name': self.names.get(vid, ''), 'set': d.get(DEC_SET, [0])[0], 'binding': d[DEC_BINDING][0], 'count': count, 'dim': None}
            eop = self.op(elem)
            if sc == SC_UNIFORM and self.op(elem) == OP_TYPE_STRUCT:
                e['desc'] = VK_STORAGE_BUFFER if DEC_BUFFER_BLOCK in self.decos.get(elem, {}) else VK_UNIFORM_BUFFER
            elif sc == SC_STORAGE_BUFFER: e['desc'] = VK_STORAGE_BUFFER
            elif sc == SC_UNIFORM_CONSTANT:
                if eop == OP_TYPE_SAMPLER: e['desc'] = VK_SAMPLER
                else:
                    img = self.types[elem][2] if eop == OP_TYPE_SAMPLED_IMAGE else elem
                    if self.op(img) != OP_TYPE_IMAGE: raise ValueError(f'resource {e["name"]}: type {eop} is not an image, sampler or block')
                    _, _, sampled_t, dim, depth, arrayed, ms, sampled = self.types[img][:8]
                    e['dim'] = {'dim': dim, 'depth': depth == 1, 'array': bool(arrayed), 'ms': bool(ms), 'type': self.int_type(sampled_t)}
                    if eop == OP_TYPE_SAMPLED_IMAGE: e['desc'] = VK_COMBINED
                    elif sampled == 2: e['desc'] = VK_STORAGE_TEXEL if dim == DIM_BUFFER else VK_STORAGE_IMAGE
                    else: e['desc'] = VK_UNIFORM_TEXEL if dim == DIM_BUFFER else VK_SAMPLED_IMAGE
            else: raise ValueError(f'resource {e["name"]}: storage class {sc}')
            out.append(e)
        return out

    def set_binding(self, vid, s, b):
        for x in self.ins:
            if x[0] & 0xffff == OP_DECORATE and x[1] == vid:
                if x[2] == DEC_SET: x[3] = s
                elif x[2] == DEC_BINDING: x[3] = b
        d = self.decos.setdefault(vid, {})
        if DEC_SET not in d:   # a resource without a set decoration is in set 0: give it its set explicitly
            at = next(k for k, y in enumerate(self.ins) if y[0] & 0xffff == OP_DECORATE and y[1] == vid)
            self.ins.insert(at, [4 << 16 | OP_DECORATE, vid, DEC_SET, s])
        d[DEC_SET] = [s]; d[DEC_BINDING] = [b]

    def io(self, sc):
        """Location-decorated Input / Output variables of the entry point → {location: (variable, value type)}"""
        out = {}
        for vid in self.interface():
            ptr, c = self.vars.get(vid, (None, None))
            if c != sc: continue
            d = self.decos.get(vid, {})
            if DEC_LOCATION in d: out[d[DEC_LOCATION][0]] = (vid, self.pointee(ptr)[1])
        return out

    def builtin_var(self, sc, builtin):
        """(variable, member index or None) of a builtin in the interface: a variable decorated with it, or a block member"""
        for vid in self.interface():
            ptr, c = self.vars.get(vid, (None, None))
            if c != sc: continue
            if self.decos.get(vid, {}).get(DEC_BUILTIN) == [builtin]: return vid, None
            t = self.pointee(ptr)[1]
            for x in self.ins:
                if x[0] & 0xffff == OP_MEMBER_DECORATE and x[1] == t and x[3] == DEC_BUILTIN and x[4] == builtin: return vid, x[2]
        return None, None

    def entry_function(self): return self.entry[2]

    def function_range(self, fid):   # (index of OpFunction, index of OpFunctionEnd)
        a = next(k for k, x in enumerate(self.ins) if x[0] & 0xffff == OP_FUNCTION and x[2] == fid)
        return a, next(k for k in range(a, len(self.ins)) if self.ins[k][0] & 0xffff == OP_FUNCTION_END)

    def uses(self):
        """id → [instruction indices that take it as an operand] (operand positions are not decoded: a literal equal to an id counts too, which only widens the search)"""
        u = {}
        for k, x in enumerate(self.ins):
            for v in x[1:]: u.setdefault(v, []).append(k)
        return u


def defined_id(x):
    """The result id of an instruction with a result type (or a type / label), else None: [opcode, type, result, ...] for most"""
    op = x[0] & 0xffff
    if 19 <= op <= 39 or op in (OP_LABEL, OP_EXT_INST_IMPORT): return x[1]
    if op in (OP_LOAD, OP_ACCESS_CHAIN, OP_IN_BOUNDS_ACCESS_CHAIN, OP_COMPOSITE_EXTRACT, OP_COPY_OBJECT, OP_BITCAST, OP_VARIABLE, OP_CONSTANT, OP_CONSTANT_COMPOSITE): return x[2]
    return None


def first_block_insert_point(m, fid):
    """The index in m.ins right after the entry function's first label and its OpVariable declarations"""
    a, _ = m.function_range(fid)
    k = next(k for k in range(a, len(m.ins)) if m.ins[k][0] & 0xffff == OP_LABEL) + 1
    while m.ins[k][0] & 0xffff == OP_VARIABLE: k += 1
    return k


def vec4_const(m, f32, vals):
    vt = m.add_type(OP_TYPE_VECTOR, f32, 4)
    ids = [m.add_const(f32, struct.unpack('<I', struct.pack('<f', v))[0]) for v in vals]
    for i, x in enumerate(m.ins):
        if x[0] & 0xffff == OP_CONSTANT_COMPOSITE and x[1] == vt and x[3:] == ids: return x[2]
    c = m.new_id(); m._insert_decl([7 << 16 | OP_CONSTANT_COMPOSITE, vt, c, *ids])
    return c


def init_outputs(m, extra_locations=()):
    """Vertex outputs start as (0, 0, 0, 1) before the body runs, as an unwritten component reads on the hardware (dmsh_build.py vs_init); extra_locations get new
    vec4 outputs holding that value, for paired pixel shaders that read what this shader does not write (vs_pad). → the locations added"""
    outs = m.io(SC_OUTPUT)
    f32 = m.find_type(OP_TYPE_FLOAT, 32)
    if f32 is None: return []
    one = vec4_const(m, f32, (0.0, 0.0, 0.0, 1.0))
    vt = m.find_type(OP_TYPE_VECTOR, f32, 4)
    added = []
    for loc in sorted(set(extra_locations) - set(outs)):
        ptr = m.add_type(OP_TYPE_POINTER, SC_OUTPUT, vt)
        v = m.new_id(); m._insert_decl([4 << 16 | OP_VARIABLE, ptr, v, SC_OUTPUT]); m.vars[v] = (ptr, SC_OUTPUT)
        at = next(k for k, y in enumerate(m.ins) if y[0] & 0xffff == OP_DECORATE)
        m.ins.insert(at, [4 << 16 | OP_DECORATE, v, DEC_LOCATION, loc]); m.decos[v] = {DEC_LOCATION: [loc]}
        name = lit_string(f'dm_pad{loc}'); m.ins.insert(at, [(2 + len(name)) << 16 | OP_NAME, v, *name])
        ep = m.entry; ep.append(v); ep[0] = len(ep) << 16 | OP_ENTRY_POINT
        outs[loc] = (v, vt); added.append(loc)
    k = first_block_insert_point(m, m.entry_function())
    stores = [[3 << 16 | OP_STORE, v, one] for loc, (v, t) in sorted(outs.items()) if t == vt]
    m.ins[k:k] = stores
    return added


def gl_depth(m, spec_id):
    """The GL depth convention (near = -w, NVN's default) under a specialization constant: z' = fma(z, 0.5, w * 0.5) of the position output, selected when the bool
    constant spec_id is true (default false: z as translated, NVN's near = 0), before every return of the entry function or before every vertex a geometry shader emits
    (dmsh_build.py vs_depth, the same arithmetic; there it is a second entry point). → True when the module writes a position"""
    pos, member = m.builtin_var(SC_OUTPUT, BUILTIN_POSITION)
    if pos is None: return False
    f32 = m.find_type(OP_TYPE_FLOAT, 32); vt = m.find_type(OP_TYPE_VECTOR, f32, 4)
    pvec = m.add_type(OP_TYPE_POINTER, SC_OUTPUT, vt)
    half = m.add_const(f32, 0x3f000000)
    i32 = m.find_type(OP_TYPE_INT, 32, 1) or m.add_type(OP_TYPE_INT, 32, 1)
    midx = m.add_const(i32, member) if member is not None else None
    glsl = ensure_glsl(m)
    boolean = m.add_type(OP_TYPE_BOOL)
    spec = m.new_id(); m._insert_decl([3 << 16 | OP_SPEC_CONSTANT_FALSE, boolean, spec])
    at = next(k for k, y in enumerate(m.ins) if y[0] & 0xffff == OP_DECORATE)
    m.ins.insert(at, [4 << 16 | OP_DECORATE, spec, DEC_SPEC_ID, spec_id]); m.decos[spec] = {DEC_SPEC_ID: [spec_id]}
    name = lit_string('dm_gl_depth'); m.ins.insert(next(k for k, y in enumerate(m.ins) if y[0] & 0xffff in (OP_NAME, OP_DECORATE)), [(2 + len(name)) << 16 | OP_NAME, spec, *name])
    a, e = m.function_range(m.entry_function())
    stop = (OP_EMIT_VERTEX, OP_EMIT_STREAM_VERTEX) if m.model == 'gs' else (OP_RETURN, OP_RETURN_VALUE)
    out, n = [], 0
    for k, x in enumerate(m.ins):
        if a < k < e and x[0] & 0xffff in stop:
            p = pos
            if midx is not None: p = m.new_id(); out.append([5 << 16 | OP_ACCESS_CHAIN, pvec, p, pos, midx])
            v, z, w, wh, z2, z3, v2 = (m.new_id() for _ in range(7))
            out += [[4 << 16 | OP_LOAD, vt, v, p], [5 << 16 | OP_COMPOSITE_EXTRACT, f32, z, v, 2], [5 << 16 | OP_COMPOSITE_EXTRACT, f32, w, v, 3],
                    [5 << 16 | OP_FMUL, f32, wh, w, half], [8 << 16 | OP_EXT_INST, f32, z2, glsl, 50, z, half, wh],   # GLSL.std.450 50: Fma
                    [6 << 16 | OP_SELECT, f32, z3, spec, z2, z], [6 << 16 | OP_COMPOSITE_INSERT, vt, v2, z3, v, 2], [3 << 16 | OP_STORE, p, v2]]
            n += 1
        out.append(x)
    m.ins = out
    return n > 0


def value_uses(m):
    """id → [(instruction, operand position)] for the instructions of function bodies whose operands are ids at known positions"""
    u = {}
    for x in m.ins:
        op = x[0] & 0xffff
        if op in (OP_LOAD,): ids = [(3, x[3])]
        elif op == OP_STORE: ids = [(1, x[1]), (2, x[2])]
        elif op in (OP_ACCESS_CHAIN, OP_IN_BOUNDS_ACCESS_CHAIN): ids = [(p, x[p]) for p in range(3, len(x))]
        elif op == OP_COMPOSITE_EXTRACT: ids = [(3, x[3])]
        elif op in (OP_COPY_OBJECT, OP_BITCAST): ids = [(3, x[3])]
        elif op == OP_ENTRY_POINT or op == OP_NAME or op == OP_DECORATE or op == OP_MEMBER_DECORATE: continue
        else: ids = [(p, x[p]) for p in range(1, len(x))]   # conservative: every word may be an id
        for p, v in ids: u.setdefault(v, []).append((x, p))
    return u


def integer_only_inputs(m):
    """Vertex inputs declared float that the shader only uses as integer bits (dmsh_build.py int_inputs, here on the SPIR-V): every value loaded from the input, whole or
    by component, is only bit-cast to an integer type, copied, or stored to a function variable whose loads are such values in turn. → {location: 'uint'}"""
    u = value_uses(m)
    def int_bits(val, seen):
        if val in seen: return True
        seen.add(val)
        users = u.get(val, [])
        if not users: return False
        for x, p in users:
            op = x[0] & 0xffff
            if op == OP_BITCAST and m.int_type(m.scalar_of(x[1])): continue
            if op in (OP_COPY_OBJECT, OP_COMPOSITE_EXTRACT) and p == 3:
                if not int_bits(x[2], seen): return False
                continue
            if op == OP_STORE and p == 2:   # into a local: its loads must be integer uses too
                var = x[1]
                if m.vars.get(var, (0, None))[1] != SC_FUNCTION: return False
                loads = [y[2] for y, q in u.get(var, []) if y[0] & 0xffff == OP_LOAD and q == 3]
                if any(y[0] & 0xffff not in (OP_LOAD, OP_STORE) for y, _ in u.get(var, [])): return False
                if not all(int_bits(l, seen) for l in loads): return False
                continue
            return False
        return True
    out = {}
    for loc, (var, t) in m.io(SC_INPUT).items():
        if m.int_type(m.scalar_of(t)): continue
        vals, ok = [], True
        for x, p in u.get(var, []):
            op = x[0] & 0xffff
            if op == OP_LOAD and p == 3: vals.append(x[2])
            elif op in (OP_ACCESS_CHAIN, OP_IN_BOUNDS_ACCESS_CHAIN) and p == 3:
                for y, q in u.get(x[2], []):
                    if y[0] & 0xffff == OP_LOAD and q == 3: vals.append(y[2])
                    else: ok = False
            else: ok = False
        if ok and vals and all(int_bits(v, set()) for v in vals): out[str(loc)] = 'uint'
    return out


def int_outputs(m):
    """Colour outputs (fragment Output locations) some store writes from integer bits (a bit cast of an integer value, whole or through an access chain)"""
    u = value_uses(m); defs = {}
    for x in m.ins:
        d = defined_id(x)
        if d is not None: defs[d] = x
    found = set()
    for loc, (var, _) in m.io(SC_OUTPUT).items():
        ptrs = [var] + [x[2] for x, p in u.get(var, []) if x[0] & 0xffff in (OP_ACCESS_CHAIN, OP_IN_BOUNDS_ACCESS_CHAIN) and p == 3]
        for ptr in ptrs:
            for x, p in u.get(ptr, []):
                if x[0] & 0xffff == OP_STORE and p == 1:
                    d = defs.get(x[2])
                    if d and d[0] & 0xffff == OP_BITCAST: found.add(loc)   # the translator bit-casts only between integers and floats
    return found


def functions_named(m):
    return {m.names.get(x[2], ''): x[2] for x in m.ins if x[0] & 0xffff == OP_FUNCTION}


def ensure_glsl(m):
    """The GLSL.std.450 import's id, added before OpMemoryModel when the module has none"""
    for x in m.ins:
        if x[0] & 0xffff == OP_EXT_INST_IMPORT and string_at(x, 2)[0] == 'GLSL.std.450': return x[1]
    i = m.new_id(); s = lit_string('GLSL.std.450')
    at = next(k for k, x in enumerate(m.ins) if x[0] & 0xffff == 14)   # OpMemoryModel
    m.ins.insert(at, [(2 + len(s)) << 16 | OP_EXT_INST_IMPORT, i, *s])
    return i


SUBGROUP_OPS = set(range(333, 367)) | {4421, 4422, 4428, 4429, 4430, 4432}   # OpGroupNonUniform*, the KHR ballot / vote instructions
SUBGROUP_BUILTINS = {36, 37, 40, 41, 4416, 4417, 4418, 4419, 4420}   # SubgroupSize, NumSubgroups, SubgroupId, SubgroupLocalInvocationId, the lane masks
CAP_SUBGROUP = {61, 62, 63, 64, 65, 66, 67, 68, 4423, 4431}
CAP_FLOAT64, CAP_INTERLOCK = 10, {5363, 5372, 5378}
OP_TYPE_FLOAT64_WIDTH = 64
INTERLOCK_OPS, INTERLOCK_MODES = {5364, 5365}, {5366, 5367, 5368, 5369, 5370, 5371}   # Begin / EndInvocationInterlockEXT; the interlock execution modes


def subgroup_used(m):
    return any(x[0] & 0xffff in SUBGROUP_OPS for x in m.ins) or any(d.get(DEC_BUILTIN, [None])[0] in SUBGROUP_BUILTINS for d in m.decos.values()) or \
        any(x[0] & 0xffff == OP_MEMBER_DECORATE and x[3] == DEC_BUILTIN and x[4] in SUBGROUP_BUILTINS for x in m.ins)


def strip_unused_caps(m):
    """Capabilities the translator declares in every module whatever it uses (its host answers say the device has them), dropped where nothing needs them, so a module asks the device
    only for what it uses: Float64 without a 64-bit float type (the phone has no shaderFloat64; a shader that used doubles was already lowered, Program.cs softfloat64), fragment shader
    interlock without an interlock instruction (with its extension and modes), the subgroup capabilities without a subgroup instruction or builtin. → names of those dropped"""
    drop = set()
    if not any(x[0] & 0xffff == OP_TYPE_FLOAT and x[2] == 64 for x in m.ins): drop.add(CAP_FLOAT64)
    if not any(x[0] & 0xffff in INTERLOCK_OPS for x in m.ins): drop |= CAP_INTERLOCK
    if not subgroup_used(m): drop |= CAP_SUBGROUP
    drop &= set(m.caps)
    keep = []
    for x in m.ins:
        op = x[0] & 0xffff
        if op == OP_CAPABILITY and x[1] in drop: continue
        if op == OP_EXTENSION and drop & CAP_INTERLOCK and string_at(x, 1)[0] == 'SPV_EXT_fragment_shader_interlock': continue
        if op == OP_EXECUTION_MODE and drop & CAP_INTERLOCK and x[2] in INTERLOCK_MODES: continue
        keep.append(x)
    m.ins = keep; m.index()
    return sorted(CAPS.get(c, str(c)) for c in drop)


GLSL_PRECISE = {37: 79, 40: 80, 43: 81}   # GLSL.std.450 FMin / FMax / FClamp → NMin / NMax / NClamp


def precise_minmax(m):
    """min, max and clamp with Maxwell's NaN results: FMNMX returns the other operand of a NaN and saturation gives 0, which GLSL.std.450's NMin / NMax / NClamp
    define and FMin / FMax / FClamp leave to the device (dmsh_build.py precise, the same rule for Metal's fast:: functions). → instructions rewritten"""
    glsl = {x[1] for x in m.ins if x[0] & 0xffff == OP_EXT_INST_IMPORT and string_at(x, 2)[0] == 'GLSL.std.450'}
    n = 0
    for x in m.ins:
        if x[0] & 0xffff == OP_EXT_INST and x[3] in glsl and x[4] in GLSL_PRECISE and m.op(x[1]) != OP_TYPE_INT and m.op(m.scalar_of(x[1])) == OP_TYPE_FLOAT:
            x[4] = GLSL_PRECISE[x[4]]; n += 1
    return n


def const_int(m, ident):
    c = m.consts.get(ident)
    return c[1][0] if c and m.int_type(c[0]) else None


def block_rows(m, vid):
    """The 16-byte rows of a uniform block declared as vec4[n] (the translator's constant buffers) that the shader reads: (sorted byte offsets of rows read at
    constant indices, whether any index is computed), from the access chains on the variable"""
    rows, dyn = set(), False
    for x in m.ins:
        if x[0] & 0xffff in (OP_ACCESS_CHAIN, OP_IN_BOUNDS_ACCESS_CHAIN) and x[3] == vid:
            if len(x) < 6: dyn = True; continue
            r = const_int(m, x[5])
            if r is None: dyn = True
            else: rows.add(r * 16 + (4 * const_int(m, x[6]) if len(x) > 6 and const_int(m, x[6]) is not None else 0))
        elif x[0] & 0xffff == OP_LOAD and x[3] == vid: dyn = True   # the whole block
    return sorted(rows), dyn


CAP_READ_WITHOUT_FORMAT, CAP_WRITE_WITHOUT_FORMAT = 55, 56


def formatless_storage_caps(m):
    """The capabilities a read or write of a storage image declared with format Unknown needs, which the translator leaves out (its images take the format of the answer
    table, and without an answer it declares none). → names of those added (the device features shaderStorageImageRead / WriteWithoutFormat)"""
    imgs = {i for i, x in m.types.items() if x[0] & 0xffff == OP_TYPE_IMAGE and x[7] == 2 and x[8] == 0}   # sampled 2 = storage, format 0 = Unknown
    if not imgs: return []
    typed = {}
    for x in m.ins:
        if x[0] & 0xffff == OP_LOAD and x[1] in imgs: typed[x[2]] = True
    need = set()
    for x in m.ins:
        op = x[0] & 0xffff
        if op == OP_IMAGE_READ and x[3] in typed: need.add(CAP_READ_WITHOUT_FORMAT)
        elif op == OP_IMAGE_WRITE and x[1] in typed: need.add(CAP_WRITE_WITHOUT_FORMAT)
    need -= set(m.caps)
    if not need: return []
    at = max(k for k, y in enumerate(m.ins) if y[0] & 0xffff == OP_CAPABILITY) + 1
    for c in sorted(need): m.ins.insert(at, [2 << 16 | OP_CAPABILITY, c])
    m.index()
    return sorted(CAPS[c] for c in need)


def maybe_ids(x):
    """The words of a function-body instruction that may be ids (the literal operands of the instructions whose literals could pass for an id left out; others whole)"""
    op = x[0] & 0xffff
    if op == OP_EXT_INST: return [x[1], x[3]] + x[5:]
    if op == OP_COMPOSITE_EXTRACT: return x[1:4]
    if op in (OP_COMPOSITE_INSERT, 79): return x[1:5]   # 79: OpVectorShuffle
    if op == 251: return [x[1]] + x[2:3] + x[4::2]   # OpSwitch: selector, default, the targets after each literal
    mask_at = {87: 5, 88: 5, 91: 5, 92: 5, 95: 5, 98: 5, 89: 6, 90: 6, 93: 6, 94: 6, 96: 6, 97: 6, 99: 4}.get(op)   # image sample / fetch / gather / read / write: the image operands mask
    if mask_at is not None and len(x) > mask_at: return x[1:mask_at] + x[mask_at + 1:]
    return x[1:]


SC_PRIVATE = 6
BUILTIN_VIEWPORT_MASK_NV = 5253
CAP_VIEWPORT_MASK_NV, CAP_VIEWPORT_INDEX_LAYER = 5255, 5254


def private_viewport_mask(m):
    """A geometry shader whose passthrough Ryujinx emulates (Program.cs gs_passthrough false: the device has no SPV_NV_geometry_shader_passthrough) keeps NVN's viewport mask
    output (0x3a0) for one use: it emits the primitive only when the mask is not 0 (EmitterContext.PrepareForReturn; a cull, as the hardware sends a primitive with an empty mask
    to no viewport). Without SPV_NV_viewport_array2 (Adreno) the ViewportMaskNV output becomes a Private variable of the same type: the cull stays, the mask leaves the
    shader. What is lost is the routing of a primitive to the viewports the mask names (a single-viewport draw sees no difference). → True when rewritten"""
    var = next((v for v, d in m.decos.items() if d.get(DEC_BUILTIN) == [BUILTIN_VIEWPORT_MASK_NV] and m.vars.get(v, (0, None))[1] == SC_OUTPUT), None)
    if var is None: return False
    ptr, _ = m.vars[var]; t = m.pointee(ptr)[1]
    pptr = m.add_type(OP_TYPE_POINTER, SC_PRIVATE, t)
    chains, in_fn = [], False   # access chains on the variable; any other use in a function body but a load or store is not the shape this rewrite knows
    for x in m.ins:
        op = x[0] & 0xffff
        in_fn = (in_fn or op == OP_FUNCTION) and op != OP_FUNCTION_END
        if op in (OP_ACCESS_CHAIN, OP_IN_BOUNDS_ACCESS_CHAIN) and x[3] == var:
            if m.pointee(x[1])[0] != SC_OUTPUT: raise ValueError('viewport mask access chain not in Output')
            chains.append(x)
        elif in_fn and var in maybe_ids(x) and op not in (OP_LOAD, OP_STORE): raise ValueError(f'viewport mask used by opcode {op}')
    twins = {}   # Output pointer type of a chain → its Private twin (added after the scan: add_type inserts into m.ins)
    for x in chains:
        if x[1] not in twins: twins[x[1]] = m.add_type(OP_TYPE_POINTER, SC_PRIVATE, m.pointee(x[1])[1])
        x[1] = twins[x[1]]
    keep = []
    for x in m.ins:
        op = x[0] & 0xffff
        if op == OP_DECORATE and x[1] == var: continue
        if op == OP_VARIABLE and x[2] == var: continue   # declared again below, after its new pointer type
        if op == OP_ENTRY_POINT and m.header[1] < 0x10400:   # before SPIR-V 1.4 the interface lists only Input / Output variables (the translator writes 1.3)
            _, k = string_at(x, 3)
            x = x[:k] + [v for v in x[k:] if v != var]; x[0] = len(x) << 16 | OP_ENTRY_POINT
        keep.append(x)
    m.ins = keep; m._insert_decl([4 << 16 | OP_VARIABLE, pptr, var, SC_PRIVATE]); m.index()
    if not any(d.get(DEC_BUILTIN) == [BUILTIN_VIEWPORT_MASK_NV] for d in m.decos.values()) and \
            not any(x[0] & 0xffff == OP_MEMBER_DECORATE and x[3] == DEC_BUILTIN and x[4] == BUILTIN_VIEWPORT_MASK_NV for x in m.ins):
        layer = CAP_VIEWPORT_INDEX_LAYER in m.caps
        m.ins = [x for x in m.ins if not (x[0] & 0xffff == OP_CAPABILITY and x[1] == CAP_VIEWPORT_MASK_NV) and
                 not (x[0] & 0xffff == OP_EXTENSION and not layer and string_at(x, 1)[0] == 'SPV_NV_viewport_array2')]
        m.index()
    return True


OP_CONSTANT_TRUE, OP_CONSTANT_FALSE, OP_SPEC_CONSTANT, OP_CONSTANT_NULL = 41, 42, 50, 46
OP_IEQUAL, OP_FORD_EQUAL, OP_FUNORD_NOT_EQUAL, OP_FORD_LESS, OP_FORD_GREATER, OP_FORD_LESS_EQUAL, OP_FORD_GREATER_EQUAL = 170, 180, 183, 184, 186, 188, 190
OP_SELECTION_MERGE, OP_BRANCH_CONDITIONAL, OP_KILL = 247, 250, 252
DEC_INDEX = 32


def _float_vec(m, t):
    """(component count, float32 type) of a float scalar or vector type, else None"""
    s = m.scalar_of(t)
    if m.op(s) != OP_TYPE_FLOAT or m.types[s][2] != 32: return None
    return (m.types[t][3] if m.op(t) == OP_TYPE_VECTOR else 1), s


def const_written_outputs(m):
    """Colour outputs (locations) every store to which, whole or by component, writes a constant: a clear drawn into an integer target writes 0 that way
    (dmsh_build.py fs_uint's second rule). → the locations, empty unless every output written is such"""
    consts = {x[2] for x in m.ins if x[0] & 0xffff in (OP_CONSTANT, OP_CONSTANT_COMPOSITE, OP_CONSTANT_NULL)}
    outs = m.io(SC_OUTPUT); ptrs = {}
    for loc, (var, _) in outs.items(): ptrs[var] = loc
    for x in m.ins:
        if x[0] & 0xffff in (OP_ACCESS_CHAIN, OP_IN_BOUNDS_ACCESS_CHAIN) and x[3] in ptrs: ptrs[x[2]] = ptrs[x[3]]
    stored = {}
    for x in m.ins:
        if x[0] & 0xffff == OP_STORE and x[1] in ptrs: stored.setdefault(ptrs[x[1]], []).append(x[2] in consts)
    return set(stored) if stored and all(all(v) for v in stored.values()) else set()


def retype_outputs(m, locs):
    """The fragment outputs at locs, declared float, retyped to the unsigned integer vector of the same width: every store bit-casts its value, every load bit-casts back
    (dmsh_build.py fs_uint: the same bits, nothing converted). An integer attachment takes only an integer output (Vulkan: the numeric class must match). → locations retyped"""
    outs = m.io(SC_OUTPUT); u32 = m.add_type(OP_TYPE_INT, 32, 0)
    want = {}
    for loc in sorted(locs):
        if loc not in outs: continue
        var, t = outs[loc]; fv = _float_vec(m, t)
        if fv: want[var] = (loc, t, fv[0])
    if not want: return []
    def utype(n): return u32 if n == 1 else m.add_type(OP_TYPE_VECTOR, u32, n)
    newptr = {var: m.add_type(OP_TYPE_POINTER, SC_OUTPUT, utype(n)) for var, (_, _, n) in want.items()}
    ptr_of = {var: (t, utype(n)) for var, (_, t, n) in want.items()}   # pointer id → (float value type, uint value type)
    chains = []
    for x in m.ins:
        if x[0] & 0xffff in (OP_ACCESS_CHAIN, OP_IN_BOUNDS_ACCESS_CHAIN) and x[3] in ptr_of:
            et = m.pointee(x[1])[1]; fv = _float_vec(m, et)
            if not fv: raise ValueError('colour output access chain to a non-float')
            chains.append((x, et, fv[0]))
    for x, et, n in chains:
        ptr_of[x[2]] = (et, utype(n)); x[1] = m.add_type(OP_TYPE_POINTER, SC_OUTPUT, utype(n))
    out, in_fn = [], False
    for x in m.ins:
        op = x[0] & 0xffff
        in_fn = (in_fn or op == OP_FUNCTION) and op != OP_FUNCTION_END
        if op == OP_VARIABLE and x[2] in want: continue   # declared again below, after its new pointer type
        if op == OP_STORE and x[1] in ptr_of:
            v = m.new_id(); out.append([4 << 16 | OP_BITCAST, ptr_of[x[1]][1], v, x[2]]); out.append([3 << 16 | OP_STORE, x[1], v, *x[3:]]); continue
        if op == OP_LOAD and x[3] in ptr_of:
            v = m.new_id(); out.append([x[0], ptr_of[x[3]][1], v, *x[3:]]); out.append([4 << 16 | OP_BITCAST, x[1], x[2], v]); continue
        if op in (OP_STORE, OP_LOAD) or op in (OP_ACCESS_CHAIN, OP_IN_BOUNDS_ACCESS_CHAIN): out.append(x); continue
        if in_fn and any(v in ptr_of for v in maybe_ids(x)):
            raise ValueError(f'colour output used by opcode {op}')
        out.append(x)
    m.ins = out
    for var in want: m._insert_decl([4 << 16 | OP_VARIABLE, newptr[var], var, SC_OUTPUT])
    m.index()
    return sorted(loc for loc, _, _ in want.values())


DEC_PER_PRIMITIVE, CAP_MESH_SHADING = 5271, 5283   # PerPrimitiveEXT, MeshShadingEXT (SPV_EXT_mesh_shader)


def per_primitive_inputs(m, locs):
    """The inputs of a pixel shader that a mesh program writes per primitive (shader_split.py mesh_facts: the flat inputs of the mesh's pixel shader): Vulkan matches a
    PerPrimitiveEXT mesh output only with an input decorated the same, so each such input gets PerPrimitiveEXT, and the module the capability and extension. Done only for a
    shader drawn with mesh programs alone (vk_android_build.py MESH_PRIM): it is not valid in a vertex pipeline any more. → the locations decorated"""
    ins = m.io(SC_INPUT)
    done = sorted(l for l in locs if l in ins)
    if not done: return []
    at = next(k for k, y in enumerate(m.ins) if y[0] & 0xffff == OP_DECORATE)
    for l in done: m.ins.insert(at, [3 << 16 | OP_DECORATE, ins[l][0], DEC_PER_PRIMITIVE])
    if CAP_MESH_SHADING not in m.caps:
        k = max(k for k, y in enumerate(m.ins) if y[0] & 0xffff == OP_CAPABILITY)
        m.ins.insert(k + 1, [2 << 16 | OP_CAPABILITY, CAP_MESH_SHADING])
    if 'SPV_EXT_mesh_shader' not in m.exts:
        k = max(k for k, y in enumerate(m.ins) if y[0] & 0xffff in (OP_CAPABILITY, OP_EXTENSION))
        e = lit_string('SPV_EXT_mesh_shader'); m.ins.insert(k + 1, [(1 + len(e)) << 16 | OP_EXTENSION, *e])
    if m.header[1] < 0x10400:   # the extension needs SPIR-V 1.4, whose entry point lists every global variable it uses, not only Input / Output: all of them are listed
        m.header[1] = 0x10400
        have = set(m.interface())
        glob = [x[2] for x in m.ins if x[0] & 0xffff == OP_VARIABLE and x[3] != SC_FUNCTION and x[2] not in have]
        k = m.ins.index(m.entry)
        m.ins[k] = m.entry + glob; m.ins[k][0] = len(m.ins[k]) << 16 | OP_ENTRY_POINT
        m.index(); buffer_block_to_storage(m)
    m.index()
    return done


OP_PTR_ACCESS_CHAIN, DEC_BLOCK_ = 67, 2


def buffer_block_to_storage(m):
    """SPIR-V 1.4 has no BufferBlock: a Uniform-class block decorated BufferBlock (this translator's storage buffers) becomes a StorageBuffer-class Block, and every pointer
    into it (its variables, the access chains from them) gets a StorageBuffer pointer type of the same pointee. → the number of blocks rewritten"""
    blocks = {x[1] for x in m.ins if x[0] & 0xffff == OP_DECORATE and x[2] == DEC_BUFFER_BLOCK}
    if not blocks: return 0
    def sb_ptr(uptr):   # the StorageBuffer pointer of a Uniform pointer type's pointee, declared right after it (a global variable may use it)
        t = m.types[uptr][3]
        i = m.find_type(OP_TYPE_POINTER, SC_STORAGE_BUFFER, t)
        if i is not None: return i
        i = m.new_id(); x = [4 << 16 | OP_TYPE_POINTER, i, SC_STORAGE_BUFFER, t]
        m.ins.insert(m.ins.index(m.types[uptr]) + 1, x); m.types[i] = x
        return i
    def elem(t):   # an array of blocks (a descriptor array): its element
        while m.op(t) in (OP_TYPE_ARRAY, OP_TYPE_RUNTIME_ARRAY): t = m.types[t][2]
        return t
    ptrs = {i: sb_ptr(i) for i, x in list(m.types.items()) if x[0] & 0xffff == OP_TYPE_POINTER and x[2] == SC_UNIFORM and elem(x[3]) in blocks}
    sb = set()
    for x in list(m.ins):   # a copy: sb_ptr may insert a pointer type
        op = x[0] & 0xffff
        if op == OP_DECORATE and x[1] in blocks and x[2] == DEC_BUFFER_BLOCK: x[2] = DEC_BLOCK_
        elif op == OP_VARIABLE and x[1] in ptrs and x[3] == SC_UNIFORM: x[1] = ptrs[x[1]]; x[3] = SC_STORAGE_BUFFER; sb.add(x[2])
        elif op in (OP_ACCESS_CHAIN, OP_IN_BOUNDS_ACCESS_CHAIN, OP_PTR_ACCESS_CHAIN, OP_COPY_OBJECT) and x[3] in sb:
            x[1] = sb_ptr(x[1]); sb.add(x[2])
    m.index()
    return len(blocks)


def dual_source(m):
    """Dual-source blending (dmsh_build.py fs_dual): NVN's SRC1 factors read output 1 as target 0's second source; Vulkan reads it only from Location 0 Index 1. For a shader
    writing exactly colours 0 and 1: output 1 moved to Location 0, Index 1. → True when rewritten"""
    outs = m.io(SC_OUTPUT)
    if sorted(outs) != [0, 1]: return False
    var = outs[1][0]
    for k, x in enumerate(m.ins):
        if x[0] & 0xffff == OP_DECORATE and x[1] == var and x[2] == DEC_LOCATION:
            x[3] = 0; m.ins.insert(k + 1, [4 << 16 | OP_DECORATE, var, DEC_INDEX, 1]); break
    m.index()
    return True


ALPHA_CMP = {2: OP_FORD_LESS, 3: OP_FORD_EQUAL, 4: OP_FORD_LESS_EQUAL, 5: OP_FORD_GREATER, 6: OP_FUNORD_NOT_EQUAL, 7: OP_FORD_GREATER_EQUAL}   # NVN_ALPHA_FUNC_* → comparison of alpha with the reference


def alpha_test(m, spec_id):
    """NVN's alpha test (ColorStateSetAlphaTest / SetAlphaRef; dmsh_alpha.py on Metal): colour 0's alpha compared with a reference before blending, the fragment dropped when the
    comparison fails. The function is a uint specialization constant spec_id (NVN_ALPHA_FUNC_*: 1 never, 2 less … 7 gequal, 8 always; default 0 = no test, which the driver
    folds away), part of the pipeline as the colour state is; the reference is per draw, the float bits of the support buffer's word 0 (Ryujinx's unused alpha_test field), so a
    reference that changes every draw (a dissolve) keeps the pipeline. The test runs before every return of the entry function. → True when added (a float vec4 colour 0 and
    the support buffer present)"""
    outs = m.io(SC_OUTPUT)
    if 0 not in outs: return False
    c0, t = outs[0]; fv = _float_vec(m, t)
    if not fv or fv[0] != 4: return False
    f32 = fv[1]
    sb = next((v for v, (p, sc) in m.vars.items() if sc == SC_UNIFORM and m.names.get(v) == 'support_buffer'), None)
    if sb is None: return False
    st = m.pointee(m.vars[sb][0])[1]
    if m.op(st) != OP_TYPE_STRUCT or m.int_type(m.types[st][2]) != (32, 0): return False
    u32 = m.types[st][2]; i32 = m.find_type(OP_TYPE_INT, 32, 1) or m.add_type(OP_TYPE_INT, 32, 1)
    boolean = m.add_type(OP_TYPE_BOOL); pu = m.add_type(OP_TYPE_POINTER, SC_UNIFORM, u32)
    zero = m.add_const(i32, 0)
    fconst = {n: m.add_const(u32, n) for n in [1] + list(ALPHA_CMP)}
    spec = m.new_id(); m._insert_decl([4 << 16 | OP_SPEC_CONSTANT, u32, spec, 0])
    t_ = next((x[2] for x in m.ins if x[0] & 0xffff == OP_CONSTANT_TRUE and x[1] == boolean), None)
    if t_ is None: t_ = m.new_id(); m._insert_decl([3 << 16 | OP_CONSTANT_TRUE, boolean, t_])
    f_ = next((x[2] for x in m.ins if x[0] & 0xffff == OP_CONSTANT_FALSE and x[1] == boolean), None)
    if f_ is None: f_ = m.new_id(); m._insert_decl([3 << 16 | OP_CONSTANT_FALSE, boolean, f_])
    at = next(k for k, y in enumerate(m.ins) if y[0] & 0xffff == OP_DECORATE)
    m.ins.insert(at, [4 << 16 | OP_DECORATE, spec, DEC_SPEC_ID, spec_id])
    name = lit_string('dm_alpha_func'); m.ins.insert(next(k for k, y in enumerate(m.ins) if y[0] & 0xffff in (OP_NAME, OP_DECORATE)), [(2 + len(name)) << 16 | OP_NAME, spec, *name])
    vt = t; a, e = m.function_range(m.entry_function()); out, n = [], 0
    for k, x in enumerate(m.ins):
        if a < k < e and x[0] & 0xffff == OP_RETURN:
            col, al, pw, rw, ref = (m.new_id() for _ in range(5))
            out += [[4 << 16 | OP_LOAD, vt, col, c0], [5 << 16 | OP_COMPOSITE_EXTRACT, f32, al, col, 3],
                    [5 << 16 | OP_ACCESS_CHAIN, pu, pw, sb, zero], [4 << 16 | OP_LOAD, u32, rw, pw], [4 << 16 | OP_BITCAST, f32, ref, rw]]
            res = t_   # an unknown function (0, 8 always) passes
            for fn in [1] + list(ALPHA_CMP):
                is_fn, cmp_, r2 = m.new_id(), m.new_id(), m.new_id()
                out.append([5 << 16 | OP_IEQUAL, boolean, is_fn, spec, fconst[fn]])
                if fn == 1: cmp_ = f_
                else: out.append([5 << 16 | ALPHA_CMP[fn], boolean, cmp_, al, ref])
                out.append([6 << 16 | OP_SELECT, boolean, r2, is_fn, cmp_, res]); res = r2
            ok, kill = m.new_id(), m.new_id()
            out += [[3 << 16 | OP_SELECTION_MERGE, ok, 0], [4 << 16 | OP_BRANCH_CONDITIONAL, res, ok, kill],
                    [2 << 16 | OP_LABEL, kill], [1 << 16 | OP_KILL], [2 << 16 | OP_LABEL, ok]]
            n += 1
        out.append(x)
    m.ins = out; m.index()
    return n > 0


OP_FUNCTION_PARAMETER, OP_FUNCTION_CALL = 55, 57
OP_IADD, OP_ISUB, OP_IMUL, OP_SHR_LOGICAL, OP_SHR_ARITH, OP_SHL_LOGICAL, OP_BITWISE_OR, OP_BITWISE_XOR, OP_BITWISE_AND = 128, 130, 132, 194, 195, 196, 197, 198, 199
OP_CONTROL_BARRIER, OP_GROUP_NON_UNIFORM_SHUFFLE = 224, 345
SC_WORKGROUP, SCOPE_SUBGROUP, SEM_ACQ_REL_WORKGROUP = 4, 3, 0x8 | 0x100
BUILTIN_SUBGROUP_SIZE, BUILTIN_SUBGROUP_ID, BUILTIN_SUBGROUP_LOCAL_INVOCATION_ID = 36, 40, 41
CAP_GROUP_NON_UNIFORM, EXEC_MODE_LOCAL_SIZE = 61, 17
VN_OPS = {OP_IADD, OP_ISUB, OP_IMUL, OP_SHR_LOGICAL, OP_SHR_ARITH, OP_SHL_LOGICAL, OP_BITWISE_OR, OP_BITWISE_XOR, OP_BITWISE_AND}


def input_builtin(m, builtin, ty):
    """The Input variable of a builtin (a plain variable), added with its decoration and in the entry point's interface when the module has none → its id"""
    v, member = m.builtin_var(SC_INPUT, builtin)
    if v is not None and member is None: return v
    p = m.add_type(OP_TYPE_POINTER, SC_INPUT, ty); v = m.new_id()
    m._insert_decl([4 << 16 | OP_VARIABLE, p, v, SC_INPUT])
    m.ins.insert(next(k for k, x in enumerate(m.ins) if x[0] & 0xffff == OP_DECORATE), [4 << 16 | OP_DECORATE, v, DEC_BUILTIN, builtin])
    k = next(k for k, x in enumerate(m.ins) if x[0] & 0xffff == OP_ENTRY_POINT)
    m.ins[k] = m.ins[k] + [v]; m.ins[k][0] = len(m.ins[k]) << 16 | OP_ENTRY_POINT
    m.index()
    return v


def workgroup_bytes(m):
    """Bytes of the module's Workgroup variables (arrays of 32-bit scalars or vectors, as the translator declares them); None for another type"""
    n = 0
    for vid, (ptr, sc) in m.vars.items():
        if sc != SC_WORKGROUP: continue
        t, count = m.strip_arrays(m.pointee(ptr)[1])
        comps = m.types[t][3] if m.op(t) == OP_TYPE_VECTOR else 1
        s = m.scalar_of(t)
        if m.op(s) not in (OP_TYPE_INT, OP_TYPE_FLOAT) or m.types[s][2] != 32 or not count: return None
        n += 4 * comps * count
    return n


class BlockValues:
    """Value numbers within a block of the translator's code, which keeps its registers in Function variables: a load of a variable numbers as the value last stored to it in
    the block, or as (variable, version) with a version that every store and every call taking it as an argument moves; bit casts and copies keep their operand's number;
    integer arithmetic numbers by its operation and operands' numbers, a load of an Input variable (or of a constant index into one) by the variable. Two values with one
    number hold the same bits in every lane. see(x) before x is passed on; block()
    at each label; function() at each OpFunction"""
    def __init__(self, m): self.m = m; self.inptr = {}; self.function()
    def function(self): self.fvars, self.fbase, self.ver, self.stored, self.vn = set(), {}, {}, {}, {}
    def block(self): self.stored = {}
    def __call__(self, i): return self.vn.get(i, i)
    def key_of_var(self, p):   # the number of what a load of Function variable p would give here
        b = self.fbase.get(p, p)
        return self(self.stored[p]) if p in self.stored else ('ld', b, self.ver.get(b, 0))
    def bump(self, p):
        b = self.fbase.get(p, p); self.ver[b] = self.ver.get(b, 0) + 1; self.stored.pop(b, None)
    def see(self, x):
        m, op = self.m, x[0] & 0xffff
        if op == OP_FUNCTION: self.function()
        elif op == OP_LABEL: self.block()
        elif (op == OP_VARIABLE and len(x) > 3 and x[3] == SC_FUNCTION) or (op == OP_FUNCTION_PARAMETER and m.op(x[1]) == OP_TYPE_POINTER and m.types[x[1]][2] == SC_FUNCTION):
            self.fvars.add(x[2])   # a local variable, or the translator's pointer argument (written only through a store here or by a call)
        elif op in (OP_ACCESS_CHAIN, OP_IN_BOUNDS_ACCESS_CHAIN) and (x[3] in self.fvars or x[3] in self.fbase): self.fbase[x[2]] = self.fbase.get(x[3], x[3])
        elif op == OP_STORE and (x[1] in self.fvars or x[1] in self.fbase):
            self.bump(x[1])
            if x[1] in self.fvars: self.stored[x[1]] = x[2]
        elif op == OP_LOAD and x[3] in self.fvars: self.vn[x[2]] = self.key_of_var(x[3])
        elif op == OP_LOAD and (x[3] in self.inptr or m.vars.get(x[3], (0, 0))[1] == SC_INPUT): self.vn[x[2]] = ('in', self.inptr.get(x[3], x[3]))
        elif op in (OP_ACCESS_CHAIN, OP_IN_BOUNDS_ACCESS_CHAIN) and m.vars.get(x[3], (0, 0))[1] == SC_INPUT and all(i in m.consts for i in x[4:]): self.inptr[x[2]] = (x[3], *x[4:])
        elif op in (OP_BITCAST, OP_COPY_OBJECT): self.vn[x[2]] = self(x[3])
        elif op in VN_OPS: self.vn[x[2]] = (op, self(x[3]), self(x[4]))
        elif op == OP_FUNCTION_CALL:
            for p in x[4:]:
                if p in self.fvars or p in self.fbase: self.bump(p)

def shuffle_lds(m, shared_max):
    """The 32-bit subgroup shuffles of a compute module through workgroup memory (the library's DMSH_VK_MOD_SHUFFLE_LDS module, for drivers without an arbitrary-index
    shuffle instruction): each lane stores the value at its slot of its subgroup's row (SubgroupId * SubgroupSize + lane), a subgroup barrier, the slot of the lane asked
    for is read. Turnip has no such instruction: ir3 makes each OpGroupNonUniformShuffle a loop of rotates, one pass per distinct (index - lane) in the subgroup, up to 24
    for the translator's LDSM / MOVM gathers (agl's upscaler network on Donkey Kong Bananza: 389 loops, 10.7 ms a dispatch on the Adreno 840 against 2.0 ms this way);
    Qualcomm's driver shuffles natively and is faster with its base module (1.7 ms against 1.9). Two rows alternate, so a store comes after the other row's reads only
    with the barrier of the exchange between them; an exchange of the value a row still holds in the same block reads it without a store or barrier (LDSM's four reads of
    one address, MOVM's two); the last exchange before the block ends or another function is called gets a trailing barrier. The module is rewritten in place.
    → bytes of workgroup memory added (0: not a compute module with such shuffles, or over shared_max with them; the module is then unchanged)"""
    if m.model != 'cs': return 0
    shuf = [x for x in m.ins if x[0] & 0xffff == OP_GROUP_NON_UNIFORM_SHUFFLE and m.op(x[1]) in (OP_TYPE_INT, OP_TYPE_FLOAT) and m.types[x[1]][2] == 32
            and m.consts.get(x[3], (0, [0]))[1] == [SCOPE_SUBGROUP]]
    ls = next((x[1:4] for x in m.modes if x[0] == EXEC_MODE_LOCAL_SIZE), None)   # (m.modes: the entry point's, without its id)
    used = workgroup_bytes(m)
    if not shuf or ls is None or used is None: return 0
    words = -(-ls[0] * ls[1] * ls[2] // 128) * 128   # a row: SubgroupId * SubgroupSize + lane, subgroups of up to 128 lanes
    if used + 8 * words > shared_max: return 0
    u32 = m.add_type(OP_TYPE_INT, 32, 0)
    arr = m.add_type(OP_TYPE_ARRAY, u32, m.add_const(u32, 2 * words))
    p_arr = m.add_type(OP_TYPE_POINTER, SC_WORKGROUP, arr); p_u = m.add_type(OP_TYPE_POINTER, SC_WORKGROUP, u32)
    xs = m.new_id(); m._insert_decl([4 << 16 | OP_VARIABLE, p_arr, xs, SC_WORKGROUP])
    row_off = [None, m.add_const(u32, words)]; C = {v: m.add_const(u32, v) for v in (SCOPE_SUBGROUP, SEM_ACQ_REL_WORKGROUP)}
    sgid, sgsz, sgl = (input_builtin(m, b, u32) for b in (BUILTIN_SUBGROUP_ID, BUILTIN_SUBGROUP_SIZE, BUILTIN_SUBGROUP_LOCAL_INVOCATION_ID))
    if CAP_GROUP_NON_UNIFORM not in m.caps:
        at = max(k for k, x in enumerate(m.ins) if x[0] & 0xffff == OP_CAPABILITY); m.ins.insert(at + 1, [2 << 16 | OP_CAPABILITY, CAP_GROUP_NON_UNIFORM])
    nm = lit_string('dm_shuffle_rows'); m.ins.insert(next(k for k, y in enumerate(m.ins) if y[0] & 0xffff in (OP_NAME, OP_DECORATE)), [(2 + len(nm)) << 16 | OP_NAME, xs, *nm])
    barrier = [4 << 16 | OP_CONTROL_BARRIER, C[SCOPE_SUBGROUP], C[SCOPE_SUBGROUP], C[SEM_ACQ_REL_WORKGROUP]]
    m.index()
    want = {id(x) for x in shuf}
    calls, cur = {}, None   # functions that exchange, themselves or through a call: a call of one ends the caller's run of exchanges
    for x in m.ins:
        op = x[0] & 0xffff
        if op == OP_FUNCTION: cur = x[2]; calls[cur] = [False, set()]
        elif op == OP_GROUP_NON_UNIFORM_SHUFFLE and id(x) in want: calls[cur][0] = True
        elif op == OP_FUNCTION_CALL: calls[cur][1].add(x[3])
    exch = {f for f, (h, _) in calls.items() if h}
    while True:
        more = {f for f, (_, c) in calls.items() if f not in exch and c & exch}
        if not more: break
        exch |= more
    out, trail = [], []   # trail: positions in out after an exchange's read that take a barrier
    V = BlockValues(m)
    rows, dirty, last = [None, None], [False, False], None   # the value each row holds in this block, rows read since the last barrier, the last exchange's read
    def close():   # the block ends or a function that exchanges runs: the last exchange gets a trailing barrier, and the rows' contents count as unknown after it
        nonlocal rows, dirty, last
        if last is not None: trail.append(last)
        rows, dirty, last = [None, None], [False, False], None
    def em(op, ty, *ops):
        r = m.new_id(); out.append([(3 + len(ops)) << 16 | op, ty, r, *ops]); return r
    for x in m.ins:
        op = x[0] & 0xffff
        if op in (OP_LABEL, OP_FUNCTION_END) or (op == OP_FUNCTION_CALL and x[3] in exch): close()
        V.see(x)
        if op != OP_GROUP_NON_UNIFORM_SHUFFLE or id(x) not in want: out.append(x); continue
        key = V(x[4])   # (a value number: the same in two shuffles of this block only when every lane's value is the same)
        row = next((r for r in (0, 1) if rows[r] == key), None)
        sg = em(OP_IMUL, u32, em(OP_LOAD, u32, sgid), em(OP_LOAD, u32, sgsz))
        if row is None:   # store into a row nothing has read since the last barrier
            clean = [r for r in (0, 1) if not dirty[r]]
            if not clean: trail.append(last); clean = [0, 1]
            row = next((r for r in clean if rows[r] is None), clean[0])
            base = sg if row == 0 else em(OP_IADD, u32, sg, row_off[1])
            v = x[4] if x[1] == u32 else em(OP_BITCAST, u32, x[4])
            out.append([3 << 16 | OP_STORE, em(OP_ACCESS_CHAIN, p_u, xs, em(OP_IADD, u32, base, em(OP_LOAD, u32, sgl))), v])
            out.append(list(barrier))
            rows[row] = key; dirty = [False, False]   # the barrier orders every earlier read
        else: base = sg if row == 0 else em(OP_IADD, u32, sg, row_off[1])
        dirty[row] = True
        r = em(OP_LOAD, u32, em(OP_ACCESS_CHAIN, p_u, xs, em(OP_IADD, u32, base, x[5])))
        out.append([4 << 16 | (OP_COPY_OBJECT if x[1] == u32 else OP_BITCAST), x[1], x[2], r])
        last = len(out)
    for k in sorted(set(trail), reverse=True): out.insert(k, list(barrier))
    m.ins = out; m.index()
    return 8 * words


HMMA_NAME = 'dmsh_hmma16816_f16'
OP_MEMORY_BARRIER, OP_COMPOSITE_CONSTRUCT, OP_TYPE_FUNCTION_ = 225, 80, 33
GLSL_FMA, GLSL_PACK_HALF_2X16, GLSL_UNPACK_HALF_2X16 = 50, 58, 62
NO_RESULT_OPS = {OP_STORE, OP_CONTROL_BARRIER, OP_MEMORY_BARRIER, OP_RETURN, OP_RETURN_VALUE, 246, 247, 249, 250, 251, 252, OP_FUNCTION_END}


def hmma_helper(m):
    """The translator's HMMA.16816 helper (HelperFunctionManager.GenerateHmma16816F16) when it has the translator's shape: 11 pointer arguments, one block, 8 + 64 accesses
    of one Workgroup array of u32 used by nothing else, two subgroup barriers → {fid, i32, u32, scratch, barrier}, else None"""
    fid = functions_named(m).get(HMMA_NAME)
    if fid is None: return None
    a, e = m.function_range(fid)
    i32 = m.ins[a][1]
    if m.int_type(i32) != (32, 1): return None
    params = [x for x in m.ins[a + 1:e] if x[0] & 0xffff == OP_FUNCTION_PARAMETER]
    if len(params) != 11 or any(m.types.get(p[1], [0, 0, 0, 0])[2:4] != [SC_FUNCTION, i32] for p in params): return None
    body = m.ins[a + 1 + len(params):e]
    if sum(1 for x in body if x[0] & 0xffff == OP_LABEL) != 1: return None
    chains = [x for x in body if x[0] & 0xffff in (OP_ACCESS_CHAIN, OP_IN_BOUNDS_ACCESS_CHAIN)]
    if len(chains) != 8 + 64 or len({x[3] for x in chains}) != 1: return None
    scratch = chains[0][3]; pwu = chains[0][1]
    if m.vars.get(scratch, (0, 0))[1] != SC_WORKGROUP or m.types.get(pwu, [0, 0, 0])[2] != SC_WORKGROUP: return None
    u32 = m.pointee(pwu)[1]; arr = m.pointee(m.vars[scratch][0])[1]
    if m.int_type(u32) != (32, 0) or m.op(arr) != OP_TYPE_ARRAY or m.types[arr][2] != u32 or m.consts[m.types[arr][3]][1][0] % 4: return None
    if any(scratch in x[1:] for k, x in enumerate(m.ins) if not (a <= k <= e) and not (x[0] & 0xffff in (OP_NAME, OP_DECORATE, OP_VARIABLE) or 19 <= x[0] & 0xffff <= 52)):
        return None   # the scratch array is the helper's alone
    groups, cur = [], None   # runs of barrier instructions: two equal ones (the translator's subgroupBarrier, or a memory barrier and an execution barrier)
    for x in body:
        if x[0] & 0xffff in (OP_CONTROL_BARRIER, OP_MEMORY_BARRIER):
            if cur is None: cur = []; groups.append(cur)
            cur.append(list(x))
        else: cur = None
    if len(groups) != 2 or groups[0] != groups[1]: return None
    return {'fid': fid, 'i32': i32, 'u32': u32, 'scratch': scratch, 'barrier': groups[0]}


def hmma_lds(m):
    """Every call of the translator's HMMA.16816 helper (f16 sources and accumulator) replaced at its call site, with the gathers of A and B shared by the calls of a block
    that take the same fragments. The translator's body stores the lane's 8 fragment words into its warp's 256-word block of the scratch array, then reads 64 words one by one
    (both rows of A, both columns of B, for each output) and converts each half: 240 calls in agl's upscaler network on Donkey Kong Bananza, 35 ms a dispatch on the Adreno
    840 with Qualcomm's driver, 29 ms on Turnip (with its shuffles). Here the block is uvec4 rows: A row-major (16 rows of 8 words) and B transposed (8 rows), each row's k
    words in the order (k-word t, t + 4) for t = 0..3 so a lane's two words of a row are adjacent; C stays in the lane, which is the only one to need it. A gather is the
    stores, a subgroup barrier, 8 uvec4 reads (the lane's two rows of A and two columns of B, converted to f32 once), a barrier; a call whose A (or B) fragments are those of
    an earlier call in the block (the same value numbers, BlockValues) reuses its rows: agl's network multiplies two A tiles by two B tiles, so 8 calls gather 4 A and 6 B.
    The arithmetic is the translator's (f32 FMAs from C over k in order, one rounding to f16), so D is bit for bit the same. 8.0 ms instead of 35 on Qualcomm's driver,
    10.7 ms instead of 29 on Turnip (with shuffle_lds; 13 ms with the translator's body). The helper function is removed.
    → number of calls lowered (0: no helper of the translator's shape; the module is then unchanged)"""
    h = hmma_helper(m)
    if h is None: return 0
    fid, i32, u32, scratch = h['fid'], h['i32'], h['u32'], h['scratch']
    glsl = ensure_glsl(m)
    f32 = m.add_type(OP_TYPE_FLOAT, 32); v2f = m.add_type(OP_TYPE_VECTOR, f32, 2); uv4 = m.add_type(OP_TYPE_VECTOR, u32, 4)
    n = m.consts[m.types[m.pointee(m.vars[scratch][0])[1]][3]][1][0]
    arr4 = m.add_type(OP_TYPE_ARRAY, uv4, m.add_const(u32, n // 4))
    p_arr4 = m.add_type(OP_TYPE_POINTER, SC_WORKGROUP, arr4); p_uv4 = m.add_type(OP_TYPE_POINTER, SC_WORKGROUP, uv4); p_u = m.add_type(OP_TYPE_POINTER, SC_WORKGROUP, u32)
    k = next(k for k, x in enumerate(m.ins) if x[0] & 0xffff == OP_VARIABLE and x[2] == scratch)
    x = m.ins.pop(k); x[1] = p_arr4; m._insert_decl(x)   # the scratch array as uvec4s, declared after its new type
    CI = {v: m.add_const(i32, v) for v in (1, 2, 3, 8, 32)}
    CU = {v: m.add_const(u32, v) for v in (0, 1)}
    m.index()
    calls = 0
    for f in [x[2] for x in m.ins if x[0] & 0xffff == OP_FUNCTION and x[2] != fid]:
        a, e = m.function_range(f)
        out = []; V = BlockValues(m); cache = {}
        def em(op, ty, *ops):
            r = m.new_id(); out.append([(3 + len(ops)) << 16 | op, ty, r, *ops]); return r
        def I(op, p, q): return em(op, i32, p, q)
        for x in m.ins[a:e + 1]:
            op = x[0] & 0xffff
            if op == OP_LABEL: cache = {}
            if not (op == OP_FUNCTION_CALL and x[3] == fid): V.see(x); out.append(x); continue
            # [OpFunctionCall, i32, result, fid, lane, base, a0..a3, b0, b1, c0, c1, d1 (out)]
            args = x[4:15]
            key = [V.key_of_var(p) for p in args[:10]]
            val = [em(OP_LOAD, i32, p) for p in args[:10]]
            lane, bse = val[0], val[1]
            g = I(OP_SHR_LOGICAL, lane, CI[2]); t = I(OP_BITWISE_AND, lane, CI[3])
            kA = ('A', *key[:6]); kB = ('B', *key[:2], *key[6:8])
            need = [q for q in (kA, kB) if q not in cache]
            if need:
                b4 = I(OP_SHR_LOGICAL, bse, CI[2])   # the warp's block in uvec4s: A rows at 2 r, B rows at 32 + 2 n
                vi = I(OP_SHR_LOGICAL, t, CI[1]); c0 = em(OP_BITCAST, u32, I(OP_SHL_LOGICAL, I(OP_BITWISE_AND, t, CI[1]), CI[1])); c1 = em(OP_IADD, u32, c0, CU[1])
                rowA = lambda r: I(OP_IADD, b4, I(OP_SHL_LOGICAL, r, CI[1]))
                rowB = lambda c: I(OP_IADD, b4, I(OP_IADD, CI[32], I(OP_SHL_LOGICAL, c, CI[1])))
                def st(row, w0, w1):   # the lane's k-words t and t + 4 of a row: components 2 (t & 1) and + 1 of its uvec4 t / 2
                    v4 = I(OP_IADD, row, vi)
                    for c, w in ((c0, w0), (c1, w1)): out.append([3 << 16 | OP_STORE, em(OP_ACCESS_CHAIN, p_u, scratch, v4, c), em(OP_BITCAST, u32, w)])
                g8 = I(OP_IADD, g, CI[8])
                if kA in need: st(rowA(g), val[2], val[4]); st(rowA(g8), val[3], val[5])
                if kB in need: st(rowB(g), val[6], val[7])
                out.extend(list(b) for b in h['barrier'])
                def ld_row(r0):   # a row's 8 words, read as two uvec4, converted to vec2 (lo, hi halves) in k order
                    w = []
                    for p in range(2):
                        v4 = em(OP_LOAD, uv4, em(OP_ACCESS_CHAIN, p_uv4, scratch, I(OP_IADD, r0, CI[1]) if p else r0))
                        w += [em(OP_COMPOSITE_EXTRACT, u32, v4, c) for c in range(4)]
                    return [em(OP_EXT_INST, v2f, glsl, GLSL_UNPACK_HALF_2X16, w[2 * (kw % 4) + kw // 4]) for kw in range(8)]
                if kA in need: cache[kA] = [ld_row(rowA(g)), ld_row(rowA(g8))]
                if kB in need:
                    t2 = I(OP_SHL_LOGICAL, t, CI[1]); cache[kB] = [ld_row(rowB(t2)), ld_row(rowB(I(OP_IADD, t2, CI[1])))]
                out.extend(list(b) for b in h['barrier'])
            A, B = cache[kA], cache[kB]
            d = []
            for q in range(2):   # D rows g, g + 8: the translator's order, C then k = 0..15 per output
                cv = em(OP_EXT_INST, v2f, glsl, GLSL_UNPACK_HALF_2X16, em(OP_BITCAST, u32, val[8 + q]))
                acc = [em(OP_COMPOSITE_EXTRACT, f32, cv, 0), em(OP_COMPOSITE_EXTRACT, f32, cv, 1)]
                for j in range(2):
                    for kw in range(8):
                        for hh in range(2):
                            acc[j] = em(OP_EXT_INST, f32, glsl, GLSL_FMA, em(OP_COMPOSITE_EXTRACT, f32, A[q][kw], hh), em(OP_COMPOSITE_EXTRACT, f32, B[j][kw], hh), acc[j])
                d.append(em(OP_BITCAST, i32, em(OP_EXT_INST, u32, glsl, GLSL_PACK_HALF_2X16, em(OP_COMPOSITE_CONSTRUCT, v2f, *acc))))
            st_out = [3 << 16 | OP_STORE, args[10], d[1]]
            out.append(st_out); V.see(st_out)
            out.append([4 << 16 | OP_COPY_OBJECT, i32, x[2], d[0]])
            calls += 1
        m.ins[a:e + 1] = out
    a, e = m.function_range(fid)   # the helper, no longer called
    gone = {y[2] for y in m.ins[a:e + 1] if len(y) > 2 and (y[0] & 0xffff) not in NO_RESULT_OPS} | {y[1] for y in m.ins[a:e + 1] if y[0] & 0xffff == OP_LABEL}
    del m.ins[a:e + 1]
    m.ins = [y for y in m.ins if not (y[0] & 0xffff in (OP_NAME, OP_DECORATE) and y[1] in gone)]
    m.index()
    return calls
