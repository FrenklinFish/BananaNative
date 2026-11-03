// SHA-256 in managed code for the Android translator (dmsh_xlate_android.csproj). Program.cs calls System.Security.Cryptography.SHA256.HashData (the --source-sha argument, and
// VertexCounterLowering's code match); .NET on linux-bionic implements it with OpenSSL, which Android does not ship (the system's BoringSSL lacks symbols .NET wants:
// "Cannot get required symbol a2d_ASN1_OBJECT from libssl", then abort). This type has the framework type's name, so Program.cs compiles against it unchanged (CS0436).
// FIPS 180-4. Checked by the source-sha argument on the phone, which must print the lock file's xlate_source (a SHA-256 of the embedded sources).
namespace System.Security.Cryptography
{
    static class SHA256
    {
        static readonly uint[] K = {
            0x428a2f98, 0x71374491, 0xb5c0fbcf, 0xe9b5dba5, 0x3956c25b, 0x59f111f1, 0x923f82a4, 0xab1c5ed5, 0xd807aa98, 0x12835b01, 0x243185be, 0x550c7dc3, 0x72be5d74, 0x80deb1fe, 0x9bdc06a7, 0xc19bf174,
            0xe49b69c1, 0xefbe4786, 0x0fc19dc6, 0x240ca1cc, 0x2de92c6f, 0x4a7484aa, 0x5cb0a9dc, 0x76f988da, 0x983e5152, 0xa831c66d, 0xb00327c8, 0xbf597fc7, 0xc6e00bf3, 0xd5a79147, 0x06ca6351, 0x14292967,
            0x27b70a85, 0x2e1b2138, 0x4d2c6dfc, 0x53380d13, 0x650a7354, 0x766a0abb, 0x81c2c92e, 0x92722c85, 0xa2bfe8a1, 0xa81a664b, 0xc24b8b70, 0xc76c51a3, 0xd192e819, 0xd6990624, 0xf40e3585, 0x106aa070,
            0x19a4c116, 0x1e376c08, 0x2748774c, 0x34b0bcb5, 0x391c0cb3, 0x4ed8aa4a, 0x5b9cca4f, 0x682e6ff3, 0x748f82ee, 0x78a5636f, 0x84c87814, 0x8cc70208, 0x90befffa, 0xa4506ceb, 0xbef9a3f7, 0xc67178f2 };

        static uint R(uint x, int n) => (x >> n) | (x << (32 - n));

        public static byte[] HashData(byte[] data)
        {
            uint[] h = { 0x6a09e667, 0xbb67ae85, 0x3c6ef372, 0xa54ff53a, 0x510e527f, 0x9b05688c, 0x1f83d9ab, 0x5be0cd19 };
            long n = data.LongLength; long total = (n + 9 + 63) / 64 * 64;
            var w = new uint[64];
            for (long blk = 0; blk < total; blk += 64)
            {
                for (int i = 0; i < 16; i++)
                {
                    uint v = 0;
                    for (int j = 0; j < 4; j++)
                    {
                        long p = blk + 4 * i + j; byte b;
                        if (p < n) b = data[p];
                        else if (p == n) b = 0x80;
                        else if (p >= total - 8) b = (byte)((ulong)(n * 8) >> (int)(8 * (total - 1 - p)));
                        else b = 0;
                        v = (v << 8) | b;
                    }
                    w[i] = v;
                }
                for (int i = 16; i < 64; i++)
                {
                    uint s0 = R(w[i - 15], 7) ^ R(w[i - 15], 18) ^ (w[i - 15] >> 3), s1 = R(w[i - 2], 17) ^ R(w[i - 2], 19) ^ (w[i - 2] >> 10);
                    w[i] = w[i - 16] + s0 + w[i - 7] + s1;
                }
                uint a = h[0], b2 = h[1], c = h[2], d = h[3], e = h[4], f = h[5], g = h[6], hh = h[7];
                for (int i = 0; i < 64; i++)
                {
                    uint t1 = hh + (R(e, 6) ^ R(e, 11) ^ R(e, 25)) + ((e & f) ^ (~e & g)) + K[i] + w[i];
                    uint t2 = (R(a, 2) ^ R(a, 13) ^ R(a, 22)) + ((a & b2) ^ (a & c) ^ (b2 & c));
                    hh = g; g = f; f = e; e = d + t1; d = c; c = b2; b2 = a; a = t1 + t2;
                }
                h[0] += a; h[1] += b2; h[2] += c; h[3] += d; h[4] += e; h[5] += f; h[6] += g; h[7] += hh;
            }
            var o = new byte[32];
            for (int i = 0; i < 8; i++) { o[4 * i] = (byte)(h[i] >> 24); o[4 * i + 1] = (byte)(h[i] >> 16); o[4 * i + 2] = (byte)(h[i] >> 8); o[4 * i + 3] = (byte)h[i]; }
            return o;
        }
    }
}
