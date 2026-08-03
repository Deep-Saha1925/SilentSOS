package com.example.SilentSOS

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.net.wifi.WifiManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.example.SilentSOS.ui.theme.MyApplicationTheme
import com.google.zxing.BarcodeFormat
import com.google.zxing.qrcode.QRCodeWriter
import java.net.NetworkInterface
import java.util.Collections

class MainActivity : ComponentActivity() {

    private lateinit var wifiManager: WifiManager
    private var reservation: WifiManager.LocalOnlyHotspotReservation? = null
    private var server: EmergencyServer? = null

    private var statusText = mutableStateOf("Not started")
    private var qrBitmap = mutableStateOf<Bitmap?>(null)
    private var pageUrl = mutableStateOf("")
    private var urlQrBitmap = mutableStateOf<Bitmap?>(null)

    private var ipsBeforeHotspot: Set<String> = emptySet()

    private var helperConnected = mutableStateOf(false)
    private var conversation = mutableStateOf(listOf<ChatMessage>())
    private var replyInput = mutableStateOf("")

    private val requestPermissions =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { results ->
            val allGranted = results.values.all { it }
            if (allGranted) {
                startHotspot()
            } else {
                statusText.value = "Permissions denied. Hotspot needs location + nearby devices access."
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        wifiManager = applicationContext.getSystemService(WIFI_SERVICE) as WifiManager

        setContent {
            MyApplicationTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(24.dp)
                            .verticalScroll(rememberScrollState()),
                        verticalArrangement = Arrangement.Top
                    ) {
                        val status by statusText
                        val bitmap by qrBitmap
                        val url by pageUrl
                        val urlQr by urlQrBitmap
                        val connected by helperConnected
                        val chatList by conversation
                        val reply by replyInput

                        Text(text = status)

                        Button(onClick = { checkPermissionAndStart() }, modifier = Modifier.padding(top = 16.dp)) {
                            Text("Start Emergency Hotspot")
                        }

                        bitmap?.let {
                            Text(text = "Step 1: Scan to join WiFi", modifier = Modifier.padding(top = 24.dp))
                            Image(
                                bitmap = it.asImageBitmap(),
                                contentDescription = "WiFi QR Code",
                                modifier = Modifier.size(200.dp)
                            )
                        }

                        if (url.isNotBlank()) {
                            Text(text = "Step 2: Scan to open chat page", modifier = Modifier.padding(top = 24.dp))
                            urlQr?.let {
                                Image(
                                    bitmap = it.asImageBitmap(),
                                    contentDescription = "Page URL QR Code",
                                    modifier = Modifier.size(200.dp)
                                )
                            }
                            Text(text = url, style = MaterialTheme.typography.headlineSmall)
                        }

                        if (connected) {
                            Text(
                                text = "Someone connected! You can chat now.",
                                modifier = Modifier.padding(top = 24.dp)
                            )

                            chatList.forEach { msg ->
                                val label = if (msg.fromHelper) "Helper" else "You"
                                Text(text = "$label: ${msg.text}", modifier = Modifier.padding(top = 8.dp))
                            }

                            OutlinedTextField(
                                value = reply,
                                onValueChange = { replyInput.value = it },
                                label = { Text("Type your reply") },
                                modifier = Modifier.padding(top = 16.dp)
                            )

                            Button(
                                onClick = {
                                    if (reply.isNotBlank()) {
                                        server?.addVictimMessage(reply)
                                        replyInput.value = ""
                                    }
                                },
                                modifier = Modifier.padding(top = 8.dp)
                            ) {
                                Text("Send Reply")
                            }
                        }
                    }
                }
            }
        }
    }

    private fun checkPermissionAndStart() {
        val permissionsNeeded = mutableListOf(Manifest.permission.ACCESS_FINE_LOCATION)

        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
            permissionsNeeded.add(Manifest.permission.NEARBY_WIFI_DEVICES)
        }

        val allGranted = permissionsNeeded.all {
            ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED
        }

        if (allGranted) {
            ipsBeforeHotspot = getAllLocalIps()
            startHotspot()
        } else {
            requestPermissions.launch(permissionsNeeded.toTypedArray())
        }
    }

    private fun startHotspot() {
        if (reservation != null) {
            statusText.value = "Hotspot already running. Please restart the app to try again."
            return
        }

        statusText.value = "Starting hotspot..."
        wifiManager.startLocalOnlyHotspot(object : WifiManager.LocalOnlyHotspotCallback() {
            override fun onStarted(res: WifiManager.LocalOnlyHotspotReservation) {
                reservation = res
                val config = res.wifiConfiguration
                val ssid = config?.SSID ?: ""
                val password = config?.preSharedKey ?: ""

                statusText.value = "Hotspot ON\nSSID: $ssid\nPassword: $password"

                val wifiQrText = "WIFI:S:$ssid;T:WPA;P:$password;;"
                qrBitmap.value = generateQrCode(wifiQrText)

                startServer()
            }

            override fun onStopped() {
                statusText.value = "Hotspot stopped"
                qrBitmap.value = null
                pageUrl.value = ""
                urlQrBitmap.value = null
                helperConnected.value = false
                conversation.value = emptyList()
                stopServer()
            }

            override fun onFailed(reason: Int) {
                statusText.value = "Hotspot failed to start (reason: $reason)"
            }
        }, null)
    }

    private fun startServer() {
        try {
            server = EmergencyServer(
                port = 8080,
                onHelperConnected = {
                    runOnUiThread { helperConnected.value = true }
                },
                onConversationUpdated = { updatedList ->
                    runOnUiThread { conversation.value = updatedList }
                }
            )
            server?.start()

            val ip = getHotspotIpAddress()
            val url = "http://$ip:8080"
            pageUrl.value = url
            urlQrBitmap.value = generateQrCode(url)
        } catch (e: Exception) {
            statusText.value = "Server failed to start: ${e.message}"
        }
    }

    private fun stopServer() {
        server?.stop()
        server = null
    }

    private fun getAllLocalIps(): Set<String> {
        val result = mutableSetOf<String>()
        val interfaces = Collections.list(NetworkInterface.getNetworkInterfaces())
        for (intf in interfaces) {
            val addrs = Collections.list(intf.inetAddresses)
            for (addr in addrs) {
                if (!addr.isLoopbackAddress && addr.hostAddress?.contains(":") == false) {
                    result.add(addr.hostAddress ?: "")
                }
            }
        }
        return result
    }

    private fun getHotspotIpAddress(): String {
        val currentIps = getAllLocalIps()
        val newIps = currentIps - ipsBeforeHotspot
        return newIps.firstOrNull() ?: "NOT FOUND - before: $ipsBeforeHotspot, after: $currentIps"
    }

    private fun generateQrCode(text: String, size: Int = 512): Bitmap {
        val writer = QRCodeWriter()
        val bitMatrix = writer.encode(text, BarcodeFormat.QR_CODE, size, size)
        val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.RGB_565)
        for (x in 0 until size) {
            for (y in 0 until size) {
                bmp.setPixel(x, y, if (bitMatrix[x, y]) android.graphics.Color.BLACK else android.graphics.Color.WHITE)
            }
        }
        return bmp
    }

    override fun onDestroy() {
        super.onDestroy()
        stopServer()
        reservation?.close()
    }
}