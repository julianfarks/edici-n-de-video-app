package com.vanta.studio

import com.vanta.studio.TestData.SEC
import com.vanta.studio.domain.anim.Easings
import com.vanta.studio.domain.anim.Keyframes
import com.vanta.studio.domain.anim.Pose
import com.vanta.studio.domain.model.Easing
import com.vanta.studio.domain.model.Keyframe
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AnimationTest {
    private val base = Pose(0f, 0f, 1f, 0f, 1f)

    private fun key(t: Long, x: Float, easing: Easing = Easing.LINEAR) = Keyframe(t, x, 0f, 1f, 0f, 1f, easing)

    @Test
    fun noKeyframesReturnsBase() {
        assertEquals(base, Keyframes.evaluate(emptyList(), SEC, base))
    }

    @Test
    fun linearInterpolationHitsMidpoint() {
        val k = listOf(key(0, 0f), key(2 * SEC, 1f))
        assertEquals(0.5f, Keyframes.evaluate(k, SEC, base).x, 1e-4f)
    }

    @Test
    fun valuesHoldOutsideRange() {
        val k = listOf(key(SEC, 0.2f), key(2 * SEC, 0.8f))
        assertEquals(0.2f, Keyframes.evaluate(k, 0, base).x, 1e-6f)
        assertEquals(0.8f, Keyframes.evaluate(k, 9 * SEC, base).x, 1e-6f)
    }

    @Test
    fun holdEasingKeepsStartValueUntilNextKey() {
        val k = listOf(key(0, 0f, Easing.HOLD), key(SEC, 1f))
        assertEquals(0f, Keyframes.evaluate(k, SEC / 2, base).x, 1e-6f)
    }

    @Test
    fun easingsAreMonotonicAndBounded() {
        for (e in Easing.entries) {
            assertEquals(0f, Easings.apply(e, 0f), 1e-6f)
            if (e != Easing.HOLD) assertEquals(1f, Easings.apply(e, 1f), 1e-6f)
            var prev = -1f
            for (i in 0..20) {
                val v = Easings.apply(e, i / 20f)
                assertTrue("$e not monotonic", v >= prev - 1e-6f)
                assertTrue(v in -1e-6f..1.0001f)
                prev = v
            }
        }
    }

    @Test
    fun upsertReplacesNearbyKeyAndKeepsOrder() {
        var k = listOf(key(0, 0f), key(2 * SEC, 1f))
        k = Keyframes.upsert(k, key(SEC, 0.5f), 10_000)
        assertEquals(listOf(0L, SEC, 2 * SEC), k.map { it.timeUs })
        k = Keyframes.upsert(k, key(SEC + 5_000, 0.9f), 10_000)
        assertEquals(3, k.size)
        assertEquals(0.9f, k[1].x, 1e-6f)
        assertEquals(SEC, k[1].timeUs)
    }

    @Test
    fun removeDeletesNearestWithinTolerance() {
        val k = listOf(key(0, 0f), key(SEC, 1f))
        assertEquals(1, Keyframes.remove(k, SEC + 100, 1000).size)
        assertEquals(2, Keyframes.remove(k, 5 * SEC, 1000).size)
    }

    @Test
    fun splitKeepsTheAnimationContinuousAtTheCut() {
        val k = listOf(key(0, 0f), key(4 * SEC, 1f))
        val (l, r) = Keyframes.split(k, SEC, base)
        assertEquals(0.25f, l.last().x, 1e-3f)
        assertEquals(0L, r.first().timeUs)
        assertEquals(0.25f, r.first().x, 1e-3f)
        assertEquals(3 * SEC, r.last().timeUs)
    }
}
