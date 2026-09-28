package com.cursorforandroid.domain

import org.junit.Test

/**
 * Audit harness: the coordinator transcript's folding at sizes past the fixtures — N turns of a Project, most of them
 * a worker's report answered silently, one in nine with a `send_to_user` message, a prompt every fifty — timed cold,
 * warm with one streaming delta appended (what each delta of a live coordinator turn costs), and the panel's
 * [TranscriptContent.of] over the same items. Prints; asserts nothing.
 */
class CoordinatorFoldingAuditBench {

    private fun items(turns: Int): List<TimelineItem> {
        val out = ArrayList<TimelineItem>(turns * 4)
        val t0 = 1_800_000_000_000L - turns * 90_000L
        for (i in 0 until turns) {
            val at = t0 + i * 90_000L
            if (i % 50 == 0) {
                out += UserMessage("u-$i", "Prompt $i: keep the workers moving.", at)
            } else {
                out += SystemNotification("n-$i", SystemNotification.Kind.Worker, title = "Report $i", summary = "Worker ${i % 12} reported", raw = "title: Report $i\nsummary: Worker ${i % 12} reported", timestampMillis = at, agentId = "bc-w${i % 12}")
            }
            val steps = ArrayList<ActivityStep>()
            steps += ThinkingBlock("Reading the report of turn $i and deciding what the worker does next.", 3)
            repeat(3) { c ->
                steps += ToolCall("c-$i-$c", "read_file", ToolKind.Read, ToolCall.STATUS_COMPLETED, "notes.md", detail = "notes/$c.md")
            }
            steps += ToolCall("c-$i-edit", "edit_file", ToolKind.Edit, ToolCall.STATUS_COMPLETED, "notes.md", detail = "notes/notes.md", linesAdded = 3, linesRemoved = 1)
            if (i % 9 == 0) {
                steps += ToolCall("c-$i-send", "send_to_user", ToolKind.Coordinator, ToolCall.STATUS_COMPLETED, "", payload = ToolPayload.CoordinatorMessage("Turn $i: worker ${i % 12} finished its slice; next one started.", messageId = "m-$i"))
            }
            out += ActivityGroup("g-$i", steps)
            out += RunFooter("f-$i", "run-$i", RunStatus.FINISHED, 60_000L, emptyList(), endedAtMillis = at + 60_000L)
        }
        return out
    }

    private inline fun ms(block: () -> Unit): Double {
        val t = System.nanoTime(); block(); return (System.nanoTime() - t) / 1e6
    }

    private fun median(xs: List<Double>) = xs.sorted()[xs.size / 2]

    @Test
    fun `folding timings at size`() {
        repeat(2) { items(240).let { TranscriptPresenter().present(it, true, false); TranscriptContent.of(it) } }
        for (turns in listOf(240, 2_000, 5_000)) {
            val base = items(turns)
            val cold = List(5) { ms { TranscriptPresenter().present(base, coordinatorMode = true, runActive = false) } }
            val presenter = TranscriptPresenter()
            presenter.present(base, true, true)
            var text = ""
            val deltas = List(40) { d ->
                text += "word$d "
                val live = base + AssistantMessage("live", text, isStreaming = true)
                ms { presenter.present(live, coordinatorMode = true, runActive = true) }
            }
            val content = List(20) { ms { TranscriptContent.of(base) } }
            val leftOut = List(20) { ms { CoordinatorTranscript.leftOut(base) } }
            val rows = TranscriptPresenter().present(base, true, false).rows
            println("AUDIT fold turns=$turns items=${base.size} rows=${rows.size} messages=${rows.count { it is TranscriptRow.Message }} " +
                "cold median=${"%.1f".format(median(cold))}ms warm-delta median=${"%.2f".format(median(deltas))}ms max=${"%.2f".format(deltas.max())}ms " +
                "leftOut median=${"%.2f".format(median(leftOut))}ms TranscriptContent.of median=${"%.2f".format(median(content))}ms")
        }
    }
}
