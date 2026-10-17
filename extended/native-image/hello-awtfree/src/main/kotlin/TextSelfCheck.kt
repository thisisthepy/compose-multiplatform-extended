package awtfree

import org.jetbrains.skia.Color
import org.jetbrains.skia.FontMgr
import org.jetbrains.skia.paragraph.FontCollection
import org.jetbrains.skia.paragraph.Paragraph
import org.jetbrains.skia.paragraph.ParagraphBuilder
import org.jetbrains.skia.paragraph.ParagraphStyle
import org.jetbrains.skia.paragraph.TextStyle

/**
 * Whether Korean text is shaped, broken into words and wrapped correctly by the Skia linked
 * into this executable.
 *
 * An application that is one executable carries no ICU data file beside it, so finding words
 * and places to wrap has to work from what is linked in. When it does not, nothing fails
 * loudly: Korean comes out as boxes, a double click selects one syllable, and a line wraps in
 * the middle of a Latin word. This lays text out through the Skia paragraph engine Compose
 * draws its text with, and checks three things:
 *
 * 1. every character has a glyph in some font the system offers (no unresolved glyphs, which
 *    is what draws as a box);
 * 2. the word around a syllable of a Korean word is that whole word;
 * 3. wrapped to a narrow width, lines break only between words or between Hangul syllables,
 *    and a Latin word that fits on a line is never split.
 *
 * Kotlin/Native renderers built on the same window modules run the same check, so both kinds of
 * executable prove the same thing.
 */
fun textSelfCheck(): Boolean {
    val failures = mutableListOf<String>()

    // 1. Glyph coverage, on one line so nothing else is being measured.
    val wide = layout(SAMPLE, 100_000f)
    if (wide.unresolvedGlyphsCount != 0) {
        failures += "${wide.unresolvedGlyphsCount} character(s) have no glyph in any font; they draw as boxes"
    }

    // 2. The word around a syllable in the middle of a Korean word.
    val wordStart = SAMPLE.indexOf(KOREAN_WORD)
    val middle = wordStart + KOREAN_WORD.length / 2
    val word = wide.getWordBoundary(middle)
    val found = SAMPLE.substring(word.start.coerceIn(0, SAMPLE.length), word.end.coerceIn(0, SAMPLE.length))
    if (word.start != wordStart || word.end != wordStart + KOREAN_WORD.length) {
        failures += "the word around offset $middle is [${word.start}, ${word.end}) \"$found\", not \"$KOREAN_WORD\""
    }

    // 3. Wrapping. Narrow enough that the sample takes several lines, wide enough for the
    //    longest Latin word, which therefore must never be split.
    val latinWidth = layout(LATIN_WORD, 100_000f).maxIntrinsicWidth
    val lines = sampleParagraph(latinWidth * 1.25f).lineMetrics
    if (lines.size < 3) {
        failures += "wrapped to ${latinWidth * 1.25f} the sample took ${lines.size} line(s), expected several"
    }
    for (line in lines.dropLast(1)) {
        val at = line.endIndex
        if (at <= 0 || at >= SAMPLE.length) {
            failures += "a line ends at $at, outside the text"
            continue
        }
        val before = SAMPLE[at - 1]
        val after = SAMPLE[at]
        val allowed = before.isWhitespace() || after.isWhitespace() ||
            isPunctuation(before) || (isHangul(before) && isHangul(after))
        if (!allowed) failures += "a line breaks between '$before' and '$after' at $at, which is inside a word"
    }
    val latinStart = SAMPLE.indexOf(LATIN_WORD)
    val latinEnd = latinStart + LATIN_WORD.length
    if (lines.none { it.startIndex <= latinStart && it.endIndex >= latinEnd }) {
        failures += "\"$LATIN_WORD\" fits on a line and was split across lines"
    }

    val breaks = lines.dropLast(1).map { it.endIndex }
    return if (failures.isEmpty()) {
        println("text self-check: ok (no unresolved glyphs, word \"$found\", ${lines.size} lines breaking at $breaks)")
        true
    } else {
        println("text self-check: FAILED")
        failures.forEach { println("  $it") }
        false
    }
}

/** The sample text laid out to [width], for the check and for the PNG. */
fun sampleParagraph(width: Float): Paragraph = layout(SAMPLE, width)

private val fonts by lazy { FontCollection().setDefaultFontManager(FontMgr.default) }

private fun layout(text: String, width: Float): Paragraph {
    val style = ParagraphStyle()
    // Skia's paragraph text is white unless told otherwise, which on the white drawing
    // surface would ink nothing.
    style.textStyle = TextStyle().setFontSize(16f).setColor(Color.BLACK)
    return ParagraphBuilder(style, fonts).addText(text).build().layout(width)
}

private fun isHangul(c: Char): Boolean = c in '가'..'힣' || c in 'ᄀ'..'ᇿ' || c in '㄰'..'㆏'

private fun isPunctuation(c: Char): Boolean = c in ".,!?;:"

/** A Korean word in the middle of the sample, not first or last on any line it could take. */
private const val KOREAN_WORD = "반갑습니다"

/** A Latin word long enough that a broken line breaker would split it. */
private const val LATIN_WORD = "internationalization"

private const val SAMPLE =
    "안녕하세요 반갑습니다. 한국어 문장의 줄바꿈과 단어 경계를 internationalization 단어와 함께 확인합니다."
