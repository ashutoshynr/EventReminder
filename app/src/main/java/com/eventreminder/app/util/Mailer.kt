package com.eventreminder.app.util

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.time.LocalDate

/** Single entry point for "email my events": mail app or direct SMTP, depending on Settings. */
object Mailer {
    /** Returns a message to show the user, or null if nothing needs to be said. */
    suspend fun send(ctx: Context): String? {
        val email = Prefs.email(ctx).trim()
        if (!email.contains("@")) return "Enter your email address in Settings first."
        if (!Prefs.directMode(ctx)) return Exporter.sendViaApp(ctx, email)

        val host = Prefs.smtpHost(ctx).trim()
        val port = Prefs.smtpPort(ctx).trim().toIntOrNull()
        val user = Prefs.smtpUser(ctx).trim().ifEmpty { email }
        var pass = Prefs.smtpPass(ctx)
        if (host.isEmpty() || port == null) return "Enter the SMTP server and port in Settings."
        if (pass.isEmpty()) return "Enter the sender's App Password in Settings."
        if (host.contains("gmail", ignoreCase = true)) pass = pass.filterNot { it.isWhitespace() }

        return try {
            withContext(Dispatchers.IO) {
                val (csv, db) = Exporter.build(ctx)
                Smtp.send(
                    host, port, user, pass, from = if (user.contains("@")) user else email, to = email,
                    subject = "My events backup - ${LocalDate.now()}", body = Exporter.BODY, files = listOf(csv, db)
                )
            }
            "✅ Sent to $email"
        } catch (e: UnknownHostException) {
            "❌ No internet connection, or the server name is wrong."
        } catch (e: SocketTimeoutException) {
            "❌ The mail server did not respond in time."
        } catch (e: ConnectException) {
            "❌ Could not connect to the mail server. Check the server and port."
        } catch (e: Exception) {
            "❌ ${e.message ?: "Could not send the email"}"
        }
    }
}
