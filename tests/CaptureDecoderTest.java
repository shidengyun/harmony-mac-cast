import java.nio.file.Files;
import java.nio.file.Path;

public final class CaptureDecoderTest {
    public static void main(String[] args) throws Exception {
        byte[] video = Files.readAllBytes(Path.of(args[0]));
        for (int attempt = 0; attempt < 12; attempt++) {
            CastAppearance.CastDecoder decoder = new CastAppearance.CastDecoder();
            try {
                if (decoder.feed(video) == null) throw new AssertionError("No decoded frame in attempt " + attempt);
            } finally { decoder.close(); }
        }
        System.out.println("PASS: decode and release resources across 12 capture attempts");
    }
}
