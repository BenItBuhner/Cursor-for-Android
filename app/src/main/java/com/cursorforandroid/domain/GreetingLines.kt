package com.cursorforandroid.domain

import com.cursorforandroid.domain.Cue.At
import com.cursorforandroid.domain.Cue.Away
import com.cursorforandroid.domain.Cue.Dated
import com.cursorforandroid.domain.Cue.InSeason
import com.cursorforandroid.domain.Cue.ManyChats
import com.cursorforandroid.domain.Cue.On
import com.cursorforandroid.domain.Cue.Running
import com.cursorforandroid.domain.Cue.Streak
import com.cursorforandroid.domain.DayPart.Afternoon
import com.cursorforandroid.domain.DayPart.EarlyMorning
import com.cursorforandroid.domain.DayPart.Evening
import com.cursorforandroid.domain.DayPart.LateNight
import com.cursorforandroid.domain.DayPart.Lunch
import com.cursorforandroid.domain.DayPart.Morning
import com.cursorforandroid.domain.DayPart.SmallHours
import java.time.DayOfWeek.FRIDAY
import java.time.DayOfWeek.MONDAY
import java.time.DayOfWeek.SATURDAY
import java.time.DayOfWeek.SUNDAY
import java.time.DayOfWeek.THURSDAY
import java.time.DayOfWeek.TUESDAY
import java.time.DayOfWeek.WEDNESDAY

/**
 * Every line the New Chat page's greeting is picked from (see [Greetings.pick]), with when each fits. Short enough for
 * two lines on a phone with a long first name; no emoji. Ids are kept in the device's history, so a line keeps its id
 * when its words are edited, and a removed line's id is not reused.
 */
object GreetingLines {
    private fun line(id: String, template: String, cue: Cue = Cue.Anytime) = GreetingLine(id, template, cue)

    private val weekend = On(setOf(SATURDAY, SUNDAY))

    val all: List<GreetingLine> = listOf(
        // 2-4 a.m.
        line("small-3am", "3 a.m. thoughts, compiled.", At(SmallHours)),
        line("small-owl", "Night owl mode{, name}.", At(SmallHours)),
        line("small-quiet", "The servers are quiet. Your move.", At(SmallHours)),
        line("small-sleep", "Sleep is a feature too.", At(SmallHours)),
        line("small-one-more", "One more commit, then bed?", At(SmallHours)),
        line("small-bugs", "The bugs come out at this hour.", At(SmallHours)),
        line("small-agents", "Agents don't sleep. You should.", At(SmallHours)),
        line("small-still-up", "Still up, {name}?", At(SmallHours)),
        line("small-dark", "Dark mode was made for this.", At(SmallHours, LateNight)),

        // 5-7 a.m.
        line("early-ci", "Up before the CI{, name}?", At(EarlyMorning)),
        line("early-bird", "Early bird gets the green build.", At(EarlyMorning)),
        line("early-coffee", "Coffee first. Then commits.", At(EarlyMorning, Morning)),
        line("early-sunrise", "Sunrise and a clean diff.", At(EarlyMorning)),
        line("early-inbox", "Quiet inbox. Big plans.", At(EarlyMorning)),
        line("early-kettle", "Morning, {name}. Kettle's on.", At(EarlyMorning)),
        line("early-head-start", "Getting a head start?", At(EarlyMorning)),

        // 8-10 a.m.
        line("morning-good", "Good morning, {name}.", At(EarlyMorning, Morning)),
        line("morning-shipping", "What are we shipping today?", At(Morning)),
        line("morning-brewed", "Coffee's brewed. Repo's ready.", At(Morning)),
        line("morning-standup", "Ship something before standup.", At(Morning)),
        line("morning-fresh", "Fresh day, fresh branch.", At(Morning)),
        line("morning-mug", "Morning{, name}. Mug in hand?", At(Morning)),
        line("morning-todo", "Let's knock out that to-do list.", At(Morning)),
        line("morning-menu", "What's on the menu this morning?", At(Morning)),

        // 11 a.m.-1 p.m.
        line("lunch-cooking", "What's cooking, good looking?", At(Lunch)),
        line("lunch-break", "Lunch break or ship break?", At(Lunch)),
        line("lunch-sandwich", "Sandwich in one hand, prompt in the other.", At(Lunch)),
        line("lunch-hungry", "Hungry for a refactor?", At(Lunch)),
        line("lunch-check-in", "Midday check-in{, name}.", At(Lunch)),
        line("lunch-delegate", "Delegate it. Go eat something.", At(Lunch)),
        line("lunch-high-noon", "High noon. Draw your prompt.", At(Lunch)),

        // 2-4 p.m.
        line("afternoon-slump", "Afternoon slump? Hand it off.", At(Afternoon)),
        line("afternoon-tea", "Tea time. Agent time.", At(Afternoon)),
        line("afternoon-push", "Big push before the evening?", At(Afternoon)),
        line("afternoon-focus", "Deep focus o'clock{, name}.", At(Afternoon)),
        line("afternoon-review", "Good hour for a code review.", At(Afternoon)),
        line("afternoon-second-wind", "Second wind incoming.", At(Afternoon)),
        line("afternoon-next", "Hey {name}, what's next?", At(Afternoon)),

        // 5-8 p.m.
        line("evening-wind", "Winding down or warming up?", At(Evening)),
        line("evening-dinner", "Dinner's on. So are the agents.", At(Evening)),
        line("evening-golden", "Golden hour for side projects.", At(Evening)),
        line("evening-one-more", "One more thing before dinner?", At(Evening)),
        line("evening-hello", "Evening, {name}.", At(Evening)),
        line("evening-wrap", "Let's wrap up the day.", At(Evening)),
        line("evening-queue", "Queue it up. Read it after dinner.", At(Evening)),

        // 9 p.m.-1 a.m.
        line("late-coffee", "Late night coffee{, name}?", At(LateNight)),
        line("late-hack", "Late night hacking session?", At(LateNight)),
        line("late-lofi", "Lo-fi beats and a long diff.", At(LateNight)),
        line("late-quick-fix", "Just one quick fix, right?", At(LateNight, SmallHours)),
        line("late-breakfast", "Start it now, review it at breakfast.", At(LateNight)),
        line("late-moonlight", "Moonlight mode{, name}.", At(LateNight)),
        line("late-still", "Still shipping, {name}?", At(LateNight)),
        line("late-gift", "Leave a gift for tomorrow's you.", At(LateNight)),

        // Days of the week
        line("monday-count", "Monday. Let's make it count.", On(MONDAY)),
        line("monday-week", "Fresh week, fresh diff.", On(MONDAY, EarlyMorning, Morning)),
        line("monday-easy", "Monday again{, name}? Easy does it.", On(MONDAY)),
        line("monday-coffee", "Monday morning. Coffee, then code.", On(MONDAY, EarlyMorning, Morning)),
        line("tuesday-doable", "It's Tuesday. Totally doable.", On(TUESDAY)),
        line("tuesday-groove", "Tuesday. Into the groove.", On(TUESDAY)),
        line("wednesday-halfway", "Halfway there{, name}.", On(WEDNESDAY)),
        line("wednesday-hump", "Hump day. Hand it off.", On(WEDNESDAY)),
        line("thursday-almost", "Thursday. Almost Friday.", On(THURSDAY)),
        line("thursday-push", "Thursday push{, name}?", On(THURSDAY)),
        line("friday-deploy", "Friday deploy? Brave.", On(FRIDAY)),
        line("friday-happy", "Happy Friday{, name}.", On(FRIDAY)),
        line("friday-sleep", "Friday. Ship small, sleep well.", On(FRIDAY)),
        line("friday-merge", "Friday afternoon. Merge with care.", On(FRIDAY, Afternoon)),
        line("friday-last-pr", "Weekend's calling. One last PR?", On(FRIDAY, Evening, LateNight)),
        line("saturday-side-quest", "Saturday side quest?", On(SATURDAY)),
        line("saturday-standups", "No standups today.", weekend),
        line("saturday-morning", "Saturday morning tinkering?", On(SATURDAY, EarlyMorning, Morning)),
        line("sunday-tinker", "Sunday tinkering{, name}?", On(SUNDAY)),
        line("sunday-lazy", "Lazy Sunday, busy agents.", On(SUNDAY)),
        line("sunday-ahead", "Getting ahead of Monday?", On(SUNDAY, Evening, LateNight)),
        line("weekend-mode", "Weekend mode{, name}.", weekend),
        line("weekend-fun", "Coding for fun today?", weekend),
        line("weekend-build", "Weekend build?", weekend),

        // Seasons
        line("winter-laptop", "Cold outside. Warm laptop.", InSeason(Season.Winter)),
        line("winter-cocoa", "Hot cocoa and hot fixes.", InSeason(Season.Winter)),
        line("winter-cozy", "Cozy commits{, name}.", InSeason(Season.Winter)),
        line("spring-cleaning", "Spring cleaning the codebase?", InSeason(Season.Spring)),
        line("spring-fresh", "Fresh season, fresh repo.", InSeason(Season.Spring)),
        line("spring-bloom", "Something's blooming in main.", InSeason(Season.Spring)),
        line("summer-hours", "Summer hours{, name}?", InSeason(Season.Summer)),
        line("summer-iced", "Iced coffee and green checks.", InSeason(Season.Summer)),
        line("summer-heat", "Too hot to type. Delegate.", InSeason(Season.Summer)),
        line("autumn-spice", "Pumpkin spice and pull requests.", InSeason(Season.Autumn)),
        line("autumn-leaves", "Leaves are falling. So are bugs.", InSeason(Season.Autumn)),
        line("autumn-sweater", "Sweater weather. Deep work weather.", InSeason(Season.Autumn)),

        // Occasions
        line("new-year-happy", "Happy New Year{, name}!", Dated(Occasion.NewYear)),
        line("new-year-repo", "New year, new repo?", Dated(Occasion.NewYear)),
        line("new-year-week", "First week of the year. Easy start.", Dated(Occasion.NewYearWeek)),
        line("new-year-resolution", "Resolution: fewer TODOs.", Dated(Occasion.NewYearWeek)),
        line("valentines-roses", "Roses are red, tests are green.", Dated(Occasion.Valentines)),
        line("valentines-love", "Show your code some love{, name}.", Dated(Occasion.Valentines)),
        line("leap-day", "A bonus day. Spend it wisely.", Dated(Occasion.LeapDay)),
        line("pi-day", "Pi Day. Irrationally productive?", Dated(Occasion.PiDay)),
        line("pi-day-happy", "Happy Pi Day{, name}.", Dated(Occasion.PiDay)),
        line("april-fools-pranks", "No pranks. Just prompts.", Dated(Occasion.AprilFools)),
        line("april-fools-joke", "This commit is not a joke.", Dated(Occasion.AprilFools)),
        line("may-fourth-fork", "May the fork be with you.", Dated(Occasion.StarWarsDay)),
        line("may-fourth-agents", "These are the agents you're looking for.", Dated(Occasion.StarWarsDay)),
        line("programmers-day", "Happy Programmers' Day{, name}.", Dated(Occasion.ProgrammersDay)),
        line("programmers-day-256", "Day 256. A very round number.", Dated(Occasion.ProgrammersDay)),
        line("spooky-season", "Spooky season. Haunted bugs?", Dated(Occasion.SpookySeason)),
        line("spooky-logs", "Something spooky in the logs?", Dated(Occasion.SpookySeason)),
        line("halloween-treat", "Trick or treat? Ship a treat.", Dated(Occasion.Halloween)),
        line("halloween-ghost", "Boo. It's just a ghost commit.", Dated(Occasion.Halloween)),
        line("thanksgiving", "Grateful for green builds.", Dated(Occasion.Thanksgiving)),
        line("holidays-happy", "Happy holidays{, name}.", Dated(Occasion.WinterHolidays)),
        line("holidays-cozy", "Cozy season. Small commits.", Dated(Occasion.WinterHolidays)),
        line("new-years-eve-last", "Last commit of the year?", Dated(Occasion.NewYearsEve)),
        line("new-years-eve-midnight", "Ship it before midnight.", Dated(Occasion.NewYearsEve)),
        line("friday-13th", "Friday the 13th. Back up your branch.", Dated(Occasion.FridayThe13th)),
        line("month-first", "New month. Clean slate.", Dated(Occasion.FirstOfMonth)),
        line("month-last", "Last day of the month. Close it out.", Dated(Occasion.LastOfMonth)),

        // What the chats are doing
        line("running-one", "An agent's on the job. Want another?", Running(1, 1)),
        line("running-one-company", "One agent busy. Keep it company?", Running(1, 1)),
        line("running-chef", "{Running} agents cooking. You're the chef.", Running(2)),
        line("running-crew", "{Running} agents at work. Nice crew{, name}.", Running(2)),
        line("running-fleet", "The fleet is busy. Add one more?", Running(3)),
        line("running-lots", "{Running} agents running. Look at you go.", Running(5)),
        line("finished-results", "Fresh results are in.", Cue.JustFinished),
        line("finished-next", "One run done. What's next?", Cue.JustFinished),
        line("finished-follow-up", "Finished! Fancy a follow-up?", Cue.JustFinished),
        line("merged-nice", "Merged! Nice one{, name}.", Cue.PrMerged),
        line("merged-landed", "A PR just landed. Celebrate quietly.", Cue.PrMerged),
        line("merged-momentum", "Fresh merge. Ride the momentum.", Cue.PrMerged),
        line("project-smell", "New Project smell.", Cue.NewProject),
        line("project-plans", "A new Project! Big plans{, name}?", Cue.NewProject),
        line("chats-roll", "{Chats} chats today. On a roll.", ManyChats(5)),
        line("chats-busy", "Busy day{, name}. Keep going.", ManyChats(5)),
        line("chats-legendary", "{Chats} chats today. Legendary.", ManyChats(10)),

        // Coming and going
        line("first-ever-hello", "Hello{, name}. Let's build something.", Cue.FirstEver),
        line("first-ever-chat", "First chat? Make it a good one.", Cue.FirstEver),
        line("first-today-welcome", "Welcome back{, name}.", Cue.FirstToday),
        line("first-today-again", "Good to see you again{, name}.", Cue.FirstToday),
        line("first-today-at-it", "Back at it.", Cue.FirstToday),
        line("first-today-pick-up", "Pick up where you left off?", Cue.FirstToday),
        line("away-long-time", "Long time no see{, name}.", Away(3)),
        line("away-days", "{Away} days away. Missed you.", Away(3)),
        line("away-look", "Look who's back!", Away(14)),
        line("away-repo", "The repo missed you{, name}.", Away(14)),
        line("streak-hot", "{Streak} days in a row. Hot streak.", Streak(3)),
        line("streak-day", "Day {streak} of the streak{, name}.", Streak(3)),
        line("streak-week", "A full week straight. Respect.", Streak(7)),
        line("back-soon-forgot", "Back already? Forget something?", Cue.BackSoon),
        line("back-soon-round-two", "Round two?", Cue.BackSoon),
        line("back-soon-quick", "That was quick{, name}.", Cue.BackSoon),

        // Any time
        line("any-building", "What are we building{, name}?"),
        line("any-idea", "Got an idea? Let's hear it."),
        line("any-ship-next", "What should we ship next?"),
        line("any-ready", "Ready when you are{, name}."),
        line("any-await", "Your agents await."),
        line("any-describe", "Describe it. Agents do the rest."),
        line("any-small-prompt", "Small prompt, big diff."),
        line("any-hey", "Hey {name}."),
        line("any-plan", "Hi {name}, what's the plan?"),
        line("any-make", "Let's make something."),
        line("any-move", "What's the move{, name}?"),
        line("any-bug", "Got a bug with your name on it?"),
        line("any-failing", "Somewhere, a test is failing."),
        line("any-ship-again", "Ship it, then ship it again."),
        line("any-typos", "Ideas welcome. Typos too."),
        line("any-cursor", "The cursor is blinking at you."),
        line("any-refactor", "Any refactors on your mind?"),
        line("any-point", "Point us at a problem."),
        line("any-forecast", "Today's forecast: green checks."),
        line("any-bold", "Bold plans or small fixes?"),
        line("any-backlog", "Let's clear that backlog."),
        line("any-duck", "Need a rubber duck{, name}?"),
        line("any-win", "What would make today a win?"),
        line("any-todos", "Let's turn TODOs into DONEs."),
        line("any-big-idea", "Big idea or quick fix?"),
        line("any-standing-by", "Agents standing by."),
        line("any-conflicts", "Merge conflicts fear you{, name}."),
        line("any-tabs", "Tabs, spaces, or agents?"),
        line("any-my-machine", "Works on your machine? Let's check."),
        line("any-hello-world", "Hello, world. Hello, {name}."),
        line("any-boilerplate", "Write the prompt. Skip the boilerplate."),
        line("any-better", "Leave the code better than you found it."),
    )
}
