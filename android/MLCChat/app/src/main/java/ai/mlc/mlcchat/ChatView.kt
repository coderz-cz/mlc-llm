package ai.mlc.mlcchat

import android.app.Activity
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AddAPhoto
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Chat
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.DeleteForever
import androidx.compose.material.icons.filled.Photo
import androidx.compose.material.icons.filled.Send
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Divider
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import dev.jeziellago.compose.markdowntext.MarkdownText
import kotlinx.coroutines.launch

@ExperimentalMaterial3Api
@Composable
fun ChatView(
    navController: NavController, chatState: AppViewModel.ChatState, activity: Activity
) {
    val localFocusManager = LocalFocusManager.current
    (activity as MainActivity).chatState = chatState
    var showSessionList by rememberSaveable { mutableStateOf(false) }
    if (showSessionList) {
        ChatSessionListDialog(
            chatState = chatState,
            onDismiss = { showSessionList = false }
        )
    }
    chatState.pendingCommand.value?.let { command ->
        ShellCommandConfirmDialog(
            command = command,
            onApprove = { chatState.approvePendingCommand() },
            onDecline = { chatState.declinePendingCommand() }
        )
    }
    Scaffold(topBar = {
        TopAppBar(
            title = {
                Text(
                    text = "MLCChat: " + chatState.modelName.value.split("-")[0],
                    color = MaterialTheme.colorScheme.onPrimary
                )
            },
            colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.primary),
            navigationIcon = {
                IconButton(
                    onClick = { navController.popBackStack() },
                    enabled = chatState.interruptable()
                ) {
                    Icon(
                        imageVector = Icons.Filled.ArrowBack,
                        contentDescription = "back home page",
                        tint = MaterialTheme.colorScheme.onPrimary
                    )
                }
            },
            actions = {
                val shellOn = chatState.shellToolsEnabled.value
                IconButton(
                    onClick = {
                        chatState.shellToolsEnabled.value = !shellOn
                    },
                    enabled = chatState.interruptable()
                ) {
                    Icon(
                        imageVector = Icons.Filled.Terminal,
                        contentDescription = if (shellOn)
                            "shell tools on" else "shell tools off",
                        tint = if (shellOn)
                            MaterialTheme.colorScheme.onPrimary
                        else
                            MaterialTheme.colorScheme.onPrimary.copy(alpha = 0.4f)
                    )
                }
                IconButton(
                    onClick = {
                        chatState.refreshSessionList()
                        showSessionList = true
                    },
                    enabled = chatState.interruptable()
                ) {
                    Icon(
                        imageVector = Icons.Filled.Chat,
                        contentDescription = "chat list",
                        tint = MaterialTheme.colorScheme.onPrimary
                    )
                }
                IconButton(
                    onClick = {
                        chatState.requestDeleteCurrentChat()
                        activity.hasImage = false },
                    enabled = chatState.interruptable()
                ) {
                    Icon(
                        imageVector = Icons.Filled.DeleteForever,
                        contentDescription = "delete current chat",
                        tint = MaterialTheme.colorScheme.onPrimary
                    )
                }
            })
    }, modifier = Modifier.pointerInput(Unit) {
        detectTapGestures(onTap = {
            localFocusManager.clearFocus()
        })
    }) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .padding(horizontal = 10.dp)
        ) {
            val lazyColumnListState = rememberLazyListState()
            val coroutineScope = rememberCoroutineScope()
            Text(
                text = chatState.report.value,
                textAlign = TextAlign.Center,
                modifier = Modifier
                    .fillMaxWidth()
                    .wrapContentHeight()
                    .padding(top = 5.dp)
            )
            Divider(thickness = 1.dp, modifier = Modifier.padding(vertical = 5.dp))
            LazyColumn(
                modifier = Modifier.weight(9f),
                verticalArrangement = Arrangement.spacedBy(5.dp, alignment = Alignment.Bottom),
                state = lazyColumnListState
            ) {
                coroutineScope.launch {
                    lazyColumnListState.animateScrollToItem(chatState.messages.size)
                }
                items(
                    items = chatState.messages,
                    key = { message -> message.id },
                ) { message ->
                    MessageView(messageData = message, activity)
                }
                item {
                    // place holder item for scrolling to the bottom
                }
            }
            Divider(thickness = 1.dp, modifier = Modifier.padding(top = 5.dp))
            SendMessageView(chatState = chatState, activity)
        }
    }
}

@Composable
fun ChatSessionListDialog(chatState: AppViewModel.ChatState, onDismiss: () -> Unit) {
    val dateFormat = remember {
        java.text.SimpleDateFormat("dd/MM HH:mm", java.util.Locale.getDefault())
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Chats") },
        text = {
            Column {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 8.dp)
                        .pointerInput(Unit) {
                            detectTapGestures(onTap = {
                                chatState.requestNewChat()
                                onDismiss()
                            })
                        }
                ) {
                    Icon(
                        imageVector = Icons.Filled.Add,
                        contentDescription = "new chat",
                        tint = MaterialTheme.colorScheme.primary
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("New Chat", color = MaterialTheme.colorScheme.primary)
                }
                Divider()
                LazyColumn(modifier = Modifier.heightIn(max = 400.dp)) {
                    items(
                        items = chatState.sessionList,
                        key = { session -> session.id }
                    ) { session ->
                        val isCurrent = session.id == chatState.currentSessionId.value
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 8.dp)
                                .pointerInput(session.id) {
                                    detectTapGestures(onTap = {
                                        chatState.requestSwitchSession(session.id)
                                        onDismiss()
                                    })
                                }
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = session.title,
                                    fontWeight = if (isCurrent) FontWeight.Bold else FontWeight.Normal
                                )
                                Text(
                                    text = dateFormat.format(java.util.Date(session.lastUpdatedAt)),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            IconButton(onClick = { chatState.requestDeleteSession(session.id) }) {
                                Icon(
                                    imageVector = Icons.Filled.Delete,
                                    contentDescription = "delete this chat"
                                )
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("Close") }
        }
    )
}

@Composable
fun ShellCommandConfirmDialog(
    command: String,
    onApprove: () -> Unit,
    onDecline: () -> Unit
) {
    AlertDialog(
        // Deliberately require an explicit choice: tapping outside declines
        // rather than silently running a real device command.
        onDismissRequest = onDecline,
        title = { Text("Run this command in Termux?") },
        text = {
            SelectionContainer {
                Text(
                    text = command,
                    fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(
                            color = MaterialTheme.colorScheme.surfaceVariant,
                            shape = RoundedCornerShape(5.dp)
                        )
                        .padding(10.dp)
                )
            }
        },
        confirmButton = {
            TextButton(onClick = onApprove) { Text("Run") }
        },
        dismissButton = {
            TextButton(onClick = onDecline) { Text("Decline") }
        }
    )
}

@Composable
fun MessageView(messageData: MessageData, activity: Activity?) {
    // default render the Assistant text as MarkdownText
    var useMarkdown by remember { mutableStateOf(true) }
    var localActivity : MainActivity = activity as MainActivity
    SelectionContainer {
        if (messageData.role == MessageRole.Assistant) {
            Column {
                if (messageData.text.isNotEmpty()) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = "Show as Markdown",
                            color = MaterialTheme.colorScheme.onSecondaryContainer,
                            modifier = Modifier
                                .wrapContentWidth()
                                .padding(end = 8.dp)
                                .widthIn(max = 300.dp)
                        )
                        Switch(
                            checked = useMarkdown,
                            onCheckedChange = { useMarkdown = it }
                        )
                    }
                }
                Row(
                    horizontalArrangement = Arrangement.Start,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    if (useMarkdown) {
                        MarkdownText(
                            isTextSelectable = true,
                            modifier = Modifier
                                .wrapContentWidth()
                                .background(
                                    color = MaterialTheme.colorScheme.secondaryContainer,
                                    shape = RoundedCornerShape(5.dp)
                                )
                                .padding(5.dp)
                                .widthIn(max = 300.dp),
                            markdown = messageData.text,
                        )
                    } else {
                        Text(
                            text = messageData.text,
                            textAlign = TextAlign.Left,
                            color = MaterialTheme.colorScheme.onSecondaryContainer,
                            modifier = Modifier
                                .wrapContentWidth()
                                .background(
                                    color = MaterialTheme.colorScheme.secondaryContainer,
                                    shape = RoundedCornerShape(5.dp)
                                )
                                .padding(5.dp)
                                .widthIn(max = 300.dp)
                        )
                    }
                }
            }
        } else {
            Row(
                horizontalArrangement = Arrangement.End,
                modifier = Modifier.fillMaxWidth()
            ) {
                if (messageData.imageUri != null) {
                    val uri = messageData.imageUri
                    val bitmap = uri?.let {
                        activity.contentResolver.openInputStream(it)?.use { input ->
                            BitmapFactory.decodeStream(input)
                        }
                    }
                    val displayBitmap = bitmap?.let { Bitmap.createScaledBitmap(it, 224, 224, true) }
                    if (displayBitmap != null) {
                        Image(
                            displayBitmap.asImageBitmap(),
                            "",
                            modifier = Modifier
                                .wrapContentWidth()
                                .background(
                                    color = MaterialTheme.colorScheme.secondaryContainer,
                                    shape = RoundedCornerShape(5.dp)
                                )
                                .padding(5.dp)
                                .widthIn(max = 300.dp)
                        )
                    }
                    if (!localActivity.hasImage) {
                        localActivity.chatState.requestImageBitmap(messageData.imageUri)
                    }
                    localActivity.hasImage = true
                } else {
                    Text(
                        text = messageData.text,
                        textAlign = TextAlign.Right,
                        color = MaterialTheme.colorScheme.onPrimaryContainer,
                        modifier = Modifier
                            .wrapContentWidth()
                            .background(
                                color = MaterialTheme.colorScheme.primaryContainer,
                                shape = RoundedCornerShape(5.dp)
                            )
                            .padding(5.dp)
                            .widthIn(max = 300.dp)
                    )
                }

            }
        }
    }
}

@ExperimentalMaterial3Api
@Composable
fun SendMessageView(chatState: AppViewModel.ChatState, activity: Activity) {
    val localFocusManager = LocalFocusManager.current
    val localActivity : MainActivity = activity as MainActivity
    Row(
        horizontalArrangement = Arrangement.spacedBy(5.dp),
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .height(IntrinsicSize.Max)
            .fillMaxWidth()
            .padding(bottom = 5.dp)
    ) {
        var text by rememberSaveable { mutableStateOf("") }
        OutlinedTextField(
            value = text,
            onValueChange = { text = it },
            label = { Text(text = "Input") },
            modifier = Modifier
                .weight(9f),
        )
        IconButton(
            onClick = {
                activity.takePhoto()
            },
            modifier = Modifier
                .aspectRatio(1f)
                .weight(1f),
            enabled = (chatState.chatable() && !localActivity.hasImage)
        ) {
            Icon(
                imageVector = Icons.Filled.AddAPhoto,
                contentDescription = "use camera",
            )
        }
        IconButton(
            onClick = {
                activity.pickImageFromGallery()
            },
            modifier = Modifier
                .aspectRatio(1f)
                .weight(1f),
            enabled = (chatState.chatable() && !localActivity.hasImage)
        ) {
            Icon(
                imageVector = Icons.Filled.Photo,
                contentDescription = "select image",
            )
        }
        IconButton(
            onClick = {
                localFocusManager.clearFocus()
                chatState.requestGenerate(text, activity)
                text = ""
            },
            modifier = Modifier
                .aspectRatio(1f)
                .weight(1f),
            enabled = (text != "" && chatState.chatable())
        ) {
            Icon(
                imageVector = Icons.Filled.Send,
                contentDescription = "send message",
            )
        }
    }
}

@Preview
@Composable
fun MessageViewPreviewWithMarkdown() {
    MessageView(
        messageData = MessageData(
            role = MessageRole.Assistant, text = """
# Sample  Header
* Markdown
* [Link](https://example.com)
<a href="https://www.google.com/">Google</a>
"""
        ), null
    )
}
