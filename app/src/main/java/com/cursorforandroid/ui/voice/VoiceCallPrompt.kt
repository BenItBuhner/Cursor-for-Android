package com.cursorforandroid.ui.voice

import com.cursorforandroid.domain.AssistantMessage
import com.cursorforandroid.domain.TimelineItem
import com.cursorforandroid.domain.UserMessage
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/** A line of the written chat as the call reads it: [fromUser] typed it (or the call sent it), or the agent wrote it. */
data class WrittenLine(val id: String, val fromUser: Boolean, val text: String)

/** The written chat among [items], oldest first: the user's messages and the agent's finished replies. */
fun writtenLinesOf(items: List<TimelineItem>): List<WrittenLine> = items.mapNotNull { item ->
    when (item) {
        is UserMessage -> item.text.takeIf { it.isNotBlank() }?.let { WrittenLine(item.id, fromUser = true, text = it) }
        is AssistantMessage -> item.markdown.takeIf { it.isNotBlank() && !item.isStreaming }?.let { WrittenLine(item.id, fromUser = false, text = it) }
        else -> null
    }
}

/**
 * The voice agent's instructions as the desktop finishes them before the line opens (`recent-conversation.ts`
 * `ZUv`): the account's plan, then the tail of the written chat, the searching and style sections, and the time
 * the call started. The words are Cursor's own, so the voice agent behaves here as it does at the desk.
 */
object VoiceCallPrompt {
    /** The name the agent's lines go under in the recent conversation (the desktop's `agentName`). */
    const val AgentName = "Cursor"
    private const val RecentLines = 6
    private const val LineChars = 240
    private const val RecentChars = 1_000

    fun instructions(plan: String, lines: List<WrittenLine>, startedAt: Date, timeZone: TimeZone = TimeZone.getDefault()): String {
        val recent = recent(lines)
        val withRecent = if (recent.isEmpty()) plan else "$plan\n\n$RecentHeader\n${recent.joinToString("\n")}"
        return "$withRecent\n\n$Searching\n\n$Style\n\n${currentTime(startedAt, timeZone)}"
    }

    /** The last few lines, each cut to a summary, as `VUv` keeps them: at most [RecentLines], about [RecentChars] in all. */
    internal fun recent(lines: List<WrittenLine>): List<String> {
        val out = lines.filter { it.text.isNotBlank() }.takeLast(RecentLines).map { line ->
            val text = line.text.replace(Regex("""\s*\n+\s*"""), " ").trim()
            "${if (line.fromUser) "User" else AgentName}: ${if (text.length > LineChars) text.take(LineChars - 1).trimEnd() + "…" else text}"
        }.toMutableList()
        var total = out.sumOf { it.length }
        while (out.size > 1 && total > RecentChars) total -= out.removeAt(0).length
        return out
    }

    internal fun currentTime(at: Date, zone: TimeZone): String {
        val spoken = SimpleDateFormat("EEEE, MMMM d, yyyy 'at' h:mm a z", Locale.US).apply { timeZone = zone }.format(at)
        val day = SimpleDateFormat("yyyy-MM-dd", Locale.US).apply { timeZone = zone }.format(at)
        return """# Current time
The call started at $spoken (timezone ${zone.id});
some time may have passed since then. Do not infer anyone's location from it.
This is silent background context. Do not respond to this.
If they ask what day it is, or what today is, answer from this. Do not announce it otherwise.
As YYYY-MM-DD, today is $day."""
    }

    private const val RecentHeader = "RECENT CONVERSATION (context only — answer the latest user utterance; do not re-greet or reopen old topics unless the user asks; speak of your own work in the first person as I, and never call a lookup a peek or say a tool name out loud; lines are clipped summaries, so frame in-progress work as \"last I saw\", state settled results flat, never guess why a check passed or failed, call send_task for exact values, constants, line numbers, or code walkthroughs not verbatim below, paraphrase snake-case or camelCase test names in plain words, never read raw URLs, directory trees, or full commit hashes out loud unless they explicitly ask for a URL or path, and when asked to continue or finish an interrupted reply, speak only the Unheard remainder without repeating Interrupted mid-reply after):"

    private const val Searching = """### Searching
The recent lines are the tail of the chat, cut short. Answer from them only when they hold the exact answer; never extrapolate constants, line numbers, or a code walkthrough from a summary line. When they say no to that answer, search; do not guess again from the lines.
The answer waits for the hits. Never say you do not have it in the same breath as the call.
Speak only what a hit or what came back says. A miss means those words did not match, nothing more. Never say your history has nothing, that something never came up, that you did not find it, or that you two never discussed it, unless what came back says so. On a miss, send_task; the one line naming that job is all you say.
A hit is what was said then, not where things stand now. When the recent conversation, work_overheard, or work_landed already states the latest status, answer from it directly in the first person as your own work; call send_task when they ask for exact values, constants, line numbers, or a walkthrough not verbatim here, or to inspect the workspace, run tools, or check for changes since the latest update.
The query is the subject, not "earlier" or "before". A day or a span goes in from and to as YYYY-MM-DD, worked out from the Current time stamp, never in the query.
When one hit fits, open its id and answer from its text. When two different hits fit, name them, ask which, then open that one.
Two searches per question. If neither names it, send_task it.
Say the day as the hit says it. Do not count days yourself.
A last-call question is the newest earlier call."""

    private const val Style = """### Style guardrails
Be concise. Respond succinctly, addressing one topic at most.
Speak in short, natural, friendly sentences. Aim for one to three sentences whenever possible.
Avoid long explanations unless the caller asks for more detail.
Ask one question at a time when needed."""
}
