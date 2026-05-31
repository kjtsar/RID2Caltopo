#ifndef ANOMALY_LINEAR_SOLVE_H
#define ANOMALY_LINEAR_SOLVE_H

#include <stdbool.h>

bool anomaly_linear_solve_3x3(
        const float a_in[3][3],
        const float b_in[3],
        float       out[3]);

bool anomaly_linear_solve_6x6(
        const float a_in[6][6],
        const float b_in[6],
        float       out[6]);

#endif // ANOMALY_LINEAR_SOLVE_H
