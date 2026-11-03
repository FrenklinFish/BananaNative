using Ryujinx.Graphics.Shader.StructuredIr;
using System;
using System.Collections.Generic;
using System.Linq;
using static Spv.Specification;

namespace Ryujinx.Graphics.Shader.CodeGen.Spirv
{
    using SpvInstruction = Spv.Generator.Instruction;
    using SpvLiteralInteger = Spv.Generator.LiteralInteger;

    // nxnative (after azmasm/switch2mac, MIT: their SpirvGenerator.GenerateMeshEntry, there for Metal through SPIRV-Cross; here for Vulkan 1.3 with SPV_EXT_mesh_shader):
    // the entry point of a Switch 2 task / mesh program (StructuredIr/MeshShaderInfo.cs). The translated program is function 0, a compute body whose early returns
    // stay legal. The entry point, "main", calls it between two workgroup barriers and hands its stage memory over:
    //   task (TaskEXT): before the body the task memory's word 1 (mesh workgroups to launch) is 0; after it OpEmitMeshTasksEXT(word 1, 1, 1, payload) ends every
    //     invocation (switch2mac also cleared word 0, which a mesh workgroup reads as its own index: InstEmitAmpereMesh.cs)
    //   mesh (MeshEXT): OpSetMeshOutputsEXT(max vertices, max primitives) first (no output is written before it), then the outputs that start as (0, 0, 0, 1) set,
    //     the body (its plane stores land in the outputs at once, MeshPlaneStore below), then every triangle's indices from the primitive area: the program's
    //     indices (each clamped below max vertices) for t below the primitive count (byte 3), (0, 0, 0), a degenerate triangle, from there to max primitives;
    //     with a GL depth constant (MeshShaderInfo.GlDepthSpecId), z' = z / 2 + w / 2 of every vertex when it is true, as vk_android_spv.gl_depth does a vertex shader's
    // Outputs: the position in a per-vertex block array (BuiltIn Position), one vec4 array per per-vertex generic location at Location g (as this translator's vertex
    // shaders number theirs), one vec4 array per per-primitive location at Location g decorated PerPrimitiveEXT, PrimitiveTriangleIndicesEXT uvec3[max primitives].
    // SPIR-V 1.4: the entry point lists every global variable.
    // Dropped from switch2mac's Metal path: the per-vertex outputs at every location 0-5 whether written or not (Metal refused a mesh pipeline missing a fragment
    // input; here only the layout's padded locations are added), the 1024 mesh workgroup bound of a Metal object function (here 65535, Vulkan's
    // maxMeshWorkGroupCount minimum), SetMeshOutputsEXT after the body with the program's own count (Vulkan: before any output write; the unused primitives are
    // degenerate instead)
    static class SpirvMeshEntry
    {
        private const uint MaxMeshWorkgroups = 65535;

        public static void Generate(CodeGenContext context, MeshShaderInfo mesh)
        {
            context.AddCapability(Capability.MeshShadingEXT);
            context.AddExtension("SPV_EXT_mesh_shader");

            SpvInstruction tVoid = context.TypeVoid(), tU32 = context.TypeU32(), tF32 = context.TypeFP32(), tBool = context.TypeBool();
            SpvInstruction tVec4 = context.TypeVector(tF32, 4), tUVec3 = context.TypeVector(tU32, 3);
            SpvInstruction U(uint v) => context.Constant(tU32, v);
            uint threads = (uint)Math.Max(1, context.Definitions.ComputeLocalSizeX * context.Definitions.ComputeLocalSizeY * context.Definitions.ComputeLocalSizeZ);
            List<SpvInstruction> io = new(context.GetMainInterface());

            SpvInstruction lidVar = context.Variable(context.TypePointer(StorageClass.Input, tU32), StorageClass.Input);
            context.Decorate(lidVar, Decoration.BuiltIn, (SpvLiteralInteger)BuiltIn.LocalInvocationIndex);
            context.AddGlobalVariable(lidVar);
            io.Add(lidVar);

            SpvInstruction payload = mesh.PayloadMemoryId >= 0 ? context.SharedMemories[mesh.PayloadMemoryId] : null;
            SpvInstruction pPayload = context.TypePointer(StorageClass.TaskPayloadWorkgroupEXT, tU32);
            SpvInstruction pWg = context.TypePointer(StorageClass.Workgroup, tU32);
            SpvInstruction pOutF32 = context.TypePointer(StorageClass.Output, tF32), pOutVec4 = context.TypePointer(StorageClass.Output, tVec4);

            SpvInstruction posVar = null, idxVar = null;
            Dictionary<int, SpvInstruction> generics = new(), primGenerics = new();

            if (mesh.IsMesh)
            {
                SpvInstruction perVertex = context.TypeStruct(true, tVec4);
                context.Decorate(perVertex, Decoration.Block);
                context.MemberDecorate(perVertex, 0, Decoration.BuiltIn, (SpvLiteralInteger)BuiltIn.Position);
                posVar = context.Variable(context.TypePointer(StorageClass.Output, context.TypeArray(perVertex, U((uint)mesh.MaxVertices))), StorageClass.Output);
                context.Name(posVar, "mesh_position");
                context.AddGlobalVariable(posVar);
                io.Add(posVar);

                foreach (int g in mesh.VertexLocations)
                {
                    SpvInstruction v = context.Variable(context.TypePointer(StorageClass.Output, context.TypeArray(tVec4, U((uint)mesh.MaxVertices))), StorageClass.Output);
                    context.Decorate(v, Decoration.Location, (SpvLiteralInteger)g);
                    context.Name(v, $"mesh_out{g}");
                    context.AddGlobalVariable(v);
                    generics[g] = v;
                    io.Add(v);
                }

                foreach (int g in mesh.PrimitiveLocations)
                {
                    SpvInstruction v = context.Variable(context.TypePointer(StorageClass.Output, context.TypeArray(tVec4, U((uint)mesh.MaxPrimitives))), StorageClass.Output);
                    context.Decorate(v, Decoration.Location, (SpvLiteralInteger)g);
                    context.Decorate(v, Decoration.PerPrimitiveEXT);
                    context.Name(v, $"mesh_prim{g}");
                    context.AddGlobalVariable(v);
                    primGenerics[g] = v;
                    io.Add(v);
                }

                idxVar = context.Variable(context.TypePointer(StorageClass.Output, context.TypeArray(tUVec3, U((uint)mesh.MaxPrimitives))), StorageClass.Output);
                context.Decorate(idxVar, Decoration.BuiltIn, (SpvLiteralInteger)BuiltIn.PrimitiveTriangleIndicesEXT);
                context.AddGlobalVariable(idxVar);
                io.Add(idxVar);
            }

            SpvInstruction glDepth = null;
            if (mesh.IsMesh && mesh.GlDepthSpecId >= 0)
            {
                glDepth = context.DeclareSpecConstantFalse(tBool);
                context.Decorate(glDepth, Decoration.SpecId, (SpvLiteralInteger)mesh.GlDepthSpecId);
                context.Name(glDepth, "dm_gl_depth");
            }

            // The GL depth rewrite reads z and w back, which a mesh program cannot do with its outputs (VUID-StandaloneSpirv-MeshEXT-07107): their plane stores
            // also go to a workgroup copy, z then w of each vertex, starting as (0, 1)
            SpvInstruction zw = null;
            if (glDepth != null && mesh.Planes.Contains(2) && mesh.Planes.Contains(3))
            {
                zw = context.Variable(context.TypePointer(StorageClass.Workgroup, context.TypeArray(tU32, U((uint)mesh.MaxVertices * 2))), StorageClass.Workgroup);
                context.Name(zw, "mesh_position_zw");
                context.AddGlobalVariable(zw);
            }

            (_, SpvInstruction body) = context.GetFunction(0);
            SpvInstruction wrapper = context.Function(tVoid, FunctionControlMask.MaskNone, context.TypeFunction(tVoid, false));
            context.Name(wrapper, "main");
            context.AddFunction(wrapper);
            context.AddLabel(context.Label());

            void Barrier() => context.ControlBarrier(U((uint)Scope.Workgroup), U((uint)Scope.Workgroup), U((uint)(MemorySemanticsMask.AcquireRelease | MemorySemanticsMask.WorkgroupMemory)));

            void Guarded(SpvInstruction cond, Action then)
            {
                SpvInstruction thenLabel = context.Label(), mergeLabel = context.Label();
                context.SelectionMerge(mergeLabel, SelectionControlMask.MaskNone);
                context.BranchConditional(cond, thenLabel, mergeLabel);
                context.AddLabel(thenLabel);
                then();
                context.Branch(mergeLabel);
                context.AddLabel(mergeLabel);
            }

            // Each invocation takes elements lid, lid + threads, ... below count (unrolled: count / threads is at most 256)
            void ForEach(SpvInstruction lidValue, int count, Action<SpvInstruction> then)
            {
                for (uint k = 0; k * threads < count; k++)
                {
                    SpvInstruction t = k == 0 ? lidValue : context.IAdd(tU32, lidValue, U(k * threads));
                    if ((k + 1) * threads <= count)
                    {
                        then(t);
                    }
                    else
                    {
                        Guarded(context.ULessThan(tBool, t, U((uint)count)), () => then(t));
                    }
                }
            }

            SpvInstruction lid = context.Load(tU32, lidVar);
            SpvInstruction first = context.IEqual(tBool, lid, U(0));
            SpvInstruction init = context.ConstantComposite(tVec4, context.Constant(tF32, 0f), context.Constant(tF32, 0f), context.Constant(tF32, 0f), context.Constant(tF32, 1f));

            if (mesh.IsTask)
            {
                Guarded(first, () => context.Store(context.AccessChain(pPayload, payload, U(1)), U(0)));
            }
            else
            {
                context.SetMeshOutputsEXT(U((uint)mesh.MaxVertices), U((uint)mesh.MaxPrimitives));

                SpvInstruction prims = context.SharedMemories[mesh.PrimitiveMemoryId];
                Guarded(first, () => context.Store(context.AccessChain(pWg, prims, U(3)), U(0)));

                // Outputs start as (0, 0, 0, 1), as an unwritten component reads on the hardware (vk_android_spv.init_outputs gives a vertex shader's the same): the
                // position and the generic locations whose components the planes do not all name, and every per-primitive location
                bool posFull = Enumerable.Range(0, 4).All(c => mesh.Planes.Contains(c));
                List<SpvInstruction> vertexInit = generics.Where(kv => !Enumerable.Range(0, 4).All(c => mesh.Planes.Contains(4 + 4 * kv.Key + c))).Select(kv => kv.Value).ToList();
                if (vertexInit.Count != 0 || !posFull)
                {
                    ForEach(lid, mesh.MaxVertices, v =>
                    {
                        if (!posFull)
                        {
                            context.Store(context.AccessChain(pOutVec4, posVar, v, U(0)), init);
                        }

                        foreach (SpvInstruction g in vertexInit)
                        {
                            context.Store(context.AccessChain(pOutVec4, g, v), init);
                        }
                    });
                }

                if (zw != null)
                {
                    ForEach(lid, mesh.MaxVertices, v =>
                    {
                        SpvInstruction e = context.IMul(tU32, v, U(2));
                        context.Store(context.AccessChain(pWg, zw, e), U(0));
                        context.Store(context.AccessChain(pWg, zw, context.IAdd(tU32, e, U(1))), U(0x3f800000));
                    });
                }

                if (primGenerics.Count != 0)
                {
                    ForEach(lid, mesh.MaxPrimitives, t =>
                    {
                        foreach (SpvInstruction g in primGenerics.Values)
                        {
                            context.Store(context.AccessChain(pOutVec4, g, t), init);
                        }
                    });
                }
            }

            Barrier();
            context.FunctionCall(tVoid, body);
            Barrier();

            if (mesh.IsTask)
            {
                SpvInstruction count = context.Load(tU32, context.AccessChain(pPayload, payload, U(1)));
                count = context.Select(tU32, context.ULessThan(tBool, count, U(MaxMeshWorkgroups)), count, U(MaxMeshWorkgroups));
                context.EmitMeshTasksEXT(count, U(1), U(1), payload);
            }
            else
            {
                SpvInstruction prims = context.SharedMemories[mesh.PrimitiveMemoryId];
                SpvInstruction primCount = context.Load(tU32, context.AccessChain(pWg, prims, U(3)));
                SpvInstruction pOutUVec3 = context.TypePointer(StorageClass.Output, tUVec3);
                SpvInstruction lastVertex = U((uint)mesh.MaxVertices - 1);

                SpvInstruction Index(SpvInstruction word)
                {
                    SpvInstruction w = context.BitwiseAnd(tU32, word, U((uint)mesh.PrimitiveWords - 1));
                    SpvInstruction b = context.Load(tU32, context.AccessChain(pWg, prims, w));
                    return context.Select(tU32, context.ULessThan(tBool, b, U((uint)mesh.MaxVertices)), b, lastVertex);
                }

                ForEach(lid, mesh.MaxPrimitives, t =>
                {
                    SpvInstruction a = context.IAdd(tU32, context.IMul(tU32, t, U(3)), U(4));
                    SpvInstruction tri = context.CompositeConstruct(tUVec3, Index(a), Index(context.IAdd(tU32, a, U(1))), Index(context.IAdd(tU32, a, U(2))));
                    tri = context.Select(tUVec3, context.ULessThan(tBool, t, primCount), tri, context.ConstantComposite(tUVec3, U(0), U(0), U(0)));
                    context.Store(context.AccessChain(pOutUVec3, idxVar, t), tri);
                });

                // The GL depth convention (vk_android_spv.gl_depth's arithmetic for a vertex shader): z' = fma(z, 0.5, w * 0.5) when the constant is true
                if (zw != null)
                {
                    SpvInstruction half = context.Constant(tF32, 0.5f);
                    ForEach(lid, mesh.MaxVertices, v =>
                    {
                        SpvInstruction e = context.IMul(tU32, v, U(2));
                        SpvInstruction z = context.Bitcast(tF32, context.Load(tU32, context.AccessChain(pWg, zw, e)));
                        SpvInstruction w = context.Bitcast(tF32, context.Load(tU32, context.AccessChain(pWg, zw, context.IAdd(tU32, e, U(1)))));
                        context.Store(context.AccessChain(pOutF32, posVar, v, U(0), U(2)), context.Select(tF32, glDepth, context.GlslFma(tF32, z, half, context.FMul(tF32, w, half)), z));
                    });
                }

                context.Return();
            }

            context.FunctionEnd();

            // The plane stores (MeshShaderInfo), straight into the outputs: word w below the per-primitive base is vertex (w / chunk) x 32 + w % 32, plane (w % chunk) / 32
            // (chunk = 32 x planes); above it the same for the primitives and their values. Words outside both, and planes of no output, are dropped
            if (mesh.IsMesh && context.HasMeshPlaneStore)
            {
                SpvInstruction store = context.MeshPlaneStore;
                context.Name(store, "mesh_plane_store");
                context.AddFunction(store);
                SpvInstruction word = context.FunctionParameter(tU32), value = context.FunctionParameter(tU32);
                context.AddLabel(context.Label());
                SpvInstruction f = context.Bitcast(tF32, value);

                void Scatter(SpvInstruction w, int chunkWords, int count, int[] planes, Action<SpvInstruction, int> target)
                {
                    if (chunkWords == 0)
                    {
                        return;
                    }

                    SpvInstruction rem = context.UMod(tU32, w, U((uint)chunkWords));
                    SpvInstruction plane = context.ShiftRightLogical(tU32, rem, U(5));
                    SpvInstruction index = context.IAdd(tU32, context.IMul(tU32, context.UDiv(tU32, w, U((uint)chunkWords)), U(32)), context.BitwiseAnd(tU32, rem, U(31)));
                    Guarded(context.ULessThan(tBool, index, U((uint)count)), () =>
                    {
                        for (int n = 0; n < planes.Length; n++)
                        {
                            int p = n;
                            Guarded(context.IEqual(tBool, plane, U((uint)p)), () => target(index, planes[p]));
                        }
                    });
                }

                SpvInstruction isVertex = context.ULessThan(tBool, word, U((uint)mesh.PrimitiveBaseWord));
                Guarded(isVertex, () => Scatter(word, mesh.VertexChunkWords, mesh.MaxVertices, mesh.Planes, (v, plane) =>
                {
                    context.Store(plane < 4
                        ? context.AccessChain(pOutF32, posVar, v, U(0), U((uint)plane))
                        : context.AccessChain(pOutF32, generics[(plane - 4) >> 2], v, U((uint)(plane & 3))), f);

                    if (zw != null && plane is 2 or 3)
                    {
                        context.Store(context.AccessChain(pWg, zw, context.IAdd(tU32, context.IMul(tU32, v, U(2)), U((uint)plane - 2))), value);
                    }
                }));
                if (mesh.PrimitivePlanes.Length != 0)
                {
                    Guarded(context.LogicalNot(tBool, isVertex), () => Scatter(context.ISub(tU32, word, U((uint)mesh.PrimitiveBaseWord)), mesh.PrimitiveChunkWords, mesh.MaxPrimitives,
                        mesh.PrimitivePlanes, (t, plane) =>
                        {
                            if (plane != MeshShaderInfo.NoOutput)
                            {
                                context.Store(context.AccessChain(pOutF32, primGenerics[(plane - 4) >> 2], t, U((uint)(plane & 3))), f);
                            }
                        }));
                }

                context.Return();
                context.FunctionEnd();
            }

            foreach (SpvInstruction global in context.GlobalVariables)   // SPIR-V 1.4: every global, not only the inputs and outputs
            {
                if (!io.Contains(global))
                {
                    io.Add(global);
                }
            }

            context.AddEntryPoint(mesh.IsTask ? ExecutionModel.TaskEXT : ExecutionModel.MeshEXT, wrapper, "main", io.ToArray());
            context.AddExecutionMode(wrapper, ExecutionMode.LocalSize,
                (SpvLiteralInteger)context.Definitions.ComputeLocalSizeX, (SpvLiteralInteger)context.Definitions.ComputeLocalSizeY, (SpvLiteralInteger)context.Definitions.ComputeLocalSizeZ);

            if (mesh.IsMesh)
            {
                context.AddExecutionMode(wrapper, ExecutionMode.OutputVertices, (SpvLiteralInteger)mesh.MaxVertices);
                context.AddExecutionMode(wrapper, ExecutionMode.OutputPrimitivesEXT, (SpvLiteralInteger)mesh.MaxPrimitives);
                context.AddExecutionMode(wrapper, ExecutionMode.OutputTrianglesEXT);
            }
        }
    }
}
