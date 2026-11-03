package com.pokescan

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.fragment.app.FragmentActivity
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun SettingsScreen(vm: MainVM) {
    val ctx = LocalContext.current
    val me = vm.current
    var dialog by remember { mutableStateOf<String?>(null) }
    var pendingPwd by remember { mutableStateOf("") }

    val createDoc = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/octet-stream")
    ) { uri ->
        if (uri != null) vm.exportBackup(uri, pendingPwd)
        pendingPwd = ""
    }
    val openDoc = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) vm.importBackup(uri, pendingPwd)
        pendingPwd = ""
    }

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        Column {
            Kicker("PERFIL")
            Text("Seus dados e ajustes", fontSize = 27.sp, fontWeight = FontWeight.ExtraBold)
        }

        if (me != null) {
            SectionCard("👤 Perfil") {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(me.avatar, fontSize = 36.sp)
                    Text(me.name, fontSize = 20.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(start = 12.dp))
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = { dialog = "edit" }) { Text("Editar") }
                    OutlinedButton(onClick = { dialog = "pass" }) { Text("Senha") }
                }
                Button(modifier = Modifier.fillMaxWidth(), onClick = { vm.logout() }) { Text("🔒 Sair / trocar de perfil") }
            }

            SectionCard("🔐 Segurança") {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("Manter conectado", fontWeight = FontWeight.SemiBold)
                        Text(
                            "Abre direto no seu perfil, sem pedir a senha toda vez. Ao tocar em Sair, a senha volta a ser pedida.",
                            fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Switch(checked = vm.keepSignedIn, onCheckedChange = { vm.changeKeepSignedIn(it) })
                }
                if (Biometrics.available(ctx)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text("Entrar com biometria", fontWeight = FontWeight.SemiBold)
                            Text(
                                "Digital ou rosto, no lugar de digitar a senha.",
                                fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Switch(
                            checked = vm.bioEnabledFor(me),
                            onCheckedChange = { on ->
                                if (!on) vm.changeBiometric(false)
                                else {
                                    val act = ctx.findActivity() as? FragmentActivity
                                    if (act != null) {
                                        Biometrics.prompt(act, "Ativar biometria", { vm.changeBiometric(true) }, { vm.info = it })
                                    }
                                }
                            }
                        )
                    }
                }
                Text("Bloquear quando eu sair do app por", fontWeight = FontWeight.SemiBold)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf(60 to "1 min", 300 to "5 min", 900 to "15 min", -1 to "Nunca").forEach { (secs, label) ->
                        val sel = vm.lockDelay == secs
                        Surface(
                            shape = RoundedCornerShape(50),
                            color = if (sel) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant,
                            modifier = Modifier.clip(RoundedCornerShape(50)).clickable { vm.changeLockDelay(secs) }
                        ) {
                            Text(
                                label,
                                Modifier.padding(horizontal = 14.dp, vertical = 8.dp),
                                color = if (sel) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface,
                                fontSize = 13.sp
                            )
                        }
                    }
                }
                Text(
                    "Senhas são guardadas só como hash PBKDF2 (nunca em texto). Após 5 erros o login é bloqueado por um tempo. " +
                        "A tela de login não aparece em capturas. Atualizações só instalam se a assinatura e o checksum conferirem.",
                    fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            SectionCard("💾 Backup do deck") {
                Text(
                    "Salve o deck deste perfil num arquivo criptografado (AES-256) para guardar ou levar para outro celular.",
                    fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = { dialog = "export" }) { Text("Exportar") }
                    OutlinedButton(onClick = { dialog = "import" }) { Text("Importar") }
                }
                vm.info?.let { Text(it, fontSize = 13.sp, color = MaterialTheme.colorScheme.primary) }
            }
        }

        SectionCard("💱 Moeda") {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Currencies.list.forEach { (code, label) ->
                    val sel = vm.currency == code
                    Surface(
                        shape = RoundedCornerShape(50),
                        color = if (sel) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant,
                        modifier = Modifier.clip(RoundedCornerShape(50)).clickable { vm.changeCurrency(code) }
                    ) {
                        Text(
                            label,
                            Modifier.padding(horizontal = 14.dp, vertical = 8.dp),
                            color = if (sel) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface,
                            fontSize = 13.sp,
                            fontWeight = if (sel) FontWeight.Bold else FontWeight.Normal
                        )
                    }
                }
            }
            val quando = if (vm.ratesTime > 0)
                SimpleDateFormat("dd/MM HH:mm", Locale.getDefault()).format(Date(vm.ratesTime)) else "valores aproximados"
            Text(
                "Preços do TCGplayer (mercado dos EUA, em dólar) convertidos pela cotação do dia. É uma estimativa. Cotação: $quando.",
                fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        SectionCard("⚡ Escaneamento") {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Escaneamento automático", fontWeight = FontWeight.SemiBold)
                    Text(
                        "Lê a carta sozinho quando o número aparece. Desligado, você toca em “Escanear carta”.",
                        fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Switch(checked = vm.autoScan, onCheckedChange = { vm.changeAutoScan(it) })
            }
        }

        SectionCard("🌐 Fontes de dados") {
            Text(
                "• Scan: pokemontcg.io (principal) → TCGdex (reserva) → Poke API Brasil (por nome)\n" +
                    "• Buscar e Mercado: TCGdex em português do Brasil → TCGdex pt → Poke API Brasil → TCGdex em inglês\n" +
                    "• Preços: TCGplayer (USD) e Cardmarket (EUR), convertidos para a sua moeda",
                fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        SectionCard("⬆️ Atualização") {
            Text("Versão ${vm.versionName} (build ${vm.versionCode})")
            vm.updateStatus?.let { Text(it, fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(enabled = !vm.checking, onClick = { vm.checkUpdate(true) }) { Text("Verificar agora") }
                if (vm.update != null) {
                    Button(onClick = { vm.installUpdate() }) { Text("Instalar ${vm.update?.versionName}") }
                }
            }
            Text(
                "O app também verifica sozinho toda vez que você abre.",
                fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }

    // ------------------------------------------------ diálogos
    when (dialog) {
        "edit" -> if (me != null) {
            var name by remember { mutableStateOf(me.name) }
            var avatar by remember { mutableStateOf(me.avatar) }
            var err by remember { mutableStateOf<String?>(null) }
            AlertDialog(
                onDismissRequest = { dialog = null },
                title = { Text("Editar perfil") },
                text = {
                    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        OutlinedTextField(
                            value = name, onValueChange = { name = it.take(20) }, singleLine = true,
                            label = { Text("Nome") }
                        )
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            AvatarChoices.forEach { a ->
                                Surface(
                                    shape = CircleShape,
                                    color = if (a == avatar) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant,
                                    modifier = Modifier.size(42.dp).clip(CircleShape).clickable { avatar = a }
                                ) { Box(contentAlignment = Alignment.Center) { Text(a, fontSize = 20.sp) } }
                            }
                        }
                        err?.let { Text(it, color = MaterialTheme.colorScheme.error, fontSize = 13.sp) }
                    }
                },
                confirmButton = {
                    Button(onClick = {
                        val e = vm.updateProfile(name, avatar)
                        if (e == null) dialog = null else err = e
                    }) { Text("Salvar") }
                },
                dismissButton = { TextButton(onClick = { dialog = null }) { Text("Cancelar") } }
            )
        }

        "pass" -> {
            var old by remember { mutableStateOf("") }
            var n1 by remember { mutableStateOf("") }
            var n2 by remember { mutableStateOf("") }
            var err by remember { mutableStateOf<String?>(null) }
            AlertDialog(
                onDismissRequest = { dialog = null },
                title = { Text("Alterar senha") },
                text = {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        PasswordField(old, { old = it }, "Senha atual")
                        PasswordField(n1, { n1 = it }, "Nova senha")
                        PasswordField(n2, { n2 = it }, "Repita a nova senha")
                        err?.let { Text(it, color = MaterialTheme.colorScheme.error, fontSize = 13.sp) }
                    }
                },
                confirmButton = {
                    Button(onClick = {
                        vm.changePassword(old, n1, n2) { e ->
                            if (e == null) { dialog = null; vm.info = "✓ Senha alterada." } else err = e
                        }
                    }) { Text("Alterar") }
                },
                dismissButton = { TextButton(onClick = { dialog = null }) { Text("Cancelar") } }
            )
        }

        "export", "import" -> {
            val exporting = dialog == "export"
            var pwd by remember { mutableStateOf("") }
            AlertDialog(
                onDismissRequest = { dialog = null },
                title = { Text(if (exporting) "Exportar backup" else "Importar backup") },
                text = {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(
                            if (exporting) "Escolha uma senha para o arquivo. Sem ela não dá para abrir o backup."
                            else "Digite a senha usada ao criar o backup. As cartas serão somadas ao deck deste perfil.",
                            fontSize = 13.sp
                        )
                        PasswordField(pwd, { pwd = it }, "Senha do backup")
                    }
                },
                confirmButton = {
                    Button(enabled = pwd.length >= 4, onClick = {
                        pendingPwd = pwd
                        dialog = null
                        vm.holdLock()
                        if (exporting) {
                            val safe = (me?.name ?: "deck").replace(Regex("[^A-Za-z0-9_-]"), "_")
                            createDoc.launch("pokescan-$safe.psbackup")
                        } else {
                            openDoc.launch(arrayOf("*/*"))
                        }
                    }) { Text(if (exporting) "Escolher destino" else "Escolher arquivo") }
                },
                dismissButton = { TextButton(onClick = { dialog = null }) { Text("Cancelar") } }
            )
        }

    }
}
