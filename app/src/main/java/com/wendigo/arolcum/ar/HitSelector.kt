package com.wendigo.arolcum.ar

internal enum class HitKind {
    PLANE,
    DEPTH,
    POINT
}

internal data class HitCandidate(
    val index: Int,
    val kind: HitKind,
    val distance: Float,
    val planeId: Int = 0
)

internal object HitSelector {
    private const val PLANE_TIE_M = 0.08f
    private const val DEPTH_TIE_M = 0.10f

    fun choose(candidates: List<HitCandidate>): HitCandidate? {
        if (candidates.isEmpty()) return null
        val sorted = candidates.sortedBy { it.distance }
        val nearest = sorted.first()

        if (nearest.kind == HitKind.POINT) {
            val depth = sorted.firstOrNull { it.kind == HitKind.DEPTH }
            if (depth != null && depth.distance <= nearest.distance + DEPTH_TIE_M) {
                return depth
            }
        }

        if (nearest.kind == HitKind.DEPTH) {
            val plane = sorted.firstOrNull { it.kind == HitKind.PLANE }
            if (plane != null && plane.distance <= nearest.distance + PLANE_TIE_M) {
                return plane
            }
        }

        return nearest
    }
}
