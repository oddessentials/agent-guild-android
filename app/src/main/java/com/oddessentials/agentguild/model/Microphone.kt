package com.oddessentials.agentguild.model

enum class MicrophoneDecision {
    Grant,

    /** Ask for the `RECORD_AUDIO` runtime permission, and grant only if the user allows it. */
    AskThenGrant,
    Deny,
}

/** `onPermissionRequest`: only audio capture, only for exactly the manager's origin. */
object Microphone {
    const val AUDIO_CAPTURE = "android.webkit.resource.AUDIO_CAPTURE"

    fun decide(resources: List<String>, origin: String?, manager: Origin, recordAudioGranted: Boolean): MicrophoneDecision {
        if (resources != listOf(AUDIO_CAPTURE) || Origin.parse(origin) != manager) return MicrophoneDecision.Deny
        return if (recordAudioGranted) MicrophoneDecision.Grant else MicrophoneDecision.AskThenGrant
    }
}
