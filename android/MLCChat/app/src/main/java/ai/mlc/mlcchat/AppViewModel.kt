package ai.mlc.mlcchat

import ai.mlc.mlcllm.MLCEngine
import ai.mlc.mlcllm.OpenAIProtocol
import android.app.Application
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Environment
import android.widget.Toast
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.toMutableStateList
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.google.gson.Gson
import com.google.gson.annotations.SerializedName
import kotlinx.coroutines.launch
import java.io.File
import java.io.FileOutputStream
import java.net.URL
import java.nio.channels.Channels
import java.util.UUID
import java.util.concurrent.Executors
import kotlin.concurrent.thread
import ai.mlc.mlcllm.OpenAIProtocol.ChatCompletionMessage
import ai.mlc.mlcllm.OpenAIProtocol.ChatCompletionMessageContent
import android.app.Activity
import kotlinx.coroutines.*
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import java.io.ByteArrayOutputStream
import android.util.Base64
import android.util.Log
import ai.mlc.mlcchat.data.ChatDatabase
import ai.mlc.mlcchat.data.ChatMessageDao
import ai.mlc.mlcchat.data.ChatMessageEntity
import ai.mlc.mlcchat.data.ChatSessionDao
import ai.mlc.mlcchat.data.ChatSessionEntity
import ai.mlc.mlcchat.data.DEFAULT_SESSION_TITLE
import ai.mlc.mlcchat.tools.ShellToolProtocol
import ai.mlc.mlcchat.tools.TermuxShell

class AppViewModel(application: Application) : AndroidViewModel(application) {
    val modelList = emptyList<ModelState>().toMutableStateList()
    val chatState = ChatState()
    val modelSampleList = emptyList<ModelRecord>().toMutableStateList()
    private var showAlert = mutableStateOf(false)
    private var alertMessage = mutableStateOf("")
    private var appConfig = AppConfig(
        emptyList<String>().toMutableList(),
        emptyList<ModelRecord>().toMutableList()
    )
    private val application = getApplication<Application>()
    private val appDirFile = application.getExternalFilesDir("")
    private val gson = Gson()
    private val modelIdSet = emptySet<String>().toMutableSet()
    private val chatDao: ChatMessageDao = ChatDatabase.getInstance(application).chatMessageDao()
    private val chatSessionDao: ChatSessionDao = ChatDatabase.getInstance(application).chatSessionDao()

    companion object {
        const val AppConfigFilename = "mlc-app-config.json"
        const val ModelConfigFilename = "mlc-chat-config.json"
        const val ParamsConfigFilename = "tensor-cache.json"
        const val ModelUrlSuffix = "resolve/main/"
    }

    init {
        loadAppConfig()
    }

    fun isShowingAlert(): Boolean {
        return showAlert.value
    }

    fun errorMessage(): String {
        return alertMessage.value
    }

    fun dismissAlert() {
        require(showAlert.value)
        showAlert.value = false
    }

    fun copyError() {
        require(showAlert.value)
        val clipboard =
            application.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText("MLCChat", errorMessage()))
    }

    private fun issueAlert(error: String) {
        showAlert.value = true
        alertMessage.value = error
    }

    fun requestDeleteModel(modelId: String) {
        deleteModel(modelId)
        issueAlert("Model: $modelId has been deleted")
    }


    private fun loadAppConfig() {
        val appConfigFile = File(appDirFile, AppConfigFilename)
        val jsonString: String = if (!appConfigFile.exists()) {
            application.assets.open(AppConfigFilename).bufferedReader().use { it.readText() }
        } else {
            appConfigFile.readText()
        }
        appConfig = gson.fromJson(jsonString, AppConfig::class.java)
        appConfig.modelLibs = emptyList<String>().toMutableList()
        modelList.clear()
        modelIdSet.clear()
        modelSampleList.clear()
        for (modelRecord in appConfig.modelList) {
            appConfig.modelLibs.add(modelRecord.modelLib)
            val modelDirFile = File(appDirFile, modelRecord.modelId)
            val modelConfigFile = File(modelDirFile, ModelConfigFilename)
            if (modelConfigFile.exists()) {
                val modelConfigString = modelConfigFile.readText()
                val modelConfig = gson.fromJson(modelConfigString, ModelConfig::class.java)
                modelConfig.modelId = modelRecord.modelId
                modelConfig.modelLib = modelRecord.modelLib
                modelConfig.estimatedVramBytes = modelRecord.estimatedVramBytes
                addModelConfig(modelConfig, modelRecord.modelUrl, true)
            } else {
                downloadModelConfig(
                    if (modelRecord.modelUrl.endsWith("/")) modelRecord.modelUrl else "${modelRecord.modelUrl}/",
                    modelRecord,
                    true
                )
            }
        }
    }

    private fun updateAppConfig(action: () -> Unit) {
        action()
        val jsonString = gson.toJson(appConfig)
        val appConfigFile = File(appDirFile, AppConfigFilename)
        appConfigFile.writeText(jsonString)
    }

    private fun addModelConfig(modelConfig: ModelConfig, modelUrl: String, isBuiltin: Boolean) {
        require(!modelIdSet.contains(modelConfig.modelId))
        modelIdSet.add(modelConfig.modelId)
        modelList.add(
            ModelState(
                modelConfig,
                modelUrl + if (modelUrl.endsWith("/")) "" else "/",
                File(appDirFile, modelConfig.modelId)
            )
        )
        if (!isBuiltin) {
            updateAppConfig {
                appConfig.modelList.add(
                    ModelRecord(
                        modelUrl,
                        modelConfig.modelId,
                        modelConfig.estimatedVramBytes,
                        modelConfig.modelLib
                    )
                )
            }
        }
    }

    private fun deleteModel(modelId: String) {
        val modelDirFile = File(appDirFile, modelId)
        modelDirFile.deleteRecursively()
        require(!modelDirFile.exists())
        modelIdSet.remove(modelId)
        modelList.removeIf { modelState -> modelState.modelConfig.modelId == modelId }
        updateAppConfig {
            appConfig.modelList.removeIf { modelRecord -> modelRecord.modelId == modelId }
        }
        thread(start = true) {
            chatDao.clearForModel(modelId)
            chatSessionDao.clearSessionsForModel(modelId)
        }
    }

    private fun isModelConfigAllowed(modelConfig: ModelConfig): Boolean {
        if (appConfig.modelLibs.contains(modelConfig.modelLib)) return true
        viewModelScope.launch {
            issueAlert("Model lib ${modelConfig.modelLib} is not supported.")
        }
        return false
    }


    private fun downloadModelConfig(
        modelUrl: String,
        modelRecord: ModelRecord,
        isBuiltin: Boolean
    ) {
        thread(start = true) {
            try {
                val url = URL("${modelUrl}${ModelUrlSuffix}${ModelConfigFilename}")
                val tempId = UUID.randomUUID().toString()
                val tempFile = File(
                    application.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS),
                    tempId
                )
                url.openStream().use {
                    Channels.newChannel(it).use { src ->
                        FileOutputStream(tempFile).use { fileOutputStream ->
                            fileOutputStream.channel.transferFrom(src, 0, Long.MAX_VALUE)
                        }
                    }
                }
                require(tempFile.exists())
                viewModelScope.launch {
                    try {
                        val modelConfigString = tempFile.readText()
                        val modelConfig = gson.fromJson(modelConfigString, ModelConfig::class.java)
                        modelConfig.modelId = modelRecord.modelId
                        modelConfig.modelLib = modelRecord.modelLib
                        modelConfig.estimatedVramBytes = modelRecord.estimatedVramBytes
                        if (modelIdSet.contains(modelConfig.modelId)) {
                            tempFile.delete()
                            issueAlert("${modelConfig.modelId} has been used, please consider another local ID")
                            return@launch
                        }
                        if (!isModelConfigAllowed(modelConfig)) {
                            tempFile.delete()
                            return@launch
                        }
                        val modelDirFile = File(appDirFile, modelConfig.modelId)
                        val modelConfigFile = File(modelDirFile, ModelConfigFilename)
                        tempFile.copyTo(modelConfigFile, overwrite = true)
                        tempFile.delete()
                        require(modelConfigFile.exists())
                        addModelConfig(modelConfig, modelUrl, isBuiltin)
                    } catch (e: Exception) {
                        viewModelScope.launch {
                            issueAlert("Add model failed: ${e.localizedMessage}")
                        }
                    }
                }
            } catch (e: Exception) {
                viewModelScope.launch {
                    issueAlert("Download model config failed: ${e.localizedMessage}")
                }
            }

        }
    }

    inner class ModelState(
        val modelConfig: ModelConfig,
        private val modelUrl: String,
        private val modelDirFile: File
    ) {
        var modelInitState = mutableStateOf(ModelInitState.Initializing)
        private var paramsConfig = ParamsConfig(emptyList())
        val progress = mutableStateOf(0)
        val total = mutableStateOf(1)
        val id: UUID = UUID.randomUUID()
        private val remainingTasks = emptySet<DownloadTask>().toMutableSet()
        private val downloadingTasks = emptySet<DownloadTask>().toMutableSet()
        private val maxDownloadTasks = 3
        private val gson = Gson()


        init {
            switchToInitializing()
        }

        private fun switchToInitializing() {
            val paramsConfigFile = File(modelDirFile, ParamsConfigFilename)
            if (paramsConfigFile.exists()) {
                loadParamsConfig()
                switchToIndexing()
            } else {
                downloadParamsConfig()
            }
        }

        private fun loadParamsConfig() {
            val paramsConfigFile = File(modelDirFile, ParamsConfigFilename)
            require(paramsConfigFile.exists())
            val jsonString = paramsConfigFile.readText()
            paramsConfig = gson.fromJson(jsonString, ParamsConfig::class.java)
        }

        private fun downloadParamsConfig() {
            thread(start = true) {
                val url = URL("${modelUrl}${ModelUrlSuffix}${ParamsConfigFilename}")
                val tempId = UUID.randomUUID().toString()
                val tempFile = File(modelDirFile, tempId)
                url.openStream().use {
                    Channels.newChannel(it).use { src ->
                        FileOutputStream(tempFile).use { fileOutputStream ->
                            fileOutputStream.channel.transferFrom(src, 0, Long.MAX_VALUE)
                        }
                    }
                }
                require(tempFile.exists())
                val paramsConfigFile = File(modelDirFile, ParamsConfigFilename)
                tempFile.renameTo(paramsConfigFile)
                require(paramsConfigFile.exists())
                viewModelScope.launch {
                    loadParamsConfig()
                    switchToIndexing()
                }
            }
        }

        fun handleStart() {
            switchToDownloading()
        }

        fun handlePause() {
            switchToPausing()
        }

        fun handleClear() {
            require(
                modelInitState.value == ModelInitState.Downloading ||
                        modelInitState.value == ModelInitState.Paused ||
                        modelInitState.value == ModelInitState.Finished
            )
            switchToClearing()
        }

        private fun switchToClearing() {
            if (modelInitState.value == ModelInitState.Paused) {
                modelInitState.value = ModelInitState.Clearing
                clear()
            } else if (modelInitState.value == ModelInitState.Finished) {
                modelInitState.value = ModelInitState.Clearing
                if (chatState.modelName.value == modelConfig.modelId) {
                    chatState.requestTerminateChat { clear() }
                } else {
                    clear()
                }
            } else {
                modelInitState.value = ModelInitState.Clearing
            }
        }

        fun handleDelete() {
            require(
                modelInitState.value == ModelInitState.Downloading ||
                        modelInitState.value == ModelInitState.Paused ||
                        modelInitState.value == ModelInitState.Finished
            )
            switchToDeleting()
        }

        private fun switchToDeleting() {
            if (modelInitState.value == ModelInitState.Paused) {
                modelInitState.value = ModelInitState.Deleting
                delete()
            } else if (modelInitState.value == ModelInitState.Finished) {
                modelInitState.value = ModelInitState.Deleting
                if (chatState.modelName.value == modelConfig.modelId) {
                    chatState.requestTerminateChat { delete() }
                } else {
                    delete()
                }
            } else {
                modelInitState.value = ModelInitState.Deleting
            }
        }

        private fun switchToIndexing() {
            modelInitState.value = ModelInitState.Indexing
            progress.value = 0
            total.value = modelConfig.tokenizerFiles.size + paramsConfig.paramsRecords.size
            for (tokenizerFilename in modelConfig.tokenizerFiles) {
                val file = File(modelDirFile, tokenizerFilename)
                if (file.exists()) {
                    ++progress.value
                } else {
                    remainingTasks.add(
                        DownloadTask(
                            URL("${modelUrl}${ModelUrlSuffix}${tokenizerFilename}"),
                            file
                        )
                    )
                }
            }
            for (paramsRecord in paramsConfig.paramsRecords) {
                val file = File(modelDirFile, paramsRecord.dataPath)
                if (file.exists()) {
                    ++progress.value
                } else {
                    remainingTasks.add(
                        DownloadTask(
                            URL("${modelUrl}${ModelUrlSuffix}${paramsRecord.dataPath}"),
                            file
                        )
                    )
                }
            }
            if (progress.value < total.value) {
                switchToPaused()
            } else {
                switchToFinished()
            }
        }

        private fun switchToDownloading() {
            modelInitState.value = ModelInitState.Downloading
            for (downloadTask in remainingTasks) {
                if (downloadingTasks.size < maxDownloadTasks) {
                    handleNewDownload(downloadTask)
                } else {
                    return
                }
            }
        }

        private fun handleNewDownload(downloadTask: DownloadTask) {
            require(modelInitState.value == ModelInitState.Downloading)
            require(!downloadingTasks.contains(downloadTask))
            downloadingTasks.add(downloadTask)
            thread(start = true) {
                val tempId = UUID.randomUUID().toString()
                val tempFile = File(modelDirFile, tempId)
                downloadTask.url.openStream().use {
                    Channels.newChannel(it).use { src ->
                        FileOutputStream(tempFile).use { fileOutputStream ->
                            fileOutputStream.channel.transferFrom(src, 0, Long.MAX_VALUE)
                        }
                    }
                }
                require(tempFile.exists())
                tempFile.renameTo(downloadTask.file)
                require(downloadTask.file.exists())
                viewModelScope.launch {
                    handleFinishDownload(downloadTask)
                }
            }
        }

        private fun handleNextDownload() {
            require(modelInitState.value == ModelInitState.Downloading)
            for (downloadTask in remainingTasks) {
                if (!downloadingTasks.contains(downloadTask)) {
                    handleNewDownload(downloadTask)
                    break
                }
            }
        }

        private fun handleFinishDownload(downloadTask: DownloadTask) {
            remainingTasks.remove(downloadTask)
            downloadingTasks.remove(downloadTask)
            ++progress.value
            require(
                modelInitState.value == ModelInitState.Downloading ||
                        modelInitState.value == ModelInitState.Pausing ||
                        modelInitState.value == ModelInitState.Clearing ||
                        modelInitState.value == ModelInitState.Deleting
            )
            if (modelInitState.value == ModelInitState.Downloading) {
                if (remainingTasks.isEmpty()) {
                    if (downloadingTasks.isEmpty()) {
                        switchToFinished()
                    }
                } else {
                    handleNextDownload()
                }
            } else if (modelInitState.value == ModelInitState.Pausing) {
                if (downloadingTasks.isEmpty()) {
                    switchToPaused()
                }
            } else if (modelInitState.value == ModelInitState.Clearing) {
                if (downloadingTasks.isEmpty()) {
                    clear()
                }
            } else if (modelInitState.value == ModelInitState.Deleting) {
                if (downloadingTasks.isEmpty()) {
                    delete()
                }
            }
        }

        private fun clear() {
            val files = modelDirFile.listFiles { dir, name ->
                !(dir == modelDirFile && name == ModelConfigFilename)
            }
            require(files != null)
            for (file in files) {
                file.deleteRecursively()
                require(!file.exists())
            }
            val modelConfigFile = File(modelDirFile, ModelConfigFilename)
            require(modelConfigFile.exists())
            switchToIndexing()
        }

        private fun delete() {
            modelDirFile.deleteRecursively()
            require(!modelDirFile.exists())
            requestDeleteModel(modelConfig.modelId)
        }

        private fun switchToPausing() {
            modelInitState.value = ModelInitState.Pausing
        }

        private fun switchToPaused() {
            modelInitState.value = ModelInitState.Paused
        }


        private fun switchToFinished() {
            modelInitState.value = ModelInitState.Finished
        }

        fun startChat() {
            chatState.requestReloadChat(
                modelConfig,
                modelDirFile.absolutePath,
            )
        }

    }

    inner class ChatState {
        val messages = emptyList<MessageData>().toMutableStateList()
        val report = mutableStateOf("")
        val modelName = mutableStateOf("")
        // The chat "session" (topic/conversation) currently shown. Null only
        // very briefly before a model has finished its first load.
        val currentSessionId = mutableStateOf<Long?>(null)
        // Sessions for modelName.value, newest-first; populated by refreshSessionList().
        val sessionList = emptyList<ChatSessionEntity>().toMutableStateList()
        private var modelChatState = mutableStateOf(ModelChatState.Ready)
            @Synchronized get
            @Synchronized set
        private val engine = MLCEngine()
        private var historyMessages = mutableListOf<ChatCompletionMessage>()
        private var modelLib = ""
        private var modelPath = ""
        private val executorService = Executors.newSingleThreadExecutor()
        private val viewModelScope = CoroutineScope(Dispatchers.Main + Job())
        private var imageUri: Uri? = null

        // --- Shell tools (Termux bridge) ---
        // User-facing toggle: when on, the model may request shell commands
        // via the ShellToolProtocol exec-block convention.
        val shellToolsEnabled = mutableStateOf(false)
        // When non-null, a command the model proposed is waiting for the user
        // to approve or decline (drives the confirmation dialog in ChatView).
        val pendingCommand = mutableStateOf<String?>(null)
        // The tool-loop step index of the pending command, so a continuation
        // keeps counting toward ShellToolProtocol.MAX_STEPS.
        private var pendingStepCount = 0

        // Must be called from a background thread (i.e. already inside an
        // executorService.submit block) since it hits the DB.
        private fun createSession(): ChatSessionEntity {
            val now = System.currentTimeMillis()
            val id = chatSessionDao.insert(
                ChatSessionEntity(
                    modelId = modelName.value,
                    title = DEFAULT_SESSION_TITLE,
                    createdAt = now,
                    lastUpdatedAt = now
                )
            )
            return ChatSessionEntity(id, modelName.value, DEFAULT_SESSION_TITLE, now, now)
        }

        // Must be called from a background thread. Pushes the refreshed list
        // to the UI-facing sessionList.
        private fun queryAndPublishSessionList() {
            val sessions = chatSessionDao.getSessionsForModel(modelName.value)
            viewModelScope.launch {
                sessionList.clear()
                sessionList.addAll(sessions)
            }
        }

        fun refreshSessionList() {
            executorService.submit { queryAndPublishSessionList() }
        }

        /** Starts a brand-new, empty chat for the current model (kept, not deleted). */
        fun requestNewChat() {
            require(interruptable())
            interruptChat(
                prologue = { switchToResetting() },
                epilogue = { mainNewChat() }
            )
        }

        private fun mainNewChat() {
            imageUri = null
            executorService.submit {
                callBackend { engine.reset() }
                historyMessages = mutableListOf<ChatCompletionMessage>()
                val session = createSession()
                currentSessionId.value = session.id
                queryAndPublishSessionList()
                viewModelScope.launch {
                    clearHistory()
                    switchToReady()
                }
            }
        }

        /** Deletes the chat currently being viewed, then opens a fresh empty one. */
        fun requestDeleteCurrentChat() {
            require(interruptable())
            val sessionToDelete = currentSessionId.value
            interruptChat(
                prologue = { switchToResetting() },
                epilogue = { mainDeleteCurrentChat(sessionToDelete) }
            )
        }

        private fun mainDeleteCurrentChat(sessionId: Long?) {
            imageUri = null
            executorService.submit {
                callBackend { engine.reset() }
                historyMessages = mutableListOf<ChatCompletionMessage>()
                if (sessionId != null) {
                    chatDao.clearForSession(sessionId)
                    chatSessionDao.deleteSession(sessionId)
                }
                val session = createSession()
                currentSessionId.value = session.id
                queryAndPublishSessionList()
                viewModelScope.launch {
                    clearHistory()
                    switchToReady()
                }
            }
        }

        /** Switches to a different, already-existing chat/topic. */
        fun requestSwitchSession(sessionId: Long) {
            if (currentSessionId.value == sessionId) return
            require(interruptable())
            interruptChat(
                prologue = { switchToResetting() },
                epilogue = { mainSwitchSession(sessionId) }
            )
        }

        private fun mainSwitchSession(sessionId: Long) {
            imageUri = null
            executorService.submit {
                callBackend { engine.reset() }
                val savedMessages = chatDao.getMessagesForSession(sessionId)
                val newHistory = savedMessages.map { saved ->
                    ChatCompletionMessage(
                        role = if (saved.role == MessageRole.User.name)
                            OpenAIProtocol.ChatCompletionRole.user
                        else OpenAIProtocol.ChatCompletionRole.assistant,
                        content = saved.text
                    )
                }.toMutableList()
                currentSessionId.value = sessionId
                viewModelScope.launch {
                    messages.clear()
                    report.value = ""
                    historyMessages = newHistory
                    for (saved in savedMessages) {
                        val role = if (saved.role == MessageRole.User.name)
                            MessageRole.User else MessageRole.Assistant
                        messages.add(MessageData(role, saved.text))
                    }
                    switchToReady()
                }
            }
        }

        /**
         * Deletes an arbitrary chat from the session list (e.g. one that
         * isn't currently open). Deleting the currently-open one falls back
         * to [requestDeleteCurrentChat] so a chat is always showing.
         */
        fun requestDeleteSession(sessionId: Long) {
            if (sessionId == currentSessionId.value) {
                requestDeleteCurrentChat()
                return
            }
            executorService.submit {
                chatDao.clearForSession(sessionId)
                chatSessionDao.deleteSession(sessionId)
                queryAndPublishSessionList()
            }
        }

        private fun clearHistory() {
            messages.clear()
            report.value = ""
            historyMessages.clear()
        }


        private fun switchToResetting() {
            modelChatState.value = ModelChatState.Resetting
        }

        private fun switchToGenerating() {
            modelChatState.value = ModelChatState.Generating
        }

        private fun switchToReloading() {
            modelChatState.value = ModelChatState.Reloading
        }

        private fun switchToReady() {
            modelChatState.value = ModelChatState.Ready
        }

        private fun switchToFailed() {
            modelChatState.value = ModelChatState.Falied
        }

        private fun callBackend(callback: () -> Unit): Boolean {
            try {
                callback()
            } catch (e: Exception) {
                viewModelScope.launch {
                    val stackTrace = e.stackTraceToString()
                    val errorMessage = e.localizedMessage
                    appendMessage(
                        MessageRole.Assistant,
                        "MLCChat failed\n\nStack trace:\n$stackTrace\n\nError message:\n$errorMessage"
                    )
                    switchToFailed()
                }
                return false
            }
            return true
        }

        private fun interruptChat(prologue: () -> Unit, epilogue: () -> Unit) {
            // prologue runs before interruption
            // epilogue runs after interruption
            require(interruptable())
            if (modelChatState.value == ModelChatState.Ready) {
                prologue()
                epilogue()
            } else if (modelChatState.value == ModelChatState.Generating) {
                prologue()
                executorService.submit {
                    viewModelScope.launch { epilogue() }
                }
            } else {
                require(false)
            }
        }

        fun requestTerminateChat(callback: () -> Unit) {
            require(interruptable())
            interruptChat(
                prologue = {
                    switchToTerminating()
                },
                epilogue = {
                    mainTerminateChat(callback)
                }
            )
        }

        private fun mainTerminateChat(callback: () -> Unit) {
            executorService.submit {
                callBackend { engine.unload() }
                viewModelScope.launch {
                    clearHistory()
                    switchToReady()
                    callback()
                }
            }
        }

        private fun switchToTerminating() {
            modelChatState.value = ModelChatState.Terminating
        }


        fun requestReloadChat(modelConfig: ModelConfig, modelPath: String) {

            if (this.modelName.value == modelConfig.modelId && this.modelLib == modelConfig.modelLib && this.modelPath == modelPath) {
                return
            }
            require(interruptable())
            interruptChat(
                prologue = {
                    switchToReloading()
                },
                epilogue = {
                    mainReloadChat(modelConfig, modelPath)
                }
            )
        }

        private fun mainReloadChat(modelConfig: ModelConfig, modelPath: String) {
            clearHistory()
            this.modelName.value = modelConfig.modelId
            this.modelLib = modelConfig.modelLib
            this.modelPath = modelPath
            executorService.submit {
                viewModelScope.launch {
                    Toast.makeText(application, "Initialize...", Toast.LENGTH_SHORT).show()
                }
                if (!callBackend {
                        engine.unload()
                        engine.reload(modelPath, modelConfig.modelLib)
                    }) return@submit
                // Resume the most recently used chat for this model, creating
                // a first one if it has never been opened before.
                val existingSessions = chatSessionDao.getSessionsForModel(modelConfig.modelId)
                val session = existingSessions.firstOrNull() ?: createSession()
                currentSessionId.value = session.id
                val savedMessages = chatDao.getMessagesForSession(session.id)
                viewModelScope.launch {
                    for (saved in savedMessages) {
                        val role = if (saved.role == MessageRole.User.name)
                            MessageRole.User else MessageRole.Assistant
                        messages.add(MessageData(role, saved.text))
                    }
                    historyMessages = savedMessages.map { saved ->
                        ChatCompletionMessage(
                            role = if (saved.role == MessageRole.User.name)
                                OpenAIProtocol.ChatCompletionRole.user
                            else OpenAIProtocol.ChatCompletionRole.assistant,
                            content = saved.text
                        )
                    }.toMutableList()
                    Toast.makeText(application, "Ready to chat", Toast.LENGTH_SHORT).show()
                    switchToReady()
                }
            }
        }

        fun requestImageBitmap(uri: Uri?) {
            require(chatable())
            switchToGenerating()
            executorService.submit {
                imageUri = uri
                viewModelScope.launch {
                    report.value = "Image process is done, ask any question."
                    if (modelChatState.value == ModelChatState.Generating) switchToReady()
                }
            }
        }

        fun bitmapToURL(bm: Bitmap): String {
            val targetSize = 336
            val scaledBitmap = Bitmap.createScaledBitmap(bm, targetSize, targetSize, true)

            val outputStream = ByteArrayOutputStream()
            scaledBitmap.compress(Bitmap.CompressFormat.JPEG, 100, outputStream)
            scaledBitmap.recycle()

            val imageBytes = outputStream.toByteArray()
            val imageBase64 = Base64.encodeToString(imageBytes, Base64.NO_WRAP)
            return "data:image/jpg;base64,$imageBase64"
        }

        fun requestGenerate(prompt: String, activity: Activity) {
            require(chatable())
            switchToGenerating()
            appendMessage(MessageRole.User, prompt)
            persistMessage(MessageRole.User, prompt)
            appendMessage(MessageRole.Assistant, "")
            var content = ChatCompletionMessageContent(text=prompt)
            if (imageUri != null) {
                val uri = imageUri
                val bitmap = uri?.let {
                    activity.contentResolver.openInputStream(it)?.use { input ->
                        BitmapFactory.decodeStream(input)
                    }
                }
                val imageBase64URL = bitmapToURL(bitmap!!)
                Log.v("requestGenerate", "image base64 url: $imageBase64URL")
                val parts = listOf(
                    mapOf("type" to "text", "text" to prompt),
                    mapOf("type" to "image_url", "image_url" to imageBase64URL)
                )
                content = ChatCompletionMessageContent(parts=parts)
                imageUri = null
            }

            executorService.submit {
                ensureSystemPrompt()
                historyMessages.add(ChatCompletionMessage(
                    role = OpenAIProtocol.ChatCompletionRole.user,
                    content = content
                ))
                viewModelScope.launch {
                    streamAssistant(stepCount = 0)
                }
            }
        }

        /**
         * Runs one assistant completion against [historyMessages] (whose last
         * entry must be the triggering user/tool message), streams it into the
         * trailing Assistant placeholder bubble, persists it, then hands off to
         * [onAssistantComplete] which decides whether a shell tool step
         * follows. [stepCount] counts shell tool iterations for this turn.
         *
         * The Assistant placeholder bubble is expected to already be appended
         * before this is called.
         */
        private suspend fun streamAssistant(stepCount: Int) {
            val responses = engine.chat.completions.create(
                messages = historyMessages,
                stream_options = OpenAIProtocol.StreamOptions(include_usage = true)
            )

            var finishReasonLength = false
            var streamingText = ""

            for (res in responses) {
                if (!callBackend {
                    for (choice in res.choices) {
                        choice.delta.content?.let { content ->
                            streamingText += content.asText()
                        }
                        choice.finish_reason?.let { finishReason ->
                            if (finishReason == "length") {
                                finishReasonLength = true
                            }
                        }
                    }
                    updateMessage(MessageRole.Assistant, streamingText)
                    res.usage?.let { finalUsage ->
                        report.value = finalUsage.extra?.asTextLabel() ?: ""
                    }
                    if (finishReasonLength) {
                        streamingText += " [output truncated due to context length limit...]"
                        updateMessage(MessageRole.Assistant, streamingText)
                    }
                });
            }
            if (streamingText.isNotEmpty()) {
                historyMessages.add(ChatCompletionMessage(
                    role = OpenAIProtocol.ChatCompletionRole.assistant,
                    content = streamingText
                ))
                persistMessage(MessageRole.Assistant, streamingText)
            } else {
                if (historyMessages.isNotEmpty()) {
                    historyMessages.removeAt(historyMessages.size - 1)
                }
            }

            onAssistantComplete(streamingText, stepCount)
        }

        /**
         * After a completed assistant message: if shell tools are on and the
         * reply contains an exec block (and we're under the step limit),
         * surface the proposed command for user approval (leaving the chat in
         * the Generating state so input stays locked). Otherwise finish the
         * turn.
         */
        private fun onAssistantComplete(assistantText: String, stepCount: Int) {
            if (modelChatState.value != ModelChatState.Generating) return

            if (shellToolsEnabled.value && assistantText.isNotEmpty()) {
                val command = ShellToolProtocol.extractCommand(assistantText)
                if (command != null) {
                    if (stepCount >= ShellToolProtocol.MAX_STEPS) {
                        appendMessage(
                            MessageRole.Assistant,
                            "[tool step limit of ${ShellToolProtocol.MAX_STEPS} reached — " +
                                "not running further commands this turn]"
                        )
                        switchToReady()
                        return
                    }
                    // Park the command for the confirmation dialog. Stay in
                    // Generating so the composer stays disabled until the user
                    // approves or declines.
                    pendingStepCount = stepCount
                    pendingCommand.value = command
                    return
                }
            }
            switchToReady()
        }

        /** User approved the parked command: run it in Termux and continue. */
        fun approvePendingCommand() {
            val command = pendingCommand.value ?: return
            pendingCommand.value = null
            val step = pendingStepCount
            viewModelScope.launch {
                val result = TermuxShell.run(application, command)
                val feedback = ShellToolProtocol.formatResultForModel(result)
                appendMessage(MessageRole.User, feedback)
                persistMessage(MessageRole.User, feedback)
                appendMessage(MessageRole.Assistant, "")
                historyMessages.add(
                    ChatCompletionMessage(
                        role = OpenAIProtocol.ChatCompletionRole.user,
                        content = feedback
                    )
                )
                streamAssistant(stepCount = step + 1)
            }
        }

        /** User declined the parked command: note it and end the turn. */
        fun declinePendingCommand() {
            if (pendingCommand.value == null) return
            pendingCommand.value = null
            appendMessage(
                MessageRole.Assistant,
                "[command declined by user — not run]"
            )
            if (modelChatState.value == ModelChatState.Generating) switchToReady()
        }

        /**
         * Keeps [historyMessages] front-loaded with the shell-tools system
         * prompt while the toggle is on. Only inserts once; reloading a model
         * clears history, so there's no stale-prompt buildup.
         */
        private fun ensureSystemPrompt() {
            val hasSystem = historyMessages.firstOrNull()?.role ==
                OpenAIProtocol.ChatCompletionRole.system
            if (shellToolsEnabled.value && !hasSystem) {
                historyMessages.add(
                    0,
                    ChatCompletionMessage(
                        role = OpenAIProtocol.ChatCompletionRole.system,
                        content = ShellToolProtocol.SYSTEM_PROMPT
                    )
                )
            }
        }

        private fun appendMessage(role: MessageRole, text: String) {
            messages.add(MessageData(role, text))
        }

        // Persists a completed message (not called for the empty placeholder
        // appended before streaming starts, nor for each streaming delta —
        // only once a message's final text is known) off the main thread.
        private fun persistMessage(role: MessageRole, text: String) {
            val forModelId = modelName.value
            val forSessionId = currentSessionId.value ?: return
            executorService.submit {
                val nextIndex = chatDao.getMaxOrderIndex(forSessionId) + 1
                chatDao.insert(
                    ChatMessageEntity(
                        sessionId = forSessionId,
                        modelId = forModelId,
                        role = role.name,
                        text = text,
                        orderIndex = nextIndex
                    )
                )
                val now = System.currentTimeMillis()
                chatSessionDao.touchSession(forSessionId, now)
                if (role == MessageRole.User) {
                    // Only takes effect once: the query only matches while
                    // the session still has the default placeholder title.
                    val title = text.trim().let { if (it.length > 40) it.take(40) + "…" else it }
                    if (title.isNotEmpty()) {
                        chatSessionDao.setInitialTitleIfDefault(forSessionId, title)
                    }
                }
            }
        }


        private fun updateMessage(role: MessageRole, text: String) {
            messages[messages.size - 1] = MessageData(role, text)
        }

        fun chatable(): Boolean {
            return modelChatState.value == ModelChatState.Ready
        }

        fun interruptable(): Boolean {
            return modelChatState.value == ModelChatState.Ready
                    || modelChatState.value == ModelChatState.Generating
                    || modelChatState.value == ModelChatState.Falied
        }
    }
}

enum class ModelInitState {
    Initializing,
    Indexing,
    Paused,
    Downloading,
    Pausing,
    Clearing,
    Deleting,
    Finished
}

enum class ModelChatState {
    Generating,
    Resetting,
    Reloading,
    Terminating,
    Ready,
    Falied
}

enum class MessageRole {
    Assistant,
    User
}

data class DownloadTask(val url: URL, val file: File)

data class MessageData(val role: MessageRole, val text: String, val id: UUID = UUID.randomUUID(), var imageUri: Uri? = null)

data class AppConfig(
    @SerializedName("model_libs") var modelLibs: MutableList<String>,
    @SerializedName("model_list") val modelList: MutableList<ModelRecord>,
)

data class ModelRecord(
    @SerializedName("model_url") val modelUrl: String,
    @SerializedName("model_id") val modelId: String,
    @SerializedName("estimated_vram_bytes") val estimatedVramBytes: Long?,
    @SerializedName("model_lib") val modelLib: String
)

data class ModelConfig(
    @SerializedName("model_lib") var modelLib: String,
    @SerializedName("model_id") var modelId: String,
    @SerializedName("estimated_vram_bytes") var estimatedVramBytes: Long?,
    @SerializedName("tokenizer_files") val tokenizerFiles: List<String>,
    @SerializedName("context_window_size") val contextWindowSize: Int,
    @SerializedName("prefill_chunk_size") val prefillChunkSize: Int,
)

data class ParamsRecord(
    @SerializedName("dataPath") val dataPath: String
)

data class ParamsConfig(
    @SerializedName("records") val paramsRecords: List<ParamsRecord>
)
