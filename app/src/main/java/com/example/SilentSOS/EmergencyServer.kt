package com.example.SilentSOS

import android.util.Log
import fi.iki.elonen.NanoHTTPD
import java.util.Collections

data class ChatMessage(val fromHelper: Boolean, val text: String)

class EmergencyServer(
    port: Int,
    private val onHelperConnected: () -> Unit,
    private val onConversationUpdated: (List<ChatMessage>) -> Unit
) : NanoHTTPD(port) {

    companion object {
        private const val TAG = "SilentSOS_Server"
    }

    private val conversation = Collections.synchronizedList(mutableListOf<ChatMessage>())
    private var helperHasConnected = false

    fun addVictimMessage(text: String) {
        val snapshot: List<ChatMessage>
        synchronized(conversation) {
            conversation.add(ChatMessage(fromHelper = false, text = text))
            snapshot = conversation.toList()
        }
        Log.d(TAG, "Victim added message: $text | total messages: ${snapshot.size}")
        onConversationUpdated(snapshot)
    }

    override fun serve(session: IHTTPSession): Response {
        Log.d(TAG, "Incoming request: method=${session.method} uri=${session.uri}")

        return when {
            session.method == Method.GET && session.uri == "/poll" -> {
                val body = serializeConversation()
                Log.d(TAG, "Poll request served. Body length=${body.length}")
                newFixedLengthResponse(Response.Status.OK, "text/plain", body)
            }
            session.method == Method.GET -> {
                if (!helperHasConnected) {
                    helperHasConnected = true
                    Log.d(TAG, "Helper connected for the first time")
                    onHelperConnected()
                }
                newFixedLengthResponse(Response.Status.OK, "text/html", htmlPage())
            }
            session.method == Method.POST -> {
                try {
                    val files = HashMap<String, String>()
                    session.parseBody(files)
                    val body = files["postData"] ?: ""
                    Log.d(TAG, "POST raw body: '$body'")

                    val message = extractMessage(body)
                    Log.d(TAG, "Extracted message: '$message'")

                    if (message.isNotBlank()) {
                        val snapshot: List<ChatMessage>
                        synchronized(conversation) {
                            conversation.add(ChatMessage(fromHelper = true, text = message))
                            snapshot = conversation.toList()
                        }
                        Log.d(TAG, "Helper message added. Total messages: ${snapshot.size}")
                        onConversationUpdated(snapshot)
                    } else {
                        Log.w(TAG, "Message was blank after extraction, not adding")
                    }

                    newFixedLengthResponse(Response.Status.OK, "text/plain", "received")
                } catch (e: Exception) {
                    Log.e(TAG, "Error handling POST", e)
                    newFixedLengthResponse(Response.Status.INTERNAL_ERROR, "text/plain", "error: ${e.message}")
                }
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
                    #debugLog { margin-top: 20px; font-size: 12px; color: #888; white-space: pre-wrap; }
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
                <div id="debugLog"></div>

                <script>
                    function log(msg) {
                        var d = document.getElementById('debugLog');
                        d.innerText += msg + '\n';
                    }

                    document.getElementById('msgForm').addEventListener('submit', function(e) {
                        e.preventDefault();
                        var input = document.getElementById('msgInput');
                        var msg = input.value;
                        if (!msg || msg.trim().length === 0) { return; }
                        log('Sending: ' + msg);
                        fetch('/', {
                            method: 'POST',
                            headers: { 'Content-Type': 'application/x-www-form-urlencoded' },
                            body: 'message=' + encodeURIComponent(msg)
                        }).then(function(res) {
                            log('Send response status: ' + res.status);
                            return res.text();
                        }).then(function(text) {
                            log('Send response body: ' + text);
                            input.value = '';
                            fetchConversation();
                        }).catch(function(err) {
                            log('Send ERROR: ' + err);
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
                            .then(function(text) {
                                log('Poll got: ' + JSON.stringify(text));
                                renderConversation(text);
                            })
                            .catch(function(err) {
                                log('Poll ERROR: ' + err);
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