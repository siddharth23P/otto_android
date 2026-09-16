package dev.otto.phone.protocol

/** Every request the app can make, as `group` (the frame `type`) and `op` (its `op` field, when the
 *  group has several). The reply to a request is `<group>_result`. `key` ("sessions.rename") is how
 *  a feature names one op; the bare group name ("routing") names all of a group's ops. */
enum class Op(val group: String, val op: String? = null) {
    SESSIONS_LIST("sessions", "list"),
    SESSIONS_OPEN("sessions", "open"),
    SESSIONS_TRANSCRIPT("sessions", "transcript"),
    SESSIONS_DELETE("sessions", "delete"),
    SESSIONS_CLOSE("sessions", "close"),
    SESSIONS_RENAME("sessions", "rename"),
    SESSIONS_EXPORT("sessions", "export"),
    SESSIONS_IMPORT("sessions", "import"),
    SESSIONS_USAGE("sessions", "usage"),
    TURN("turn"),
    /** Not a frame: the optional `turn.phone` flag. */
    TURN_PHONE("turn", "phone"),
    ANSWER("answer"),
    CANCEL("cancel"),
    SETUP_STATUS("setup", "status"),
    SETUP_SET_KEY("setup", "set_key"),
    SETUP_PROBE("setup", "probe"),
    DOCTOR("doctor"),
    MODELS("models"),
    ROUTING_LIST("routing", "list"),
    ROUTING_OPTIONS("routing", "options"),
    ROUTING_PIN("routing", "pin"),
    ROUTING_CLEAR("routing", "clear"),
    LESSONS_LIST("lessons", "list"),
    LESSONS_DELETE("lessons", "delete"),
    LESSONS_CLEAR("lessons", "clear"),
    NOTES_LIST("notes", "list"),
    NOTES_GET("notes", "get"),
    NOTES_DELETE("notes", "delete"),
    FILES("files");

    val key: String get() = if (op == null) group else "$group.$op"
    val resultType: String get() = "${group}_result"

    companion object {
        fun of(group: String, op: String?): Op? =
            entries.firstOrNull { it.group == group && it.op == op?.ifBlank { null } }
    }
}

/** What the other end can do. Protocol 1 (every otto before the v2 framing) knows sessions
 *  list/open/transcript/delete and turn/answer/cancel, nothing else, whatever it claims; from
 *  protocol 2 an op is available when `features` names it or its group. */
data class Capabilities(val protocol: Int, val features: Set<String> = emptySet()) {
    fun supports(op: Op): Boolean =
        op in V1_OPS || (protocol >= 2 && (op.key in features || op.group in features))

    /** Request ids are echoed from protocol 2 on; before that, replies are matched by op. */
    val echoesIds: Boolean get() = protocol >= 2

    companion object {
        val V1_OPS: Set<Op> = setOf(
            Op.SESSIONS_LIST, Op.SESSIONS_OPEN, Op.SESSIONS_TRANSCRIPT, Op.SESSIONS_DELETE,
            Op.TURN, Op.ANSWER, Op.CANCEL,
        )
        val V1 = Capabilities(1)

        fun of(hello: Hello): Capabilities = Capabilities(hello.protocolVersion, hello.features.toSet())
    }
}
