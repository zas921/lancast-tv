package com.lancast.tv;

import java.nio.charset.StandardCharsets;

/** Byte-mode QR version 4, error correction L, mask 0. No network dependencies. */
public final class QrCode {
    public static final int SIZE = 33;
    private final boolean[][] modules = new boolean[SIZE][SIZE];
    private final boolean[][] fixed = new boolean[SIZE][SIZE];
    private QrCode() {}
    public static boolean[][] encode(String text) {
        byte[] input = text.getBytes(StandardCharsets.UTF_8);
        if (input.length > 78) throw new IllegalArgumentException("QR link too long");
        int[] data = new int[80];
        int bit = 0;
        bit = put(data, bit, 4, 4);
        bit = put(data, bit, input.length, 8);
        for (byte b : input) bit = put(data, bit, b & 255, 8);
        bit = put(data, bit, 0, Math.min(4, 640 - bit));
        while (bit % 8 != 0) bit = put(data, bit, 0, 1);
        for (int i = bit / 8; i < 80; i++) data[i] = ((i - bit / 8) % 2 == 0) ? 0xEC : 0x11;
        int[] divisor = new int[20]; divisor[19] = 1;
        int root = 1;
        for (int i = 0; i < 20; i++) {
            for (int j = 0; j < 20; j++) { divisor[j] = mul(divisor[j], root); if (j + 1 < 20) divisor[j] ^= divisor[j + 1]; }
            root = mul(root, 2);
        }
        int[] ecc = new int[20];
        for (int b : data) {
            int factor = b ^ ecc[0];
            System.arraycopy(ecc, 1, ecc, 0, 19); ecc[19] = 0;
            for (int j = 0; j < 20; j++) ecc[j] ^= mul(divisor[j], factor);
        }
        int[] codewords = new int[100];
        System.arraycopy(data, 0, codewords, 0, 80); System.arraycopy(ecc, 0, codewords, 80, 20);
        QrCode q = new QrCode();
        for (int i = 0; i < SIZE; i++) { q.set(6, i, i % 2 == 0); q.set(i, 6, i % 2 == 0); }
        q.finder(3, 3); q.finder(SIZE - 4, 3); q.finder(3, SIZE - 4);
        for (int dy = -2; dy <= 2; dy++) for (int dx = -2; dx <= 2; dx++) q.set(26 + dx, 26 + dy, Math.max(Math.abs(dx), Math.abs(dy)) != 1);
        int rem = 8;
        for (int i = 0; i < 10; i++) rem = (rem << 1) ^ (((rem >>> 9) & 1) * 0x537);
        int format = ((8 << 10) | rem) ^ 0x5412;
        for (int i = 0; i <= 5; i++) q.set(8, i, b(format, i));
        q.set(8, 7, b(format, 6)); q.set(8, 8, b(format, 7)); q.set(7, 8, b(format, 8));
        for (int i = 9; i < 15; i++) q.set(14 - i, 8, b(format, i));
        for (int i = 0; i < 8; i++) q.set(SIZE - 1 - i, 8, b(format, i));
        for (int i = 8; i < 15; i++) q.set(8, SIZE - 15 + i, b(format, i));
        q.set(8, SIZE - 8, true);
        bit = 0;
        for (int right = SIZE - 1; right >= 1; right -= 2) {
            if (right == 6) right = 5;
            for (int v = 0; v < SIZE; v++) {
                int y = ((right + 1) & 2) == 0 ? SIZE - 1 - v : v;
                for (int j = 0; j < 2; j++) {
                    int x = right - j;
                    if (!q.fixed[y][x]) {
                        boolean value = bit < 800 && ((codewords[bit >>> 3] >>> (7 - (bit & 7))) & 1) != 0;
                        q.modules[y][x] = value ^ ((x + y) % 2 == 0); bit++;
                    }
                }
            }
        }
        return q.modules;
    }
    private static int put(int[] data, int pos, int value, int count) {
        for (int i = count - 1; i >= 0; i--, pos++) data[pos >>> 3] |= ((value >>> i) & 1) << (7 - (pos & 7));
        return pos;
    }
    private static int mul(int x, int y) {
        int z = 0; for (int i = 7; i >= 0; i--) { z = (z << 1) ^ ((z >>> 7) * 0x11D); z ^= ((y >>> i) & 1) * x; } return z;
    }
    private static boolean b(int v, int i) { return ((v >>> i) & 1) != 0; }
    private void set(int x, int y, boolean value) { if (x >= 0 && y >= 0 && x < SIZE && y < SIZE) { modules[y][x] = value; fixed[y][x] = true; } }
    private void finder(int cx, int cy) {
        for (int dy = -4; dy <= 4; dy++) for (int dx = -4; dx <= 4; dx++) { int d = Math.max(Math.abs(dx), Math.abs(dy)); set(cx + dx, cy + dy, d != 2 && d != 4); }
    }
}
