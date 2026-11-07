if (-not ('GameTier.Installer.Tests.WindowReadiness' -as [type])) {
    Add-Type -TypeDefinition @'
using System;
using System.ComponentModel;
using System.Runtime.InteropServices;
using System.Text;
using System.Threading;

namespace GameTier.Installer.Tests
{
    public sealed class WindowReadiness
    {
        private const uint EventObjectShow = 0x8002;
        private const uint EventObjectNameChange = 0x800C;
        private const uint WmQuit = 0x0012;
        private readonly uint processId;
        private readonly ManualResetEvent completed = new ManualResetEvent(false);
        private readonly ManualResetEvent queueReady = new ManualResetEvent(false);
        private readonly ManualResetEvent exited = new ManualResetEvent(false);
        private uint threadId;
        private IntPtr window;
        private Exception error;

        private WindowReadiness(int processId) { this.processId = (uint)processId; }

        public static IntPtr Wait(int processId, int timeoutMilliseconds)
        {
            if (processId <= 0) throw new ArgumentOutOfRangeException("processId");
            if (timeoutMilliseconds <= 0) throw new ArgumentOutOfRangeException("timeoutMilliseconds");
            var waiter = new WindowReadiness(processId);
            var thread = new Thread(waiter.Run) { IsBackground = true, Name = "GameTier window readiness" };
            thread.Start();
            bool signaled;
            try
            {
                signaled = waiter.completed.WaitOne(timeoutMilliseconds);
            }
            finally
            {
                waiter.queueReady.WaitOne();
                if (!waiter.exited.WaitOne(0) &&
                    !PostThreadMessage(waiter.threadId, WmQuit, UIntPtr.Zero, IntPtr.Zero) &&
                    !waiter.exited.WaitOne(0))
                    throw new Win32Exception(Marshal.GetLastWin32Error(), "Cannot stop the GameTier window readiness thread.");
                thread.Join();
                waiter.completed.Dispose();
                waiter.queueReady.Dispose();
                waiter.exited.Dispose();
            }
            if (waiter.error != null)
                throw new InvalidOperationException("Cannot observe the GameTier window for process " + processId + ".", waiter.error);
            if (!signaled || waiter.window == IntPtr.Zero)
                throw new TimeoutException("Process " + processId + " did not show a visible GameTier SunAwtFrame window within " + timeoutMilliseconds + " milliseconds.");
            return waiter.window;
        }

        private void Run()
        {
            IntPtr hook = IntPtr.Zero;
            GCHandle callbackRoot = default(GCHandle);
            WinEventCallback callback = OnWindowEvent;
            try
            {
                threadId = GetCurrentThreadId();
                Message message;
                // Create the queue before another thread can post WM_QUIT to it.
                PeekMessage(out message, IntPtr.Zero, 0, 0, 0);
                queueReady.Set();
                callbackRoot = GCHandle.Alloc(callback);
                hook = SetWinEventHook(EventObjectShow, EventObjectNameChange, IntPtr.Zero, callback, processId, 0, 0);
                if (hook == IntPtr.Zero)
                    throw new Win32Exception(Marshal.GetLastWin32Error(), "SetWinEventHook failed for process " + processId + ".");
                EnumWindowsCallback enumerate = delegate(IntPtr candidate, IntPtr parameter)
                {
                    Observe(candidate);
                    return window == IntPtr.Zero && error == null;
                };
                if (!EnumWindows(enumerate, IntPtr.Zero) && window == IntPtr.Zero && error == null)
                    throw new Win32Exception(Marshal.GetLastWin32Error(), "EnumWindows failed while inspecting process " + processId + ".");
                GC.KeepAlive(enumerate);
                int result;
                while ((result = GetMessage(out message, IntPtr.Zero, 0, 0)) != 0)
                {
                    if (result == -1)
                        throw new Win32Exception(Marshal.GetLastWin32Error(), "The GameTier window readiness message loop failed.");
                    TranslateMessage(ref message);
                    DispatchMessage(ref message);
                }
            }
            catch (Exception exception) { error = exception; }
            finally
            {
                if (hook != IntPtr.Zero && !UnhookWinEvent(hook) && error == null)
                    error = new Win32Exception(Marshal.GetLastWin32Error(), "UnhookWinEvent failed.");
                if (callbackRoot.IsAllocated) callbackRoot.Free();
                queueReady.Set();
                exited.Set();
                completed.Set();
            }
        }

        private void OnWindowEvent(IntPtr hook, uint eventType, IntPtr candidate, int objectId, int childId, uint eventThread, uint eventTime)
        {
            if (objectId == 0 && childId == 0) Observe(candidate);
        }

        private void Observe(IntPtr candidate)
        {
            if (window != IntPtr.Zero || error != null) return;
            try
            {
                uint owner;
                if (candidate == IntPtr.Zero || GetWindowThreadProcessId(candidate, out owner) == 0 || owner != processId || !IsWindowVisible(candidate)) return;
                var text = new StringBuilder(256);
                if (GetClassName(candidate, text, text.Capacity) == 0 || text.ToString() != "SunAwtFrame") return;
                text.Clear();
                if (GetWindowText(candidate, text, text.Capacity) == 0 || text.ToString() != "GameTier") return;
                window = candidate;
                completed.Set();
            }
            catch (Exception exception) { error = exception; completed.Set(); }
        }

        private delegate void WinEventCallback(IntPtr hook, uint eventType, IntPtr window, int objectId, int childId, uint eventThread, uint eventTime);
        private delegate bool EnumWindowsCallback(IntPtr window, IntPtr parameter);

        [StructLayout(LayoutKind.Sequential)]
        private struct Message
        {
            public IntPtr Window;
            public uint Id;
            public UIntPtr WParam;
            public IntPtr LParam;
            public uint Time;
            public int X;
            public int Y;
            public uint Private;
        }

        [DllImport("user32.dll", SetLastError = true)]
        private static extern IntPtr SetWinEventHook(uint eventMin, uint eventMax, IntPtr module, WinEventCallback callback, uint processId, uint threadId, uint flags);
        [DllImport("user32.dll", SetLastError = true)]
        private static extern bool UnhookWinEvent(IntPtr hook);
        [DllImport("user32.dll", SetLastError = true)]
        private static extern bool EnumWindows(EnumWindowsCallback callback, IntPtr parameter);
        [DllImport("user32.dll")]
        private static extern uint GetWindowThreadProcessId(IntPtr window, out uint processId);
        [DllImport("user32.dll")]
        private static extern bool IsWindowVisible(IntPtr window);
        [DllImport("user32.dll", CharSet = CharSet.Unicode)]
        private static extern int GetClassName(IntPtr window, StringBuilder name, int capacity);
        [DllImport("user32.dll", CharSet = CharSet.Unicode)]
        private static extern int GetWindowText(IntPtr window, StringBuilder text, int capacity);
        [DllImport("kernel32.dll")]
        private static extern uint GetCurrentThreadId();
        [DllImport("user32.dll", CharSet = CharSet.Unicode)]
        private static extern bool PeekMessage(out Message message, IntPtr window, uint first, uint last, uint remove);
        [DllImport("user32.dll", CharSet = CharSet.Unicode, SetLastError = true)]
        private static extern int GetMessage(out Message message, IntPtr window, uint first, uint last);
        [DllImport("user32.dll")]
        private static extern bool TranslateMessage(ref Message message);
        [DllImport("user32.dll", CharSet = CharSet.Unicode)]
        private static extern IntPtr DispatchMessage(ref Message message);
        [DllImport("user32.dll", SetLastError = true)]
        private static extern bool PostThreadMessage(uint threadId, uint message, UIntPtr wParam, IntPtr lParam);
    }
}
'@
}

function Wait-GameTierWindow {
    [CmdletBinding()]
    param(
        [Parameter(Mandatory)]
        [ValidateRange(1, [int]::MaxValue)]
        [int] $ProcessId,

        [ValidateRange(1, [int]::MaxValue)]
        [int] $TimeoutMilliseconds = 60000
    )

    [GameTier.Installer.Tests.WindowReadiness]::Wait($ProcessId, $TimeoutMilliseconds)
}
