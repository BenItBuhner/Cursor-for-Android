package com.cursorforandroid.domain

import kotlinx.serialization.Serializable
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.Month
import java.time.ZoneId
import java.time.temporal.ChronoUnit
import java.time.temporal.TemporalAdjusters
import kotlin.random.Random

/**
 * The line over the New Chat page's composer ("What's cooking, good looking?"), picked from [GreetingLines] for the
 * moment it is shown: the hour, the day, the season and its occasions, what the account's chats are doing, and how
 * the reader has been coming and going. One pick per visit to the page and per [GreetingBucket], never per frame.
 */
object Greetings {
    /** How many of the last lines shown are kept off the next pick. */
    const val HISTORY = 16

    /**
     * How strongly each kind of line is favoured once lines of it fit the moment: what is happening right now, and the
     * day's occasion, over the hour; the hour over what fits any time; the season seldom, as it lasts for months.
     */
    val Weights: Map<GreetingKind, Int> = mapOf(
        GreetingKind.Moment to 40,
        GreetingKind.Occasion to 40,
        GreetingKind.Rhythm to 30,
        GreetingKind.Clock to 30,
        GreetingKind.Anytime to 22,
        GreetingKind.Season to 8,
    )

    /**
     * The line for [context]: of the lines that fit it (those naming the reader only when there is a name), the ones
     * not among [recent] — or, when every one is, the ones shown longest ago — then a kind by [Weights] and a line of
     * that kind, both drawn from [random]. Equal inputs and an equally seeded [random] give the same line.
     */
    fun pick(context: GreetingContext, recent: List<String> = emptyList(), random: Random = Random.Default, lines: List<GreetingLine> = GreetingLines.all): Greeting {
        val fitting = lines.filter { it.fits(context) }
        check(fitting.isNotEmpty()) { "No greeting fits $context" }
        val fresh = fitting.filter { it.id !in recent }
        val pool = fresh.ifEmpty {
            val age = recent.withIndex().associate { (i, id) -> id to i }
            val oldest = fitting.minOf { age[it.id] ?: -1 }
            fitting.filter { (age[it.id] ?: -1) == oldest }
        }
        val byKind = pool.groupBy { it.kind }
        val kinds = GreetingKind.entries.filter { it in byKind }
        val kind = weighted(kinds, random) { Weights.getValue(it) }
        val line = byKind.getValue(kind).let { it[random.nextInt(it.size)] }
        return Greeting(line, line.render(context))
    }

    private fun <T> weighted(items: List<T>, random: Random, weight: (T) -> Int): T {
        var roll = random.nextInt(items.sumOf(weight))
        for (item in items) {
            roll -= weight(item)
            if (roll < 0) return item
        }
        return items.last()
    }

    /**
     * The seed for one pick: the moment and what was shown before it, so that picks a minute apart, or with another
     * history, differ, while a test with the clock pinned picks the same line on every run.
     */
    fun seed(nowMillis: Long, recent: List<String>): Long = nowMillis * 31 + recent.hashCode()
}

/** A line as shown: the template [line] filled in for the moment. */
data class Greeting(val line: GreetingLine, val text: String)

/** What a line is about, which decides how often its kind is picked (see [Greetings.Weights]). */
enum class GreetingKind { Moment, Occasion, Rhythm, Clock, Season, Anytime }

/**
 * One line of the pool. [template] is the text, with these filled in as it is shown:
 *  - `{name}`: the reader's first name; a line carrying it is only picked when there is one.
 *  - `{, name}`: ", Bennett" with a name, nothing without one.
 *  - `{running}`, `{chats}`, `{streak}`, `{away}`: the agents running, today's new chats, the days in a row the page
 *    was opened, the days since it last was — spelled out up to twelve; capitalised (`{Running}`) to start a sentence.
 */
data class GreetingLine(val id: String, val template: String, val cue: Cue = Cue.Anytime) {
    val kind: GreetingKind get() = cue.kind
    val needsName: Boolean get() = NAME in template

    fun fits(context: GreetingContext): Boolean = (!needsName || context.name != null) && cue.fits(context)

    fun render(context: GreetingContext): String = TOKEN.replace(template) { match ->
        when (val token = match.groupValues[1]) {
            "name" -> context.name.orEmpty()
            ", name" -> context.name?.let { ", $it" }.orEmpty()
            else -> {
                val count = when (token.lowercase()) {
                    "running" -> context.running
                    "chats" -> context.chatsToday
                    "streak" -> context.rhythm.streakDays
                    "away" -> context.rhythm.awayDays
                    else -> error("Unknown greeting token {$token} in \"$template\"")
                }
                spelled(count).let { if (token[0].isUpperCase()) it.replaceFirstChar(Char::uppercaseChar) else it }
            }
        }
    }

    private companion object {
        const val NAME = "{name}"
        val TOKEN = Regex("""\{([^}]+)\}""")
    }
}

/** Two to twelve as words ("three agents"), anything else in digits. */
internal fun spelled(n: Int): String = NUMBER_WORDS.getOrNull(n) ?: n.toString()

private val NUMBER_WORDS = listOf("zero", "one", "two", "three", "four", "five", "six", "seven", "eight", "nine", "ten", "eleven", "twelve")

/** The moment a line is picked for. */
data class GreetingContext(
    val at: LocalDateTime,
    /** The reader's first name (see [FirstName]); null leaves the lines naming them out. */
    val name: String? = null,
    /** Seasons run the other way round (see [Hemisphere]). */
    val southern: Boolean = false,
    /** Agents running now. */
    val running: Int = 0,
    /** A chat's run finished in the last few minutes. */
    val justFinished: Boolean = false,
    /** A recent chat's pull request was merged in the last day. */
    val prMerged: Boolean = false,
    /** A Project was started in the last day. */
    val newProject: Boolean = false,
    /** Chats started today. */
    val chatsToday: Int = 0,
    val rhythm: GreetingRhythm = GreetingRhythm(),
) {
    val part: DayPart get() = DayPart.of(at.hour)
    val date: LocalDate get() = at.toLocalDate()
    val season: Season get() = Season.of(at.month, southern)
}

/** How the reader has been coming to the page, from [GreetingLog]. The default is a visit like any other. */
data class GreetingRhythm(
    /** Never opened on this device before (or since a sign-out). */
    val firstEver: Boolean = false,
    /** The day's first visit. */
    val firstToday: Boolean = false,
    /** Whole days since the last visit, on a first visit of the day. */
    val awayDays: Int = 0,
    /** Days in a row with a visit, today's included. */
    val streakDays: Int = 1,
    /** Back within [GreetingLog.BACK_SOON_MS] of the last visit. */
    val backSoon: Boolean = false,
)

/** The hour's part of the day, as the lines know it; late night runs past midnight. */
enum class DayPart(val label: String) {
    SmallHours("2-4 a.m."),
    EarlyMorning("5-7 a.m."),
    Morning("8-10 a.m."),
    Lunch("11 a.m.-1 p.m."),
    Afternoon("2-4 p.m."),
    Evening("5-8 p.m."),
    LateNight("9 p.m.-1 a.m.");

    companion object {
        fun of(hour: Int): DayPart = when (hour) {
            in 2..4 -> SmallHours
            in 5..7 -> EarlyMorning
            in 8..10 -> Morning
            in 11..13 -> Lunch
            in 14..16 -> Afternoon
            in 17..20 -> Evening
            else -> LateNight
        }
    }
}

enum class Season {
    Winter, Spring, Summer, Autumn;

    companion object {
        /** Meteorological seasons: winter is December to February in the north, June to August in the south. */
        fun of(month: Month, southern: Boolean): Season {
            val north = when (month) {
                Month.DECEMBER, Month.JANUARY, Month.FEBRUARY -> Winter
                Month.MARCH, Month.APRIL, Month.MAY -> Spring
                Month.JUNE, Month.JULY, Month.AUGUST -> Summer
                else -> Autumn
            }
            return if (!southern) north else entries[(north.ordinal + 2) % 4]
        }
    }
}

/** Whether the device's time zone is south of the equator, by the zones that are (the rest are taken as north). */
object Hemisphere {
    private val SOUTHERN_PREFIXES = listOf("Australia/", "Antarctica/", "Pacific/Auckland", "Pacific/Chatham", "Pacific/Fiji", "Pacific/Tongatapu", "Pacific/Apia", "Pacific/Noumea", "Pacific/Efate", "Pacific/Tahiti", "Pacific/Easter", "Indian/Mauritius", "Indian/Reunion", "Indian/Antananarivo", "Atlantic/Stanley")
    private val SOUTHERN_ZONES = setOf(
        "America/Sao_Paulo", "America/Santiago", "America/Montevideo", "America/Asuncion", "America/La_Paz", "America/Lima",
        "America/Punta_Arenas", "Africa/Johannesburg", "Africa/Maputo", "Africa/Harare", "Africa/Lusaka", "Africa/Windhoek",
        "Africa/Gaborone", "Africa/Maseru", "Africa/Mbabane", "Africa/Luanda", "Africa/Lubumbashi", "Africa/Blantyre",
        "Africa/Dar_es_Salaam", "Asia/Jakarta", "Asia/Makassar", "Asia/Jayapura", "Asia/Dili", "Pacific/Port_Moresby",
    )

    fun southern(zone: String): Boolean = zone in SOUTHERN_ZONES || zone.startsWith("America/Argentina/") || SOUTHERN_PREFIXES.any { zone.startsWith(it) }
}

/** The days a line keeps to, by date: the season's occasions and the calendar's oddities. */
enum class Occasion(val label: String, private val on: (LocalDate) -> Boolean) {
    NewYear("1 January", { it.monthValue == 1 && it.dayOfMonth == 1 }),
    NewYearWeek("2-7 January", { it.monthValue == 1 && it.dayOfMonth in 2..7 }),
    Valentines("14 February", { it.monthValue == 2 && it.dayOfMonth == 14 }),
    LeapDay("29 February", { it.monthValue == 2 && it.dayOfMonth == 29 }),
    PiDay("14 March", { it.monthValue == 3 && it.dayOfMonth == 14 }),
    AprilFools("1 April", { it.monthValue == 4 && it.dayOfMonth == 1 }),
    StarWarsDay("4 May", { it.monthValue == 5 && it.dayOfMonth == 4 }),
    ProgrammersDay("the year's 256th day", { it.dayOfYear == 256 }),
    SpookySeason("24-30 October", { it.monthValue == 10 && it.dayOfMonth in 24..30 }),
    Halloween("31 October", { it.monthValue == 10 && it.dayOfMonth == 31 }),
    Thanksgiving("fourth Thursday of November", { it == it.with(TemporalAdjusters.dayOfWeekInMonth(4, DayOfWeek.THURSDAY)) && it.monthValue == 11 }),
    WinterHolidays("24-26 December", { it.monthValue == 12 && it.dayOfMonth in 24..26 }),
    NewYearsEve("31 December", { it.monthValue == 12 && it.dayOfMonth == 31 }),
    FridayThe13th("a Friday the 13th", { it.dayOfWeek == DayOfWeek.FRIDAY && it.dayOfMonth == 13 }),
    FirstOfMonth("a month's first day (not 1 January)", { it.dayOfMonth == 1 && it.monthValue != 1 }),
    LastOfMonth("a month's last day (not 31 December)", { it.dayOfMonth == it.lengthOfMonth() && it.monthValue != 12 });

    fun on(date: LocalDate): Boolean = on.invoke(date)
}

/** When a line fits, and so what kind of line it is. */
sealed interface Cue {
    val kind: GreetingKind
    val label: String
    fun fits(c: GreetingContext): Boolean

    data object Anytime : Cue {
        override val kind = GreetingKind.Anytime
        override val label = "any time"
        override fun fits(c: GreetingContext) = true
    }

    data class At(val parts: Set<DayPart>) : Cue {
        constructor(vararg parts: DayPart) : this(parts.toSet())
        override val kind = GreetingKind.Clock
        override val label get() = parts.joinToString(" or ") { "${it.name} (${it.label})" }
        override fun fits(c: GreetingContext) = c.part in parts
    }

    /** On one of [days], and in one of [parts] when given. */
    data class On(val days: Set<DayOfWeek>, val parts: Set<DayPart> = emptySet()) : Cue {
        constructor(day: DayOfWeek, vararg parts: DayPart) : this(setOf(day), parts.toSet())
        override val kind = GreetingKind.Clock
        override val label get() = days.joinToString(" or ") { it.name.lowercase().replaceFirstChar(Char::uppercaseChar) } +
            if (parts.isEmpty()) "" else ", " + parts.joinToString(" or ") { "${it.name} (${it.label})" }
        override fun fits(c: GreetingContext) = c.at.dayOfWeek in days && (parts.isEmpty() || c.part in parts)
    }

    data class InSeason(val season: Season) : Cue {
        override val kind = GreetingKind.Season
        override val label get() = "${season.name} (by hemisphere)"
        override fun fits(c: GreetingContext) = c.season == season
    }

    data class Dated(val occasion: Occasion) : Cue {
        override val kind = GreetingKind.Occasion
        override val label get() = "${occasion.name} (${occasion.label})"
        override fun fits(c: GreetingContext) = occasion.on(c.date)
    }

    /** Between [min] and [max] agents running. */
    data class Running(val min: Int, val max: Int = Int.MAX_VALUE) : Cue {
        override val kind = GreetingKind.Moment
        override val label get() = if (max == Int.MAX_VALUE) "$min+ agents running" else if (min == max) "$min agent${if (min == 1) "" else "s"} running" else "$min-$max agents running"
        override fun fits(c: GreetingContext) = c.running in min..max
    }

    data object JustFinished : Cue {
        override val kind = GreetingKind.Moment
        override val label = "a run finished in the last 15 minutes"
        override fun fits(c: GreetingContext) = c.justFinished
    }

    data object PrMerged : Cue {
        override val kind = GreetingKind.Moment
        override val label = "a recent chat's PR merged in the last day"
        override fun fits(c: GreetingContext) = c.prMerged
    }

    data object NewProject : Cue {
        override val kind = GreetingKind.Moment
        override val label = "a Project started in the last day"
        override fun fits(c: GreetingContext) = c.newProject
    }

    data class ManyChats(val min: Int) : Cue {
        override val kind = GreetingKind.Moment
        override val label get() = "$min+ chats started today"
        override fun fits(c: GreetingContext) = c.chatsToday >= min
    }

    data object FirstEver : Cue {
        override val kind = GreetingKind.Rhythm
        override val label = "first visit on this device"
        override fun fits(c: GreetingContext) = c.rhythm.firstEver
    }

    /** The day's first visit after one yesterday or the day before: a regular coming back. */
    data object FirstToday : Cue {
        override val kind = GreetingKind.Rhythm
        override val label = "first visit today (last one 1-2 days ago)"
        override fun fits(c: GreetingContext) = c.rhythm.firstToday && !c.rhythm.firstEver && c.rhythm.awayDays in 1..2
    }

    data class Away(val minDays: Int) : Cue {
        override val kind = GreetingKind.Rhythm
        override val label get() = "back after $minDays+ days away"
        override fun fits(c: GreetingContext) = c.rhythm.firstToday && !c.rhythm.firstEver && c.rhythm.awayDays >= minDays
    }

    /** The day's first visit, [min] or more days in a row. */
    data class Streak(val min: Int) : Cue {
        override val kind = GreetingKind.Rhythm
        override val label get() = "first visit today, $min+ days in a row"
        override fun fits(c: GreetingContext) = c.rhythm.firstToday && c.rhythm.streakDays >= min
    }

    data object BackSoon : Cue {
        override val kind = GreetingKind.Rhythm
        override val label = "back within 3 minutes"
        override fun fits(c: GreetingContext) = c.rhythm.backSoon
    }
}

/**
 * Which part of the day, and which day, a line was picked for: while it stands the line does, and a new one is picked
 * once it changes, the page still open.
 */
data class GreetingBucket(val date: LocalDate, val part: DayPart) {
    companion object {
        fun of(nowMillis: Long, zone: ZoneId): GreetingBucket {
            val at = LocalDateTime.ofInstant(Instant.ofEpochMilli(nowMillis), zone)
            return GreetingBucket(at.toLocalDate(), DayPart.of(at.hour))
        }
    }
}

/**
 * The page's greetings on this device: the last lines shown, kept off the next picks, and the visits they were shown
 * on, which the [GreetingRhythm] is read from.
 */
@Serializable
data class GreetingLog(
    /** Line ids, oldest first, at most [Greetings.HISTORY]. */
    val recent: List<String> = emptyList(),
    val lastVisitMillis: Long? = null,
    /** Days in a row with a visit, up to [streakEndDay]. */
    val streakDays: Int = 0,
    /** The epoch day of the last visit, in the zone it was made in. */
    val streakEndDay: Long? = null,
) {
    /** The reader's rhythm for a visit at [nowMillis]. */
    fun rhythm(nowMillis: Long, zone: ZoneId): GreetingRhythm {
        val last = lastVisitMillis ?: return GreetingRhythm(firstEver = true, firstToday = true, streakDays = 1)
        val today = epochDay(nowMillis, zone)
        val lastDay = streakEndDay ?: epochDay(last, zone)
        val away = (today - lastDay).coerceAtLeast(0).toInt()
        return GreetingRhythm(
            firstToday = away > 0,
            awayDays = away,
            streakDays = streakAt(today, lastDay),
            backSoon = nowMillis - last in 0 until BACK_SOON_MS,
        )
    }

    /** The log with [lineId] shown on a visit at [nowMillis]. */
    fun shown(lineId: String, nowMillis: Long, zone: ZoneId): GreetingLog {
        val today = epochDay(nowMillis, zone)
        return GreetingLog(
            recent = (recent.filter { it != lineId } + lineId).takeLast(Greetings.HISTORY),
            lastVisitMillis = nowMillis,
            streakDays = if (streakEndDay == null) 1 else streakAt(today, streakEndDay),
            streakEndDay = today,
        )
    }

    private fun streakAt(today: Long, lastDay: Long): Int = when (today - lastDay) {
        0L -> streakDays.coerceAtLeast(1)
        1L -> streakDays + 1
        else -> 1
    }

    companion object {
        const val BACK_SOON_MS = 3 * 60_000L

        private fun epochDay(millis: Long, zone: ZoneId): Long =
            ChronoUnit.DAYS.between(LocalDate.ofEpochDay(0), Instant.ofEpochMilli(millis).atZone(zone).toLocalDate())
    }
}

/** What the account's chats are doing, as far as the greeting cares (see [GreetingContext]). */
data class GreetingMoments(val running: Int = 0, val justFinished: Boolean = false, val prMerged: Boolean = false, val newProject: Boolean = false, val chatsToday: Int = 0) {
    companion object {
        const val JUST_FINISHED_MS = 15 * 60_000L
        const val RECENT_MS = 24 * 60 * 60_000L

        fun of(running: Int, agents: List<Agent>, recentRows: List<AgentRow>, projectRows: List<AgentRow>, nowMillis: Long, zone: ZoneId): GreetingMoments {
            fun within(at: Long, span: Long) = nowMillis - at in 0..span
            val today = Instant.ofEpochMilli(nowMillis).atZone(zone).toLocalDate()
            return GreetingMoments(
                running = running,
                justFinished = agents.any { it.runStatus == RunStatus.FINISHED && within(it.listedAtMillis, JUST_FINISHED_MS) },
                prMerged = recentRows.any { it.pullRequest == PullRequestState.Merged && within(it.agent.listedAtMillis, RECENT_MS) },
                newProject = projectRows.any { within(it.agent.createdAtMillis, RECENT_MS) },
                chatsToday = agents.count { Instant.ofEpochMilli(it.createdAtMillis).atZone(zone).toLocalDate() == today },
            )
        }
    }
}

/**
 * The reader's first name for the greeting, or null when none can be told with confidence, which leaves the lines
 * naming them out. From the account's first name — its first word, a title ("Dr.") skipped — or, failing that, an
 * email whose local part is plainly a name ("bennett.buhner@…"). Never the last name, nor a key's name; never
 * something that only looks like a name ("Demo", "user", "j", "dev42").
 */
object FirstName {
    private val TITLES = setOf("mr", "mrs", "ms", "mx", "miss", "dr", "prof", "sir", "madam", "dame", "rev")
    private val PLACEHOLDERS = setOf(
        "demo", "user", "test", "tester", "admin", "administrator", "cursor", "unknown", "none", "null", "undefined",
        "anonymous", "anon", "guest", "me", "dev", "developer", "root", "name", "first", "firstname", "account", "hello",
        "info", "contact", "support", "team", "noreply", "na",
    )
    private const val MAX = 20

    fun of(user: CursorUser?): String? = user?.let { of(it.firstName, it.email) }

    fun of(firstName: String?, email: String?): String? {
        val given = firstName?.trim().orEmpty()
        if ('@' in given) return fromEmail(given) ?: fromEmail(email)
        val word = given.split(Regex("\\s+")).map { it.trimEnd('.', ',') }.firstOrNull { it.isNotEmpty() && it.lowercase() !in TITLES }
        return word?.let(::clean) ?: fromEmail(email)
    }

    private fun fromEmail(email: String?): String? {
        val local = email?.substringBefore('@')?.substringBefore('+')?.takeIf { email.contains('@') } ?: return null
        val parts = local.split('.', '_', '-').filter { it.isNotEmpty() }
        if (parts.size < 2) return null
        return clean(parts.first())
    }

    private fun clean(word: String): String? {
        if (word.length > MAX) return null
        if (!word.first().isLetter() || !word.all { it.isLetter() || it == '-' || it == '\'' || it == '\u2019' }) return null
        if (word.lowercase() in PLACEHOLDERS) return null
        val latin = word.any { Character.UnicodeScript.of(it.code) == Character.UnicodeScript.LATIN }
        if (latin && word.count(Char::isLetter) < 2) return null
        val uniform = word == word.lowercase() || word == word.uppercase()
        return if (!uniform) word else word.lowercase().split('-').joinToString("-") { part -> part.replaceFirstChar(Char::titlecaseChar) }
    }
}
