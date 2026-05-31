#include "anomaly_registration_image.h"

void anomaly_registration_prefilter_luma_grid(
        const uint8_t *src,
        int            width,
        int            height,
        uint8_t       *tmp,
        uint8_t       *dst) {
    if (src == 0 || tmp == 0 || dst == 0 || width <= 0 || height <= 0) return;

    for (int y = 0; y < height; y++) {
        const int row = y * width;
        for (int x = 0; x < width; x++) {
            int x0 = x > 0 ? (x - 1) : x;
            int x2 = x + 1 < width ? (x + 1) : x;
            int sum = (int)src[row + x0] + (2 * (int)src[row + x]) + (int)src[row + x2];
            tmp[row + x] = (uint8_t)((sum + 2) >> 2);
        }
    }

    for (int y = 0; y < height; y++) {
        int y0 = y > 0 ? (y - 1) : y;
        int y2 = y + 1 < height ? (y + 1) : y;
        for (int x = 0; x < width; x++) {
            int sum = (int)tmp[y0 * width + x] + (2 * (int)tmp[y * width + x]) + (int)tmp[y2 * width + x];
            dst[y * width + x] = (uint8_t)((sum + 2) >> 2);
        }
    }
}
