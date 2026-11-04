package cz.nothrow.spherecam

import kotlin.math.ceil
import kotlin.math.roundToInt

/** Seconds per frame of each stitcher phase (order of `Phase`), desktop 36-frame run; only the ratios matter. */
val DEFAULT_PHASE_WEIGHTS = doubleArrayOf(0.0005, 4.92, 5.68, 3.91, 1.65, 0.018, 0.61, 6.05, 0.39).map { it / 36 }.toDoubleArray()

fun overallFraction(weights: DoubleArray, phase: Int, fraction: Double): Double =
  (weights.take(phase).sum() + weights[phase] * fraction) / weights.sum()

// Early estimates swing wildly, so nothing is shown until the stitch is under way.
fun remainingSeconds(elapsedS: Double, p: Double): Double? =
  if (elapsedS < 5 || p < 0.05) null else elapsedS * (1 - p) / p

fun formatRemaining(s: Double): String =
  if (s < 120) "${(ceil(s / 10) * 10).toInt()} s" else "${(s / 60).roundToInt()} min"
