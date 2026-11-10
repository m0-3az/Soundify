package com.soundify.app.editor

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class FilterGraphTest {

    private fun clip(
        id: String = "clip",
        sourceHash: String = "hash",
        sourceDurationMs: Long = 2_000L,
        trimStartMs: Long = 0L,
        trimEndMs: Long = sourceDurationMs,
        fadeInMs: Long = 0L,
        fadeOutMs: Long = 0L,
        gainDb: Float = 0f,
        speed: Float = 1f,
        pitchSemitones: Int = 0,
        denoise: DenoiseMode = DenoiseMode.OFF,
        clarity: Boolean = false,
        eqPreset: EqPreset = EqPreset.FLAT,
        eqLowDb: Float = 0f,
        eqMidDb: Float = 0f,
        eqHighDb: Float = 0f,
        compThresholdDb: Float = -18f,
        compRatio: Float = 3f,
        compMakeupDb: Float = 0f,
        crossfadeMs: Long = 0L,
    ) = EditorClip(
        id = id,
        sourceHash = sourceHash,
        sourceFile = "sources/$sourceHash.wav",
        displayName = "clip.wav",
        sourceDurationMs = sourceDurationMs,
        sourceSampleRate = 44_100,
        sourceChannels = 2,
        trimStartMs = trimStartMs,
        trimEndMs = trimEndMs,
        fadeInMs = fadeInMs,
        fadeOutMs = fadeOutMs,
        gainDb = gainDb,
        speed = speed,
        pitchSemitones = pitchSemitones,
        denoise = denoise,
        clarity = clarity,
        eqPreset = eqPreset,
        eqLowDb = eqLowDb,
        eqMidDb = eqMidDb,
        eqHighDb = eqHighDb,
        compThresholdDb = compThresholdDb,
        compRatio = compRatio,
        compMakeupDb = compMakeupDb,
        crossfadeMs = crossfadeMs,
    )

    private fun project(vararg clips: EditorClip) =
        EditorProject(name = "Test project", clips = clips.toList())

    private fun planOf(
        project: EditorProject,
        clips: List<EditorClip> = project.clips,
        format: EditorFormat = EditorFormat.WAV,
    ) = FilterGraph.build(project, clips, format)

    private fun singleClipPlan(clip: EditorClip, format: EditorFormat = EditorFormat.WAV) =
        planOf(project(clip), listOf(clip), format)

    /** One clip chain with the fixed head and tail the brief mandates. */
    private fun chain(
        effects: List<String> = emptyList(),
        volume: String = "1.000000",
        timeline: List<String> = emptyList(),
        trimStart: String = "0.000",
        trimEnd: String = "2.000",
    ): String = (
        listOf(
            "atrim=start=$trimStart:end=$trimEnd",
            "asetpts=PTS-STARTPTS",
            AFORMAT,
        ) + effects + listOf("volume=$volume") + timeline + listOf(
            "aresample=44100",
            AFORMAT,
            "asetpts=PTS-STARTPTS",
        )
    ).joinToString(",")

    private fun graph(filters: String) = "[0:a]$filters[a0];[a0]$LIMITER[out]"

    private fun branch(index: Int, filters: String) = "[$index:a]$filters[a$index]"

    private fun chainGraph(vararg parts: String) = parts.joinToString(";")

    @Test
    fun singleClipWithNoEffectsUsesTheFixedHeadAndTail() {
        val plan = singleClipPlan(clip())

        assertEquals(
            "[0:a]atrim=start=0.000:end=2.000,asetpts=PTS-STARTPTS," +
                "aformat=sample_fmts=fltp:sample_rates=44100:channel_layouts=stereo," +
                "volume=1.000000,aresample=44100," +
                "aformat=sample_fmts=fltp:sample_rates=44100:channel_layouts=stereo," +
                "asetpts=PTS-STARTPTS[a0];" +
                "[a0]alimiter=limit=0.891:attack=5:release=50:level=false[out]",
            plan.filterComplex,
        )
        assertEquals("[out]", plan.outputLabel)
        assertEquals(2_000L, plan.expectedDurationMs)
        assertEquals("-ar 44100 -ac 2 -c:a pcm_s16le", plan.outputArgs)
    }

    @Test
    fun lightDenoiseAddsANoiseReductionFilter() {
        assertEquals(
            graph(chain(effects = listOf("afftdn=nr=6:nf=-30"))),
            singleClipPlan(clip(denoise = DenoiseMode.LIGHT)).filterComplex,
        )
    }

    @Test
    fun strongDenoiseUsesTheStrongerProfile() {
        assertEquals(
            graph(chain(effects = listOf("afftdn=nr=12:nf=-25"))),
            singleClipPlan(clip(denoise = DenoiseMode.STRONG)).filterComplex,
        )
    }

    @Test
    fun clarityAddsPresenceAndControl() {
        assertEquals(
            graph(
                chain(
                    effects = listOf(
                        "highpass=f=90",
                        "equalizer=f=2500:t=q:w=1.2:g=2",
                        "acompressor=threshold=0.125893:ratio=2",
                    ),
                ),
            ),
            singleClipPlan(clip(clarity = true)).filterComplex,
        )
    }

    @Test
    fun eqPresetsUseTheLockedGains() {
        val cases = listOf(
            EqPreset.VOICE to listOf(
                "equalizer=f=100:t=q:w=1:g=-2",
                "equalizer=f=1000:t=q:w=1:g=3",
                "equalizer=f=8000:t=q:w=1:g=1",
            ),
            EqPreset.BASS to listOf(
                "equalizer=f=100:t=q:w=1:g=4",
                "equalizer=f=8000:t=q:w=1:g=-1",
            ),
            EqPreset.TREBLE to listOf(
                "equalizer=f=100:t=q:w=1:g=-1",
                "equalizer=f=8000:t=q:w=1:g=4",
            ),
            EqPreset.WARM to listOf(
                "equalizer=f=100:t=q:w=1:g=2",
                "equalizer=f=1000:t=q:w=1:g=1",
                "equalizer=f=8000:t=q:w=1:g=-2",
            ),
        )

        cases.forEach { (preset, bands) ->
            assertEquals(
                preset.name,
                graph(chain(effects = bands)),
                singleClipPlan(clip(eqPreset = preset)).filterComplex,
            )
        }
    }

    @Test
    fun flatPresetOmitsTheEqBlock() {
        assertFalse(singleClipPlan(clip()).filterComplex.contains("equalizer="))
    }

    @Test
    fun flatPresetWithCustomGainsUsesThem() {
        assertEquals(
            graph(
                chain(
                    effects = listOf(
                        "equalizer=f=100:t=q:w=1:g=2.5",
                        "equalizer=f=1000:t=q:w=1:g=-1",
                        "equalizer=f=8000:t=q:w=1:g=0.5",
                    ),
                ),
            ),
            singleClipPlan(clip(eqLowDb = 2.5f, eqMidDb = -1f, eqHighDb = 0.5f)).filterComplex,
        )
    }

    @Test
    fun zeroCustomBandsAreOmitted() {
        assertEquals(
            graph(chain(effects = listOf("equalizer=f=100:t=q:w=1:g=3"))),
            singleClipPlan(clip(eqLowDb = 3f)).filterComplex,
        )
    }

    @Test
    fun aNonFlatPresetIgnoresCustomGains() {
        assertEquals(
            graph(
                chain(
                    effects = listOf(
                        "equalizer=f=100:t=q:w=1:g=-2",
                        "equalizer=f=1000:t=q:w=1:g=3",
                        "equalizer=f=8000:t=q:w=1:g=1",
                    ),
                ),
            ),
            singleClipPlan(
                clip(eqPreset = EqPreset.VOICE, eqLowDb = 9f, eqMidDb = -9f, eqHighDb = 9f),
            ).filterComplex,
        )
    }

    @Test
    fun customCompressorUsesLinearThresholdAndMakeup() {
        assertEquals(
            graph(
                chain(
                    effects = listOf("acompressor=threshold=0.063096:ratio=4:makeup=1.412538"),
                ),
            ),
            singleClipPlan(
                clip(compThresholdDb = -24f, compRatio = 4f, compMakeupDb = 3f),
            ).filterComplex,
        )
    }

    @Test
    fun oneNonDefaultCompressorFieldStillEmitsTheBlock() {
        assertEquals(
            graph(
                chain(
                    effects = listOf("acompressor=threshold=0.125893:ratio=2:makeup=1.000000"),
                ),
            ),
            singleClipPlan(clip(compRatio = 2f)).filterComplex,
        )
    }

    @Test
    fun defaultCompressorIsOmitted() {
        assertFalse(singleClipPlan(clip()).filterComplex.contains("acompressor"))
    }

    @Test
    fun gainIsEmittedAsALinearVolume() {
        assertEquals(
            graph(chain(volume = "1.995262")),
            singleClipPlan(clip(gainDb = 6f)).filterComplex,
        )
        assertEquals(
            graph(chain(volume = "0.501187")),
            singleClipPlan(clip(gainDb = -6f)).filterComplex,
        )
    }

    @Test
    fun speedHalfUsesAtempoHalf() {
        val plan = singleClipPlan(clip(sourceDurationMs = 2_000L, speed = 0.5f))

        assertEquals(graph(chain(timeline = listOf("atempo=0.5"))), plan.filterComplex)
        assertEquals(4_000L, plan.expectedDurationMs)
    }

    @Test
    fun speedDoubleUsesAtempoTwo() {
        val plan = singleClipPlan(clip(sourceDurationMs = 2_000L, speed = 2f))

        assertEquals(graph(chain(timeline = listOf("atempo=2"))), plan.filterComplex)
        assertEquals(1_000L, plan.expectedDurationMs)
    }

    @Test
    fun pitchUpTwelveRaisesTheRateAndSlowsBackDown() {
        val plan = singleClipPlan(clip(pitchSemitones = 12))

        assertEquals(
            graph(
                chain(
                    timeline = listOf("asetrate=88200", "aresample=44100", "atempo=0.5"),
                ),
            ),
            plan.filterComplex,
        )
        assertEquals(2_000L, plan.expectedDurationMs)
    }

    @Test
    fun pitchDownTwelveLowersTheRateAndSpeedsBackUp() {
        val plan = singleClipPlan(clip(pitchSemitones = -12))

        assertEquals(
            graph(
                chain(
                    timeline = listOf("asetrate=22050", "aresample=44100", "atempo=2"),
                ),
            ),
            plan.filterComplex,
        )
        assertEquals(2_000L, plan.expectedDurationMs)
    }

    @Test
    fun speedAndPitchChainInTheBriefOrder() {
        val plan = singleClipPlan(
            clip(sourceDurationMs = 3_000L, speed = 1.5f, pitchSemitones = 3),
        )

        assertEquals(
            graph(
                chain(
                    timeline = listOf(
                        "atempo=1.5",
                        "asetrate=52444",
                        "aresample=44100",
                        "atempo=0.840896",
                    ),
                    trimEnd = "3.000",
                ),
            ),
            plan.filterComplex,
        )
        assertEquals(2_000L, plan.expectedDurationMs)
    }

    @Test
    fun fadesSitOnTheRenderedTimeline() {
        val plan = singleClipPlan(
            clip(
                sourceDurationMs = 4_000L,
                speed = 2f,
                fadeInMs = 250L,
                fadeOutMs = 500L,
            ),
        )

        assertEquals(
            graph(
                chain(
                    timeline = listOf(
                        "atempo=2",
                        "afade=t=in:st=0:d=0.250",
                        "afade=t=out:st=1.500:d=0.500",
                    ),
                    trimEnd = "4.000",
                ),
            ),
            plan.filterComplex,
        )
        assertEquals(2_000L, plan.expectedDurationMs)
    }

    @Test
    fun allEffectsChainInTheBriefOrder() {
        val plan = singleClipPlan(
            clip(
                sourceDurationMs = 10_000L,
                trimStartMs = 1_000L,
                trimEndMs = 9_000L,
                fadeInMs = 250L,
                fadeOutMs = 400L,
                gainDb = 3f,
                speed = 1.25f,
                pitchSemitones = 2,
                denoise = DenoiseMode.STRONG,
                clarity = true,
                eqPreset = EqPreset.WARM,
                compThresholdDb = -12f,
                compRatio = 2.5f,
                compMakeupDb = 1.5f,
            ),
        )

        assertEquals(
            graph(
                chain(
                    effects = listOf(
                        "afftdn=nr=12:nf=-25",
                        "highpass=f=90",
                        "equalizer=f=2500:t=q:w=1.2:g=2",
                        "acompressor=threshold=0.125893:ratio=2",
                        "equalizer=f=100:t=q:w=1:g=2",
                        "equalizer=f=1000:t=q:w=1:g=1",
                        "equalizer=f=8000:t=q:w=1:g=-2",
                        "acompressor=threshold=0.251189:ratio=2.5:makeup=1.188502",
                    ),
                    volume = "1.412538",
                    timeline = listOf(
                        "atempo=1.25",
                        "asetrate=49501",
                        "aresample=44100",
                        "atempo=0.890899",
                        "afade=t=in:st=0:d=0.250",
                        "afade=t=out:st=6.000:d=0.400",
                    ),
                    trimStart = "1.000",
                    trimEnd = "9.000",
                ),
            ),
            plan.filterComplex,
        )
        assertEquals(6_400L, plan.expectedDurationMs)
        assertFalse(plan.filterComplex.contains("dB"))
    }

    @Test
    fun threeClipsWithoutCrossfadeUseASingleConcat() {
        val a = clip(id = "a")
        val b = clip(id = "b")
        val c = clip(id = "c")
        val plan = planOf(project(a, b, c))

        assertEquals(
            chainGraph(
                branch(0, chain()),
                branch(1, chain()),
                branch(2, chain()),
                "[a0][a1][a2]concat=n=3:v=0:a=1[mix]",
                "[mix]$LIMITER[out]",
            ),
            plan.filterComplex,
        )
        assertEquals(6_000L, plan.expectedDurationMs)
    }

    @Test
    fun twoClipCrossfadeChainsPairwise() {
        val a = clip(id = "a")
        val b = clip(id = "b", crossfadeMs = 500L)
        val plan = planOf(project(a, b))

        assertEquals(
            chainGraph(
                branch(0, chain()),
                branch(1, chain()),
                "[a0][a1]acrossfade=d=0.500:c1=tri:c2=tri[x1]",
                "[x1]$LIMITER[out]",
            ),
            plan.filterComplex,
        )
        assertEquals(3_500L, plan.expectedDurationMs)
    }

    @Test
    fun threeClipCrossfadeChainsEveryJoin() {
        val a = clip(id = "a")
        val b = clip(id = "b", crossfadeMs = 400L)
        val c = clip(id = "c", crossfadeMs = 600L)
        val plan = planOf(project(a, b, c))

        assertEquals(
            chainGraph(
                branch(0, chain()),
                branch(1, chain()),
                branch(2, chain()),
                "[a0][a1]acrossfade=d=0.400:c1=tri:c2=tri[x1]",
                "[x1][a2]acrossfade=d=0.600:c1=tri:c2=tri[x2]",
                "[x2]$LIMITER[out]",
            ),
            plan.filterComplex,
        )
        assertEquals(5_000L, plan.expectedDurationMs)
    }

    @Test
    fun aZeroCrossfadeInsideACrossfadeProjectConcatenatesThatJoin() {
        val a = clip(id = "a")
        val b = clip(id = "b")
        val c = clip(id = "c", crossfadeMs = 400L)
        val plan = planOf(project(a, b, c))

        assertEquals(
            chainGraph(
                branch(0, chain()),
                branch(1, chain()),
                branch(2, chain()),
                "[a0][a1]concat=n=2:v=0:a=1[x1]",
                "[x1][a2]acrossfade=d=0.400:c1=tri:c2=tri[x2]",
                "[x2]$LIMITER[out]",
            ),
            plan.filterComplex,
        )
        assertEquals(5_600L, plan.expectedDurationMs)
    }

    @Test
    fun aSelectedClipIgnoresItsCrossfade() {
        val a = clip(id = "a")
        val b = clip(id = "b", crossfadeMs = 500L)
        val plan = planOf(project(a, b), listOf(b))

        assertEquals(2_000L, plan.expectedDurationMs)
        assertFalse(plan.filterComplex.contains("acrossfade"))
    }

    @Test
    fun selectedClipsKeepTheGivenPlaybackOrder() {
        val first = clip(id = "first", trimStartMs = 0L, trimEndMs = 1_000L)
        val second = clip(id = "second", trimStartMs = 1_000L, trimEndMs = 2_000L)
        val plan = planOf(project(first, second), listOf(second, first))

        assertEquals(
            chainGraph(
                branch(0, chain(trimStart = "1.000", trimEnd = "2.000")),
                branch(1, chain(trimStart = "0.000", trimEnd = "1.000")),
                "[a0][a1]concat=n=2:v=0:a=1[mix]",
                "[mix]$LIMITER[out]",
            ),
            plan.filterComplex,
        )
        assertEquals(listOf(second, first), plan.inputClips)
        assertEquals(2_000L, plan.expectedDurationMs)
    }

    @Test
    fun buildSanitizesTheProjectBeforeUsingItsClips() {
        val raw = clip(
            sourceDurationMs = 4_000L,
            fadeInMs = 5_000L,
            fadeOutMs = 5_000L,
            gainDb = 100f,
            speed = 9f,
            pitchSemitones = 50,
            eqLowDb = 50f,
        )
        val plan = planOf(project(raw), listOf(raw))

        assertEquals(
            graph(
                chain(
                    effects = listOf("equalizer=f=100:t=q:w=1:g=12"),
                    volume = "15.848932",
                    timeline = listOf(
                        "atempo=2",
                        "asetrate=88200",
                        "aresample=44100",
                        "atempo=0.5",
                        "afade=t=in:st=0:d=2.000",
                    ),
                    trimStart = "0.000",
                    trimEnd = "4.000",
                ),
            ),
            plan.filterComplex,
        )
        assertEquals(2_000L, plan.expectedDurationMs)
    }

    @Test
    fun clipsDroppedBySanitizationAreDroppedFromTheGraph() {
        val good = clip(id = "good", sourceHash = "good", trimStartMs = 500L, trimEndMs = 1_500L)
        val blank = clip(id = "blank", sourceHash = " ")
        val plan = planOf(project(good, blank), listOf(blank, good))

        assertEquals(listOf(good), plan.inputClips)
        assertEquals(graph(chain(trimStart = "0.500", trimEnd = "1.500")), plan.filterComplex)
        assertFalse(plan.filterComplex.contains("start=0.000:end=2.000"))
        assertEquals(1_000L, plan.expectedDurationMs)
    }

    @Test
    fun anEmptySelectionIsRejected() {
        assertThrows(IllegalArgumentException::class.java) {
            FilterGraph.build(EditorProject(name = "Empty"), emptyList(), EditorFormat.WAV)
        }
    }

    @Test
    fun expectedDurationSubtractsEveryLaterCrossfade() {
        val a = clip(id = "a")
        val b = clip(id = "b", sourceDurationMs = 3_000L, crossfadeMs = 500L)
        val c = clip(id = "c", sourceDurationMs = 4_000L, crossfadeMs = 700L)
        val plan = planOf(project(a, b, c))

        assertEquals(7_800L, plan.expectedDurationMs)
    }

    @Test
    fun aCrossfadeIsRecappedAgainstTheSelectedNeighbours() {
        val short = clip(id = "short", sourceDurationMs = 1_000L)
        val middle = clip(id = "middle", sourceDurationMs = 4_000L)
        val tail = clip(id = "tail", sourceDurationMs = 2_000L, crossfadeMs = 1_000L)
        val plan = planOf(project(short, middle, tail), listOf(short, tail))

        assertEquals(2_500L, plan.expectedDurationMs)
        assertTrue(plan.filterComplex.contains("acrossfade=d=0.500:c1=tri:c2=tri"))
    }

    @Test
    fun outputArgsMatchEachFormat() {
        val clip = clip()
        val project = project(clip)

        assertEquals(
            "-ar 44100 -ac 2 -c:a pcm_s16le",
            FilterGraph.build(project, listOf(clip), EditorFormat.WAV).outputArgs,
        )
        assertEquals(
            "-ar 44100 -ac 2 -c:a flac",
            FilterGraph.build(project, listOf(clip), EditorFormat.FLAC).outputArgs,
        )
        assertEquals(
            "-ar 44100 -ac 2 -c:a libmp3lame -b:a 192k",
            FilterGraph.build(project, listOf(clip), EditorFormat.MP3).outputArgs,
        )
        assertEquals(
            "-ar 44100 -ac 2 -c:a aac -b:a 192k",
            FilterGraph.build(project, listOf(clip), EditorFormat.M4A).outputArgs,
        )
    }

    @Test
    fun everyAtempoFactorStaysWithinTheSupportedRange() {
        listOf(0.5f, 0.75f, 1.5f, 2f).forEach { speed ->
            (-12..12).forEach { pitch ->
                val clip = clip(speed = speed, pitchSemitones = pitch)
                val graph = planOf(project(clip), listOf(clip)).filterComplex
                val factors = Regex("atempo=([0-9.]+)")
                    .findAll(graph)
                    .map { it.groupValues[1].toDouble() }
                    .toList()

                assertTrue("speed=$speed pitch=$pitch had no atempo", factors.isNotEmpty())
                factors.forEach { factor ->
                    assertTrue(
                        "speed=$speed pitch=$pitch emitted atempo=$factor",
                        factor in 0.5..2.0,
                    )
                }
                Regex("asetrate=([0-9]+)").findAll(graph).forEach {
                    assertTrue(it.groupValues[1].toInt() > 0)
                }
            }
        }
    }

    private companion object {
        const val AFORMAT =
            "aformat=sample_fmts=fltp:sample_rates=44100:channel_layouts=stereo"
        const val LIMITER = "alimiter=limit=0.891:attack=5:release=50:level=false"
    }
}
