package com.example.SilentSOS

import fi.iki.elonen.NanoHTTPD

class EmergencyServer(port: Int) : NanoHTTPD(port) {

    // Stores messages sent by the helper, so we can show them later in the app
    val messages = mutableListOf<String>()

    override fun serve(session: IHTTPSession): Response {
        return when (session.method) {
            Method.GET -> {
                // Serve the chat page
                newFixedLengthResponse(Response.Status.OK, "text/html", htmlPage())
            }
            Method.POST -> {
                // Helper sent a message
                val files = HashMap<String, String>()
                session.parseBody(files)
                val body = files["postData"] ?: ""
                val message = extractMessage(body)
                if (message.isNotBlank()) {
                    messages.add(message)
                }
                newFixedLengthResponse(Response.Status.OK, "text/plain", "received")
            }
            else -> newFixedLengthResponse(Response.Status.METHOD_NOT_ALLOWED, "text/plain", "Not allowed")
        }
    }

    private fun extractMessage(body: String): String {
        // body looks like: message=Hello+there
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
                </script>
            </body>
            </html>
        """.trimIndent()
    }
}