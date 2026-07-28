@file:Suppress("DEPRECATION", "DEPRECATION")

package com.example.rjlmulticomsg_proclientportal.ui.login

import android.annotation.SuppressLint
import android.app.Activity
import android.util.Log
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Email
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.rjlmulticomsg_proclientportal.R
import com.example.rjlmulticomsg_proclientportal.data.repo.PortalRepository
import com.example.rjlmulticomsg_proclientportal.ui.theme.MulticomRed
import com.google.android.gms.auth.api.signin.GoogleSignIn
import com.google.android.gms.auth.api.signin.GoogleSignInOptions
import com.google.android.gms.common.api.ApiException
import kotlinx.coroutines.launch

@SuppressLint("DiscouragedApi", "LocalContextResourcesRead")
@Suppress("DEPRECATION")
@Composable
fun LoginScreen(
    repository: PortalRepository,
    onLoggedIn: () -> Unit
) {
    val scope = rememberCoroutineScope()
    val focusManager = LocalFocusManager.current
    val context = LocalContext.current

    var email by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var rememberMe by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var info by remember { mutableStateOf<String?>(null) }
    var loading by remember { mutableStateOf(false) }
    var showResetDialog by remember { mutableStateOf(false) }
    var resetEmail by remember { mutableStateOf("") }
    var resetBusy by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        rememberMe = repository.rememberMe()
        if (rememberMe) {
            repository.rememberedEmail()
                .takeIf(String::isNotBlank)
                ?.let { email = it }
        }
        password = ""
    }

    val webClientId = remember(context.packageName) {
        runCatching {
            val resourceId = context.resources.getIdentifier(
                "default_web_client_id",
                "string",
                context.packageName
            )
            if (resourceId == 0) "" else context.getString(resourceId)
        }.getOrDefault("")
    }

    val googleClient = remember(webClientId) {
        if (webClientId.isBlank()) {
            null
        } else {
            val options = GoogleSignInOptions.Builder(
                GoogleSignInOptions.DEFAULT_SIGN_IN
            )
                .requestIdToken(webClientId)
                .requestEmail()
                .build()

            GoogleSignIn.getClient(context, options)
        }
    }

    val googleLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode != Activity.RESULT_OK) {
            loading = false
            return@rememberLauncherForActivityResult
        }

        val task = GoogleSignIn.getSignedInAccountFromIntent(result.data)

        scope.launch {
            try {
                val account = task.getResult(ApiException::class.java)
                val token = account?.idToken

                if (token.isNullOrBlank()) {
                    loading = false
                    error = "Google sign-in failed."
                    return@launch
                }

                val loginResult = repository.loginWithGoogleIdToken(
                    idToken = token,
                    remember = true
                )

                loading = false

                loginResult
                    .onSuccess {
                        if (repository.session.value.isLoggedIn) {
                            onLoggedIn()
                        } else {
                            error = "The session did not start. Restart the app."
                        }
                    }
                    .onFailure {
                        error = it.message ?: "Google sign-in failed."
                    }
            } catch (exception: ApiException) {
                loading = false
                Log.w(
                    "LoginScreen",
                    "Google sign-in failed: ${exception.statusCode}",
                    exception
                )
                error = when (exception.statusCode) {
                    10 -> "Google sign-in is not configured. Use email sign-in."
                    12501 -> "Google sign-in was cancelled."
                    else -> "Google sign-in failed (${exception.statusCode}). Please try again or use email login."
                }
            } catch (exception: Exception) {
                loading = false
                Log.e("LoginScreen", "Google sign-in error", exception)
                error = exception.message ?: "Google sign-in failed. Please try again."
            }
        }
    }

    fun submitEmail() {
        focusManager.clearFocus()
        error = null
        info = null

        if (email.isBlank() || password.isBlank()) {
            error = "Enter your email and password."
            return
        }

        loading = true

        scope.launch {
            val result = repository.login(
                email = email.trim(),
                password = password,
                remember = rememberMe
            )

            loading = false

            result
                .onSuccess {
                    if (repository.session.value.isLoggedIn) {
                        onLoggedIn()
                    } else {
                        error = "The session did not start. Restart the app."
                    }
                }
                .onFailure {
                    error = it.message ?: "Sign in failed."
                }
        }
    }

    fun startGoogleSignIn() {
        error = null
        info = null

        if (googleClient == null) {
            error = "Google sign-in is unavailable."
            return
        }

        loading = true

        googleClient.signOut().addOnCompleteListener {
            googleLauncher.launch(googleClient.signInIntent)
        }
    }

    fun sendPasswordReset() {
        val targetEmail = resetEmail.trim().ifBlank { email.trim() }

        if (targetEmail.isBlank()) {
            error = "Enter your email address first."
            return
        }

        resetBusy = true
        error = null
        info = null

        scope.launch {
            val result = repository.sendPasswordReset(targetEmail)

            resetBusy = false

            result
                .onSuccess {
                    showResetDialog = false
                    info = "If the account exists, a reset email was sent."
                }
                .onFailure {
                    error = it.message ?: "The reset email could not be sent."
                }
        }
    }

    LoginScreenContent(
        email = email,
        onEmailChange = {
            email = it
            error = null
            info = null
        },
        password = password,
        onPasswordChange = {
            password = it
            error = null
            info = null
        },
        rememberMe = rememberMe,
        onRememberMeChange = { rememberMe = it },
        error = error,
        info = info,
        loading = loading,
        showResetDialog = showResetDialog,
        onResetDialogDismiss = {
            if (!resetBusy) {
                showResetDialog = false
            }
        },
        resetEmail = resetEmail,
        onResetEmailChange = { resetEmail = it },
        resetBusy = resetBusy,
        onResetSubmit = { sendPasswordReset() },
        onForgotPasswordClick = {
            resetEmail = email
            showResetDialog = true
        },
        onSubmit = { submitEmail() },
        showGoogleSignIn = googleClient != null,
        onGoogleSignIn = { startGoogleSignIn() }
    )
}

@Composable
private fun LoginScreenContent(
    email: String,
    onEmailChange: (String) -> Unit,
    password: String,
    onPasswordChange: (String) -> Unit,
    rememberMe: Boolean,
    onRememberMeChange: (Boolean) -> Unit,
    error: String?,
    info: String?,
    loading: Boolean,
    showResetDialog: Boolean,
    onResetDialogDismiss: () -> Unit,
    resetEmail: String,
    onResetEmailChange: (String) -> Unit,
    resetBusy: Boolean,
    onResetSubmit: () -> Unit,
    onForgotPasswordClick: () -> Unit,
    onSubmit: () -> Unit,
    showGoogleSignIn: Boolean,
    onGoogleSignIn: () -> Unit
) {
    if (showResetDialog) {
        AlertDialog(
            onDismissRequest = onResetDialogDismiss,
            title = {
                Text("Reset password")
            },
            text = {
                Column {
                    Text("Enter the email address for your RJL portal account.")
                    Spacer(modifier = Modifier.height(12.dp))
                    TextField(
                        value = resetEmail,
                        onValueChange = onResetEmailChange,
                        enabled = !resetBusy,
                        singleLine = true,
                        placeholder = { Text("Email address") },
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            },
            confirmButton = {
                TextButton(
                    onClick = onResetSubmit,
                    enabled = !resetBusy
                ) {
                    Text("Send link", color = MulticomRed)
                }
            },
            dismissButton = {
                TextButton(
                    onClick = onResetDialogDismiss,
                    enabled = !resetBusy
                ) {
                    Text("Cancel")
                }
            }
        )
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFF03070C))
    ) {
        Image(
            painter = painterResource(R.drawable.login_gate_clean),
            contentDescription = null,
            contentScale = ContentScale.Crop,
            alignment = Alignment.TopCenter,
            modifier = Modifier.fillMaxSize()
        )

        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    Brush.horizontalGradient(
                        listOf(
                            Color(0x88000000),
                            Color.Transparent,
                            Color(0x66000000)
                        )
                    )
                )
        )

        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .statusBarsPadding()
                .navigationBarsPadding()
                .padding(top = 12.dp, bottom = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Image(
                painter = painterResource(R.drawable.rjl_portal_logo),
                contentDescription = "RJL Multicom SG-PRO",
                contentScale = ContentScale.Fit,
                modifier = Modifier
                    .width(230.dp)
                    .height(142.dp)
            )

            Text(
                text = "CLIENT PORTAL",
                color = Color.White,
                fontSize = 30.sp,
                fontWeight = FontWeight.Black,
                letterSpacing = 2.sp,
                textAlign = TextAlign.Center
            )

            Spacer(modifier = Modifier.height(6.dp))

            Text(
                text = "Secure access for the RJL team",
                color = Color(0xFFB5BEC9),
                fontSize = 14.sp,
                textAlign = TextAlign.Center
            )

            Spacer(modifier = Modifier.height(24.dp))

            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp),
                shape = RoundedCornerShape(24.dp),
                color = Color(0xE8121820),
                border = BorderStroke(1.dp, Color(0xFF303946)),
                shadowElevation = 12.dp
            ) {
                Column(
                    modifier = Modifier.padding(20.dp)
                ) {
                    Text(
                        text = "SIGN IN TO CONTINUE",
                        color = Color(0xFF9EA8B5),
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold,
                        letterSpacing = 2.sp
                    )

                    Spacer(modifier = Modifier.height(16.dp))

                    LoginField(
                        value = email,
                        onValueChange = onEmailChange,
                        label = "Email address",
                        icon = Icons.Default.Email,
                        keyboardType = KeyboardType.Email,
                        imeAction = ImeAction.Next,
                        enabled = !loading
                    )

                    Spacer(modifier = Modifier.height(12.dp))

                    LoginField(
                        value = password,
                        onValueChange = onPasswordChange,
                        label = "Password",
                        icon = Icons.Default.Lock,
                        keyboardType = KeyboardType.Password,
                        imeAction = ImeAction.Done,
                        isPassword = true,
                        enabled = !loading,
                        onDone = onSubmit
                    )

                    Spacer(modifier = Modifier.height(4.dp))

                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Checkbox(
                            checked = rememberMe,
                            onCheckedChange = onRememberMeChange,
                            enabled = !loading,
                            colors = CheckboxDefaults.colors(
                                checkedColor = MulticomRed,
                                uncheckedColor = Color(0xFF748092),
                                checkmarkColor = Color.White
                            )
                        )

                        Text(
                            text = "Remember me",
                            color = Color(0xFFD1D7E0),
                            fontSize = 13.sp,
                            modifier = Modifier
                                .weight(1f)
                                .clickable(enabled = !loading) {
                                    onRememberMeChange(!rememberMe)
                                }
                        )

                        Text(
                            text = "Forgot password?",
                            color = MulticomRed,
                            fontSize = 13.sp,
                            fontWeight = FontWeight.SemiBold,
                            modifier = Modifier
                                .clickable(enabled = !loading) {
                                    onForgotPasswordClick()
                                }
                                .padding(vertical = 10.dp)
                        )
                    }

                    error?.let { message ->
                        Text(
                            text = message,
                            color = Color(0xFFFF7373),
                            fontSize = 13.sp,
                            fontWeight = FontWeight.SemiBold,
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(top = 2.dp, bottom = 8.dp)
                        )
                    }

                    info?.let { message ->
                        Text(
                            text = message,
                            color = Color(0xFF86E6A6),
                            fontSize = 13.sp,
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(top = 2.dp, bottom = 8.dp)
                        )
                    }

                    Button(
                        onClick = onSubmit,
                        enabled = !loading,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(56.dp),
                        shape = RoundedCornerShape(14.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = MulticomRed,
                            contentColor = Color.White,
                            disabledContainerColor = MulticomRed.copy(alpha = 0.55f)
                        )
                    ) {
                        if (loading) {
                            CircularProgressIndicator(
                                color = Color.White,
                                strokeWidth = 2.dp,
                                modifier = Modifier.size(22.dp)
                            )
                        } else {
                            Text(
                                text = "SIGN IN",
                                fontSize = 15.sp,
                                fontWeight = FontWeight.Bold,
                                letterSpacing = 1.4.sp
                            )
                        }
                    }

                    if (showGoogleSignIn) {
                        Spacer(modifier = Modifier.height(18.dp))

                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            HorizontalDivider(
                                modifier = Modifier.weight(1f),
                                color = Color(0xFF343D49)
                            )
                            Text(
                                text = "  OR  ",
                                color = Color(0xFF8994A3),
                                fontSize = 12.sp
                            )
                            HorizontalDivider(
                                modifier = Modifier.weight(1f),
                                color = Color(0xFF343D49)
                            )
                        }

                        Spacer(modifier = Modifier.height(14.dp))

                        OutlinedButton(
                            onClick = onGoogleSignIn,
                            enabled = !loading,
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(54.dp),
                            shape = RoundedCornerShape(14.dp),
                            border = BorderStroke(1.5.dp, MulticomRed),
                            colors = ButtonDefaults.outlinedButtonColors(
                                contentColor = Color.White
                            )
                        ) {
                            Text(
                                text = "Continue with Google",
                                fontSize = 14.sp,
                                fontWeight = FontWeight.SemiBold
                            )
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(20.dp))

            Text(
                text = "Need help? Contact RJL Commercial for portal access.",
                color = Color(0xFF8490A0),
                fontSize = 11.sp,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(horizontal = 24.dp)
            )

            Spacer(modifier = Modifier.height(8.dp))

            Text(
                text = "RJL Multicom SG-PRO  •  Access Control",
                color = Color(0xFF566171),
                fontSize = 10.sp,
                textAlign = TextAlign.Center
            )
        }
    }
}

@Composable
private fun LoginField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    icon: ImageVector,
    keyboardType: KeyboardType,
    imeAction: ImeAction,
    isPassword: Boolean = false,
    enabled: Boolean = true,
    onDone: (() -> Unit)? = null
) {
    TextField(
        value = value,
        onValueChange = onValueChange,
        enabled = enabled,
        singleLine = true,
        visualTransformation = if (isPassword) {
            PasswordVisualTransformation()
        } else {
            VisualTransformation.None
        },
        leadingIcon = {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = Color(0xFFAAB3BF),
                modifier = Modifier.size(21.dp)
            )
        },
        label = {
            Text(label)
        },
        modifier = Modifier
            .fillMaxWidth()
            .height(62.dp),
        shape = RoundedCornerShape(14.dp),
        colors = TextFieldDefaults.colors(
            focusedTextColor = Color.White,
            unfocusedTextColor = Color.White,
            disabledTextColor = Color.White.copy(alpha = 0.65f),
            focusedLabelColor = Color(0xFFDDE3EA),
            unfocusedLabelColor = Color(0xFF8E99A8),
            focusedContainerColor = Color(0xFF1A212B),
            unfocusedContainerColor = Color(0xFF171E27),
            disabledContainerColor = Color(0xFF171E27),
            cursorColor = MulticomRed,
            focusedIndicatorColor = MulticomRed,
            unfocusedIndicatorColor = Color(0xFF3A4553),
            disabledIndicatorColor = Color(0xFF303946)
        ),
        keyboardOptions = KeyboardOptions(
            keyboardType = keyboardType,
            imeAction = imeAction
        ),
        keyboardActions = KeyboardActions(
            onDone = { onDone?.invoke() }
        )
    )
}
@androidx.compose.ui.tooling.preview.Preview
@Composable
fun LoginScreenPreview() {
    com.example.rjlmulticomsg_proclientportal.ui.theme.RJLMulticomSGPROCLIENTPORTALTheme {
        LoginScreenContent(
            email = "support@rjlmulticom.com",
            onEmailChange = {},
            password = "password123",
            onPasswordChange = {},
            rememberMe = true,
            onRememberMeChange = {},
            error = null,
            info = null,
            loading = false,
            showResetDialog = false,
            onResetDialogDismiss = {},
            resetEmail = "",
            onResetEmailChange = {},
            resetBusy = false,
            onResetSubmit = {},
            onForgotPasswordClick = {},
            onSubmit = {},
            showGoogleSignIn = true,
            onGoogleSignIn = {}
        )
    }
}
