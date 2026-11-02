package com.eventreminder.app.util

import android.util.Base64
import java.io.BufferedReader
import java.io.BufferedWriter
import java.io.File
import java.io.IOException
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.InetSocketAddress
import java.net.Socket
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.SSLPeerUnverifiedException
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory

/** Sends one email with attachments straight to an SMTP server. Port 465 = implicit SSL, otherwise STARTTLS. */
object Smtp {
    private class Conn(var sock: Socket) {
        lateinit var r: BufferedReader
        lateinit var w: BufferedWriter
        init { bind() }
        fun bind() {
            r = BufferedReader(InputStreamReader(sock.getInputStream(), Charsets.US_ASCII))
            w = BufferedWriter(OutputStreamWriter(sock.getOutputStream(), Charsets.US_ASCII))
        }
        fun reply(): Pair<Int, String> {
            val sb = StringBuilder()
            while (true) {
                val line = r.readLine() ?: throw IOException("Mail server closed the connection")
                sb.appendLine(line)
                if (line.length >= 4 && line[3] == '-') continue
                return (line.take(3).toIntOrNull() ?: 0) to sb.toString().trim()
            }
        }
        fun cmd(s: String, vararg ok: Int): String {
            w.write(s + "\r\n"); w.flush()
            val (code, text) = reply()
            if (code !in ok) throw IOException("Mail server said: $text")
            return text
        }
    }

    private fun secure(plain: Socket, host: String, port: Int): SSLSocket {
        val ssl = (SSLSocketFactory.getDefault() as SSLSocketFactory).createSocket(plain, host, port, true) as SSLSocket
        ssl.startHandshake()
        if (!HttpsURLConnection.getDefaultHostnameVerifier().verify(host, ssl.session))
            throw SSLPeerUnverifiedException("Server certificate does not match $host")
        return ssl
    }

    private fun b64(s: String) = Base64.encodeToString(s.toByteArray(Charsets.UTF_8), Base64.NO_WRAP)

    fun send(
        host: String, port: Int, user: String, pass: String,
        from: String, to: String, subject: String, body: String, files: List<File>
    ) {
        val plain = Socket().apply { connect(InetSocketAddress(host, port), 15_000); soTimeout = 30_000 }
        val c = Conn(if (port == 465) secure(plain, host, port) else plain)
        try {
            c.reply().let { if (it.first != 220) throw IOException("Mail server said: ${it.second}") }
            c.cmd("EHLO eventreminder", 250)
            if (port != 465) {
                c.cmd("STARTTLS", 220)
                c.sock = secure(c.sock, host, port)
                c.bind()
                c.cmd("EHLO eventreminder", 250)
            }
            c.cmd("AUTH LOGIN", 334)
            c.cmd(b64(user), 334)
            try { c.cmd(b64(pass), 235) }
            catch (e: IOException) {
                throw IOException("Login failed. Check the username and password (Gmail and Yahoo need an App Password).")
            }
            c.cmd("MAIL FROM:<$from>", 250)
            c.cmd("RCPT TO:<$to>", 250, 251)
            c.cmd("DATA", 354)
            c.w.write(message(from, to, subject, body, files))
            c.w.write("\r\n.\r\n"); c.w.flush()
            c.reply().let { if (it.first != 250) throw IOException("Mail server said: ${it.second}") }
            runCatching { c.cmd("QUIT", 221) }
        } finally {
            runCatching { c.sock.close() }
        }
    }

    private fun chunk(b64: String) = b64.chunked(76).joinToString("\r\n")

    private fun message(from: String, to: String, subject: String, body: String, files: List<File>): String {
        val b = "=_evtrem_${System.nanoTime()}"
        val date = SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss Z", Locale.US).format(Date())
        val sb = StringBuilder()
        sb.append("From: <$from>\r\nTo: <$to>\r\nSubject: =?UTF-8?B?${b64(subject)}?=\r\nDate: $date\r\n")
        sb.append("MIME-Version: 1.0\r\nContent-Type: multipart/mixed; boundary=\"$b\"\r\n\r\n")
        sb.append("--$b\r\nContent-Type: text/plain; charset=UTF-8\r\nContent-Transfer-Encoding: base64\r\n\r\n")
        sb.append(chunk(b64(body))).append("\r\n")
        for (f in files) {
            sb.append("--$b\r\nContent-Type: application/octet-stream; name=\"${f.name}\"\r\n")
            sb.append("Content-Transfer-Encoding: base64\r\nContent-Disposition: attachment; filename=\"${f.name}\"\r\n\r\n")
            sb.append(chunk(Base64.encodeToString(f.readBytes(), Base64.NO_WRAP))).append("\r\n")
        }
        sb.append("--$b--")
        return sb.toString()   // base64 never starts a line with '.', so no dot-stuffing is needed
    }
}
