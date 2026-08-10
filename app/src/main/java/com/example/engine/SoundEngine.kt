package com.example.engine

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import com.example.model.VehicleType
import kotlin.math.sin

class SoundEngine {
    private var audioTrack: AudioTrack? = null
    private var isPlaying = false
    private var soundThread: Thread? = null

    var isMuted = false
    var currentRpm: Float = 1000f
    var throttleInput: Float = 0f
    var speedKmh: Float = 0f
    var isDrifting: Boolean = false
    var vehicleType: VehicleType = VehicleType.PRIDE_131
    var isHornActive: Boolean = false

    var triggerCollision: Boolean = false
        set(value) {
            if (value) {
                triggerCollision(1.0f)
            }
            field = false
        }

    @Volatile
    private var pendingCollisionIntensity: Float = 0f

    fun triggerCollision(intensity: Float = 1.0f) {
        pendingCollisionIntensity = intensity.coerceIn(0.5f, 3.5f)
    }

    private val sampleRate = 22050
    private val bufferSize = AudioTrack.getMinBufferSize(
        sampleRate,
        AudioFormat.CHANNEL_OUT_MONO,
        AudioFormat.ENCODING_PCM_16BIT
    )

    fun start() {
        if (isPlaying) return
        try {
            audioTrack = AudioTrack.Builder()
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
                        .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                        .build()
                )
                .setBufferSizeInBytes(bufferSize * 2)
                .setTransferMode(AudioTrack.MODE_STREAM)
                .build()

            if (audioTrack?.state == AudioTrack.STATE_INITIALIZED) {
                audioTrack?.play()
                isPlaying = true

                soundThread = Thread {
                    generateAudioLoop()
                }.apply {
                    priority = Thread.MAX_PRIORITY
                    start()
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    fun stop() {
        isPlaying = false
        try {
            soundThread?.interrupt()
            soundThread = null
            audioTrack?.stop()
            audioTrack?.release()
            audioTrack = null
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun generateAudioLoop() {
        var phaseEngine = 0.0
        var phaseIntake = 0.0
        var phaseScreech1 = 0.0
        var phaseScreech2 = 0.0
        var phaseHorn = 0.0
        var collisionEnvelope = 0.0
        var collisionMaxVol = 0.0

        var smoothedRpm = 1000.0
        var smoothedThrottle = 0.0
        var smoothedDrift = 0.0

        val chunkSamples = 512
        val buffer = ShortArray(chunkSamples)

        val masterVolume = 0.18

        while (isPlaying) {
            if (isMuted) {
                buffer.fill(0)
                audioTrack?.write(buffer, 0, chunkSamples)
                try {
                    Thread.sleep(20)
                } catch (e: InterruptedException) {
                    break
                }
                continue
            }

            // Check collision triggers
            if (pendingCollisionIntensity > 0f) {
                collisionMaxVol = (pendingCollisionIntensity * 0.42).coerceIn(0.25, 0.95)
                collisionEnvelope = 1.0
                pendingCollisionIntensity = 0f
            }

            // Smooth RPM & Throttle inputs to eliminate step artifacts
            smoothedRpm += (currentRpm - smoothedRpm) * 0.08
            smoothedThrottle += (throttleInput - smoothedThrottle) * 0.12
            val targetDrift = if (isDrifting) 1.0 else 0.0
            smoothedDrift += (targetDrift - smoothedDrift) * 0.15

            // Base engine firing frequency (Hz based on engine type cylinder count & RPM)
            val cylinderMult = when (vehicleType) {
                VehicleType.DENA_PLUS -> 1.4
                VehicleType.TOYOTA_LAND_CRUISER -> 2.0
                VehicleType.TOYOTA_HILUX -> 1.7
                VehicleType.PEUGEOT_PARS -> 1.3
                VehicleType.PEUGEOT_405 -> 1.25
                VehicleType.PRIDE_131 -> 1.1
            }
            val engineFreq = ((smoothedRpm / 60.0) * cylinderMult).coerceIn(18.0, 320.0)

            for (i in 0 until chunkSamples) {
                var sample = 0.0

                // 1. ENGINE SYNTHESIZER (Harmonics + Throttle Growl)
                phaseEngine += (2.0 * Math.PI * engineFreq) / sampleRate
                if (phaseEngine > 2.0 * Math.PI) phaseEngine -= 2.0 * Math.PI

                val fund = sin(phaseEngine)
                val h2 = sin(phaseEngine * 2.0) * 0.40
                val h3 = sin(phaseEngine * 3.0) * 0.22
                val h4 = sin(phaseEngine * 4.0) * 0.12
                val subBass = sin(phaseEngine * 0.5) * (0.35 + smoothedThrottle * 0.25)

                // Throttle Intake Roar
                phaseIntake += (2.0 * Math.PI * (engineFreq * 0.75)) / sampleRate
                if (phaseIntake > 2.0 * Math.PI) phaseIntake -= 2.0 * Math.PI
                val throttleRoar = sin(phaseIntake) * smoothedThrottle * 0.38

                val rawEngine = (fund + h2 + h3 + h4 + subBass + throttleRoar) * 0.32
                sample += rawEngine

                // 2. REALISTIC TIRE SCREECH SYNTHESIZER
                if (smoothedDrift > 0.02) {
                    val screechBaseFreq = 820.0 + (smoothedDrift * 280.0) + (speedKmh * 2.5)
                    phaseScreech1 += (2.0 * Math.PI * screechBaseFreq) / sampleRate
                    if (phaseScreech1 > 2.0 * Math.PI) phaseScreech1 -= 2.0 * Math.PI

                    phaseScreech2 += (2.0 * Math.PI * (screechBaseFreq * 1.34)) / sampleRate
                    if (phaseScreech2 > 2.0 * Math.PI) phaseScreech2 -= 2.0 * Math.PI

                    val tone1 = sin(phaseScreech1) * 0.18
                    val tone2 = sin(phaseScreech2) * 0.12
                    val frictionNoise = (Math.random() - 0.5) * 0.22
                    val screech = (tone1 + tone2 + frictionNoise) * smoothedDrift * 0.42

                    sample += screech
                }

                // 3. HORN SYNTHESIZER (Dual-tone chime)
                if (isHornActive) {
                    phaseHorn += (2.0 * Math.PI * 435.0) / sampleRate
                    if (phaseHorn > 2.0 * Math.PI) phaseHorn -= 2.0 * Math.PI
                    val horn = (sin(phaseHorn) + sin(phaseHorn * 1.25) * 0.85) * 0.28
                    sample += horn
                }

                // 4. CRASH & IMPACT SYNTHESIZER
                if (collisionEnvelope > 0.001) {
                    val impactThud = sin(phaseEngine * 0.25) * collisionEnvelope * 0.45
                    val metalCrunch = (Math.random() - 0.5) * collisionEnvelope * 0.65
                    val crashSample = (impactThud + metalCrunch) * collisionMaxVol

                    sample += crashSample
                    collisionEnvelope *= 0.9982
                }

                val output = (sample * masterVolume).coerceIn(-1.0, 1.0)
                buffer[i] = (output * 32767.0).toInt().toShort()
            }

            try {
                if (audioTrack?.playState == AudioTrack.PLAYSTATE_PLAYING) {
                    audioTrack?.write(buffer, 0, chunkSamples)
                } else {
                    Thread.sleep(10)
                }
            } catch (e: Exception) {
                break
            }
        }
    }
}

