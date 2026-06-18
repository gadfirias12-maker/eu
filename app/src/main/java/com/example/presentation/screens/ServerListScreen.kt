package com.example.presentation.screens

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.platform.LocalContext
import android.widget.Toast
import androidx.compose.ui.unit.sp
import androidx.navigation.NavController
import com.example.domain.model.ProxyProtocol
import com.example.domain.model.ServerConfig
import com.example.presentation.navigation.Routes
import com.example.presentation.viewmodel.VpnViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ServerListScreen(
    navController: NavController,
    viewModel: VpnViewModel
) {
    val context = LocalContext.current
    val servers by viewModel.servers.collectAsState()
    var searchQuery by remember { mutableStateOf("") }
    var sortByLatency by remember { mutableStateOf(false) }
    
    val filteredList = remember(servers, searchQuery, sortByLatency) {
        var list = servers.filter {
            it.name.contains(searchQuery, ignoreCase = true) ||
            it.address.contains(searchQuery, ignoreCase = true)
        }
        if (sortByLatency) {
            list = list.sortedWith(compareBy { 
                if (it.latency <= 0) Long.MAX_VALUE else it.latency 
            })
        }
        list
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Server Profiles", fontWeight = FontWeight.Bold, color = Color.White) },
                navigationIcon = {
                    IconButton(onClick = { navController.navigate(Routes.HOME) {
                        popUpTo(Routes.HOME) { inclusive = true }
                    }}) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "Back", tint = Color.White)
                    }
                },
                actions = {
                    IconButton(onClick = {
                        viewModel.importServerFromClipboard(
                            context = context,
                            onSuccess = {
                                Toast.makeText(context, "پیکربندی با موفقیت وارد شد", Toast.LENGTH_SHORT).show()
                            },
                            onFailure = { error ->
                                Toast.makeText(context, error, Toast.LENGTH_LONG).show()
                            }
                        )
                    }) {
                        Icon(Icons.Default.ContentPaste, contentDescription = "Import from Clipboard", tint = Color.White)
                    }
                    IconButton(onClick = { viewModel.testAllLatencies() }) {
                        Icon(Icons.Default.Speed, contentDescription = "Ping All", tint = Color.White)
                    }
                    IconButton(onClick = { sortByLatency = !sortByLatency }) {
                        Icon(
                            imageVector = Icons.Default.Sort,
                            contentDescription = "Sort Latency",
                            tint = if (sortByLatency) Color(0xFF38BDF8) else Color.White
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Color(0xFF0F172A))
            )
        },
        floatingActionButton = {
            FloatingActionButton(
                onClick = { navController.navigate(Routes.ADD_EDIT_SERVER) },
                containerColor = Color(0xFF38BDF8),
                contentColor = Color.White,
                modifier = Modifier.testTag("add_server_fab")
            ) {
                Icon(Icons.Default.Add, contentDescription = "Add Config")
            }
        },
        containerColor = Color(0xFF0F172A)
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .padding(horizontal = 16.dp)
        ) {
            // Search Bar
            TextField(
                value = searchQuery,
                onValueChange = { searchQuery = it },
                placeholder = { Text("Search servers, nodes, IPs...", color = Color(0xFF64748B)) },
                leadingIcon = { Icon(Icons.Default.Search, contentDescription = "Search", tint = Color(0xFF94A3B8)) },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 12.dp)
                    .clip(RoundedCornerShape(12.dp)),
                colors = TextFieldDefaults.colors(
                    focusedTextColor = Color.White,
                    unfocusedTextColor = Color.White,
                    focusedContainerColor = Color(0xFF1E293B),
                    unfocusedContainerColor = Color(0xFF1E293B),
                    disabledContainerColor = Color(0xFF1E293B),
                    cursorColor = Color(0xFF38BDF8),
                    focusedIndicatorColor = Color.Transparent,
                    unfocusedIndicatorColor = Color.Transparent
                ),
                singleLine = true
            )

            Button(
                onClick = {
                    viewModel.importServerFromClipboard(
                        context = context,
                        onSuccess = {
                            Toast.makeText(context, "پیکربندی با موفقیت از کلیپ‌بورد وارد شد!", Toast.LENGTH_SHORT).show()
                        },
                        onFailure = { error ->
                            Toast.makeText(context, error, Toast.LENGTH_LONG).show()
                        }
                    )
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 12.dp)
                    .height(52.dp)
                    .testTag("import_clipboard_shortcut"),
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF1E3A8A)),
                shape = RoundedCornerShape(12.dp)
            ) {
                Icon(
                    imageVector = Icons.Default.ContentPaste,
                    contentDescription = "Import clipboard",
                    tint = Color(0xFF38BDF8)
                )
                Spacer(modifier = Modifier.width(10.dp))
                Text("ورود خودکار کانفیگ از کلیپ‌بورد (vmess, vless, ss, ...)", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 13.sp)
            }

            if (filteredList.isEmpty()) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f),
                    contentAlignment = Alignment.Center
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(
                            imageVector = Icons.Default.Dns,
                            contentDescription = "Empty",
                            tint = Color(0xFF334155),
                            modifier = Modifier.size(72.dp)
                        )
                        Spacer(modifier = Modifier.height(16.dp))
                        Text(
                            text = "No proxy servers available",
                            color = Color(0xFF64748B),
                            fontSize = 16.sp,
                            fontWeight = FontWeight.Bold
                        )
                        Spacer(modifier = Modifier.height(6.dp))
                        Text(
                            text = "Tap + to manually add, or sync via Subscriptions",
                            color = Color(0xFF475569),
                            fontSize = 13.sp
                        )
                    }
                }
            } else {
                LazyColumn(
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                    modifier = Modifier.weight(1f)
                ) {
                    items(filteredList) { server ->
                        ServerItemRow(
                            server = server,
                            pingText = viewModel.pingState.value[server.id],
                            onSelect = { 
                                viewModel.toggleSelectServer(server.id)
                                navController.navigate(Routes.HOME) {
                                    popUpTo(Routes.HOME) { inclusive = true }
                                }
                            },
                            onPing = { viewModel.testServerLatency(server) },
                            onDuplicate = { viewModel.duplicateServer(server) },
                            onDelete = { viewModel.deleteServer(server.id) }
                        )
                    }
                }
            }
        }
    }
}

fun getPingResultInfo(pingText: String?, cachedLatency: Long): Pair<String, Color> {
    if (pingText == "Testing...") {
        return Pair("Testing...", Color(0xFF38BDF8))
    }
    
    val latency: Long
    val text: String
    if (pingText != null) {
        text = pingText
        if (pingText == "Timeout") {
            latency = -1
        } else {
            val msVal = pingText.replace("ms", "").toLongOrNull()
            latency = msVal ?: cachedLatency
        }
    } else {
        latency = cachedLatency
        text = if (cachedLatency > 0) "${cachedLatency}ms" else if (cachedLatency < 0) "Timeout" else "Ping"
    }
    
    val color = when {
        text == "Ping" -> Color(0xFF64748B)
        text == "Timeout" || latency <= 0 -> Color(0xFFEF4444) // Red for timeout/fail
        latency in 1..250 -> Color(0xFF10B981) // Green for good/low latency
        else -> Color(0xFFF59E0B) // Orange for weak/high latency
    }
    
    return Pair(text, color)
}

@Composable
fun ServerItemRow(
    server: ServerConfig,
    pingText: String?,
    onSelect: (Long) -> Unit,
    onPing: (ServerConfig) -> Unit,
    onDuplicate: (ServerConfig) -> Unit,
    onDelete: () -> Unit
) {
    var expanded by remember { mutableStateOf(false) }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .testTag("server_item_${server.id}")
            .clickable { onSelect(server.id) },
        colors = CardDefaults.cardColors(
            containerColor = if (server.isSelected) Color(0xFF0284C7) else Color(0xFF1E293B)
        ),
        shape = RoundedCornerShape(12.dp)
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            Row(
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Protocol Tag
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(6.dp))
                        .background(if (server.isSelected) Color(0xFF0369A1) else Color(0xFF334155))
                        .padding(horizontal = 8.dp, vertical = 4.dp)
                ) {
                    Text(
                        text = server.protocol.name,
                        color = Color.White,
                        fontWeight = FontWeight.Bold,
                        fontSize = 10.sp
                    )
                }

                Spacer(modifier = Modifier.width(12.dp))

                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = server.name,
                        color = Color.White,
                        fontWeight = FontWeight.Bold,
                        fontSize = 15.sp
                    )
                    Spacer(modifier = Modifier.height(2.dp))
                    Text(
                        text = "${server.address}:${server.port}",
                        color = if (server.isSelected) Color.White.copy(0.75f) else Color(0xFF94A3B8),
                        fontSize = 12.sp
                    )
                }

                // Latency Indicator (Bordered custom design)
                val (latStr, latColor) = getPingResultInfo(pingText, server.latency)
                
                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier
                        .clip(RoundedCornerShape(8.dp))
                        .border(
                            width = 1.dp,
                            color = if (pingText == "Testing...") Color(0xFF38BDF8) else latColor,
                            shape = RoundedCornerShape(8.dp)
                        )
                        .background(
                            if (pingText == "Testing...") Color(0xFF38BDF8).copy(alpha = 0.1f) 
                            else latColor.copy(alpha = 0.1f)
                        )
                        .clickable { onPing(server) }
                        .padding(horizontal = 10.dp, vertical = 6.dp)
                ) {
                    if (pingText == "Testing...") {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            CircularProgressIndicator(
                                color = Color(0xFF38BDF8),
                                strokeWidth = 1.5.dp,
                                modifier = Modifier.size(10.dp)
                            )
                            Text(
                                text = "تست...",
                                color = Color(0xFF38BDF8),
                                fontWeight = FontWeight.SemiBold,
                                fontSize = 11.sp
                            )
                        }
                    } else {
                        val displayStr = when (latStr) {
                            "Ping" -> "پینگ"
                            "Timeout" -> "قطع"
                            else -> latStr
                        }
                        Text(
                            text = displayStr,
                            color = latColor,
                            fontWeight = FontWeight.Bold,
                            fontSize = 11.sp
                        )
                    }
                }

                Spacer(modifier = Modifier.width(12.dp))

                // Expand settings
                IconButton(
                    onClick = { expanded = !expanded },
                    modifier = Modifier.size(32.dp)
                ) {
                    Icon(
                        imageVector = if (expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                        contentDescription = "Actions",
                        tint = Color.White
                    )
                }
            }

            // Expanded quick actions list
            AnimatedVisibility(visible = expanded) {
                Column {
                    Spacer(modifier = Modifier.height(12.dp))
                    Divider(color = Color.White.copy(alpha = 0.15f))
                    Spacer(modifier = Modifier.height(8.dp))
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(16.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        IconButton(
                            onClick = { onDuplicate(server) },
                            modifier = Modifier.size(36.dp)
                        ) {
                            Icon(Icons.Outlined.ContentCopy, contentDescription = "Duplicate Config", tint = Color.White)
                        }
                        IconButton(
                            onClick = { onDelete() },
                            modifier = Modifier.size(36.dp)
                        ) {
                            Icon(Icons.Outlined.Delete, contentDescription = "Delete Config", tint = Color(0xFFEF4444))
                        }
                    }
                }
            }
        }
    }
}
