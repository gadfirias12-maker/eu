package com.example.presentation.screens

import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavController
import com.example.domain.model.Subscription
import com.example.presentation.navigation.Routes
import com.example.presentation.viewmodel.VpnViewModel
import java.text.SimpleDateFormat
import java.util.*

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SubscriptionScreen(
    navController: NavController,
    viewModel: VpnViewModel
) {
    val context = LocalContext.current
    val subscriptions by viewModel.subscriptions.collectAsState()
    val syncState by viewModel.syncState.collectAsState()

    var name by remember { mutableStateOf("") }
    var url by remember { mutableStateOf("") }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Subscriptions Manager", fontWeight = FontWeight.Bold, color = Color.White) },
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
        ) {
            // 1. Add subscription card section
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 12.dp),
                colors = CardDefaults.cardColors(containerColor = Color(0xFF1E293B)),
                shape = RoundedCornerShape(12.dp)
            ) {
                Column(modifier = Modifier.padding(14.dp)) {
                    Text(
                        "Add Remote Subscription", 
                        color = Color.White, 
                        fontWeight = FontWeight.Bold, 
                        fontSize = 14.sp
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                    
                    TextField(
                        value = name,
                        onValueChange = { name = it },
                        placeholder = { Text("Provider Name (e.g. Node Provider X)", color = Color(0xFF64748B)) },
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(8.dp))
                            .testTag("sub_name_input"),
                        colors = TextFieldDefaults.colors(
                            focusedTextColor = Color.White,
                            unfocusedTextColor = Color.White,
                            focusedContainerColor = Color(0xFF334155),
                            unfocusedContainerColor = Color(0xFF334155),
                            cursorColor = Color(0xFF38BDF8),
                            focusedIndicatorColor = Color.Transparent,
                            unfocusedIndicatorColor = Color.Transparent
                        ),
                        singleLine = true
                    )
                    Spacer(modifier = Modifier.height(8.dp))

                    TextField(
                        value = url,
                        onValueChange = { url = it },
                        placeholder = { Text("https://example.com/subscribe?token=...", color = Color(0xFF64748B)) },
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(8.dp))
                            .testTag("sub_url_input"),
                        colors = TextFieldDefaults.colors(
                            focusedTextColor = Color.White,
                            unfocusedTextColor = Color.White,
                            focusedContainerColor = Color(0xFF334155),
                            unfocusedContainerColor = Color(0xFF334155),
                            cursorColor = Color(0xFF38BDF8),
                            focusedIndicatorColor = Color.Transparent,
                            unfocusedIndicatorColor = Color.Transparent
                        ),
                        singleLine = true
                    )
                    Spacer(modifier = Modifier.height(12.dp))

                    Button(
                        onClick = {
                            if (name.trim().isEmpty() || url.trim().isEmpty()) {
                                Toast.makeText(context, "Please complete all fields", Toast.LENGTH_SHORT).show()
                            } else if (!url.startsWith("http://") && !url.startsWith("https://")) {
                                Toast.makeText(context, "URL must start with http or https", Toast.LENGTH_SHORT).show()
                            } else {
                                viewModel.addSubscription(name.trim(), url.trim())
                                name = ""
                                url = ""
                                Toast.makeText(context, "Adding connection & running initial update sync...", Toast.LENGTH_SHORT).show()
                            }
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(44.dp)
                            .testTag("add_sub_button"),
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF38BDF8)),
                        shape = RoundedCornerShape(8.dp)
                    ) {
                        Icon(Icons.Default.CloudDownload, contentDescription = "Download")
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("Download & Sync Profile", fontWeight = FontWeight.Bold, fontSize = 13.sp)
                    }
                }
            }

            // Sync indicators running in background
            if (syncState != null) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(8.dp))
                        .background(Color(0xFF0369A1))
                        .padding(12.dp)
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(color = Color.White, strokeWidth = 2.dp, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(12.dp))
                        Text(text = syncState ?: "", color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.Bold)
                    }
                }
                Spacer(modifier = Modifier.height(10.dp))
            }

            // 2. Subscriptions List view
            Text("Active Subscriptions", color = Color(0xFF94A3B8), fontSize = 13.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(vertical = 8.dp))

            if (subscriptions.isEmpty()) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f),
                    contentAlignment = Alignment.Center
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(Icons.Default.WifiTetheringError, contentDescription = "None", tint = Color(0xFF334155), modifier = Modifier.size(64.dp))
                        Spacer(modifier = Modifier.height(12.dp))
                        Text("No remote subscriptions added", color = Color(0xFF475569), fontSize = 14.sp)
                    }
                }
            } else {
                LazyColumn(
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                    modifier = Modifier.weight(1f)
                ) {
                    items(subscriptions) { subscription ->
                        SubscriptionItemCard(
                            subscription = subscription,
                            onSync = { viewModel.syncSubscription(subscription) },
                            onDelete = { viewModel.deleteSubscription(subscription.id) }
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun SubscriptionItemCard(
    subscription: Subscription,
    onSync: (Subscription) -> Unit,
    onDelete: () -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .testTag("sub_item_${subscription.id}"),
        colors = CardDefaults.cardColors(containerColor = Color(0xFF1E293B)),
        shape = RoundedCornerShape(10.dp)
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = subscription.name,
                        color = Color.White,
                        fontWeight = FontWeight.Bold,
                        fontSize = 15.sp
                    )
                    Spacer(modifier = Modifier.height(2.dp))
                    Text(
                        text = subscription.url,
                        color = Color(0xFF64748B),
                        fontSize = 11.sp,
                        maxLines = 1
                    )
                }
                
                // Expiry Check tag
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(6.dp))
                        .background(if (subscription.isExpired) Color(0xFFEF4444).copy(0.15f) else Color(0xFF10B981).copy(0.12f))
                        .padding(horizontal = 8.dp, vertical = 4.dp)
                ) {
                    Text(
                        text = if (subscription.isExpired) "EXPIRED" else "ACTIVE",
                        color = if (subscription.isExpired) Color(0xFFEF4444) else Color(0xFF10B981),
                        fontWeight = FontWeight.Bold,
                        fontSize = 9.sp
                    )
                }
            }

            Spacer(modifier = Modifier.height(12.dp))
            Divider(color = Color(0xFF334155))
            Spacer(modifier = Modifier.height(8.dp))

            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
                modifier = Modifier.fillMaxWidth()
            ) {
                // Info Section
                Column {
                    Text(
                        text = "Last synced: " + if (subscription.lastUpdated > 0) formatDate(subscription.lastUpdated) else "Never",
                        color = Color(0xFF94A3B8),
                        fontSize = 11.sp
                    )
                    if (subscription.expiryTimestamp > 0) {
                        Text(
                            text = "Expires on: " + formatDate(subscription.expiryTimestamp),
                            color = Color(0xFF64748B),
                            fontSize = 10.sp
                        )
                    }
                }

                // Call buttons
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    IconButton(
                        onClick = { onSync(subscription) },
                        colors = IconButtonDefaults.iconButtonColors(containerColor = Color(0xFF334155)),
                        modifier = Modifier.size(34.dp)
                    ) {
                        Icon(Icons.Default.Sync, contentDescription = "Manual sync", tint = Color.White, modifier = Modifier.size(16.dp))
                    }
                    IconButton(
                        onClick = { onDelete() },
                        colors = IconButtonDefaults.iconButtonColors(containerColor = Color(0xFF3B0712)),
                        modifier = Modifier.size(34.dp)
                    ) {
                        Icon(Icons.Default.Delete, contentDescription = "Delete config", tint = Color(0xFFEF4444), modifier = Modifier.size(16.dp))
                    }
                }
            }
        }
    }
}

fun formatDate(timestamp: Long): String {
    val date = Date(timestamp)
    val formatter = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault())
    return formatter.format(date)
}
