package io.duckyplayer;

import com.sun.jna.Native;
import com.sun.jna.Pointer;
import com.sun.jna.WString;
import com.sun.jna.platform.win32.BaseTSD.ULONG_PTR;
import com.sun.jna.platform.win32.Kernel32;
import com.sun.jna.platform.win32.WinDef.DWORD;
import com.sun.jna.platform.win32.WinDef.HWND;
import com.sun.jna.platform.win32.WinDef.LONG;
import com.sun.jna.platform.win32.WinDef.POINT;
import com.sun.jna.platform.win32.WinDef.RECT;
import com.sun.jna.platform.win32.WinDef.WORD;
import com.sun.jna.platform.win32.WinNT.HANDLE;
import com.sun.jna.platform.win32.WinNT.LARGE_INTEGER;
import com.sun.jna.platform.win32.WinUser.INPUT;
import com.sun.jna.platform.win32.WinUser.KEYBDINPUT;
import com.sun.jna.platform.win32.WinUser.MOUSEINPUT;
import com.sun.jna.platform.win32.WinUser.MSG;
import com.sun.jna.platform.win32.User32;
import com.sun.jna.ptr.IntByReference;
import com.sun.jna.win32.StdCallLibrary;
import com.sun.jna.win32.W32APIOptions;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.function.IntConsumer;

final class NativeWindows {
    static final int HOTKEY_START = 1;
    static final int HOTKEY_STOP = 2;
    static final int HOTKEY_START_FAILED = -1;
    static final int HOTKEY_STOP_FAILED = -2;
    private static final int VK_F8 = 0x77;
    private static final int VK_F12 = 0x7b;
    private static final int MOD_NOREPEAT = 0x4000;
    private static final int WM_HOTKEY = 0x0312;
    private static final int WM_QUIT = 0x0012;
    private static final int GW_OWNER = 4;
    private static final int SM_XVIRTUALSCREEN = 76;
    private static final int SM_YVIRTUALSCREEN = 77;
    private static final int SM_CXVIRTUALSCREEN = 78;
    private static final int SM_CYVIRTUALSCREEN = 79;
    private static final int CREATE_WAITABLE_TIMER_HIGH_RESOLUTION = 0x00000002;
    private static final int TIMER_ALL_ACCESS = 0x001f0003;

    private NativeWindows() {
    }

    interface User32Extra extends StdCallLibrary {
        User32Extra INSTANCE = Native.load("user32", User32Extra.class, W32APIOptions.UNICODE_OPTIONS);

        boolean ClientToScreen(HWND window, POINT point);
    }

    interface Kernel32Extra extends StdCallLibrary {
        Kernel32Extra INSTANCE = Native.load("kernel32", Kernel32Extra.class, W32APIOptions.UNICODE_OPTIONS);

        HANDLE CreateWaitableTimerExW(Pointer attributes, WString name, int flags, int desiredAccess);
        HANDLE CreateWaitableTimerW(Pointer attributes, boolean manualReset, WString name);
        boolean SetWaitableTimer(HANDLE timer, LARGE_INTEGER dueTime, int periodMs,
                                 Pointer completionRoutine, Pointer argument, boolean resume);
    }

    record TargetWindow(HWND handle, long processId, String label) {
        @Override
        public String toString() {
            return label;
        }
    }

    enum PlaybackKey {
        A(0x41), B(0x42), C(0x43), D(0x44), E(0x45), F(0x46), G(0x47),
        H(0x48), I(0x49), J(0x4a), K(0x4b), L(0x4c), M(0x4d), N(0x4e),
        O(0x4f), P(0x50), Q(0x51), R(0x52), S(0x53), T(0x54), U(0x55),
        V(0x56), W(0x57), X(0x58), Y(0x59), Z(0x5a),
        DIGIT_0(0x30), DIGIT_1(0x31), DIGIT_2(0x32), DIGIT_3(0x33),
        DIGIT_4(0x34), DIGIT_5(0x35), DIGIT_6(0x36), DIGIT_7(0x37),
        DIGIT_8(0x38), DIGIT_9(0x39), SPACE(0x20);

        private final int virtualKey;

        PlaybackKey(int virtualKey) { this.virtualKey = virtualKey; }

        int virtualKey() { return virtualKey; }

        @Override public String toString() {
            if (name().startsWith("DIGIT_")) return name().substring(6);
            return name().equals("SPACE") ? "Space" : name();
        }
    }

    static List<TargetWindow> findOsuWindows() {
        List<TargetWindow> result = new ArrayList<>();
        User32.INSTANCE.EnumWindows((window, ignored) -> {
            if (!User32.INSTANCE.IsWindowVisible(window)
                    || User32.INSTANCE.GetWindow(window, new DWORD(GW_OWNER)) != null) return true;
            IntByReference processId = new IntByReference();
            User32.INSTANCE.GetWindowThreadProcessId(window, processId);
            long pid = Integer.toUnsignedLong(processId.getValue());
            Optional<String> command = ProcessHandle.of(pid).flatMap(process -> process.info().command());
            if (command.isEmpty()) return true;
            String fileName;
            try {
                fileName = Path.of(command.get()).getFileName().toString().toLowerCase(Locale.ROOT);
            } catch (RuntimeException exception) {
                return true;
            }
            if (!fileName.equals("osu!.exe") && !fileName.equals("osu.exe")) return true;
            char[] titleBuffer = new char[512];
            User32.INSTANCE.GetWindowText(window, titleBuffer, titleBuffer.length);
            String title = Native.toString(titleBuffer);
            if (title.isBlank()) title = "osu!";
            result.add(new TargetWindow(window, pid, title + "  (PID " + pid + ")"));
            return true;
        }, null);
        return List.copyOf(result);
    }

    static boolean isForeground(HWND target) {
        HWND foreground = User32.INSTANCE.GetForegroundWindow();
        return foreground != null && target != null
                && Pointer.nativeValue(foreground.getPointer()) == Pointer.nativeValue(target.getPointer());
    }

    static final class HighResolutionWaiter implements AutoCloseable {
        private final HANDLE stopEvent = Kernel32.INSTANCE.CreateEvent(null, true, false, null);
        private final HANDLE timer;

        HighResolutionWaiter() {
            HANDLE selected = Kernel32Extra.INSTANCE.CreateWaitableTimerExW(
                    null, null, CREATE_WAITABLE_TIMER_HIGH_RESOLUTION, TIMER_ALL_ACCESS);
            if (selected == null)
                selected = Kernel32Extra.INSTANCE.CreateWaitableTimerW(null, false, null);
            timer = selected;
            if (stopEvent == null || timer == null) {
                close();
                throw new IllegalStateException("Could not create Windows timing handles");
            }
        }

        void reset() {
            Kernel32.INSTANCE.ResetEvent(stopEvent);
        }

        void stop() {
            Kernel32.INSTANCE.SetEvent(stopEvent);
        }

        boolean isStopped() {
            return Kernel32.INSTANCE.WaitForSingleObject(stopEvent, 0) == 0;
        }

        boolean waitForNanos(long nanos) {
            long bounded = Math.max(1, Math.min(nanos, 5_000_000L));
            LARGE_INTEGER dueTime = new LARGE_INTEGER(-Math.max(1, bounded / 100));
            if (!Kernel32Extra.INSTANCE.SetWaitableTimer(timer, dueTime, 0, null, null, false))
                throw new IllegalStateException("SetWaitableTimer failed: " + Kernel32.INSTANCE.GetLastError());
            HANDLE[] handles = {stopEvent, timer};
            return Kernel32.INSTANCE.WaitForMultipleObjects(handles.length, handles, false, 6) == 0;
        }

        @Override
        public void close() {
            if (timer != null) Kernel32.INSTANCE.CloseHandle(timer);
            if (stopEvent != null) Kernel32.INSTANCE.CloseHandle(stopEvent);
        }
    }

    static final class InputSender {
        private static final long GEOMETRY_REFRESH_NANOS = 250_000_000L;
        private static final int MOUSEEVENTF_MOVE = 0x0001;
        private static final int MOUSEEVENTF_LEFTDOWN = 0x0002;
        private static final int MOUSEEVENTF_LEFTUP = 0x0004;
        private static final int MOUSEEVENTF_RIGHTDOWN = 0x0008;
        private static final int MOUSEEVENTF_RIGHTUP = 0x0010;
        private static final int MOUSEEVENTF_ABSOLUTE = 0x8000;
        private static final int MOUSEEVENTF_VIRTUALDESK = 0x4000;
        private static final int MOUSEEVENTF_MOVE_NOCOALESCE = 0x2000;
        private static final int KEYEVENTF_KEYUP = 0x0002;

        private final INPUT[] cursor = mouseInput(MOUSEEVENTF_MOVE | MOUSEEVENTF_ABSOLUTE
                | MOUSEEVENTF_VIRTUALDESK | MOUSEEVENTF_MOVE_NOCOALESCE);
        private INPUT[] firstKey = keyboardInput(PlaybackKey.Z.virtualKey());
        private INPUT[] secondKey = keyboardInput(PlaybackKey.X.virtualKey());
        private final INPUT[] leftButton = mouseInput(MOUSEEVENTF_LEFTUP);
        private final INPUT[] rightButton = mouseInput(MOUSEEVENTF_RIGHTUP);
        private final RECT client = new RECT();
        private final POINT origin = new POINT(0, 0);
        private long targetValue = Long.MIN_VALUE;
        private long geometryValidUntil;
        private double scale;
        private double viewportX;
        private double viewportY;
        private int virtualX;
        private int virtualY;
        private int virtualWidth;
        private int virtualHeight;

        void setKeyboardKeys(PlaybackKey first, PlaybackKey second) {
            if (first == null || second == null || first == second)
                throw new IllegalArgumentException("K1 and K2 must use different keys.");
            firstKey = keyboardInput(first.virtualKey());
            secondKey = keyboardInput(second.virtualKey());
        }

        boolean sendCursor(HWND target, double replayX, double replayY) {
            long packed = absolutePoint(target, replayX, replayY);
            if (packed == -1) return false;
            MOUSEINPUT mouse = cursor[0].input.mi;
            mouse.dx.setValue((int) (packed >>> 32));
            mouse.dy.setValue((int) packed);
            return send(cursor);
        }

        boolean sendKeyTransitions(int previousKeys, int newKeys) {
            boolean oldK1 = (previousKeys & 4) != 0;
            boolean oldK2 = (previousKeys & 8) != 0;
            boolean newK1 = (newKeys & 4) != 0;
            boolean newK2 = (newKeys & 8) != 0;
            boolean oldM1 = (previousKeys & 1) != 0 && !oldK1;
            boolean oldM2 = (previousKeys & 2) != 0 && !oldK2;
            boolean newM1 = (newKeys & 1) != 0 && !newK1;
            boolean newM2 = (newKeys & 2) != 0 && !newK2;
            return (oldK1 == newK1 || sendKeyboard(firstKey, newK1))
                    && (oldK2 == newK2 || sendKeyboard(secondKey, newK2))
                    && (oldM1 == newM1 || sendMouseButton(leftButton, newM1, MOUSEEVENTF_LEFTDOWN, MOUSEEVENTF_LEFTUP))
                    && (oldM2 == newM2 || sendMouseButton(rightButton, newM2, MOUSEEVENTF_RIGHTDOWN, MOUSEEVENTF_RIGHTUP));
        }

        void releaseAll() {
            sendKeyboard(firstKey, false);
            sendKeyboard(secondKey, false);
            sendMouseButton(leftButton, false, MOUSEEVENTF_LEFTDOWN, MOUSEEVENTF_LEFTUP);
            sendMouseButton(rightButton, false, MOUSEEVENTF_RIGHTDOWN, MOUSEEVENTF_RIGHTUP);
        }

        private static INPUT[] mouseInput(int flags) {
            INPUT[] inputs = (INPUT[]) new INPUT().toArray(1);
            inputs[0].type = new DWORD(INPUT.INPUT_MOUSE);
            inputs[0].input.setType(MOUSEINPUT.class);
            inputs[0].input.mi = new MOUSEINPUT();
            inputs[0].input.mi.dx = new LONG(0);
            inputs[0].input.mi.dy = new LONG(0);
            inputs[0].input.mi.mouseData = new DWORD(0);
            inputs[0].input.mi.dwFlags = new DWORD(flags);
            inputs[0].input.mi.time = new DWORD(0);
            inputs[0].input.mi.dwExtraInfo = new ULONG_PTR(0);
            return inputs;
        }

        private static INPUT[] keyboardInput(int virtualKey) {
            INPUT[] inputs = (INPUT[]) new INPUT().toArray(1);
            inputs[0].type = new DWORD(INPUT.INPUT_KEYBOARD);
            inputs[0].input.setType(KEYBDINPUT.class);
            inputs[0].input.ki = new KEYBDINPUT();
            inputs[0].input.ki.wVk = new WORD(virtualKey);
            inputs[0].input.ki.wScan = new WORD(0);
            inputs[0].input.ki.dwFlags = new DWORD(0);
            inputs[0].input.ki.time = new DWORD(0);
            inputs[0].input.ki.dwExtraInfo = new ULONG_PTR(0);
            return inputs;
        }

        private static boolean sendKeyboard(INPUT[] input, boolean down) {
            input[0].input.ki.dwFlags.setValue(down ? 0 : KEYEVENTF_KEYUP);
            return send(input);
        }

        private static boolean sendMouseButton(INPUT[] input, boolean down, int downFlag, int upFlag) {
            input[0].input.mi.dwFlags.setValue(down ? downFlag : upFlag);
            return send(input);
        }

        private static boolean send(INPUT[] input) {
            input[0].write();
            return User32.INSTANCE.SendInput(new DWORD(1), input, input[0].size()).intValue() == 1;
        }

        private long absolutePoint(HWND target, double replayX, double replayY) {
            long now = System.nanoTime();
            long handleValue = Pointer.nativeValue(target.getPointer());
            if (handleValue != targetValue || now >= geometryValidUntil) {
                origin.x = 0; origin.y = 0;
                if (!User32.INSTANCE.GetClientRect(target, client)
                        || !User32Extra.INSTANCE.ClientToScreen(target, origin)) return -1;
                double width = client.right - client.left;
                double height = client.bottom - client.top;
                if (width < 320 || height < 240) return -1;
                scale = Math.min(width / 640.0, height / 480.0);
                viewportX = origin.x + (width - 640 * scale) / 2.0;
                viewportY = origin.y + (height - 480 * scale) / 2.0;
                virtualX = User32.INSTANCE.GetSystemMetrics(SM_XVIRTUALSCREEN);
                virtualY = User32.INSTANCE.GetSystemMetrics(SM_YVIRTUALSCREEN);
                virtualWidth = User32.INSTANCE.GetSystemMetrics(SM_CXVIRTUALSCREEN);
                virtualHeight = User32.INSTANCE.GetSystemMetrics(SM_CYVIRTUALSCREEN);
                if (virtualWidth <= 1 || virtualHeight <= 1) return -1;
                targetValue = handleValue;
                geometryValidUntil = now + GEOMETRY_REFRESH_NANOS;
            }
            double screenX = viewportX + (64 + replayX) * scale;
            // Exported lazer replay cursors were consistently 8 osu pixels above the source
            // with the nominal 48-pixel inset (3593 matched transitions across this map).
            double screenY = viewportY + (56 + replayY) * scale;
            int absoluteX = (int) Math.round((screenX - virtualX) * 65535.0 / (virtualWidth - 1));
            int absoluteY = (int) Math.round((screenY - virtualY) * 65535.0 / (virtualHeight - 1));
            absoluteX = Math.clamp(absoluteX, 0, 65535);
            absoluteY = Math.clamp(absoluteY, 0, 65535);
            return ((long) absoluteX << 32) | Integer.toUnsignedLong(absoluteY);
        }
    }

    static final class GlobalHotkeys implements AutoCloseable {
        private final IntConsumer callback;
        private final AtomicBoolean started = new AtomicBoolean();
        private final CountDownLatch ready = new CountDownLatch(1);
        private Thread thread;
        private volatile int nativeThreadId;

        GlobalHotkeys(IntConsumer callback) {
            this.callback = callback;
        }

        void start() {
            if (!started.compareAndSet(false, true)) return;
            thread = Thread.ofPlatform().name("global-hotkeys").daemon(true).start(this::messageLoop);
        }

        private void messageLoop() {
            nativeThreadId = Kernel32.INSTANCE.GetCurrentThreadId();
            boolean startRegistered = User32.INSTANCE.RegisterHotKey(null, HOTKEY_START, MOD_NOREPEAT, VK_F8);
            boolean stopRegistered = User32.INSTANCE.RegisterHotKey(null, HOTKEY_STOP, MOD_NOREPEAT, VK_F12);
            ready.countDown();
            if (!startRegistered) callback.accept(HOTKEY_START_FAILED);
            if (!stopRegistered) callback.accept(HOTKEY_STOP_FAILED);
            MSG message = new MSG();
            while (User32.INSTANCE.GetMessage(message, null, 0, 0) > 0) {
                if (message.message == WM_HOTKEY) callback.accept(message.wParam.intValue());
            }
            if (startRegistered) User32.INSTANCE.UnregisterHotKey(null, HOTKEY_START);
            if (stopRegistered) User32.INSTANCE.UnregisterHotKey(null, HOTKEY_STOP);
        }

        @Override
        public void close() {
            if (!started.compareAndSet(true, false)) return;
            try {
                ready.await(1, TimeUnit.SECONDS);
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
            }
            int threadId = nativeThreadId;
            if (threadId != 0) User32.INSTANCE.PostThreadMessage(threadId, WM_QUIT, null, null);
            if (thread != null) {
                try {
                    thread.join(2000);
                } catch (InterruptedException exception) {
                    Thread.currentThread().interrupt();
                }
            }
        }
    }
}
