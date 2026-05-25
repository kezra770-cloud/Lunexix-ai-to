package com.example

import android.app.Application
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color as AndroidColor
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.os.Bundle
import android.os.ParcelFileDescriptor
import android.provider.OpenableColumns
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.*
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.data.AppDatabase
import com.example.data.DocumentEntity
import com.example.data.MessageEntity
import com.example.data.PdfRepository
import com.example.data.UserEntity
import com.example.util.SessionManager
import com.example.util.SecurityUtils
import com.example.ui.theme.MyApplicationTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.math.min

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            MyApplicationTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    PdfAssistantApp()
                }
            }
        }
    }
}

// --- VIEWMODEL ---

class PdfViewModel(application: Application) : AndroidViewModel(application) {
    private val db = AppDatabase.getDatabase(application)
    private val repository = PdfRepository(db)
    private val sessionManager = SessionManager(application)
    private val userDao = db.userDao()

    private val _currentUser = MutableStateFlow<String?>(null)
    val currentUser: StateFlow<String?> = _currentUser.asStateFlow()

    private val _documentsList = MutableStateFlow<List<DocumentEntity>>(emptyList())
    val documentsList: StateFlow<List<DocumentEntity>> = _documentsList.asStateFlow()

    private val _activeDocument = MutableStateFlow<DocumentEntity?>(null)
    val activeDocument: StateFlow<DocumentEntity?> = _activeDocument.asStateFlow()

    private val _messagesList = MutableStateFlow<List<MessageEntity>>(emptyList())
    val messagesList: StateFlow<List<MessageEntity>> = _messagesList.asStateFlow()

    private val _isAnalyzing = MutableStateFlow(false)
    val isAnalyzing: StateFlow<Boolean> = _isAnalyzing.asStateFlow()

    private val _analysisProgress = MutableStateFlow("")
    val analysisProgress: StateFlow<String> = _analysisProgress.asStateFlow()

    private val _isAsking = MutableStateFlow(false)
    val isAsking: StateFlow<Boolean> = _isAsking.asStateFlow()

    private val _errorMessage = MutableStateFlow<String?>(null)
    val errorMessage: StateFlow<String?> = _errorMessage.asStateFlow()

    init {
        // Read session during init
        if (sessionManager.isLoggedIn()) {
            _currentUser.value = sessionManager.getUsername()
        }

        // Collect document list from database Room
        viewModelScope.launch {
            repository.allDocuments.collectLatest { docs ->
                _documentsList.value = docs
            }
        }

        // Collect message logs for current active document
        viewModelScope.launch {
            _activeDocument.collectLatest { doc ->
                if (doc != null) {
                    repository.getMessagesForDocument(doc.id).collectLatest { msgs ->
                        _messagesList.value = msgs
                    }
                } else {
                    _messagesList.value = emptyList()
                }
            }
        }
    }

    fun signUp(username: String, password: String, onResult: (Boolean, String) -> Unit) {
        val trimmedU = username.trim()
        if (trimmedU.isBlank() || password.isBlank()) {
            onResult(false, "Username and password cannot be empty.")
            return
        }
        viewModelScope.launch {
            try {
                val existing = userDao.getUserByUsername(trimmedU)
                if (existing != null) {
                    onResult(false, "Username already exists.")
                    return@launch
                }
                val salt = SecurityUtils.generateSalt()
                val passwordHash = SecurityUtils.hashPassword(password, salt)
                val newUser = UserEntity(
                    username = trimmedU,
                    passwordHash = passwordHash,
                    salt = salt
                )
                val insertedId = userDao.insertUser(newUser)
                
                // Save session on successful sign up
                sessionManager.saveSession(insertedId.toInt(), trimmedU)
                _currentUser.value = trimmedU
                onResult(true, "Successfully registered.")
            } catch (e: Exception) {
                onResult(false, "Error: ${e.message}")
            }
        }
    }

    fun logIn(username: String, password: String, onResult: (Boolean, String) -> Unit) {
        val trimmedU = username.trim()
        if (trimmedU.isBlank() || password.isBlank()) {
            onResult(false, "Username and password cannot be empty.")
            return
        }
        viewModelScope.launch {
            try {
                val user = userDao.getUserByUsername(trimmedU)
                if (user == null) {
                    onResult(false, "Invalid username or password.")
                    return@launch
                }
                val calculatedHash = SecurityUtils.hashPassword(password, user.salt)
                if (calculatedHash == user.passwordHash) {
                    sessionManager.saveSession(user.id, trimmedU)
                    _currentUser.value = trimmedU
                    onResult(true, "Login successful.")
                } else {
                    onResult(false, "Invalid username or password.")
                }
            } catch (e: Exception) {
                onResult(false, "Error: ${e.message}")
            }
        }
    }

    fun logOut() {
        sessionManager.clearSession()
        _currentUser.value = null
        _activeDocument.value = null
    }

    fun updateProgress(status: String) {
        _analysisProgress.value = status
    }

    fun selectDocument(document: DocumentEntity) {
        _activeDocument.value = document
        _errorMessage.value = null
    }

    fun unloadDocument() {
        _activeDocument.value = null
        _errorMessage.value = null
    }

    fun deleteDocument(id: Int) {
        viewModelScope.launch {
            if (_activeDocument.value?.id == id) {
                _activeDocument.value = null
            }
            repository.deleteDocument(id)
        }
    }

    fun clearError() {
        _errorMessage.value = null
    }

    fun showError(message: String) {
        _errorMessage.value = message
        _isAnalyzing.value = false
        _analysisProgress.value = ""
    }

    fun analyzeSelectedPdf(name: String, fileSize: Long, bitmaps: List<Bitmap>) {
        if (bitmaps.isEmpty()) {
            showError("Failed to extract pages from PDF.")
            return
        }

        viewModelScope.launch {
            _isAnalyzing.value = true
            _analysisProgress.value = "Analyzing document structure with Gemini..."
            try {
                // Ensure API key is set
                val apiKey = BuildConfig.GEMINI_API_KEY
                if (apiKey.isEmpty() || apiKey == "MY_GEMINI_API_KEY") {
                    throw Exception("Gemini API Key is not configured. Please use the Secrets panel in AI Studio.")
                }

                val savedDoc = repository.analyzePdf(apiKey, name, bitmaps, fileSize)
                _activeDocument.value = savedDoc
                _errorMessage.value = null
            } catch (e: Exception) {
                Log.e("PdfViewModel", "Analysis failed", e)
                _errorMessage.value = "Document indexing failed: ${e.message}"
            } finally {
                _isAnalyzing.value = false
                _analysisProgress.value = ""
            }
        }
    }

    fun askQuestion(questionText: String) {
        val doc = _activeDocument.value ?: return
        if (questionText.isBlank()) return

        viewModelScope.launch {
            _isAsking.value = true
            try {
                val apiKey = BuildConfig.GEMINI_API_KEY
                if (apiKey.isEmpty() || apiKey == "MY_GEMINI_API_KEY") {
                    throw Exception("Gemini API Key is not configured.")
                }

                // Call repository to process and save conversation
                repository.askQuestion(apiKey, doc, questionText, _messagesList.value)
                _errorMessage.value = null
            } catch (e: Exception) {
                Log.e("PdfViewModel", "Failed to get AI reply", e)
                _errorMessage.value = "AI Error: ${e.message}"
            } finally {
                _isAsking.value = false
            }
        }
    }
}

// --- HELPER UTILS ---

fun getFileNameAndSize(context: Context, uri: Uri): Pair<String, Long> {
    var name = "uploaded_document.pdf"
    var size = 0L
    try {
        val cursor = context.contentResolver.query(uri, null, null, null, null)
        cursor?.use {
            if (it.moveToFirst()) {
                val nameIndex = it.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                val sizeIndex = it.getColumnIndex(OpenableColumns.SIZE)
                if (nameIndex != -1) name = it.getString(nameIndex)
                if (sizeIndex != -1) size = it.getLong(sizeIndex)
            }
        }
    } catch (e: Exception) {
        Log.e("PdfHelper", "Error getting file details", e)
    }
    return Pair(name, size)
}

fun renderPdfPages(context: Context, uri: Uri, maxPages: Int = 12): List<Bitmap> {
    val bitmaps = mutableListOf<Bitmap>()
    try {
        val contentResolver = context.contentResolver
        val parcelFileDescriptor: ParcelFileDescriptor? = contentResolver.openFileDescriptor(uri, "r")
        if (parcelFileDescriptor != null) {
            val pdfRenderer = PdfRenderer(parcelFileDescriptor)
            val pageCount = pdfRenderer.pageCount
            val pagesToProcess = min(pageCount, maxPages)

            for (i in 0 until pagesToProcess) {
                val page = pdfRenderer.openPage(i)
                // Downsample page resolution to optimize processing times & network sizes
                val targetWidth = 800
                val scale = targetWidth.toFloat() / page.width.toFloat()
                val targetHeight = (page.height * scale).toInt()

                val bitmap = Bitmap.createBitmap(targetWidth, targetHeight, Bitmap.Config.ARGB_8888)
                val canvas = Canvas(bitmap)
                canvas.drawColor(AndroidColor.WHITE) // PDF backgrounds default to transparent in rendering, draw white backdrop
                page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                bitmaps.add(bitmap)
                page.close()
            }
            pdfRenderer.close()
        }
    } catch (e: Exception) {
        Log.e("PdfHelper", "Error rendering pages", e)
        throw e
    }
    return bitmaps
}

fun formatFileSize(bytes: Long): String {
    if (bytes <= 0) return "0 B"
    val kb = bytes / 1024.0
    val mb = kb / 1024.0
    return when {
        mb >= 1.0 -> String.format("%.2f MB", mb)
        kb >= 1.0 -> String.format("%.1f KB", kb)
        else -> "$bytes B"
    }
}

// --- UI COMPONENTS ---

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PdfAssistantApp(viewModel: PdfViewModel = viewModel()) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()

    val currentUser by viewModel.currentUser.collectAsStateWithLifecycle()
    if (currentUser == null) {
        AuthScreen(
            onLogin = { u, p, callback -> viewModel.logIn(u, p, callback) },
            onSignUp = { u, p, callback -> viewModel.signUp(u, p, callback) }
        )
        return
    }

    val activeDocument by viewModel.activeDocument.collectAsStateWithLifecycle()
    val documentsList by viewModel.documentsList.collectAsStateWithLifecycle()
    val messagesList by viewModel.messagesList.collectAsStateWithLifecycle()
    val isAnalyzing by viewModel.isAnalyzing.collectAsStateWithLifecycle()
    val analysisProgress by viewModel.analysisProgress.collectAsStateWithLifecycle()
    val isAsking by viewModel.isAsking.collectAsStateWithLifecycle()
    val errorMessage by viewModel.errorMessage.collectAsStateWithLifecycle()

    var showDocSelector by remember { mutableStateOf(false) }

    // Check if the API key has been securely configured in Secrets
    val isApiKeyMissing = remember {
        BuildConfig.GEMINI_API_KEY.isEmpty() || BuildConfig.GEMINI_API_KEY == "MY_GEMINI_API_KEY"
    }

    // Picker Launcher for system storage
    val pdfPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        uri?.let {
            coroutineScope.launch(Dispatchers.Default) {
                try {
                    viewModel.updateProgress("Extracting document pages...")
                    val (name, size) = getFileNameAndSize(context, it)
                    val bitmaps = renderPdfPages(context, it)
                    viewModel.analyzeSelectedPdf(name, size, bitmaps)
                } catch (e: Exception) {
                    viewModel.showError("Could not process PDF file: ${e.message}")
                }
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Text(
                            text = "Insight.",
                            fontSize = 32.sp,
                            fontWeight = FontWeight.Black,
                            fontStyle = androidx.compose.ui.text.font.FontStyle.Italic,
                            fontFamily = FontFamily.SansSerif,
                            letterSpacing = (-1.5).sp,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.testTag("app_title_brand")
                        )
                        
                        // Bold Typography Theme decorative circular badge
                        Box(
                            modifier = Modifier
                                .size(28.dp)
                                .background(MaterialTheme.colorScheme.primaryContainer, shape = CircleShape),
                            contentAlignment = Alignment.Center
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(12.dp)
                                    .border(2.dp, MaterialTheme.colorScheme.primary, shape = RoundedCornerShape(2.dp))
                            )
                        }
                    }
                },
                actions = {
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.padding(end = 8.dp)
                    ) {
                        // Modern neobrutalist history drawer picker container
                        Box(
                            modifier = Modifier
                                .size(38.dp)
                                .background(
                                    color = if (showDocSelector) MaterialTheme.colorScheme.primaryContainer else Color.Transparent,
                                    shape = CircleShape
                                )
                                .border(
                                    width = 1.dp,
                                    color = if (showDocSelector) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline.copy(alpha = 0.5f),
                                    shape = CircleShape
                                ),
                            contentAlignment = Alignment.Center
                        ) {
                            IconButton(
                                onClick = { showDocSelector = !showDocSelector },
                                modifier = Modifier.testTag("doc_list_button")
                            ) {
                                Icon(
                                    imageVector = if (showDocSelector) Icons.Default.FolderOpen else Icons.Default.Folder,
                                    contentDescription = "Document Library",
                                    tint = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.size(18.dp)
                                )
                            }
                        }

                        // Logout button
                        Box(
                            modifier = Modifier
                                .size(38.dp)
                                .background(
                                    color = Color.Transparent,
                                    shape = CircleShape
                                )
                                .border(
                                    width = 1.dp,
                                    color = MaterialTheme.colorScheme.outline.copy(alpha = 0.5f),
                                    shape = CircleShape
                                ),
                            contentAlignment = Alignment.Center
                        ) {
                            IconButton(
                                onClick = { viewModel.logOut() },
                                modifier = Modifier.testTag("logout_button")
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Logout,
                                    contentDescription = "Log Out",
                                    tint = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.size(18.dp)
                                )
                            }
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background
                )
            )
        }
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
        ) {
            // Main Interactive Views
            if (activeDocument == null) {
                EmptyStateView(
                    username = currentUser ?: "User",
                    documents = documentsList,
                    onSelectDocument = { viewModel.selectDocument(it) },
                    onUploadClicked = { pdfPickerLauncher.launch("application/pdf") },
                    onDeleteDocument = { viewModel.deleteDocument(it) }
                )
            } else {
                ChatScreen(
                    document = activeDocument!!,
                    messages = messagesList,
                    isAsking = isAsking,
                    onAskQuestion = { viewModel.askQuestion(it) },
                    onCloseDocument = { viewModel.unloadDocument() }
                )
            }

            // Slide-down / Popup Historical Document Selector
            if (showDocSelector) {
                DocumentSelectorOverlay(
                    documents = documentsList,
                    activeDocId = activeDocument?.id,
                    onSelect = {
                        viewModel.selectDocument(it)
                        showDocSelector = false
                    },
                    onDelete = { viewModel.deleteDocument(it) },
                    onClose = { showDocSelector = false }
                )
            }

            // Progress HUD during document indexing and parsing
            if (isAnalyzing) {
                AnalysisProgressDialog(status = analysisProgress)
            }

            // Error Message Snackbar
            if (errorMessage != null) {
                Snackbar(
                    action = {
                        TextButton(onClick = { viewModel.clearError() }) {
                            Text("Dismiss", color = MaterialTheme.colorScheme.inversePrimary)
                        }
                    },
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .padding(16.dp)
                ) {
                    Text(text = errorMessage!!)
                }
            }

            // Missing API Key Warning Banner
            if (isApiKeyMissing) {
                ApiKeyWarningBanner(modifier = Modifier.align(Alignment.TopCenter))
            }
        }
    }
}

// --- SCREEN LAYOUTS & COMPOSABLES ---

@Composable
fun ApiKeyWarningBanner(modifier: Modifier = Modifier) {
    Card(
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.errorContainer,
            contentColor = MaterialTheme.colorScheme.onErrorContainer
        ),
        modifier = modifier
            .fillMaxWidth()
            .padding(16.dp)
            .shadow(4.dp, RoundedCornerShape(12.dp)),
        shape = RoundedCornerShape(12.dp)
    ) {
        Row(
            modifier = Modifier
                .padding(16.dp)
                .fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = Icons.Default.Warning,
                contentDescription = "API key missing",
                tint = MaterialTheme.colorScheme.error,
                modifier = Modifier.size(32.dp)
            )
            Spacer(modifier = Modifier.width(16.dp))
            Column {
                Text(
                    text = "Gemini API Key Required",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = "Please configure your GEMINI_API_KEY inside the Secrets panel of Google AI Studio. This is required for summaries and queries.",
                    style = MaterialTheme.typography.bodySmall,
                    lineHeight = 16.sp
                )
            }
        }
    }
}

@Composable
fun EmptyStateView(
    username: String,
    documents: List<DocumentEntity>,
    onSelectDocument: (DocumentEntity) -> Unit,
    onUploadClicked: () -> Unit,
    onDeleteDocument: (Int) -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        // Decorative neobrutalist emblem
        Box(
            modifier = Modifier
                .size(96.dp)
                .background(MaterialTheme.colorScheme.primaryContainer, shape = CircleShape)
                .border(2.5.dp, MaterialTheme.colorScheme.primary, shape = CircleShape),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = Icons.Default.Description,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(40.dp)
            )
        }

        Spacer(modifier = Modifier.height(24.dp))

        // Personal Greeting badge
        Box(
            modifier = Modifier
                .background(MaterialTheme.colorScheme.primaryContainer, shape = RoundedCornerShape(12.dp))
                .border(2.dp, MaterialTheme.colorScheme.primary, shape = RoundedCornerShape(12.dp))
                .padding(horizontal = 14.dp, vertical = 6.dp)
        ) {
            Text(
                text = "HELLO, ${username.uppercase()}.",
                fontSize = 11.sp,
                fontWeight = FontWeight.Black,
                letterSpacing = 1.sp,
                color = MaterialTheme.colorScheme.onPrimaryContainer
            )
        }

        Spacer(modifier = Modifier.height(14.dp))

        Text(
            text = "INTELLECT.",
            fontSize = 38.sp,
            fontWeight = FontWeight.Black,
            fontStyle = androidx.compose.ui.text.font.FontStyle.Italic,
            fontFamily = FontFamily.SansSerif,
            letterSpacing = (-1.5).sp,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.padding(bottom = 6.dp)
        )

        Text(
            text = "Extract comprehensive text summaries and ask questions in real-time from any PDF file (renders up to 12 pages).",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
            lineHeight = 20.sp,
            modifier = Modifier.padding(horizontal = 16.dp)
        )

        Spacer(modifier = Modifier.height(32.dp))

        // Large tactile neobrutalist action button
        Box(
            modifier = Modifier
                .fillMaxWidth(0.88f)
                .padding(bottom = 6.dp, end = 6.dp)
                .clickable { onUploadClicked() }
                .background(MaterialTheme.colorScheme.primary, shape = RoundedCornerShape(16.dp))
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .offset(x = (-4).dp, y = (-4).dp)
                    .background(MaterialTheme.colorScheme.primaryContainer, shape = RoundedCornerShape(16.dp))
                    .border(2.5.dp, MaterialTheme.colorScheme.primary, shape = RoundedCornerShape(16.dp))
                    .padding(horizontal = 24.dp, vertical = 14.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.Center
            ) {
                Icon(
                    imageVector = Icons.Default.CloudUpload,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onPrimaryContainer,
                    modifier = Modifier.size(24.dp)
                )
                Spacer(modifier = Modifier.width(10.dp))
                Text(
                    text = "SELECT PDF DOCUMENT",
                    fontWeight = FontWeight.Black,
                    fontStyle = androidx.compose.ui.text.font.FontStyle.Italic,
                    letterSpacing = 0.5.sp,
                    fontSize = 14.sp,
                    color = MaterialTheme.colorScheme.onPrimaryContainer
                )
            }
        }

        if (documents.isNotEmpty()) {
            Spacer(modifier = Modifier.height(32.dp))
            
            HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.3f))
            
            Spacer(modifier = Modifier.height(20.dp))

            Text(
                text = "RECENTLY INDEXED",
                fontSize = 11.sp,
                fontWeight = FontWeight.Black,
                letterSpacing = 1.sp,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.align(Alignment.Start)
            )

            Spacer(modifier = Modifier.height(12.dp))

            LazyColumn(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f, fill = false),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                items(documents) { doc ->
                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onSelectDocument(doc) }
                            .border(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.2f), RoundedCornerShape(12.dp)),
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f)
                        ),
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Row(
                            modifier = Modifier
                                .padding(horizontal = 14.dp, vertical = 10.dp)
                                .fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Row(
                                modifier = Modifier.weight(1f),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Box(
                                    modifier = Modifier
                                        .size(36.dp)
                                        .background(Color(0xFFEF4444).copy(alpha = 0.1f), shape = RoundedCornerShape(8.dp)),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.PictureAsPdf,
                                        contentDescription = "PDF document",
                                        tint = Color(0xFFEF4444),
                                        modifier = Modifier.size(20.dp)
                                    )
                                }
                                Spacer(modifier = Modifier.width(12.dp))
                                Column {
                                    Text(
                                        text = doc.name,
                                        style = MaterialTheme.typography.bodyMedium,
                                        fontWeight = FontWeight.Bold,
                                        color = MaterialTheme.colorScheme.onSurface,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                    Text(
                                        text = "${doc.pageCount} pages • ${formatFileSize(doc.fileSize)}",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.8f)
                                    )
                                }
                            }
                            IconButton(
                                onClick = { onDeleteDocument(doc.id) },
                                modifier = Modifier.size(36.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Delete,
                                    contentDescription = "Delete index",
                                    tint = MaterialTheme.colorScheme.error.copy(alpha = 0.8f),
                                    modifier = Modifier.size(18.dp)
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun ChatScreen(
    document: DocumentEntity,
    messages: List<MessageEntity>,
    isAsking: Boolean,
    onAskQuestion: (String) -> Unit,
    onCloseDocument: () -> Unit
) {
    val listState = rememberLazyListState()
    val coroutineScope = rememberCoroutineScope()
    var inputMessage by remember { mutableStateOf("") }
    val keyboardController = LocalSoftwareKeyboardController.current

    // Suggestions chip row to prompt queries immediately
    val suggestionChips = listOf(
        "Summarize core insights",
        "What are the main takeaways?",
        "Key statistics & numbers",
        "Explain the conclusion"
    )

    // Automatically scroll to bottom when a new block arrives
    LaunchedEffect(messages.size, isAsking) {
        if (messages.isNotEmpty()) {
            listState.animateScrollToItem(messages.size - 1)
        }
    }

    Column(modifier = Modifier.fillMaxSize()) {
        // Sticky running state parameters header - Redesigned to PDF Status Badge from Theme HTMl
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp)
                .background(Color(0xFFE7E0EC), shape = RoundedCornerShape(16.dp))
                .border(1.dp, Color(0xFFCAC4D0).copy(alpha = 0.4f), shape = RoundedCornerShape(16.dp))
                .padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Row(
                modifier = Modifier.weight(1f),
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Bold PDF icon bloc
                Box(
                    modifier = Modifier
                        .size(38.dp)
                        .background(Color(0xFF6750A4), shape = RoundedCornerShape(10.dp)),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = "PDF",
                        color = Color.White,
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Black,
                        letterSpacing = 0.5.sp
                    )
                }
                Spacer(modifier = Modifier.width(12.dp))
                Column {
                    Text(
                        text = document.name,
                        style = MaterialTheme.typography.bodySmall,
                        fontWeight = FontWeight.ExtraBold,
                        color = Color(0xFF49454F),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        text = "${document.pageCount} Pages • ${formatFileSize(document.fileSize)}",
                        fontSize = 10.sp,
                        color = Color(0xFF49454F).copy(alpha = 0.7f),
                        fontWeight = FontWeight.Bold
                    )
                }
            }
            Spacer(modifier = Modifier.width(6.dp))
            // Active Tag
            Box(
                modifier = Modifier
                    .background(Color(0xFFF3F0F5), shape = RoundedCornerShape(100.dp))
                    .border(1.dp, Color(0xFFCAC4D0), shape = RoundedCornerShape(100.dp))
                    .padding(horizontal = 8.dp, vertical = 4.dp)
            ) {
                Text(
                    text = "ACTIVE",
                    color = Color(0xFF1C1B1F),
                    fontSize = 9.sp,
                    fontWeight = FontWeight.Black,
                    letterSpacing = 0.5.sp
                )
            }
            Spacer(modifier = Modifier.width(6.dp))
            IconButton(
                onClick = onCloseDocument,
                modifier = Modifier.size(28.dp)
            ) {
                Icon(
                    imageVector = Icons.Default.Close,
                    contentDescription = "Unload document",
                    tint = Color(0xFF1C1B1F),
                    modifier = Modifier.size(16.dp)
                )
            }
        }

        // Lazy lists containing actual responses with custom TL;DR card at the very top
        LazyColumn(
            state = listState,
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .padding(horizontal = 12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            contentPadding = PaddingValues(bottom = 12.dp)
        ) {
            // First item: TL;DR Watermark Summary block from Theme HTML
            item {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 12.dp, horizontal = 4.dp)
                ) {
                    // Huge background typography watermark
                    Text(
                        text = "TL;DR",
                        fontSize = 72.sp,
                        fontWeight = FontWeight.Black,
                        fontStyle = androidx.compose.ui.text.font.FontStyle.Italic,
                        letterSpacing = (-4).sp,
                        color = Color(0xFF1C1B1F).copy(alpha = 0.08f),
                        modifier = Modifier
                            .align(Alignment.TopStart)
                            .offset(y = (-12).dp)
                    )
                    
                    // Neobrutalist custom container: bg-[#EADDFF] border-b-4 border-r-4 border-[#6750A4]
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 18.dp, end = 6.dp, bottom = 6.dp)
                            .background(Color(0xFF6750A4), shape = RoundedCornerShape(24.dp))
                    ) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .offset(x = (-4).dp, y = (-4).dp)
                                .background(Color(0xFFEADDFF), shape = RoundedCornerShape(24.dp))
                                .border(2.5.dp, Color(0xFF6750A4), shape = RoundedCornerShape(24.dp))
                                .padding(18.dp)
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(
                                    imageVector = Icons.Default.Summarize,
                                    tint = Color(0xFF21005D),
                                    contentDescription = null,
                                    modifier = Modifier.size(20.dp)
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(
                                    text = "DOCUMENT SYNOPSIS",
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.Black,
                                    fontStyle = androidx.compose.ui.text.font.FontStyle.Italic,
                                    color = Color(0xFF21005D),
                                    letterSpacing = 0.5.sp
                                )
                            }
                            Spacer(modifier = Modifier.height(10.dp))
                            MarkdownText(
                                text = document.summary,
                                color = Color(0xFF21005D)
                            )
                        }
                    }
                }
            }

            items(messages) { message ->
                val isModel = message.role == "model"
                Box(
                    modifier = Modifier.fillMaxWidth(),
                    contentAlignment = if (isModel) Alignment.CenterStart else Alignment.CenterEnd
                ) {
                    Card(
                        modifier = Modifier
                            .fillMaxWidth(0.90f)
                            .border(
                                width = 1.dp,
                                color = if (isModel) Color(0xFFCAC4D0).copy(alpha = 0.3f) else Color(0xFF6750A4).copy(alpha = 0.2f),
                                shape = RoundedCornerShape(
                                    topStart = 16.dp,
                                    topEnd = 16.dp,
                                    bottomStart = if (isModel) 0.dp else 16.dp,
                                    bottomEnd = if (isModel) 16.dp else 0.dp
                                )
                            ),
                        colors = CardDefaults.cardColors(
                            containerColor = if (isModel) {
                                Color.White
                            } else {
                                Color(0xFFEADDFF).copy(alpha = 0.15f)
                            }
                        ),
                        shape = RoundedCornerShape(
                            topStart = 16.dp,
                            topEnd = 16.dp,
                            bottomStart = if (isModel) 0.dp else 16.dp,
                            bottomEnd = if (isModel) 16.dp else 0.dp
                        )
                    ) {
                        Column(modifier = Modifier.padding(16.dp)) {
                            // Header label context indicator
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = if (isModel) "AI ASSISTANT" else "YOU",
                                    fontSize = 10.sp,
                                    fontWeight = FontWeight.Black,
                                    letterSpacing = 0.5.sp,
                                    color = if (isModel) Color(0xFF6750A4) else Color(0xFF49454F)
                                )
                            }
                            Spacer(modifier = Modifier.height(6.dp))
                            // Formatted markdown content outputs
                            MarkdownText(
                                text = message.content,
                                color = if (isModel) Color(0xFF1C1B1F) else Color(0xFF21005D)
                            )
                        }
                    }
                }
            }

            // Real-time compiling answer response simulator indicators
            if (isAsking) {
                item {
                    Box(
                        modifier = Modifier.fillMaxWidth(),
                        contentAlignment = Alignment.CenterStart
                    ) {
                        Card(
                            colors = CardDefaults.cardColors(
                                containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f)
                            ),
                            shape = RoundedCornerShape(12.dp),
                            modifier = Modifier.fillMaxWidth(0.85f)
                        ) {
                            Row(
                                modifier = Modifier.padding(16.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(16.dp),
                                    strokeWidth = 2.dp,
                                    color = MaterialTheme.colorScheme.primary
                                )
                                Spacer(modifier = Modifier.width(10.dp))
                                Text(
                                    text = "AI is querying document facts...",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }
                }
            }
        }

        // Suggested Chips Panel
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState())
                .padding(horizontal = 12.dp, vertical = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            suggestionChips.forEach { tip ->
                SuggestionChip(
                    onClick = {
                        inputMessage = tip
                        onAskQuestion(tip)
                        inputMessage = ""
                        keyboardController?.hide()
                    },
                    label = { Text(text = tip, fontSize = 11.sp, fontWeight = FontWeight.Bold) },
                    shape = RoundedCornerShape(20.dp),
                    colors = SuggestionChipDefaults.suggestionChipColors(
                        containerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.5f),
                        labelColor = MaterialTheme.colorScheme.onPrimaryContainer
                    )
                )
            }
        }

        // Bottom text message input field
        Surface(
            color = MaterialTheme.colorScheme.background,
            modifier = Modifier.fillMaxWidth()
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 14.dp, vertical = 12.dp)
                    .navigationBarsPadding(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                TextField(
                    value = inputMessage,
                    onValueChange = { inputMessage = it },
                    placeholder = { 
                        Text(
                            text = "Ask a question...", 
                            color = Color(0xFF49454F).copy(alpha = 0.6f),
                            fontWeight = FontWeight.Medium
                        ) 
                    },
                    modifier = Modifier
                        .weight(1f)
                        .testTag("question_input")
                        .minimumInteractiveComponentSize(),
                    shape = RoundedCornerShape(28.dp),
                    colors = TextFieldDefaults.colors(
                        focusedContainerColor = Color(0xFFE7E0EC),
                        unfocusedContainerColor = Color(0xFFE7E0EC),
                        disabledContainerColor = Color(0xFFE7E0EC),
                        focusedIndicatorColor = Color.Transparent,
                        unfocusedIndicatorColor = Color.Transparent,
                        disabledIndicatorColor = Color.Transparent,
                        focusedTextColor = Color(0xFF1C1B1F),
                        unfocusedTextColor = Color(0xFF1C1B1F)
                    ),
                    keyboardOptions = KeyboardOptions(
                        imeAction = ImeAction.Send
                    ),
                    keyboardActions = KeyboardActions(
                        onSend = {
                            if (inputMessage.isNotBlank()) {
                                onAskQuestion(inputMessage.trim())
                                inputMessage = ""
                                keyboardController?.hide()
                            }
                        }
                    ),
                    maxLines = 3
                )
                Spacer(modifier = Modifier.width(10.dp))
                IconButton(
                    onClick = {
                        if (inputMessage.isNotBlank()) {
                            onAskQuestion(inputMessage.trim())
                            inputMessage = ""
                            keyboardController?.hide()
                        }
                    },
                    modifier = Modifier
                        .size(48.dp)
                        .background(
                            color = if (inputMessage.isNotBlank()) MaterialTheme.colorScheme.primary else Color(0xFFE7E0EC),
                            shape = CircleShape
                        )
                        .testTag("send_button"),
                    enabled = inputMessage.isNotBlank()
                ) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.Send,
                        contentDescription = "Send",
                        tint = if (inputMessage.isNotBlank()) Color.White else Color(0xFF49454F).copy(alpha = 0.4f),
                        modifier = Modifier.size(18.dp)
                    )
                }
            }
        }
    }
}

@Composable
fun DocumentSelectorOverlay(
    documents: List<DocumentEntity>,
    activeDocId: Int?,
    onSelect: (DocumentEntity) -> Unit,
    onDelete: (Int) -> Unit,
    onClose: () -> Unit
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.5f))
            .clickable { onClose() }
    ) {
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .fillMaxHeight(0.7f)
                .align(Alignment.TopCenter)
                .clickable(enabled = false) {}, // Prevent closures on tap
            shape = RoundedCornerShape(bottomStart = 20.dp, bottomEnd = 20.dp),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surface
            )
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(16.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "Document Library",
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold
                    )
                    IconButton(onClick = onClose) {
                        Icon(imageVector = Icons.Default.Close, contentDescription = "Close library")
                    }
                }

                HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))

                if (documents.isEmpty()) {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .weight(1f),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = "No documents currently indexed.",
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            style = MaterialTheme.typography.bodyMedium
                        )
                    }
                } else {
                    LazyColumn(
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(1f),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        items(documents) { doc ->
                            val isActive = doc.id == activeDocId
                            Card(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable { onSelect(doc) },
                                shape = RoundedCornerShape(10.dp),
                                border = if (isActive) {
                                    CardDefaults.outlinedCardBorder().copy(
                                        brush = Brush.horizontalGradient(
                                            listOf(MaterialTheme.colorScheme.primary, MaterialTheme.colorScheme.secondary)
                                        )
                                    )
                                } else null,
                                colors = CardDefaults.cardColors(
                                    containerColor = if (isActive) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.4f) else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f)
                                )
                            ) {
                                Row(
                                    modifier = Modifier
                                        .padding(12.dp)
                                        .fillMaxWidth(),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.SpaceBetween
                                ) {
                                    Row(
                                        modifier = Modifier.weight(1f),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Icon(
                                            imageVector = Icons.Default.PictureAsPdf,
                                            contentDescription = null,
                                            tint = Color(0xFFEF4444),
                                            modifier = Modifier.size(28.dp)
                                        )
                                        Spacer(modifier = Modifier.width(12.dp))
                                        Column {
                                            Text(
                                                text = doc.name,
                                                style = MaterialTheme.typography.bodyMedium,
                                                fontWeight = FontWeight.Bold,
                                                maxLines = 1,
                                                overflow = TextOverflow.Ellipsis
                                            )
                                            Text(
                                                text = "${doc.pageCount} pages • ${formatFileSize(doc.fileSize)}",
                                                style = MaterialTheme.typography.labelSmall,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant
                                            )
                                        }
                                    }
                                    IconButton(
                                        onClick = { onDelete(doc.id) },
                                        modifier = Modifier.size(28.dp)
                                    ) {
                                        Icon(
                                            imageVector = Icons.Default.Delete,
                                            contentDescription = "Delete document",
                                            tint = MaterialTheme.colorScheme.error.copy(alpha = 0.7f),
                                            modifier = Modifier.size(18.dp)
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun AnalysisProgressDialog(status: String) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.6f)),
        contentAlignment = Alignment.Center
    ) {
        Card(
            modifier = Modifier
                .width(280.dp)
                .shadow(8.dp, RoundedCornerShape(16.dp)),
            shape = RoundedCornerShape(16.dp),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surface
            )
        ) {
            Column(
                modifier = Modifier.padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                CircularProgressIndicator(
                    modifier = Modifier.size(48.dp),
                    color = MaterialTheme.colorScheme.primary,
                    strokeWidth = 4.dp
                )
                Spacer(modifier = Modifier.height(20.dp))
                Text(
                    text = "Indexing PDF Document",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = status,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center
                )
            }
        }
    }
}

// --- CUSTOM ELEGANT MARKDOWN RENDERER ---

@Composable
fun MarkdownText(text: String, color: Color) {
    val lines = text.split("\n")
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        lines.forEach { line ->
            val trimmed = line.trim()
            when {
                // Header level formatting
                trimmed.startsWith("### ") -> {
                    Text(
                        text = parseMarkdown(trimmed.substring(4)),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.padding(vertical = 4.dp)
                    )
                }
                trimmed.startsWith("## ") -> {
                    Text(
                        text = parseMarkdown(trimmed.substring(3)),
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.padding(vertical = 6.dp)
                    )
                }
                trimmed.startsWith("# ") -> {
                    Text(
                        text = parseMarkdown(trimmed.substring(2)),
                        style = MaterialTheme.typography.headlineSmall,
                        fontWeight = FontWeight.ExtraBold,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.padding(vertical = 8.dp)
                    )
                }
                // Bullet point lists
                trimmed.startsWith("- ") || trimmed.startsWith("* ") -> {
                    Row(
                        modifier = Modifier.padding(start = 8.dp, top = 2.dp),
                        verticalAlignment = Alignment.Top
                    ) {
                        Text(
                            text = "• ",
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.ExtraBold,
                            color = color
                        )
                        Box(modifier = Modifier.weight(1f)) {
                            Text(
                                text = parseMarkdown(trimmed.substring(2)),
                                style = MaterialTheme.typography.bodyMedium,
                                color = color,
                                lineHeight = 20.sp
                            )
                        }
                    }
                }
                // Paragraph text formatting
                trimmed.isNotEmpty() -> {
                    Text(
                        text = parseMarkdown(line),
                        style = MaterialTheme.typography.bodyMedium,
                        color = color,
                        lineHeight = 20.sp
                    )
                }
            }
        }
    }
}

// Inline Markdown parsing helper (bold extractor)
fun parseMarkdown(text: String): AnnotatedString {
    return buildAnnotatedString {
        var cursor = 0
        while (cursor < text.length) {
            val boldStart = text.indexOf("**", cursor)
            if (boldStart == -1) {
                append(text.substring(cursor))
                break
            }
            append(text.substring(cursor, boldStart))
            val boldEnd = text.indexOf("**", boldStart + 2)
            if (boldEnd == -1) {
                append(text.substring(boldStart))
                break
            }
            pushStyle(SpanStyle(fontWeight = FontWeight.Black))
            append(text.substring(boldStart + 2, boldEnd))
            pop()
            cursor = boldEnd + 2
        }
    }
}

@Composable
fun AuthScreen(
    onLogin: (String, String, (Boolean, String) -> Unit) -> Unit,
    onSignUp: (String, String, (Boolean, String) -> Unit) -> Unit
) {
    var username by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var isLogin by remember { mutableStateOf(true) }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    var isSubmitting by remember { mutableStateOf(false) }
    var passwordVisible by remember { mutableStateOf(false) }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .padding(24.dp),
        contentAlignment = Alignment.Center
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .widthIn(max = 420.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            // Header Section
            Text(
                text = "Insight.",
                fontSize = 54.sp,
                fontWeight = FontWeight.Black,
                fontStyle = androidx.compose.ui.text.font.FontStyle.Italic,
                fontFamily = FontFamily.SansSerif,
                letterSpacing = (-2).sp,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(bottom = 6.dp)
            )

            Text(
                text = if (isLogin) "LOGIN PORTAL." else "SIGN UP PORTAL.",
                fontSize = 11.sp,
                fontWeight = FontWeight.Black,
                letterSpacing = 1.sp,
                color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.6f),
                modifier = Modifier.padding(bottom = 32.dp)
            )

            // Auth Error Panel if exists
            errorMessage?.let { error ->
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 20.dp)
                        .background(Color(0xFFEF4444).copy(alpha = 0.1f), shape = RoundedCornerShape(12.dp))
                        .border(1.5.dp, Color(0xFFEF4444), shape = RoundedCornerShape(12.dp))
                        .padding(14.dp)
                ) {
                    Text(
                        text = error,
                        color = Color(0xFFEF4444),
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
            }

            // Inputs Container with double outline neobrutalist styling
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 24.dp, end = 6.dp)
                    .background(MaterialTheme.colorScheme.primary, shape = RoundedCornerShape(24.dp))
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .offset(x = (-4).dp, y = (-4).dp)
                        .background(Color.White, shape = RoundedCornerShape(24.dp))
                        .border(2.5.dp, MaterialTheme.colorScheme.primary, shape = RoundedCornerShape(24.dp))
                        .padding(20.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    Text(
                        text = "CREDENTIALS",
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Black,
                        fontStyle = androidx.compose.ui.text.font.FontStyle.Italic,
                        color = MaterialTheme.colorScheme.primary,
                        letterSpacing = 0.5.sp
                    )

                    // Username Input
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(
                            text = "USERNAME",
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Black,
                            color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.6f)
                        )
                        TextField(
                            value = username,
                            onValueChange = {
                                username = it
                                errorMessage = null
                            },
                            placeholder = { Text("enter username...") },
                            singleLine = true,
                            modifier = Modifier
                                .fillMaxWidth()
                                .testTag("username_input"),
                            shape = RoundedCornerShape(12.dp),
                            colors = TextFieldDefaults.colors(
                                focusedContainerColor = Color(0xFFE7E0EC),
                                unfocusedContainerColor = Color(0xFFE7E0EC),
                                disabledContainerColor = Color(0xFFE7E0EC),
                                focusedIndicatorColor = Color.Transparent,
                                unfocusedIndicatorColor = Color.Transparent,
                                disabledIndicatorColor = Color.Transparent,
                                focusedTextColor = Color(0xFF1C1B1F),
                                unfocusedTextColor = Color(0xFF1C1B1F)
                            )
                        )
                    }

                    // Password Input
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(
                            text = "PASSWORD",
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Black,
                            color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.6f)
                        )
                        TextField(
                            value = password,
                            onValueChange = {
                                password = it
                                errorMessage = null
                            },
                            placeholder = { Text("enter password...") },
                            singleLine = true,
                            visualTransformation = if (passwordVisible) androidx.compose.ui.text.input.VisualTransformation.None else androidx.compose.ui.text.input.PasswordVisualTransformation(),
                            trailingIcon = {
                                IconButton(onClick = { passwordVisible = !passwordVisible }) {
                                    Icon(
                                        imageVector = if (passwordVisible) Icons.Default.Visibility else Icons.Default.VisibilityOff,
                                        contentDescription = "Toggle password visibility",
                                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            },
                            modifier = Modifier
                                .fillMaxWidth()
                                .testTag("password_input"),
                            shape = RoundedCornerShape(12.dp),
                            colors = TextFieldDefaults.colors(
                                focusedContainerColor = Color(0xFFE7E0EC),
                                unfocusedContainerColor = Color(0xFFE7E0EC),
                                disabledContainerColor = Color(0xFFE7E0EC),
                                focusedIndicatorColor = Color.Transparent,
                                unfocusedIndicatorColor = Color.Transparent,
                                disabledIndicatorColor = Color.Transparent,
                                focusedTextColor = Color(0xFF1C1B1F),
                                unfocusedTextColor = Color(0xFF1C1B1F)
                            )
                        )
                    }
                }
            }

            // Neobrutalist Submit Button
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 16.dp, end = 6.dp)
                    .clickable(enabled = !isSubmitting) {
                        if (username.isBlank() || password.isBlank()) {
                            errorMessage = "Please fill in all fields."
                            return@clickable
                        }
                        if (!isLogin && password.length < 4) {
                            errorMessage = "Password must be at least 4 characters long."
                            return@clickable
                        }
                        
                        isSubmitting = true
                        val callback = { success: Boolean, msg: String ->
                            isSubmitting = false
                            if (!success) {
                                errorMessage = msg
                            }
                        }
                        if (isLogin) {
                            onLogin(username, password, callback)
                        } else {
                            onSignUp(username, password, callback)
                        }
                    }
                    .background(MaterialTheme.colorScheme.primary, shape = RoundedCornerShape(16.dp))
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .offset(x = (-4).dp, y = (-4).dp)
                        .background(MaterialTheme.colorScheme.primaryContainer, shape = RoundedCornerShape(16.dp))
                        .border(2.5.dp, MaterialTheme.colorScheme.primary, shape = RoundedCornerShape(16.dp))
                        .padding(horizontal = 24.dp, vertical = 14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.Center
                ) {
                    if (isSubmitting) {
                        CircularProgressIndicator(
                            color = MaterialTheme.colorScheme.onPrimaryContainer,
                            modifier = Modifier.size(18.dp),
                            strokeWidth = 2.dp
                        )
                    } else {
                        Text(
                            text = if (isLogin) "LOG IN NOW." else "REGISTER NOW.",
                            fontWeight = FontWeight.Black,
                            fontStyle = androidx.compose.ui.text.font.FontStyle.Italic,
                            letterSpacing = 0.5.sp,
                            fontSize = 14.sp,
                            color = MaterialTheme.colorScheme.onPrimaryContainer
                        )
                    }
                }
            }

            // Screen Toggle Link
            Text(
                text = if (isLogin) "DON'T HAVE AN ACCOUNT? REGISTER" else "ALREADY REGISTERED? LOG IN",
                fontSize = 11.sp,
                fontWeight = FontWeight.Black,
                letterSpacing = 0.5.sp,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier
                    .clickable {
                        isLogin = !isLogin
                        errorMessage = null
                        password = ""
                    }
                    .padding(8.dp)
            )
        }
    }
}
