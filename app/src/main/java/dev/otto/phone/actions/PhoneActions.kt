package dev.otto.phone.actions

import android.Manifest
import android.app.NotificationManager
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.media.AudioManager
import android.net.Uri
import android.os.SystemClock
import android.provider.AlarmClock
import android.provider.CalendarContract
import android.provider.ContactsContract
import android.provider.Settings
import android.view.KeyEvent
import androidx.core.content.FileProvider
import dev.otto.phone.bridge.DeviceException
import dev.otto.phone.guard.PolicyGuard
import dev.otto.phone.log.OttoLog
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.io.File
import java.util.Calendar

/**
 * The actions of [ActionCatalog], done with Android's own APIs and intents -- no screen is read and
 * nothing is tapped. Run with the accessibility service's context: a service the system has bound is
 * allowed to start activities while Otto is in the background.
 *
 * The money guard stands over all of it: the person's switch and a hand-over refuse every action, a
 * target app is judged like any app Otto opens, and an action the person finishes ([Effect.CONFIRM])
 * hands the phone over once its screen is up, so the agent cannot press Send.
 */
class PhoneActions(private val context: Context, private val guard: () -> PolicyGuard?) {

    fun run(name: String, raw: JsonObject): JsonObject {
        val spec = ActionCatalog.spec(name) ?: throw DeviceException("no phone action '$name'", "unsupported")
        val args = spec.validate(raw)
        // No guard, no action: the switch, the hand-over and the app checks all live there.
        val g = guard() ?: throw DeviceException("the phone's guard is not ready yet -- try again in a moment", "failed")
        g.requireActionable(null)
        val result = when (name) {
            "alarm.set" -> setAlarm(args)
            "timer.set" -> setTimer(args)
            "alarm.show" -> start(Intent(AlarmClock.ACTION_SHOW_ALARMS), "opened the alarms")
            "calendar.add" -> addEvent(args)
            "flashlight" -> torch(args.bool("on"))
            "volume" -> volume(args)
            "media" -> media(args.text("command"))
            "dnd" -> dnd(args.text("mode"))
            "panel" -> panel(args.text("name"))
            "sms.compose" -> start(Intent(Intent.ACTION_SENDTO, Uri.parse("smsto:" + ActionRules.phoneNumber(args.text("to"))))
                .putExtra("sms_body", args.text("body")), "wrote the SMS")
            "email.compose" -> email(args)
            "whatsapp.compose" -> whatsapp(args)
            "dial" -> start(Intent(Intent.ACTION_DIAL, Uri.parse("tel:" + ActionRules.phoneNumber(args.text("number")))), "put the number in the dialer")
            "contacts.find" -> findContact(args.text("name"))
            "share" -> share(args, g)
            "open_url" -> start(Intent(Intent.ACTION_VIEW, Uri.parse(ActionRules.webUrl(args.text("url")))), "opened the page")
            "maps" -> start(Intent(Intent.ACTION_VIEW, Uri.parse(ActionRules.mapsUri(args.text("query"), args.bool("navigate")))),
                if (args.bool("navigate")) "started navigation" else "showed the place")
            "clipboard.copy" -> copy(args.text("text"))
            "note.create" -> note(args)
            else -> throw DeviceException("no phone action '$name'", "unsupported")
        }
        if (spec.effect == ActionSpec.Effect.CONFIRM) {
            // The person finishes it: nothing more is done on the phone until they tap Resume.
            g.handedOver = true
            OttoLog.i(TAG, "$name opened for the person to finish; handed over")
            return actionDone("${result.done}; the person finishes it (send, save or call) -- the phone is theirs now", handedOver = true, data = result.data)
        }
        OttoLog.i(TAG, "$name done")
        return actionDone(result.done, data = result.data)
    }

    private data class Result(val done: String, val data: JsonObject? = null)

    private fun start(intent: Intent, done: String): Result {
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        val target = intent.resolveActivity(context.packageManager)?.packageName
            ?: throw DeviceException("no app on this phone can do that", "unsupported")
        guard()?.packageVerdict(target)?.takeIf { it.isNotEmpty() }?.let { throw DeviceException(it, "guard") }
        context.startActivity(intent)
        return Result(done)
    }

    private fun setAlarm(args: ActionSpec.Args): Result {
        val intent = Intent(AlarmClock.ACTION_SET_ALARM)
            .putExtra(AlarmClock.EXTRA_HOUR, args.int("hour"))
            .putExtra(AlarmClock.EXTRA_MINUTES, args.int("minute"))
            .putExtra(AlarmClock.EXTRA_SKIP_UI, true)
        args.textOr("label").takeIf { it.isNotBlank() }?.let { intent.putExtra(AlarmClock.EXTRA_MESSAGE, it) }
        val days = args.list("days").map { DAY_NUMBERS.getValue(it.lowercase()) }
        if (days.isNotEmpty()) intent.putExtra(AlarmClock.EXTRA_DAYS, ArrayList(days))
        return start(intent, "set an alarm for %02d:%02d".format(args.int("hour"), args.int("minute")))
    }

    private fun setTimer(args: ActionSpec.Args): Result {
        val intent = Intent(AlarmClock.ACTION_SET_TIMER)
            .putExtra(AlarmClock.EXTRA_LENGTH, args.int("seconds"))
            .putExtra(AlarmClock.EXTRA_SKIP_UI, true)
        args.textOr("label").takeIf { it.isNotBlank() }?.let { intent.putExtra(AlarmClock.EXTRA_MESSAGE, it) }
        return start(intent, "started a ${args.int("seconds")}-second timer")
    }

    private fun addEvent(args: ActionSpec.Args): Result {
        val (begin, end, allDay) = ActionRules.eventTimes(args.text("start"), args.textOr("end"))
        val intent = Intent(Intent.ACTION_INSERT).setData(CalendarContract.Events.CONTENT_URI)
            .putExtra(CalendarContract.Events.TITLE, args.text("title"))
            .putExtra(CalendarContract.EXTRA_EVENT_BEGIN_TIME, begin)
            .putExtra(CalendarContract.EXTRA_EVENT_END_TIME, end)
            .putExtra(CalendarContract.EXTRA_EVENT_ALL_DAY, allDay)
        args.textOr("location").takeIf { it.isNotBlank() }?.let { intent.putExtra(CalendarContract.Events.EVENT_LOCATION, it) }
        args.textOr("notes").takeIf { it.isNotBlank() }?.let { intent.putExtra(CalendarContract.Events.DESCRIPTION, it) }
        return start(intent, "opened a new event '${args.text("title")}'")
    }

    private fun torch(on: Boolean): Result {
        val cameras = context.getSystemService(CameraManager::class.java)
        val id = cameras.cameraIdList.firstOrNull {
            cameras.getCameraCharacteristics(it).get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true
        } ?: throw DeviceException("this phone has no torch", "unsupported")
        cameras.setTorchMode(id, on)
        return Result("turned the torch ${if (on) "on" else "off"}")
    }

    private fun volume(args: ActionSpec.Args): Result {
        val audio = context.getSystemService(AudioManager::class.java)
        val stream = when (args.text("stream")) {
            "ring" -> AudioManager.STREAM_RING
            "alarm" -> AudioManager.STREAM_ALARM
            "notification" -> AudioManager.STREAM_NOTIFICATION
            "call" -> AudioManager.STREAM_VOICE_CALL
            else -> AudioManager.STREAM_MUSIC
        }
        try {
            when {
                args.has("level") -> {
                    val max = audio.getStreamMaxVolume(stream)
                    audio.setStreamVolume(stream, Math.round(args.int("level") / 100f * max), 0)
                }
                args.has("step") -> audio.adjustStreamVolume(stream, when (args.text("step")) {
                    "up" -> AudioManager.ADJUST_RAISE
                    "down" -> AudioManager.ADJUST_LOWER
                    "mute" -> AudioManager.ADJUST_MUTE
                    else -> AudioManager.ADJUST_UNMUTE
                }, 0)
                else -> throw DeviceException("volume needs a level or a step", "invalid")
            }
        } catch (e: SecurityException) {
            throw DeviceException("Do Not Disturb stops the ${args.text("stream")} volume from changing", "refused")
        }
        val now = audio.getStreamVolume(stream) * 100 / audio.getStreamMaxVolume(stream).coerceAtLeast(1)
        return Result("${args.text("stream")} volume is now $now%")
    }

    private fun media(command: String): Result {
        val code = when (command) {
            "play" -> KeyEvent.KEYCODE_MEDIA_PLAY
            "pause" -> KeyEvent.KEYCODE_MEDIA_PAUSE
            "next" -> KeyEvent.KEYCODE_MEDIA_NEXT
            "previous" -> KeyEvent.KEYCODE_MEDIA_PREVIOUS
            "stop" -> KeyEvent.KEYCODE_MEDIA_STOP
            else -> KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE
        }
        val audio = context.getSystemService(AudioManager::class.java)
        val time = SystemClock.uptimeMillis()
        audio.dispatchMediaKeyEvent(KeyEvent(time, time, KeyEvent.ACTION_DOWN, code, 0))
        audio.dispatchMediaKeyEvent(KeyEvent(time, time, KeyEvent.ACTION_UP, code, 0))
        return Result("sent $command to what is playing")
    }

    private fun dnd(mode: String): Result {
        val notifications = context.getSystemService(NotificationManager::class.java)
        if (!notifications.isNotificationPolicyAccessGranted) {
            context.startActivity(Intent(Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            throw DeviceException("Otto needs Do Not Disturb access: the settings page is open for the person to allow it", "unsupported")
        }
        notifications.setInterruptionFilter(when (mode) {
            "off" -> NotificationManager.INTERRUPTION_FILTER_ALL
            "priority" -> NotificationManager.INTERRUPTION_FILTER_PRIORITY
            "alarms" -> NotificationManager.INTERRUPTION_FILTER_ALARMS
            else -> NotificationManager.INTERRUPTION_FILTER_NONE
        })
        return Result("Do Not Disturb is $mode")
    }

    private fun panel(name: String): Result = start(Intent(when (name) {
        "wifi" -> Settings.Panel.ACTION_WIFI
        "nfc" -> Settings.Panel.ACTION_NFC
        "volume" -> Settings.Panel.ACTION_VOLUME
        "bluetooth" -> Settings.ACTION_BLUETOOTH_SETTINGS
        else -> Settings.Panel.ACTION_INTERNET_CONNECTIVITY
    }), "opened the $name panel")

    private fun email(args: ActionSpec.Args): Result {
        val intent = Intent(Intent.ACTION_SENDTO, Uri.parse("mailto:"))
            .putExtra(Intent.EXTRA_EMAIL, ActionRules.emails(args.text("to")))
        args.textOr("cc").takeIf { it.isNotBlank() }?.let { intent.putExtra(Intent.EXTRA_CC, ActionRules.emails(it)) }
        args.textOr("subject").takeIf { it.isNotBlank() }?.let { intent.putExtra(Intent.EXTRA_SUBJECT, it) }
        args.textOr("body").takeIf { it.isNotBlank() }?.let { intent.putExtra(Intent.EXTRA_TEXT, it) }
        return start(intent, "wrote the email")
    }

    private fun whatsapp(args: ActionSpec.Args): Result {
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(ActionRules.whatsappUrl(args.text("phone"), args.text("text"))))
        for (pkg in listOf("com.whatsapp", "com.whatsapp.w4b")) {
            if (installed(pkg)) { intent.setPackage(pkg); break }
        }
        return start(intent, "wrote the WhatsApp message")
    }

    private fun findContact(name: String): Result {
        if (context.checkSelfPermission(Manifest.permission.READ_CONTACTS) != PackageManager.PERMISSION_GRANTED) {
            throw DeviceException("Otto may not read contacts: the person can allow it in Otto's Settings", "unsupported")
        }
        val found = buildJsonArray {
            context.contentResolver.query(
                ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
                arrayOf(ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME, ContactsContract.CommonDataKinds.Phone.NUMBER),
                "${ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME} LIKE ?", arrayOf("%$name%"),
                "${ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME} LIMIT $MAX_CONTACTS",
            )?.use { c ->
                while (c.moveToNext()) add(buildJsonObject { put("name", c.getString(0) ?: ""); put("phone", c.getString(1) ?: "") })
            }
            context.contentResolver.query(
                ContactsContract.CommonDataKinds.Email.CONTENT_URI,
                arrayOf(ContactsContract.CommonDataKinds.Email.DISPLAY_NAME_PRIMARY, ContactsContract.CommonDataKinds.Email.ADDRESS),
                "${ContactsContract.CommonDataKinds.Email.DISPLAY_NAME_PRIMARY} LIKE ?", arrayOf("%$name%"),
                "${ContactsContract.CommonDataKinds.Email.DISPLAY_NAME_PRIMARY} LIMIT $MAX_CONTACTS",
            )?.use { c ->
                while (c.moveToNext()) add(buildJsonObject { put("name", c.getString(0) ?: ""); put("email", c.getString(1) ?: "") })
            }
        }
        return Result(if (found.isEmpty()) "no contact matches '$name'" else "${found.size} contact detail(s) for '$name'",
            buildJsonObject { put("contacts", found) })
    }

    private fun share(args: ActionSpec.Args, g: PolicyGuard): Result {
        val intent = Intent(Intent.ACTION_SEND)
        if (args.has("path")) {
            val file = ActionRules.conversationFile(args.text("path"), File(context.filesDir, "otto/workspaces"), currentSession)
            val uri = FileProvider.getUriForFile(context, "${context.packageName}.files", file)
            intent.setType(ActionRules.mimeOf(file.name)).putExtra(Intent.EXTRA_STREAM, uri)
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        } else if (args.has("text")) {
            intent.setType("text/plain")
        } else throw DeviceException("share needs a path or text", "invalid")
        args.textOr("text").takeIf { it.isNotBlank() }?.let { intent.putExtra(Intent.EXTRA_TEXT, it) }
        val app = args.textOr("app")
        if (app.isNotBlank()) {
            g.packageVerdict(app).takeIf { it.isNotEmpty() }?.let { throw DeviceException(it, "guard") }
            if (!installed(app)) throw DeviceException("$app is not installed", "failed")
            intent.setPackage(app)
            return start(intent, "offered it to $app")
        }
        return start(Intent.createChooser(intent, "Share"), "opened the share sheet")
    }

    private fun copy(text: String): Result {
        context.getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("otto", text))
        return Result("copied ${text.length} characters")
    }

    private fun note(args: ActionSpec.Args): Result {
        val intent = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, args.text("text"))
        args.textOr("title").takeIf { it.isNotBlank() }?.let { intent.putExtra(Intent.EXTRA_SUBJECT, it) }
        return if (installed(KEEP)) start(intent.setPackage(KEEP), "opened a new Keep note")
        else start(Intent.createChooser(intent, "Save note"), "opened the share sheet for the note")
    }

    private fun installed(pkg: String): Boolean =
        runCatching { context.packageManager.getPackageInfo(pkg, 0) }.isSuccess

    companion object {
        private const val TAG = "OttoAction"
        private const val KEEP = "com.google.android.keep"
        private const val MAX_CONTACTS = 5
        private val DAY_NUMBERS = mapOf(
            "sun" to Calendar.SUNDAY, "mon" to Calendar.MONDAY, "tue" to Calendar.TUESDAY, "wed" to Calendar.WEDNESDAY,
            "thu" to Calendar.THURSDAY, "fri" to Calendar.FRIDAY, "sat" to Calendar.SATURDAY,
        )

        /** The conversation a turn runs in, for actions that name its files (ChatViewModel sets it). */
        @Volatile var currentSession: String = ""

        fun menu(): JsonObject = buildJsonObject {
            put("actions", buildJsonArray { ActionCatalog.specs.forEach { add(it.toJson()) } })
        }
    }
}
