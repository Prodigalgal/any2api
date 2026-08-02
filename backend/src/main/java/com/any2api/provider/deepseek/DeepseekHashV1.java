package com.any2api.provider.deepseek;

import java.util.Arrays;

/** DeepSeek's official SHA3-shaped worker skips Keccak round zero. */
final class DeepseekHashV1 {
    private static final int RATE_BYTES = 136;
    private static final long[] ROUND_CONSTANTS = {
        0x0000000000000001L, 0x0000000000008082L, 0x800000000000808AL,
        0x8000000080008000L, 0x000000000000808BL, 0x0000000080000001L,
        0x8000000080008081L, 0x8000000000008009L, 0x000000000000008AL,
        0x0000000000000088L, 0x0000000080008009L, 0x000000008000000AL,
        0x000000008000808BL, 0x800000000000008BL, 0x8000000000008089L,
        0x8000000000008003L, 0x8000000000008002L, 0x8000000000000080L,
        0x000000000000800AL, 0x800000008000000AL, 0x8000000080008081L,
        0x8000000000008080L, 0x0000000080000001L, 0x8000000080008008L
    };
    private static final int[] ROTATIONS = {
        0, 1, 62, 28, 27,
        36, 44, 6, 55, 20,
        3, 10, 43, 25, 39,
        41, 45, 15, 21, 8,
        18, 2, 61, 56, 14
    };
    private static final ThreadLocal<Workspace> WORKSPACE =
        ThreadLocal.withInitial(Workspace::new);

    private DeepseekHashV1() {}

    static boolean matches(byte[] prefix, int answer, byte[] target) {
        if (target.length != 32 || prefix.length + 10 >= RATE_BYTES) return false;
        var workspace = WORKSPACE.get();
        workspace.reset();
        System.arraycopy(prefix, 0, workspace.block, 0, prefix.length);
        var length = prefix.length + writeDecimal(answer, workspace.block, prefix.length);
        workspace.block[length] ^= 0x06;
        workspace.block[RATE_BYTES - 1] ^= (byte) 0x80;
        for (var index = 0; index < RATE_BYTES; index++) {
            workspace.state[index >>> 3] ^=
                (long) (workspace.block[index] & 0xff) << ((index & 7) << 3);
        }
        permute(workspace);
        for (var index = 0; index < target.length; index++) {
            var actual = (byte) (workspace.state[index >>> 3] >>> ((index & 7) << 3));
            if (actual != target[index]) return false;
        }
        return true;
    }

    private static int writeDecimal(int value, byte[] target, int offset) {
        if (value == 0) {
            target[offset] = '0';
            return 1;
        }
        var digits = 0;
        for (var remaining = value; remaining > 0; remaining /= 10) digits++;
        var remaining = value;
        for (var index = offset + digits - 1; index >= offset; index--) {
            target[index] = (byte) ('0' + remaining % 10);
            remaining /= 10;
        }
        return digits;
    }

    private static void permute(Workspace workspace) {
        var state = workspace.state;
        var columns = workspace.columns;
        var deltas = workspace.deltas;
        var moved = workspace.moved;
        // The official browser worker intentionally executes rounds 1 through 23.
        for (var round = 1; round < 24; round++) {
            for (var x = 0; x < 5; x++) {
                columns[x] = state[x] ^ state[x + 5] ^ state[x + 10]
                    ^ state[x + 15] ^ state[x + 20];
            }
            for (var x = 0; x < 5; x++) {
                deltas[x] = columns[(x + 4) % 5]
                    ^ Long.rotateLeft(columns[(x + 1) % 5], 1);
            }
            for (var y = 0; y < 5; y++) {
                for (var x = 0; x < 5; x++) state[x + 5 * y] ^= deltas[x];
            }
            for (var y = 0; y < 5; y++) {
                for (var x = 0; x < 5; x++) {
                    moved[y + 5 * ((2 * x + 3 * y) % 5)] =
                        Long.rotateLeft(state[x + 5 * y], ROTATIONS[x + 5 * y]);
                }
            }
            for (var y = 0; y < 5; y++) {
                for (var x = 0; x < 5; x++) {
                    state[x + 5 * y] = moved[x + 5 * y]
                        ^ (~moved[(x + 1) % 5 + 5 * y]
                            & moved[(x + 2) % 5 + 5 * y]);
                }
            }
            state[0] ^= ROUND_CONSTANTS[round];
        }
    }

    private static final class Workspace {
        private final byte[] block = new byte[RATE_BYTES];
        private final long[] state = new long[25];
        private final long[] columns = new long[5];
        private final long[] deltas = new long[5];
        private final long[] moved = new long[25];

        private void reset() {
            Arrays.fill(block, (byte) 0);
            Arrays.fill(state, 0L);
        }
    }
}
