package com.example.presentation.screens

import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Save
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavController
import com.example.data.vpn.XrayConfigGenerator
import com.example.domain.model.ProxyProtocol
import com.example.domain.model.ServerConfig
import com.example.presentation.navigation.Routes
import com.example.presentation.viewmodel.VpnViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AddEditServerScreen(
    navController: NavController,
    viewModel: VpnViewModel
) {
    val context = LocalContext.current
    val scrollState = rememberScrollState()

    var name by remember { mutableStateOf("New Server") }
    var protocol by remember { mutableStateOf(ProxyProtocol.VMESS) }
    var address by remember { mutableStateOf("1.2.3.4") }
    var portString by remember { mutableStateOf("443") }
    var uuid by remember { mutableStateOf(java.util.UUID.randomUUID().toString()) }
    var password by remember { mutableStateOf("") }
    var security by remember { mutableStateOf("tls") }
    var network by remember { mutableStateOf("ws") }
    var sni by remember { mutableStateOf("") }
    var path by remember { mutableStateOf("/ws-tunnel") }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Add Config", fontWeight = FontWeight.Bold, color = Color.White) },
                navigationIcon = {
                    IconButton(onClick = { navController.navigate(Routes.SERVER_LIST) {
                        popUpTo(Routes.SERVER_LIST) { inclusive = true }
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
            Spacer(modifier = Modifier.height(12.dp))

            // 1. Selector Protocol
            Text("Protocol", color = Color(0xFF94A3B8), fontSize = 13.sp, fontWeight = FontWeight.Bold)
            Spacer(modifier = Modifier.height(8.dp))
            
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(8.dp))
                    .background(Color(0xFF1E293B))
                    .padding(4.dp),
                horizontalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                ProxyProtocol.values().forEach { protocolOption ->
                    val isSelected = protocol == protocolOption
                    Button(
                        modifier = Modifier.weight(1f).height(38.dp),
                        onClick = { protocol = protocolOption },
                        contentPadding = PaddingValues(0.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = if (isSelected) Color(0xFF38BDF8) else Color.Transparent,
                            contentColor = if (isSelected) Color.White else Color(0xFF94A3B8)
                        ),
                        shape = RoundedCornerShape(6.dp)
                    ) {
                        Text(text = protocolOption.name, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                    }
                }
            }

            Spacer(modifier = Modifier.height(20.dp))

            // 2. Main Parameters Form
            FormTextField(value = name, onValueChange = { name = it }, label = "Profile Name", tag = "form_name")
            FormTextField(value = address, onValueChange = { address = it }, label = "Server Address / IP", tag = "form_address")
            FormTextField(
                value = portString, 
                onValueChange = { portString = it }, 
                label = "Server Port", 
                keyboardType = KeyboardType.Number,
                tag = "form_port"
            )

            // 3. Conditional Security credentials
            when (protocol) {
                ProxyProtocol.VMESS, ProxyProtocol.VLESS -> {
                    FormTextField(value = uuid, onValueChange = { uuid = it }, label = "UUID", tag = "form_uuid")
                }
                ProxyProtocol.TROJAN, ProxyProtocol.SHADOWSOCKS -> {
                    FormTextField(
                        value = password, 
                        onValueChange = { password = it }, 
                        label = "Credentials Password", 
                        visualTransformation = PasswordVisualTransformation(),
                        tag = "form_password"
                    )
                }
                ProxyProtocol.SOCKS -> {} // No credentials for basic socks
            }

            Spacer(modifier = Modifier.height(12.dp))
            Divider(color = Color(0xFF334155))
            Spacer(modifier = Modifier.height(16.dp))

            // 4. Transport settings parameters
            Text("Stream Transport Options", color = Color(0xFF38BDF8), fontSize = 14.sp, fontWeight = FontWeight.Bold)
            Spacer(modifier = Modifier.height(16.dp))

            FormTextField(value = security, onValueChange = { security = it }, label = "Security (none, tls, xtls)", tag = "form_security")
            FormTextField(value = network, onValueChange = { network = it }, label = "Network protocol (tcp, ws, grpc)", tag = "form_network")
            FormTextField(value = sni, onValueChange = { sni = it }, label = "Server SNI (Optional)", tag = "form_sni")
            
            if (network == "ws" || network == "grpc") {
                FormTextField(
                    value = path, 
                    onValueChange = { path = it }, 
                    label = if (network == "ws") "WebSocket Request Path" else "gRPC Service Name",
                    tag = "form_path"
                )
            }

            Spacer(modifier = Modifier.height(32.dp))

            // 5. Submit Action Button
            Button(
                onClick = {
                    val parsedPort = portString.toIntOrNull() ?: 0
                    val server = ServerConfig(
                        name = name,
                        protocol = protocol,
                        address = address,
                        port = parsedPort,
                        uuid = uuid,
                        password = password,
                        security = security,
                        network = network,
                        sni = sni,
                        path = path
                    )
                    val validationResult = XrayConfigGenerator.validate(server)
                    if (validationResult.first) {
                        viewModel.insertServer(server)
                        Toast.makeText(context, "Server configuration saved successfully", Toast.LENGTH_SHORT).show()
                        navController.navigate(Routes.SERVER_LIST) {
                            popUpTo(Routes.SERVER_LIST) { inclusive = true }
                        }
                    } else {
                        Toast.makeText(context, "Error: ${validationResult.second}", Toast.LENGTH_LONG).show()
                    }
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(50.dp)
                    .testTag("submit_config_button"),
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF10B981)),
                shape = RoundedCornerShape(12.dp)
            ) {
                Icon(Icons.Default.Save, contentDescription = "Save Profile")
                Spacer(modifier = Modifier.width(8.dp))
                Text("Save Server Configuration", fontWeight = FontWeight.Bold, fontSize = 15.sp)
            }
            
            Spacer(modifier = Modifier.height(48.dp))
        }
    }
}

@Composable
fun FormTextField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    keyboardType: KeyboardType = KeyboardType.Text,
    visualTransformation: androidx.compose.ui.text.input.VisualTransformation = androidx.compose.ui.text.input.VisualTransformation.None,
    tag: String
) {
    Column(modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
        Text(text = label, color = Color(0xFF94A3B8), fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
        Spacer(modifier = Modifier.height(6.dp))
        TextField(
            value = value,
            onValueChange = onValueChange,
            visualTransformation = visualTransformation,
            keyboardOptions = KeyboardOptions(keyboardType = keyboardType),
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(8.dp))
                .testTag(tag),
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
    }
}
