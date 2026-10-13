//
// Created by Alexander Nusov
// Copyright (C) 2026 Roles Witch AI
//

package ai.roleswitch.android.data.database

/**
 * TEST SEED — canned conversation lines for [AppDatabase.seedStressMessages].
 *
 * Deliberately uneven: one-word replies next to multi-paragraph answers, code fences,
 * lists, tables and long unbroken URLs, so a seeded thread exercises bubble sizing,
 * markdown rendering and scroll performance instead of 1000 identical rows.
 */
internal object StressSeedText {

    val userLines: List<String> = listOf(
        "hey",
        "morning!",
        "quick question",
        "can you explain that again?",
        "what's the difference between a mutex and a semaphore?",
        "ok that makes sense",
        "hmm, not sure I follow",
        "give me an example",
        "shorter please",
        "thanks!",
        "why does my app freeze when I scroll a long list?",
        "how do I keep a coroutine alive across a config change?",
        "Write a haiku about slow database migrations.",
        "Is it worth paginating a chat screen at 1000 messages, or is that premature?",
        "I have a Room query that returns everything ordered by createdAt. At what point does that become a problem on a mid-range phone?",
        "what about battery impact",
        "does that also apply on iOS?",
        "one more thing",
        "can you summarise the whole thread so far?",
        "no, the other approach",
        "🙂",
        "👍",
        "let's move on",
        "I tried that and it didn't work — the list still jumps to the top after a rotation.",
        "what would you do differently?",
        "any downsides?",
        "how long would that take?",
        "show me the code",
        "does that need a migration?",
        "wait, really?",
        "Here's what I have so far:\n\n```kotlin\nval items by dao.observeAll().collectAsState(emptyList())\n```\n\nWhat's wrong with it?",
        "Compare the two options for me, briefly.",
        "I keep going back and forth on this. On one hand the simple version ships today and I can move on to the parts of the app that users actually notice. On the other hand I know I'll be back here in three months when someone imports their old history and the whole screen locks up for four seconds. Which regret is cheaper?",
        "ok last one for today",
        "good night"
    )

    val assistantLines: List<String> = listOf(
        "Sure.",
        "Of course — happy to help.",
        "Good morning! What are we working on?",
        "Short answer: yes.",
        "Short answer: no, and here's why.",
        "A mutex protects **one** resource and has an owner — whoever locked it must unlock it. " +
            "A semaphore is just a counter: any thread can release it, and it's usually used to " +
            "limit how many workers run at once rather than to guard a single critical section.",
        "Here's a minimal example:\n\n```kotlin\nval semaphore = Semaphore(permits = 4)\n\nsuspend fun fetch(url: String) = semaphore.withPermit {\n    client.get(url)\n}\n```\n\nFour requests in flight, the rest suspend until a permit frees up.",
        "That freeze is almost always work on the main thread. Three usual suspects:\n\n1. Decoding images inline instead of off-thread.\n2. Recomposing the whole list because the items aren't keyed.\n3. A synchronous database read inside the row composable.\n\nStart with a systrace — guessing here is expensive.",
        "Hoist it into a `ViewModel` and collect with `collectAsStateWithLifecycle()`. The " +
            "ViewModel survives the configuration change, so the flow isn't re-subscribed and " +
            "the list keeps its scroll position.",
        "Migrations grind on —\nthe column that was nullable\nnever was, it turns out.",
        "Not premature, but not urgent either. A thousand rows of text is roughly a megabyte; " +
            "Room will hand it over in a few hundred milliseconds and `LazyColumn` only composes " +
            "what's visible. The pain starts when rows carry images, or when the query re-runs on " +
            "every keystroke. I'd ship the simple version and add paging when a profile says to.",
        "Two things are worth separating: the query cost and the render cost. The query is one " +
            "pass over an indexed column, so it scales fine. The render cost depends entirely on " +
            "whether your items have stable keys — without them, an insert at the tail invalidates " +
            "every row below it.\n\nIf you only change one thing, change that.",
        "Battery-wise it's negligible. You're doing a single read, not polling.",
        "Yes — the shared logic is the same on both platforms, so the behaviour matches. The " +
            "difference is the list implementation: `LazyColumn` and `List` have slightly " +
            "different recycling rules, and iOS is more forgiving about tall cells.",
        "| Option | Effort | Risk |\n|---|---|---|\n| Load everything | low | slow on huge threads |\n| Page by 50 | medium | scroll anchoring gets fiddly |\n| Cursor + window | high | best at scale |\n\nFor where you are, the middle row.",
        "I'd do it the boring way first and measure.",
        "The main downside is that you're now maintaining two code paths for the same screen, " +
            "and they'll drift. Whoever fixes a bug in one will forget the other.",
        "Maybe an afternoon, most of it spent on the scroll-position edge cases rather than the query.",
        "Nothing in that snippet is *wrong*, but `collectAsState` restarts the collection on every " +
            "recomposition of the enclosing scope and ignores lifecycle. Prefer:\n\n```kotlin\nval items by viewModel.items.collectAsStateWithLifecycle()\n```",
        "No migration needed — it's a read-side change only.",
        "Really. It surprised me too the first time.",
        "Let me summarise where we've landed:\n\n- The freeze was main-thread work, not the query.\n- Stable keys fixed the scroll jump.\n- Paging is deferred until a profile justifies it.\n- Nothing needs a schema migration.\n\nWant me to write that up as a comment on the ticket?",
        "Ah — I misread which one you meant. Taking the other approach: it trades a bit of memory " +
            "for a much simpler invalidation story, which is usually the right side of that trade.",
        "Neither regret is expensive if you leave a note. Ship the simple version, add a comment " +
            "saying what the limit is and what to do when you hit it, and move on to the parts " +
            "users notice. The version of you in three months mostly needs to not be surprised.",
        "Happy to help. 👋",
        "Good night!",
        "One caveat worth flagging: this assumes the timestamps are unique. If two rows land in " +
            "the same millisecond the order between them is undefined, which shows up as messages " +
            "swapping places on reload.",
        "Reference: https://developer.android.com/reference/kotlin/androidx/compose/foundation/lazy/package-summary#LazyColumn(androidx.compose.ui.Modifier,androidx.compose.foundation.lazy.LazyListState,androidx.compose.foundation.layout.PaddingValues)",
        "Try it and tell me what you see — I'd rather react to the actual numbers than keep guessing.",
        "Makes sense. Anything else?"
    )
}
