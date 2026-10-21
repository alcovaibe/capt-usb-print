package com.pulse.ai.ui.chat

import androidx.compose.animation.core.*
import android.content.Intent
import android.provider.MediaStore
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import coil.compose.AsyncImage
import com.pulse.ai.R
import com.pulse.ai.ChatUiState
import com.pulse.ai.ChatViewModel
import com.pulse.ai.data.local.ChatEntity
import com.pulse.ai.data.local.MessageEntity
import kotlinx.coroutines.launch
import java.io.File

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun ChatScreen(viewModel: ChatViewModel, onAboutClick: () -> Unit) {
    val context = LocalContext.current
    val uiState by viewModel.uiState.collectAsState()
    val snackbarHostState = remember { SnackbarHostState() }
    val listState = rememberLazyListState()
    val drawerState = rememberDrawerState(initialValue = DrawerValue.Closed)
    val scope = rememberCoroutineScope()
    val sheetState = rememberModalBottomSheetState()
    
    var showAttachmentSheet by remember { mutableStateOf(false) }
    var chatToDelete by remember { mutableStateOf<ChatEntity?>(null) }
    var tempCameraUri by remember { mutableStateOf<android.net.Uri?>(null) }

    // Обработка ошибок через Snackbar
    LaunchedEffect(uiState) {
        if (uiState is ChatUiState.Success) {
            val error = (uiState as ChatUiState.Success).temporaryError
            if (error != null) {
                snackbarHostState.showSnackbar(error)
                viewModel.clearError()
            }
        }
    }

    val galleryLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == android.app.Activity.RESULT_OK) {
            val imageUri = result.data?.data
            imageUri?.let { viewModel.onImageSelected(it) }
        }
    }

    val cameraLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.TakePicture()
    ) { success -> 
        if (success) {
            tempCameraUri?.let { viewModel.onImageSelected(it) }
        }
    }

    if (showAttachmentSheet) {
        ModalBottomSheet(
            onDismissRequest = { showAttachmentSheet = false },
            sheetState = sheetState
        ) {
            AttachmentOptions(
                onCameraClick = {
                    showAttachmentSheet = false
                    val file = File(context.cacheDir, "images").apply { mkdirs() }
                    val imageFile = File(file, "temp_camera_${System.currentTimeMillis()}.jpg")
                    val uri = FileProvider.getUriForFile(
                        context,
                        "${context.packageName}.fileprovider",
                        imageFile
                    )
                    tempCameraUri = uri
                    cameraLauncher.launch(uri)
                },
                onGalleryClick = {
                    showAttachmentSheet = false
                    val intent = Intent(Intent.ACTION_PICK, MediaStore.Images.Media.EXTERNAL_CONTENT_URI)
                    galleryLauncher.launch(intent)
                }
            )
        }
    }

    ModalNavigationDrawer(
        drawerState = drawerState,
        drawerContent = {
            ModalDrawerSheet(
                modifier = Modifier.fillMaxWidth(0.7f),
                drawerContainerColor = Color(0xFF121212),
                drawerContentColor = MaterialTheme.colorScheme.onBackground
            ) {
                Spacer(Modifier.height(12.dp))
                Text(stringResource(R.string.drawer_chats), style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(16.dp))
                HorizontalDivider()
                
                Box(modifier = Modifier.weight(1f)) {
                    if (uiState is ChatUiState.Success) {
                        val state = uiState as ChatUiState.Success
                        
                        if (chatToDelete != null) {
                            AlertDialog(
                                onDismissRequest = { chatToDelete = null },
                                title = { Text(stringResource(R.string.dialog_delete_chat_title)) },
                                text = { Text(stringResource(R.string.dialog_delete_chat_message)) },
                                confirmButton = {
                                    TextButton(onClick = {
                                        chatToDelete?.let { viewModel.deleteChat(it) }
                                        chatToDelete = null
                                    }) { Text(stringResource(R.string.action_delete)) }
                                },
                                dismissButton = {
                                    TextButton(onClick = { chatToDelete = null }) { Text(stringResource(R.string.action_cancel)) }
                                }
                            )
                        }

                        LazyColumn(modifier = Modifier.fillMaxSize()) {
                            items(state.chats) { chat ->
                                NavigationDrawerItem(
                                    label = { Text(chat.title, maxLines = 1) },
                                    selected = chat.id == state.selectedChatId,
                                    onClick = {
                                        viewModel.selectChat(chat.id)
                                        scope.launch { drawerState.close() }
                                    },
                                    modifier = Modifier.combinedClickable(
                                        onClick = {
                                            viewModel.selectChat(chat.id)
                                            scope.launch { drawerState.close() }
                                        },
                                        onLongClick = { chatToDelete = chat }
                                    )
                                )
                            }
                        }
                    }

                    ExtendedFloatingActionButton(
                        onClick = {
                            viewModel.createNewChat()
                            scope.launch { drawerState.close() }
                        },
                        icon = { Icon(Icons.Default.Add, contentDescription = null) },
                        text = { Text(stringResource(R.string.action_new_chat)) },
                        modifier = Modifier.align(Alignment.BottomCenter).padding(16.dp)
                    )
                }

                HorizontalDivider()
                NavigationDrawerItem(
                    icon = { Icon(Icons.Default.Settings, contentDescription = null) },
                    label = { Text(stringResource(R.string.menu_settings)) },
                    selected = false,
                    onClick = { /* TODO */ }
                )
                NavigationDrawerItem(
                    icon = { Icon(Icons.Default.Info, contentDescription = null) },
                    label = { Text(stringResource(R.string.menu_about)) },
                    selected = false,
                    onClick = { 
                        scope.launch { 
                            drawerState.close()
                            onAboutClick()
                        }
                    }
                )
                Spacer(Modifier.height(12.dp))
            }
        }
    ) {
        Scaffold(
            snackbarHost = { SnackbarHost(snackbarHostState) },
            topBar = {
                CenterAlignedTopAppBar(
                    title = { Text("PulseAI") },
                    colors = TopAppBarDefaults.topAppBarColors(
                        containerColor = Color.Black,
                        titleContentColor = Color.White
                    ),
                    navigationIcon = {
                        Surface(
                            modifier = Modifier.padding(start = 12.dp).size(40.dp),
                            shape = CircleShape,
                            color = Color.White.copy(alpha = 0.1f)
                        ) {
                            IconButton(
                                onClick = { scope.launch { drawerState.open() } },
                                modifier = Modifier.fillMaxSize()
                            ) {
                                Icon(
                                    Icons.Default.Menu,
                                    contentDescription = stringResource(R.string.cd_menu),
                                    tint = Color.White,
                                    modifier = Modifier.size(20.dp)
                                )
                            }
                        }
                    },
                    actions = {
                        var showMenu by remember { mutableStateOf(false) }
                        Surface(
                            modifier = Modifier.padding(end = 12.dp).size(40.dp),
                            shape = CircleShape,
                            color = Color.White.copy(alpha = 0.1f)
                        ) {
                            IconButton(
                                onClick = { showMenu = true },
                                modifier = Modifier.fillMaxSize()
                            ) {
                                Icon(
                                    Icons.Default.MoreVert,
                                    contentDescription = stringResource(R.string.cd_more),
                                    tint = Color.White,
                                    modifier = Modifier.size(20.dp)
                                )
                            }
                        }
                        DropdownMenu(expanded = showMenu, onDismissRequest = { showMenu = false }) {
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.action_pin)) },
                                leadingIcon = { Icon(Icons.Default.PushPin, contentDescription = null) },
                                onClick = { showMenu = false }
                            )
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.action_rename)) },
                                leadingIcon = { Icon(Icons.Default.Edit, contentDescription = null) },
                                onClick = { showMenu = false }
                            )
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.action_archive)) },
                                leadingIcon = { Icon(Icons.Default.Archive, contentDescription = null) },
                                onClick = { showMenu = false }
                            )
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.action_share)) },
                                leadingIcon = { Icon(Icons.Default.Share, contentDescription = null) },
                                onClick = { showMenu = false }
                            )
                            HorizontalDivider()
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.action_delete)) },
                                leadingIcon = { Icon(Icons.Default.Delete, contentDescription = null) },
                                colors = MenuDefaults.itemColors(
                                    textColor = Color.Red,
                                    leadingIconColor = Color.Red
                                ),
                                onClick = {
                                    showMenu = false
                                    viewModel.deleteCurrentChat()
                                }
                            )
                        }
                    }
                )
            },
            bottomBar = {
                if (uiState is ChatUiState.Success) {
                    val selectedImage by viewModel.selectedImage.collectAsState()
                    ChatInput(
                        selectedImage = selectedImage,
                        onRemoveImage = { viewModel.removeImage() },
                        onSendMessage = { 
                            viewModel.sendMessage(it)
                        },
                        onAttachClick = { showAttachmentSheet = true }
                    )
                }
            }
        ) { padding ->
            Box(modifier = Modifier.fillMaxSize().padding(padding).background(Color.Black)) {
                when (val state = uiState) {
                    is ChatUiState.Loading -> {
                        CircularProgressIndicator(modifier = Modifier.align(Alignment.Center))
                    }
                    is ChatUiState.Success -> {
                        LaunchedEffect(state.selectedChatMessages.size, state.isAiThinking) {
                            if (state.selectedChatMessages.isNotEmpty() || state.isAiThinking) {
                                val targetIndex = if (state.isAiThinking) {
                                    state.selectedChatMessages.size // Индекс индикатора
                                } else {
                                    state.selectedChatMessages.size - 1
                                }
                                if (targetIndex >= 0) {
                                    listState.animateScrollToItem(targetIndex)
                                }
                            }
                        }

                        LazyColumn(
                            state = listState,
                            modifier = Modifier.fillMaxSize(),
                            contentPadding = PaddingValues(16.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            items(
                                items = state.selectedChatMessages,
                                key = { it.id }
                            ) { message ->
                                MessageBubble(message)
                            }
                            
                            if (state.isAiThinking) {
                                item {
                                    AiTypingIndicator()
                                }
                            }
                        }
                    }
                    is ChatUiState.Error -> {
                        Text(state.message, color = Color.Red, modifier = Modifier.align(Alignment.Center))
                    }
                }
            }
        }
    }
}

@Composable
fun ChatInput(
    selectedImage: android.net.Uri?,
    onRemoveImage: () -> Unit,
    onSendMessage: (String) -> Unit,
    onAttachClick: () -> Unit
) {
    var inputText by remember { mutableStateOf("") }
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp)
            .navigationBarsPadding(),
        color = Color(0xFF2B2B2B),
        shape = MaterialTheme.shapes.extraLarge
    ) {
        Column {
            if (selectedImage != null) {
                Box(
                    modifier = Modifier
                        .padding(start = 12.dp, top = 8.dp, bottom = 4.dp)
                        .size(100.dp)
                ) {
                    AsyncImage(
                        model = selectedImage,
                        contentDescription = null,
                        modifier = Modifier
                            .fillMaxSize()
                            .clip(RoundedCornerShape(12.dp)),
                        contentScale = ContentScale.Crop
                    )
                    Surface(
                        onClick = onRemoveImage,
                        color = Color.Black.copy(alpha = 0.6f),
                        shape = CircleShape,
                        modifier = Modifier
                            .align(Alignment.TopEnd)
                            .padding(4.dp)
                            .size(24.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Close,
                            contentDescription = "Remove",
                            tint = Color.White,
                            modifier = Modifier.padding(4.dp)
                        )
                    }
                }
            }
            Row(
                modifier = Modifier.padding(horizontal = 4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(onClick = onAttachClick) {
                    Icon(Icons.Default.AttachFile, contentDescription = "Attach", tint = Color.White)
                }
                BasicTextField(
                    value = inputText,
                    onValueChange = { inputText = it },
                    modifier = Modifier
                        .weight(1f)
                        .padding(vertical = 12.dp),
                    textStyle = MaterialTheme.typography.bodyLarge.copy(color = Color.White),
                    cursorBrush = SolidColor(Color.White),
                    decorationBox = { innerTextField ->
                        if (inputText.isEmpty()) {
                            Text(
                                stringResource(R.string.chat_input_hint),
                                style = MaterialTheme.typography.bodyLarge,
                                color = Color.Gray
                            )
                        }
                        innerTextField()
                    }
                )
                IconButton(onClick = {
                    if (inputText.isNotBlank() || selectedImage != null) {
                        onSendMessage(inputText)
                        inputText = ""
                    }
                }) {
                    Icon(
                        Icons.Default.Send,
                        contentDescription = "Send",
                        tint = if (inputText.isNotBlank() || selectedImage != null) Color(0xFF007AFF) else Color.Gray
                    )
                }
            }
        }
    }
}

@Composable
fun AttachmentOptions(onCameraClick: () -> Unit, onGalleryClick: () -> Unit) {
    Row(modifier = Modifier.fillMaxWidth().padding(16.dp).padding(bottom = 32.dp), horizontalArrangement = Arrangement.SpaceEvenly) {
        AttachmentItem(Icons.Default.CameraAlt, stringResource(R.string.attach_camera), onCameraClick)
        AttachmentItem(Icons.Default.Image, stringResource(R.string.attach_gallery), onGalleryClick)
    }
}

@Composable
fun AttachmentItem(icon: ImageVector, label: String, onClick: () -> Unit) {
    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.width(80.dp)) {
        Surface(
            onClick = onClick,
            shape = CircleShape,
            color = Color(0xFF2B2B2B),
            modifier = Modifier.size(64.dp)
        ) {
            Box(contentAlignment = Alignment.Center) { Icon(icon, contentDescription = label, tint = Color.White) }
        }
        Spacer(Modifier.height(8.dp))
        Text(label, style = MaterialTheme.typography.labelMedium, color = Color.White)
    }
}

@Composable
fun AiTypingIndicator() {
    val infiniteTransition = rememberInfiniteTransition(label = "typing")
    val alpha by infiniteTransition.animateFloat(
        initialValue = 0.4f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(600, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "alpha"
    )

    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.Start
    ) {
        Surface(
            color = Color(0xFF333333),
            shape = MaterialTheme.shapes.large,
            modifier = Modifier.alpha(alpha)
        ) {
            Text(
                "PulseAI думает...",
                modifier = Modifier.padding(16.dp, 10.dp),
                color = Color.White,
                style = MaterialTheme.typography.bodyLarge
            )
        }
    }
}

@Composable
fun MessageBubble(message: MessageEntity) {
    val clipboardManager = LocalClipboardManager.current
    val context = LocalContext.current
    val alignment = if (message.isFromUser) Alignment.End else Alignment.Start
    val containerColor = if (message.isFromUser) Color(0xFF007AFF) else Color(0xFF333333)
    val copiedText = stringResource(R.string.toast_copied)

    Column(modifier = Modifier.fillMaxWidth(), horizontalAlignment = alignment) {
        val imageUris = remember(message.imageUris) {
            message.imageUris?.split(",")?.filter { it.isNotBlank() } ?: emptyList()
        }

        // 1. Изображения СВЕРХУ (над пузырем)
        if (imageUris.isNotEmpty()) {
            Column(
                modifier = Modifier.padding(bottom = 6.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
                horizontalAlignment = alignment
            ) {
                imageUris.forEach { uriString ->
                    AsyncImage(
                        model = uriString,
                        contentDescription = null,
                        modifier = Modifier
                            .widthIn(max = 260.dp)
                            .heightIn(max = 300.dp)
                            .clip(RoundedCornerShape(16.dp)),
                        contentScale = ContentScale.Crop
                    )
                }
            }
        }

        // 2. Текстовый пузырь
        if (message.content.isNotBlank()) {
            Surface(
                color = containerColor,
                shape = MaterialTheme.shapes.large,
                modifier = Modifier.combinedClickable(
                    onClick = {},
                    onLongClick = {
                        clipboardManager.setText(AnnotatedString(message.content))
                        Toast.makeText(context, copiedText, Toast.LENGTH_SHORT).show()
                    }
                )
            ) {
                SelectionContainer {
                    Text(
                        message.content,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
                        color = Color.White,
                        style = MaterialTheme.typography.bodyLarge
                    )
                }
            }
        }
    }
}
