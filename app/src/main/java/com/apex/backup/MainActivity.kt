package com.apex.backup

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            ApexBackupApp()
        }
    }
}

@Composable
private fun ApexBackupApp() {
    MaterialTheme {
        Surface(
            modifier = Modifier.fillMaxSize(),
            color = MaterialTheme.colorScheme.background
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(24.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                Text(
                    text = "Apex Backup",
                    style = MaterialTheme.typography.headlineMedium
                )
                Text(
                    text = "Secure backup. Verified recovery.",
                    style = MaterialTheme.typography.titleMedium
                )
                Card {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Text(
                            text = "Foundation build",
                            style = MaterialTheme.typography.titleSmall
                        )
                        Text(
                            text = "The app shell is ready. Device diagnostics, backup, " +
                                "encryption, and restore features are not implemented yet."
                        )
                    }
                }
            }
        }
    }
}
