package com.globalannotate.official;

/**
 * Shared vocabulary for the floating picker. Kept in one place so the host,
 * the official geometry port and the app shell cannot drift apart.
 *
 * The eight dock values are Mi Canvas' `lIIIlll` order used by its position
 * solver; the enum ordinal is not used by the solver itself, only the value.
 */
public final class Types {

    private Types() {
    }

    /** Mi Canvas' six tools in capsule order. */
    public enum PenStyle {
        BALLPOINT("ballpoint", "圆珠笔", false),
        PENCIL("pencil", "铅笔", false),
        INK_PEN("pen", "钢笔", false),
        MARKER("mark", "马克笔", false),
        WATER_COLOR("watercolor", "水彩笔", false),
        ERASER("eraser", "橡皮", true);

        public final String art;
        public final String label;
        public final boolean eraser;

        PenStyle(String art, String label, boolean eraser) {
            this.art = art;
            this.label = label;
            this.eraser = eraser;
        }

        public static final PenStyle[] TOOLBAR = {
                BALLPOINT, PENCIL, INK_PEN, MARKER, WATER_COLOR, ERASER,
        };
    }

    /** Mi Canvas picker positions. */
    public enum Dock {
        TOP, BOTTOM, LEFT, RIGHT,
        LEFT_TOP, LEFT_BOTTOM, RIGHT_TOP, RIGHT_BOTTOM;

        public boolean vertical() {
            return this == LEFT || this == RIGHT;
        }

        public boolean onLeft() {
            return this == LEFT || this == LEFT_TOP || this == LEFT_BOTTOM;
        }

        public boolean corner() {
            return this == LEFT_TOP || this == LEFT_BOTTOM
                    || this == RIGHT_TOP || this == RIGHT_BOTTOM;
        }

        public boolean topSide() {
            return this == TOP || this == LEFT_TOP || this == RIGHT_TOP;
        }

        /** The four anchors a released ball may stay on. */
        public static final Dock[] BALL_ANCHORS = {
                LEFT_TOP, RIGHT_TOP, LEFT_BOTTOM, RIGHT_BOTTOM,
        };
    }

    /** Red, yellow, blue, black, white; then the colour-wheel slot. */
    public static final int[] FIXED_COLORS = {
            0xFFE8464A, 0xFFFFD23F, 0xFF3482FF, 0xFF000000, 0xFFFFFFFF,
    };

    public static final int WHEEL_SLOT = 5;
    public static final int SLOT_COUNT = 6;

    public static boolean isNearBackground(int color) {
        int r = (color >> 16) & 0xFF;
        int g = (color >> 8) & 0xFF;
        int b = color & 0xFF;
        int lum = (r * 299 + g * 587 + b * 114) / 1000;
        return lum > 235;
    }
}
