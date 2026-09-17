package dev.otto.phone.actions

import dev.otto.phone.actions.ActionSpec.Effect
import dev.otto.phone.actions.ActionSpec.Param
import dev.otto.phone.actions.ActionSpec.Param.Type

/** Every action the app offers, by name. Pure, so the tests hold the menu otto is shown. */
object ActionCatalog {
    private fun p(name: String, type: Type, doc: String = "", required: Boolean = true,
                  choices: List<String> = emptyList(), min: Double? = null, max: Double? = null) =
        Param(name, type, doc, required, choices, min, max)

    val WEEKDAYS = listOf("mon", "tue", "wed", "thu", "fri", "sat", "sun")

    private fun group(name: String, specs: List<ActionSpec>) = specs.map { it.copy(group = name) }

    val specs: List<ActionSpec> = group("clock", listOf(
        // -- clock and reminders ------------------------------------------------------------------
        ActionSpec("alarm.set", "set an alarm in the clock app", listOf(
            p("hour", Type.INT, "0-23", min = 0.0, max = 23.0),
            p("minute", Type.INT, "0-59", min = 0.0, max = 59.0),
            p("label", Type.TEXT, required = false),
            p("days", Type.TEXT_LIST, "repeat on these days", required = false, choices = WEEKDAYS),
        )),
        ActionSpec("timer.set", "start a countdown timer", listOf(
            p("seconds", Type.INT, min = 1.0, max = 86_400.0),
            p("label", Type.TEXT, required = false),
        )),
        ActionSpec("alarm.show", "open the clock app's alarms", effect = Effect.READ),
        ActionSpec("calendar.add", "open a new calendar event for the person to save", listOf(
            p("title", Type.TEXT),
            p("start", Type.TEXT, "local date-time, 2026-09-18T15:00, or a date for all day"),
            p("end", Type.TEXT, "local date-time", required = false),
            p("location", Type.TEXT, required = false),
            p("notes", Type.TEXT, required = false),
        ), Effect.CONFIRM),
    )) + group("device", listOf(
        // -- device controls ----------------------------------------------------------------------
        ActionSpec("flashlight", "turn the torch on or off", listOf(p("on", Type.BOOL))),
        ActionSpec("volume", "set a volume (0-100) or step it", listOf(
            p("stream", Type.TEXT, choices = listOf("media", "ring", "alarm", "notification", "call")),
            p("level", Type.INT, "0-100", required = false, min = 0.0, max = 100.0),
            p("step", Type.TEXT, required = false, choices = listOf("up", "down", "mute", "unmute")),
        )),
        ActionSpec("media", "control what is playing", listOf(
            p("command", Type.TEXT, choices = listOf("play", "pause", "toggle", "next", "previous", "stop")),
        )),
        ActionSpec("dnd", "set Do Not Disturb", listOf(
            p("mode", Type.TEXT, choices = listOf("off", "on", "priority", "alarms")),
        )),
        ActionSpec("panel", "open a quick panel for the person to switch (apps cannot switch these)", listOf(
            p("name", Type.TEXT, choices = listOf("internet", "wifi", "bluetooth", "nfc", "volume")),
        ), Effect.CONFIRM),
    )) + group("message", listOf(
        // -- communication (the person sends) -----------------------------------------------------
        ActionSpec("sms.compose", "write an SMS for the person to send", listOf(
            p("to", Type.TEXT, "phone number"), p("body", Type.TEXT),
        ), Effect.CONFIRM),
        ActionSpec("email.compose", "write an email for the person to send", listOf(
            p("to", Type.TEXT, "address, or several separated by commas"),
            p("subject", Type.TEXT, required = false), p("body", Type.TEXT, required = false),
            p("cc", Type.TEXT, required = false),
        ), Effect.CONFIRM),
        ActionSpec("whatsapp.compose", "write a WhatsApp message for the person to send", listOf(
            p("phone", Type.TEXT, "number with country code"), p("text", Type.TEXT),
        ), Effect.CONFIRM),
        ActionSpec("dial", "put a number in the dialer for the person to call", listOf(
            p("number", Type.TEXT),
        ), Effect.CONFIRM),
        ActionSpec("contacts.find", "look up a contact's numbers and emails", listOf(
            p("name", Type.TEXT),
        ), Effect.READ),
    )) + group("files", listOf(
        // -- files and sharing --------------------------------------------------------------------
        ActionSpec("share", "offer a file otto made (documents/...) or text to another app", listOf(
            p("path", Type.TEXT, "a path in this conversation's files", required = false),
            p("text", Type.TEXT, required = false),
            p("app", Type.TEXT, "package to share to", required = false),
        ), Effect.CONFIRM),
        ActionSpec("open_url", "open a web page", listOf(p("url", Type.TEXT, "http or https"))),
        ActionSpec("maps", "show a place, or start navigation", listOf(
            p("query", Type.TEXT, "a place or address"),
            p("navigate", Type.BOOL, required = false),
        )),
        ActionSpec("clipboard.copy", "copy text to the clipboard", listOf(p("text", Type.TEXT))),
        ActionSpec("note.create", "write a note (Keep) for the person to save", listOf(
            p("text", Type.TEXT), p("title", Type.TEXT, required = false),
        ), Effect.CONFIRM),
    ))

    private val byName = specs.associateBy { it.name }

    fun spec(name: String): ActionSpec? = byName[name]
}
