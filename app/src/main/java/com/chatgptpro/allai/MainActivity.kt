package com.chatgptpro.allai

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.speech.RecognizerIntent
import android.speech.tts.TextToSpeech
import android.util.Base64
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.util.Locale

private val Navy = Color(0xFF101827)
private val Panel = Color(0xFF1B2638)
private val Amber = Color(0xFFF5A623)
private val Ink = Color(0xFFEAF0F8)

class MainActivity : ComponentActivity() {
    private var tts: TextToSpeech? = null
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        tts = TextToSpeech(this) { status -> if (status == TextToSpeech.SUCCESS) tts?.language = Locale.getDefault() }
        setContent { AppHome(onSpeak = { tts?.speak(it, TextToSpeech.QUEUE_FLUSH, null, "reply") }) }
    }
    override fun onDestroy() { tts?.shutdown(); super.onDestroy() }
}

private class KeyVault(context: Context) {
    private val prefs = context.getSharedPreferences("secure_settings", Context.MODE_PRIVATE)
    private val alias = "chatgpt_pro_all_ai_key"
    private fun key(): SecretKey {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (ks.getKey(alias, null) as? SecretKey)?.let { return it }
        val gen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        gen.init(KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build())
        return gen.generateKey()
    }
    fun put(name: String, value: String) {
        if (value.isBlank()) { prefs.edit().remove(name).apply(); return }
        val c = Cipher.getInstance("AES/GCM/NoPadding"); c.init(Cipher.ENCRYPT_MODE, key())
        val encrypted = c.doFinal(value.toByteArray())
        prefs.edit().putString(name, Base64.encodeToString(c.iv, Base64.NO_WRAP) + ":" + Base64.encodeToString(encrypted, Base64.NO_WRAP)).apply()
    }
    fun get(name: String): String {
        return try {
            val raw = prefs.getString(name, null) ?: return ""
            val p = raw.split(":", limit = 2); val c = Cipher.getInstance("AES/GCM/NoPadding")
            c.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, Base64.decode(p[0], Base64.NO_WRAP)))
            String(c.doFinal(Base64.decode(p[1], Base64.NO_WRAP)))
        } catch (_: Exception) { "" }
    }
}

data class ChatMessage(val role: String, val text: String)

enum class Provider(val label: String) { OPENAI("OpenAI"), DEEPSEEK("DeepSeek"), GEMINI("Gemini"), OLLAMA("Local Ollama") }

private class AiClient(private val vault: KeyVault) {
    private val http = OkHttpClient()
    suspend fun ask(provider: Provider, model: String, prompt: String, history: List<ChatMessage>): String = withContext(Dispatchers.IO) {
        when (provider) {
            Provider.GEMINI -> gemini(model.ifBlank { "gemini-2.0-flash" }, prompt, history)
            Provider.OPENAI -> compatible("https://api.openai.com/v1/chat/completions", vault.get("openai"), model.ifBlank { "gpt-4o-mini" }, prompt, history)
            Provider.DEEPSEEK -> compatible("https://api.deepseek.com/chat/completions", vault.get("deepseek"), model.ifBlank { "deepseek-chat" }, prompt, history)
            Provider.OLLAMA -> compatible(vault.get("ollama_url").ifBlank { "http://10.0.2.2:11434/v1/chat/completions" }, "ollama", model.ifBlank { "llama3.2" }, prompt, history)
        }
    }
    private fun compatible(url: String, apiKey: String, model: String, prompt: String, history: List<ChatMessage>): String {
        if (apiKey.isBlank() && !url.startsWith("http://10.0.2.2")) return "API key missing. Open Settings and add your $model provider key."
        val msgs = JSONArray()
        history.takeLast(12).forEach { msgs.put(JSONObject().put("role", it.role).put("content", it.text)) }
        msgs.put(JSONObject().put("role", "user").put("content", prompt))
        val body = JSONObject().put("model", model).put("messages", msgs).put("temperature", 0.7).toString()
        val b = Request.Builder().url(url).post(body.toRequestBody("application/json".toMediaType())).addHeader("Content-Type", "application/json")
        if (apiKey.isNotBlank() && apiKey != "ollama") b.addHeader("Authorization", "Bearer $apiKey")
        http.newCall(b.build()).execute().use { r ->
            val raw = r.body?.string().orEmpty()
            if (!r.isSuccessful) return "Provider error ${r.code}: ${raw.take(500)}"
            val j = JSONObject(raw)
            return j.optJSONArray("choices")?.optJSONObject(0)?.optJSONObject("message")?.optString("content")
                ?: "No response text returned."
        }
    }
    private fun gemini(model: String, prompt: String, history: List<ChatMessage>): String {
        val key = vault.get("gemini")
        if (key.isBlank()) return "Gemini API key missing. Open Settings to add it."
        val parts = JSONArray()
        (history.takeLast(10).map { it.text } + prompt).forEach { parts.put(JSONObject().put("text", it)) }
        val body = JSONObject().put("contents", JSONArray().put(JSONObject().put("parts", parts))).toString()
        val url = "https://generativelanguage.googleapis.com/v1beta/models/$model:generateContent?key=$key"
        http.newCall(Request.Builder().url(url).post(body.toRequestBody("application/json".toMediaType())).build()).execute().use { r ->
            val raw = r.body?.string().orEmpty(); if (!r.isSuccessful) return "Gemini error ${r.code}: ${raw.take(500)}"
            return JSONObject(raw).optJSONArray("candidates")?.optJSONObject(0)?.optJSONObject("content")?.optJSONArray("parts")?.optJSONObject(0)?.optString("text") ?: "No response text returned."
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppHome(onSpeak: (String) -> Unit) {
    val context = LocalContext.current
    val vault = remember { KeyVault(context) }
    val client = remember { AiClient(vault) }
    val scope = rememberCoroutineScope()
    var tab by remember { mutableStateOf("Chat") }
    var provider by remember { mutableStateOf(Provider.OPENAI) }
    var model by remember { mutableStateOf("gpt-4o-mini") }
    var input by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf("") }
    var messages by remember { mutableStateOf(listOf(ChatMessage("assistant", "Welcome to ChatGPT PRO All AI. Choose a provider, add your API key in Settings, and start chatting."))) }
    var openai by remember { mutableStateOf(vault.get("openai")) }
    var deepseek by remember { mutableStateOf(vault.get("deepseek")) }
    var gemini by remember { mutableStateOf(vault.get("gemini")) }
    var ollamaUrl by remember { mutableStateOf(vault.get("ollama_url").ifBlank { "http://10.0.2.2:11434/v1/chat/completions" }) }
    var ollamaModel by remember { mutableStateOf("llama3.2") }
    var fileText by remember { mutableStateOf("") }
    val filePicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) try { context.contentResolver.openInputStream(uri)?.bufferedReader()?.use { fileText = it.readText().take(20000) }; tab = "Files" } catch (_: Exception) { status = "Could not read this file. Text-based files work best." }
    }
    val speech = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            val spoken = result.data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)?.firstOrNull().orEmpty()
            if (spoken.isNotBlank()) input = spoken
        }
    }
    MaterialTheme(colorScheme = darkColorScheme(primary = Amber, background = Navy, surface = Panel, onBackground = Ink, onSurface = Ink)) {
        Scaffold(containerColor = Navy, topBar = {
            TopAppBar(title = { Column { Text("ChatGPT PRO All AI", fontWeight = FontWeight.Bold, color = Ink); Text("FREE • Hybrid AI workspace", fontSize = 11.sp, color = Amber) } }, colors = TopAppBarDefaults.topAppBarColors(containerColor = Navy), actions = { IconButton(onClick = { tab = "Settings" }) { Icon(Icons.Default.Settings, null, tint = Ink) } })
        }, bottomBar = {
            NavigationBar(containerColor = Panel) {
                listOf("Chat", "Tools", "Files", "Settings").forEach { name ->
                    NavigationBarItem(selected = tab == name, onClick = { tab = name }, icon = { Icon(when(name){"Chat"->Icons.Default.Chat;"Tools"->Icons.Default.AutoAwesome;"Files"->Icons.Default.FolderOpen;else->Icons.Default.Settings}, null) }, label = { Text(name) })
                }
            }
        }) { padding ->
            Column(Modifier.fillMaxSize().padding(padding).padding(14.dp)) {
                when (tab) {
                    "Chat" -> {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            var expanded by remember { mutableStateOf(false) }
                            Box {
                                OutlinedButton(onClick = { expanded = true }) { Text(provider.label) }
                                DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) { Provider.values().forEach { p -> DropdownMenuItem(text = { Text(p.label) }, onClick = { provider = p; model = when(p){Provider.OPENAI->"gpt-4o-mini";Provider.DEEPSEEK->"deepseek-chat";Provider.GEMINI->"gemini-2.0-flash";Provider.OLLAMA->ollamaModel}; expanded = false }) } }
                            }
                            OutlinedTextField(value = model, onValueChange = { model = it }, label = { Text("Model") }, singleLine = true, modifier = Modifier.weight(1f))
                        }
                        Spacer(Modifier.height(8.dp))
                        LazyColumn(Modifier.weight(1f).fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                            items(messages) { m ->
                                Column(Modifier.fillMaxWidth(), horizontalAlignment = if (m.role == "user") Alignment.End else Alignment.Start) {
                                    Text(if (m.role == "user") "YOU" else "AI", color = Amber, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                                    Surface(color = if (m.role == "user") Color(0xFF293B55) else Panel, shape = RoundedCornerShape(14.dp)) { Text(m.text, Modifier.padding(12.dp), color = Ink) }
                                    if (m.role == "assistant") TextButton(onClick = { onSpeak(m.text) }) { Icon(Icons.Default.VolumeUp, null); Text("Read aloud") }
                                }
                            }
                        }
                        if (busy) LinearProgressIndicator(Modifier.fillMaxWidth(), color = Amber)
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            IconButton(onClick = { speech.launch(Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)) }) { Icon(Icons.Default.Mic, "Voice", tint = Amber) }
                            OutlinedTextField(value = input, onValueChange = { input = it }, modifier = Modifier.weight(1f), placeholder = { Text("Ask anything...") }, maxLines = 4)
                            IconButton(enabled = !busy && input.isNotBlank(), onClick = {
                                val q = input.trim(); input = ""; messages = messages + ChatMessage("user", q); busy = true; status = ""
                                scope.launch { val answer = try { client.ask(provider, model, q, messages.dropLast(1)) } catch (e: Exception) { "Connection failed: ${e.message}" }; messages = messages + ChatMessage("assistant", answer); busy = false }
                            }) { Icon(Icons.Default.Send, "Send", tint = Amber) }
                        }
                    }
                    "Tools" -> {
                        Text("AI TOOLS", color = Amber, fontWeight = FontWeight.Bold)
                        listOf("Web search" to "Opens a browser search", "Image generation" to "Connect a provider that supports image generation", "Translation" to "Ask the AI to translate text", "Code assistant" to "Generate, explain and debug code", "Data analysis" to "Paste CSV or spreadsheet data into chat", "OCR scanner" to "Select an image/PDF and use a compatible AI vision model", "Voice assistant" to "Use the microphone in Chat and Read aloud").forEach { (title, desc) ->
                            Card(Modifier.fillMaxWidth().padding(vertical = 4.dp), colors = CardDefaults.cardColors(containerColor = Panel)) { Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) { Column(Modifier.weight(1f)) { Text(title, fontWeight = FontWeight.SemiBold); Text(desc, fontSize = 12.sp, color = Color.LightGray) }; TextButton(onClick = { when(title){"Web search"->context.startActivity(Intent(Intent.ACTION_VIEW, android.net.Uri.parse("https://www.google.com/search?q=")));else->{tab="Chat";input="Please help me with $title."} } }) { Text("Open") } } }
                        }
                    }
                    "Files" -> {
                        Text("FILE READER", color = Amber, fontWeight = FontWeight.Bold)
                        Text("Select a text, CSV or TXT file. PDF/DOCX binary extraction is not included in this starter build.", color = Color.LightGray, fontSize = 12.sp)
                        Button(onClick = { filePicker.launch(arrayOf("text/*", "text/csv", "application/pdf", "application/vnd.openxmlformats-officedocument.wordprocessingml.document")) }) { Icon(Icons.Default.UploadFile, null); Spacer(Modifier.width(6.dp)); Text("Choose file") }
                        if (fileText.isNotBlank()) { Text("Extracted text / preview", color = Amber); Surface(Modifier.weight(1f).fillMaxWidth(), color = Panel) { Text(fileText, Modifier.padding(10.dp), color = Ink) }; Button(onClick = { tab="Chat"; input = "Analyze this file content:\n$fileText" }) { Text("Analyze with AI") } }
                    }
                    else -> {
                        Text("SETTINGS & PROVIDERS", color = Amber, fontWeight = FontWeight.Bold)
                        Text("API keys are encrypted locally using Android Keystore. Never share your keys.", color = Color.LightGray, fontSize = 12.sp)
                        OutlinedTextField(openai, { openai=it }, Modifier.fillMaxWidth(), label={Text("OpenAI API key")}, singleLine=true)
                        OutlinedTextField(deepseek, { deepseek=it }, Modifier.fillMaxWidth(), label={Text("DeepSeek API key")}, singleLine=true)
                        OutlinedTextField(gemini, { gemini=it }, Modifier.fillMaxWidth(), label={Text("Gemini API key")}, singleLine=true)
                        OutlinedTextField(ollamaUrl, { ollamaUrl=it }, Modifier.fillMaxWidth(), label={Text("Ollama OpenAI-compatible URL")}, singleLine=true)
                        OutlinedTextField(ollamaModel, { ollamaModel=it }, Modifier.fillMaxWidth(), label={Text("Local model name")}, singleLine=true)
                        Button(onClick = { vault.put("openai",openai);vault.put("deepseek",deepseek);vault.put("gemini",gemini);vault.put("ollama_url",ollamaUrl); status="Settings saved securely on this device." }) { Icon(Icons.Default.Save,null); Spacer(Modifier.width(6.dp)); Text("Save settings") }
                        if(status.isNotBlank()) Text(status, color=Amber)
                        Text("Free use note: this app has no subscription or ads. Hosted AI providers may charge for API usage and have separate rate limits. Local Ollama requires an Ollama server on your own computer/network.", color=Color.LightGray, fontSize=12.sp)
                    }
                }
            }
        }
    }
}
