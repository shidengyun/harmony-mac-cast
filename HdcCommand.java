import java.util.Arrays;
import java.util.concurrent.TimeUnit;

/** Bound connection-tool waits so an unavailable phone cannot stall App startup. */
public final class HdcCommand {
    public static void main(String[] args) throws Exception {
        int seconds = Integer.parseInt(args[0]);
        Process child = new ProcessBuilder(Arrays.copyOfRange(args, 1, args.length)).inheritIO().start();
        try {
            if (child.waitFor(seconds, TimeUnit.SECONDS)) System.exit(child.exitValue());
            child.destroy();
            if (!child.waitFor(2, TimeUnit.SECONDS)) child.destroyForcibly().waitFor();
            System.err.println("连接检查超时，请确认手机已连接。");
            System.exit(124);
        } finally {
            if (child.isAlive()) child.destroyForcibly();
        }
    }
}
