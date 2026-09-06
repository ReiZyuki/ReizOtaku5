package com.example.runner.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.runner.project.ProjectStorageManager

@Composable
fun InstallPipPackageDialog(
    onDismiss: () -> Unit,
    onInstall: (String) -> Unit
) {
    var packageName by remember { mutableStateOf("") }
    var isInstalling by remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = { if (!isInstalling) onDismiss() },
        title = { Text("Install pip package") },
        text = {
            Column {
                Text(
                    text = "Packages are stored in Runner's private package directory and made available to all Python runtimes.",
                    fontSize = 13.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.height(12.dp))
                OutlinedTextField(
                    value = packageName,
                    onValueChange = { packageName = it },
                    label = { Text("Package Name") },
                    placeholder = { Text("e.g. requests, pyTelegramBotAPI, yt-dlp") },
                    singleLine = true,
                    enabled = !isInstalling,
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("pip_package_input")
                )
                if (isInstalling) {
                    Spacer(modifier = Modifier.height(16.dp))
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.Center,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        CircularProgressIndicator(modifier = Modifier.size(20.dp))
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("Installing from PyPI...", fontSize = 13.sp)
                    }
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    if (packageName.isNotBlank()) {
                        isInstalling = true
                        onInstall(packageName.trim())
                    }
                },
                enabled = packageName.isNotBlank() && !isInstalling,
                modifier = Modifier.testTag("install_button")
            ) {
                Text("Install")
            }
        },
        dismissButton = {
            TextButton(
                onClick = onDismiss,
                enabled = !isInstalling
            ) {
                Text("Cancel")
            }
        }
    )
}

@Composable
fun DownloadProjectDialog(
    onDismiss: () -> Unit,
    onDownload: (String) -> Unit
) {
    var projectUrl by remember { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Download Project") },
        text = {
            Column {
                Text(
                    text = "Enter the direct URL of a project archive (ZIP) or web project to download, extract, and run.",
                    fontSize = 13.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.height(12.dp))
                OutlinedTextField(
                    value = projectUrl,
                    onValueChange = { projectUrl = it },
                    label = { Text("Project URL") },
                    placeholder = { Text("https://example.com/project.zip") },
                    singleLine = true,
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("download_url_input")
                )
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    if (projectUrl.isNotBlank()) {
                        onDownload(projectUrl.trim())
                        onDismiss()
                    }
                },
                enabled = projectUrl.isNotBlank(),
                modifier = Modifier.testTag("download_button")
            ) {
                Text("Download")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel")
            }
        }
    )
}

@Composable
fun SettingsDialog(
    storageManager: ProjectStorageManager,
    onDismiss: () -> Unit,
    onClearProject: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Settings") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Project Storage", fontWeight = FontWeight.Bold, fontSize = 14.sp)
                Text(
                    "Working Dir: ${storageManager.activeProjectDir.absolutePath}",
                    fontSize = 12.sp,
                    fontFamily = FontFamily.Monospace,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                Spacer(modifier = Modifier.height(4.dp))
                Text("Private Pip Packages", fontWeight = FontWeight.Bold, fontSize = 14.sp)
                Text(
                    "Path: ${storageManager.pipPackagesDir.absolutePath}",
                    fontSize = 12.sp,
                    fontFamily = FontFamily.Monospace,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                Spacer(modifier = Modifier.height(4.dp))
                Text("Downloads Destination", fontWeight = FontWeight.Bold, fontSize = 14.sp)
                Text(
                    "Path: ${storageManager.normalDownloadDir.absolutePath}",
                    fontSize = 12.sp,
                    fontFamily = FontFamily.Monospace,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                Spacer(modifier = Modifier.height(8.dp))
                Button(
                    onClick = {
                        onClearProject()
                        onDismiss()
                    },
                    shape = RoundedCornerShape(8.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("Clear Active Project Cache")
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text("Done")
            }
        }
    )
}

@Composable
fun AboutDialog(
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("About Runner") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Runner v1.0", fontWeight = FontWeight.Bold, fontSize = 15.sp)
                Text("DETECT → RUN → DISPLAY", fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.primary)
                Text(
                    "Runner is a real local project runner with an embedded local Python runtime, multi-process architecture (:python_backend), Android Binder IPC, WebView JavaScript bridge, and interactive terminal interface.",
                    fontSize = 13.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text("Supported Project Types:", fontWeight = FontWeight.Bold, fontSize = 13.sp)
                Text("• HTML (index.html, style.css, script.js, assets)", fontSize = 12.sp)
                Text("• HTML + Python Backend (index.html + app2.py + TeleBot)", fontSize = 12.sp)
                Text("• Python (main.py, run.py, app.py)", fontSize = 12.sp)
                Text("• runner.json declarative project descriptor", fontSize = 12.sp)
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text("Close")
            }
        }
    )
}
