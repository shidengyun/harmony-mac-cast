import com.huawei.hosscrcpy.api.HosRemoteConfig;
import com.huawei.hosscrcpy.api.HosRemoteDevice;
import com.huawei.hosscrcpy.protocol.Scrcpy;
import com.huawei.hosscrcpy.protocol.ScrcpyServiceGrpc;
import io.grpc.*;
import io.grpc.stub.*;
import java.lang.reflect.Field;
import java.nio.file.*;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

public final class CaptureConnectionTest {
    static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
    private static void run(String[] args) throws Exception {
        Path directory = Files.createTempDirectory("capture-connection-test-");
        Path executable = directory.resolve("hdc"), online = directory.resolve("online"), slow = directory.resolve("slow");
        Files.writeString(executable, "#!/bin/sh\n"
            + "printf '%s\\n' \"$*\" >> '" + directory + "/commands'\n"
            + "if [ -f '" + slow + "' ]; then sleep 10; exit 0; fi\n"
            + "case \"$*\" in *tconn*) touch '" + online + "'; echo connected; exit 0;; esac\n"
            + "if [ -f '" + online + "' ]; then echo HARMONY_CAST_ALIVE; else echo 'phone-one USB Offline localhost'; fi\n");
        executable.toFile().setExecutable(true);
        CaptureConnection connection = new CaptureConnection(executable.toString());
        CaptureRecovery.Target usb = new CaptureRecovery.Target("phone-one", "127.0.0.1");
        check(connection.probe(usb, null, false, false) == CaptureRecovery.Health.TRANSPORT_DOWN,
            "offline cached device must not be accepted as connected");
        Files.createFile(online);
        check(connection.probe(usb, null, false, false) == CaptureRecovery.Health.TRANSPORT_ONLY, "live transport accepted");
        Files.delete(online);
        CaptureRecovery.Target wireless = new CaptureRecovery.Target("192.0.2.22:8710", "127.0.0.1");
        check(connection.probe(wireless, null, false, false) == CaptureRecovery.Health.TRANSPORT_ONLY, "wireless reconnect succeeds");
        String commands = Files.readString(directory.resolve("commands"));
        check(commands.contains("tconn 192.0.2.22:8710") && !commands.contains("tconn phone-one"), "reconnect only the requested wireless target");
        Files.createFile(slow);
        long began = System.nanoTime();
        check(connection.probe(usb, null, false, false) == CaptureRecovery.Health.TRANSPORT_DOWN, "timed out transport is offline");
        check(System.nanoTime() - began < TimeUnit.SECONDS.toNanos(5), "hdc timeout remains bounded");

        AtomicBoolean answer = new AtomicBoolean(true);
        Server server = ServerBuilder.forPort(0).addService(ServerServiceDefinition.builder("ScrcpyService")
            .addMethod(ScrcpyServiceGrpc.c(), ServerCalls.asyncUnaryCall((Scrcpy.Empty request,
                    StreamObserver<Scrcpy.ReplyEndMessage> response) -> {
                if (answer.get()) {
                    response.onNext(Scrcpy.ReplyEndMessage.getDefaultInstance()); response.onCompleted();
                }
            })).build()).build().start();
        ManagedChannel channel = ManagedChannelBuilder.forAddress("127.0.0.1", server.getPort()).usePlaintext().build();
        try {
            HosRemoteDevice device = new HosRemoteDevice(new HosRemoteConfig("test-device"));
            AbstractStub<?> stub = ScrcpyServiceGrpc.a(channel);
            for (Field field : HosRemoteDevice.class.getDeclaredFields()) {
                if (AbstractStub.class.isAssignableFrom(field.getType())) {
                    field.setAccessible(true); field.set(device, stub);
                }
            }
            check(CaptureConnection.keyFrame(device), "capture service responds to a bounded key-frame request");
            answer.set(false);
            began = System.nanoTime();
            check(!CaptureConnection.keyFrame(device), "hung capture service detected");
            check(System.nanoTime() - began < TimeUnit.SECONDS.toNanos(4), "capture RPC timeout remains bounded");
            check(stub.getCallOptions().getDeadline() == null, "streaming call options were not modified");
        } finally {
            channel.shutdownNow().awaitTermination(3, TimeUnit.SECONDS);
            server.shutdownNow().awaitTermination(3, TimeUnit.SECONDS);
            try (var files = Files.list(directory)) {
                for (Path file : files.toList()) Files.deleteIfExists(file);
            }
            Files.delete(directory);
        }
        System.out.println("PASS: offline cache, exact wireless target, bounded hdc/RPC, healthy/hung service, unchanged streaming deadlines");
    }
    public static void main(String[] args) {
        try { run(args); System.exit(0); }
        catch (Throwable error) { error.printStackTrace(); System.exit(1); }
    }

}
