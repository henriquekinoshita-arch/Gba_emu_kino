package com.kino.gbaemu.engine

import android.graphics.Bitmap
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.util.Log
import com.kino.gbaemu.core.LibretroCore
import com.kino.gbaemu.data.RomEntry
import com.kino.gbaemu.data.RomRepository
import kotlinx.coroutines.flow.MutableStateFlow
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean

private const val TAG = "EmulatorEngine"

/**
 * Drives one loaded core through its whole lifecycle: load, a single paced
 * render thread that calls retro_run() once per frame (no separate emulation
 * thread - see core_bridge.c's synchronous design), audio playback, save RAM
 * and save-state persistence, then teardown.
 */
class EmulatorEngine(private val nativeLibraryDir: String, private val repository: RomRepository) {

    private var handle: Long = 0
    private var thread: Thread? = null
    private val running = AtomicBoolean(false)
    private lateinit var rom: RomEntry
    private lateinit var saveRamFile: File

    // `bitmap` is mutated in place every frame (copyPixelsFromBuffer), not
    // reassigned - so it cannot double as its own change notification via
    // StateFlow (a repeated assignment of the same reference is always
    // "equal" to a plain Kotlin/Java Bitmap, which has no equals() override,
    // and StateFlow would silently conflate it away). `frameTick` is the
    // actual signal Compose observes to know a new frame is ready to redraw.
    var bitmap: Bitmap? = null
        private set
    val frameTick: MutableStateFlow<Long> = MutableStateFlow(0)
    val frameWidth: MutableStateFlow<Int> = MutableStateFlow(1)
    val frameHeight: MutableStateFlow<Int> = MutableStateFlow(1)

    fun load(rom: RomEntry): Boolean {
        this.rom = rom
        val corePath = File(nativeLibraryDir, "lib${rom.platform.coreLibraryName}.so").absolutePath
        handle = LibretroCore.nativeLoadCore(corePath)
        if (handle == 0L) {
            Log.e(TAG, "nativeLoadCore failed for $corePath")
            return false
        }

        LibretroCore.nativeSetDirectories(
            handle,
            repository.systemDir.absolutePath,
            repository.saveDir(rom.platform).absolutePath,
        )

        if (!LibretroCore.nativeLoadGame(handle, rom.romFile.absolutePath)) {
            Log.e(TAG, "nativeLoadGame failed for ${rom.romFile}")
            LibretroCore.nativeUnloadCore(handle)
            handle = 0
            return false
        }

        saveRamFile = File(repository.saveDir(rom.platform), rom.romFile.nameWithoutExtension + ".srm")
        if (saveRamFile.exists() && LibretroCore.nativeGetSaveRamSize(handle) > 0) {
            LibretroCore.nativeWriteSaveRam(handle, saveRamFile.readBytes())
        }

        frameWidth.value = LibretroCore.nativeGetFrameWidth(handle)
        frameHeight.value = LibretroCore.nativeGetFrameHeight(handle)
        return true
    }

    fun start() {
        if (running.getAndSet(true)) return
        thread = Thread(::runLoop, "kino-emu-loop").apply { start() }
    }

    fun stop() {
        if (!running.getAndSet(false)) return
        thread?.join(2000)
        thread = null
    }

    fun setButton(port: Int, id: Int, pressed: Boolean) {
        if (handle != 0L) LibretroCore.nativeSetButton(handle, port, id, pressed)
    }

    fun setPointer(normalizedX: Int, normalizedY: Int, pressed: Boolean) {
        if (handle != 0L) LibretroCore.nativeSetPointer(handle, normalizedX, normalizedY, pressed)
    }

    fun saveState(slot: Int): Boolean {
        if (handle == 0L) return false
        val data = LibretroCore.nativeSerialize(handle) ?: return false
        stateFile(slot).writeBytes(data)
        return true
    }

    fun loadState(slot: Int): Boolean {
        if (handle == 0L) return false
        val file = stateFile(slot)
        if (!file.exists()) return false
        return LibretroCore.nativeUnserialize(handle, file.readBytes())
    }

    fun reset() {
        if (handle != 0L) LibretroCore.nativeReset(handle)
    }

    fun shutdown() {
        stop()
        if (handle != 0L) {
            persistSaveRam()
            LibretroCore.nativeUnloadGame(handle)
            LibretroCore.nativeUnloadCore(handle)
            handle = 0
        }
    }

    private fun stateFile(slot: Int) =
        File(repository.statesDir(rom.platform), "${rom.romFile.nameWithoutExtension}.state$slot")

    private fun persistSaveRam() {
        if (LibretroCore.nativeGetSaveRamSize(handle) <= 0) return
        val data = LibretroCore.nativeReadSaveRam(handle) ?: return
        saveRamFile.writeBytes(data)
    }

    private fun runLoop() {
        val maxW = LibretroCore.nativeGetMaxWidth(handle)
        val maxH = LibretroCore.nativeGetMaxHeight(handle)
        val bitmap = Bitmap.createBitmap(maxW, maxH, Bitmap.Config.ARGB_8888)
        this.bitmap = bitmap

        val sampleRate = LibretroCore.nativeGetSampleRate(handle).toInt().coerceAtLeast(8000)
        val audioTrack = createAudioTrack(sampleRate)
        audioTrack.play()
        val audioBuffer = ShortArray(sampleRate) // plenty for a couple of frames' worth of interleaved stereo samples

        val fps = LibretroCore.nativeGetFps(handle).takeIf { it > 0 } ?: 60.0
        val frameNanos = (1_000_000_000.0 / fps).toLong()
        var nextFrameAt = System.nanoTime()
        var saveRamCounter = 0

        while (running.get()) {
            LibretroCore.nativeRunFrame(handle)

            val buffer = LibretroCore.nativeGetVideoBuffer(handle)
            if (buffer != null) {
                buffer.rewind()
                bitmap.copyPixelsFromBuffer(buffer)
                frameWidth.value = LibretroCore.nativeGetFrameWidth(handle)
                frameHeight.value = LibretroCore.nativeGetFrameHeight(handle)
                frameTick.value++
            }

            val popped = LibretroCore.nativePopAudio(handle, audioBuffer, audioBuffer.size / 2)
            if (popped > 0) {
                audioTrack.write(audioBuffer, 0, popped * 2)
            }

            // Cheap periodic autosave: every ~5s of gameplay, in case the app is killed
            // without a clean shutdown (persistSaveRam() also runs there for the normal path).
            if (++saveRamCounter >= (fps * 5).toInt()) {
                saveRamCounter = 0
                persistSaveRam()
            }

            nextFrameAt += frameNanos
            val sleepNanos = nextFrameAt - System.nanoTime()
            if (sleepNanos > 0) {
                try {
                    Thread.sleep(sleepNanos / 1_000_000, (sleepNanos % 1_000_000).toInt())
                } catch (_: InterruptedException) {
                    break
                }
            } else {
                // Fell behind (slow device/frame): resync instead of spiraling.
                nextFrameAt = System.nanoTime()
            }
        }

        audioTrack.stop()
        audioTrack.release()
    }

    private fun createAudioTrack(sampleRate: Int): AudioTrack {
        val minBufferSize = AudioTrack.getMinBufferSize(
            sampleRate,
            AudioFormat.CHANNEL_OUT_STEREO,
            AudioFormat.ENCODING_PCM_16BIT,
        )
        return AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_GAME)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .build()
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setSampleRate(sampleRate)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_STEREO)
                    .build()
            )
            .setBufferSizeInBytes(minBufferSize.coerceAtLeast(4096) * 2)
            .setTransferMode(AudioTrack.MODE_STREAM)
            .build()
    }
}
