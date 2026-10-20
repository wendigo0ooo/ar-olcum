package com.wendigo.arolcum

import com.wendigo.arolcum.ar.HitCandidate
import com.wendigo.arolcum.ar.HitKind
import com.wendigo.arolcum.ar.HitSelector
import org.junit.Assert.assertEquals
import org.junit.Test

class HitSelectorTest {
    @Test
    fun nearestDepthWinsOverBackgroundPlane() {
        val candidates = listOf(
            candidate(0, HitKind.DEPTH, 0.25f),
            candidate(1, HitKind.PLANE, 3.5f, planeId = 7)
        )

        assertEquals(0, HitSelector.choose(candidates)?.index ?: -1)
    }

    @Test
    fun nearPlaneWinsOverDepthTie() {
        val candidates = listOf(
            candidate(0, HitKind.DEPTH, 1.02f),
            candidate(1, HitKind.PLANE, 1.0f, planeId = 7)
        )

        assertEquals(1, HitSelector.choose(candidates)?.index ?: -1)
    }

    @Test
    fun depthWinsWhenPlaneIsClearlyBehind() {
        val candidates = listOf(
            candidate(0, HitKind.PLANE, 2.0f, planeId = 7),
            candidate(1, HitKind.DEPTH, 1.2f)
        )

        assertEquals(1, HitSelector.choose(candidates)?.index ?: -1)
    }

    @Test
    fun depthWinsOverVeryClosePoint() {
        val candidates = listOf(
            candidate(0, HitKind.POINT, 0.3f),
            candidate(1, HitKind.DEPTH, 0.36f)
        )

        assertEquals(1, HitSelector.choose(candidates)?.index ?: -1)
    }

    @Test
    fun pointWinsWhenDepthIsFarBehind() {
        val candidates = listOf(
            candidate(0, HitKind.POINT, 0.3f),
            candidate(1, HitKind.DEPTH, 1.2f)
        )

        assertEquals(0, HitSelector.choose(candidates)?.index ?: -1)
    }

    @Test
    fun nearestPlaneWinsWithoutCloserDepth() {
        val candidates = listOf(
            candidate(0, HitKind.PLANE, 1.0f, planeId = 7),
            candidate(1, HitKind.DEPTH, 1.4f)
        )

        assertEquals(0, HitSelector.choose(candidates)?.index ?: -1)
    }

    @Test
    fun emptyCandidatesReturnNull() {
        assertEquals(null, HitSelector.choose(emptyList()))
    }

    private fun candidate(
        index: Int,
        kind: HitKind,
        distance: Float,
        planeId: Int = 0
    ): HitCandidate {
        return HitCandidate(index = index, kind = kind, distance = distance, planeId = planeId)
    }
}
