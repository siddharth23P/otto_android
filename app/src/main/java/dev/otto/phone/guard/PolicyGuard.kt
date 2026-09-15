package dev.otto.phone.guard

import dev.otto.phone.access.Snapshot
import dev.otto.phone.access.UiNode
import dev.otto.phone.bridge.DeviceException

/**
 * The money guard, enforced on the phone. It judges exactly as otto's agent/phone/guard.py does --
 * function for function, held to the same numbers on every page of the shared corpus
 * (src/test/resources/guard_pages, PageCorpusTest) -- and runs before every gesture, where nothing
 * the model says can argue with it.
 *
 * A PAGE IS JUDGED WHOLE ([classify]): secure (a payment app, a payment window by its class, a window
 * that refused a screenshot, a field asking for a secret), payment (payment methods, a final pay
 * button, saved cards, a payment title, a total under a wide button -- and against it, a grid of
 * priced products or a product page), checkout (an address step), cart, or none. On a secure or
 * payment page every tap, keystroke, Enter and swipe from a point hands the phone to the person.
 *
 * A CONTROL'S OWN SHORT LABEL AND ID ([controlVerdict]) still decide, on any page: a pay control (Pay,
 * Buy Now, Place order) is never tapped -- off a payment page it is refused without handing over, so
 * the run goes on; a checkout entry, a commit, and on a checkout step a forward button, only through
 * phone_commit. A password field is never typed into. The person's own switch and the hand-over,
 * which only their Resume clears, stand above all of it.
 */
class PolicyGuard(val rules: GuardRules) {
    enum class PageKind(val wire: String) {
        NONE("none"), CART("cart"), CHECKOUT("checkout"), PAYMENT("payment"), SECURE("secure");

        fun atLeast(other: PageKind): PageKind = if (ordinal >= other.ordinal) this else other

        companion object {
            fun of(wire: String?): PageKind? = values().firstOrNull { it.wire == wire }
        }
    }

    enum class Control(val wire: String) { NONE(""), PAY("pay"), ENTRY("entry"), COMMIT("commit"), FORWARD("forward") }

    data class Box(val left: Int, val top: Int, val right: Int, val bottom: Int) {
        fun inside(outer: Box) = outer.left <= left && outer.top <= top && right <= outer.right && bottom <= outer.bottom
        val area: Long get() = (right - left).toLong() * (bottom - top)
    }

    /** Something a tap could act on: a clickable element, a text inside a clickable container with no
     *  words of its own, or the id of an element scrolled out of view ([box] null). */
    data class Target(val label: String, val viewId: String, val role: String, val box: Box?)

    /** Which window a page was judged on, and what it was judged. */
    data class Memory(val packageName: String, val activity: String, val seq: Long, val kind: PageKind)

    data class PageVerdict(
        val kind: PageKind, val reason: String, val payment: Int, val checkout: Int, val cart: Int,
        val features: Set<String>, val memory: Memory?,
    )

    @Volatile var allowedToAct: Boolean = true
    @Volatile var handedOver: Boolean = false
    @Volatile private var memory: Memory? = null
    val log: MutableList<String> = java.util.Collections.synchronizedList(mutableListOf())

    private fun note(why: String): String { log.add(why); if (log.size > 200) log.removeAt(0); return why }

    fun normal(text: String): String = Words.normal(text)
    fun idWords(viewId: String): String = Words.idWords(viewId)

    // -- apps -------------------------------------------------------------------------------------

    fun packageVerdict(packageName: String, label: String = ""): String {
        if (normal(packageName) in rules.deniedPackages) return "$packageName is a payment or banking app"
        val shown = normal(label)
        if (shown.isNotEmpty() && rules.deniedNames.any { it.containsMatchIn(shown) }) return "$label is a payment or banking app"
        var source = "$packageName $label"
        for (exc in rules.packageWordExceptions) source = Regex(Regex.escape(exc), RegexOption.IGNORE_CASE).replace(source, " ")
        for (token in Words.tokens(source)) {
            if (token in rules.wholeWords) return "${label.ifEmpty { packageName }} looks money-related ('$token')"
            for (word in rules.affixWords) {
                if (token == word || token.startsWith(word) || token.endsWith(word)) return "${label.ifEmpty { packageName }} looks money-related ('$word')"
            }
        }
        return ""
    }

    // -- secrets ----------------------------------------------------------------------------------

    /** Which strings name a secret outright: what a screenshot must not carry. */
    fun sensitiveText(texts: Iterable<String>): List<String> =
        texts.filter { t -> normal(t).let { n -> n.isNotEmpty() && rules.screenPatterns.any { it.containsMatchIn(n) } } }.map { it.take(60) }

    /** A node's text as the other side reads it: its text, else its description, and nothing of a password field. */
    private fun textOf(n: UiNode): String = if (n.password) "" else n.text.ifEmpty { n.desc }

    /** What `nodes[index]` asks for when it is a field asking for a secret, or "". */
    fun sensitiveField(nodes: List<UiNode>, index: Int): String {
        val node = nodes[index]
        if (!node.editable) return ""
        if (node.password || node.inputKind == "pw" || node.inputKind == "numpw") return "a password field"
        for (own in listOf(textOf(node), node.hint)) {
            val s = normal(own)
            if (s.isNotEmpty() && rules.fieldPatterns.any { it.containsMatchIn(s) }) return "a field asking for '${own.take(40)}'"
        }
        for (prev in nodes.subList(maxOf(0, index - 2), index)) {
            if (prev.editable || prev.password) continue
            val caption = normal(textOf(prev))
            if (caption.isNotEmpty() && caption.length <= MAX_LABEL_CHARS && rules.fieldPatterns.any { it.containsMatchIn(caption) }) {
                return "a field under '${textOf(prev).take(40)}'"
            }
        }
        return ""
    }

    private fun otpBoxes(nodes: List<UiNode>): Boolean {
        var run = 0
        for (n in nodes) {
            if (n.editable && n.maxLength == 1) { run++; if (run >= OTP_BOXES) return true } else run = 0
        }
        return false
    }

    /** Why nothing on this screen may be described or touched, or "". */
    fun secureReason(snapshot: Snapshot): String {
        packageVerdict(snapshot.packageName, snapshot.label).let { if (it.isNotEmpty()) return it }
        val activity = snapshot.page?.activity?.lowercase().orEmpty()
        if (activity.isNotEmpty() && rules.secureActivities.any { it.containsMatchIn(activity) }) {
            return "this is a payment screen (its window is a payment component) -- the person takes over here"
        }
        if (snapshot.secure) return "this window is protected (secure content) -- the person takes over here"
        for (i in snapshot.nodes.indices) {
            val asks = sensitiveField(snapshot.nodes, i)
            if (asks.isNotEmpty()) return "this looks like a payment or sign-in screen ($asks) -- the person takes over here"
        }
        if (otpBoxes(snapshot.nodes)) return "this looks like a payment or sign-in screen (a code's digit boxes) -- the person takes over here"
        return ""
    }

    // -- controls ---------------------------------------------------------------------------------

    fun stripAmounts(text: String): String {
        var s = rules.amount.replace(normal(text), " ")
        s = BRACKETED.replace(s, " ")
        s = SEPARATORS.replace(s, " ")
        return Words.split(s).joinToString(" ")
    }

    private fun labelVerdict(label: String, role: String): Control {
        val text = normal(label)
        if (text.isEmpty()) return Control.NONE
        val stripped = stripAmounts(text)
        val words = Words.split(stripped)
        if (words.size <= rules.controlMaxWords) {
            if (stripped in rules.payExact || rules.payPhrases.any { it.containsMatchIn(text) || it.containsMatchIn(stripped) }) return Control.PAY
            if (words.any { it in rules.paySquashed }) return Control.PAY
            if (stripped in rules.entryExact || rules.entryPhrases.any { it.containsMatchIn(stripped) }) return Control.ENTRY
        }
        for (word in rules.commitWords) {
            if ((stripped == word || stripped.startsWith("$word ")) && (words.size <= rules.commitMaxWords || role == "button")) return Control.COMMIT
        }
        if (words.size <= rules.controlMaxWords &&
            (stripped in rules.forwardExact || rules.forwardPrefixes.any { stripped == it || stripped.startsWith("$it ") })) return Control.FORWARD
        return Control.NONE
    }

    private fun idVerdict(viewId: String): Control {
        val words = idWords(viewId)
        if (words.isEmpty()) return Control.NONE
        return when {
            rules.payIds.any { it.containsMatchIn(words) } -> Control.PAY
            rules.entryIds.any { it.containsMatchIn(words) } -> Control.ENTRY
            rules.commitIds.any { it.containsMatchIn(words) } -> Control.COMMIT
            rules.forwardIds.any { it.containsMatchIn(words) } -> Control.FORWARD
            else -> Control.NONE
        }
    }

    /** What tapping this control does, by its own short label and its id; the stricter of the two. */
    fun controlVerdict(label: String, viewId: String = "", role: String = ""): Control {
        val verdicts = setOf(labelVerdict(label, role), idVerdict(viewId))
        return CONTROL_ORDER.firstOrNull { it in verdicts } ?: Control.NONE
    }

    fun controlVerdict(target: Target): Control = controlVerdict(target.label, target.viewId, target.role)

    /** The field being typed in is a search box: focused, editable, not a password, and its label, hint
     *  or id says "search". Enter there runs a search. */
    fun searchFocused(snapshot: Snapshot): Boolean = snapshot.nodes.any { n ->
        n.editable && n.focused && !n.password &&
            (SEARCH_WORD.containsMatchIn(normal("${textOf(n)} ${n.hint}")) || " search " in " ${idWords(n.viewId)} ")
    }

    // -- pages ------------------------------------------------------------------------------------

    private fun box(n: UiNode) = Box(n.left, n.top, n.right, n.bottom)

    fun controls(snapshot: Snapshot): List<Target> {
        val found = mutableListOf<Target>()
        for (node in snapshot.nodes) {
            if (!node.clickable) continue
            val b = box(node)
            val label = textOf(node)
            found += Target(label, node.viewId, node.role, b)
            if (label.isBlank()) {
                snapshot.nodes.filter { !it.clickable && textOf(it).isNotBlank() && box(it).inside(b) }.take(MAX_TILE_TEXTS)
                    .forEach { found += Target(textOf(it), node.viewId, node.role, box(it)) }
            }
        }
        snapshot.page?.offscreenIds?.forEach { found += Target("", it, "", null) }
        return found
    }

    private fun titleMatch(text: String, titles: List<String>): Boolean {
        val s = stripAmounts(text)
        return titles.any { s == it || (" " in it && s.startsWith("$it ")) }
    }

    fun pageFeatures(snapshot: Snapshot): Map<String, Boolean> {
        val nodes = snapshot.nodes
        val width = (if (snapshot.width > 0) snapshot.width else nodes.maxOfOrNull { it.right } ?: 1).coerceAtLeast(1)
        val height = (if (snapshot.height > 0) snapshot.height else nodes.maxOfOrNull { it.bottom } ?: 1).coerceAtLeast(1)
        val found = controls(snapshot)
        val onScreen = found.filter { it.box != null && it.label.isNotBlank() }

        val finalPay = onScreen.any { k ->
            Words.split(stripAmounts(k.label)).size <= rules.controlMaxWords && rules.finalPayPhrases.any { it.containsMatchIn(normal(k.label)) }
        }
        val barePay = onScreen.any { stripAmounts(it.label) in BARE_PAY }

        var rows = 0
        for (node in nodes) {
            if (node.checked == null && node.role != "radio") continue
            val b = box(node)
            val holders = nodes.map(::box).filter { it != b && b.inside(it) && it.bottom - it.top <= rules.rowContainerMax }
            val row = holders.minByOrNull { it.area }
            val top = row?.top ?: (b.top - rules.rowBand)
            val bottom = row?.bottom ?: (b.bottom + rules.rowBand)
            val beside = nodes.filter { it.top < bottom && it.bottom > top && textOf(it).isNotBlank() }.joinToString(" ") { normal(textOf(it)) }
            if (rules.paymentMethods.any { it.containsMatchIn(beside) }) rows++
        }
        val methodList = rows >= 2
        val masked = nodes.any { textOf(it).isNotBlank() && rules.maskedCard.containsMatchIn(normal(textOf(it))) }

        val topLimit = height * rules.topRegion
        val titles = nodes.filter { n ->
            textOf(n).isNotBlank() && (n.heading || (n.top < topLimit && Words.split(stripAmounts(textOf(n))).size <= rules.titleMaxWords))
        }
        val paymentTitle = titles.any { titleMatch(textOf(it), rules.paymentTitles) }
        val checkoutTitle = titles.any { titleMatch(textOf(it), rules.checkoutTitles) }
        val cartTitle = titles.any { titleMatch(textOf(it), rules.cartTitles) }

        val totals = mutableListOf<Box>()
        for (n in nodes) {
            val s = normal(textOf(n))
            if (s.isEmpty() || !rules.total.containsMatchIn(s)) continue
            val middle = (n.top + n.bottom) / 2.0
            if (rules.amount.containsMatchIn(s) || nodes.any { m ->
                    m !== n && rules.amount.containsMatchIn(normal(textOf(m))) && Math.abs((m.top + m.bottom) / 2.0 - middle) <= 40
                }) totals += box(n)
        }
        val primaryAfterTotal = nodes.any { n ->
            n.clickable && n.right - n.left >= width * rules.wideControl && totals.any { t -> t.bottom - 10 <= n.top && n.top <= t.bottom + 400 }
        }

        val priced = nodes.count { it.clickable && rules.amount.containsMatchIn(normal(textOf(it))) }
        val adds = onScreen.count { rules.addToCart.containsMatchIn(normal(it.label)) }
        val listing = priced >= 3 || adds >= 4
        val ids = nodes.filter { it.viewId.isNotEmpty() }.map { it.viewId } + snapshot.page?.offscreenIds.orEmpty()
        val productPage = ids.any { v -> rules.productIds.any { it in v.lowercase() } }
        val addressChoice = onScreen.any { k -> rules.addressChoices.any { it.containsMatchIn(normal(k.label)) } } ||
            ids.any { v -> rules.addressIds.any { it.containsMatchIn(idWords(v)) } }
        val entry = found.any { controlVerdict(it) == Control.ENTRY }
        val cartStructure = onScreen.any { k -> rules.cartStructure.any { it.containsMatchIn(normal(k.label)) } } ||
            ids.any { v -> rules.cartIds.any { it.containsMatchIn(v) } }
        return linkedMapOf(
            "final_pay_control" to finalPay, "bare_pay_control" to barePay, "payment_method_list" to methodList,
            "masked_card" to masked, "payment_title" to paymentTitle, "total_with_amount" to totals.isNotEmpty(),
            "primary_after_total" to primaryAfterTotal, "listing" to listing, "product_page" to productPage,
            "checkout_title" to checkoutTitle, "address_choice" to addressChoice, "entry_control" to entry,
            "cart_structure" to cartStructure, "cart_title" to cartTitle, "no_clickable_amounts" to (priced == 0),
        )
    }

    /** (payment, checkout, cart) from [pageFeatures] and the rules' weights. */
    fun pageScores(f: Map<String, Boolean>): Triple<Int, Int, Int> {
        fun w(name: String, feature: String = name) = if (f.getValue(feature)) rules.weight(name) else 0
        var payment = w("final_pay_control") + w("bare_pay_control") + w("payment_method_list") + w("masked_card") +
            w("payment_title") + w("total_with_amount") + w("primary_after_total")
        // A Buy Now sheet opens over its product page, whose ids stay in the tree: the evidence against
        // speaks only when no final pay control does.
        if (!f.getValue("final_pay_control")) payment += w("listing") + w("product_page")
        val checkout = w("checkout_title") + w("address_choice") + w("checkout_total", "total_with_amount") + w("checkout_listing", "listing")
        val cart = w("entry_control") + w("cart_total", "total_with_amount") + w("cart_structure") + w("cart_title") +
            w("no_clickable_amounts") + w("cart_listing", "listing")
        return Triple(payment, checkout, cart)
    }

    private fun keyOf(snapshot: Snapshot): Triple<String, String, Long>? =
        snapshot.page?.let { Triple(snapshot.packageName, it.activity, it.seq) }

    /** What this page is, judged whole, from the memory of the last page judged -- otto's classify_page. Pure. */
    fun classify(snapshot: Snapshot, held: Memory?): PageVerdict {
        val why = secureReason(snapshot)
        val features = pageFeatures(snapshot)
        val (payment, checkout, cart) = pageScores(features)
        val on = features.filterValues { it }.keys
        val floor = PageKind.of(snapshot.page?.kind)
        if (why.isNotEmpty() || floor == PageKind.SECURE) {
            return PageVerdict(PageKind.SECURE, why.ifEmpty { "the phone judged this screen protected -- the person takes over here" },
                payment, checkout, cart, on, null)
        }
        val now = when {
            payment >= rules.paymentMin -> PageKind.PAYMENT
            checkout >= rules.checkoutMin -> PageKind.CHECKOUT
            cart >= rules.cartMin -> PageKind.CART
            else -> PageKind.NONE
        }
        val key = keyOf(snapshot)
        var kind = now
        if (held != null && key != null && Triple(held.packageName, held.activity, held.seq) == key) {
            kind = when {
                features.getValue("listing") || (features.getValue("product_page") && !features.getValue("final_pay_control")) -> now
                held.kind == PageKind.PAYMENT && now == PageKind.CART && payment <= rules.paymentMin - 2 -> now
                else -> now.atLeast(held.kind)
            }
        }
        if (floor != null) kind = kind.atLeast(floor)
        val shown = REASONS.filter { (feature, _) -> feature in on }.map { it.second }.take(3)
        val reason = if (kind == now) shown.joinToString(", ") else "as it was judged before it scrolled"
        val remember = key != null && kind in setOf(PageKind.CART, PageKind.CHECKOUT, PageKind.PAYMENT)
        return PageVerdict(kind, reason, payment, checkout, cart, on,
            if (remember) Memory(key!!.first, key.second, key.third, kind) else null)
    }

    /** [classify] with the memory this guard holds, which it then keeps: every walk the phone makes is judged once. */
    @Synchronized fun page(snapshot: Snapshot): PageVerdict = classify(snapshot, memory).also { memory = it.memory }

    /** The class a snapshot carries when the walk that took it was judged, else judged now. */
    fun kindOf(snapshot: Snapshot): PageKind = PageKind.of(snapshot.page?.kind) ?: page(snapshot).kind

    enum class Enter { PRESS, DECLINE, HANDOVER }

    fun enterVerdict(snapshot: Snapshot, kind: PageKind): Enter = when {
        kind == PageKind.SECURE || kind == PageKind.PAYMENT -> Enter.HANDOVER
        searchFocused(snapshot) -> Enter.PRESS
        kind == PageKind.CART || kind == PageKind.CHECKOUT -> Enter.DECLINE
        controls(snapshot).any { controlVerdict(it).let { v -> v == Control.PAY || v == Control.ENTRY } } -> Enter.DECLINE
        else -> Enter.PRESS
    }

    private fun named(t: Target): String =
        if (t.label.isBlank()) "#${t.viewId.take(60)}" else if (t.viewId.isEmpty()) "'${t.label.take(60)}'" else "'${t.label.take(60)}' #${t.viewId.take(60)}"

    // -- enforcement ------------------------------------------------------------------------------

    /** Throws when nothing but the way out may happen: acting switched off, the person has the phone, or a secure page. */
    fun requireActionable(snapshot: Snapshot?) {
        if (!allowedToAct) throw guard(note("acting is switched off in Otto's settings"), handover = false)
        if (handedOver) throw guard(note("the person has taken over; tap Resume in Otto to continue"), handover = true)
        if (snapshot == null) return
        val why = secureReason(snapshot)
        if (why.isNotEmpty()) { handedOver = true; throw guard(note(why), handover = true) }
    }

    /** Nothing on a payment page is tapped, typed into, submitted or swiped from a point: the person pays there. */
    fun requireInteractive(snapshot: Snapshot) {
        val kind = kindOf(snapshot)
        if (kind == PageKind.SECURE || kind == PageKind.PAYMENT) {
            handedOver = true
            throw guard(note("this is a payment page -- the person pays here; nothing on it is tapped, typed or submitted"), handover = true)
        }
    }

    /** The control's own words: a pay control is refused on any page (not handed over); a checkout entry, a
     *  commit, and on a checkout step a forward button, only through phone_commit. */
    fun requireTappable(node: UiNode, commit: Boolean, kind: PageKind = PageKind.NONE) {
        val target = Target(textOf(node), node.viewId, node.role, box(node))
        val shown = named(target)
        when (controlVerdict(target)) {
            Control.PAY -> throw refused(note("$shown is a payment control -- Otto never taps it; nothing was handed over"))
            Control.ENTRY -> if (!commit) throw refused(note("$shown leads to checkout; only phone_commit may tap it"))
            Control.COMMIT -> if (!commit) throw refused(note("$shown cannot be taken back; only phone_commit may tap it"))
            Control.FORWARD -> if (!commit && kind == PageKind.CHECKOUT) throw refused(note("$shown moves this checkout on; only phone_commit may tap it"))
            Control.NONE -> Unit
        }
    }

    /** A tap by coordinates that lands on no element: refused when the screen has clickable elements, because
     *  what is drawn at that point is unknown to the guard. On a screen with none (a game) the only
     *  content-level check is a look, so the tap needs a screenshot taken on this very capture. */
    fun requireBlindTap(snapshot: Snapshot, lookedId: String?) {
        if (snapshot.nodes.any { it.clickable }) throw refused(note("nothing in the tree is under that point; tap an element by its text"))
        if (lookedId == null || lookedId != snapshot.snapshotId) {
            throw refused(note("nothing in the tree is under that point; phone_look at this screen first, then tap"))
        }
    }

    /** Enter has no label: the page is judged instead. */
    fun requireSubmit(snapshot: Snapshot) {
        when (enterVerdict(snapshot, kindOf(snapshot))) {
            Enter.HANDOVER -> { handedOver = true; throw guard(note("Enter would submit a payment page -- the person does that"), handover = true) }
            Enter.DECLINE -> {
                val blocker = controls(snapshot).firstOrNull { controlVerdict(it).let { v -> v == Control.PAY || v == Control.ENTRY } }
                throw refused(note("Enter was not pressed: ${blocker?.let { "this page has ${named(it)}" } ?: "this page is part of a checkout"}, and Enter could submit it"))
            }
            Enter.PRESS -> Unit
        }
    }

    fun requireTypeable(node: UiNode?) {
        if (node != null && node.password) throw guard(note("that is a password field -- the person types there"), handover = true)
    }

    /** A screenshot goes to a vision model: not of a secure page (hand over), a payment page, or a screen naming a secret. */
    fun requireCapturable(snapshot: Snapshot) {
        when (kindOf(snapshot)) {
            PageKind.SECURE -> { handedOver = true; throw guard(note("this screen is protected -- the person takes over"), handover = true) }
            PageKind.PAYMENT -> throw refused(note("this is a payment page -- it is not captured"))
            else -> Unit
        }
        sensitiveText(snapshot.nodes.map { textOf(it) }).firstOrNull()?.let { throw refused(note("the screen shows something sensitive ('$it') -- not captured")) }
    }

    /** Whether a judgement made on `snapshot` must be made again on a fresh walk before acting: it was
     *  read while the screen was still changing, or the screen has said it changed since. */
    fun needsRecheck(snapshot: Snapshot?, lastEventAt: Long): Boolean =
        snapshot != null && (!snapshot.settled || lastEventAt > snapshot.takenAt)

    /** Element `index` of `old`, judged again on `fresh` before it is tapped: still there (same label and id,
     *  overlapping where it was), the fresh page judged whole, and the element -- and whatever is drawn over
     *  its centre now -- judged by its own words. Returns the element as it is now. */
    fun requireStillTappable(old: Snapshot, fresh: Snapshot, index: Int, commit: Boolean): UiNode {
        val was = old.node(index) ?: throw stale("no element [$index] on this screen")
        val now = fresh.nodes.filter { it.label == was.label && it.viewId == was.viewId && overlap(it, was) > 0 }
            .maxByOrNull { overlap(it, was) }
            ?: throw stale("[$index] '${was.label.take(60)}' is not where it was any more; read the screen again")
        requireActionable(fresh)
        requireInteractive(fresh)
        val kind = kindOf(fresh)
        requireTappable(now, commit, kind)
        nodeAt(fresh, now.centreX, now.centreY)?.let { requireTappable(it, commit, kind) }
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
        requireInteractive(fresh)
        if (now == null) {
            if (fresh.nodes.any { it.clickable }) throw refused(note("nothing in the tree is under that point; tap an element by its text"))
            return null
        }
        requireTappable(now, commit = false, kind = kindOf(fresh))
        requireTypeable(now)
        return now
    }

    /** A swipe from a point, judged again on `fresh`: whatever is under the point now is what it drags. */
    fun requireStillSwipeable(old: Snapshot, fresh: Snapshot, x: Int, y: Int) {
        requireActionable(fresh)
        requireInteractive(fresh)
        nodeAt(fresh, x, y)?.let { requireTappable(it, commit = false, kind = kindOf(fresh)) }
    }

    private fun overlap(a: UiNode, b: UiNode): Long =
        maxOf(0, minOf(a.right, b.right) - maxOf(a.left, b.left)).toLong() * maxOf(0, minOf(a.bottom, b.bottom) - maxOf(a.top, b.top))

    /** The smallest element under a point, for a tap by coordinates. */
    fun nodeAt(snapshot: Snapshot, x: Int, y: Int): UiNode? =
        snapshot.nodes.filter { x in it.left..it.right && y in it.top..it.bottom }
            .minByOrNull { (it.right - it.left).coerceAtLeast(1) * (it.bottom - it.top).coerceAtLeast(1) }

    private fun stale(message: String) = DeviceException(message, "stale")
    private fun refused(message: String) = DeviceException(message, "refused", handover = false)
    private fun guard(message: String, handover: Boolean) = DeviceException(message, "guard", handover)

    companion object {
        const val MAX_LABEL_CHARS = 32
        const val OTP_BOXES = 4
        const val MAX_TILE_TEXTS = 3
        val SEARCH_WORD = Regex("(^|\\W)search($|\\W)")
        private val BRACKETED = Regex("\\([^)]*\\)")
        private val SEPARATORS = Regex("[·|•+\\-–—:,!.?&]+")
        private val BARE_PAY = setOf("pay", "buy", "purchase")
        private val CONTROL_ORDER = listOf(Control.PAY, Control.ENTRY, Control.COMMIT, Control.FORWARD)
        private val REASONS = listOf(
            "payment_method_list" to "payment methods to choose from", "final_pay_control" to "a final pay button",
            "masked_card" to "saved cards", "payment_title" to "a payment or order-review title", "bare_pay_control" to "a Pay button",
            "total_with_amount" to "a total", "checkout_title" to "a checkout title", "address_choice" to "an address to choose",
            "entry_control" to "a checkout button", "cart_structure" to "cart rows",
        )
    }
}
