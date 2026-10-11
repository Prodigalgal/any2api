/*
 * DeepSeek PoW C Implementation
 * 23-round Keccak-f[1600] single-block permutation
 */
#include <stdint.h>
#include <string.h>
#include <stdio.h>

#define RATE_BYTES 136

static const uint64_t ROUND_CONSTANTS[23] = {
    0x0000000000008082ULL, 0x800000000000808AULL, 0x8000000080008000ULL,
    0x000000000000808BULL, 0x0000000080000001ULL, 0x8000000080008081ULL,
    0x8000000000008009ULL, 0x000000000000008AULL, 0x0000000000000088ULL,
    0x0000000080008009ULL, 0x000000008000000AULL, 0x000000008000808BULL,
    0x800000000000008BULL, 0x8000000000008089ULL, 0x8000000000008003ULL,
    0x8000000000008002ULL, 0x8000000000000080ULL, 0x000000000000800AULL,
    0x800000008000000AULL, 0x8000000080008081ULL, 0x8000000000008080ULL,
    0x0000000080000001ULL, 0x8000000080008008ULL
};

static inline uint64_t rotl64(uint64_t v, int c) {
    return (v << c) | (v >> (64 - c));
}

#ifdef _WIN32
__declspec(dllexport)
#endif
int solve_pow_c(const char* prefix, int prefix_len, const uint8_t* target, int start, int end) {
    if (!prefix || prefix_len <= 0 || !target || start < 0 || end <= start) {
        return -1;
    }
    uint64_t tw0, tw1, tw2, tw3;
    memcpy(&tw0, target, 8);
    memcpy(&tw1, target + 8, 8);
    memcpy(&tw2, target + 16, 8);
    memcpy(&tw3, target + 24, 8);

    uint8_t buf[RATE_BYTES];
    memset(buf, 0, RATE_BYTES);
    memcpy(buf, prefix, prefix_len);
    buf[RATE_BYTES - 1] = 0x80;

    char suffix[32];
    for (int ans = start; ans < end; ++ans) {
        int slen = snprintf(suffix, sizeof(suffix), "%d", ans);
        memcpy(buf + prefix_len, suffix, slen);
        buf[prefix_len + slen] = 0x06;

        uint64_t w[25];
        memset(w, 0, sizeof(w));
        memcpy(w, buf, RATE_BYTES);
        buf[prefix_len + slen] = 0;

        uint64_t w0 = w[0], w1 = w[1], w2 = w[2], w3 = w[3], w4 = w[4];
        uint64_t w5 = w[5], w6 = w[6], w7 = w[7], w8 = w[8], w9 = w[9];
        uint64_t w10 = w[10], w11 = w[11], w12 = w[12], w13 = w[13], w14 = w[14];
        uint64_t w15 = w[15], w16 = w[16], w17 = w[17], w18 = w[18], w19 = w[19];
        uint64_t w20 = w[20], w21 = w[21], w22 = w[22], w23 = w[23], w24 = w[24];

        for (int r = 0; r < 23; ++r) {
            uint64_t rc = ROUND_CONSTANTS[r];
            uint64_t c0 = w0 ^ w5 ^ w10 ^ w15 ^ w20;
            uint64_t c1 = w1 ^ w6 ^ w11 ^ w16 ^ w21;
            uint64_t c2 = w2 ^ w7 ^ w12 ^ w17 ^ w22;
            uint64_t c3 = w3 ^ w8 ^ w13 ^ w18 ^ w23;
            uint64_t c4 = w4 ^ w9 ^ w14 ^ w19 ^ w24;

            uint64_t d0 = c4 ^ rotl64(c1, 1);
            uint64_t d1 = c0 ^ rotl64(c2, 1);
            uint64_t d2 = c1 ^ rotl64(c3, 1);
            uint64_t d3 = c2 ^ rotl64(c4, 1);
            uint64_t d4 = c3 ^ rotl64(c0, 1);

            uint64_t s0 = w0 ^ d0, s1 = w1 ^ d1, s2 = w2 ^ d2, s3 = w3 ^ d3, s4 = w4 ^ d4;
            uint64_t s5 = w5 ^ d0, s6 = w6 ^ d1, s7 = w7 ^ d2, s8 = w8 ^ d3, s9 = w9 ^ d4;
            uint64_t s10 = w10 ^ d0, s11 = w11 ^ d1, s12 = w12 ^ d2, s13 = w13 ^ d3, s14 = w14 ^ d4;
            uint64_t s15 = w15 ^ d0, s16 = w16 ^ d1, s17 = w17 ^ d2, s18 = w18 ^ d3, s19 = w19 ^ d4;
            uint64_t s20 = w20 ^ d0, s21 = w21 ^ d1, s22 = w22 ^ d2, s23 = w23 ^ d3, s24 = w24 ^ d4;

            uint64_t b0 = s0;
            uint64_t b1 = rotl64(s6, 44);
            uint64_t b2 = rotl64(s12, 43);
            uint64_t b3 = rotl64(s18, 21);
            uint64_t b4 = rotl64(s24, 14);
            uint64_t b5 = rotl64(s3, 28);
            uint64_t b6 = rotl64(s9, 20);
            uint64_t b7 = rotl64(s10, 3);
            uint64_t b8 = rotl64(s16, 45);
            uint64_t b9 = rotl64(s22, 61);
            uint64_t b10 = rotl64(s1, 1);
            uint64_t b11 = rotl64(s7, 6);
            uint64_t b12 = rotl64(s13, 25);
            uint64_t b13 = rotl64(s19, 8);
            uint64_t b14 = rotl64(s20, 18);
            uint64_t b15 = rotl64(s4, 27);
            uint64_t b16 = rotl64(s5, 36);
            uint64_t b17 = rotl64(s11, 10);
            uint64_t b18 = rotl64(s17, 15);
            uint64_t b19 = rotl64(s23, 56);
            uint64_t b20 = rotl64(s2, 62);
            uint64_t b21 = rotl64(s8, 55);
            uint64_t b22 = rotl64(s14, 39);
            uint64_t b23 = rotl64(s15, 41);
            uint64_t b24 = rotl64(s21, 2);

            w0 = (b0 ^ ((~b1) & b2)) ^ rc;
            w1 = b1 ^ ((~b2) & b3);
            w2 = b2 ^ ((~b3) & b4);
            w3 = b3 ^ ((~b4) & b0);
            w4 = b4 ^ ((~b0) & b1);
            w5 = b5 ^ ((~b6) & b7);
            w6 = b6 ^ ((~b7) & b8);
            w7 = b7 ^ ((~b8) & b9);
            w8 = b8 ^ ((~b9) & b5);
            w9 = b9 ^ ((~b5) & b6);
            w10 = b10 ^ ((~b11) & b12);
            w11 = b11 ^ ((~b12) & b13);
            w12 = b12 ^ ((~b13) & b14);
            w13 = b13 ^ ((~b14) & b10);
            w14 = b14 ^ ((~b10) & b11);
            w15 = b15 ^ ((~b16) & b17);
            w16 = b16 ^ ((~b17) & b18);
            w17 = b17 ^ ((~b18) & b19);
            w18 = b18 ^ ((~b19) & b15);
            w19 = b19 ^ ((~b15) & b16);
            w20 = b20 ^ ((~b21) & b22);
            w21 = b21 ^ ((~b22) & b23);
            w22 = b22 ^ ((~b23) & b24);
            w23 = b23 ^ ((~b24) & b20);
            w24 = b24 ^ ((~b20) & b21);
        }

        if (w0 == tw0 && w1 == tw1 && w2 == tw2 && w3 == tw3) {
            return ans;
        }
    }
    return -1;
}
