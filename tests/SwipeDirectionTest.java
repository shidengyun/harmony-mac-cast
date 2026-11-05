public final class SwipeDirectionTest {
    static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
    public static void main(String[] args) {
        check(CastAppearance.swipeCommand(0, 0, 1276, 2848)
            .equals("uitest uiInput swipe 1020 1424 255 1424 1800"), "left must use a concrete right-to-left drag");
        check(CastAppearance.swipeCommand(1, 0, 1276, 2848)
            .equals("uitest uiInput swipe 255 1424 1020 1424 1800"), "right must reverse the path");
        for (int rotation : new int[]{0, 90, 180, 270}) {
            for (int direction = 0; direction < 4; direction++) {
                String[] tokens = CastAppearance.swipeCommand(direction, rotation, 1276, 2848).split(" ");
                int x1 = Integer.parseInt(tokens[3]), y1 = Integer.parseInt(tokens[4]);
                int x2 = Integer.parseInt(tokens[5]), y2 = Integer.parseInt(tokens[6]);
                check(x1 > 0 && x1 < 1276 && x2 > 0 && x2 < 1276, "horizontal coordinate outside screen");
                check(y1 > 0 && y1 < 2848 && y2 > 0 && y2 < 2848, "vertical coordinate outside screen");
                if (rotation == 0) check(direction <= 1 ? y1 == y2 : x1 == x2, "gesture axis mismatch");
                if (rotation == 90) {
                    if (direction == 0) check(y1 < y2 && x1 == x2, "rotated left maps to physical down");
                    if (direction == 1) check(y1 > y2 && x1 == x2, "rotated right maps to physical up");
                }
                if (rotation == 180 && direction == 0) check(x1 < x2, "upside-down left must map to physical right");
                if (rotation == 270 && direction == 0) check(y1 > y2, "270-degree left maps to physical up");
            }
        }
        boolean rejected = false;
        try { CastAppearance.swipeCommand(4, 0, 1276, 2848); }
        catch (IllegalArgumentException expected) { rejected = true; }
        check(rejected, "invalid direction must be rejected");
        System.out.println("PASS: left/right paths, four rotations, screen bounds, invalid direction");
    }
}
