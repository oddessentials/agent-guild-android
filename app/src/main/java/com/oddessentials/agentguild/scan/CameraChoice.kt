package com.oddessentials.agentguild.scan

import android.content.Context
import android.hardware.camera2.CameraCharacteristics
import androidx.annotation.OptIn
import androidx.camera.camera2.interop.Camera2CameraInfo
import androidx.camera.camera2.interop.ExperimentalCamera2Interop

/** What the scanner knows about one camera, in the order CameraX lists them. */
data class CameraFacts(val facing: Facing, val source: Int?, val position: Int?) {
    enum class Facing { Back, Front, Other }
}

/** Which camera reads the sign-in code. */
@OptIn(ExperimentalCamera2Interop::class)
object CameraChoice {
    /** Meta's vendor tags. A passthrough camera has source 0; position 0 is the wearer's left. */
    private val SOURCE by lazy { CameraCharacteristics.Key("com.meta.extra_metadata.camera_source", Int::class.java) }
    private val POSITION by lazy { CameraCharacteristics.Key("com.meta.extra_metadata.position", Int::class.java) }
    private const val PASSTHROUGH = 0

    /**
     * The index in [cameras] of the one to use, or null when none can scan. A passthrough camera
     * comes first, the left one before others. On a Horizon OS headset nothing else is used: its
     * front camera shows the wearer's avatar, not the room. Elsewhere, the first back camera, then
     * the first front one, as CameraX's default selectors choose.
     */
    fun choose(cameras: List<CameraFacts>, headset: Boolean): Int? {
        val passthrough = cameras.indices.filter { cameras[it].source == PASSTHROUGH }
        if (passthrough.isNotEmpty()) return passthrough.minBy { if (cameras[it].position == 0) 0 else 1 }
        if (headset) return null
        return cameras.indexOfFirst { it.facing == CameraFacts.Facing.Back }.takeIf { it >= 0 }
            ?: cameras.indexOfFirst { it.facing == CameraFacts.Facing.Front }.takeIf { it >= 0 }
    }

    fun source(camera: Camera2CameraInfo) = vendorTag(camera, SOURCE)

    fun position(camera: Camera2CameraInfo) = vendorTag(camera, POSITION)

    // Camera2 throws for a vendor tag the device doesn't define.
    private fun vendorTag(camera: Camera2CameraInfo, key: CameraCharacteristics.Key<Int>): Int? =
        try {
            camera.getCameraCharacteristic(key)
        } catch (_: IllegalArgumentException) {
            null
        }

    const val HEADSET_CAMERA = "horizonos.permission.HEADSET_CAMERA"

    /** The two features Meta's `HorizonOsDetector.isOnHorizonOs` checks, without its library. */
    fun isHorizonOs(context: Context) =
        context.packageManager.hasSystemFeature("horizonos.software.horizon_os") ||
            context.packageManager.hasSystemFeature("oculus.hardware.standalone_vr")
}
