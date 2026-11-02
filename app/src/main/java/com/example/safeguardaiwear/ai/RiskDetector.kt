package com.example.safeguardaiwear.ai

import android.content.Context
import org.json.JSONObject
import org.tensorflow.lite.Interpreter
import java.io.Closeable
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.log10
import kotlin.math.sqrt

/**
 * On-watch audio risk detector.
 *
 *  1. Pretrained YAMNet (assets/ai/yamnet.tflite) turns each 0.975 s of sound into
 *     521 sound-class scores (speech, shouting, screaming, crying, slap ...).
 *  2. Our trained classifier (assets/ai/violence_head.json, made by
 *     train_and_evaluate.py) looks at the last ~3 s of scores and outputs a
 *     risk probability between 0 and 1.
 *  3. Loudness gate: if the last ~3 s are quieter than [Head.minLoudnessDb]
 *     (e.g. hiss, a distant TV, a quiet room) the risk is set to 0 – real danger
 *     (shouting, screaming, hits) is loud.
 *
 * Same maths as check_audio.py on the Mac.
 */
class RiskDetector private constructor(
    private val interpreter: Interpreter,
    private val labels: List<String>,
    private val head: Head
) : Closeable {

    class Head(
        val mean: FloatArray,
        val scale: FloatArray,
        val coef: FloatArray,
        val intercept: Float,
        val threshold: Float,
        val frameSamples: Int,
        val hopSamples: Int,
        val framesPerWindow: Int,
        /** "log" = use the logarithm of each mean/max score (must match training). */
        val logFeatures: Boolean = false,
        /** Alert after this many results in a row at/above [threshold] (2 = ~1 s). */
        val confirmResults: Int = 2,
        /** Ignore sound quieter than this (dB relative to full scale; speech ≈ -30). */
        val minLoudnessDb: Float = -42f
    )

    /** One result every 0.5 s. [loudnessDb] = loudest 0.975 s in the last ~3 s. */
    data class Result(
        val probability: Float,
        val topSound: String,
        val topScore: Float,
        val loudnessDb: Float
    )

    val threshold: Float get() = head.threshold
    val confirmResults: Int get() = head.confirmResults

    private val ring = FloatArray(head.frameSamples)   // last 0.975 s of audio
    private var ringPos = 0
    private var filled = 0
    private var sinceLastFrame = 0
    private val recentScores = ArrayDeque<FloatArray>()
    private val recentLoudness = ArrayDeque<Float>()

    private val input: ByteBuffer =
        ByteBuffer.allocateDirect(head.frameSamples * 4).order(ByteOrder.nativeOrder())
    private val output: ByteBuffer =
        ByteBuffer.allocateDirect(interpreter.getOutputTensor(0).numBytes()).order(ByteOrder.nativeOrder())

    /**
     * Feed raw 16-bit little-endian PCM (16 kHz mono).
     * Returns a new [Result] roughly every 0.5 s, otherwise null.
     */
    fun feed(pcm: ByteArray, length: Int): Result? {
        var result: Result? = null
        var i = 0
        while (i + 1 < length) {
            val sample = ((pcm[i + 1].toInt() shl 8) or (pcm[i].toInt() and 0xFF)).toShort()
            ring[ringPos] = sample / 32768f
            ringPos = (ringPos + 1) % ring.size
            if (filled < ring.size) filled++
            sinceLastFrame++
            if (filled == ring.size && sinceLastFrame >= head.hopSamples) {
                sinceLastFrame = 0
                result = evaluate()
            }
            i += 2
        }
        return result
    }

    private fun evaluate(): Result {
        // 1. YAMNet on the latest 0.975 s (oldest sample first).
        input.rewind()
        for (k in ring.indices) input.putFloat(ring[(ringPos + k) % ring.size])
        input.rewind()
        output.rewind()
        interpreter.run(input, output)
        output.rewind()
        val classes = output.capacity() / 4
        val scores = FloatArray(classes) { output.getFloat() }

        recentScores.addLast(scores)
        while (recentScores.size > head.framesPerWindow) recentScores.removeFirst()

        // Loudness of this 0.975 s (RMS in dB, 0 = maximum).
        var sumSquares = 0.0
        for (v in ring) sumSquares += (v * v).toDouble()
        val rms = sqrt(sumSquares / ring.size).toFloat()
        recentLoudness.addLast(20f * log10(maxOf(rms, 1e-6f)))
        while (recentLoudness.size > head.framesPerWindow) recentLoudness.removeFirst()
        val loudness = recentLoudness.maxOrNull() ?: -120f

        // 2. Mean + max of each class over the last frames, then logistic regression.
        var z = head.intercept
        for (j in 0 until classes) {
            var sum = 0f
            var max = -Float.MAX_VALUE
            for (frame in recentScores) {
                sum += frame[j]
                if (frame[j] > max) max = frame[j]
            }
            var meanFeature = sum / recentScores.size
            var maxFeature = max
            if (head.logFeatures) {
                meanFeature = ln(maxOf(meanFeature, 1e-6f))
                maxFeature = ln(maxOf(maxFeature, 1e-6f))
            }
            z += (meanFeature - head.mean[j]) / head.scale[j] * head.coef[j]
            z += (maxFeature - head.mean[classes + j]) / head.scale[classes + j] * head.coef[classes + j]
        }
        // Too quiet to be violence -> no risk.
        val probability = if (loudness < head.minLoudnessDb) 0f else 1f / (1f + exp(-z))

        var top = 0
        for (j in 1 until classes) if (scores[j] > scores[top]) top = j
        // Shown on the watch as e.g. "AI risk: 12% · Speech · -31 dB" (helps tune the gate).
        val label = "${labels.getOrElse(top) { "Sound $top" }} · ${loudness.toInt()} dB"
        return Result(probability, label, scores[top], loudness)
    }

    override fun close() {
        interpreter.close()
    }

    companion object {
        private const val DIR = "ai"

        /** Throws if the model files are missing from app/src/main/assets/ai/. */
        fun load(context: Context): RiskDetector {
            val assets = context.assets

            val modelBytes = assets.open("$DIR/yamnet.tflite").use { it.readBytes() }
            val modelBuffer = ByteBuffer.allocateDirect(modelBytes.size).order(ByteOrder.nativeOrder())
            modelBuffer.put(modelBytes)
            modelBuffer.rewind()

            val json = JSONObject(assets.open("$DIR/violence_head.json").bufferedReader().use { it.readText() })
            fun floats(name: String): FloatArray {
                val arr = json.getJSONArray(name)
                return FloatArray(arr.length()) { arr.getDouble(it).toFloat() }
            }
            val head = Head(
                mean = floats("scaler_mean"),
                scale = floats("scaler_scale"),
                coef = floats("coef"),
                intercept = json.getDouble("intercept").toFloat(),
                threshold = json.getDouble("threshold").toFloat(),
                frameSamples = json.optInt("frame_samples", 15_600),
                hopSamples = json.optInt("hop_samples", 8_000),
                framesPerWindow = json.optInt("frames_per_window", 5),
                logFeatures = json.optString("feature_transform", "none") == "log",
                confirmResults = json.optInt("confirm_results", 2).coerceAtLeast(1),
                minLoudnessDb = json.optDouble("min_loudness_db", -42.0).toFloat()
            )

            val interpreter = Interpreter(modelBuffer, Interpreter.Options().setNumThreads(2))
            if (interpreter.getInputTensor(0).numElements() != head.frameSamples) {
                interpreter.resizeInput(0, intArrayOf(head.frameSamples))
                interpreter.allocateTensors()
            }

            val labels = try {
                assets.open("$DIR/yamnet_labels.txt").bufferedReader().use { it.readLines() }
            } catch (e: Exception) {
                emptyList()
            }
            return RiskDetector(interpreter, labels, head)
        }
    }
}