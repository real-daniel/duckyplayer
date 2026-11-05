package io.duckyplayer;

import com.sun.jna.Pointer;
import com.sun.jna.Structure;
import com.sun.jna.platform.win32.COM.COMUtils;
import com.sun.jna.platform.win32.COM.Unknown;
import com.sun.jna.platform.win32.Guid.GUID;
import com.sun.jna.platform.win32.Ole32;
import com.sun.jna.platform.win32.WinNT.HRESULT;
import com.sun.jna.ptr.IntByReference;
import com.sun.jna.ptr.LongByReference;
import com.sun.jna.ptr.PointerByReference;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.HexFormat;

/** Minimal read-only WASAPI loopback capture for the default Windows output endpoint. */
final class WasapiLoopbackCapture implements AutoCloseable {
    interface Listener {
        void onSamples(AudioChunk chunk);
    }

    record AudioChunk(float[] monoSamples, int sampleRate, long firstSampleNanos) {
    }

    private static final GUID CLSID_MM_DEVICE_ENUMERATOR = new GUID("{BCDE0395-E52F-467C-8E3D-C4579291692E}");
    private static final GUID IID_MM_DEVICE_ENUMERATOR = new GUID("{A95664D2-9614-4F35-A746-DE8DB63617E6}");
    private static final GUID IID_AUDIO_CLIENT = new GUID("{1CB9AD4C-DBFA-4C32-B178-C2F568A703B2}");
    private static final GUID IID_AUDIO_CAPTURE_CLIENT = new GUID("{C8ADBD64-E71E-48A0-A4DE-185C395CD317}");
    private static final int CLSCTX_ALL = 23;
    private static final int AUDCLNT_SHAREMODE_SHARED = 0;
    private static final int AUDCLNT_STREAMFLAGS_LOOPBACK = 0x0002_0000;
    private static final int AUDCLNT_BUFFERFLAGS_SILENT = 0x0000_0002;
    private static final int WAVE_FORMAT_PCM = 1;
    private static final int WAVE_FORMAT_IEEE_FLOAT = 3;
    private static final int WAVE_FORMAT_EXTENSIBLE = 0xfffe;

    private volatile boolean stopRequested;
    private volatile RuntimeException failure;
    private Thread worker;

    synchronized void start(Listener listener) {
        if (worker != null && worker.isAlive()) throw new IllegalStateException("Audio capture is already running");
        stopRequested = false;
        failure = null;
        worker = Thread.ofPlatform().name("wasapi-loopback").priority(Thread.NORM_PRIORITY)
                .start(() -> {
                    try {
                        captureLoop(listener);
                    } catch (RuntimeException exception) {
                        failure = exception;
                    }
                });
    }

    synchronized void stop() {
        stopRequested = true;
        Thread current = worker;
        if (current != null && current != Thread.currentThread()) {
            try {
                current.join(2000);
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
            }
        }
        worker = null;
        RuntimeException capturedFailure = failure;
        failure = null;
        if (capturedFailure != null) throw capturedFailure;
    }

    RuntimeException failure() {
        return failure;
    }

    private void captureLoop(Listener listener) {
        HRESULT initialized = Ole32.INSTANCE.CoInitializeEx(Pointer.NULL, Ole32.COINIT_MULTITHREADED);
        boolean uninitialize = initialized.intValue() >= 0;
        if (!uninitialize) throw new IllegalStateException("Could not initialize Windows audio COM: " + initialized);

        MmDeviceEnumerator enumerator = null;
        MmDevice device = null;
        AudioClient audioClient = null;
        AudioCaptureClient captureClient = null;
        Pointer formatPointer = null;
        try {
            PointerByReference result = new PointerByReference();
            check(Ole32.INSTANCE.CoCreateInstance(CLSID_MM_DEVICE_ENUMERATOR, Pointer.NULL, CLSCTX_ALL,
                    IID_MM_DEVICE_ENUMERATOR, result));
            enumerator = new MmDeviceEnumerator(result.getValue());
            device = enumerator.defaultRenderEndpoint();
            audioClient = device.activateAudioClient();
            formatPointer = audioClient.mixFormat();
            WaveFormat format = WaveFormat.read(formatPointer);
            audioClient.initialize(formatPointer);
            captureClient = audioClient.captureClient();
            audioClient.startClient();

            while (!stopRequested) {
                int packetFrames = captureClient.nextPacketFrames();
                if (packetFrames == 0) {
                    Thread.sleep(1);
                    continue;
                }
                while (packetFrames > 0 && !stopRequested) {
                    CapturedBuffer buffer = captureClient.getBuffer();
                    try {
                        float[] mono = format.toMono(buffer.data, buffer.frames,
                                (buffer.flags & AUDCLNT_BUFFERFLAGS_SILENT) != 0);
                        listener.onSamples(new AudioChunk(mono, format.sampleRate,
                                Math.multiplyExact(buffer.qpcHundredNanos, 100L)));
                    } finally {
                        captureClient.releaseBuffer(buffer.frames);
                    }
                    packetFrames = captureClient.nextPacketFrames();
                }
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
        } finally {
            if (audioClient != null) audioClient.stopClient();
            if (captureClient != null) captureClient.Release();
            if (formatPointer != null) Ole32.INSTANCE.CoTaskMemFree(formatPointer);
            if (audioClient != null) audioClient.Release();
            if (device != null) device.Release();
            if (enumerator != null) enumerator.Release();
            if (uninitialize) Ole32.INSTANCE.CoUninitialize();
        }
    }

    @Override
    public void close() {
        stop();
    }

    private static void check(HRESULT result) {
        COMUtils.checkRC(result);
    }

    private static class ComObject extends Unknown {
        ComObject(Pointer pointer) {
            super(pointer);
        }

        HRESULT call(int slot, Object... arguments) {
            Object[] values = new Object[arguments.length + 1];
            values[0] = getPointer();
            System.arraycopy(arguments, 0, values, 1, arguments.length);
            return new HRESULT(_invokeNativeInt(slot, values));
        }
    }

    private static final class MmDeviceEnumerator extends ComObject {
        MmDeviceEnumerator(Pointer pointer) {
            super(pointer);
        }

        MmDevice defaultRenderEndpoint() {
            PointerByReference endpoint = new PointerByReference();
            check(call(4, 0, 0, endpoint)); // eRender, eConsole
            return new MmDevice(endpoint.getValue());
        }
    }

    private static final class MmDevice extends ComObject {
        MmDevice(Pointer pointer) {
            super(pointer);
        }

        AudioClient activateAudioClient() {
            PointerByReference client = new PointerByReference();
            check(call(3, IID_AUDIO_CLIENT, CLSCTX_ALL, Pointer.NULL, client));
            return new AudioClient(client.getValue());
        }
    }

    private static final class AudioClient extends ComObject {
        AudioClient(Pointer pointer) {
            super(pointer);
        }

        Pointer mixFormat() {
            PointerByReference format = new PointerByReference();
            check(call(8, format));
            return format.getValue();
        }

        void initialize(Pointer format) {
            check(call(3, AUDCLNT_SHAREMODE_SHARED, AUDCLNT_STREAMFLAGS_LOOPBACK,
                    1_000_000L, 0L, format, Pointer.NULL));
        }

        AudioCaptureClient captureClient() {
            PointerByReference client = new PointerByReference();
            check(call(14, IID_AUDIO_CAPTURE_CLIENT, client));
            return new AudioCaptureClient(client.getValue());
        }

        void startClient() {
            check(call(10));
        }

        void stopClient() {
            call(11);
        }
    }

    private record CapturedBuffer(Pointer data, int frames, int flags, long qpcHundredNanos) {
    }

    private static final class AudioCaptureClient extends ComObject {
        AudioCaptureClient(Pointer pointer) {
            super(pointer);
        }

        int nextPacketFrames() {
            IntByReference frames = new IntByReference();
            check(call(5, frames));
            return frames.getValue();
        }

        CapturedBuffer getBuffer() {
            PointerByReference data = new PointerByReference();
            IntByReference frames = new IntByReference();
            IntByReference flags = new IntByReference();
            LongByReference devicePosition = new LongByReference();
            LongByReference qpcPosition = new LongByReference();
            check(call(3, data, frames, flags, devicePosition, qpcPosition));
            return new CapturedBuffer(data.getValue(), frames.getValue(), flags.getValue(), qpcPosition.getValue());
        }

        void releaseBuffer(int frames) {
            check(call(4, frames));
        }
    }

    @Structure.FieldOrder({"formatTag", "channels", "sampleRate", "averageBytesPerSecond",
            "blockAlign", "bitsPerSample", "extraSize"})
    public static final class WaveFormatHeader extends Structure {
        public short formatTag;
        public short channels;
        public int sampleRate;
        public int averageBytesPerSecond;
        public short blockAlign;
        public short bitsPerSample;
        public short extraSize;

        WaveFormatHeader(Pointer pointer) {
            super(pointer);
            read();
        }
    }

    private record WaveFormat(int format, int channels, int sampleRate, int blockAlign, int bitsPerSample) {
        static WaveFormat read(Pointer pointer) {
            WaveFormatHeader header = new WaveFormatHeader(pointer);
            int encoding = Short.toUnsignedInt(header.formatTag);
            int bits = Short.toUnsignedInt(header.bitsPerSample);
            if (encoding == WAVE_FORMAT_EXTENSIBLE) {
                if (Short.toUnsignedInt(header.extraSize) < 22)
                    throw new IllegalStateException("Invalid extensible Windows audio format");
                encoding = pointer.getInt(24); // First DWORD of the KSDATAFORMAT_SUBTYPE GUID.
            }
            if (encoding != WAVE_FORMAT_PCM && encoding != WAVE_FORMAT_IEEE_FLOAT)
                throw new IllegalStateException("Unsupported Windows audio encoding: " + encoding
                        + " header=" + HexFormat.of().formatHex(pointer.getByteArray(0, 40)));
            return new WaveFormat(encoding, Short.toUnsignedInt(header.channels), header.sampleRate,
                    Short.toUnsignedInt(header.blockAlign), bits);
        }

        float[] toMono(Pointer data, int frames, boolean silent) {
            float[] result = new float[frames];
            if (silent || data == null) return result;
            ByteBuffer bytes = data.getByteBuffer(0, Math.multiplyExact(frames, blockAlign))
                    .order(ByteOrder.LITTLE_ENDIAN);
            int bytesPerSample = bitsPerSample / 8;
            for (int frame = 0; frame < frames; frame++) {
                double sum = 0;
                for (int channel = 0; channel < channels; channel++) {
                    int offset = frame * blockAlign + channel * bytesPerSample;
                    sum += sample(bytes, offset);
                }
                result[frame] = (float) (sum / channels);
            }
            return result;
        }

        private double sample(ByteBuffer bytes, int offset) {
            if (format == WAVE_FORMAT_IEEE_FLOAT && bitsPerSample == 32) return bytes.getFloat(offset);
            if (format == WAVE_FORMAT_PCM && bitsPerSample == 16) return bytes.getShort(offset) / 32768.0;
            if (format == WAVE_FORMAT_PCM && bitsPerSample == 24) {
                int value = Byte.toUnsignedInt(bytes.get(offset))
                        | Byte.toUnsignedInt(bytes.get(offset + 1)) << 8
                        | bytes.get(offset + 2) << 16;
                return value / 8_388_608.0;
            }
            if (format == WAVE_FORMAT_PCM && bitsPerSample == 32) return bytes.getInt(offset) / 2_147_483_648.0;
            throw new IllegalStateException("Unsupported Windows audio sample format: " + bitsPerSample + " bit");
        }
    }
}
