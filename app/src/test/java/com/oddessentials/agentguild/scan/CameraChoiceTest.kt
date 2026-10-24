package com.oddessentials.agentguild.scan

import com.oddessentials.agentguild.scan.CameraFacts.Facing
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class CameraChoiceTest {
    private fun camera(facing: Facing, source: Int? = null, position: Int? = null) = CameraFacts(facing, source, position)

    private val avatar = camera(Facing.Front)

    @Test
    fun aHeadsetUsesItsLeftPassthroughCamera() {
        val cameras = listOf(avatar, camera(Facing.Back, source = 0, position = 1), camera(Facing.Back, source = 0, position = 0))
        assertEquals(2, CameraChoice.choose(cameras, headset = true))
    }

    @Test
    fun passthroughCamerasAreFoundWhateverWayTheyFace() {
        for (facing in Facing.entries) {
            assertEquals(1, CameraChoice.choose(listOf(avatar, camera(facing, source = 0, position = 0)), headset = true))
        }
    }

    @Test
    fun withoutALeftOneTheFirstPassthroughCameraIsUsed() {
        val cameras = listOf(avatar, camera(Facing.Other, source = 0, position = 1), camera(Facing.Other, source = 0))
        assertEquals(1, CameraChoice.choose(cameras, headset = true))
    }

    @Test
    fun otherSourcesAreNotPassthrough() {
        val cameras = listOf(camera(Facing.Back, source = 1, position = 0), camera(Facing.Back, source = 0, position = 1))
        assertEquals(1, CameraChoice.choose(cameras, headset = true))
    }

    @Test
    fun aHeadsetWithoutPassthroughNeverFallsBackToItsAvatarCamera() {
        assertNull(CameraChoice.choose(listOf(avatar), headset = true))
        assertNull(CameraChoice.choose(listOf(avatar, camera(Facing.Back, source = 1)), headset = true))
        assertNull(CameraChoice.choose(emptyList(), headset = true))
    }

    @Test
    fun aPassthroughCameraIsUsedEvenWhenTheDeviceIsntRecognized() {
        assertEquals(1, CameraChoice.choose(listOf(avatar, camera(Facing.Other, source = 0)), headset = false))
    }

    @Test
    fun elsewhereTheFirstBackCameraComesFirst() {
        val cameras = listOf(camera(Facing.Front), camera(Facing.Other), camera(Facing.Back), camera(Facing.Back))
        assertEquals(2, CameraChoice.choose(cameras, headset = false))
    }

    @Test
    fun thenTheFirstFrontCamera() {
        val cameras = listOf(camera(Facing.Other), camera(Facing.Front), camera(Facing.Front))
        assertEquals(1, CameraChoice.choose(cameras, headset = false))
    }

    @Test
    fun withoutVendorTagsADeviceKeepsItsBackThenFrontChoice() {
        assertEquals(0, CameraChoice.choose(listOf(camera(Facing.Back), camera(Facing.Front)), headset = false))
        assertEquals(0, CameraChoice.choose(listOf(camera(Facing.Front)), headset = false))
    }

    @Test
    fun noBackOrFrontCameraMeansNoneCanScan() {
        assertNull(CameraChoice.choose(listOf(camera(Facing.Other)), headset = false))
        assertNull(CameraChoice.choose(emptyList(), headset = false))
    }
}
