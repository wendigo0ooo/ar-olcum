package com.wendigo.arolcum.core

import kotlin.math.abs

object Measurement {
    fun distance(a: Vec3, b: Vec3): Double {
        if (!a.isFinite() || !b.isFinite()) return 0.0
        return a.distanceTo(b)
    }

    fun chainLength(points: List<Vec3>): Double {
        if (points.size < 2 || points.any { !it.isFinite() }) return 0.0
        var total = 0.0
        for (index in 1 until points.size) {
            total += points[index - 1].distanceTo(points[index])
        }
        return total
    }

    fun polygonArea(points: List<Vec3>): Double {
        if (points.size < 3 || points.any { !it.isFinite() }) return 0.0
        var twiceArea = 0.0
        for (index in points.indices) {
            val current = points[index]
            val next = points[(index + 1) % points.size]
            twiceArea += current.x * next.z - next.x * current.z
        }
        return abs(twiceArea) / 2.0
    }

    fun height(base: Vec3, top: Vec3): Double {
        if (!base.isFinite() || !top.isFinite()) return 0.0
        return abs(top.y - base.y)
    }
}
