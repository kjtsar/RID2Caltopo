#include "anomaly_linear_solve.h"

#include <math.h>

bool anomaly_linear_solve_3x3(
        const float a_in[3][3],
        const float b_in[3],
        float       out[3]) {
    float a[3][4];
    for (int r = 0; r < 3; r++) {
        for (int c = 0; c < 3; c++) a[r][c] = a_in[r][c];
        a[r][3] = b_in[r];
    }

    for (int pivot = 0; pivot < 3; pivot++) {
        int best_row = pivot;
        float best_abs = fabsf(a[pivot][pivot]);
        for (int r = pivot + 1; r < 3; r++) {
            float cand = fabsf(a[r][pivot]);
            if (cand > best_abs) {
                best_abs = cand;
                best_row = r;
            }
        }
        if (best_abs < 1e-4f) return false;
        if (best_row != pivot) {
            for (int c = pivot; c < 4; c++) {
                float tmp = a[pivot][c];
                a[pivot][c] = a[best_row][c];
                a[best_row][c] = tmp;
            }
        }
        float inv = 1.0f / a[pivot][pivot];
        for (int c = pivot; c < 4; c++) a[pivot][c] *= inv;
        for (int r = 0; r < 3; r++) {
            if (r == pivot) continue;
            float factor = a[r][pivot];
            if (fabsf(factor) < 1e-6f) continue;
            for (int c = pivot; c < 4; c++) {
                a[r][c] -= factor * a[pivot][c];
            }
        }
    }

    out[0] = a[0][3];
    out[1] = a[1][3];
    out[2] = a[2][3];
    return true;
}

bool anomaly_linear_solve_6x6(
        const float a_in[6][6],
        const float b_in[6],
        float       out[6]) {
    float a[6][7];
    for (int r = 0; r < 6; r++) {
        for (int c = 0; c < 6; c++) a[r][c] = a_in[r][c];
        a[r][6] = b_in[r];
    }

    for (int pivot = 0; pivot < 6; pivot++) {
        int best_row = pivot;
        float best_abs = fabsf(a[pivot][pivot]);
        for (int r = pivot + 1; r < 6; r++) {
            float cand = fabsf(a[r][pivot]);
            if (cand > best_abs) {
                best_abs = cand;
                best_row = r;
            }
        }
        if (best_abs < 1e-6f) return false;
        if (best_row != pivot) {
            for (int c = pivot; c < 7; c++) {
                float tmp = a[pivot][c];
                a[pivot][c] = a[best_row][c];
                a[best_row][c] = tmp;
            }
        }
        float inv = 1.0f / a[pivot][pivot];
        for (int c = pivot; c < 7; c++) a[pivot][c] *= inv;
        for (int r = 0; r < 6; r++) {
            if (r == pivot) continue;
            float factor = a[r][pivot];
            if (fabsf(factor) < 1e-8f) continue;
            for (int c = pivot; c < 7; c++) {
                a[r][c] -= factor * a[pivot][c];
            }
        }
    }

    for (int i = 0; i < 6; i++) out[i] = a[i][6];
    return true;
}
