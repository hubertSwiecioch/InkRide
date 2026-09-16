package com.speedevand.inkride.core.domain.tracking

/** Whether a power reading came from a meter or from [PowerEstimator]'s model. */
enum class PowerSource {
    MEASURED,
    ESTIMATED,
}
