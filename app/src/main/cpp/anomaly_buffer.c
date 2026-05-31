#include "anomaly_buffer.h"

#include <stdlib.h>

bool anomaly_buffer_ensure_u8_capacity(uint8_t **buffer, size_t *capacity, size_t count) {
    if (buffer == NULL || capacity == NULL) return false;
    if (count == 0) return true;
    if (*buffer != NULL && *capacity >= count) return true;
    uint8_t *grown = (uint8_t *)realloc(*buffer, count * sizeof(uint8_t));
    if (grown == NULL) return false;
    *buffer = grown;
    *capacity = count;
    return true;
}

bool anomaly_buffer_resize_u8(uint8_t **buffer, size_t count) {
    if (buffer == NULL) return false;
    if (count == 0) return true;
    uint8_t *grown = (uint8_t *)realloc(*buffer, count * sizeof(uint8_t));
    if (grown == NULL) return false;
    *buffer = grown;
    return true;
}

bool anomaly_buffer_ensure_float_capacity(float **buffer, size_t *capacity, size_t count) {
    if (buffer == NULL || capacity == NULL) return false;
    if (count == 0) return true;
    if (*buffer != NULL && *capacity >= count) return true;
    float *grown = (float *)realloc(*buffer, count * sizeof(float));
    if (grown == NULL) return false;
    *buffer = grown;
    *capacity = count;
    return true;
}

bool anomaly_buffer_resize_float(float **buffer, size_t count) {
    if (buffer == NULL) return false;
    if (count == 0) return true;
    float *grown = (float *)realloc(*buffer, count * sizeof(float));
    if (grown == NULL) return false;
    *buffer = grown;
    return true;
}

bool anomaly_buffer_ensure_double_capacity(double **buffer, size_t *capacity, size_t count) {
    if (buffer == NULL || capacity == NULL) return false;
    if (count == 0) return true;
    if (*buffer != NULL && *capacity >= count) return true;
    double *grown = (double *)realloc(*buffer, count * sizeof(double));
    if (grown == NULL) return false;
    *buffer = grown;
    *capacity = count;
    return true;
}

bool anomaly_buffer_ensure_int_capacity(int **buffer, size_t *capacity, size_t count) {
    if (buffer == NULL || capacity == NULL) return false;
    if (count == 0) return true;
    if (*buffer != NULL && *capacity >= count) return true;
    int *grown = (int *)realloc(*buffer, count * sizeof(int));
    if (grown == NULL) return false;
    *buffer = grown;
    *capacity = count;
    return true;
}
