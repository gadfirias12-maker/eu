package com.example.presentation.screens

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material.icons.filled.LibraryBooks
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
import com.example.domain.model.AppLog
import com.example.domain.model.LogLevel
import com.example.presentation.navigation.Routes
import com.example.presentation.viewmodel.VpnViewModel
import java.text.SimpleDateFormat
import java.util.*

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LogsScreen(
    navController: NavController,
    viewModel: VpnViewModel
) {
    val context = LocalContext.current
    val logs by viewModel.logs.collectAsState()
    val listState = rememberLazyListState()

    // Scroll automatically to latest line as logs insert
    LaunchedEffect(logs.size) {
        if (logs.isNotEmpty()) {
            listState.animateScrollToItem(0) // 0 is top because we query descending
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Daemon Terminal Console", fontWeight = FontWeight.Bold, color = Color.White) },
                navigationIcon = {
                    IconButton(onClick = { navController.navigate(Routes.HOME) {
                        popUpTo(Routes.HOME) { inclusive = true }
                    }}) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "Back", tint = Color.White)
                    }
                },
                actions = {
                    // Copy All Action
                    IconButton(onClick = {
                        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                        val textDump = logs.joinToString("\n") { log ->
                            "[${formatTime(log.timestamp)}] [${log.level}] [${log.tag}] ${log.message}"
                        }
                        clipboard.setPrimaryClip(ClipData.newPlainText("Xray logs", textDump))
                        Toast.makeText(context, "Terminal logs copied to clipboard", Toast.LENGTH_SHORT).show()
                    }) {
                        Icon(Icons.Default.LibraryBooks, contentDescription = "Copy all logs", tint = Color.White)
                    }
                    // Clear logs
                    IconButton(onClick = { 
                        viewModel.clearLogs()
                        Toast.makeText(context, "Log database cleared", Toast.LENGTH_SHORT).show()
                    }) {
                        Icon(Icons.Default.DeleteSweep, contentDescription = "Clear logs", tint = Color(0xFFEF4444))
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Color(0xFF0F172A))
            )
        },
        containerColor = Color(0xFF020617) // Pure dark slate
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .padding(horizontal = 14.dp)
        ) {
            Spacer(modifier = Modifier.height(10.dp))

            // Subtitle tag
            Text(
                "Real-time Xray-core client events and network tunnel handshake diagnostic output:",
                color = Color(0xFF64748B),
                fontSize = 11.sp,
                modifier = Modifier.padding(bottom = 10.dp)
            )

            if (logs.isEmpty()) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f)
                        .clip(RoundedCornerShape(8.dp))
                        .background(Color(0xFF0B0F19)),
                    contentAlignment = Alignment.Center
                ) {
                    Text("Console output empty. Handshake events appear here.", color = Color(0xFF334155), fontSize = 13.sp)
                }
            } else {
                LazyColumn(
                    state = listState,
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f)
                        .clip(RoundedCornerShape(8.dp))
                        .background(Color(0xFF0F172A))
                        .padding(10.dp)
                        .testTag("logs_terminal_list")
                ) {
                    items(logs) { log ->
                        TerminalLogLine(log = log)
                    }
                }
            }
            Spacer(modifier = Modifier.height(14.dp))
        }
    }
}

@Composable
fun TerminalLogLine(log: AppLog) {
    val levelColor = when (log.level) {
        LogLevel.INFO -> Color(0xFF94A3B8)       // Slate 400
        LogLevel.WARNING -> Color(0xFFF59E0B)    // Amber 500
        LogLevel.ERROR -> Color(0xFFEF4444)      // Red 500
        LogLevel.SUCCESS -> Color(0xFF10B981)    // Emerald 500
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 2.dp)
    ) {
        Row(
            verticalAlignment = Alignment.Top
        ) {
            // Timestamp
            Text(
                text = "${formatTime(log.timestamp)} ",
                color = Color(0xFF475569), // Muted timestamp blue
                fontSize = 10.sp,
                fontFamily = FontFamily.Monospace
            )

            // Log Tag + Level
            Text(
                text = "[${log.level.name}] ",
                color = levelColor,
                fontSize = 10.sp,
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.Bold
            )

            // Message
            Text(
                text = log.message,
                color = if (log.level == LogLevel.ERROR) Color(0xFFFCA5A5) else Color(0xFFE2E8F0),
                fontSize = 12.sp,
                fontFamily = FontFamily.Monospace,
                lineHeight = 16.sp,
                modifier = Modifier.weight(1f)
            )
        }
    }
}

private fun formatTime(timestamp: Long): String {
    val formatter = SimpleDateFormat("HH:mm:ss.SSS", Locale.getDefault())
    return formatter.format(Date(timestamp))
}
