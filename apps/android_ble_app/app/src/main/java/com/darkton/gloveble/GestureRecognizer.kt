package com.darkton.gloveble

import kotlin.math.sqrt

/**
 * One labeled static reference pose, built from a recorded session: the average
 * flex shape (in normalized 0..100 percent space) for one hand.
 */
data class GestureTemplate(
    val label: String,
    val hand: Hand,
    val flex: FloatArray,
    val sampleCount: Int
)

/**
 * One labeled moving sign, stored as a fixed-length sequence of normalized
 * feature vectors (flex + accel + gyro) for matching with DTW.
 */
data class DynamicTemplate(
    val label: String,
    val hand: Hand,
    val sequence: Array<FloatArray>
)

/** Outcome of classifying a live pose or gesture against the dataset. */
data class RecognitionResult(
    val word: String,
    /** 0..1 — how close the live input sat to the nearest template. */
    val confidence: Float,
    /** True only when both the distance and the margin pass their thresholds. */
    val isConfident: Boolean,
    /** Which matcher produced this result. */
    val dynamic: Boolean = false
)

/**
 * On-device sign recognizer built entirely from the recorded dataset
 * (`gestures.csv`), so teaching a new sign only needs a new recording — there is
 * nothing to retrain.
 *
 * Each recorded session is auto-sorted into one of two matchers by how much the
 * hand moved while recording:
 *  - **Static** ("ท่านิ่ง") — k-nearest-neighbour over per-session flex centroids.
 *  - **Dynamic** ("ท่าเคลื่อนไหว") — nearest-neighbour with DTW over the
 *    resampled flex+IMU trajectory, so timing/speed differences don't matter.
 *
 * The live pipeline (see BleViewModel) decides which matcher to ask based on
 * whether the hand is currently moving.
 */
class GestureRecognizer(
    sessions: List<GestureSession>,
    private val k: Int = 3,
    /** Static: above this Euclidean distance the pose is "no match". */
    private val rejectDistance: Float = 55f,
    /** Static: nearest competing word must be this much farther than the winner. */
    private val minMargin: Float = 1.18f,
    /** Dynamic: above this per-step DTW distance the gesture is "no match". */
    private val dynamicRejectDistance: Float = 0.42f,
    /** Dynamic: nearest competing word must be this much farther than the winner. */
    private val dynamicMinMargin: Float = 1.12f
) {
    private val staticTemplates = mutableListOf<GestureTemplate>()
    private val dynamicTemplates = mutableListOf<DynamicTemplate>()

    init {
        build(sessions)
    }

    val isEmpty: Boolean get() = staticTemplates.isEmpty() && dynamicTemplates.isEmpty()
    val hasDynamic: Boolean get() = dynamicTemplates.isNotEmpty()
    val hasStatic: Boolean get() = staticTemplates.isNotEmpty()
    val labels: List<String>
        get() = (staticTemplates.map { it.label } + dynamicTemplates.map { it.label }).distinct()

    /** Builds a normalized feature vector from a live reading (IMU optional). */
    fun feature(flex: List<Int>, imu: ImuReading?): FloatArray = floatArrayOf(
        flexPercent(flex.getOrElse(0) { 0 }) / 100f,
        flexPercent(flex.getOrElse(1) { 0 }) / 100f,
        flexPercent(flex.getOrElse(2) { 0 }) / 100f,
        flexPercent(flex.getOrElse(3) { 0 }) / 100f,
        flexPercent(flex.getOrElse(4) { 0 }) / 100f,
        (imu?.ax ?: 0) / ImuFullScale,
        (imu?.ay ?: 0) / ImuFullScale,
        (imu?.az ?: 0) / ImuFullScale,
        (imu?.gx ?: 0) / ImuFullScale,
        (imu?.gy ?: 0) / ImuFullScale,
        (imu?.gz ?: 0) / ImuFullScale
    )

    // ---------- Static matching ----------

    /**
     * Classifies a held flex pose. `hand` selects same-hand templates first,
     * falling back to all templates when that hand has none recorded yet.
     */
    fun classifyStatic(flex: List<Int>, hand: Hand): RecognitionResult? {
        if (staticTemplates.isEmpty()) return null
        val query = FloatArray(FlexChannels) { flexPercent(flex.getOrElse(it) { 0 }).toFloat() }
        val pool = staticTemplates.filter { it.hand == hand }.ifEmpty { staticTemplates }

        val ranked = pool
            .map { it to euclid(query, it.flex) }
            .sortedBy { it.second }
        val nearest = ranked.first()
        val voteLabel = ranked.take(k.coerceAtMost(ranked.size))
            .groupingBy { it.first.label }
            .eachCount()
            .maxByOrNull { it.value }!!
            .key

        val nearestDist = nearest.second
        val otherDist = ranked.firstOrNull { it.first.label != nearest.first.label }?.second
        val margin = marginOf(nearestDist, otherDist)
        val confident = nearestDist <= rejectDistance && margin >= minMargin
        val confidence = (1f - nearestDist / rejectDistance).coerceIn(0f, 1f)

        return RecognitionResult(
            word = if (confident) voteLabel else nearest.first.label,
            confidence = confidence,
            isConfident = confident,
            dynamic = false
        )
    }

    // ---------- Dynamic matching ----------

    /**
     * Classifies a captured movement (a sequence of live feature vectors) with
     * DTW, so the same sign performed faster or slower still matches.
     */
    fun classifyDynamic(sequence: List<FloatArray>, hand: Hand): RecognitionResult? {
        if (dynamicTemplates.isEmpty() || sequence.size < 2) return null
        val query = resample(sequence, ResampleLength)
        val pool = dynamicTemplates.filter { it.hand == hand }.ifEmpty { dynamicTemplates }

        val ranked = pool
            .map { it to dtw(query, it.sequence) }
            .sortedBy { it.second }
        val nearest = ranked.first()
        val voteLabel = ranked.take(k.coerceAtMost(ranked.size))
            .groupingBy { it.first.label }
            .eachCount()
            .maxByOrNull { it.value }!!
            .key

        val nearestDist = nearest.second
        val otherDist = ranked.firstOrNull { it.first.label != nearest.first.label }?.second
        val margin = marginOf(nearestDist, otherDist)
        val confident = nearestDist <= dynamicRejectDistance && margin >= dynamicMinMargin
        val confidence = (1f - nearestDist / dynamicRejectDistance).coerceIn(0f, 1f)

        return RecognitionResult(
            word = if (confident) voteLabel else nearest.first.label,
            confidence = confidence,
            isConfident = confident,
            dynamic = true
        )
    }

    // ---------- Template building ----------

    private fun build(sessions: List<GestureSession>) {
        for (session in sessions) {
            if (session.label.isBlank()) continue
            // A session may hold both hands (dual-hand recording); keep them apart.
            session.samples.groupBy { it.hand }.forEach { (hand, samples) ->
                if (samples.size < 3) return@forEach
                if (meanGyroMagnitude(samples) >= DynamicMotionEnergy) {
                    buildDynamicTemplate(session.label, hand, samples)
                } else {
                    buildStaticTemplate(session.label, hand, samples)
                }
            }
        }
    }

    private fun buildStaticTemplate(label: String, hand: Hand, samples: List<FlexSample>) {
        val steady = steadyWindow(samples)
        if (steady.isEmpty()) return
        val mean = FloatArray(FlexChannels)
        for (s in steady) {
            mean[0] += flexPercent(s.f1)
            mean[1] += flexPercent(s.f2)
            mean[2] += flexPercent(s.f3)
            mean[3] += flexPercent(s.f4)
            mean[4] += flexPercent(s.f5)
        }
        for (i in mean.indices) mean[i] /= steady.size
        staticTemplates += GestureTemplate(label, hand, mean, steady.size)
    }

    private fun buildDynamicTemplate(label: String, hand: Hand, samples: List<FlexSample>) {
        val moving = trimToMotion(samples)
        if (moving.size < 2) return
        val sequence = resample(moving.map { sampleFeature(it) }, ResampleLength)
        dynamicTemplates += DynamicTemplate(label, hand, sequence)
    }

    private fun sampleFeature(s: FlexSample): FloatArray = floatArrayOf(
        flexPercent(s.f1) / 100f,
        flexPercent(s.f2) / 100f,
        flexPercent(s.f3) / 100f,
        flexPercent(s.f4) / 100f,
        flexPercent(s.f5) / 100f,
        s.ax / ImuFullScale, s.ay / ImuFullScale, s.az / ImuFullScale,
        s.gx / ImuFullScale, s.gy / ImuFullScale, s.gz / ImuFullScale
    )

    /**
     * Trims the first/last fifth of a recording (the hand settling into the pose
     * and releasing it) so a static centroid reflects the held shape.
     */
    private fun steadyWindow(samples: List<FlexSample>): List<FlexSample> {
        if (samples.size < 5) return samples
        val margin = samples.size / 5
        return samples.subList(margin, samples.size - margin)
    }

    /** Drops leading/trailing still frames so a dynamic template is just the motion. */
    private fun trimToMotion(samples: List<FlexSample>): List<FlexSample> {
        val first = samples.indexOfFirst { gyroMagnitude(it) >= MotionStopCounts }
        val last = samples.indexOfLast { gyroMagnitude(it) >= MotionStopCounts }
        if (first < 0 || last < first) return samples
        return samples.subList(first, last + 1)
    }

    private fun meanGyroMagnitude(samples: List<FlexSample>): Double {
        if (samples.isEmpty()) return 0.0
        return samples.sumOf { gyroMagnitude(it) } / samples.size
    }

    // ---------- Math helpers ----------

    private fun euclid(a: FloatArray, b: FloatArray): Float {
        var sum = 0f
        for (i in a.indices) {
            val d = a[i] - b[i]
            sum += d * d
        }
        return sqrt(sum)
    }

    private fun marginOf(nearest: Float, other: Float?): Float =
        if (other == null || nearest <= 0.0001f) Float.MAX_VALUE else other / nearest

    /** Linearly resamples a variable-length sequence to a fixed number of frames. */
    private fun resample(sequence: List<FloatArray>, length: Int): Array<FloatArray> {
        if (sequence.size == length) return sequence.toTypedArray()
        val dims = sequence.first().size
        return Array(length) { i ->
            val pos = if (length == 1) 0f else i * (sequence.size - 1f) / (length - 1f)
            val lo = pos.toInt()
            val hi = (lo + 1).coerceAtMost(sequence.size - 1)
            val frac = pos - lo
            FloatArray(dims) { d -> sequence[lo][d] * (1f - frac) + sequence[hi][d] * frac }
        }
    }

    /** DTW with a Sakoe-Chiba band; returns the per-step (length-normalized) distance. */
    private fun dtw(a: Array<FloatArray>, b: Array<FloatArray>): Float {
        val n = a.size
        val m = b.size
        val band = (maxOf(n, m) / 8).coerceAtLeast(3)
        val inf = Float.MAX_VALUE
        var prev = FloatArray(m + 1) { inf }
        var curr = FloatArray(m + 1) { inf }
        prev[0] = 0f

        for (i in 1..n) {
            curr.fill(inf)
            val from = maxOf(1, i - band)
            val to = minOf(m, i + band)
            for (j in from..to) {
                val cost = euclid(a[i - 1], b[j - 1])
                val best = minOf(prev[j], curr[j - 1], prev[j - 1])
                curr[j] = if (best == inf) inf else cost + best
            }
            val tmp = prev
            prev = curr
            curr = tmp
        }
        val total = prev[m]
        return if (total == inf) inf else total / (n + m)
    }

    companion object {
        /** int16 full-scale; both accel and gyro are raw int16 counts. */
        const val ImuFullScale = 32768f

        /** Frames every dynamic trajectory is stretched/squeezed to before DTW. */
        const val ResampleLength = 40

        /** Mean gyro magnitude (raw counts) above which a recording is "moving". */
        const val DynamicMotionEnergy = 3500.0

        /** Gyro magnitude (raw counts) treated as "still" when trimming. ~38 deg/s. */
        const val MotionStopCounts = 5000.0

        /** Gyro magnitude (raw counts) that starts a live gesture capture. ~61 deg/s. */
        const val MotionStartCounts = 8000.0

        fun gyroMagnitude(s: FlexSample): Double {
            val gx = s.gx.toDouble()
            val gy = s.gy.toDouble()
            val gz = s.gz.toDouble()
            return sqrt(gx * gx + gy * gy + gz * gz)
        }

        fun gyroMagnitude(imu: ImuReading?): Double {
            if (imu == null) return 0.0
            val gx = imu.gx.toDouble()
            val gy = imu.gy.toDouble()
            val gz = imu.gz.toDouble()
            return sqrt(gx * gx + gy * gy + gz * gz)
        }
    }
}
