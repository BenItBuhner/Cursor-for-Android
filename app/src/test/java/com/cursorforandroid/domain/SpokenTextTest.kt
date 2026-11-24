package com.cursorforandroid.domain

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class SpokenTextTest {

    @Test
    fun `plain prose is said as it is`() {
        assertThat(SpokenText.of("The tests pass. I opened a pull request.")).isEqualTo("The tests pass. I opened a pull request.")
    }

    @Test
    fun `the marks of Markdown are not read out`() {
        val markdown = """
            ## Done

            I **fixed** the _login redirect_ in `SessionManager.kt` and ~~removed~~ the old path.

            - Added a test
            - [x] Ran the suite
            1. Pushed the branch
            > Nothing else changed.
        """.trimIndent()
        assertThat(SpokenText.of(markdown)).isEqualTo(
            "Done. I fixed the login redirect in SessionManager.kt and removed the old path. Added a test. Ran the suite. Pushed the branch. Nothing else changed.",
        )
    }

    @Test
    fun `bold around italics loses both`() {
        assertThat(SpokenText.of("This is ***very* important**.")).isEqualTo("This is very important.")
    }

    @Test
    fun `code blocks are named once, not read`() {
        val markdown = "Here is the change:\n\n```kotlin\nval a = 1\nval b = 2\n```\n\n```\nmore\n```\n\nIt compiles."
        assertThat(SpokenText.of(markdown)).isEqualTo("Here is the change: ${SpokenText.CODE_OMITTED} It compiles.")
    }

    @Test
    fun `a code block the stream has not closed yet is still left out`() {
        assertThat(SpokenText.of("Running it:\n\n```bash\n./gradlew test")).isEqualTo("Running it: ${SpokenText.CODE_OMITTED}")
    }

    @Test
    fun `links say their text and bare addresses say a link`() {
        assertThat(SpokenText.of("See [the pull request](https://github.com/acme/app/pull/12) or https://example.com/x?y=1 for more."))
            .isEqualTo("See the pull request or a link for more.")
        assertThat(SpokenText.of("![diagram](https://example.com/a.png) shows it.")).isEqualTo("diagram shows it.")
    }

    @Test
    fun `tables and rules are skipped`() {
        val markdown = "Results:\n\n| Test | Time |\n| --- | --- |\n| a | 1s |\n\n---\n\nAll green."
        assertThat(SpokenText.of(markdown)).isEqualTo("Results: All green.")
    }

    @Test
    fun `a long reply stops at the end of a sentence and says where the rest is`() {
        val sentence = "This sentence is exactly forty characters. "
        val spoken = SpokenText.of(sentence.repeat(40), maxChars = 200)
        assertThat(spoken.length).isAtMost(200)
        assertThat(spoken).endsWith("characters. ${SpokenText.REST_IN_CHAT}")
    }

    @Test
    fun `a long reply with no sentence to stop at is cut at a word`() {
        val spoken = SpokenText.of("word ".repeat(200).trim(), maxChars = 120)
        assertThat(spoken.length).isAtMost(120)
        assertThat(spoken).endsWith("word. ${SpokenText.REST_IN_CHAT}")
    }

    @Test
    fun `nothing sayable is empty`() {
        assertThat(SpokenText.of("")).isEmpty()
        assertThat(SpokenText.of("\n\n---\n\n| a | b |\n")).isEmpty()
    }

    @Test
    fun `a request to end the call is told from something to send`() {
        listOf("Goodbye.", "hang up", "OK, end call", "End the call please", "That’s all.", "Bye!").forEach { assertThat(SpokenText.isHangUp(it)).isTrue() }
        listOf("Say goodbye to the old API", "stop the server", "hang up the request after ten seconds", "").forEach { assertThat(SpokenText.isHangUp(it)).isFalse() }
    }
}
