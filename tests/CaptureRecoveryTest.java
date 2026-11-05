import java.util.ArrayList;
import java.util.List;

/** Deterministic regression checks: no real phone or timing sleeps required. */
public final class CaptureRecoveryTest {
    static final class Backend implements CaptureRecovery.Backend {
        CaptureRecovery.Health health = CaptureRecovery.Health.HEALTHY;
        final List<Long> starts = new ArrayList<>();
        final List<String> devices = new ArrayList<>();
        boolean serviceChecked;
        public CaptureRecovery.Health probe(CaptureRecovery.Target target, long token, boolean service) {
            serviceChecked |= service;
            return health;
        }
        public void restart(CaptureRecovery.Target target, long token) {
            starts.add(token); devices.add(target.sn);
        }
    }
    static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
    static CaptureRecovery.Target target(String sn) { return new CaptureRecovery.Target(sn, "127.0.0.1"); }
    public static void main(String[] args) {
        Backend backend = new Backend();
        CaptureRecovery recovery = new CaptureRecovery(backend, Runnable::run);
        long token = recovery.start(target("phone-one"), 0);
        recovery.frame(token, 1);
        for (long now = 9000; now <= 300000; now += 4000) recovery.tick(now);
        check(backend.starts.size() == 1, "static screen must not restart after 8 seconds or 5 minutes");
        check(backend.serviceChecked, "static screen checks the service instead of trusting a saved frame");
        check(recovery.state() == CaptureRecovery.State.CONNECTED, "healthy static frame stays connected");

        backend.health = CaptureRecovery.Health.TRANSPORT_DOWN;
        recovery.tick(304000);
        check(recovery.state() == CaptureRecovery.State.CHECKING, "one failed probe is not a disconnection");
        recovery.tick(308000);
        check(recovery.state() == CaptureRecovery.State.OFFLINE, "two failures mark the transport offline");
        check(backend.starts.size() == 1, "do not start capture on an offline device");
        recovery.frame(token, 309000);
        check(recovery.state() == CaptureRecovery.State.OFFLINE, "buffered frames cannot overwrite offline status");
        backend.health = CaptureRecovery.Health.HEALTHY;
        recovery.tick(312000);
        check(backend.starts.size() == 2 && backend.devices.get(1).equals("phone-one"), "reconnect same device");
        recovery.frame(token, 313000);
        check(recovery.state() == CaptureRecovery.State.CONNECTING, "reject stale callbacks after reconnect");
        token = backend.starts.get(1);
        recovery.frame(token, 314000);

        backend.health = CaptureRecovery.Health.SERVICE_DOWN;
        recovery.tick(324000); recovery.tick(328000);
        check(recovery.state() == CaptureRecovery.State.RECOVERING, "two service failures enter recovery");
        check(backend.starts.size() == 2, "retry backoff prevents immediate restart loops");
        recovery.tick(332000);
        check(backend.starts.size() == 3, "healthy transport can recover an unresponsive capture service");
        token = backend.starts.get(2);
        recovery.failed(token, false, 333000);
        recovery.failed(token, false, 334000);
        backend.health = CaptureRecovery.Health.HEALTHY;
        recovery.tick(340000);
        check(backend.starts.size() == 4, "repeated errors cannot postpone a retry forever");

        recovery.stop();
        recovery.tick(500000);
        check(backend.starts.size() == 4 && recovery.state() == CaptureRecovery.State.IDLE, "manual stop cancels recovery");
        token = recovery.start(target("phone-two"), 600000);
        recovery.failed(token, true, 600001);
        recovery.tick(900000);
        check(backend.starts.size() == 5 && recovery.state() == CaptureRecovery.State.FAILED, "unsupported system does not retry forever");
        token = recovery.start(target("phone-three"), 1000000);
        recovery.tick(1046000);
        check(backend.starts.size() == 7, "no first frame within 45 seconds retries startup");
        recovery.close();

        List<Runnable> pending = new ArrayList<>();
        Backend late = new Backend();
        late.health = CaptureRecovery.Health.TRANSPORT_DOWN;
        CaptureRecovery guarded = new CaptureRecovery(late, pending::add);
        long old = guarded.start(target("old-phone"), 0);
        guarded.tick(5000);
        guarded.stop();
        guarded.start(target("new-phone"), 6000);
        pending.remove(0).run();
        guarded.failed(old, false, 7000);
        check(late.starts.size() == 2 && guarded.state() == CaptureRecovery.State.CONNECTING,
            "late probes and failures cannot affect a newly selected phone");
        guarded.close();
        System.out.println("PASS: static screen, transport loss/restoration, service failure, retry backoff, stop, switch, stale callbacks, startup timeout");
    }
}
