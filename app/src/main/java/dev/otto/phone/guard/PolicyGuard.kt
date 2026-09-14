package dev.otto.phone.guard

import dev.otto.phone.access.Snapshot
import dev.otto.phone.access.UiNode

/**
 * The money guard, enforced on the phone. The verdicts mirror otto's
 * agent/phone/guard.py line for line, because the two must agree on what a
 * payment screen is; the difference is that this one runs before every
 * gesture and cannot be argued with.
 *
 *  1. a denied or money-worded package in front: only back/home are allowed
 *  2. a sensitive pattern with a second signal (an input field asking for
 *     it, a money-worded package, a secure window): hand over
 *  3. a password field: never typed into
 *  4. a pay word on the target: never tapped; a commit word: only through
 *     phone_commit
 *  5. the person's own switch, and the hand-over state that only the
 *     person's Resume tap clears
 */
class PolicyGuard(val rules: GuardRules) {
    enum class Target { NONE, PAY, COMMIT }

    @Volatile var allowedToAct: Boolean = true
    @Volatile var handedOver: Boolean = false
    val log: MutableList<String> = java.util.Collections.synchronizedList(mutableListOf())

    private fun note(why: String): String { log.add(why); if (log.size > 200) log.removeAt(0); return why }

    fun packageVerdict(packageName: String, label: String = ""): String {
        val pkg = packageName.trim().lowercase()
        if (pkg in rules.deniedPackages) return "$packageName is a payment or banking app"
        var text = "$pkg ${label.lowercase()}"
        for (exc in rules.packageWordExceptions) text = text.replace(exc, " ")
        for (word in rules.packageWords) if (word in text) return "${label.ifBlank { packageName }} looks money-related ('$word')"
        return ""
    }

    fun sensitiveMatches(texts: Iterable<String>): List<String> =
        texts.filter { t -> rules.sensitivePatterns.any { it.matcher(t).find() } }.map { it.take(60) }

    fun screenVerdict(snapshot: Snapshot): String {
        val pkgWhy = packageVerdict(snapshot.packageName, snapshot.label)
        if (pkgWhy.isNotEmpty()) return pkgWhy
        val texts = snapshot.nodes.map { it.label }
        val matches = sensitiveMatches(texts)
        if (matches.isEmpty()) return ""
        val asks = snapshot.nodes.any { it.password } ||
            snapshot.nodes.any { it.editable && sensitiveMatches(listOf(it.label)).isNotEmpty() }
        val reasons = mutableListOf<String>()
        if (asks) reasons += "an input field"
        if (snapshot.secure) reasons += "a secure window"
        if (reasons.isEmpty()) return ""
        return "this looks like a payment or sign-in screen ('${matches.first()}' with ${reasons.joinToString(", ")}) -- the person takes over here"
    }

    fun targetVerdict(label: String): Target {
        val text = label.lowercase().split(Regex("\\s+")).joinToString(" ").trim()
        if (text.isEmpty()) return Target.NONE
        if (rules.payWords.any { it in text }) return Target.PAY
        if (rules.commitWords.any { Regex("(^|\\W)" + Regex.escape(it) + "($|\\W)").containsMatchIn(text) }) return Target.COMMIT
        return Target.NONE
    }

    /** Throws when nothing but back/home may happen on this screen. */
    fun requireActionable(snapshot: Snapshot?) {
        if (!allowedToAct) throw guard(note("acting is switched off in Otto's settings"), handover = false)
        if (handedOver) throw guard(note("the person has taken over; tap Resume in Otto to continue"), handover = true)
        if (snapshot == null) return
        val why = screenVerdict(snapshot)
        if (why.isNotEmpty()) { handedOver = true; throw guard(note(why), handover = true) }
    }

    fun requireTappable(node: UiNode, commit: Boolean) {
        when (targetVerdict(node.label)) {
            Target.PAY -> { handedOver = true; throw guard(note("'${node.label}' is a payment step -- the person does that"), handover = true) }
            Target.COMMIT -> if (!commit) throw guard(note("'${node.label}' cannot be taken back; only phone_commit may tap it"), handover = false)
            Target.NONE -> Unit
        }
    }

    fun requireTypeable(node: UiNode?) {
        if (node != null && node.password) throw guard(note("that is a password field -- the person types there"), handover = true)
    }

    private fun guard(message: String, handover: Boolean) =
        dev.otto.phone.bridge.DeviceException(message, "guard", handover)
}
