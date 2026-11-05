import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Session identity and recovery policy, independent of UI and connection tools. */
final class CaptureRecovery implements AutoCloseable {
    enum State { IDLE, CONNECTING, CONNECTED, CHECKING, OFFLINE, RECOVERING, FAILED }
    enum Health { HEALTHY, TRANSPORT_DOWN, SERVICE_DOWN, TRANSPORT_ONLY }
    static final class Target {
        final String sn, ip;
        Target(String sn, String ip) { this.sn = sn; this.ip = ip; }
    }
    interface Backend {
        Health probe(Target target, long token, boolean checkService) throws Exception;
        void restart(Target target, long token);
    }
    private final Backend backend;
    private final Executor worker;
    private final ExecutorService ownedWorker;
    private long token, startedAt, lastFrameAt, nextProbe, retryAt;
    private int transportFailures, serviceFailures, retries;
    private boolean active, probing, ready, hasFrame;
    private Target target;
    private State state = State.IDLE;
    static long now() { return System.nanoTime() / 1_000_000; }

    CaptureRecovery(Backend backend) {
        this.backend = backend;
        ownedWorker = Executors.newSingleThreadExecutor(task -> {
            Thread thread = new Thread(task, "cast-connection-check");
            thread.setDaemon(true);
            return thread;
        });
        worker = ownedWorker;
    }
    CaptureRecovery(Backend backend, Executor worker) {
        this.backend = backend; this.worker = worker; ownedWorker = null;
    }
    synchronized long start(Target target, long now) {
        this.target = target;
        active = true;
        retries = 0;
        retryAt = now;
        prepareAttempt(now);
        backend.restart(target, token);
        return token;
    }
    private void prepareAttempt(long now) {
        ++token;
        state = State.CONNECTING;
        startedAt = now;
        lastFrameAt = now;
        nextProbe = now + 4000;
        hasFrame = ready = probing = false;
        transportFailures = serviceFailures = 0;
    }
    synchronized void stop() {
        ++token;
        active = probing = false;
        state = State.IDLE;
    }
    synchronized boolean isCurrent(long candidate) { return active && candidate == token; }
    synchronized boolean active() { return active; }
    synchronized State state() { return state; }
    synchronized Target target() { return active ? target : null; }
    synchronized void ready(long candidate) { if (isCurrent(candidate)) ready = true; }
    synchronized boolean frame(long candidate, long now) {
        if (!isCurrent(candidate) || state == State.OFFLINE || state == State.RECOVERING || state == State.FAILED) return false;
        lastFrameAt = now;
        hasFrame = ready = true;
        state = State.CONNECTED;
        retries = serviceFailures = transportFailures = 0;
        return true;
    }
    synchronized void failed(long candidate, boolean fatal, long now) {
        if (!isCurrent(candidate)) return;
        if (!fatal && state == State.RECOVERING) return;
        state = fatal ? State.FAILED : State.RECOVERING;
        nextProbe = now;
        retryAt = now + retryDelay();
    }
    private long retryDelay() { return Math.min(30000L, 2000L << Math.min(retries, 4)); }

    void tick(long now) {
        final long candidate;
        final Target selected;
        final boolean checkService;
        synchronized (this) {
            if (!active || state == State.FAILED || probing || now < nextProbe) return;
            nextProbe = now + 4000;
            if (state == State.CONNECTED && now - lastFrameAt < 8000) return;
            candidate = token;
            selected = target;
            checkService = ready && now - lastFrameAt >= 8000;
            probing = true;
        }
        worker.execute(() -> {
            Health health;
            try { health = backend.probe(selected, candidate, checkService); }
            catch (Exception error) { health = Health.TRANSPORT_DOWN; }
            boolean restart = false;
            long newToken = candidate;
            synchronized (CaptureRecovery.this) {
                if (!isCurrent(candidate)) return;
                probing = false;
                if (health == Health.TRANSPORT_DOWN) {
                    if (++transportFailures >= 2) state = State.OFFLINE;
                    else if (state == State.CONNECTED) state = State.CHECKING;
                    return;
                }
                transportFailures = 0;
                if (health == Health.SERVICE_DOWN) {
                    if (++serviceFailures >= 2 && state != State.RECOVERING) {
                        state = State.RECOVERING;
                        retryAt = now + retryDelay();
                    }
                } else {
                    serviceFailures = 0;
                    if (state == State.CHECKING) state = hasFrame ? State.CONNECTED : State.CONNECTING;
                }
                boolean startupTimedOut = !hasFrame && now - startedAt >= 45000;
                if (state == State.OFFLINE || state == State.RECOVERING || startupTimedOut) {
                    if (now >= retryAt) {
                        ++retries;
                        retryAt = now + retryDelay();
                        prepareAttempt(now);
                        newToken = token;
                        restart = true;
                    }
                }
            }
            if (restart) backend.restart(selected, newToken);
        });
    }
    public void close() {
        stop();
        if (ownedWorker != null) ownedWorker.shutdownNow();
    }
}
