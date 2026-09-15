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
 *  4. a pay word on the target ("Pay now", and the bare "Pay", "Buy"): never
 *     tapped; a forward word ("Continue", "Next") next to a checkout signal
 *     (a total, "payment"): the same; a commit word ("Send", "Checkout"):
 *     only through phone_commit. The element's resource id is judged as words too, and the stricter
 *     verdict wins: a web "Submit" whose id is buy-now-button is a pay word
 *  4b. a tap by coordinates that lands on no element: refused on a screen
 *     that has clickable elements, because what is drawn there is unknown
 *  4c. enter is the keyboard's submit, judged like a tap; back/home/recents
 *     are the way out
 *  5. the person's own switch, and the hand-over state that only the
 *     person's Resume tap clears
 */
class PolicyGuard(val rules: GuardRules) {
    enum class Target { NONE, PAY, COMMIT }

    @Volatile var allowedToAct: Boolean = true
    @Volatile var handedOver: Boolean = false
    val log: MutableList<String> = java.util.Collections.synchronizedList(mutableListOf())

    private fun note(why: String): String { log.add(why); if (log.size > 200) log.removeAt(0); return why }

    /** NFKC-folded, invisible characters removed, lower-cased, one space between words -- the
     *  same normalisation as otto's guard.normal(), so the two sides read one screen the same way. */
    fun normal(text: String): String {
        val folded = java.text.Normalizer.normalize(text, java.text.Normalizer.Form.NFKC)
        val lowered = folded.replace(INVISIBLE, "").lowercase()
        val latin = buildString(lowered.length) { for (ch in lowered) append(CONFUSABLES[ch] ?: ch) }
        return latin.trim().split(WHITESPACE).filter { it.isNotEmpty() }.joinToString(" ")
    }

    /** An element's resource id as words: the package prefix dropped, camelCase and -_./: split, then
     *  [normal] -- the same as otto's guard.id_words(), so "buyNowButton" is "buy now button". */
    fun idWords(viewId: String): String =
        normal(viewId.substringAfter(":id/").replace(CAMEL, "$1 $2").replace(ID_SEPARATORS, " "))

    fun packageVerdict(packageName: String, label: String = ""): String {
        val pkg = normal(packageName)
        if (pkg in rules.deniedPackages) return "$packageName is a payment or banking app"
        val shown = normal(label)
        if (shown.isNotEmpty() && rules.deniedNames.any { it.matcher(shown).find() }) return "$label is a payment or banking app"
        var text = "$pkg $shown"
        for (exc in rules.packageWordExceptions) text = text.replace(exc, " ")
        for (word in rules.packageWords) if (word in text) return "${label.ifBlank { packageName }} looks money-related ('$word')"
        return ""
    }

    fun sensitiveMatches(texts: Iterable<String>): List<String> =
        texts.filter { t -> rules.sensitivePatterns.any { it.matcher(normal(t)).find() } }.map { it.take(60) }

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

    /** A word's whole-word pattern, compiled once: the words come from the rules, so this stays small. */
    private val wholeWords = java.util.concurrent.ConcurrentHashMap<String, Regex>()

    private fun whole(word: String, text: String) =
        wholeWords.getOrPut(word) { Regex("(^|\\W)" + Regex.escape(word) + "($|\\W)") }.containsMatchIn(text)

    /** The first screen string that says this is a checkout (a total, "payment", a card field), or "". */
    fun checkoutContext(texts: Iterable<String>): String =
        texts.firstOrNull { t -> normal(t).let { n -> n.isNotEmpty() && rules.checkoutSignals.any { it.matcher(n).find() } } }?.take(60) ?: ""

    /** `texts` are the other strings on the screen: a forward word is a pay word only next to a checkout signal. */
    fun targetVerdict(label: String, texts: Iterable<String> = emptyList(), viewId: String = ""): Target {
        val screen = texts.toList()
        val verdicts = listOf(wordsVerdict(label, screen), wordsVerdict(idWords(viewId), screen))
        return when {
            Target.PAY in verdicts -> Target.PAY
            Target.COMMIT in verdicts -> Target.COMMIT
            else -> Target.NONE
        }
    }

    private fun wordsVerdict(label: String, texts: List<String>): Target {
        val text = normal(label)
        if (text.isEmpty()) return Target.NONE
        // A phrase matches as a substring, and with every space removed; a single word matches whole
        // ("Pay", "Pay ₹499", not "Payload"). A pay word may over-match; it only ever refuses.
        val squashed = text.replace(" ", "")
        fun hit(word: String) = if (" " in word) word in text || word.replace(" ", "") in squashed else whole(word, text)
        if (rules.payWords.any(::hit)) return Target.PAY
        if (rules.forwardWords.any(::hit) && checkoutContext(texts).isNotEmpty()) return Target.PAY
        if (rules.commitWords.any { whole(it, text) }) return Target.COMMIT
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

    fun requireTappable(node: UiNode, commit: Boolean, texts: Iterable<String> = emptyList()) {
        val shown = if (node.viewId.isEmpty()) node.label else "${node.label} #${node.viewId.take(60)}".trim()
        when (targetVerdict(node.label, texts, node.viewId)) {
            Target.PAY -> { handedOver = true; throw guard(note("'$shown' is a payment step -- the person does that"), handover = true) }
            Target.COMMIT -> if (!commit) throw guard(note("'$shown' cannot be taken back; only phone_commit may tap it"), handover = false)
            Target.NONE -> Unit
        }
    }

    /** A tap by coordinates that lands on no element: refused when the screen has clickable elements,
     *  because what is drawn at that point is unknown to the guard (a checkout drawn on a canvas inside
     *  an ordinary page is exactly the case). On a screen with none (a game) the only content-level
     *  check is a look, so the tap needs a screenshot taken on this very capture (`lookedId`); every
     *  action installs a new capture, which is what expires the look. */
    fun requireBlindTap(snapshot: Snapshot, lookedId: String?) {
        if (snapshot.nodes.any { it.clickable }) {
            throw guard(note("nothing in the tree is under that point; tap an element by its text"), handover = false)
        }
        if (lookedId == null || lookedId != snapshot.snapshotId) {
            throw guard(note("nothing in the tree is under that point; phone_look at this screen first, then tap"), handover = false)
        }
    }

    /** Enter has no label to judge, so the screen is judged instead: a checkout signal, or any pay
     *  button on it, is what Enter would submit. */
    fun requireSubmit(snapshot: Snapshot) {
        val texts = snapshot.nodes.map { it.label }
        val seen = checkoutContext(texts)
        if (seen.isNotEmpty()) { handedOver = true; throw guard(note("this screen is a checkout ('$seen'); Enter would submit it -- the person does that"), handover = true) }
        texts.firstOrNull { targetVerdict(it) == Target.PAY }?.let {
            handedOver = true; throw guard(note("this screen has a payment step ('${it.take(60)}'); Enter would submit it -- the person does that"), handover = true)
        }
        snapshot.nodes.firstOrNull { it.viewId.isNotEmpty() && targetVerdict("", viewId = it.viewId) == Target.PAY }?.let {
            handedOver = true; throw guard(note("this screen has a payment step (#${it.viewId.take(60)}); Enter would submit it -- the person does that"), handover = true)
        }
    }

    fun requireTypeable(node: UiNode?) {
        if (node != null && node.password) throw guard(note("that is a password field -- the person types there"), handover = true)
    }

    /** Whether a judgement made on `snapshot` must be made again on a fresh walk before acting: it was
     *  read while the screen was still changing, or the screen has said it changed since. With no
     *  snapshot the caller reads the screen afresh anyway. */
    fun needsRecheck(snapshot: Snapshot?, lastEventAt: Long): Boolean =
        snapshot != null && (!snapshot.settled || lastEventAt > snapshot.takenAt)

    /** Element `index` of `old`, judged again on `fresh` before it is tapped. It must still be there --
     *  the same label and id, overlapping where it was -- or the tap is stale; the fresh screen is judged
     *  whole, and the element with the strings of both walks, so a total that appeared since makes
     *  "Continue" a payment step. Whatever is drawn over its centre now is what a fallback tap lands
     *  on, and is judged the same. Returns the element as it is now. */
    fun requireStillTappable(old: Snapshot, fresh: Snapshot, index: Int, commit: Boolean): UiNode {
        val was = old.node(index) ?: throw stale("no element [$index] on this screen")
        val now = fresh.nodes.filter { it.label == was.label && it.viewId == was.viewId && overlap(it, was) > 0 }
            .maxByOrNull { overlap(it, was) }
            ?: throw stale("[$index] '${was.label.take(60)}' is not where it was any more; read the screen again")
        requireActionable(fresh)
        val texts = old.nodes.map { it.label } + fresh.nodes.map { it.label }
        requireTappable(now, commit, texts)
        nodeAt(fresh, now.centreX, now.centreY)?.let { requireTappable(it, commit, texts) }
        return now
    }

    /** A tap by coordinates, judged again on `fresh`: the same element must still be under the point, or
     *  still nothing on a screen that still has nothing to tap by text. */
    fun requireStillAtPoint(old: Snapshot, fresh: Snapshot, x: Int, y: Int): UiNode? {
        val was = nodeAt(old, x, y)
        val now = nodeAt(fresh, x, y)
        if ((was == null) != (now == null) || (was != null && now != null && (was.label != now.label || was.viewId != now.viewId))) {
            throw stale("what is under $x,$y changed; read the screen again")
        }
        requireActionable(fresh)
        if (now == null) {
            if (fresh.nodes.any { it.clickable }) throw guard(note("nothing in the tree is under that point; tap an element by its text"), handover = false)
            return null
        }
        requireTappable(now, commit = false, texts = old.nodes.map { it.label } + fresh.nodes.map { it.label })
        requireTypeable(now)
        return now
    }

    /** A swipe from a point, judged again on `fresh`: whatever is under the point now is what it drags. */
    fun requireStillSwipeable(old: Snapshot, fresh: Snapshot, x: Int, y: Int) {
        requireActionable(fresh)
        nodeAt(fresh, x, y)?.let { requireTappable(it, commit = false, texts = old.nodes.map { n -> n.label } + fresh.nodes.map { n -> n.label }) }
    }

    private fun overlap(a: UiNode, b: UiNode): Long =
        maxOf(0, minOf(a.right, b.right) - maxOf(a.left, b.left)).toLong() * maxOf(0, minOf(a.bottom, b.bottom) - maxOf(a.top, b.top))

    private fun stale(message: String) = dev.otto.phone.bridge.DeviceException(message, "stale")

    /** The smallest element under a point, for a tap by coordinates. */
    fun nodeAt(snapshot: Snapshot, x: Int, y: Int): UiNode? =
        snapshot.nodes.filter { x in it.left..it.right && y in it.top..it.bottom }
            .minByOrNull { (it.right - it.left).coerceAtLeast(1) * (it.bottom - it.top).coerceAtLeast(1) }

    private fun guard(message: String, handover: Boolean) =
        dev.otto.phone.bridge.DeviceException(message, "guard", handover)

    companion object {
        val CAMEL = Regex("([a-z0-9])([A-Z])")
        val WHITESPACE = Regex("\\s+")
        val ID_SEPARATORS = Regex("[-_./:#]+")
        val INVISIBLE = Regex("[\\u200B-\\u200F\\u2060-\\u2064\\u00AD\\uFEFF\\u202A-\\u202E\\u2066-\\u2069]")

        /** Cyrillic and Greek letters that draw the same as a Latin one, folded after lower-casing --
         *  the same table as otto's guard._CONFUSABLES, so "Pаy now" with a Cyrillic а is "pay now". */
        val CONFUSABLES: Map<Char, Char> = mapOf(
            'а' to 'a', 'е' to 'e', 'о' to 'o', 'р' to 'p', 'с' to 'c', 'у' to 'y', 'х' to 'x', 'і' to 'i',
            'ј' to 'j', 'ѕ' to 's', 'һ' to 'h', 'ԁ' to 'd', 'ԛ' to 'q', 'ԝ' to 'w', 'ѵ' to 'v', 'ԍ' to 'g',
            'ӏ' to 'l', 'к' to 'k', 'т' to 't', 'м' to 'm', 'в' to 'b', 'н' to 'h', 'ь' to 'b', 'ѡ' to 'w',
            'α' to 'a', 'ο' to 'o', 'ρ' to 'p', 'ν' to 'v', 'ι' to 'i', 'κ' to 'k', 'υ' to 'u', 'τ' to 't',
            'ε' to 'e', 'β' to 'b', 'χ' to 'x', 'γ' to 'y', 'ς' to 's',
        )
    }
}
