package com.cursorforandroid.domain

import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import org.junit.Test
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.Month
import java.time.ZoneId
import java.time.ZoneOffset
import kotlin.random.Random

/**
 * The New Chat page's greeting: the parts of the day and the seasons it reads the clock by, the occasions it knows,
 * the first name it greets by (or not), the pool's lines, and the pick — fitting the moment, never naming a reader
 * without a name, keeping off the lines lately shown, and the same line for the same moment and seed.
 */
class GreetingsTest {

    private val utc: ZoneId = ZoneOffset.UTC

    /** Friday 2 October 2026, 15:30. */
    private val friday = LocalDateTime.of(2026, 10, 2, 15, 30)

    private fun at(date: LocalDateTime, name: String? = null) = GreetingContext(at = date, name = name)

    private fun millis(date: LocalDateTime) = date.toInstant(ZoneOffset.UTC).toEpochMilli()

    @Test
    fun `the hours fall into the parts of the day, late night running past midnight`() {
        val parts = (0..23).associateWith { DayPart.of(it) }
        assertThat(parts.filterValues { it == DayPart.LateNight }.keys).containsExactly(21, 22, 23, 0, 1)
        assertThat(parts.filterValues { it == DayPart.SmallHours }.keys).containsExactly(2, 3, 4)
        assertThat(parts.filterValues { it == DayPart.EarlyMorning }.keys).containsExactly(5, 6, 7)
        assertThat(parts.filterValues { it == DayPart.Morning }.keys).containsExactly(8, 9, 10)
        assertThat(parts.filterValues { it == DayPart.Lunch }.keys).containsExactly(11, 12, 13)
        assertThat(parts.filterValues { it == DayPart.Afternoon }.keys).containsExactly(14, 15, 16)
        assertThat(parts.filterValues { it == DayPart.Evening }.keys).containsExactly(17, 18, 19, 20)
    }

    @Test
    fun `the bucket turns with the part of the day and with the date, and not within either`() {
        fun bucket(h: Int, m: Int, day: Int = 2) = GreetingBucket.of(millis(LocalDateTime.of(2026, 10, day, h, m)), utc)
        assertThat(bucket(14, 0)).isEqualTo(bucket(16, 59))
        assertThat(bucket(16, 59)).isNotEqualTo(bucket(17, 0))
        assertThat(bucket(23, 59).part).isEqualTo(DayPart.LateNight)
        assertThat(bucket(0, 1, day = 3).part).isEqualTo(DayPart.LateNight)
        assertThat(bucket(23, 59)).isNotEqualTo(bucket(0, 1, day = 3))
        // The zone is the reader's: 15:30 UTC is 08:30 in Los Angeles.
        assertThat(GreetingBucket.of(millis(friday), ZoneId.of("America/Los_Angeles")).part).isEqualTo(DayPart.Morning)
    }

    @Test
    fun `the seasons turn the other way round in the south`() {
        assertThat(Season.of(Month.JANUARY, southern = false)).isEqualTo(Season.Winter)
        assertThat(Season.of(Month.JANUARY, southern = true)).isEqualTo(Season.Summer)
        assertThat(Season.of(Month.APRIL, southern = false)).isEqualTo(Season.Spring)
        assertThat(Season.of(Month.APRIL, southern = true)).isEqualTo(Season.Autumn)
        assertThat(Season.of(Month.JULY, southern = true)).isEqualTo(Season.Winter)
        assertThat(Season.of(Month.OCTOBER, southern = false)).isEqualTo(Season.Autumn)
        assertThat(Season.of(Month.OCTOBER, southern = true)).isEqualTo(Season.Spring)
        assertThat(Hemisphere.southern("Australia/Sydney")).isTrue()
        assertThat(Hemisphere.southern("America/Argentina/Buenos_Aires")).isTrue()
        assertThat(Hemisphere.southern("Pacific/Auckland")).isTrue()
        assertThat(Hemisphere.southern("America/Los_Angeles")).isFalse()
        assertThat(Hemisphere.southern("Europe/London")).isFalse()
        assertThat(Hemisphere.southern("UTC")).isFalse()
    }

    @Test
    fun `occasions keep to their days`() {
        fun on(y: Int, m: Int, d: Int) = Occasion.entries.filter { it.on(LocalDate.of(y, m, d)) }
        assertThat(on(2026, 11, 26)).contains(Occasion.Thanksgiving)
        assertThat(on(2026, 11, 19)).doesNotContain(Occasion.Thanksgiving)
        assertThat(on(2026, 9, 13)).contains(Occasion.ProgrammersDay)
        // A leap year's 256th day is the 12th.
        assertThat(on(2024, 9, 12)).contains(Occasion.ProgrammersDay)
        assertThat(on(2026, 11, 13)).contains(Occasion.FridayThe13th)
        assertThat(on(2026, 10, 13)).doesNotContain(Occasion.FridayThe13th)
        assertThat(on(2026, 10, 31)).containsExactly(Occasion.Halloween, Occasion.LastOfMonth)
        assertThat(on(2026, 12, 31)).containsExactly(Occasion.NewYearsEve)
        assertThat(on(2027, 1, 1)).containsExactly(Occasion.NewYear)
        assertThat(on(2027, 2, 28)).containsExactly(Occasion.LastOfMonth)
        assertThat(on(2028, 2, 29)).containsExactly(Occasion.LeapDay, Occasion.LastOfMonth)
        assertThat(on(2026, 10, 7)).isEmpty()
    }

    @Test
    fun `the first name is the account's first word, title-cased, or none`() {
        assertThat(FirstName.of("Bennett", "bennett@example.com")).isEqualTo("Bennett")
        assertThat(FirstName.of("Bennett Buhner", null)).isEqualTo("Bennett")
        assertThat(FirstName.of("  bennett ", null)).isEqualTo("Bennett")
        assertThat(FirstName.of("BENNETT", null)).isEqualTo("Bennett")
        assertThat(FirstName.of("Dr. Ada Lovelace", null)).isEqualTo("Ada")
        assertThat(FirstName.of("jean-luc", null)).isEqualTo("Jean-Luc")
        assertThat(FirstName.of("McKenzie", null)).isEqualTo("McKenzie")
        assertThat(FirstName.of("Zoë", null)).isEqualTo("Zoë")
        assertThat(FirstName.of("Cher", null)).isEqualTo("Cher")
        // Single characters and scripts without capitals.
        assertThat(FirstName.of("J", null)).isNull()
        assertThat(FirstName.of("美咲", null)).isEqualTo("美咲")
        // Placeholders and things that are not names.
        assertThat(FirstName.of("Demo", "demo@cursor.local")).isNull()
        assertThat(FirstName.of("Cursor", null)).isNull()
        assertThat(FirstName.of("user", null)).isNull()
        assertThat(FirstName.of("dev42", null)).isNull()
        assertThat(FirstName.of("Abcdefghijklmnopqrstuvwxyz", null)).isNull()
        assertThat(FirstName.of(null, null)).isNull()
        assertThat(FirstName.of("   ", null)).isNull()
    }

    @Test
    fun `an email stands in only when its local part is plainly a name`() {
        assertThat(FirstName.of(null, "bennett.buhner@example.com")).isEqualTo("Bennett")
        assertThat(FirstName.of(null, "ada_lovelace+cursor@example.com")).isEqualTo("Ada")
        assertThat(FirstName.of("", "grace-hopper@navy.mil")).isEqualTo("Grace")
        // An email given as the first name.
        assertThat(FirstName.of("bennett.buhner@example.com", null)).isEqualTo("Bennett")
        // No telling where the name ends.
        assertThat(FirstName.of(null, "bbuhner@example.com")).isNull()
        assertThat(FirstName.of(null, "j.smith@example.com")).isNull()
        assertThat(FirstName.of(null, "dev.team@example.com")).isNull()
        assertThat(FirstName.of(null, "42.answers@example.com")).isNull()
        assertThat(FirstName.of(null, "not-an-email")).isNull()
        // The account's own first name wins over its email.
        assertThat(FirstName.of("Sam", "alex.doe@example.com")).isEqualTo("Sam")
    }

    @Test
    fun `the pool is large, its ids unique, and every line short and fully filled in`() {
        val lines = GreetingLines.all
        assertThat(lines.size).isAtLeast(150)
        assertThat(lines.map { it.id }.toSet()).hasSize(lines.size)
        assertWithMessage("lines naming the reader").that(lines.count { it.needsName || "{, name}" in it.template }).isAtLeast(30)
        assertWithMessage("lines that never name").that(lines.count { "name}" !in it.template }).isAtLeast(100)
        val busy = GreetingContext(at = friday, name = "Christopher", running = 12, chatsToday = 12, rhythm = GreetingRhythm(streakDays = 12, awayDays = 12))
        for (line in lines) {
            for (name in listOf("Christopher", null)) {
                if (line.needsName && name == null) continue
                val text = line.render(busy.copy(name = name))
                assertWithMessage(line.id).that(text).doesNotContain("{")
                assertWithMessage(line.id).that(text).doesNotContain("}")
                assertWithMessage(line.id).that(text).doesNotContain("  ")
                assertWithMessage(line.id).that(text).doesNotMatch(".*\\s[,.?!].*")
                assertWithMessage(line.id).that(text.length).isAtMost(48)
                assertWithMessage(line.id).that(text.first().isUpperCase() || text.first().isDigit()).isTrue()
            }
        }
    }

    @Test
    fun `every hour of every day has lines of its own, with or without a name`() {
        for (day in 0..6) for (hour in 0..23) {
            val context = at(LocalDateTime.of(2026, 10, 5 + day, hour, 0))
            val fitting = GreetingLines.all.filter { it.fits(context) }
            assertWithMessage("clock lines on %s at %s", context.at.dayOfWeek, hour).that(fitting.count { it.kind == GreetingKind.Clock }).isAtLeast(5)
            assertThat(fitting.none { it.needsName }).isTrue()
            Greetings.pick(context, random = Random(hour * 7L + day))
        }
    }

    @Test
    fun `numbers are spelled out to twelve`() {
        val line = GreetingLines.all.first { it.id == "running-chef" }
        assertThat(line.render(at(friday).copy(running = 3))).isEqualTo("Three agents cooking. You're the chef.")
        assertThat(line.render(at(friday).copy(running = 14))).isEqualTo("14 agents cooking. You're the chef.")
        val optional = GreetingLines.all.first { it.id == "late-coffee" }
        assertThat(optional.render(at(friday, name = "Bennett"))).isEqualTo("Late night coffee, Bennett?")
        assertThat(optional.render(at(friday))).isEqualTo("Late night coffee?")
    }

    @Test
    fun `a line naming the reader is never picked without a name`() {
        repeat(300) { seed ->
            val pick = Greetings.pick(at(friday.withHour(seed % 24)), random = Random(seed))
            assertThat(pick.line.needsName).isFalse()
            assertThat(pick.text).doesNotContain("{")
        }
        val named = (0 until 300).map { Greetings.pick(at(friday.withHour(it % 24), name = "Bennett"), random = Random(it)) }
        assertThat(named.count { "Bennett" in it.text }).isGreaterThan(20)
    }

    @Test
    fun `every pick fits its moment`() {
        val contexts = listOf(
            at(LocalDateTime.of(2026, 10, 31, 3, 0)),
            at(LocalDateTime.of(2026, 5, 4, 12, 0), name = "Bennett"),
            at(friday).copy(running = 1),
            at(friday).copy(running = 4, justFinished = true, prMerged = true),
            at(friday).copy(rhythm = GreetingRhythm(firstToday = true, awayDays = 20, streakDays = 1)),
        )
        for (context in contexts) repeat(200) { seed ->
            val pick = Greetings.pick(context, random = Random(seed))
            assertWithMessage("%s for %s", pick.line.id, context).that(pick.line.cue.fits(context)).isTrue()
        }
    }

    @Test
    fun `what is happening now is favoured over the clock`() {
        val busy = at(friday).copy(running = 3)
        val picks = (0 until 400).map { Greetings.pick(busy, random = Random(it)).line }
        val moments = picks.count { it.kind == GreetingKind.Moment }
        assertThat(moments).isAtLeast(80)
        assertThat(picks.filter { it.kind == GreetingKind.Moment }.map { it.id }.toSet()).containsExactly("running-chef", "running-crew", "running-fleet")
        // One agent is not a crew.
        val one = (0 until 200).map { Greetings.pick(at(friday).copy(running = 1), random = Random(it)).line.id }
        assertThat(one).containsNoneOf("running-chef", "running-crew", "running-fleet", "running-lots")
        assertThat(one).contains("running-one")
        // Halloween has its say.
        val halloween = (0 until 200).map { Greetings.pick(at(LocalDateTime.of(2026, 10, 31, 15, 0)), random = Random(it)).line.id }
        assertThat(halloween).containsAtLeast("halloween-treat", "halloween-ghost")
    }

    @Test
    fun `the same moment and seed pick the same line, and other seeds others`() {
        val context = at(friday, name = "Bennett")
        val seed = Greetings.seed(millis(friday), listOf("any-hey"))
        val first = Greetings.pick(context, listOf("any-hey"), Random(seed))
        repeat(10) { assertThat(Greetings.pick(context, listOf("any-hey"), Random(seed))).isEqualTo(first) }
        assertThat(Greetings.seed(millis(friday), emptyList())).isNotEqualTo(seed)
        assertThat(Greetings.seed(millis(friday) + 60_000, listOf("any-hey"))).isNotEqualTo(seed)
        val spread = (0 until 60).map { Greetings.pick(context, random = Random(Greetings.seed(millis(friday) + it * 60_000L, emptyList()))).line.id }.toSet()
        assertThat(spread.size).isAtLeast(20)
    }

    @Test
    fun `lines lately shown are kept off the next picks`() {
        var log = GreetingLog()
        val shown = mutableListOf<String>()
        var now = millis(friday)
        repeat(60) {
            val pick = Greetings.pick(at(friday), log.recent, Random(Greetings.seed(now, log.recent)))
            assertWithMessage("pick %s", it).that(pick.line.id).isNotIn(shown.takeLast(Greetings.HISTORY))
            shown += pick.line.id
            log = log.shown(pick.line.id, now, utc)
            now += 10 * 60_000L
        }
        assertThat(log.recent).hasSize(Greetings.HISTORY)
        assertThat(log.recent).isEqualTo(shown.takeLast(Greetings.HISTORY))
    }

    @Test
    fun `when every fitting line was lately shown, the one shown longest ago comes back`() {
        val lines = listOf(GreetingLine("a", "A."), GreetingLine("b", "B."), GreetingLine("c", "C."))
        repeat(20) { seed ->
            assertThat(Greetings.pick(at(friday), listOf("b", "c", "a"), Random(seed), lines).line.id).isEqualTo("b")
            assertThat(Greetings.pick(at(friday), listOf("x", "c", "a"), Random(seed), lines).line.id).isEqualTo("b")
        }
    }

    @Test
    fun `visits make the rhythm - first ever, back soon, the day's first, a streak, a long time away`() {
        val start = millis(LocalDateTime.of(2026, 10, 2, 9, 0))
        val minute = 60_000L
        val day = 24 * 60 * minute
        var log = GreetingLog()
        assertThat(log.rhythm(start, utc)).isEqualTo(GreetingRhythm(firstEver = true, firstToday = true, streakDays = 1))

        log = log.shown("a", start, utc)
        assertThat(log.rhythm(start + 2 * minute, utc)).isEqualTo(GreetingRhythm(streakDays = 1, backSoon = true))
        assertThat(log.rhythm(start + 5 * minute, utc)).isEqualTo(GreetingRhythm(streakDays = 1))

        log = log.shown("b", start + day, utc)
        log = log.shown("c", start + 2 * day, utc)
        val third = log.rhythm(start + 3 * day, utc)
        assertThat(third.firstToday).isTrue()
        assertThat(third.awayDays).isEqualTo(1)
        assertThat(third.streakDays).isEqualTo(4)
        // A second visit the same day keeps the streak, and is no longer the day's first.
        log = log.shown("d", start + 3 * day, utc)
        assertThat(log.rhythm(start + 3 * day + 6 * 60 * minute, utc)).isEqualTo(GreetingRhythm(streakDays = 4))
        assertThat(log.shown("e", start + 3 * day + 60 * minute, utc).streakDays).isEqualTo(4)

        val away = log.rhythm(start + 10 * day, utc)
        assertThat(away).isEqualTo(GreetingRhythm(firstToday = true, awayDays = 7, streakDays = 1))
        assertThat(log.shown("f", start + 10 * day, utc).streakDays).isEqualTo(1)
    }

    @Test
    fun `the rhythm's lines speak only on the visits they are about`() {
        fun ids(rhythm: GreetingRhythm) = GreetingLines.all.filter { it.kind == GreetingKind.Rhythm && it.fits(at(friday).copy(rhythm = rhythm)) }.map { it.id }
        assertThat(ids(GreetingRhythm())).isEmpty()
        assertThat(ids(GreetingRhythm(firstEver = true, firstToday = true))).containsExactly("first-ever-hello", "first-ever-chat")
        assertThat(ids(GreetingRhythm(firstToday = true, awayDays = 1, streakDays = 2))).containsExactly("first-today-welcome", "first-today-again", "first-today-at-it", "first-today-pick-up")
        assertThat(ids(GreetingRhythm(firstToday = true, awayDays = 1, streakDays = 7))).containsAtLeast("streak-hot", "streak-day", "streak-week")
        assertThat(ids(GreetingRhythm(firstToday = true, awayDays = 4))).containsExactly("away-long-time", "away-days")
        assertThat(ids(GreetingRhythm(firstToday = true, awayDays = 30))).containsAtLeast("away-look", "away-repo")
        assertThat(ids(GreetingRhythm(backSoon = true))).containsExactly("back-soon-forgot", "back-soon-round-two", "back-soon-quick")
        val away = GreetingLines.all.first { it.id == "away-days" }
        assertThat(away.render(at(friday).copy(rhythm = GreetingRhythm(firstToday = true, awayDays = 9)))).isEqualTo("Nine days away. Missed you.")
    }

    @Test
    fun `the chats' moments come from the list`() {
        val now = millis(friday)
        val minute = 60_000L
        fun agent(id: String, createdAgo: Long, updatedAgo: Long, status: RunStatus = RunStatus.FINISHED) = Agent(
            id = id, name = id, lifecycle = AgentLifecycle.IDLE, runStatus = status, envType = EnvType.CLOUD, envName = null,
            url = "https://cursor.com/agents/$id", createdAtMillis = now - createdAgo, updatedAtMillis = now - updatedAgo,
            latestRunId = null, repoUrl = null, startingRef = null,
        )
        fun row(agent: Agent, pr: PullRequestState? = null) = AgentRow(agent = agent, indicator = AgentIndicator.Read, isPinned = false, isUnread = false, launchedFromThisDevice = false, pullRequest = pr)
        val fresh = agent("fresh", createdAgo = 30 * minute, updatedAgo = 5 * minute)
        val older = agent("older", createdAgo = 20 * 60 * minute, updatedAgo = 40 * minute)
        val running = agent("running", createdAgo = 10 * minute, updatedAgo = minute, status = RunStatus.RUNNING)
        val moments = GreetingMoments.of(1, listOf(fresh, older, running), listOf(row(older, PullRequestState.Merged)), listOf(row(running)), now, utc)
        assertThat(moments).isEqualTo(GreetingMoments(running = 1, justFinished = true, prMerged = true, newProject = true, chatsToday = 2))

        val quiet = GreetingMoments.of(0, listOf(older), listOf(row(older, PullRequestState.Open)), emptyList(), now, utc)
        assertThat(quiet).isEqualTo(GreetingMoments(chatsToday = 0))
    }

    @Test
    fun `day-of-week lines keep to their days`() {
        val fridayLines = GreetingLines.all.filter { it.cue is Cue.On && it.fits(at(friday)) }.map { it.id }
        assertThat(fridayLines).containsAtLeast("friday-deploy", "friday-happy", "friday-sleep", "friday-merge")
        assertThat(fridayLines).doesNotContain("friday-last-pr")
        val saturday = at(friday.plusDays(1).withHour(9))
        assertThat(saturday.at.dayOfWeek).isEqualTo(DayOfWeek.SATURDAY)
        assertThat(GreetingLines.all.filter { it.cue is Cue.On && it.fits(saturday) }.map { it.id })
            .containsExactly("saturday-side-quest", "saturday-standups", "saturday-morning", "weekend-mode", "weekend-fun", "weekend-build")
    }
}
