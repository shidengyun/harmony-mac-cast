import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import org.bytedeco.javacv.FFmpegFrameGrabber;

public final class CaptureDecoderTest {
    public static void main(String[] args) throws Exception {
        byte[] video = Files.readAllBytes(Path.of(args[0]));
        for (int attempt = 0; attempt < 12; attempt++) {
            CastAppearance.CastDecoder decoder = new CastAppearance.CastDecoder();
            try {
                boolean decoded = false;
                for (int offset = 0; offset < video.length; offset += 256) {
                    byte[] chunk = Arrays.copyOfRange(video, offset, Math.min(video.length, offset + 256));
                    if (decoder.feed(chunk) != null) decoded = true;
                }
                if (!decoded) throw new AssertionError("No decoded frame in attempt " + attempt);
            } finally { decoder.close(); }
        }
        try (var input = new FFmpegFrameGrabber(args[0]);
             var decoder = new CastAppearance.CastDecoder()) {
            input.setFormat("h264");
            input.start();
            boolean decoded = false;
            for (var packet = input.grabPacket(); packet != null; packet = input.grabPacket()) {
                byte[] chunk = new byte[packet.size()];
                packet.data().get(chunk);
                if (decoder.feed(chunk) != null) decoded = true;
            }
            if (!decoded) throw new AssertionError("No decoded frame from packet-aligned chunks");
        }
        System.out.println("PASS: decode fragmented and packet-aligned input, and release resources across capture attempts");
    }
}
