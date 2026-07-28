package com.example.rjlmulticomsg_proclientportal.ui.help

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.SmartToy
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
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
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.rjlmulticomsg_proclientportal.data.remote.GrokHelpClient
import com.example.rjlmulticomsg_proclientportal.domain.model.SessionState
import com.example.rjlmulticomsg_proclientportal.ui.theme.MulticomRed
import com.example.rjlmulticomsg_proclientportal.ui.theme.NeonBlue
import com.example.rjlmulticomsg_proclientportal.ui.theme.TextMuted
import kotlinx.coroutines.launch

private data class FloatingChatMessage(
    val id: Long,
    val role: String,
    val text: String
)

/**
 * One assistant host for the whole app. Because this composable sits above route content,
 * its sheet/open state and conversation survive navigation between portal pages.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FloatingAssistantHost(
    routeTitle: String,
    session: SessionState,
    bottomBarVisible: Boolean,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit
) {
    val client = remember { GrokHelpClient() }
    val scope = rememberCoroutineScope()
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var open by remember { mutableStateOf(false) }
    var input by remember { mutableStateOf("") }
    var loading by remember { mutableStateOf(false) }
    val messages = remember {
        mutableStateListOf(
            FloatingChatMessage(
                id = 0L,
                role = "assistant",
                text = "Hi — I’m your **SG-PRO assistant**.\n\n" +
                    "I can help with the page you’re on, gate access, callers, " +
                    "schedules, messages, device status and settings."
            )
        )
    }
    val listState = rememberLazyListState()

    fun safeContext(): String {
        val account = session.account
        return buildString {
            append("Current page: $routeTitle. ")
            if (session.isLoggedIn) {
                append("Signed-in role: ${session.user?.role?.name ?: "unknown"}. ")
                append("Site label: ${account?.siteName?.take(120).orEmpty()}. ")
                append(
                    "Enabled modules: " +
                        session.enabledModules.joinToString { it.shortLabel }.ifBlank { "none" } +
                        ". "
                )
                append("Connection type: ${account?.connectionType?.name ?: "unknown"}.")
            } else {
                append(
                    "The user is not signed in. Only provide generic login, password-reset, " +
                        "PIN and RJL contact guidance. Do not reveal property information."
                )
            }
        }
    }

    fun send() {
        val text = input.trim()
        if (text.isEmpty() || loading) return
        input = ""
        messages.add(
            FloatingChatMessage(
                id = System.nanoTime(),
                role = "user",
                text = text
            )
        )
        loading = true
        scope.launch {
            val history = messages.dropLast(1).map {
                GrokHelpClient.ChatMessage(role = it.role, content = it.text)
            }
            val result = client.ask(
                userMessage = text,
                history = history,
                pageContext = safeContext()
            )
            loading = false
            messages.add(
                FloatingChatMessage(
                    id = System.nanoTime(),
                    role = "assistant",
                    text = result.getOrElse {
                        it.message ?: "I couldn’t answer that right now. Please try again."
                    }
                )
            )
        }
    }

    LaunchedEffect(messages.size) {
        if (messages.isNotEmpty()) listState.animateScrollToItem(messages.lastIndex)
    }

    Box(modifier.fillMaxSize()) {
        content()

        FloatingActionButton(
            onClick = { open = true },
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .navigationBarsPadding()
                .padding(
                    end = 18.dp,
                    bottom = if (bottomBarVisible) 94.dp else 18.dp
                )
                .size(62.dp),
            shape = CircleShape,
            containerColor = MulticomRed,
            contentColor = Color.White
        ) {
            Icon(
                imageVector = Icons.Default.SmartToy,
                contentDescription = "Open SG-PRO assistant",
                modifier = Modifier.size(30.dp)
            )
        }
    }

    if (open) {
        ModalBottomSheet(
            onDismissRequest = { open = false },
            sheetState = sheetState,
            containerColor = Color.White,
            modifier = Modifier.imePadding()
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(650.dp)
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Surface(
                        shape = CircleShape,
                        color = MulticomRed.copy(alpha = 0.12f),
                        modifier = Modifier.size(44.dp)
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Icon(
                                Icons.Default.SmartToy,
                                contentDescription = null,
                                tint = MulticomRed
                            )
                        }
                    }
                    Column(
                        modifier = Modifier
                            .weight(1f)
                            .padding(start = 12.dp)
                    ) {
                        Text(
                            "SG-PRO Assistant",
                            fontWeight = FontWeight.Bold,
                            fontSize = 19.sp
                        )
                        Text(
                            if (client.isConfigured) {
                                "Secure cloud AI · $routeTitle"
                            } else {
                                "Offline help · $routeTitle"
                            },
                            color = TextMuted,
                            fontSize = 12.sp
                        )
                    }
                    IconButton(onClick = { open = false }) {
                        Icon(Icons.Default.Close, contentDescription = "Close assistant")
                    }
                }

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 10.dp),
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    suggestedQuestions(routeTitle).forEach { suggestion ->
                        TextButton(
                            onClick = {
                                if (!loading) {
                                    input = suggestion
                                    send()
                                }
                            },
                            modifier = Modifier.weight(1f)
                        ) {
                            Text(
                                suggestion,
                                maxLines = 2,
                                fontSize = 10.sp,
                                color = MulticomRed
                            )
                        }
                    }
                }

                LazyColumn(
                    state = listState,
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth()
                        .background(Color(0xFFF4F6F8))
                        .padding(horizontal = 12.dp, vertical = 10.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    items(messages, key = { it.id }) { message ->
                        val user = message.role == "user"
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement =
                                if (user) Arrangement.End else Arrangement.Start
                        ) {
                            Column(
                                modifier = Modifier
                                    .widthIn(max = 340.dp)
                                    .clip(RoundedCornerShape(16.dp))
                                    .background(if (user) MulticomRed else Color.White)
                                    .padding(13.dp)
                            ) {
                                if (!user) {
                                    Text(
                                        "SG-PRO",
                                        color = NeonBlue,
                                        fontWeight = FontWeight.Bold,
                                        fontSize = 11.sp
                                    )
                                    Spacer(Modifier.height(4.dp))
                                }
                                Text(
                                    text = assistantAnnotatedText(message.text),
                                    color = if (user) Color.White else Color(0xFF172033),
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
                                Text("Thinking…", color = TextMuted, fontSize = 13.sp)
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
                        placeholder = { Text("Ask about $routeTitle…") },
                        enabled = !loading,
                        maxLines = 4,
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                        keyboardActions = KeyboardActions(onSend = { send() })
                    )
                    IconButton(
                        onClick = { send() },
                        enabled = input.isNotBlank() && !loading
                    ) {
                        Icon(
                            Icons.AutoMirrored.Filled.Send,
                            contentDescription = "Send",
                            tint = if (input.isNotBlank() && !loading) MulticomRed else TextMuted
                        )
                    }
                }
            }
        }
    }
}

private fun suggestedQuestions(routeTitle: String): List<String> = when (routeTitle) {
    "Login" -> listOf("How do I sign in?", "Forgot password", "Contact RJL")
    "GSM" -> listOf("Add a caller", "Why offline?", "How sync works")
    "Settings" -> listOf("Change PIN", "Enable modules", "Sign out")
    "Logs" -> listOf("Explain status", "Failed opens", "Device offline")
    else -> listOf("What can I do here?", "Open the gate", "Check device")
}

/** Minimal safe Markdown: renders **bold** and leaves all other text literal. */
internal fun assistantAnnotatedText(raw: String): AnnotatedString = buildAnnotatedString {
    var cursor = 0
    while (cursor < raw.length) {
        val open = raw.indexOf("**", cursor)
        if (open < 0) {
            append(raw.substring(cursor))
            break
        }
        append(raw.substring(cursor, open))
        val close = raw.indexOf("**", open + 2)
        if (close < 0) {
            append(raw.substring(open))
            break
        }
        pushStyle(SpanStyle(fontWeight = FontWeight.Bold))
        append(raw.substring(open + 2, close))
        pop()
        cursor = close + 2
    }
}
