package com.securechat.app.ui.contacts

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.viewmodel.compose.viewModel
import com.securechat.app.AppContainer
import com.securechat.app.qr.QrCodeGenerator
import com.securechat.app.qr.QrCodeScannerView
import com.securechat.app.ui.rememberViewModelFactory

private enum class AddContactTab { SCAN, MY_CODE }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AddContactScreen(container: AppContainer, onBack: () -> Unit, onContactAdded: (String) -> Unit) {
    val viewModel: ContactsViewModel = viewModel(factory = rememberViewModelFactory { ContactsViewModel(container) })
    var tab by remember { mutableStateOf(AddContactTab.SCAN) }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    var addedUserId by remember { mutableStateOf<String?>(null) }
    val context = LocalContext.current
    var hasCameraPermission by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED,
        )
    }
    val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        hasCameraPermission = granted
    }

    LaunchedEffect(addedUserId) {
        addedUserId?.let(onContactAdded)
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Add contact") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .padding(padding)
                .fillMaxSize(),
        ) {
            TabRow(selectedTabIndex = tab.ordinal) {
                Tab(selected = tab == AddContactTab.SCAN, onClick = { tab = AddContactTab.SCAN }, text = { Text("Scan") })
                Tab(selected = tab == AddContactTab.MY_CODE, onClick = { tab = AddContactTab.MY_CODE }, text = { Text("My code") })
            }

            when (tab) {
                AddContactTab.MY_CODE -> MyCodeTab(viewModel.myContactCardQrContent)
                AddContactTab.SCAN -> ScanTab(
                    hasCameraPermission = hasCameraPermission,
                    errorMessage = errorMessage,
                    onRequestPermission = { permissionLauncher.launch(Manifest.permission.CAMERA) },
                    onScanned = { content ->
                        viewModel.addContactFromQr(content) { result ->
                            result.onSuccess { userId -> addedUserId = userId }
                            result.onFailure { errorMessage = it.message }
                        }
                    },
                )
            }
        }
    }
}

@Composable
private fun MyCodeTab(qrContent: String) {
    val bitmap = remember(qrContent) { QrCodeGenerator.generate(qrContent) }
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Image(bitmap = bitmap.asImageBitmap(), contentDescription = "Your contact QR code")
    }
}

@Composable
private fun ScanTab(
    hasCameraPermission: Boolean,
    errorMessage: String?,
    onRequestPermission: () -> Unit,
    onScanned: (String) -> Unit,
) {
    if (!hasCameraPermission) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(24.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text("Camera access is needed to scan a contact's QR code.")
            Spacer(modifier = Modifier.height(12.dp))
            Button(onClick = onRequestPermission) { Text("Grant camera access") }
        }
        return
    }

    Box(modifier = Modifier.fillMaxSize()) {
        QrCodeScannerView(modifier = Modifier.fillMaxSize(), onScanned = onScanned)
        errorMessage?.let {
            Surface(modifier = Modifier.align(Alignment.BottomCenter).padding(16.dp)) {
                Text(text = it, modifier = Modifier.padding(12.dp))
            }
        }
    }
}
