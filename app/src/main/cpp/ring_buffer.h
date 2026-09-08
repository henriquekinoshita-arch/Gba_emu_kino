#ifndef KINOGBA_RING_BUFFER_H
#define KINOGBA_RING_BUFFER_H

#include <stdatomic.h>
#include <stddef.h>
#include <stdint.h>

// Lock-free single-producer/single-consumer ring buffer of interleaved
// 16-bit stereo audio frames (left, right). The emulation thread is the
// only producer (called from mgba's per-sample audio callback) and the
// audio-output thread is the only consumer, so a simple atomic head/tail
// scheme is sufficient and avoids any locking on the hot audio path.
typedef struct {
    int16_t *frames; // capacityFrames * 2 (L/R) samples
    size_t capacityFrames;
    _Atomic size_t head; // next write index (producer-owned)
    _Atomic size_t tail; // next read index (consumer-owned)
} RingBuffer;

int ring_buffer_init(RingBuffer *rb, size_t capacityFrames);
void ring_buffer_destroy(RingBuffer *rb);
void ring_buffer_clear(RingBuffer *rb);

// Producer side: pushes one stereo frame, dropping it silently if full
// (better to lose a sample under overload than to block the CPU core).
void ring_buffer_push(RingBuffer *rb, int16_t left, int16_t right);

// Consumer side: copies up to maxFrames into out (interleaved L/R),
// returns the number of frames actually copied.
size_t ring_buffer_pop(RingBuffer *rb, int16_t *out, size_t maxFrames);

size_t ring_buffer_available(const RingBuffer *rb);

#endif // KINOGBA_RING_BUFFER_H
