package com.example.rjlmulticomsg_proclientportal.ui.location

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.rjlmulticomsg_proclientportal.BuildConfig
import com.example.rjlmulticomsg_proclientportal.data.repo.PortalRepository
import com.example.rjlmulticomsg_proclientportal.domain.model.SessionState
import com.example.rjlmulticomsg_proclientportal.ui.components.MulticomTopBar
import com.example.rjlmulticomsg_proclientportal.ui.theme.MulticomPageBg
import com.example.rjlmulticomsg_proclientportal.ui.theme.MulticomRed
import com.example.rjlmulticomsg_proclientportal.ui.theme.TextMuted
import com.google.android.gms.maps.model.CameraPosition
import com.google.android.gms.maps.model.LatLng
import com.google.maps.android.compose.GoogleMap
import com.google.maps.android.compose.MapProperties
import com.google.maps.android.compose.MapType
import com.google.maps.android.compose.MapUiSettings
import com.google.maps.android.compose.Marker
import com.google.maps.android.compose.MarkerState
import com.google.maps.android.compose.rememberCameraPositionState
import java.text.DateFormat
import java.util.Date

@Composable
fun DeviceLocationScreen(
    repository: PortalRepository,
    session: SessionState,
    onBack: () -> Unit
) {
    val context = LocalContext.current
    val accountId = session.account?.id.orEmpty()
    val devices by repository.observeGsmDevices(accountId).collectAsState(initial = emptyList())
    val device = devices.firstOrNull { it.hasLocation } ?: devices.firstOrNull()
    val coordinate = if (device?.hasLocation == true) {
        LatLng(device.latitude!!, device.longitude!!)
    } else {
        null
    }
    val cameraState = rememberCameraPositionState {
        position = CameraPosition.fromLatLngZoom(
            coordinate ?: LatLng(-37.8136, 144.9631),
            if (coordinate == null) 10f else 19f
        )
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MulticomPageBg)
    ) {
        MulticomTopBar(
            title = "Device location",
            deviceName = session.account?.siteName?.ifBlank { "SG-PRO" } ?: "SG-PRO",
            onBack = onBack
        )

        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
        ) {
            when {
                !BuildConfig.MAPS_CONFIGURED -> LocationUnavailable(
                    title = "Google Maps key required",
                    message = "Add a restricted MAPS_API_KEY to local.properties for the live satellite map."
                )
                coordinate == null -> LocationUnavailable(
                    title = "Waiting for GNSS",
                    message = "Fit the SIM7600 GNSS antenna and wait for the controller’s next valid fix."
                )
                else -> {
                    GoogleMap(
                        modifier = Modifier.fillMaxSize(),
                        cameraPositionState = cameraState,
                        properties = MapProperties(mapType = MapType.SATELLITE),
                        uiSettings = MapUiSettings(
                            compassEnabled = true,
                            zoomControlsEnabled = true,
                            mapToolbarEnabled = false
                        )
                    ) {
                        Marker(
                            state = MarkerState(coordinate),
                            title = device?.deviceName?.ifBlank { "SG-PRO controller" },
                            snippet = session.account?.siteName
                        )
                    }
                }
            }

            Column(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .padding(14.dp)
                    .background(
                        color = Color.White.copy(alpha = 0.96f),
                        shape = RoundedCornerShape(18.dp)
                    )
                    .padding(16.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(
                            device?.deviceName?.ifBlank { "SG-PRO GSM Gate" }
                                ?: "No controller reported",
                            fontWeight = FontWeight.Bold,
                            fontSize = 17.sp
                        )
                        Text(
                            when {
                                device == null -> "Waiting for controller"
                                device.isOffline() -> "Offline"
                                else -> "Online"
                            },
                            color = if (device?.isOffline() == false) {
                                Color(0xFF18794E)
                            } else {
                                MulticomRed
                            },
                            fontWeight = FontWeight.SemiBold,
                            fontSize = 13.sp
                        )
                    }
                    device?.signalStrength?.let {
                        Text(
                            "Signal $it/31",
                            color = TextMuted,
                            fontWeight = FontWeight.SemiBold
                        )
                    }
                }
                Spacer(Modifier.height(6.dp))
                Text(
                    when {
                        coordinate != null -> {
                            val age = if (device?.gnssCapturedAt ?: 0L > 0) {
                                DateFormat.getDateTimeInstance(
                                    DateFormat.SHORT,
                                    DateFormat.SHORT
                                ).format(Date(device!!.gnssCapturedAt))
                            } else {
                                "time unavailable"
                            }
                            "SIM7600 GNSS · ${"%.6f".format(coordinate.latitude)}, " +
                                "${"%.6f".format(coordinate.longitude)} · $age"
                        }
                        else -> session.account?.address.orEmpty().ifBlank {
                            "No location has been reported."
                        }
                    },
                    color = TextMuted,
                    fontSize = 12.sp
                )
                if (coordinate != null) {
                    Spacer(Modifier.height(10.dp))
                    Button(
                        onClick = {
                            val uri = Uri.parse(
                                "geo:${coordinate.latitude},${coordinate.longitude}" +
                                    "?q=${coordinate.latitude},${coordinate.longitude}" +
                                    "(SG-PRO+Controller)"
                            )
                            context.startActivity(Intent(Intent.ACTION_VIEW, uri))
                        },
                        modifier = Modifier.fillMaxWidth(),
                        colors = ButtonDefaults.buttonColors(containerColor = MulticomRed)
                    ) {
                        Text("Open in Google Maps", fontWeight = FontWeight.Bold)
                    }
                }
            }
        }
    }
}

@Composable
private fun LocationUnavailable(title: String, message: String) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFF101923))
            .padding(34.dp),
        contentAlignment = Alignment.Center
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                title,
                color = Color.White,
                fontWeight = FontWeight.Bold,
                fontSize = 21.sp
            )
            Spacer(Modifier.height(8.dp))
            Text(
                message,
                color = Color(0xFFBBC5D1),
                textAlign = TextAlign.Center,
                lineHeight = 20.sp
            )
        }
    }
}
