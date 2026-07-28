package com.example.rjlmulticomsg_proclientportal

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import com.example.rjlmulticomsg_proclientportal.ui.AppRoot
import com.example.rjlmulticomsg_proclientportal.ui.theme.RJLMulticomSGPROCLIENTPORTALTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val app = application as ClientPortalApp
        setContent {
            RJLMulticomSGPROCLIENTPORTALTheme(useDark = false) {
                Surface(modifier = Modifier.fillMaxSize()) {
                    AppRoot(repository = app.repository)
                }
            }
        }
    }
}
