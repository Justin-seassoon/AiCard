package com.aicard.ota.service;

/** 固件版本比较：按点分段数值比较（3.10 > 3.9），缺段视为 0（3.4 == 3.4.0）。 */
final class VersionComparator {

    private VersionComparator() {}

    /** a > b 返回正数，a == b 返回 0，a < b 返回负数。空值视为最低。 */
    static int compare(String a, String b) {
        boolean blankA = a == null || a.isBlank();
        boolean blankB = b == null || b.isBlank();
        if (blankA && blankB) return 0;
        if (blankA) return -1;
        if (blankB) return 1;

        String[] as = normalize(a).split("\\.");
        String[] bs = normalize(b).split("\\.");
        int len = Math.max(as.length, bs.length);
        for (int i = 0; i < len; i++) {
            String x = i < as.length ? as[i] : "0";
            String y = i < bs.length ? bs[i] : "0";
            int c = compareSegment(x, y);
            if (c != 0) {
                return c;
            }
        }
        return 0;
    }

    private static int compareSegment(String x, String y) {
        Integer nx = leadingInt(x);
        Integer ny = leadingInt(y);
        if (nx == null || ny == null) {
            return x.compareTo(y);
        }
        int c = Integer.compare(nx, ny);
        if (c != 0) {
            return c;
        }
        // 数值相同再比较后缀：无后缀（正式版）高于带后缀（如 3.4-beta）
        String sx = x.replaceFirst("^\\d+", "");
        String sy = y.replaceFirst("^\\d+", "");
        if (sx.isEmpty() && sy.isEmpty()) {
            return 0;
        }
        if (sx.isEmpty()) {
            return 1;
        }
        if (sy.isEmpty()) {
            return -1;
        }
        return sx.compareTo(sy);
    }

    private static Integer leadingInt(String s) {
        String digits = s.replaceFirst("^(\\d+).*$", "$1");
        if (digits.isEmpty() || !digits.chars().allMatch(Character::isDigit)) {
            return null;
        }
        try {
            return Integer.parseInt(digits);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static String normalize(String v) {
        String s = v.trim();
        if (s.startsWith("v") || s.startsWith("V")) {
            s = s.substring(1);
        }
        return s;
    }
}
