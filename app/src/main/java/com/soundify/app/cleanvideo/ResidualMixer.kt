package com.soundify.app.cleanvideo

enum class ResidualMode {
    /** Do not add the reconstruction residual (official default). */
    PASS,

    /** Add the whole residual to the music stem; the clean mix is unchanged. */
    MUSIC,

    /** Split the residual evenly between music and SFX. */
    MUSIC_SFX,
}

/**
 * The masks do not reconstruct the mixture exactly. The official MRX code
 * can put that residual back into the stems (`dnr_consistency`); this is the
 * same idea in the model domain, before the stem gain is restored.
 */
object ResidualMixer {

    fun apply(
        input: Array<FloatArray>,
        stems: Array<Array<FloatArray>>,
        mode: ResidualMode,
    ) {
        if (mode == ResidualMode.PASS) return
        require(input.size == 2) { "stereo input required" }
        require(stems.size == 3) { "three stems required" }
        for (channel in 0 until 2) {
            val inputChannel = input[channel]
            val music = stems[0][channel]
            val voice = stems[1][channel]
            val sfx = stems[2][channel]
            require(music.size == inputChannel.size) { "stem length differs from input" }
            require(voice.size == inputChannel.size) { "stem length differs from input" }
            require(sfx.size == inputChannel.size) { "stem length differs from input" }
            for (i in inputChannel.indices) {
                val residual = inputChannel[i] - (music[i] + voice[i] + sfx[i])
                when (mode) {
                    ResidualMode.PASS -> Unit
                    ResidualMode.MUSIC -> music[i] += residual
                    ResidualMode.MUSIC_SFX -> {
                        music[i] += residual * 0.5f
                        sfx[i] += residual * 0.5f
                    }
                }
            }
        }
    }
}
