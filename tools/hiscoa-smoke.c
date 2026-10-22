/* Native offline codec smoke test, runs in CI without a printer or CUPS. */
#include <stdint.h>
#include <stdio.h>
#include <string.h>
#include "hiscoa-compress.h"

int main(void) {
    const int rowbytes = 620, lines = 4;
    uint8_t pixels[620 * 4] = {0};
    uint8_t first[620 * 4 * 2 + 64] = {0};
    uint8_t last[620 * 4 * 2 + 64] = {0};
    uint8_t params[8] = {0};
    pixels[620] = 0xF0;
    size_t a = hiscoa_compress_band(first, sizeof(first), pixels, rowbytes, lines,
        HISCOA_EOB_NORMAL, &hiscoa_default_params);
    size_t b = hiscoa_compress_band(last, sizeof(last), pixels, rowbytes, lines,
        HISCOA_EOB_LAST, &hiscoa_default_params);
    if (a == 0 || b == 0 || a >= sizeof(pixels) || b >= sizeof(pixels)) {
        fprintf(stderr, "HISCOA invalid compressed sizes a=%zu b=%zu\n", a, b);
        return 1;
    }
    if (a == b && memcmp(first, last, a) == 0) {
        fprintf(stderr, "HISCOA EOB flags were indistinguishable\n");
        return 1;
    }
    if (hiscoa_format_params(params, sizeof(params), &hiscoa_default_params) != 8 ||
        params[0] != 1 || params[1] != 4 || params[5] != 0xF9) {
        fprintf(stderr, "HISCOA parameter mismatch\n");
        return 1;
    }
    printf("HISCOA smoke passed: normal=%zu last=%zu\n", a, b);
    return 0;
}
