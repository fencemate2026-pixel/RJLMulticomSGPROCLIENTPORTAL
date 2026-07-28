package com.example.rjlmulticomsg_proclientportal.ui.help

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.rjlmulticomsg_proclientportal.data.remote.GrokHelpClient
import com.example.rjlmulticomsg_proclientportal.ui.components.PortalHeader
import com.example.rjlmulticomsg_proclientportal.ui.theme.MulticomRed
import com.example.rjlmulticomsg_proclientportal.ui.theme.NeonBlue
import com.example.rjlmulticomsg_proclientportal.ui.theme.TextMuted
import kotlinx.coroutines.launch

private data class UiChatMessage(
    val id: Long,
    val role: String,
    val text: String
)

@Composable
fun HelpAssistantScreen(
    onBack: () -> Unit
) {
    val client = remember { GrokHelpClient() }
    val scope = rememberCoroutineScope()
    val messages = remember {
        mutableStateListOf(
            UiChatMessage(
                id = 0L,
                role = "assistant",
                text = "Hi — I'm Grok, your Multicom SG‑PRO Client Portal helper.\n\n" +
                    "Ask me anything about using the app: opening the gate, GSM callers, " +
                    "modules, people, schedules, login, or settings."
            )
        )
    }
    var input by remember { mutableStateOf("") }
    var loading by remember { mutableStateOf(false) }
    val listState = rememberLazyListState()

    LaunchedEffect(messages.size) {
        if (messages.isNotEmpty()) {
            listState.animateScrollToItem(messages.lastIndex)
        }
    }

    fun send() {
        val text = input.trim()
        if (text.isEmpty() || loading) return
        input = ""
        messages.add(
            UiChatMessage(
                id = System.currentTimeMillis(),
                role = "user",
                text = text
            )
        )
        loading = true
        scope.launch {
            val history = messages.dropLast(1).map {
                GrokHelpClient.ChatMessage(role = it.role, content = it.text)
            }
            val result = client.ask(text, history)
            loading = false
            val reply = result.getOrElse { e ->
                e.message ?: "Sorry — I couldn't answer that right now."
            }
            messages.add(
                UiChatMessage(
                    id = System.currentTimeMillis() + 1,
                    role = "assistant",
                    text = reply
                )
            )
        }
    }

    Column(Modifier.fillMaxSize()) {
        PortalHeader(
            title = "Grok help",
            subtitle = if (client.isConfigured) {
                "Secure cloud AI with offline fallback"
            } else {
                "Offline help"
            },
            onBack = onBack
        )

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp, vertical = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            listOf(
                "How do I open the gate?",
                "Add a GSM caller",
                "Enable modules"
            ).forEach { tip ->
                TextButton(
                    onClick = {
                        if (!loading) {
                            input = tip
                            send()
                        }
                    },
                    modifier = Modifier.weight(1f)
                ) {
                    Text(tip, fontSize = 10.sp, color = MulticomRed, maxLines = 2)
                }
            }
        }

        LazyColumn(
            state = listState,
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .background(Color(0xFFF5F6F8))
                .padding(horizontal = 12.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            items(messages, key = { it.id }) { msg ->
                val isUser = msg.role == "user"
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = if (isUser) Arrangement.End else Arrangement.Start
                ) {
                    val shape = RoundedCornerShape(
                        topStart = 14.dp,
                        topEnd = 14.dp,
                        bottomStart = if (isUser) 14.dp else 4.dp,
                        bottomEnd = if (isUser) 4.dp else 14.dp
                    )
                    Column(
                        modifier = Modifier
                            .widthIn(max = 320.dp)
                            .clip(shape)
                            .background(if (isUser) MulticomRed else Color.White)
                            .padding(12.dp)
                    ) {
                        if (!isUser) {
                            Text(
                                text = "Grok",
                                color = NeonBlue,
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold
                            )
                            Spacer(Modifier.height(4.dp))
                        }
                        Text(
                            text = msg.text,
                            color = if (isUser) Color.White else Color(0xFF1E293B),
                            fontSize = 14.sp,
                            lineHeight = 20.sp
                        )
                    }
                }
            }
            if (loading) {
                item {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(18.dp),
                            strokeWidth = 2.dp,
                            color = MulticomRed
                        )
                        Spacer(Modifier.size(8.dp))
                        Text(
                            text = "Grok is thinking…",
                            color = TextMuted,
                            fontSize = 13.sp
                        )
                    }
                }
            }
        }

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(Color.White)
                .padding(10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            OutlinedTextField(
                value = input,
                onValueChange = { input = it },
                modifier = Modifier.weight(1f),
                placeholder = { Text("Ask how to use the app…") },
                singleLine = true,
                enabled = !loading,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                keyboardActions = KeyboardActions(onSend = { send() })
            )
            IconButton(
                onClick = { send() },
                enabled = !loading && input.isNotBlank()
            ) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.Send,
                    contentDescription = "Send",
                    tint = if (!loading && input.isNotBlank()) MulticomRed else TextMuted
                )
            }
        }
    }
}
