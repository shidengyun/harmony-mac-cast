import com.huawei.hosscrcpy.api.HosRemoteDevice;
import com.huawei.hosscrcpy.protocol.Scrcpy;
import com.huawei.hosscrcpy.protocol.ScrcpyServiceGrpc;
import io.grpc.stub.AbstractStub;
import io.grpc.stub.ClientCalls;
import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.HashMap;
import java.util.concurrent.TimeUnit;

/** Bounded transport and capture-service checks; no input is injected into the phone. */
final class CaptureConnection {
    private final String hdc;
    private final Map<String, Long> wirelessAttempts = new HashMap<>();
    CaptureConnection(String hdc) { this.hdc = hdc; }

    CaptureRecovery.Health probe(CaptureRecovery.Target target, HosRemoteDevice device,
                                  boolean imageMode, boolean checkService) throws Exception {
        if (!transport(target)) {
            long now = CaptureRecovery.now();
            if (target.sn.matches("[0-9.]+:[0-9]+")
                    && claimWirelessAttempt(target.sn, now)) {
                run("-s", target.ip + ":8710", "tconn", target.sn);
                if (!transport(target)) return CaptureRecovery.Health.TRANSPORT_DOWN;
            } else return CaptureRecovery.Health.TRANSPORT_DOWN;
        }
        if (!checkService || imageMode || device == null) return CaptureRecovery.Health.TRANSPORT_ONLY;
        return keyFrame(device) ? CaptureRecovery.Health.HEALTHY : CaptureRecovery.Health.SERVICE_DOWN;
    }
    private synchronized boolean claimWirelessAttempt(String sn, long now) {
        if (now - wirelessAttempts.getOrDefault(sn, 0L) < 20000) return false;
        wirelessAttempts.put(sn, now);
        return true;
    }
    private boolean transport(CaptureRecovery.Target target) throws Exception {
        String output = run("-s", target.ip + ":8710", "-t", target.sn,
            "shell", "echo", "HARMONY_CAST_ALIVE");
        return output.lines().anyMatch(line -> line.trim().equals("HARMONY_CAST_ALIVE"));
    }
    private String run(String... arguments) throws Exception {
        if (hdc == null) return "";
        String[] command = new String[arguments.length + 1];
        command[0] = hdc;
        System.arraycopy(arguments, 0, command, 1, arguments.length);
        Process process = new ProcessBuilder(command).redirectErrorStream(true).start();
        try {
            if (!process.waitFor(3, TimeUnit.SECONDS) || process.exitValue() != 0) return "";
            return new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        } finally {
            if (process.isAlive()) {
                process.descendants().forEach(ProcessHandle::destroyForcibly);
                process.destroyForcibly();
            }
        }
    }
    static boolean keyFrame(HosRemoteDevice device) {
        try {
            // Use a per-call deadline, preserving the SDK's long-lived streaming call options.
            for (Field field : HosRemoteDevice.class.getDeclaredFields()) {
                if (!AbstractStub.class.isAssignableFrom(field.getType())) continue;
                field.setAccessible(true);
                AbstractStub<?> stub = (AbstractStub<?>) field.get(device);
                if (stub == null) return false;
                ClientCalls.blockingUnaryCall(stub.getChannel(), ScrcpyServiceGrpc.c(),
                    stub.getCallOptions().withDeadlineAfter(2, TimeUnit.SECONDS), Scrcpy.Empty.getDefaultInstance());
                return true;
            }
        } catch (Exception error) {
            System.err.println("采集服务检查失败: " + error.getClass().getSimpleName());
        }
        return false;
    }
}
