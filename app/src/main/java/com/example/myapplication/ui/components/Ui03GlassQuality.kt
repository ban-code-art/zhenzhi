package com.example.myapplication.ui.components

internal enum class UiGlassQuality { None, Standard, Ultra, Unknown }

internal enum class UiGlassCapture { Pending, Ready, Unavailable, ForeignWindow }

internal enum class UiGlassQualityReason {
    Disabled,
    Requested,
    AwaitingCapture,
    AwaitingRenderer,
    HardwareAccelerationUnavailable,
    ApiTooOld,
    ApiBlurOnly,
    NoBackdrop,
    CrossWindowCapture,
    ShaderUnavailable,
}

internal data class UiGlassQualitySnapshot(
    val desired: UiGlassQuality = UiGlassQuality.Ultra,
    val effective: UiGlassQuality = UiGlassQuality.Unknown,
    val reason: UiGlassQualityReason = UiGlassQualityReason.AwaitingCapture,
    val capture: UiGlassCapture = UiGlassCapture.Pending,
)

internal fun resolveUiGlassQuality(
    desired: UiGlassQuality,
    sdk: Int,
    accelerated: Boolean,
    capture: UiGlassCapture,
    shaderReady: Boolean?,
): UiGlassQualitySnapshot {
    fun result(effective: UiGlassQuality, reason: UiGlassQualityReason) =
        UiGlassQualitySnapshot(desired, effective, reason, capture)

    return when {
        desired == UiGlassQuality.None -> result(UiGlassQuality.None, UiGlassQualityReason.Disabled)
        sdk < 31 -> result(UiGlassQuality.None, UiGlassQualityReason.ApiTooOld)
        !accelerated -> result(UiGlassQuality.None, UiGlassQualityReason.HardwareAccelerationUnavailable)
        capture == UiGlassCapture.Pending -> result(UiGlassQuality.Unknown, UiGlassQualityReason.AwaitingCapture)
        capture == UiGlassCapture.Unavailable -> result(UiGlassQuality.None, UiGlassQualityReason.NoBackdrop)
        capture == UiGlassCapture.ForeignWindow -> result(UiGlassQuality.None, UiGlassQualityReason.CrossWindowCapture)
        desired == UiGlassQuality.Standard -> result(UiGlassQuality.Standard, UiGlassQualityReason.Requested)
        sdk < 33 -> result(UiGlassQuality.Standard, UiGlassQualityReason.ApiBlurOnly)
        shaderReady == null -> result(UiGlassQuality.Unknown, UiGlassQualityReason.AwaitingRenderer)
        shaderReady -> result(UiGlassQuality.Ultra, UiGlassQualityReason.Requested)
        else -> result(UiGlassQuality.Standard, UiGlassQualityReason.ShaderUnavailable)
    }
}

