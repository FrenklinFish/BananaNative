// The exported entry of libdmsh_xlate.so (build.sh, NativeLib=Shared): the app's job service calls dmsh_xlate_main(argc, argv, log) in its own process instead of starting
// the executable (an app may not run files from its data folder; android/ONDEVICE.md). argv as the executable's (argv[0] = job file, argv[1] = output folder); what the
// executable prints (standard output and standard error, where its [RED] lines go) goes to the file log (NULL: nowhere). Several threads may call it at once: each call's output goes to its own log. Returns Main's exit code; an
// exception that escapes Main returns 70.
using System;
using System.IO;
using System.Reflection;
using System.Runtime.InteropServices;
using System.Text;

static unsafe class DmshXlateEntry
{
    sealed class PerThread : TextWriter   // Console.Out and Console.Error for every thread: write to the calling thread's log
    {
        [ThreadStatic] public static TextWriter Cur;
        public override Encoding Encoding => Encoding.UTF8;
        public override void Write(char c) => Cur?.Write(c);
        public override void Write(string s) => Cur?.Write(s);
        public override void WriteLine(string s) => Cur?.WriteLine(s);
    }

    static readonly Func<string[], int> Main = (Func<string[], int>)typeof(SwshXlate).GetMethod("Main", BindingFlags.Static | BindingFlags.NonPublic).CreateDelegate(typeof(Func<string[], int>));
    static readonly object Once = new();
    static bool _set;

    [UnmanagedCallersOnly(EntryPoint = "dmsh_xlate_main")]
    static int Run(int argc, byte** argv, byte* log)
    {
        lock (Once) { if (!_set) { Console.SetOut(new PerThread()); Console.SetError(new PerThread()); _set = true; } }
        var args = new string[argc];
        for (int i = 0; i < argc; i++) args[i] = Marshal.PtrToStringUTF8((IntPtr)argv[i]);
        try
        {
            PerThread.Cur = log != null ? new StreamWriter(Marshal.PtrToStringUTF8((IntPtr)log), false) : null;
            if (args.Length == 1 && args[0] == "--tree-sha")   // the source tree this library was built from (xlate_tree.py, embedded by build.sh as "tree")
            {
                using var t = typeof(DmshXlateEntry).Assembly.GetManifestResourceStream("tree");
                PerThread.Cur?.WriteLine(t == null ? "none" : new StreamReader(t).ReadToEnd().Trim());
                return t == null ? 1 : 0;
            }
            return Main(args);
        }
        catch (Exception e) { PerThread.Cur?.WriteLine("[RED] dmsh_xlate: " + e); return 70; }
        finally { PerThread.Cur?.Dispose(); PerThread.Cur = null; }
    }
}
