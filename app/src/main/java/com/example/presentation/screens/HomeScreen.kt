package com.example.presentation.screens

import android.content.Context
import android.net.VpnService
import android.app.Activity
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavController
import com.example.data.vpn.TrafficMetric
import com.example.data.vpn.VpnStatus
import com.example.domain.model.ServerConfig
import com.example.presentation.navigation.Routes
import com.example.presentation.viewmodel.VpnViewModel
import androidx.compose.foundation.Image
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    navController: NavController,
    viewModel: VpnViewModel
) {
    val context = LocalContext.current
    val servers by viewModel.servers.collectAsState()
    val metrics by viewModel.trafficMetrics.collectAsState()
    
    val activeServer = servers.find { it.isSelected }

    val vpnPrepareLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            viewModel.toggleConnection(context)
        } else {
            Toast.makeText(context, "مجوز اتصال VPN پذیرفته نشد", Toast.LENGTH_SHORT).show()
        }
    }

    // Rotating globe background container - extremely optimized using graphicsLayer to prevent unnecessary recompositions
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFF0F172A))
    ) {
        val infiniteTransition = rememberInfiniteTransition(label = "GlobeRotation")
        val rotationAngle by infiniteTransition.animateFloat(
            initialValue = 0f,
            targetValue = 360f,
            animationSpec = infiniteRepeatable(
                animation = tween(45000, easing = LinearEasing),
                repeatMode = RepeatMode.Restart
            ),
            label = "globeRotationAngle"
        )

        Image(
            painter = painterResource(id = com.example.R.drawable.img_glowing_globe_1781763668880),
            contentDescription = "Rotating Earth Globe Background",
            modifier = Modifier
                .align(Alignment.Center)
                .size(420.dp)
                .graphicsLayer {
                    rotationZ = rotationAngle
                    alpha = 0.12f // 12% opacity keeps it beautifully subtle and readable
                },
            contentScale = ContentScale.Fit
        )

        Scaffold(
            topBar = {
                TopAppBar(
                    title = {
                        Text(
                            text = "Xray VPN",
                            fontWeight = FontWeight.Bold,
                            color = Color.White
                        )
                    },
                    actions = {
                        IconButton(onClick = { navController.navigate(Routes.ABOUT) }) {
                            Icon(Icons.Default.Info, contentDescription = "About Project", tint = Color.White)
                        }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(
                        containerColor = Color.Transparent
                    )
                )
            },
            containerColor = Color.Transparent // Allow background globe pattern to shine through
        ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .padding(horizontal = 20.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.SpaceBetween
        ) {
            Spacer(modifier = Modifier.height(12.dp))

            // 1. Connection Status Text & Timer
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                val statusText = when (metrics.status) {
                    VpnStatus.DISCONNECTED -> "Disconnected"
                    VpnStatus.CONNECTING -> "Handshaking..."
                    VpnStatus.CONNECTED -> "Securely Connected"
                    VpnStatus.RECONNECTING -> "Relaying session..."
                }
                
                Text(
                    text = statusText,
                    color = when (metrics.status) {
                        VpnStatus.CONNECTED -> Color(0xFF10B981) // Emerald
                        VpnStatus.CONNECTING -> Color(0xFFF59E0B) // Amber
                        else -> Color(0xFF94A3B8)
                    },
                    fontWeight = FontWeight.Bold,
                    fontSize = 20.sp
                )
                
                Spacer(modifier = Modifier.height(4.dp))
                
                if (metrics.status == VpnStatus.CONNECTED) {
                    Text(
                        text = formatDuration(metrics.connectedDurationSeconds),
                        color = Color(0xFF38BDF8),
                        fontFamily = FontFamily.Monospace,
                        fontSize = 16.sp
                    )
                } else {
                    Text(
                        text = "Ready to tunnel",
                        color = Color(0xFF64748B),
                        fontSize = 14.sp
                    )
                }
            }

            // 2. Large Central Connection Orb
            ConnectionOrb(
                status = metrics.status,
                onToggle = {
                    if (metrics.status == VpnStatus.CONNECTED || metrics.status == VpnStatus.CONNECTING) {
                        viewModel.toggleConnection(context)
                    } else {
                        if (activeServer == null) {
                            Toast.makeText(context, "لطفاً ابتدا یک سرور انتخاب کنید یا وارد کنید", Toast.LENGTH_LONG).show()
                        } else {
                            val intent = VpnService.prepare(context)
                            if (intent != null) {
                                vpnPrepareLauncher.launch(intent)
                            } else {
                                viewModel.toggleConnection(context)
                            }
                        }
                    }
                }
            )

            // 3. Current Config Box
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { navController.navigate(Routes.SERVER_LIST) }
                    .shadow(4.dp, RoundedCornerShape(16.dp)),
                colors = CardDefaults.cardColors(containerColor = Color(0xFF1E293B)),
                shape = RoundedCornerShape(16.dp)
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(
                        modifier = Modifier
                            .size(48.dp)
                            .background(Color(0xFF334155), CircleShape),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = when (activeServer?.protocol) {
                                null -> Icons.Default.CloudQueue
                                else -> Icons.Default.Security
                            },
                            contentDescription = "Server Protocol",
                            tint = Color(0xFF38BDF8)
                        )
                    }

                    Spacer(modifier = Modifier.width(16.dp))

                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = activeServer?.name ?: "No Server Selected",
                            color = Color.White,
                            fontWeight = FontWeight.Bold,
                            fontSize = 16.sp
                        )
                        Spacer(modifier = Modifier.height(2.dp))
                        Text(
                            text = activeServer?.getSummary() ?: "Tap to choose a proxy profile",
                            color = Color(0xFF94A3B8),
                            fontSize = 13.sp
                        )
                    }

                    Spacer(modifier = Modifier.width(8.dp))

                    if (activeServer != null) {
                        val pingText = viewModel.pingState.value[activeServer.id]
                        val (latStr, latColor) = getPingResultInfo(pingText, activeServer.latency)
                        
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
                                .clickable { viewModel.testServerLatency(activeServer) }
                                .padding(horizontal = 10.dp, vertical = 6.dp)
                        ) {
                            if (pingText == "Testing...") {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                                ) {
                                    CircularProgressIndicator(
                                        color = Color(0xFF38BDF8),
                                        strokeWidth = 1.5.dp,
                                        modifier = Modifier.size(10.dp)
                                    )
                                    Text(
                                        text = "تست...",
                                        color = Color(0xFF38BDF8),
                                        fontWeight = FontWeight.Bold,
                                        fontSize = 10.sp
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
                    } else {
                        Icon(Icons.Default.ChevronRight, contentDescription = "Go", tint = Color(0xFF64748B))
                    }
                }
            }

            // 4. Bandwidth Status Grid
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                BandwidthStatBlock(
                    modifier = Modifier.weight(1f),
                    title = "Download Rate",
                    speed = formatSpeed(metrics.downloadSpeedBps),
                    total = formatBytes(metrics.totalDownloadedBytes),
                    icon = Icons.Default.ArrowDownward,
                    color = Color(0xFF10B981)
                )
                BandwidthStatBlock(
                    modifier = Modifier.weight(1f),
                    title = "Upload Rate",
                    speed = formatSpeed(metrics.uploadSpeedBps),
                    total = formatBytes(metrics.totalUploadedBytes),
                    icon = Icons.Default.ArrowUpward,
                    color = Color(0xFF0EA5E9)
                )
            }

            // 5. Dynamic Grid Action Panel
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 16.dp),
                horizontalArrangement = Arrangement.SpaceAround
            ) {
                ActionButton(
                    title = "Configs",
                    icon = Icons.Default.Dns,
                    tag = "configs_action",
                    onClick = { navController.navigate(Routes.SERVER_LIST) }
                )
                ActionButton(
                    title = "Subs",
                    icon = Icons.Default.SyncAlt,
                    tag = "subs_action",
                    onClick = { navController.navigate(Routes.SUBSCRIPTION) }
                )
                ActionButton(
                    title = "Stats",
                    icon = Icons.Default.DataUsage,
                    tag = "stats_action",
                    onClick = { navController.navigate(Routes.STATS) }
                )
                ActionButton(
                    title = "Settings",
                    icon = Icons.Default.Tune,
                    tag = "settings_action",
                    onClick = { navController.navigate(Routes.SETTINGS) }
                )
                ActionButton(
                    title = "Terminal",
                    icon = Icons.Default.Terminal,
                    tag = "logs_action",
                    onClick = { navController.navigate(Routes.LOGS) }
                )
            }
        }
    }
}
}

@Composable
fun ConnectionOrb(
    status: VpnStatus,
    onToggle: () -> Unit
) {
    val transition = rememberInfiniteTransition(label = "Pulse")
    
    // Scale pulse for visual glow
    val pulseScale by if (status == VpnStatus.CONNECTED) {
        transition.animateFloat(
            initialValue = 1f,
            targetValue = 1.15f,
            animationSpec = infiniteRepeatable(
                animation = tween(1400, easing = EaseInOutSine),
                repeatMode = RepeatMode.Reverse
            ),
            label = "glowingScale"
        )
    } else {
        remember { mutableStateOf(1f) }
    }

    val orbColor by animateColorAsState(
        targetValue = when (status) {
            VpnStatus.CONNECTED -> Color(0xFF10B981) // Emerald
            VpnStatus.CONNECTING -> Color(0xFFF59E0B) // Amber
            else -> Color(0xFF334155) // Slate
        },
        animationSpec = tween(500),
        label = "coreColor"
    )

    Box(
        modifier = Modifier
            .size(240.dp)
            .padding(24.dp),
        contentAlignment = Alignment.Center
    ) {
        // Pulse ring background
        Box(
            modifier = Modifier
                .size(190.dp)
                .scale(pulseScale)
                .background(
                    color = orbColor.copy(alpha = 0.12f),
                    shape = CircleShape
                )
        )

        // Main Button Area (Touch targets requirement: 48dp+, here is ultra-generous 160dp)
        Box(
            modifier = Modifier
                .size(160.dp)
                .shadow(elevation = 12.dp, shape = CircleShape)
                .background(
                    brush = Brush.radialGradient(
                        colors = listOf(orbColor, orbColor.copy(alpha = 0.85f))
                    ),
                    shape = CircleShape
                )
                .border(2.dp, Color.White.copy(alpha = 0.15f), CircleShape)
                .clip(CircleShape)
                .clickable { onToggle() }
                .testTag("connection_orb"),
            contentAlignment = Alignment.Center
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Icon(
                    imageVector = Icons.Default.PowerSettingsNew,
                    contentDescription = "Power Switch",
                    tint = Color.White,
                    modifier = Modifier.size(54.dp)
                )
                Spacer(modifier = Modifier.height(6.dp))
                Text(
                    text = when (status) {
                        VpnStatus.CONNECTED -> "SECURED"
                        VpnStatus.CONNECTING -> "TUNNEL..."
                        else -> "CONNECT"
                    },
                    color = Color.White,
                    fontWeight = FontWeight.Bold,
                    fontSize = 13.sp,
                    letterSpacing = 1.sp
                )
            }
        }
    }
}

@Composable
fun BandwidthStatBlock(
    modifier: Modifier = Modifier,
    title: String,
    speed: String,
    total: String,
    icon: ImageVector,
    color: Color
) {
    Card(
        modifier = modifier.height(100.dp),
        colors = CardDefaults.cardColors(containerColor = Color(0xFF1E293B)),
        shape = RoundedCornerShape(12.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxSize()
                .padding(14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(36.dp)
                    .background(color.copy(alpha = 0.12f), CircleShape),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = icon,
                    contentDescription = title,
                    tint = color,
                    modifier = Modifier.size(18.dp)
                )
            }
            Spacer(modifier = Modifier.width(12.dp))
            Column {
                Text(text = title, color = Color(0xFF64748B), fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
                Spacer(modifier = Modifier.height(2.dp))
                Text(text = speed, color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.Bold)
                Spacer(modifier = Modifier.height(1.dp))
                Text(text = total, color = Color(0xFF94A3B8), fontSize = 11.sp)
            }
        }
    }
}

@Composable
fun ActionButton(
    title: String,
    icon: ImageVector,
    tag: String,
    onClick: () -> Unit
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier
            .width(62.dp)
            .clickable { onClick() }
            .testTag(tag)
    ) {
        Box(
            modifier = Modifier
                .size(46.dp)
                .background(Color(0xFF1E293B), CircleShape)
                .border(1.dp, Color(0xFF334155), CircleShape),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = icon,
                contentDescription = title,
                tint = Color(0xFF38BDF8),
                modifier = Modifier.size(20.dp)
            )
        }
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text = title,
            color = Color(0xFF94A3B8),
            fontSize = 11.sp,
            textAlign = TextAlign.Center,
            fontWeight = FontWeight.Medium
        )
    }
}

// Utility formatting methods
fun formatSpeed(bps: Long): String {
    val kbps = bps / 1024.0
    if (kbps < 1000) {
        return String.format("%.1f KB/s", kbps)
    }
    val mbps = kbps / 1024.0
    return String.format("%.1f MB/s", mbps)
}

fun formatBytes(bytes: Long): String {
    if (bytes < 1024) return "$bytes B"
    val kb = bytes / 1024.0
    if (kb < 1024) return String.format("%.1f KB", kb)
    val mb = kb / 1024.0
    if (mb < 1024) return String.format("%.1f MB", mb)
    val gb = mb / 1024.0
    return String.format("%.1f GB", gb)
}

fun formatDuration(seconds: Long): String {
    val s = seconds % 60
    val m = (seconds / 60) % 60
    val h = seconds / 3600
    return String.format("%02d:%02d:%02d", h, m, s)
}
