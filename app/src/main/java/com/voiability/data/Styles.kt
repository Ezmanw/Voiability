package com.voiability.data

import kotlinx.serialization.Serializable

/**
 * How dictated text should read. [instruction] goes into the polisher's system prompt; the
 * boolean flags are applied deterministically afterwards so they hold even if a small model
 * ignores them.
 */
@Serializable
data class Style(
    val id: String,
    val name: String,
    val emoji: String,
    val instruction: String,
    val lowercase: Boolean = false,
    val dropFinalPeriod: Boolean = false,
    val useLlm: Boolean = true,
    val exampleIn: String = "",
    val exampleOut: String = "",
    val builtIn: Boolean = false,
)

object Styles {
    const val CLEAN = "clean"
    const val CASUAL = "casual"
    const val PRO = "professional"
    const val EMAIL = "email"
    const val NOTES = "notes"
    const val HYPE = "hype"
    const val RAW = "raw"

    val builtIn: List<Style> = listOf(
        Style(
            id = CLEAN, name = "Clean", emoji = "✨", builtIn = true,
            instruction = "Light touch. Fix punctuation, capitalization and grammar. Keep the speaker's own words and tone.",
            exampleIn = "um so i think we should uh push the meeting to thursday no wait friday",
            exampleOut = "I think we should push the meeting to Friday.",
        ),
        Style(
            id = CASUAL, name = "Casual", emoji = "💬", builtIn = true,
            instruction = "Casual texting. Everything lowercase, including \"i\". Relaxed punctuation and no period at the end. Keep slang and contractions.",
            lowercase = true, dropFinalPeriod = true,
            exampleIn = "Um, hey, are you guys still coming tonight? Like, around eight.",
            exampleOut = "hey are you guys still coming tonight? around 8",
        ),
        Style(
            id = PRO, name = "Professional", emoji = "💼", builtIn = true,
            instruction = "Clear, confident and professional. Fix grammar and tighten rambling sentences, but keep every fact.",
            exampleIn = "so basically the the report is gonna be late because uh we're still waiting on the numbers from finance",
            exampleOut = "The report will be late because we're still waiting on the numbers from Finance.",
        ),
        Style(
            id = EMAIL, name = "Email", emoji = "✉️", builtIn = true,
            instruction = "An email body: short paragraphs separated by blank lines, polite and clear. Only include a greeting or sign-off if the speaker said one.",
            exampleIn = "hi sam thanks for sending the deck over i had a look and it looks great one thing can we move the pricing slide earlier thanks",
            exampleOut = "Hi Sam,\n\nThanks for sending the deck over. I had a look and it looks great.\n\nOne thing: can we move the pricing slide earlier?\n\nThanks",
        ),
        Style(
            id = NOTES, name = "Notes", emoji = "📝", builtIn = true,
            instruction = "Concise bullet points, one idea per line, each line starting with \"- \". Drop filler but keep every fact.",
            exampleIn = "ok so we need milk and eggs uh and also call the plumber about the sink and book flights for june",
            exampleOut = "- Buy milk and eggs\n- Call the plumber about the sink\n- Book flights for June",
        ),
        Style(
            id = HYPE, name = "Hype", emoji = "🔥", builtIn = true,
            instruction = "Upbeat and enthusiastic. Add one or two fitting emoji. Keep it short.",
            exampleIn = "we shipped the new version today and the team did amazing",
            exampleOut = "We shipped the new version today and the team did amazing! 🚀🙌",
        ),
        Style(
            id = RAW, name = "Raw", emoji = "🎙️", builtIn = true, useLlm = false,
            instruction = "Exactly what you said, with fillers removed. No AI rewrite.",
        ),
    )

    /** Sensible per-app defaults; users can change them on the Styles screen. */
    val defaultAppStyles: Map<String, String> = mapOf(
        "com.whatsapp" to CASUAL,
        "com.whatsapp.w4b" to CASUAL,
        "org.telegram.messenger" to CASUAL,
        "org.thoughtcrime.securesms" to CASUAL,
        "com.google.android.apps.messaging" to CASUAL,
        "com.samsung.android.messaging" to CASUAL,
        "com.discord" to CASUAL,
        "com.instagram.android" to CASUAL,
        "com.snapchat.android" to CASUAL,
        "com.facebook.orca" to CASUAL,
        "com.google.android.gm" to EMAIL,
        "com.microsoft.office.outlook" to EMAIL,
        "ch.protonmail.android" to EMAIL,
        "com.Slack" to PRO,
        "com.microsoft.teams" to PRO,
        "com.linkedin.android" to PRO,
        "com.google.android.keep" to NOTES,
        "md.obsidian" to NOTES,
    )

    fun newCustom(): Style = Style(
        id = "custom-" + System.currentTimeMillis().toString(36),
        name = "My style",
        emoji = "🎨",
        instruction = "",
    )
}
