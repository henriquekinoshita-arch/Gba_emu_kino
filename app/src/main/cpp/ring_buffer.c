#include "ring_buffer.h"

#include <stdlib.h>
#include <string.h>

int ring_buffer_init(RingBuffer *rb, size_t capacityFrames) {
    rb->frames = calloc(capacityFrames, sizeof(int16_t) * 2);
    if (!rb->frames) {
        return 0;
    }
    rb->capacityFrames = capacityFrames;
    atomic_store_explicit(&rb->head, 0, memory_order_relaxed);
    atomic_store_explicit(&rb->tail, 0, memory_order_relaxed);
    return 1;
}

void ring_buffer_destroy(RingBuffer *rb) {
    free(rb->frames);
    rb->frames = NULL;
    rb->capacityFrames = 0;
}

void ring_buffer_clear(RingBuffer *rb) {
    size_t tail = atomic_load_explicit(&rb->head, memory_order_acquire);
    atomic_store_explicit(&rb->tail, tail, memory_order_release);
}

void ring_buffer_push(RingBuffer *rb, int16_t left, int16_t right) {
    size_t head = atomic_load_explicit(&rb->head, memory_order_relaxed);
    size_t tail = atomic_load_explicit(&rb->tail, memory_order_acquire);
    size_t nextHead = (head + 1) % rb->capacityFrames;
    if (nextHead == tail) {
        // Buffer full: drop the oldest frame instead of the newest so
        // audio keeps advancing in time rather than stalling.
        tail = (tail + 1) % rb->capacityFrames;
        atomic_store_explicit(&rb->tail, tail, memory_order_release);
    }
    rb->frames[head * 2] = left;
    rb->frames[head * 2 + 1] = right;
    atomic_store_explicit(&rb->head, nextHead, memory_order_release);
}

size_t ring_buffer_pop(RingBuffer *rb, int16_t *out, size_t maxFrames) {
    size_t head = atomic_load_explicit(&rb->head, memory_order_acquire);
    size_t tail = atomic_load_explicit(&rb->tail, memory_order_relaxed);

    size_t available = (head + rb->capacityFrames - tail) % rb->capacityFrames;
    size_t toCopy = available < maxFrames ? available : maxFrames;

    for (size_t i = 0; i < toCopy; i++) {
        size_t idx = (tail + i) % rb->capacityFrames;
        out[i * 2] = rb->frames[idx * 2];
        out[i * 2 + 1] = rb->frames[idx * 2 + 1];
    }

    atomic_store_explicit(&rb->tail, (tail + toCopy) % rb->capacityFrames, memory_order_release);
    return toCopy;
}

size_t ring_buffer_available(const RingBuffer *rb) {
    size_t head = atomic_load_explicit(&rb->head, memory_order_acquire);
    size_t tail = atomic_load_explicit(&rb->tail, memory_order_acquire);
    return (head + rb->capacityFrames - tail) % rb->capacityFrames;
}
