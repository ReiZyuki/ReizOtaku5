package com.example.runner.ui

import android.annotation.SuppressLint
import android.content.Intent
import android.net.Uri
import android.view.View
import android.view.ViewGroup
import android.webkit.WebSettings
import android.webkit.WebView
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import com.example.runner.bridge.RunnerJavaScriptBridge
import com.example.runner.model.RunState
import com.example.runner.webview.RunnerWebViewClient

@SuppressLint("SetJavaScriptEnabled")
@Composable
fun RunnerMainScreen(
    viewModel: RunnerViewModel
) {
    val runState by viewModel.runState.collectAsState()
    val context = LocalContext.current

    var showMenu by remember { mutableStateOf(false) }
    var showTerminalDialog by remember { mutableStateOf(false) }
    var showPipDialog by remember { mutableStateOf(false) }
    var showDownloadDialog by remember { mutableStateOf(false) }
    var showSettingsDialog by remember { mutableStateOf(false) }
    var showAboutDialog by remember { mutableStateOf(false) }
    var showHelpDialog by remember { mutableStateOf(false) }

    // SAF Folder Picker launcher for "Select Folder"
    val folderPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocumentTree()
    ) { uri: Uri? ->
        if (uri != null) {
            try {
                val takeFlags: Int = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                context.contentResolver.takePersistableUriPermission(uri, takeFlags)
            } catch (_: Exception) {}
            viewModel.onFolderSelected(uri)
        }
    }

    // File picker launcher for "Import Project" (ZIP archives)
    val importZipLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        if (uri != null) {
            try {
                context.contentResolver.openInputStream(uri)?.use { stream ->
                    viewModel.importZip(stream)
                }
            } catch (e: Exception) {
                viewModel.terminalManager.appendLine(
                    com.example.runner.model.TerminalLine("Import error: ${e.message}", isError = true)
                )
            }
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFF0F172A))
    ) {
        // Output / Preview area: occupies essentially the entire screen (Section 43)
        when (val state = runState) {
            is RunState.Loading -> {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center
                ) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center
                    ) {
                        CircularProgressIndicator(
                            color = Color(0xFF38BDF8),
                            modifier = Modifier.size(40.dp)
                        )
                        Spacer(modifier = Modifier.height(16.dp))
                        Text(
                            text = state.message,
                            color = Color(0xFF94A3B8),
                            fontSize = 14.sp
                        )
                    }
                }
            }

            is RunState.EmptyProject -> {
                // Section 6: No Project Found & Select or import a project to get started.
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(32.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center
                    ) {
                        Text(
                            text = "No Project Found",
                            fontSize = 24.sp,
                            fontWeight = FontWeight.Bold,
                            color = Color(0xFFF8FAFC),
                            textAlign = TextAlign.Center
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = "Select or import a project to get started.",
                            fontSize = 14.sp,
                            color = Color(0xFF94A3B8),
                            textAlign = TextAlign.Center
                        )
                    }
                }
            }

            is RunState.UnsupportedProject -> {
                // Section 7: Unsupported Project & No runnable HTML, Python, or supported Runner backend project was detected.
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(32.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center
                    ) {
                        Text(
                            text = "Unsupported Project",
                            fontSize = 24.sp,
                            fontWeight = FontWeight.Bold,
                            color = Color(0xFFF8FAFC),
                            textAlign = TextAlign.Center
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = "No runnable HTML, Python, or supported Runner backend project was detected.",
                            fontSize = 14.sp,
                            color = Color(0xFF94A3B8),
                            textAlign = TextAlign.Center
                        )
                    }
                }
            }

            is RunState.HtmlRunning -> {
                // Section 8, 10, 34: Rendered webpage inside embedded WebView
                AndroidView(
                    modifier = Modifier
                        .fillMaxSize()
                        .testTag("runner_webview"),
                    factory = { ctx ->
                        WebView(ctx).apply {
                            layoutParams = ViewGroup.LayoutParams(
                                ViewGroup.LayoutParams.MATCH_PARENT,
                                ViewGroup.LayoutParams.MATCH_PARENT
                            )

                            settings.apply {
                                javaScriptEnabled = true
                                domStorageEnabled = true
                                allowFileAccess = true
                                allowContentAccess = true
                                loadWithOverviewMode = true
                                useWideViewPort = true
                                databaseEnabled = true
                                mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
                            }

                            // Explicitly use software layer to prevent emulator OpenGL Mesa driver from querying missing /dev/dri/renderD* nodes
                            setLayerType(View.LAYER_TYPE_SOFTWARE, null)

                            // Real JavaScript bridge connection to Runner-managed Python backend runtime
                            val bridge = RunnerJavaScriptBridge(viewModel.backendClient)
                            addJavascriptInterface(bridge, RunnerJavaScriptBridge.JS_INTERFACE_NAME)

                            webViewClient = RunnerWebViewClient(
                                projectDir = state.projectInfo.projectDir,
                                onPageError = { err ->
                                    viewModel.terminalManager.appendLine(
                                        com.example.runner.model.TerminalLine(err, isError = true)
                                    )
                                }
                            )

                            loadUrl("${RunnerWebViewClient.LOCAL_BASE_URL}index.html")
                        }
                    },
                    update = { webView ->
                        // Re-evaluate JS polyfill if needed
                        webView.evaluateJavascript(RunnerJavaScriptBridge.INJECTED_JS_POLYFILL, null)
                    },
                    onRelease = { webView ->
                        webView.stopLoading()
                        webView.destroy()
                    }
                )
            }

            is RunState.PythonRunning -> {
                // Section 18: Program output/logs
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = Color(0xFF0F172A)
                ) {
                    LazyColumn(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(16.dp)
                            .testTag("python_output_list")
                    ) {
                        items(state.logs) { logLine ->
                            Text(
                                text = logLine,
                                color = if (logLine.startsWith("Traceback") || logLine.contains("Error") || logLine.contains("Exception"))
                                    Color(0xFFF85149) else Color(0xFFE2E8F0),
                                fontFamily = FontFamily.Monospace,
                                fontSize = 13.sp,
                                modifier = Modifier.padding(vertical = 1.dp)
                            )
                        }
                        if (state.isRunning) {
                            item {
                                Row(
                                    modifier = Modifier.padding(top = 12.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    CircularProgressIndicator(
                                        color = Color(0xFF38BDF8),
                                        modifier = Modifier.size(16.dp),
                                        strokeWidth = 2.dp
                                    )
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Text(
                                        text = "Running...",
                                        color = Color(0xFF94A3B8),
                                        fontFamily = FontFamily.Monospace,
                                        fontSize = 12.sp
                                    )
                                }
                            }
                        }
                    }
                }
            }

            is RunState.FailedToRun -> {
                // Section 28 & 41: Project Failed to Run & Open Terminal for details.
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(32.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center
                    ) {
                        Text(
                            text = "Project Failed to Run",
                            fontSize = 24.sp,
                            fontWeight = FontWeight.Bold,
                            color = Color(0xFFEF4444),
                            textAlign = TextAlign.Center
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = "Open Terminal for details.",
                            fontSize = 15.sp,
                            color = Color(0xFFCBD5E1),
                            textAlign = TextAlign.Center
                        )
                        if (state.errorDetails.isNotEmpty()) {
                            Spacer(modifier = Modifier.height(12.dp))
                            Text(
                                text = state.errorDetails,
                                fontSize = 12.sp,
                                fontFamily = FontFamily.Monospace,
                                color = Color(0xFF94A3B8),
                                textAlign = TextAlign.Center
                            )
                        }
                    }
                }
            }
        }

        // ONLY ONE permanent Runner control: ⋮ at Top-Right Corner (Section 2)
        Box(
            modifier = Modifier
                .align(Alignment.TopEnd)
                .padding(top = 12.dp, end = 12.dp)
        ) {
            Surface(
                shape = CircleShape,
                color = Color(0xCC0F172A),
                shadowElevation = 4.dp
            ) {
                IconButton(
                    onClick = { showMenu = !showMenu },
                    modifier = Modifier
                        .size(48.dp)
                        .testTag("menu_more_button")
                ) {
                    Icon(
                        imageVector = Icons.Default.MoreVert,
                        contentDescription = "Menu",
                        tint = Color(0xFFF8FAFC)
                    )
                }
            }

            // Dropdown Menu with EXACTLY the requested 9 controls (Section 42)
            DropdownMenu(
                expanded = showMenu,
                onDismissRequest = { showMenu = false },
                modifier = Modifier.testTag("main_dropdown_menu")
            ) {
                DropdownMenuItem(
                    text = { Text("Terminal") },
                    onClick = {
                        showMenu = false
                        showTerminalDialog = true
                    },
                    modifier = Modifier.testTag("menu_terminal")
                )
                DropdownMenuItem(
                    text = { Text("Install pip package") },
                    onClick = {
                        showMenu = false
                        showPipDialog = true
                    },
                    modifier = Modifier.testTag("menu_install_pip")
                )
                DropdownMenuItem(
                    text = { Text("Select Folder") },
                    onClick = {
                        showMenu = false
                        folderPickerLauncher.launch(null)
                    },
                    modifier = Modifier.testTag("menu_select_folder")
                )
                DropdownMenuItem(
                    text = { Text("Import Project") },
                    onClick = {
                        showMenu = false
                        importZipLauncher.launch("application/zip")
                    },
                    modifier = Modifier.testTag("menu_import_project")
                )
                DropdownMenuItem(
                    text = { Text("Download Project") },
                    onClick = {
                        showMenu = false
                        showDownloadDialog = true
                    },
                    modifier = Modifier.testTag("menu_download_project")
                )
                DropdownMenuItem(
                    text = { Text("Restart") },
                    onClick = {
                        showMenu = false
                        viewModel.restartProject()
                    },
                    modifier = Modifier.testTag("menu_restart")
                )
                DropdownMenuItem(
                    text = { Text("Stop") },
                    onClick = {
                        showMenu = false
                        viewModel.stopProject()
                    },
                    modifier = Modifier.testTag("menu_stop")
                )
                DropdownMenuItem(
                    text = { Text("Settings") },
                    onClick = {
                        showMenu = false
                        showSettingsDialog = true
                    },
                    modifier = Modifier.testTag("menu_settings")
                )
                DropdownMenuItem(
                    text = { Text("About") },
                    onClick = {
                        showMenu = false
                        showAboutDialog = true
                    },
                    modifier = Modifier.testTag("menu_about")
                )
                DropdownMenuItem(
                    text = { Text("Help") },
                    onClick = {
                        showMenu = false
                        showHelpDialog = true
                    },
                    modifier = Modifier.testTag("menu_help")
                )
            }
        }
    }

    // Modal Terminal Sheet/Dialog (Section 25: Accessible ONLY through ⋮ -> Terminal)
    if (showTerminalDialog) {
        TerminalDialog(
            terminalManager = viewModel.terminalManager,
            onDismiss = { showTerminalDialog = false }
        )
    }

    // Install Pip Package Dialog (Section 24)
    if (showPipDialog) {
        InstallPipPackageDialog(
            onDismiss = { showPipDialog = false },
            onInstall = { pkg ->
                viewModel.installPipPackage(pkg) {
                    showPipDialog = false
                }
            }
        )
    }

    // Download Project Dialog (Section 33)
    if (showDownloadDialog) {
        DownloadProjectDialog(
            onDismiss = { showDownloadDialog = false },
            onDownload = { url ->
                viewModel.downloadProject(url)
            }
        )
    }

    // Settings Dialog
    if (showSettingsDialog) {
        SettingsDialog(
            storageManager = viewModel.storageManager,
            onDismiss = { showSettingsDialog = false },
            onClearProject = {
                viewModel.storageManager.clearActiveProjectDir()
                viewModel.startupScanAndRun()
            }
        )
    }

    // About Dialog
    if (showAboutDialog) {
        AboutDialog(
            onDismiss = { showAboutDialog = false }
        )
    }

    // Help & Documentation Dialog
    if (showHelpDialog) {
        HelpDialog(
            onDismiss = { showHelpDialog = false }
        )
    }
}
