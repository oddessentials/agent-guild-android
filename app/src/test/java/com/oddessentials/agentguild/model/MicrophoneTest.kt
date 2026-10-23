package com.oddessentials.agentguild.model

import org.junit.Assert.assertEquals
import org.junit.Test

class MicrophoneTest {
    private val manager = Origin("https", "mini.tail1234.ts.net", 443)
    private val audio = listOf(Microphone.AUDIO_CAPTURE)

    @Test
    fun grantsAudioCaptureToTheManagerOnceTheUserHas() {
        assertEquals(MicrophoneDecision.Grant, Microphone.decide(audio, "https://mini.tail1234.ts.net/", manager, recordAudioGranted = true))
        assertEquals(MicrophoneDecision.AskThenGrant, Microphone.decide(audio, "https://mini.tail1234.ts.net/", manager, recordAudioGranted = false))
    }

    @Test
    fun deniesEveryOtherOrigin() {
        for (origin in listOf("https://www.youtube-nocookie.com/", "https://mini.tail1234.ts.net:8443/", "http://mini.tail1234.ts.net/", "null", null)) {
            assertEquals(origin, MicrophoneDecision.Deny, Microphone.decide(audio, origin, manager, recordAudioGranted = true))
        }
    }

    @Test
    fun deniesEveryOtherResource() {
        val others = listOf(
            listOf("android.webkit.resource.VIDEO_CAPTURE"),
            listOf(Microphone.AUDIO_CAPTURE, "android.webkit.resource.VIDEO_CAPTURE"),
            listOf("android.webkit.resource.PROTECTED_MEDIA_ID"),
            listOf("android.webkit.resource.MIDI_SYSEX"),
            emptyList(),
        )
        for (resources in others) {
            assertEquals(resources.toString(), MicrophoneDecision.Deny, Microphone.decide(resources, "https://mini.tail1234.ts.net/", manager, recordAudioGranted = true))
        }
    }
}
