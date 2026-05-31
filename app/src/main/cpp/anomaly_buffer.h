#ifndef RID2CALTOPO_ANOMALY_BUFFER_H
#define RID2CALTOPO_ANOMALY_BUFFER_H

#include <stdbool.h>
#include <stddef.h>
#include <stdint.h>

bool anomaly_buffer_ensure_u8_capacity(uint8_t **buffer, size_t *capacity, size_t count);
bool anomaly_buffer_resize_u8(uint8_t **buffer, size_t count);

bool anomaly_buffer_ensure_float_capacity(float **buffer, size_t *capacity, size_t count);
bool anomaly_buffer_resize_float(float **buffer, size_t count);

bool anomaly_buffer_ensure_double_capacity(double **buffer, size_t *capacity, size_t count);
bool anomaly_buffer_ensure_int_capacity(int **buffer, size_t *capacity, size_t count);

#endif
