package dev.otto.phone.access

/** Whether a screen moved, as pure functions over snapshots. */
object ScreenDiff {
    /** What a scroll changes: the labels, ids and positions of what lies in `region` (or on screen). Two
     *  walks of an unmoved screen agree, whatever their ids and times. */
    fun signature(snapshot: Snapshot, region: UiNode?): Int =
        snapshot.nodes.filter { n -> region == null || (n.top < region.bottom && n.bottom > region.top && n.left < region.right && n.right > region.left) }
            .joinToString("\n") { "${it.label}|${it.viewId}@${it.left},${it.top}" }.hashCode()
}
