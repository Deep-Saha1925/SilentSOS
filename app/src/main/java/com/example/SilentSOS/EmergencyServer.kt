package com.example.SilentSOS

import fi.iki.elonen.NanoHTTPD

class EmergencyServer(
    port: Int,
    private val onHelperConnected: () -> Unit,
    private val onMessageReceived: (String) -> Unit
) : NanoHTTPD(port) {

    // Messages from the victim (app) to show to the helper (browser)
    @Volatile
    var latestVictimReply: String = ""

    private var helperHasConnected = false

    override fun serve(session: IHTTPSession): Response {
        return when {
            session.method == Method.GET && session.uri == "/poll" -> {
                // Browser asks: "any new reply from the victim?"
                newFixedLengthResponse(Response.Status.OK, "text/plain", latestVictimReply)
            }
            session.method == Method.GET -> {
                if (!helperHasConnected) {
                    helperHasConnected = true
                    onHelperConnected()
                }
                newFixedLengthResponse(Response.Status.OK, "text/html", htmlPage())
            }
            session.method == Method.POST -> {
                val files = HashMap<String, String>()
                session.parseBody(files)
                val body = files["postData"] ?: ""
                val message = extractMessage(body)
                if (message.isNotBlank()) {
                    onMessageReceived(message)
                }
                newFixedLengthResponse(Response.Status.OK, "text/plain", "received")
            }
            else -> newFixedLengthResponse(Response.Status.METHOD_NOT_ALLOWED, "text/plain", "Not allowed")
        }
    }

    private fun extractMessage(body: String): String {
        return body.substringAfter("message=", "")
            .replace("+", " ")
    }

    private fun htmlPage(): String {
        return """
            <html>
            <head>
                <title>Emergency Help</title>
                <meta name="viewport" content="width=device-width, initial-scale=1">
                <style>
                    body { font-family: sans-serif; padding: 20px; background: #111; color: #eee; }
                    h2 { color: #ff5555; }
                    input, button { font-size: 18px; padding: 10px; margin-top: 10px; width: 100%; box-sizing: border-box; }
                    button { background: #ff5555; color: white; border: none; border-radius: 6px; }
                    #replyBox { margin-top: 20px; padding: 12px; background: #222; border-radius: 6px; min-height: 40px; }
                </style>
            </head>
            <body>
                <h2>🚨 Emergency Contact</h2>
                <p>You are connected to someone who may need help. Send a message below.</p>
                <form id="msgForm">
                    <input type="text" id="msgInput" placeholder="Type your message..." required>
                    <button type="submit">Send</button>
                </form>
                <p id="status"></p>

                <div id="replyBox">Waiting for reply...</div>

                <script>
                    document.getElementById('msgForm').addEventListener('submit', function(e) {
                        e.preventDefault();
                        var msg = document.getElementById('msgInput').value;
                        fetch('/', {
                            method: 'POST',
                            headers: { 'Content-Type': 'application/x-www-form-urlencoded' },
                            body: 'message=' + encodeURIComponent(msg)
                        }).then(function() {
                            document.getElementById('status').innerText = 'Message sent!';
                            document.getElementById('msgInput').value = '';
                        });
                    });

                    // Check every 2 seconds for a reply from the victim
                    setInterval(function() {
                        fetch('/poll')
                            .then(function(res) { return res.text(); })
                            .then(function(text) {
                                if (text && text.trim().length > 0) {
                                    document.getElementById('replyBox').innerText = text;
                                }
                            });
                    }, 2000);
                </script>
            </body>
            </html>
        """.trimIndent()
    }
}