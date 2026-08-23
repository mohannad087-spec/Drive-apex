package com.driveapex.audio

/**
 * Persistent, local acoustic identity learned across drives.
 * The genome is bounded and deterministic so behavior stays stable rather than drifting.
 */
data class SonicGenome(
    val aggression: Float = 0.45f,
    val smoothness: Float = 0.60f,
    val regenAffinity: Float = 0.35f,
    val highSpeedAffinity: Float = 0.30f,
    val launchAffinity: Float = 0.25f,
    val observations: Long = 0L
) {
    val maturity: Float
        get() = (observations / 5000f).coerceIn(0f, 1f)

    fun blend(next: DriverSonicSignature.Signature, weight: Float): SonicGenome {
        val alpha = weight.coerceIn(0.005f, 0.08f)
        return copy(
            aggression = ema(aggression, next.aggression, alpha),
            smoothness = ema(smoothness, next.smoothness, alpha),
            regenAffinity = ema(regenAffinity, next.regenAffinity, alpha),
            highSpeedAffinity = ema(highSpeedAffinity, next.highSpeedAffinity, alpha),
            launchAffinity = ema(launchAffinity, next.launchAffinity, alpha),
            observations = (observations + 1L).coerceAtMost(10_000_000L)
        )
    }

    fun toSignature(): DriverSonicSignature.Signature = DriverSonicSignature.Signature(
        aggression,
        smoothness,
        regenAffinity,
        highSpeedAffinity,
        launchAffinity
    )

    private fun ema(current: Float, target: Float, alpha: Float): Float =
        (current + (target - current) * alpha).coerceIn(0f, 1f)
}
