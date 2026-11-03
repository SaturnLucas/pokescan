package com.pokescan

import android.view.WindowManager
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.fragment.app.FragmentActivity

val AvatarChoices = listOf("⚡", "🔥", "💧", "🌿", "⭐", "🐉", "🌙", "🎴", "🦊", "🐱", "👾", "🎮")

@Composable
fun AppLogo(size: Dp = 96.dp) {
    Box(
        Modifier.size(size)
            .clip(RoundedCornerShape(size * 0.24f))
            .background(Brush.linearGradient(listOf(Color(0xFF2A2110), Color(0xFF050505)))),
        contentAlignment = Alignment.Center
    ) {
        Image(
            painter = painterResource(R.drawable.ic_launcher_foreground),
            contentDescription = "PokéScan",
            modifier = Modifier.fillMaxSize()
        )
    }
}

/** Esconde a tela das capturas e da lista de apps recentes enquanto o login está aberto. */
@Composable
fun SecureWindow() {
    val ctx = LocalContext.current
    DisposableEffect(Unit) {
        val w = ctx.findActivity()?.window
        w?.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        onDispose { w?.clearFlags(WindowManager.LayoutParams.FLAG_SECURE) }
    }
}

@Composable
fun PasswordField(value: String, onChange: (String) -> Unit, label: String, modifier: Modifier = Modifier) {
    var show by remember { mutableStateOf(false) }
    OutlinedTextField(
        value = value,
        onValueChange = onChange,
        label = { Text(label) },
        singleLine = true,
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(14.dp),
        visualTransformation = if (show) VisualTransformation.None else PasswordVisualTransformation(),
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
        trailingIcon = {
            Text(
                if (show) "🙈" else "👁️",
                Modifier.clip(CircleShape).clickable { show = !show }.padding(8.dp)
            )
        }
    )
}

@Composable
fun AuthGate(vm: MainVM, content: @Composable () -> Unit) {
    val profiles by vm.profiles.collectAsState()
    val list = profiles
    when {
        list == null -> Box(
            Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background),
            contentAlignment = Alignment.Center
        ) { CircularProgressIndicator() }
        vm.current != null -> content()
        else -> {
            SecureWindow()
            Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
                if (list.isEmpty()) CreateProfileScreen(vm, first = true, onCancel = {})
                else ProfilePicker(vm, list)
            }
        }
    }
}

@Composable
fun ProfilePicker(vm: MainVM, profiles: List<Profile>) {
    var selected by remember {
        mutableStateOf(profiles.firstOrNull { it.id == vm.lastProfileId } ?: profiles.singleOrNull())
    }
    var creating by remember { mutableStateOf(false) }

    if (creating) {
        CreateProfileScreen(vm, first = false, onCancel = { creating = false; vm.clearAuthError() })
        return
    }

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Spacer(Modifier.height(24.dp))
        AppLogo(96.dp)
        Spacer(Modifier.height(12.dp))
        Text("PokéScan", fontSize = 28.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
        Spacer(Modifier.height(24.dp))

        val sel = selected
        if (sel == null) {
            Text("Quem está jogando?", fontSize = 20.sp, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(16.dp))
            profiles.forEach { p ->
                Surface(
                    shape = RoundedCornerShape(20.dp),
                    color = MaterialTheme.colorScheme.surface,
                    modifier = Modifier.fillMaxWidth().padding(vertical = 5.dp)
                        .clip(RoundedCornerShape(20.dp))
                        .clickable { vm.clearAuthError(); selected = p }
                ) {
                    Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(p.avatar, fontSize = 32.sp)
                        Spacer(Modifier.width(16.dp))
                        Text(p.name, fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
                    }
                }
            }
            Spacer(Modifier.height(12.dp))
            OutlinedButton(onClick = { vm.clearAuthError(); creating = true }) { Text("➕  Novo perfil") }
        } else {
            PasswordStep(vm, sel, canBack = profiles.size > 1, onBack = { vm.clearAuthError(); selected = null })
            if (profiles.size == 1) {
                Spacer(Modifier.height(8.dp))
                TextButton(onClick = { vm.clearAuthError(); creating = true }) { Text("➕ Novo perfil") }
            }
        }
    }
}

@Composable
fun PasswordStep(vm: MainVM, p: Profile, canBack: Boolean, onBack: () -> Unit) {
    val ctx = LocalContext.current
    var pass by remember(p.id) { mutableStateOf("") }
    val bio = vm.bioEnabledFor(p) && Biometrics.available(ctx)

    fun tryBio() {
        val act = ctx.findActivity() as? FragmentActivity ?: return
        Biometrics.prompt(act, "Entrar como ${p.name}", { vm.loginBiometric(p) }, { vm.authError = it })
    }
    LaunchedEffect(p.id) { if (bio) tryBio() }

    Text(p.avatar, fontSize = 56.sp)
    Text(p.name, fontSize = 22.sp, fontWeight = FontWeight.Bold)
    Spacer(Modifier.height(16.dp))
    PasswordField(pass, { pass = it; if (vm.authError != null) vm.clearAuthError() }, "Senha")
    vm.authError?.let {
        Spacer(Modifier.height(8.dp))
        Text(it, color = MaterialTheme.colorScheme.error, fontSize = 13.sp, textAlign = TextAlign.Center)
    }
    Spacer(Modifier.height(16.dp))
    Button(
        modifier = Modifier.fillMaxWidth().height(50.dp),
        enabled = !vm.authBusy && pass.isNotEmpty(),
        onClick = { vm.login(p, pass) }
    ) {
        if (vm.authBusy) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
        else Text("Entrar", fontSize = 16.sp)
    }
    if (bio) {
        Spacer(Modifier.height(8.dp))
        OutlinedButton(modifier = Modifier.fillMaxWidth(), onClick = { tryBio() }) { Text("👆  Entrar com biometria") }
    }
    if (canBack) {
        Spacer(Modifier.height(4.dp))
        TextButton(onClick = onBack) { Text("Trocar de perfil") }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun CreateProfileScreen(vm: MainVM, first: Boolean, onCancel: () -> Unit) {
    var name by remember { mutableStateOf("") }
    var avatar by remember { mutableStateOf(AvatarChoices[0]) }
    var pass by remember { mutableStateOf("") }
    var pass2 by remember { mutableStateOf("") }

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Spacer(Modifier.height(16.dp))
        AppLogo(if (first) 96.dp else 72.dp)
        Spacer(Modifier.height(12.dp))
        Text(
            if (first) "Bem-vindo ao PokéScan!" else "Novo perfil",
            fontSize = 24.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary
        )
        Text(
            "Crie um perfil para guardar o seu deck protegido por senha.",
            fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center,
            modifier = Modifier.padding(top = 6.dp, bottom = 18.dp)
        )

        OutlinedTextField(
            value = name,
            onValueChange = { name = it.take(20) },
            label = { Text("Nome do perfil") },
            singleLine = true,
            shape = RoundedCornerShape(14.dp),
            modifier = Modifier.fillMaxWidth()
        )
        Spacer(Modifier.height(12.dp))
        Text("Escolha um avatar", fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(6.dp))
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            AvatarChoices.forEach { a ->
                val sel = a == avatar
                Surface(
                    shape = CircleShape,
                    color = if (sel) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant,
                    modifier = Modifier.size(46.dp).clip(CircleShape).clickable { avatar = a }
                ) { Box(contentAlignment = Alignment.Center) { Text(a, fontSize = 22.sp) } }
            }
        }
        Spacer(Modifier.height(14.dp))
        PasswordField(pass, { pass = it }, "Senha (mín. 4 caracteres)")
        Spacer(Modifier.height(10.dp))
        PasswordField(pass2, { pass2 = it }, "Repita a senha")
        vm.authError?.let {
            Spacer(Modifier.height(8.dp))
            Text(it, color = MaterialTheme.colorScheme.error, fontSize = 13.sp, textAlign = TextAlign.Center)
        }
        Spacer(Modifier.height(18.dp))
        Button(
            modifier = Modifier.fillMaxWidth().height(50.dp),
            enabled = !vm.authBusy,
            onClick = { vm.createProfile(name, avatar, pass, pass2) }
        ) {
            if (vm.authBusy) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
            else Text("Criar perfil", fontSize = 16.sp)
        }
        if (!first) {
            TextButton(onClick = onCancel) { Text("Cancelar") }
        }
        Text(
            "A senha não é guardada: só uma assinatura criptografada dela. Se esquecer, não há como recuperar — mas você pode restaurar um backup.",
            fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center,
            modifier = Modifier.padding(top = 12.dp)
        )
    }
}
