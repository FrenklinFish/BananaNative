namespace Ryujinx.Graphics.Shader.StructuredIr
{
    // nxnative: the shared memory a compute program declares past the host's limit (the Switch 2 gives a workgroup 48 KiB, Adreno 840 32 KiB), moved to a storage buffer the
    // host binds (dmsh_abi.h DMSH_SHARED_SPILL_CB_OFFSET, dmsh_abi_vk.h DMSH_VK_KIND_SHARED_SPILL). Workgroup w (linear: (z * groups y + y) * groups x + x) uses words
    // [s * Words, (s + 1) * Words) with s = w mod (the buffer's words / Words): the host binds at least one slice per workgroup that can run at once, and as many slices as the
    // dispatch has workgroups when it is not split. Barriers then order storage buffer memory too, and the buffer is Coherent.
    class SharedSpillInfo
    {
        public int MemoryId { get; }   // the shared memory definition that moved (ResourceManager.SharedMemoryId); not declared as Workgroup storage
        public int Binding { get; }    // the storage buffer binding (reserved under GlobalToStorage.GmemMapCbSlot, offset GlobalToStorage.SharedSpillCbOffset)
        public int Words { get; }      // 32-bit words of one workgroup's slice

        public SharedSpillInfo(int memoryId, int binding, int words)
        {
            MemoryId = memoryId;
            Binding = binding;
            Words = words;
        }
    }
}
