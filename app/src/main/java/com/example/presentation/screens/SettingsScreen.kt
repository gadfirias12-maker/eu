package com.example.presentation.screens

import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Save
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavController
import com.example.domain.model.VpnSettings
import com.example.presentation.navigation.Routes
import com.example.presentation.viewmodel.VpnViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    navController: NavController,
    viewModel: VpnViewModel
) {
    val context = LocalContext.current
    val settingsState by viewModel.settings.collectAsState()
    val scrollState = rememberScrollState()

    var dnsServer by remember { mutableStateOf("") }
    var bypassLan by remember { mutableStateOf(true) }
    var bypassMainland by remember { mutableStateOf(false) }
    var fakeDnsEnabled by remember { mutableStateOf(true) }
    var ipv6Enabled by remember { mutableStateOf(false) }
    var connectionMode by remember { mutableStateOf("Global") }
    var perAppProxyEnabled by remember { mutableStateOf(false) }
    var selectedAppsList by remember { mutableStateOf("") }

    // Synchronize local states with room flows on startup
    LaunchedEffect(settingsState) {
        dnsServer = settingsState.dnsServer
        bypassLan = settingsState.bypassLan
        bypassMainland = settingsState.bypassMainland
        fakeDnsEnabled = settingsState.fakeDnsEnabled
        ipv6Enabled = settingsState.ipv6Enabled
        connectionMode = settingsState.connectionMode
        perAppProxyEnabled = settingsState.perAppProxyEnabled
        selectedAppsList = settingsState.selectedAppsList
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Network Configuration", fontWeight = FontWeight.Bold, color = Color.White) },
                navigationIcon = {
                    IconButton(onClick = { navController.navigate(Routes.HOME) {
                        popUpTo(Routes.HOME) { inclusive = true }
                    }}) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "Back", tint = Color.White)
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Color(0xFF0F172A))
            )
        },
        containerColor = Color(0xFF0F172A)
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .padding(horizontal = 16.dp)
                .verticalScroll(scrollState)
        ) {
            Spacer(modifier = Modifier.height(16.dp))

            // 1. DNS Section
            Text("DNS Engine", color = Color(0xFF38BDF8), fontSize = 14.sp, fontWeight = FontWeight.Bold)
            Spacer(modifier = Modifier.height(10.dp))
            
            TextField(
                value = dnsServer,
                onValueChange = { dnsServer = it },
                label = { Text("Proxy DNS Servers (e.g. 8.8.8.8, 1.1.1.1)", color = Color(0xFF94A3B8)) },
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(8.dp))
                    .testTag("settings_dns_input"),
                colors = TextFieldDefaults.colors(
                    focusedTextColor = Color.White,
                    unfocusedTextColor = Color.White,
                    focusedContainerColor = Color(0xFF1E293B),
                    unfocusedContainerColor = Color(0xFF1E293B),
                    cursorColor = Color(0xFF38BDF8),
                    focusedIndicatorColor = Color.Transparent,
                    unfocusedIndicatorColor = Color.Transparent
                ),
                singleLine = true
            )

            Spacer(modifier = Modifier.height(24.dp))

            // 2. Routing Rules Selectors
            Text("Routing Mode", color = Color(0xFF38BDF8), fontSize = 14.sp, fontWeight = FontWeight.Bold)
            Spacer(modifier = Modifier.height(10.dp))

            val modesList = listOf("Global", "Bypass LAN", "Bypass LAN & Direct")
            modesList.forEach { mode ->
                val isSelected = connectionMode == mode
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 4.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .background(if (isSelected) Color(0xFF1E3A8A) else Color(0xFF1E293B))
                        .clickable { 
                            connectionMode = mode
                            if (mode == "Bypass LAN") {
                                bypassLan = true
                                bypassMainland = false
                            } else if (mode == "Bypass LAN & Direct") {
                                bypassLan = true
                                bypassMainland = true
                            } else {
                                bypassLan = false
                                bypassMainland = false
                            }
                        }
                        .padding(14.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column {
                        Text(text = mode, color = Color.White, fontWeight = FontWeight.Bold, fontSize = 14.sp)
                        Text(
                            text = when (mode) {
                                "Global" -> "Redirect all network traffic through Xray proxy server."
                                "Bypass LAN" -> "Exclude local LAN addresses to preserve home network access."
                                else -> "Bypass direct networks, local LAN configs, and secure direct ports."
                            }, 
                            color = Color(0xFF94A3B8), 
                            fontSize = 11.sp
                        )
                    }
                    RadioButton(
                        selected = isSelected,
                        onClick = { 
                            connectionMode = mode
                            bypassLan = mode.contains("Bypass")
                            bypassMainland = mode.contains("Direct")
                        },
                        colors = RadioButtonDefaults.colors(selectedColor = Color(0xFF38BDF8))
                    )
                }
            }

            Spacer(modifier = Modifier.height(24.dp))

            // 3. Core Switches
            Text("IPv6 & DNS resolving options", color = Color(0xFF38BDF8), fontSize = 14.sp, fontWeight = FontWeight.Bold)
            Spacer(modifier = Modifier.height(8.dp))

            SettingsToggleRow(
                title = "FakeDNS resolving",
                subtitle = "Mitigate DNS pollution; speeds up domain handshaking response maps.",
                checked = fakeDnsEnabled,
                onCheckedChange = { fakeDnsEnabled = it }
            )

            SettingsToggleRow(
                title = "Enable IPv6 supporting",
                subtitle = "Intermediates IPv6 dualstack packets through tunnel channels.",
                checked = ipv6Enabled,
                onCheckedChange = { ipv6Enabled = it }
            )

            Spacer(modifier = Modifier.height(24.dp))

            // 4. Per-app split VPN routing
            Text("Per-App split tunneling", color = Color(0xFF38BDF8), fontSize = 14.sp, fontWeight = FontWeight.Bold)
            Spacer(modifier = Modifier.height(8.dp))

            SettingsToggleRow(
                title = "Enable target application filter",
                subtitle = "Bypass selected packages or restrict system routing scope.",
                checked = perAppProxyEnabled,
                onCheckedChange = { perAppProxyEnabled = it }
            )

            if (perAppProxyEnabled) {
                Spacer(modifier = Modifier.height(8.dp))
                TextField(
                    value = selectedAppsList,
                    onValueChange = { selectedAppsList = it },
                    label = { Text("Bypassed package names (comma separated)", color = Color(0xFF94A3B8)) },
                    placeholder = { Text("com.android.chrome, com.google.android.youtube", color = Color(0xFF475569)) },
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(8.dp)),
                    colors = TextFieldDefaults.colors(
                        focusedTextColor = Color.White,
                        unfocusedTextColor = Color.White,
                        focusedContainerColor = Color(0xFF1E293B),
                        unfocusedContainerColor = Color(0xFF1E293B),
                        cursorColor = Color(0xFF38BDF8),
                        focusedIndicatorColor = Color.Transparent,
                        unfocusedIndicatorColor = Color.Transparent
                    ),
                    maxLines = 3
                )
            }

            Spacer(modifier = Modifier.height(36.dp))

            // 5. Submit Changes Action
            Button(
                onClick = {
                    val updatedSettings = VpnSettings(
                        dnsServer = dnsServer,
                        bypassLan = bypassLan,
                        bypassMainland = bypassMainland,
                        fakeDnsEnabled = fakeDnsEnabled,
                        ipv6Enabled = ipv6Enabled,
                        connectionMode = connectionMode,
                        perAppProxyEnabled = perAppProxyEnabled,
                        selectedAppsList = selectedAppsList
                    )
                    viewModel.saveSettings(updatedSettings)
                    Toast.makeText(context, "Configurations updated in local storage", Toast.LENGTH_SHORT).show()
                    navController.navigate(Routes.HOME) {
                        popUpTo(Routes.HOME) { inclusive = true }
                    }
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(48.dp)
                    .testTag("save_settings_button"),
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF10B981)),
                shape = RoundedCornerShape(10.dp)
            ) {
                Icon(Icons.Default.Save, contentDescription = "Save settings")
                Spacer(modifier = Modifier.width(8.dp))
                Text("Apply & Commit Settings", fontWeight = FontWeight.Bold, fontSize = 14.sp)
            }

            Spacer(modifier = Modifier.height(54.dp))
        }
    }
}

@Composable
fun SettingsToggleRow(
    title: String,
    subtitle: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 6.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(Color(0xFF1E293B))
            .padding(14.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(text = title, color = Color.White, fontWeight = FontWeight.Bold, fontSize = 14.sp)
            Text(text = subtitle, color = Color(0xFF94A3B8), fontSize = 11.sp)
        }
        Spacer(modifier = Modifier.width(16.dp))
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange,
            colors = SwitchDefaults.colors(
                checkedThumbColor = Color.White,
                checkedTrackColor = Color(0xFF38BDF8),
                uncheckedThumbColor = Color(0xFF64748B),
                uncheckedTrackColor = Color(0xFF334155)
            )
        )
    }
}
