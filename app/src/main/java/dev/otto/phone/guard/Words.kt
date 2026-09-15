package dev.otto.phone.guard

import java.text.Normalizer

/**
 * The text forms both money guards match against, the same steps as otto's agent/phone/guard.py:
 * [normal] (NFKC, invisible characters out, lower case, look-alike letters folded to Latin, one space
 * between words), [idWords] and [tokens]. Pure, so the page corpus can hold the two sides to the same
 * numbers on the JVM.
 */
object Words {
    /** Characters a screen can hide inside a word: zero-width joiners and spaces, soft hyphens, bidi
     *  marks, word joiners. "Pay​now" is "paynow". */
    val INVISIBLE = Regex("[\\u200B-\\u200F\\u2060-\\u2064\\u00AD\\uFEFF\\u202A-\\u202E\\u2066-\\u2069]")

    /** Whatever Python's str.split() splits at: Java's \s is ASCII only. */
    private val SPACE = Regex("[\\s\\p{Z}\\u001C-\\u001F\\u0085]+")
    private val CAMEL = Regex("([a-z0-9])([A-Z])")
    private val LETTER_CAMEL = Regex("([a-z])([A-Z])")
    private val ID_SEPARATORS = Regex("[-_./:#]+")
    private val NOT_A_LETTER = Regex("[^a-z]+")

    /** Cyrillic and Greek letters that draw the same as a Latin one, folded after lower-casing -- the
     *  same table as otto's guard._CONFUSABLES, so "Pаy now" with a Cyrillic а is "pay now". */
    val CONFUSABLES: Map<Char, Char> = mapOf(
        'а' to 'a', 'е' to 'e', 'о' to 'o', 'р' to 'p', 'с' to 'c', 'у' to 'y', 'х' to 'x', 'і' to 'i',
        'ј' to 'j', 'ѕ' to 's', 'һ' to 'h', 'ԁ' to 'd', 'ԛ' to 'q', 'ԝ' to 'w', 'ѵ' to 'v', 'ԍ' to 'g',
        'ӏ' to 'l', 'к' to 'k', 'т' to 't', 'м' to 'm', 'в' to 'b', 'н' to 'h', 'ь' to 'b', 'ѡ' to 'w',
        'α' to 'a', 'ο' to 'o', 'ρ' to 'p', 'ν' to 'v', 'ι' to 'i', 'κ' to 'k', 'υ' to 'u', 'τ' to 't',
        'ε' to 'e', 'β' to 'b', 'χ' to 'x', 'γ' to 'y', 'ς' to 's',
    )

    fun nfkc(text: String): String = Normalizer.normalize(text, Normalizer.Form.NFKC)

    /** Words split at whitespace, empty ones dropped: Python's str.split(). */
    fun split(text: String): List<String> = text.split(SPACE).filter { it.isNotEmpty() }

    fun normal(text: String): String {
        val lowered = nfkc(text).replace(INVISIBLE, "").lowercase()
        val latin = buildString(lowered.length) { for (ch in lowered) append(CONFUSABLES[ch] ?: ch) }
        return split(latin).joinToString(" ")
    }

    /** An element's resource id as words: the package prefix dropped, camelCase and -_./:# split, then
     *  [normal] -- "buyNowButton" is "buy now button". */
    fun idWords(viewId: String): String =
        normal(viewId.substringAfter(":id/").replace(CAMEL, "$1 $2").replace(ID_SEPARATORS, " "))

    /** A package or a label as words of letters: camelCase split, then anything not a letter. */
    fun tokens(text: String): List<String> =
        normal(nfkc(text).replace(LETTER_CAMEL, "$1 $2")).split(NOT_A_LETTER).filter { it.isNotEmpty() }

    /** A phrase as a whole-word pattern over [normal] text: word edges only where it starts or ends with
     *  a letter or digit, so "pay ₹" matches "pay ₹499". */
    fun wordRegex(phrase: String): Regex {
        val p = normal(phrase)
        val head = if (p.firstOrNull()?.isLetterOrDigit() == true) "(^|\\W)" else ""
        val tail = if (p.lastOrNull()?.isLetterOrDigit() == true) "($|\\W)" else ""
        return Regex(head + Regex.escape(p) + tail)
    }
}
