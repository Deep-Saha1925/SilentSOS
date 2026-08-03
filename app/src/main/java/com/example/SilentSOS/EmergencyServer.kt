package com.example.SilentSOS

import fi.iki.elonen.NanoHTTPD
import java.util.Collections

data class ChatMessage(val fromHelper: Boolean, val text: String)

class EmergencyServer(
    port: Int,
    private val onHelperConnected: () -> Unit,
    private val onConversationUpdated: (List<ChatMessage>) -> Unit
) : NanoHTTPD(port) {

    private val conversation = Collections.synchronizedList(mutableListOf<ChatMessage>())
    private var helperHasConnected = false

    // Called by the victim's app (MainActivity) when they send a reply
    fun addVictimMessage(text: String) {
        val snapshot: List<ChatMessage>
        synchronized(conversation) {
            conversation.add(ChatMessage(fromHelper = false, text = text))
            snapshot = conversation.toList()
        }
        onConversationUpdated(snapshot)
    }

    override fun serve(session: IHTTPSession): Response {
        return when {
            session.method == Method.GET && session.uri == "/poll" -> {
                newFixedLengthResponse(Response.Status.OK, "text/plain", serializeConversation())
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
                    val snapshot: List<ChatMessage>
                    synchronized(conversation) {
                        conversation.add(ChatMessage(fromHelper = true, text = message))
                        snapshot = conversation.toList()
                    }
                    onConversationUpdated(snapshot)
                }
                newFixedLengthResponse(Response.Status.OK, "text/plain", "received")
            }
            else -> newFixedLengthResponse(Response.Status.METHOD_NOT_ALLOWED, "text/plain", "Not allowed")
        }
    }

    private fun serializeConversation(): String {
        val snapshot = synchronized(conversation) { conversation.toList() }
        return snapshot.joinToString("\n") { msg ->
            (if (msg.fromHelper) "H" else "V") + "|" + msg.text.replace("\n", " ")
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
                    #chatBox { margin-top: 20px; padding: 12px; background: #222; border-radius: 6px; min-height: 100px; }
                    .helperMsg { color: #7fd1ff; margin: 6px 0; }
                    .victimMsg { color: #ffd27f; margin: 6px 0; text-align: right; }
                </style>
            </head>
            <body>
                <h2>Emergency Contact</h2>
                <p>You are connected to someone who may need help. Send a message below.</p>
                <form id="msgForm">
                    <input type="text" id="msgInput" placeholder="Type your message..." required autocomplete="off">
                    <button type="submit">Send</button>
                </form>

                <div id="chatBox">Waiting for messages...</div>

                <script>
                    document.getElementById('msgForm').addEventListener('submit', function(e) {
                        e.preventDefault();
                        var input = document.getElementById('msgInput');
                        var msg = input.value;
                        if (!msg || msg.trim().length === 0) { return; }
                        fetch('/', {
                            method: 'POST',
                            headers: { 'Content-Type': 'application/x-www-form-urlencoded' },
                            body: 'message=' + encodeURIComponent(msg)
                        }).then(function() {
                            input.value = '';
                            fetchConversation();
                        }).catch(function(err) {
                            document.getElementById('chatBox').innerHTML += '<div style="color:red">Send failed: ' + err + '</div>';
                        });
                    });

                    function renderConversation(text) {
                        var box = document.getElementById('chatBox');
                        if (!text || text.trim().length === 0) {
                            box.innerHTML = 'Waiting for messages...';
                            return;
                        }
                        var lines = text.split('\n');
                        box.innerHTML = lines.map(function(line) {
                            var isHelper = line.startsWith('H|');
                            var content = line.substring(2);
                            var cls = isHelper ? 'helperMsg' : 'victimMsg';
                            var label = isHelper ? 'You' : 'Victim';
                            return '<div class="' + cls + '"><strong>' + label + ':</strong> ' + content + '</div>';
                        }).join('');
                    }

                    function fetchConversation() {
                        fetch('/poll')
                            .then(function(res) { return res.text(); })
                            .then(renderConversation)
                            .catch(function(err) {
                                document.getElementById('chatBox').innerHTML += '<div style="color:red">Poll failed: ' + err + '</div>';
                            });
                    }

                    setInterval(fetchConversation, 2000);
                    fetchConversation();
                </script>
            </body>
            </html>
        """.trimIndent()
    }
}