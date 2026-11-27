package com.cursorforandroid.domain

/**
 * An agent's reply as it is read aloud in a voice chat: the words of its Markdown without the marks, and without what
 * nobody wants read to them — code blocks, tables, link targets, raw URLs. A long reply is cut at the end of a
 * sentence and says that the rest is in the chat, as a person reading a long message to someone would.
 */
object SpokenText {

    /** What the cut-off of a long reply ends on. */
    const val REST_IN_CHAT = "The rest is in the chat."

    /** What stands in for a fenced code block, once per run of them. */
    const val CODE_OMITTED = "There's code in the chat."

    const val DEFAULT_MAX_CHARS = 700

    private val fence = Regex("""(?m)^[ \t]*(```|~~~)[^\n]*\n[\s\S]*?(?:^[ \t]*\1[ \t]*$|\z)""")
    private val image = Regex("""!\[([^\]]*)]\([^)]*\)""")
    private val link = Regex("""\[([^\]]+)]\([^)]*\)""")
    private val autolink = Regex("""<(https?://[^>\s]+)>""")
    private val url = Regex("""https?://\S+""")
    private val html = Regex("""</?[A-Za-z][^>]*>""")
    private val heading = Regex("""(?m)^[ \t]{0,3}#{1,6}[ \t]+""")
    private val quote = Regex("""(?m)^[ \t]{0,3}>[ \t]?""")
    private val bullet = Regex("""(?m)^[ \t]*(?:[-*+]|\d{1,3}[.)])[ \t]+(?:\[[ xX]][ \t]+)?""")
    private val rule = Regex("""(?m)^[ \t]*(?:-{3,}|\*{3,}|_{3,})[ \t]*$""")
    private val tableRow = Regex("""(?m)^[ \t]*\|.*\|[ \t]*$""")
    private val emphasis = Regex("""(\*\*\*|\*\*|\*|___|__|~~)(?=\S)(.+?)(?<=\S)\1""")
    /** `_word_` alone: an underscore inside a name (`snake_case`) is part of the name. */
    private val underscored = Regex("""(?<![A-Za-z0-9_])_(?=\S)([^_\n]+?)(?<=\S)_(?![A-Za-z0-9_])""")
    private val inlineCode = Regex("""`+([^`\n]+)`+""")
    private val sentenceEnd = Regex("""[.!?]["')\]]?(?=\s|$)""")

    /** [markdown] as speech, at most [maxChars] long; empty when nothing in it can be said. */
    fun of(markdown: String, maxChars: Int = DEFAULT_MAX_CHARS): String {
        var text = markdown.replace("\r\n", "\n")
        text = fence.replace(text) { "\n$CODE_OMITTED\n" }
        text = tableRow.replace(text, "")
        text = rule.replace(text, "")
        text = image.replace(text) { it.groupValues[1] }
        text = link.replace(text) { it.groupValues[1] }
        text = autolink.replace(text, "a link")
        text = url.replace(text, "a link")
        text = html.replace(text, "")
        text = heading.replace(text, "")
        text = quote.replace(text, "")
        text = bullet.replace(text, "")
        // Twice: bold around italics leaves the inner pair for the second pass.
        repeat(2) { text = emphasis.replace(text) { it.groupValues[2] } }
        text = inlineCode.replace(text) { "\u0000${it.groupValues[1]}\u0000" }
        // Code spans are fenced off first, so a name in one keeps its underscores.
        text = text.split('\u0000').mapIndexed { i, part -> if (i % 2 == 0) underscored.replace(part) { it.groupValues[1] } else part }.joinToString("")
        val lines = text.lines().map { it.trim() }.filter { it.isNotEmpty() }
        // A line that ends without punctuation (a heading, a list item) gets a stop, so the voice pauses there.
        val spoken = buildString {
            var lastCodeNote = false
            for (line in lines) {
                val codeNote = line == CODE_OMITTED
                if (codeNote && lastCodeNote) continue
                lastCodeNote = codeNote
                if (isNotEmpty()) append(' ')
                append(line)
                if (line.last() !in ".!?:;,") append('.')
            }
        }.replace(Regex("""\s+"""), " ").trim()
        return clip(spoken, maxChars)
    }

    /** [text] cut at the last sentence that ends within [maxChars], or at a word when no sentence does, saying more follows. */
    internal fun clip(text: String, maxChars: Int): String {
        if (text.length <= maxChars) return text
        val room = (maxChars - REST_IN_CHAT.length - 1).coerceAtLeast(1)
        val head = text.take(room)
        val lastSentence = sentenceEnd.findAll(head).lastOrNull()?.range?.last
        val cut = when {
            lastSentence != null && lastSentence >= room / 3 -> head.take(lastSentence + 1)
            else -> head.substringBeforeLast(' ', head).trimEnd(',', ';', ':') + "."
        }
        return "$cut $REST_IN_CHAT"
    }

    /** What ends a voice chat when it is all the user says. */
    private val hangUps = setOf("hang up", "end call", "end the call", "goodbye", "good bye", "bye", "stop listening", "end voice chat", "that's all", "thats all")

    /** True when [words] — one transcribed utterance — is nothing but a request to end the call. */
    fun isHangUp(words: String): Boolean {
        val said = words.lowercase().replace('’', '\'').filter { it.isLetter() || it == ' ' || it == '\'' }.trim().replace(Regex("""\s+"""), " ")
        return said in hangUps || said.removeSuffix(" please") in hangUps || said.removePrefix("ok ").removePrefix("okay ") in hangUps
    }
}
