package com.kino.gbaemu.core

import android.graphics.Bitmap
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.util.Log
import android.view.SurfaceHolder
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.max

private const val TAG = "EmulatorEngine"

/** Native GBA frame rate: (2^24 Hz master clock) / (280896 cycles/frame). */
private const val GBA_FPS = 16777216.0 / 280896.0
private const val FRAME_PERIOD_NANOS = (1_000_000_000.0 / GBA_FPS).toLong()

/**
 * Owns a [MgbaCore] instance and drives it in real time: a render thread
 * paced to the GBA's native frame rate that blits into whatever
 * [SurfaceHolder] is currently attached, and an audio thread streaming
 * samples to an [AudioTrack]. The emulated machine itself (mCoreThread,
 * native side) keeps running independently of whether a surface is
 * attached; [attachSurface]/[detachSurface] only control whether frames
 * are drawn, which is what you want across Activity/Compose lifecycle
 * churn (rotation, backgrounding) without losing emulation state.
 */
class EmulatorEngine {
    val core = MgbaCore()

    @Volatile var fastForwardMultiplier: Int = 1
    @Volatile var smoothFiltering: Boolean = false
    @Volatile var onFps: ((Int) -> Unit)? = null

    private var renderThread: RenderThread? = null
    private var audioThread: AudioThread? = null
    private val running = AtomicBoolean(false)

    fun start(romPath: String, savePath: String, crashLogPath: String? = null): RomLoadResult {
        if (!core.create(crashLogPath)) {
            return RomLoadResult.CORE_NOT_READY
        }
        val result = core.loadRom(romPath, savePath)
        if (result != RomLoadResult.SUCCESS) {
            return result
        }
        if (!core.start()) {
            return RomLoadResult.CORE_NOT_READY
        }
        running.set(true)
        audioThread = AudioThread(core).also { it.start() }
        return RomLoadResult.SUCCESS
    }

    fun attachSurface(holder: SurfaceHolder) {
        detachSurface()
        renderThread = RenderThread(core, holder, this).also { it.start() }
    }

    fun detachSurface() {
        renderThread?.shutdown()
        renderThread = null
    }

    fun pause() = core.pause()

    fun resume() = core.unpause()

    fun reset() = core.reset()

    fun setKey(key: GbaKey, pressed: Boolean) = core.setKey(key, pressed)

    fun setRewinding(rewinding: Boolean) = core.setRewinding(rewinding)

    /**
     * Best-effort snapshot of the last rendered frame for a save-state
     * thumbnail. Reads the shared video buffer without coordinating with
     * the render thread, so on rare occasions it may race a
     * still-in-progress frame; acceptable for a preview image.
     */
    fun captureThumbnail(): Bitmap {
        val bmp = Bitmap.createBitmap(MgbaCore.GBA_WIDTH, MgbaCore.GBA_HEIGHT, Bitmap.Config.ARGB_8888)
        core.videoBuffer.rewind()
        bmp.copyPixelsFromBuffer(core.videoBuffer)
        core.videoBuffer.rewind()
        return bmp
    }

    fun saveStateBytes(): ByteArray? = core.saveStateBytes()

    fun loadStateBytes(data: ByteArray): Boolean = core.loadStateBytes(data)

    /**
     * The first touch of mGBA's cheat device lazily creates and attaches it
     * to the ARM core, mutating state the CPU thread also reads while it
     * runs. That's only safe while the thread is paused - which on a fresh
     * [start] it isn't yet, since starting the thread is what makes it run
     * in the first place. Pausing/unpausing here (and restoring whatever
     * pause state the caller already had) makes this safe to call right
     * after [start] and at any point during gameplay alike.
     */
    fun replaceCheats(cheats: List<Pair<String, String>>) {
        val wasPaused = core.isPaused()
        core.pause()
        core.cheatsClear()
        for ((name, code) in cheats) {
            core.cheatsAdd(name, code)
        }
        if (!wasPaused) {
            core.unpause()
        }
    }

    fun shutdown() {
        running.set(false)
        detachSurface()
        audioThread?.shutdown()
        audioThread = null
        core.stop()
        core.destroy()
    }

    /** Render thread: paced to the GBA's real frame rate, drawing into a SurfaceHolder. */
    private class RenderThread(
        private val core: MgbaCore,
        private val holder: SurfaceHolder,
        private val engine: EmulatorEngine,
    ) : Thread("KinoGBA-Render") {
        private val stop = AtomicBoolean(false)
        private val bitmap = Bitmap.createBitmap(MgbaCore.GBA_WIDTH, MgbaCore.GBA_HEIGHT, Bitmap.Config.ARGB_8888)
        private val paint = android.graphics.Paint()

        fun shutdown() {
            stop.set(true)
            try {
                join(500)
            } catch (e: InterruptedException) {
                Thread.currentThread().interrupt()
            }
        }

        override fun run() {
            var nextFrameAt = System.nanoTime()
            var framesThisSecond = 0
            var fpsWindowStart = nextFrameAt

            while (!stop.get()) {
                if (!core.beginFrame()) {
                    // Core thread shutting down or not started yet.
                    continue
                }

                val speed = max(1, engine.fastForwardMultiplier)
                val shouldDraw = framesThisSecond % speed == 0

                if (shouldDraw) {
                    bitmap.copyPixelsFromBuffer(core.videoBuffer)
                }
                core.videoBuffer.rewind()
                core.endFrame()

                if (shouldDraw) {
                    drawToSurface()
                }

                framesThisSecond++
                val now = System.nanoTime()
                if (now - fpsWindowStart >= 1_000_000_000L) {
                    engine.onFps?.invoke(framesThisSecond)
                    framesThisSecond = 0
                    fpsWindowStart = now
                }

                if (speed <= 1) {
                    nextFrameAt += FRAME_PERIOD_NANOS
                    val delay = nextFrameAt - System.nanoTime()
                    if (delay > 0) {
                        try {
                            Thread.sleep(delay / 1_000_000L, (delay % 1_000_000L).toInt())
                        } catch (e: InterruptedException) {
                            Thread.currentThread().interrupt()
                        }
                    } else {
                        nextFrameAt = System.nanoTime()
                    }
                } else {
                    nextFrameAt = System.nanoTime()
                }
            }
        }

        private fun drawToSurface() {
            val canvas = try {
                holder.lockCanvas()
            } catch (e: Exception) {
                null
            } ?: return
            try {
                paint.isFilterBitmap = engine.smoothFiltering
                canvas.drawColor(android.graphics.Color.BLACK)
                canvas.drawBitmap(bitmap, null, aspectFitRect(canvas.width, canvas.height), paint)
            } finally {
                try {
                    holder.unlockCanvasAndPost(canvas)
                } catch (e: Exception) {
                    Log.w(TAG, "Failed to post canvas", e)
                }
            }
        }

        private fun aspectFitRect(canvasWidth: Int, canvasHeight: Int): android.graphics.Rect {
            val srcRatio = MgbaCore.GBA_WIDTH.toFloat() / MgbaCore.GBA_HEIGHT.toFloat()
            val dstRatio = canvasWidth.toFloat() / canvasHeight.toFloat()
            return if (dstRatio > srcRatio) {
                val width = (canvasHeight * srcRatio).toInt()
                val left = (canvasWidth - width) / 2
                android.graphics.Rect(left, 0, left + width, canvasHeight)
            } else {
                val height = (canvasWidth / srcRatio).toInt()
                val top = (canvasHeight - height) / 2
                android.graphics.Rect(0, top, canvasWidth, top + height)
            }
        }
    }

    /** Audio thread: drains the native ring buffer into a streaming AudioTrack. */
    private class AudioThread(private val core: MgbaCore) : Thread("KinoGBA-Audio") {
        private val stop = AtomicBoolean(false)
        private var track: AudioTrack? = null

        fun shutdown() {
            stop.set(true)
            try {
                join(500)
            } catch (e: InterruptedException) {
                Thread.currentThread().interrupt()
            }
            track?.stop()
            track?.release()
        }

        override fun run() {
            val sampleRate = core.getSampleRate()
            val minBufferBytes = AudioTrack.getMinBufferSize(
                sampleRate,
                AudioFormat.CHANNEL_OUT_STEREO,
                AudioFormat.ENCODING_PCM_16BIT,
            )
            val bufferBytes = max(minBufferBytes, sampleRate / 10 * 4) // >=100ms of stereo 16-bit audio

            val audioTrack = AudioTrack.Builder()
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_GAME)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                        .build(),
                )
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setSampleRate(sampleRate)
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_STEREO)
                        .build(),
                )
                .setBufferSizeInBytes(bufferBytes)
                .setTransferMode(AudioTrack.MODE_STREAM)
                .build()
            track = audioTrack
            audioTrack.play()

            val chunk = ShortArray(1024)
            while (!stop.get()) {
                val framesRead = core.popAudio(chunk)
                if (framesRead > 0) {
                    audioTrack.write(chunk, 0, framesRead * 2)
                } else {
                    try {
                        sleep(4)
                    } catch (e: InterruptedException) {
                        Thread.currentThread().interrupt()
                    }
                }
            }
        }
    }
}
