package dev.otto.phone.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.otto.phone.ui.components.OttoButton
import dev.otto.phone.ui.components.Wordmark
import dev.otto.phone.ui.theme.OttoTheme

/** The prominent disclosure Play requires before the accessibility service can be enabled. The
 *  words are unchanged; only the type is new. */
@Composable
fun DisclosureScreen(onAccept: () -> Unit) {
    val c = OttoTheme.colors
    val body = OttoTheme.type.ui.copy(fontSize = 15.5.sp, lineHeight = 24.sp, color = c.dim)
    Column(
        Modifier.fillMaxSize().background(c.bg).verticalScroll(rememberScrollState()).padding(horizontal = 21.dp, vertical = 21.dp),
        verticalArrangement = Arrangement.spacedBy(13.dp),
    ) {
        Wordmark(Modifier.padding(bottom = 8.dp))
        Text("Before Otto can help", style = OttoTheme.type.answer.copy(fontSize = 26.sp, lineHeight = 34.sp), modifier = Modifier.semantics { heading() })
        Text("Otto uses Android's accessibility service to carry out what you ask for in this chat. While a request runs it:", style = body.copy(color = c.ink))
        Text("• reads the screen of the app in front (its text, buttons and fields);", style = body)
        Text("• taps, types and scrolls on your behalf;", style = body)
        Text("• takes a screenshot when the screen's text is not enough (never of a payment, sign-in or protected screen), and briefly checks each new screen for protected content — that check's picture is discarded unseen;", style = body)
        Text("• sends what it reads and sees to the AI provider you configured, only for the current request.", style = body)
        Text("It never acts inside payment or banking apps, never enters PINs, OTPs, CVVs or passwords, and stops at any payment step for you to complete. Nothing is read when no request is running.", style = body.copy(color = c.ink))
        Text("Accessibility data is used only to perform your requests and is not shared with anyone else.", style = OttoTheme.type.meta.copy(lineHeight = 18.sp))
        OttoButton("I understand, continue", onAccept, Modifier.fillMaxWidth().padding(top = 8.dp).testTag("disclosure_accept"))
    }
}
