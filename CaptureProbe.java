import com.huawei.hosscrcpy.api.*;
import java.nio.ByteBuffer;
import java.nio.file.*;
import java.io.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;

public class CaptureProbe {
    public static void main(String[] args) throws Exception {
        HosRemoteConfig config = new HosRemoteConfig(args[0]);
        config.setHdcPath(args[1]);
        config.setScale(2);
        config.setFrameRate(30);
        config.setBitRate(4000000);
        config.setPort(15000);
        HosRemoteDevice device = new HosRemoteDevice(config);
        AtomicLong bytes = new AtomicLong();
        CountDownLatch firstFrame = new CountDownLatch(1);
        AtomicReference<Throwable> failure = new AtomicReference<>();
        try (OutputStream output = Files.newOutputStream(Paths.get(args[2]))) {
            device.startCaptureScreen(new ScreenCapCallback() {
                public void onData(ByteBuffer data) {
                    byte[] chunk = new byte[data.remaining()];
                    data.get(chunk);
                    try { synchronized (output) { output.write(chunk); } }
                    catch (IOException error) { failure.set(error); }
                    bytes.addAndGet(chunk.length);
                    firstFrame.countDown();
                }
                public void onException(Throwable error) {
                    error.printStackTrace(); failure.set(error); firstFrame.countDown();
                }
                public void onReady() { System.out.println("CAPTURE_READY"); device.requestIDRFrame(); }
            });
            firstFrame.await(20, TimeUnit.SECONDS);
            if (bytes.get() > 0) Thread.sleep(1500);
            device.stopCaptureScreen();
            System.out.println("CAPTURE_BYTES=" + bytes.get());
        }
        System.exit(bytes.get() > 0 && failure.get() == null ? 0 : 1);
    }
}
