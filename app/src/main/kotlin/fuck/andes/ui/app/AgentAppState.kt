package fuck.andes.ui.app

import android.content.ComponentName
import android.content.ContentResolver
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.PowerManager
import android.provider.Settings
import android.widget.Toast
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import fuck.andes.FuckAndesApp
import fuck.andes.agent.accessibility.AgentAccessibilityService
import fuck.andes.agent.device.AgentFileReferenceGateway
import fuck.andes.agent.device.DeviceLocationProvider
import fuck.andes.agent.media.AgentImageCodec
import fuck.andes.agent.memory.AgentMemoryContextBuilder
import fuck.andes.agent.model.AgentModelClient
import fuck.andes.agent.model.AgentFileReference
import fuck.andes.agent.model.AgentFileReferenceKind
import fuck.andes.agent.model.AgentFileReferencePolicy
import fuck.andes.agent.model.AgentFileReferencePromptCodec
import fuck.andes.agent.runtime.AgentEvent
import fuck.andes.agent.runtime.AgentExternalArchivePayload
import fuck.andes.agent.runtime.AgentRunArchiveStore
import fuck.andes.agent.runtime.AgentRuntimeClient
import fuck.andes.agent.runtime.AgentRuntimeWire
import fuck.andes.agent.runtime.AgentTokenUsage
import fuck.andes.agent.runtime.AgentUiHandoffPayload
import fuck.andes.agent.skill.SkillRuntime
import fuck.andes.config.Prefs
import fuck.andes.core.AndroidAgentLogger
import fuck.andes.core.safeLogType
import fuck.andes.data.model.ModelReasoningCapabilities
import fuck.andes.data.model.ReasoningEffort
import fuck.andes.data.model.selectedOrFirstModel
import fuck.andes.data.repository.AgentMemoryRepository
import fuck.andes.data.repository.ProviderRepository
import fuck.andes.data.repository.RuntimeConfigRepository
import fuck.andes.ui.model.AgentChatHomeUiState
import fuck.andes.ui.model.AgentChatMessageUi
import fuck.andes.ui.model.AgentMessageUi
import fuck.andes.ui.model.AgentMemoryUiState
import fuck.andes.ui.model.MessageEditUiState
import fuck.andes.ui.model.AgentSkillsUiState
import fuck.andes.ui.model.AgentSystemEnhanceUiState
import fuck.andes.ui.model.AgentToolsUiState
import fuck.andes.ui.model.ConversationModeUi
import fuck.andes.ui.model.ConversationPaneUiState
import fuck.andes.ui.model.ConversationSummaryUi
import fuck.andes.ui.model.PermissionHealthItemUi
import fuck.andes.ui.model.PermissionHealthUiState
import fuck.andes.ui.model.PermissionStatusUi
import fuck.andes.ui.model.PendingImageUi
import fuck.andes.ui.model.PendingFileReferenceUi
import fuck.andes.ui.model.SkillItemUi
import fuck.andes.ui.model.SkillNoticeUi
import fuck.andes.ui.model.SkillReplacementUi
import fuck.andes.ui.model.canDeleteUserSkill
import fuck.andes.ui.model.SystemEnhanceItemUi
import fuck.andes.ui.model.SystemEnhanceSectionUi
import fuck.andes.ui.model.SystemEnhanceStatusUi
import fuck.andes.ui.model.ThinkingMessageUi
import fuck.andes.ui.model.TokenUsageUi
import fuck.andes.ui.model.ToolActivityMessageUi
import fuck.andes.ui.model.ToolGroupUi
import fuck.andes.ui.model.ToolItemUi
import fuck.andes.ui.model.UserMessageUi
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** 顶部栏模型切换器条目。 */
data class ModelSwitcherItem(
    val id: String,
    val displayName: String,
)

internal class AgentAppState(
    context: Context,
    private val scope: CoroutineScope,
    skillZipImportGateway: SkillZipImportGateway? = null,
) {
    private val appContext = context.applicationContext
    private val skillZipImportGateway = skillZipImportGateway ?: CoreSkillZipImportGateway(appContext)
    private val runConversationIds = mutableMapOf<String, String>()
    private val runMessageProjector = AgentRunMessageProjector()
    private val runEventCoalescer = AgentRunEventCoalescer()
    private val runEventFlushJobs = mutableMapOf<String, Job>()
    private var currentRunId: String? = null
    private var currentRunJob: Job? = null
    private val persistenceLock = Any()
    private var persistenceJob: Job? = null
    private val runtimeRecoveryInProgress = AtomicBoolean(false)
    private val defaultThinkingEnabled = agentBooleanForUi(Prefs.Keys.AGENT_THINKING_ENABLED)
    private val initialConversations = AgentConversationStore.load(appContext)
    private var skillNoticeSequence = 0L
    private var pendingSkillZipUri: Uri? = null
    private var pendingSkillZipSha256: String? = null
    private var currentReasoningCapabilities: ModelReasoningCapabilities? = null
    private var fileAttachmentOwnerVersion = 0L

    private var selectedConversationId: String? = initialConversations.selectedConversationId
    private var conversationsById: Map<String, AgentChatHomeUiState> = initialConversations.conversationsById
    private var conversationTitles: Map<String, String> = initialConversations.titles
    private var conversationUpdatedAt: Map<String, Long> = initialConversations.updatedAt

    var homeState by mutableStateOf(
        selectedConversationId?.let(conversationsById::get) ?: emptyChatState(defaultThinkingEnabled)
    )
        private set

    var conversationPaneState by mutableStateOf(
        ConversationPaneUiState(
            conversations = emptyList(),
            selectedConversationId = selectedConversationId,
            searchQuery = "",
        )
    )
        private set

    var toolsState by mutableStateOf(buildToolsState())
        private set

    var skillsState by mutableStateOf(AgentSkillsUiState(isLoading = true))
        private set

    var permissionHealthState by mutableStateOf(buildPermissionHealthState(appContext))
        private set

    var systemEnhanceState by mutableStateOf(buildSystemEnhanceState())
        private set

    var memoryState by mutableStateOf(AgentMemoryUiState())
        private set

    /** 最近一轮 usage.inputTokens：模型实际收到的上下文 tokens。 */
    var contextUsageTokens by mutableStateOf<Int?>(null)
        private set

    /** 当前模型 context_window 上限。 */
    var contextWindowTokens by mutableStateOf<Int?>(null)
        private set

    /** 最近一轮请求发生了上下文裁剪（供圆环转圈动画使用）。 */
    var contextJustTrimmed by mutableStateOf(false)
        private set

    /** 当前选中模型的显示名（顶部栏展示）。 */
    var currentModelName by mutableStateOf<String?>(null)
        private set

    /** 当前提供商下可用的模型列表（顶部栏切换用）。 */
    var currentProviderModels by mutableStateOf<List<ModelSwitcherItem>>(emptyList())
        private set

    init {
        refreshConversationSummaries()
        observeReasoningCapabilities()
        runtimeRecoveryInProgress.set(true)
        scope.launch(Dispatchers.IO) {
            try {
                recoverOrphanedRuns()
                importArchivedExternalRuns()
            } finally {
                runtimeRecoveryInProgress.set(false)
            }
        }
    }

    private fun observeReasoningCapabilities() {
        scope.launch(Dispatchers.IO) {
            combine(
                RuntimeConfigRepository.selectedProviderIdFlow(),
                RuntimeConfigRepository.selectedModelIdFlow(),
                ProviderRepository.providersFlow(),
            ) { providerId, modelId, providers ->
                Triple(providerId, modelId, providers.hashCode())
            }
                .distinctUntilChanged()
                .collectLatest {
                    val capabilities = RuntimeConfigRepository.currentRuntimeConfig()
                        ?.reasoningCapabilities
                    withContext(Dispatchers.Main) {
                        applyReasoningCapabilities(capabilities)
                        refreshModelSwitcher()
                    }
                }
        }
    }

    /** 刷新顶部栏模型名与同提供商模型列表（按当前选中 provider 聚合）。 */
    private suspend fun refreshModelSwitcher() {
        val settings = ProviderRepository.repairSelection()
        val provider = settings.selectedProviderId
            ?.let { ProviderRepository.providerById(it) }
            ?: return
        val selected = provider.selectedOrFirstModel(settings.selectedModelId) ?: return
        currentModelName = selected.displayName.ifBlank { selected.modelId }
        currentProviderModels = provider.models
            .filter { it.isEnabled }
            .sortedBy { it.sortOrder }
            .map { ModelSwitcherItem(id = it.id, displayName = it.displayName.ifBlank { it.modelId }) }
    }

    private fun applyReasoningCapabilities(capabilities: ModelReasoningCapabilities?) {
        currentReasoningCapabilities = capabilities
        val next = homeState.withCurrentReasoningCapabilities()
        val changed = next.reasoningEffort != homeState.reasoningEffort ||
            next.availableReasoningEfforts != homeState.availableReasoningEfforts
        updateCurrentConversation(next)
        if (changed && selectedConversationId != null) persistConversations()
    }

    private fun AgentChatHomeUiState.withCurrentReasoningCapabilities(): AgentChatHomeUiState {
        val normalized = currentReasoningCapabilities?.normalize(reasoningEffort) ?: ReasoningEffort.OFF
        return copy(
            thinkingEnabled = normalized.enablesReasoning,
            reasoningEffort = normalized,
            availableReasoningEfforts = currentReasoningCapabilities?.selectableEfforts.orEmpty(),
        )
    }

    fun refreshRuntimeResults() {
        if (!runtimeRecoveryInProgress.compareAndSet(false, true)) return
        scope.launch(Dispatchers.IO) {
            try {
                recoverOrphanedRuns()
                importArchivedExternalRuns()
            } finally {
                runtimeRecoveryInProgress.set(false)
            }
        }
    }

    fun refreshMemory() {
        memoryState = memoryState.copy(isLoading = true, notice = null)
        scope.launch(Dispatchers.IO) {
            runCatching {
                val snapshot = AgentMemoryRepository.snapshot()
                val enabled = AgentMemoryRepository.isEnabled()
                val contextWindow = RuntimeConfigRepository.currentRuntimeConfig()?.contextWindow
                Triple(snapshot, enabled, AgentMemoryContextBuilder.coreBudgetChars(contextWindow))
            }.fold(
                onSuccess = { (snapshot, enabled, coreBudget) ->
                    withContext(Dispatchers.Main) {
                        memoryState = AgentMemoryUiState(
                            enabled = enabled,
                            isLoading = false,
                            draft = snapshot.content,
                            savedContent = snapshot.content,
                            draftBytes = snapshot.byteSize,
                            coreBudgetChars = coreBudget,
                        )
                    }
                },
                onFailure = { throwable ->
                    AndroidAgentLogger.warnThrottled("agent_memory_ui_load_failed") {
                        "Agent memory UI load failed: type=${throwable.safeLogType()}"
                    }
                    withContext(Dispatchers.Main) {
                        memoryState = memoryState.copy(
                            isLoading = false,
                            notice = "读取记忆失败，请稍后重试",
                        )
                    }
                },
            )
        }
    }

    fun updateMemoryDraft(content: String) {
        memoryState = memoryState.copy(
            draft = content,
            draftBytes = content.toByteArray(Charsets.UTF_8).size,
            notice = null,
        )
    }

    fun setMemoryEnabled(enabled: Boolean) {
        scope.launch(Dispatchers.IO) {
            runCatching { AgentMemoryRepository.setEnabled(enabled) }
                .fold(
                    onSuccess = {
                        withContext(Dispatchers.Main) {
                            memoryState = memoryState.copy(enabled = enabled, notice = null)
                        }
                    },
                    onFailure = { throwable ->
                        AndroidAgentLogger.warnThrottled("agent_memory_toggle_failed") {
                            "Agent memory setting update failed: type=${throwable.safeLogType()}"
                        }
                        withContext(Dispatchers.Main) {
                            memoryState = memoryState.copy(notice = "记忆开关保存失败")
                        }
                    },
                )
        }
    }

    fun saveMemory() {
        if (!memoryState.canSave) return
        val target = memoryState.draft
        memoryState = memoryState.copy(isSaving = true, notice = null)
        scope.launch(Dispatchers.IO) {
            runCatching { AgentMemoryRepository.replaceAll(target) }
                .fold(
                    onSuccess = { snapshot ->
                        withContext(Dispatchers.Main) {
                            memoryState = memoryState.copy(
                                isSaving = false,
                                savedContent = snapshot.content,
                                draft = if (memoryState.draft == target) {
                                    snapshot.content
                                } else {
                                    memoryState.draft
                                },
                                draftBytes = memoryState.draft.toByteArray(Charsets.UTF_8).size,
                                notice = "记忆已保存",
                            )
                        }
                    },
                    onFailure = { throwable ->
                        AndroidAgentLogger.warnThrottled("agent_memory_ui_save_failed") {
                            "Agent memory UI save failed: type=${throwable.safeLogType()}"
                        }
                        withContext(Dispatchers.Main) {
                            memoryState = memoryState.copy(
                                isSaving = false,
                                notice = throwable.message ?: "记忆保存失败",
                            )
                        }
                    },
                )
        }
    }

    fun clearMemory() {
        if (memoryState.isSaving) return
        memoryState = memoryState.copy(isSaving = true, notice = null)
        scope.launch(Dispatchers.IO) {
            runCatching { AgentMemoryRepository.replaceAll("") }
                .fold(
                    onSuccess = {
                        withContext(Dispatchers.Main) {
                            memoryState = memoryState.copy(
                                isSaving = false,
                                draft = "",
                                savedContent = "",
                                draftBytes = 0,
                                notice = "记忆已清空",
                            )
                        }
                    },
                    onFailure = { throwable ->
                        AndroidAgentLogger.warnThrottled("agent_memory_ui_clear_failed") {
                            "Agent memory UI clear failed: type=${throwable.safeLogType()}"
                        }
                        withContext(Dispatchers.Main) {
                            memoryState = memoryState.copy(
                                isSaving = false,
                                notice = throwable.message ?: "记忆清空失败",
                            )
                        }
                    },
                )
        }
    }

    fun dismissMemoryNotice() {
        memoryState = memoryState.copy(notice = null)
    }

    /**
     * App 进程可能在 Agent 操作手机期间被系统杀死，导致最终结果未能更新到会话。
     * 从 Runtime 进程拉取未交付的结果，补回对应会话的 assistant 消息。
     */
    private suspend fun recoverOrphanedRuns() {
        val client = AgentRuntimeClient(appContext, AndroidAgentLogger)
        val completedRuns = runCatching {
            client.drainCompletedRuns()
        }.getOrElse { throwable ->
            AndroidAgentLogger.warnThrottled("agent_ui_drain_results_failed") {
                "Agent UI pending result recovery failed: type=${throwable.safeLogType()}"
            }
            emptyList()
        }
        if (completedRuns.isEmpty()) return
        val ours = completedRuns.filter { it.handoff.source == HANDOFF_SOURCE }
        if (ours.isEmpty()) return
        withContext(Dispatchers.Main) {
            val acknowledgeAfterSave = mutableListOf<String>()
            ours.forEach { completedRun ->
                val runId = completedRun.result.runId.ifBlank { completedRun.handoff.id }
                val payload = AgentUiHandoffPayload.from(completedRun.handoff.payload)
                val conversationId = payload.conversationId
                val state = conversationsById[conversationId] ?: return@forEach
                val result = completedRun.result
                val recovery = AgentPendingResultRecovery.apply(
                    state = state,
                    runId = runId,
                    result = result,
                    promptSupplement = payload.promptSupplement,
                    supplements = payload.supplements,
                )
                if (recovery.alreadyApplied) {
                    acknowledgeAfterSave += runId
                    return@forEach
                }
                updateConversation(conversationId, recovery.state)
                acknowledgeAfterSave += runId
            }
            refreshConversationSummaries()
            persistConversations {
                acknowledgeAfterSave.forEach(client::ackResult)
            }
        }
    }

    private suspend fun importArchivedExternalRuns() {
        val archivedRuns = withContext(Dispatchers.IO) {
            AgentRunArchiveStore.list(appContext)
                .filter { AgentExternalArchivePayload.from(it.handoff.payload) != null }
        }
        if (archivedRuns.isEmpty()) return

        withContext(Dispatchers.Main) {
            val importedRunIds = archivedRuns.mapNotNull { archivedRun ->
                importExternalRun(archivedRun)
            }
            refreshConversationSummaries()
            persistConversations {
                importedRunIds.forEach { runId ->
                    AgentRunArchiveStore.remove(appContext, runId)
                }
            }
        }
    }

    suspend fun openAssistantConversation(conversationKey: String): Boolean {
        if (conversationKey.isBlank()) return false
        importArchivedExternalRuns()
        return withContext(Dispatchers.Main.immediate) {
            val conversationId = archiveConversationId(
                source = AgentRuntimeWire.ETA_VOICE_HANDOFF_SOURCE,
                conversationKey = conversationKey,
            )
            if (conversationsById[conversationId] == null) {
                false
            } else {
                selectConversation(conversationId)
                true
            }
        }
    }

    private fun importExternalRun(archivedRun: AgentRunArchiveStore.ArchivedRun): String? {
        val runId = archivedRun.result.runId.ifBlank { archivedRun.handoff.id }
        if (runId.isBlank()) return null
        val payload = AgentExternalArchivePayload.from(archivedRun.handoff.payload) ?: return null
        val conversationId = archiveConversationId(
            source = archivedRun.handoff.source,
            conversationKey = payload.conversationKey,
        )
        val archivedEffort = payload.reasoningEffort
            ?: payload.thinkingEnabled?.let(ReasoningEffort::fromLegacy)
            ?: ReasoningEffort.fromLegacy(defaultThinkingEnabled)
        val existingState = conversationsById[conversationId] ?: emptyChatState(
            archivedEffort.enablesReasoning
        ).copy(reasoningEffort = archivedEffort)
        val alreadyImported = AgentRuntimeHistoryReducer.wasApplied(existingState, runId) ||
            existingState.messages.any {
                it is AgentMessageUi &&
                    (it.id == "assistant-$runId" || it.id.startsWith("assistant-$runId-")) &&
                    !it.isStreaming
            }
        if (alreadyImported) return runId

        if (conversationTitles[conversationId].isNullOrBlank() || conversationTitles[conversationId] == "新对话") {
            conversationTitles = conversationTitles + (conversationId to payload.title.ifBlank { "外部记录" })
        }
        runConversationIds[runId] = conversationId
        updateConversation(
            conversationId,
            existingState.copy(
                input = "",
                isStreaming = true,
                thinkingEnabled = archivedEffort.enablesReasoning,
                reasoningEffort = archivedEffort,
                pendingImages = emptyList(),
                messages = existingState.messages +
                    UserMessageUi(
                        id = "user-$runId",
                        content = payload.userText,
                        images = archivedRun.userImagePreviews,
                    ) +
                    AgentMessageUi(
                        id = "assistant-$runId",
                        content = "",
                        isStreaming = true,
                        renderMarkdown = false,
                    ),
            )
        )
        archivedRun.events.forEach { event -> applyRunEvent(runId, event) }
        applyRunResult(runId, archivedRun.result)
        conversationUpdatedAt = conversationUpdatedAt + (conversationId to archivedRun.createdAt)
        return runId
    }

    fun updateThinkingEnabled(enabled: Boolean) {
        updateReasoningEffort(ReasoningEffort.fromLegacy(enabled))
    }

    fun updateReasoningEffort(effort: ReasoningEffort) {
        val normalized = currentReasoningCapabilities?.normalize(effort) ?: ReasoningEffort.OFF
        updateCurrentConversation(
            homeState.copy(
                thinkingEnabled = normalized.enablesReasoning,
                reasoningEffort = normalized,
            )
        )
        if (selectedConversationId != null) persistConversations()
    }

    fun updateSearchQuery(query: String) {
        conversationPaneState = conversationPaneState.copy(searchQuery = query)
    }

    fun selectConversation(conversationId: String) {
        if (homeState.messageEdit != null) cancelMessageEdit()
        val state = conversationsById[conversationId] ?: return
        fileAttachmentOwnerVersion += 1
        selectedConversationId = conversationId
        val normalized = currentReasoningCapabilities?.normalize(state.reasoningEffort)
            ?: ReasoningEffort.OFF
        val resolvedState = state.copy(
            thinkingEnabled = normalized.enablesReasoning,
            reasoningEffort = normalized,
            availableReasoningEfforts = currentReasoningCapabilities?.selectableEfforts.orEmpty(),
        )
        conversationsById = conversationsById + (conversationId to resolvedState)
        homeState = resolvedState
        conversationPaneState = conversationPaneState.copy(selectedConversationId = conversationId)
        persistConversations()
    }

    fun createConversation() {
        if (homeState.messageEdit != null) cancelMessageEdit()
        fileAttachmentOwnerVersion += 1
        selectedConversationId = null
        homeState = emptyChatState(defaultThinkingEnabled).withCurrentReasoningCapabilities()
        conversationPaneState = conversationPaneState.copy(
            selectedConversationId = null,
            searchQuery = "",
        )
        refreshConversationSummaries()
    }

    fun deleteConversation(conversationId: String) {
        val wasSelected = selectedConversationId == conversationId
        conversationsById = conversationsById - conversationId
        conversationTitles = conversationTitles - conversationId
        conversationUpdatedAt = conversationUpdatedAt - conversationId
        if (wasSelected) {
            fileAttachmentOwnerVersion += 1
            val nextId = conversationsById.keys.firstOrNull()
            if (nextId != null) {
                selectedConversationId = nextId
                homeState = conversationsById.getValue(nextId).withCurrentReasoningCapabilities()
                conversationsById = conversationsById + (nextId to homeState)
            } else {
                selectedConversationId = null
                homeState = emptyChatState(defaultThinkingEnabled).withCurrentReasoningCapabilities()
            }
        }
        conversationPaneState = conversationPaneState.copy(selectedConversationId = selectedConversationId)
        refreshConversationSummaries()
        persistConversations()
    }

    fun renameConversation(conversationId: String, title: String) {
        val trimmed = title.trim()
        if (trimmed.isBlank()) return
        conversationTitles = conversationTitles + (conversationId to trimmed)
        conversationUpdatedAt = conversationUpdatedAt + (conversationId to System.currentTimeMillis())
        refreshConversationSummaries()
        persistConversations()
    }

    fun sendCurrentMessage(submittedText: String? = null) {
        val prompt = (submittedText ?: homeState.input).trim()
        val pendingImages = homeState.pendingImages
        val pendingFileReferences = homeState.pendingFileReferences
        if (
            (prompt.isBlank() && pendingImages.isEmpty() && pendingFileReferences.isEmpty()) ||
            homeState.isStreaming
        ) {
            return
        }
        val fileReferences = pendingFileReferences.map { it.reference }
        if (
            !AgentFileReferencePolicy.canSend(
                references = fileReferences,
                terminalToolsEnabled = agentBooleanForUi(Prefs.Keys.AGENT_TERMINAL_TOOLS),
            )
        ) {
            Toast.makeText(
                appContext,
                "文件路径引用需要先开启终端与文件工具",
                Toast.LENGTH_SHORT,
            ).show()
            return
        }
        val runtimePrompt = AgentFileReferencePromptCodec.format(prompt, fileReferences)

        val edit = homeState.messageEdit
        if (edit == null && selectedConversationId?.isReadOnlyExternalArchiveConversation() == true) {
            moveCurrentDraftToNewConversation()
        }

        val editBoundary = edit?.let {
            AgentConversationRevisionReducer.boundary(homeState, it.targetMessageId)
        }
        if (edit != null && editBoundary == null) {
            cancelMessageEdit()
            return
        }

        val conversationId = selectedConversationId ?: newConversationId().also {
            selectedConversationId = it
        }
        val runId = "run-${UUID.randomUUID()}"
        val userMessage = UserMessageUi(
            id = editBoundary?.userMessage?.id ?: "user-$runId",
            content = runtimePrompt,
            images = pendingImages.map { it.dataUrl },
            isEdited = editBoundary != null,
        )
        val history = editBoundary?.historyPrefix ?: homeState.history
        val messages = if (editBoundary == null) {
            homeState.messages + userMessage
        } else {
            homeState.messages.take(editBoundary.userMessageIndex) + userMessage
        }
        val userHistoryMessage = AgentModelClient.buildUserHistoryMessage(
            text = runtimePrompt,
            images = pendingImages.toHistoryImages(),
        )

        val currentTitle = conversationTitles[conversationId]
        val oldAutoTitle = editBoundary
            ?.takeIf { it.userMessageIndex == 0 }
            ?.userMessage
            ?.content
            ?.defaultConversationTitleFromMessage()
        val nextAutoTitle = defaultConversationTitle(prompt, fileReferences)
        val title = if (
            editBoundary?.userMessageIndex == 0 &&
            (currentTitle == oldAutoTitle || currentTitle == "新对话")
        ) {
            nextAutoTitle
        } else {
            currentTitle?.takeUnless { it == "新对话" } ?: nextAutoTitle
        }

        conversationTitles = conversationTitles + (conversationId to title)
        conversationPaneState = conversationPaneState.copy(selectedConversationId = conversationId)
        launchConversationRun(
            conversationId = conversationId,
            runId = runId,
            prompt = runtimePrompt,
            images = pendingImages,
            history = history,
            userHistoryMessage = userHistoryMessage,
            messages = messages,
            state = homeState.copy(
                input = "",
                pendingImages = emptyList(),
                pendingFileReferences = emptyList(),
                messageEdit = null,
            ),
            reasoningEffort = homeState.reasoningEffort,
        )
    }

    fun beginMessageEdit(messageId: String) {
        if (homeState.isStreaming || homeState.messageEdit != null) return
        val boundary = AgentConversationRevisionReducer.boundary(homeState, messageId) ?: return
        val images = boundary.userMessage.images.mapIndexed { index, dataUrl ->
            PendingImageUi(
                id = "edit-${boundary.userMessage.id}-$index",
                uri = dataUrl,
                dataUrl = dataUrl,
                mimeType = dataUrl.imageMimeType(),
            )
        }
        val parsedPrompt = AgentFileReferencePromptCodec.parse(boundary.userMessage.content)
        val fileReferences = parsedPrompt.references.mapIndexed { index, reference ->
            PendingFileReferenceUi(
                id = "edit-${boundary.userMessage.id}-file-$index",
                reference = reference,
            )
        }
        updateCurrentConversation(
            homeState.copy(
                input = parsedPrompt.request,
                pendingImages = images,
                pendingFileReferences = fileReferences,
                messageEdit = MessageEditUiState(
                    targetMessageId = boundary.userMessage.id,
                    previousInput = homeState.input,
                    previousImages = homeState.pendingImages,
                    previousFileReferences = homeState.pendingFileReferences,
                    hasLaterTurns = boundary.laterTurnCount > 0,
                ),
            )
        )
        if (boundary.contextWasCompacted) showCompactedRevisionNotice()
    }

    fun cancelMessageEdit() {
        val edit = homeState.messageEdit ?: return
        updateCurrentConversation(
            homeState.copy(
                input = edit.previousInput,
                pendingImages = edit.previousImages,
                pendingFileReferences = edit.previousFileReferences,
                messageEdit = null,
            )
        )
    }

    fun messageRevisionImpact(messageId: String): MessageRevisionImpact? =
        AgentConversationRevisionReducer.boundary(homeState, messageId)?.let { boundary ->
            MessageRevisionImpact(laterTurnCount = boundary.laterTurnCount)
        }

    fun deleteMessageTurn(messageId: String) {
        if (homeState.isStreaming || homeState.messageEdit != null) return
        val conversationId = selectedConversationId ?: return
        val revised = AgentConversationRevisionReducer.deleteFromTurn(homeState, messageId) ?: return
        if (revised.messages.isEmpty()) {
            conversationsById = conversationsById - conversationId
            conversationTitles = conversationTitles - conversationId
            conversationUpdatedAt = conversationUpdatedAt - conversationId
            fileAttachmentOwnerVersion += 1
            selectedConversationId = null
            homeState = emptyChatState(defaultThinkingEnabled).withCurrentReasoningCapabilities()
            conversationPaneState = conversationPaneState.copy(selectedConversationId = null)
            refreshConversationSummaries()
            persistConversations()
            return
        }
        updateConversation(conversationId, revised)
        refreshConversationSummaries()
        persistConversations()
    }

    fun regenerateMessage(messageId: String) {
        if (homeState.isStreaming || homeState.messageEdit != null) return
        val conversationId = selectedConversationId ?: return
        val boundary = AgentConversationRevisionReducer.boundary(homeState, messageId) ?: return
        val images = boundary.userMessage.images.mapIndexed { index, dataUrl ->
            PendingImageUi(
                id = "regenerate-${boundary.userMessage.id}-$index",
                uri = dataUrl,
                dataUrl = dataUrl,
                mimeType = dataUrl.imageMimeType(),
            )
        }
        val runId = "run-${UUID.randomUUID()}"
        val userHistoryMessage = AgentModelClient.buildUserHistoryMessage(
            text = boundary.userMessage.content,
            images = images.toHistoryImages(),
        )
        if (boundary.contextWasCompacted) showCompactedRevisionNotice()
        launchConversationRun(
            conversationId = conversationId,
            runId = runId,
            prompt = boundary.userMessage.content,
            images = images,
            history = boundary.historyPrefix,
            userHistoryMessage = userHistoryMessage,
            messages = homeState.messages.take(boundary.userMessageIndex + 1),
            state = homeState,
            reasoningEffort = homeState.reasoningEffort,
        )
    }

    private fun launchConversationRun(
        conversationId: String,
        runId: String,
        prompt: String,
        images: List<PendingImageUi>,
        history: List<AgentModelClient.ConversationMessage>,
        userHistoryMessage: AgentModelClient.ConversationMessage,
        messages: List<AgentChatMessageUi>,
        state: AgentChatHomeUiState,
        reasoningEffort: ReasoningEffort,
    ) {
        runConversationIds[runId] = conversationId
        currentRunId = runId

        updateConversation(
            conversationId,
            state.copy(
                isStreaming = true,
                history = history + userHistoryMessage,
                messages = messages,
                messageEdit = null,
            )
        )
        refreshConversationSummaries()
        persistConversations()

        currentRunJob = scope.launch(Dispatchers.IO) {
            val permittedReasoningEffort = if (
                agentBooleanForUi(Prefs.Keys.AGENT_THINKING_ENABLED)
            ) {
                reasoningEffort
            } else {
                ReasoningEffort.OFF
            }
            val config = RuntimeConfigRepository.currentRuntimeConfig()?.copy(
                terminalTools = agentBooleanForUi(Prefs.Keys.AGENT_TERMINAL_TOOLS),
                browserTools = agentBooleanForUi(Prefs.Keys.AGENT_BROWSER_TOOLS),
                deviceDirectTools = agentBooleanForUi(Prefs.Keys.AGENT_DEVICE_DIRECT_TOOLS),
                deviceSensitiveReadTools =
                    agentBooleanForUi(Prefs.Keys.AGENT_DEVICE_SENSITIVE_READ_TOOLS),
                deviceSensitiveActionTools =
                    agentBooleanForUi(Prefs.Keys.AGENT_DEVICE_SENSITIVE_ACTION_TOOLS),
                thinkingEnabled = permittedReasoningEffort.enablesReasoning,
                reasoningEffort = permittedReasoningEffort,
            )
            contextWindowTokens = config?.contextWindow
            if (config == null) {
                withContext(Dispatchers.Main) {
                    applyRunResult(
                        runId,
                        AgentRuntimeWire.RunResult(
                            runId = runId,
                            ok = false,
                            content = "",
                            error = "请先配置模型提供商和模型",
                        )
                    )
                }
                return@launch
            }
            val modelImages = images.map { p ->
                AgentModelClient.ModelImage(
                    reference = p.uri,
                    mimeType = p.mimeType,
                    bytes = 0,
                    source = "user_attach",
                )
            }
            val result = AgentRuntimeClient(appContext, AndroidAgentLogger).run(
                request = AgentRuntimeWire.RunRequest(
                    runId = runId,
                    prompt = prompt,
                    config = config,
                    images = modelImages,
                    history = history,
                    handoff = AgentRuntimeWire.EntryHandoff(
                        id = runId,
                        source = HANDOFF_SOURCE,
                        payload = conversationId,
                    ),
                ),
                onEvent = { event -> enqueueRunEvent(runId, event) },
            )
            withContext(Dispatchers.Main) {
                applyRunResult(runId, result, acknowledgeRuntimeResult = true)
            }
        }
    }

    private fun List<PendingImageUi>.toHistoryImages(): List<AgentModelClient.ModelImage> =
        map { image ->
            AgentModelClient.ModelImage(
                reference = image.dataUrl,
                mimeType = image.mimeType,
                bytes = image.dataUrl.length,
                source = image.uri,
            )
        }

    private fun String.imageMimeType(): String =
        takeIf { startsWith("data:") }
            ?.substringAfter("data:")
            ?.substringBefore(';')
            ?.takeIf { it.startsWith("image/") }
            ?: "image/jpeg"

    private fun String.defaultConversationTitle(): String =
        lineSequence().firstOrNull().orEmpty().trim().take(MAX_TITLE_CHARS).ifBlank { "新对话" }

    private fun defaultConversationTitle(
        request: String,
        references: List<AgentFileReference>,
    ): String = AgentFileReferencePolicy
        .titleSource(request, references)
        .defaultConversationTitle()

    private fun String.defaultConversationTitleFromMessage(): String {
        val parsed = AgentFileReferencePromptCodec.parse(this)
        return defaultConversationTitle(parsed.request, parsed.references)
    }

    private fun showCompactedRevisionNotice() {
        Toast.makeText(
            appContext,
            "较早上下文已压缩，将从此消息重新开始",
            Toast.LENGTH_LONG,
        ).show()
    }

    fun attachImage(uri: String) {
        scope.launch(Dispatchers.IO) {
            try {
                val image = AgentImageCodec.fromReference(
                    context = appContext,
                    value = uri,
                    source = "user_attach",
                )
                if (image == null) {
                    withContext(Dispatchers.Main) {
                        Toast.makeText(
                            appContext,
                            "无法读取这张图片，请重试或改用其他图片",
                            Toast.LENGTH_SHORT,
                        ).show()
                    }
                    return@launch
                }
                val preview = AgentImageCodec.previewFromReference(appContext, image) ?: image
                val pending = PendingImageUi(
                    id = "img-${UUID.randomUUID()}",
                    // 后续发送使用首次读取后的稳定引用，不再依赖 ROM Photo Picker URI 的授权生命周期。
                    uri = image.reference,
                    dataUrl = preview.reference,
                    mimeType = image.mimeType,
                )
                withContext(Dispatchers.Main) {
                    updateCurrentConversation(homeState.copy(pendingImages = homeState.pendingImages + pending))
                }
            } finally {
                val selectedUri = Uri.parse(uri)
                if (selectedUri.scheme == ContentResolver.SCHEME_CONTENT) {
                    runCatching {
                        appContext.contentResolver.releasePersistableUriPermission(
                            selectedUri,
                            Intent.FLAG_GRANT_READ_URI_PERMISSION,
                        )
                    }
                }
            }
        }
    }

    fun removePendingImage(id: String) {
        updateCurrentConversation(homeState.copy(pendingImages = homeState.pendingImages.filterNot { it.id == id }))
    }

    fun attachFiles(uris: List<String>) {
        if (uris.isEmpty()) return
        resolveAndAttachFileReferences {
            val gateway = AgentFileReferenceGateway(appContext, AndroidAgentLogger)
            uris.map { uri ->
                gateway.resolveDocumentUri(
                    uri = Uri.parse(uri),
                    expectedKind = AgentFileReferenceKind.File,
                )
            }
        }
    }

    fun attachFolder(uri: String) {
        resolveAndAttachFileReferences {
            val gateway = AgentFileReferenceGateway(appContext, AndroidAgentLogger)
            listOf(
                gateway.resolveDocumentUri(
                    uri = Uri.parse(uri),
                    expectedKind = AgentFileReferenceKind.Directory,
                )
            )
        }
    }

    fun attachFilePath(path: String) {
        resolveAndAttachFileReferences {
            listOf(AgentFileReferenceGateway(AndroidAgentLogger).resolveAbsolutePath(path))
        }
    }

    fun removePendingFileReference(id: String) {
        updateCurrentConversation(
            homeState.copy(
                pendingFileReferences = homeState.pendingFileReferences.filterNot { it.id == id }
            )
        )
    }

    private fun resolveAndAttachFileReferences(
        resolver: () -> List<AgentFileReferenceGateway.Resolution>,
    ) {
        val ownerVersion = fileAttachmentOwnerVersion
        scope.launch(Dispatchers.IO) {
            val resolutions = resolver()
            val references = resolutions.mapNotNull { resolution ->
                (resolution as? AgentFileReferenceGateway.Resolution.Success)?.reference
            }
            val failures = resolutions.mapNotNull { resolution ->
                (resolution as? AgentFileReferenceGateway.Resolution.Failure)?.error
            }
            withContext(Dispatchers.Main) {
                if (ownerVersion != fileAttachmentOwnerVersion) {
                    Toast.makeText(appContext, "对话已切换，未添加所选路径", Toast.LENGTH_SHORT).show()
                    return@withContext
                }
                val existingPaths = homeState.pendingFileReferences
                    .mapTo(mutableSetOf()) { it.reference.absolutePath }
                val additions = references
                    .distinctBy { it.absolutePath }
                    .filter { existingPaths.add(it.absolutePath) }
                    .map { reference ->
                        PendingFileReferenceUi(
                            id = "file-${UUID.randomUUID()}",
                            reference = reference,
                        )
                    }
                if (additions.isNotEmpty()) {
                    updateCurrentConversation(
                        homeState.copy(
                            pendingFileReferences = homeState.pendingFileReferences + additions
                        )
                    )
                }
                val message = when {
                    failures.size == 1 && references.isEmpty() -> failures.single().userMessage
                    failures.isNotEmpty() -> "已添加 ${additions.size} 项，${failures.size} 项无法引用"
                    additions.isEmpty() -> "所选路径已经添加"
                    else -> null
                }
                if (message != null) {
                    Toast.makeText(appContext, message, Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    private val AgentFileReferenceGateway.Error.userMessage: String
        get() = when (this) {
            AgentFileReferenceGateway.Error.UnsupportedDocumentProvider ->
                "无法取得真实路径，请从“内部存储”选择或手动输入路径"
            AgentFileReferenceGateway.Error.InvalidPath -> "请输入有效的绝对路径"
            AgentFileReferenceGateway.Error.OutsideAllowedRoots ->
                "仅支持内部存储和 /data/local/tmp 下的路径"
            AgentFileReferenceGateway.Error.PathNotFound -> "路径不存在或已不可访问"
            AgentFileReferenceGateway.Error.UnsupportedFileType -> "仅支持普通文件和文件夹"
            AgentFileReferenceGateway.Error.TypeMismatch -> "选择的项目类型不匹配"
            AgentFileReferenceGateway.Error.RootUnavailable -> "Root 不可用，无法校验路径"
            AgentFileReferenceGateway.Error.ValidationTimedOut -> "路径校验超时，请重试"
        }

    fun stopCurrentRun() {
        val runId = currentRunId ?: return
        currentRunJob?.cancel()
        currentRunJob = null
        currentRunId = null
        flushPendingRunDelta(runId)
        scope.launch(Dispatchers.IO) {
            AgentRuntimeClient(appContext, AndroidAgentLogger).cancelRun(runId)
        }
        updateRunTrace(runId) { messages ->
            val finalizedThinking = runMessageProjector.finalizeThinking(runId, messages)
            val finalizedText = runMessageProjector.finalizeText(runId, finalizedThinking)
            runMessageProjector.failRunningTools("已停止", finalizedText)
        }
        replaceLatestAssistantMessage(runId, content = "已停止", isStreaming = false, renderMarkdown = false)
        setConversationStreaming(runId, false)
        runMessageProjector.clearRun(runId)
        runConversationIds.remove(runId)
        refreshConversationSummaries()
        persistConversations()
    }

    /** 顶部栏模型切换：切换到同一提供商下的其他模型。 */
    fun switchModel(modelId: String) {
        scope.launch(Dispatchers.IO) {
            runCatching {
                RuntimeConfigRepository.setSelectedModelId(modelId)
                refreshModelSwitcher()
            }
        }
    }

    fun refreshPermissionHealth() {
        permissionHealthState = buildPermissionHealthState(appContext)
    }

    fun refreshSkills() {
        scope.launch(Dispatchers.IO) {
            val entries = runCatching {
                SkillRuntime.createIndexService(appContext)
                    .listSkillsForManagement(forceRefresh = true)
            }.getOrElse {
                withContext(Dispatchers.Main) {
                    skillsState = skillsState.copy(
                        isLoading = false,
                        notice = skillsState.notice ?: newSkillNotice(
                            title = "无法读取技能",
                            message = "技能列表暂时不可用，请稍后重试。",
                            isError = true,
                        ),
                    )
                }
                return@launch
            }
            val items = entries.map { entry ->
                val capabilities = buildList {
                    if (entry.hasScripts) add("scripts")
                    if (entry.hasReferences) add("references")
                    if (entry.hasAssets) add("assets")
                    if (entry.hasEvals) add("evals")
                }
                SkillItemUi(
                    id = entry.id,
                    name = entry.name,
                    description = entry.description,
                    source = entry.source,
                    enabled = entry.enabled,
                    installed = entry.installed,
                    capabilities = capabilities,
                )
            }
            withContext(Dispatchers.Main) {
                skillsState = skillsState.copy(skills = items, isLoading = false)
            }
        }
    }

    fun toggleSkill(skillId: String, enabled: Boolean) {
        if (skillsState.isImporting || skillsState.busySkillId != null) return
        skillsState = skillsState.copy(busySkillId = skillId)
        scope.launch(Dispatchers.IO) {
            val succeeded = runCatching {
                SkillRuntime.createIndexService(appContext).setSkillEnabled(skillId, enabled)
            }.isSuccess
            withContext(Dispatchers.Main) {
                skillsState = skillsState.copy(
                    busySkillId = null,
                    notice = if (succeeded) {
                        skillsState.notice
                    } else {
                        newSkillNotice(
                            title = "无法更新技能",
                            message = "技能开关未发生变化，请稍后重试。",
                            isError = true,
                        )
                    },
                )
            }
            refreshSkills()
        }
    }

    fun deleteSkill(skillId: String) {
        if (skillsState.isImporting || skillsState.busySkillId != null) return
        val skill = skillsState.skills.firstOrNull { it.id == skillId }
            ?.takeIf { it.canDeleteUserSkill }
            ?: return
        val skillName = skill.name.safeSkillDisplayName()
        skillsState = skillsState.copy(busySkillId = skillId, notice = null)
        scope.launch(Dispatchers.IO) {
            val succeeded = runCatching {
                SkillRuntime.createIndexService(appContext).deleteSkill(skillId)
            }.getOrDefault(false)
            withContext(Dispatchers.Main) {
                skillsState = skillsState.copy(
                    busySkillId = null,
                    notice = if (succeeded) {
                        newSkillNotice(
                            title = "技能已删除",
                            message = "「$skillName」已从 Eta 删除。",
                            isError = false,
                        )
                    } else {
                        newSkillNotice(
                            title = "无法删除技能",
                            message = "删除未完成。Eta 会在刷新技能列表时尝试恢复，请确认状态后再重试。",
                            isError = true,
                        )
                    },
                )
            }
            refreshSkills()
        }
    }

    fun importSkillZip(uriValue: String) {
        if (skillsState.isImporting || skillsState.busySkillId != null) return
        val uri = runCatching { Uri.parse(uriValue) }.getOrNull()
            ?.takeIf { it.scheme == ContentResolver.SCHEME_CONTENT }
        if (uri == null) {
            skillsState = skillsState.copy(
                notice = newSkillNotice(
                    title = "无法读取技能包",
                    message = "请选择由系统文件选择器提供的 ZIP 文件。",
                    isError = true,
                ),
            )
            return
        }
        pendingSkillZipUri = uri
        pendingSkillZipSha256 = null
        launchSkillZipImport(
            uri = uri,
            replaceUserSkill = false,
            expectedReplacementId = null,
            expectedArchiveSha256 = null,
        )
    }

    fun confirmSkillZipReplacement() {
        if (skillsState.isImporting || skillsState.busySkillId != null) return
        val uri = pendingSkillZipUri
        if (uri == null) {
            pendingSkillZipSha256 = null
            skillsState = skillsState.copy(
                replacement = null,
                notice = newSkillNotice(
                    title = "无法继续安装",
                    message = "技能包已不可用，请重新选择 ZIP 文件。",
                    isError = true,
                ),
            )
            return
        }
        val replacementId = skillsState.replacement?.id
        val archiveSha256 = pendingSkillZipSha256
        if (replacementId == null || archiveSha256 == null) {
            pendingSkillZipUri = null
            pendingSkillZipSha256 = null
            skillsState = skillsState.copy(
                replacement = null,
                notice = newSkillNotice(
                    title = "无法继续安装",
                    message = "替换确认已失效，请重新选择 ZIP 文件。",
                    isError = true,
                ),
            )
            return
        }
        launchSkillZipImport(
            uri = uri,
            replaceUserSkill = true,
            expectedReplacementId = replacementId,
            expectedArchiveSha256 = archiveSha256,
        )
    }

    fun cancelSkillZipReplacement() {
        if (skillsState.isImporting) return
        pendingSkillZipUri = null
        pendingSkillZipSha256 = null
        skillsState = skillsState.copy(replacement = null)
    }

    private fun launchSkillZipImport(
        uri: Uri,
        replaceUserSkill: Boolean,
        expectedReplacementId: String?,
        expectedArchiveSha256: String?,
    ) {
        skillsState = skillsState.copy(
            isImporting = true,
            replacement = null,
            notice = null,
        )
        scope.launch(Dispatchers.IO) {
            val outcome = runCatching {
                skillZipImportGateway.installLocalZip(
                    openStream = {
                        appContext.contentResolver.openInputStream(uri)
                            ?: error("无法打开所选内容")
                    },
                    replaceUserSkill = replaceUserSkill,
                    expectedReplacementId = expectedReplacementId,
                    expectedArchiveSha256 = expectedArchiveSha256,
                )
            }.getOrElse {
                SkillZipImportOutcome.Failure(SkillZipImportOutcome.FailureCode.READ_FAILED)
            }
            withContext(Dispatchers.Main) {
                applySkillZipImportOutcome(outcome)
            }
        }
    }

    private fun enqueueRunEvent(runId: String, event: AgentEvent) {
        if (event is AgentEvent.AssistantBlockDelta) {
            if (event.kind == AgentEvent.AssistantBlockKind.TOOL_CALL || event.delta.isEmpty()) return

            runEventCoalescer.append(runId, event)?.let { ready ->
                applyRunEvent(runId, ready)
            }
            scheduleRunDeltaFlush(runId)
            return
        }

        flushPendingRunDelta(runId)
        applyRunEvent(runId, event)
    }

    private fun scheduleRunDeltaFlush(runId: String) {
        if (runEventFlushJobs[runId]?.isActive == true) return
        runEventFlushJobs[runId] = scope.launch {
            delay(STREAM_UI_UPDATE_INTERVAL_MS)
            runEventFlushJobs.remove(runId)
            flushPendingRunDelta(runId)
        }
    }

    private fun flushPendingRunDelta(runId: String) {
        runEventFlushJobs.remove(runId)?.cancel()
        runEventCoalescer.flush(runId)?.let { event ->
            applyRunEvent(runId, event)
        }
    }

    private fun applySkillZipImportOutcome(outcome: SkillZipImportOutcome) {
        when (outcome) {
            is SkillZipImportOutcome.Success -> {
                val installed = outcome.skills.singleOrNull()
                pendingSkillZipUri = null
                pendingSkillZipSha256 = null
                skillsState = skillsState.copy(
                    isImporting = false,
                    replacement = null,
                    notice = if (installed == null) {
                        skillZipFailureNotice(SkillZipImportOutcome.FailureCode.MULTIPLE_SKILLS)
                    } else {
                        newSkillNotice(
                            title = "技能已安装",
                            message = "「${installed.name.safeSkillDisplayName()}」已启用，将从下一轮对话开始可用。",
                            isError = false,
                        )
                    },
                )
                if (installed != null) refreshSkills()
            }

            is SkillZipImportOutcome.Conflict -> {
                val conflict = outcome.skills.singleOrNull()
                val archiveSha256 = outcome.archiveSha256
                if (
                    conflict != null &&
                    conflict.source == "user" &&
                    conflict.replaceAllowed &&
                    archiveSha256 != null
                ) {
                    val existingName = skillsState.skills
                        .firstOrNull { it.id == conflict.id && it.installed }
                        ?.name
                        .orEmpty()
                        .ifBlank { conflict.name }
                    pendingSkillZipSha256 = archiveSha256
                    skillsState = skillsState.copy(
                        isImporting = false,
                        replacement = SkillReplacementUi(
                            id = conflict.id,
                            name = existingName.safeSkillDisplayName(),
                        ),
                        notice = null,
                    )
                } else {
                    pendingSkillZipUri = null
                    pendingSkillZipSha256 = null
                    skillsState = skillsState.copy(
                        isImporting = false,
                        replacement = null,
                        notice = skillZipFailureNotice(
                            if (conflict?.source == "builtin") {
                                SkillZipImportOutcome.FailureCode.BUILTIN_CONFLICT
                            } else if (conflict != null && conflict.replaceAllowed) {
                                SkillZipImportOutcome.FailureCode.PACKAGE_CHANGED
                            } else if (conflict != null && !conflict.replaceAllowed) {
                                SkillZipImportOutcome.FailureCode.TARGET_NOT_REPLACEABLE
                            } else {
                                SkillZipImportOutcome.FailureCode.MULTIPLE_SKILLS
                            },
                        ),
                    )
                }
            }

            is SkillZipImportOutcome.Failure -> {
                pendingSkillZipUri = null
                pendingSkillZipSha256 = null
                skillsState = skillsState.copy(
                    isImporting = false,
                    replacement = null,
                    notice = skillZipFailureNotice(outcome.code),
                )
                if (outcome.code == SkillZipImportOutcome.FailureCode.RECOVERY_REQUIRED) {
                    refreshSkills()
                }
            }
        }
    }

    private fun skillZipFailureNotice(code: SkillZipImportOutcome.FailureCode): SkillNoticeUi {
        val message = when (code) {
            SkillZipImportOutcome.FailureCode.INVALID_ARCHIVE -> "所选文件不是有效的 ZIP 技能包。"
            SkillZipImportOutcome.FailureCode.ARCHIVE_LIMIT_EXCEEDED -> "技能包超过安全大小或文件数量限制。"
            SkillZipImportOutcome.FailureCode.UNSAFE_ARCHIVE -> "技能包包含不安全的文件路径，未进行安装。"
            SkillZipImportOutcome.FailureCode.NO_SKILL -> "ZIP 中没有找到 SKILL.md。"
            SkillZipImportOutcome.FailureCode.MULTIPLE_SKILLS -> "本地 ZIP 必须只包含一个技能。"
            SkillZipImportOutcome.FailureCode.INVALID_SKILL -> "SKILL.md 缺少必要信息或格式无效。"
            SkillZipImportOutcome.FailureCode.PACKAGE_CHANGED -> "ZIP 内容已变化，请重新选择并确认要替换的技能。"
            SkillZipImportOutcome.FailureCode.BUILTIN_CONFLICT -> "同名内置技能受保护，不能由 ZIP 替换。"
            SkillZipImportOutcome.FailureCode.TARGET_NOT_REPLACEABLE ->
                "同名目标不是可安全替换的用户技能，现有文件未被覆盖。"
            SkillZipImportOutcome.FailureCode.READ_FAILED -> "无法读取所选文件，请重新选择。"
            SkillZipImportOutcome.FailureCode.STORAGE_FAILED -> "无法保存技能，原有技能已自动恢复。"
            SkillZipImportOutcome.FailureCode.RECOVERY_REQUIRED ->
                "安装失败且自动恢复未完整完成。Eta 已在应用私有目录保留恢复备份，请先检查技能列表并停止继续安装。"
        }
        return newSkillNotice(
            title = "无法安装技能",
            message = message,
            isError = true,
        )
    }

    fun reinstallBuiltin(skillId: String) {
        if (skillsState.isImporting || skillsState.busySkillId != null) return
        skillsState = skillsState.copy(busySkillId = skillId)
        scope.launch(Dispatchers.IO) {
            val succeeded = runCatching {
                SkillRuntime.createIndexService(appContext).installBuiltinSkill(skillId)
            }.isSuccess
            withContext(Dispatchers.Main) {
                skillsState = skillsState.copy(
                    busySkillId = null,
                    notice = if (succeeded) {
                        skillsState.notice
                    } else {
                        newSkillNotice(
                            title = "无法恢复技能",
                            message = "内置技能未发生变化，请稍后重试。",
                            isError = true,
                        )
                    },
                )
            }
            if (succeeded) refreshSkills()
        }
    }

    fun dismissSkillNotice() {
        skillsState = skillsState.copy(notice = null)
    }

    private fun newSkillNotice(
        title: String,
        message: String,
        isError: Boolean,
    ): SkillNoticeUi = SkillNoticeUi(
        id = ++skillNoticeSequence,
        title = title,
        message = message,
        isError = isError,
    )

    private fun String.safeSkillDisplayName(): String =
        lineSequence().firstOrNull().orEmpty().trim().ifBlank { "未命名技能" }.take(80)

    private fun applyRunEvent(runId: String, event: AgentEvent) {
        when (event) {
            is AgentEvent.AssistantBlockStart -> {
                if (event.kind == AgentEvent.AssistantBlockKind.TOOL_CALL) {
                    updateRunTrace(runId) { messages ->
                        runMessageProjector.finalizeTextRound(runId, event.round, messages)
                    }
                }
            }

            is AgentEvent.AssistantBlockDelta -> {
                updateMessages(runId, updateTimestamp = false) { messages ->
                    when (event.kind) {
                        AgentEvent.AssistantBlockKind.TEXT ->
                            runMessageProjector.appendTextDelta(runId, event.round, event.delta, messages)

                        AgentEvent.AssistantBlockKind.THINKING ->
                            runMessageProjector.appendReasoningDelta(runId, event.round, event.delta, messages)

                        AgentEvent.AssistantBlockKind.TOOL_CALL -> messages
                    }
                }
            }

            is AgentEvent.AssistantBlockEnd -> {
                updateRunTrace(runId) { messages ->
                    when (event.kind) {
                        AgentEvent.AssistantBlockKind.TEXT ->
                            runMessageProjector.finalizeTextRound(runId, event.round, messages)

                        AgentEvent.AssistantBlockKind.THINKING ->
                            runMessageProjector.finalizeThinkingRound(runId, event.round, messages)

                        AgentEvent.AssistantBlockKind.TOOL_CALL -> messages
                    }
                }
            }

            is AgentEvent.UsageReceived -> {
                updateAssistantUsage(runId, event.round, event.usage.toUi())
            }

            is AgentEvent.ContextTrimmed -> {
                contextJustTrimmed = true
                scope.launch(Dispatchers.Main) {
                    delay(TRIM_ANIMATION_MILLIS)
                    contextJustTrimmed = false
                }
            }

            is AgentEvent.UserSupplementReceived -> {
                insertSupplementMessage(runId, event.index, event.text)
            }

            is AgentEvent.ToolStarted -> {
                updateRunTrace(runId) { messages ->
                    val finalizedThinking = runMessageProjector.finalizeThinking(runId, messages)
                    val finalizedText = runMessageProjector.finalizeTextRound(runId, event.round, finalizedThinking)
                    runMessageProjector.startTool(runId, event, finalizedText)
                }
            }

            is AgentEvent.ToolFinished -> {
                updateRunTrace(runId) { messages ->
                    runMessageProjector.finishTool(runId, event, messages)
                }
            }

            is AgentEvent.HostedToolStarted -> {
                updateRunTrace(runId) { messages ->
                    val finalizedThinking = runMessageProjector.finalizeThinking(runId, messages)
                    val finalizedText = runMessageProjector.finalizeTextRound(runId, event.round, finalizedThinking)
                    runMessageProjector.startHostedTool(runId, event, finalizedText)
                }
            }

            is AgentEvent.HostedToolFinished -> {
                updateRunTrace(runId) { messages ->
                    runMessageProjector.finishHostedTool(runId, event, messages)
                }
            }

            is AgentEvent.RunFailed -> {
                updateRunTrace(runId) { messages ->
                    val finalizedThinking = runMessageProjector.finalizeThinking(runId, messages)
                    val finalizedText = runMessageProjector.finalizeText(runId, finalizedThinking)
                    runMessageProjector.failRunningTools(event.reason, finalizedText)
                }
            }

            is AgentEvent.AssistantReceived -> {
                if (event.reasoningContent.isNotBlank()) {
                    updateRunTrace(runId) { messages ->
                        runMessageProjector.ensureCompletedThinking(
                            runId = runId,
                            round = event.round,
                            content = event.reasoningContent,
                            messages = messages,
                        )
                    }
                }
            }

            is AgentEvent.RunFinished -> {
                updateRunTrace(runId) { messages ->
                    val finalizedThinking = runMessageProjector.finalizeThinking(runId, messages)
                    runMessageProjector.finalizeText(runId, finalizedThinking)
                }
            }

            is AgentEvent.RunStarted,
            is AgentEvent.ProviderRequestStarted,
            is AgentEvent.ProviderResponseStarted,
            is AgentEvent.ToolImagesAttached,
            is AgentEvent.RoundStarted,
            -> Unit
        }
    }

    private fun applyRunResult(
        runId: String,
        result: AgentRuntimeWire.RunResult,
        acknowledgeRuntimeResult: Boolean = false,
    ) {
        flushPendingRunDelta(runId)
        if (runId == currentRunId) {
            currentRunId = null
            currentRunJob = null
        }
        val content = if (result.ok) {
            result.content.ifBlank { "已完成。" }
        } else {
            result.error ?: "Agent Runtime 调用失败"
        }

        applyConversationHistoryResult(runId, result.transcript)
        replaceLatestAssistantMessage(runId, content, isStreaming = false, renderMarkdown = result.ok)
        setConversationStreaming(runId, false)
        runMessageProjector.clearRun(runId)
        runConversationIds.remove(runId)
        refreshConversationSummaries()
        persistConversations(
            onSaved = if (acknowledgeRuntimeResult) {
                {
                    AgentRuntimeClient(appContext, AndroidAgentLogger).ackResult(runId)
                }
            } else {
                null
            }
        )
    }

    private fun updateRunTrace(
        runId: String,
        transform: (List<AgentChatMessageUi>) -> List<AgentChatMessageUi>,
    ) {
        updateMessages(runId, transform = transform)
        refreshConversationSummaries()
    }

    private fun updateAssistantUsage(runId: String, round: Int, usage: TokenUsageUi) {
        if (usage.isEmpty) return
        contextUsageTokens = usage.inputTokens ?: usage.contextTokens
        // 只补充 token 用量。不能触碰 isStreaming：Usage 事件紧跟在文本块结束之后，
        // 若把 isStreaming 改回 true，流式渲染会在流式/静态两种视图间反复切换，整段重渲染。
        updateMessages(runId) { messages ->
            val assistantId = assistantMessageId(runId, round)
            messages.map { message ->
                if (message is AgentMessageUi && message.id == assistantId) {
                    message.copy(usage = usage)
                } else {
                    message
                }
            }
        }
    }

    private fun insertSupplementMessage(runId: String, index: Int, text: String) {
        updateMessages(runId) { messages ->
            AgentPendingResultRecovery.mergeSupplements(
                runId = runId,
                supplements = listOf(
                    AgentUiHandoffPayload.Supplement(
                        index = index,
                        text = text,
                        createdAt = System.currentTimeMillis(),
                    )
                ),
                messages = messages,
            )
        }
        refreshConversationSummaries()
        persistConversations()
    }

    private fun replaceAssistantMessage(
        runId: String,
        round: Int,
        content: String,
        isStreaming: Boolean,
        renderMarkdown: Boolean? = null,
        usage: TokenUsageUi? = null,
    ) {
        updateMessages(runId) { messages ->
            val assistantId = assistantMessageId(runId, round)
            var replaced = false
            val updated = messages.map { message ->
                if (message is AgentMessageUi && message.id == assistantId) {
                    replaced = true
                    message.copy(
                        content = content,
                        isStreaming = isStreaming,
                        renderMarkdown = renderMarkdown ?: message.renderMarkdown,
                        usage = usage ?: message.usage,
                    )
                } else {
                    message
                }
            }
            if (replaced) {
                updated
            } else {
                updated + AgentMessageUi(
                    id = assistantId,
                    content = content,
                    isStreaming = isStreaming,
                    renderMarkdown = renderMarkdown ?: false,
                    usage = usage,
                )
            }
        }
    }

    private fun replaceLatestAssistantMessage(
        runId: String,
        content: String,
        isStreaming: Boolean,
        renderMarkdown: Boolean? = null,
        usage: TokenUsageUi? = null,
    ) {
        replaceAssistantMessage(
            runId = runId,
            round = latestAssistantRound(runId) ?: 1,
            content = content,
            isStreaming = isStreaming,
            renderMarkdown = renderMarkdown,
            usage = usage,
        )
    }

    private fun latestAssistantRound(runId: String): Int? =
        conversationStateForRun(runId).messages
            .filterIsInstance<AgentMessageUi>()
            .mapNotNull { assistantRound(runId, it.id) }
            .maxOrNull()

    private fun assistantMessageId(runId: String, round: Int): String =
        "${assistantMessagePrefix(runId)}$round"

    private fun assistantMessagePrefix(runId: String): String =
        "assistant-$runId-"

    private fun assistantRound(runId: String, messageId: String): Int? =
        messageId.removePrefix(assistantMessagePrefix(runId))
            .takeIf { it != messageId }
            ?.toIntOrNull()

    private fun updateMessages(
        runId: String,
        updateTimestamp: Boolean = true,
        transform: (List<AgentChatMessageUi>) -> List<AgentChatMessageUi>,
    ) {
        val conversationId = conversationIdForRun(runId) ?: return
        val state = conversationsById[conversationId] ?: return
        updateConversation(
            conversationId = conversationId,
            state = state.copy(messages = transform(state.messages)),
            updateTimestamp = updateTimestamp,
        )
    }

    private fun applyConversationHistoryResult(
        runId: String,
        additions: List<AgentModelClient.ConversationMessage>,
    ) {
        val conversationId = conversationIdForRun(runId) ?: return
        val state = conversationsById[conversationId] ?: return
        val outcome = AgentRuntimeHistoryReducer.apply(state, runId, additions)
        if (!outcome.alreadyApplied) updateConversation(conversationId, outcome.state)
    }

    private fun updateCurrentConversation(state: AgentChatHomeUiState) {
        val conversationId = selectedConversationId
        if (conversationId == null) {
            homeState = state
        } else {
            updateConversation(conversationId, state)
        }
    }

    private fun moveCurrentDraftToNewConversation() {
        val draft = homeState
        selectedConversationId = null
        homeState = emptyChatState(defaultThinkingEnabled).copy(
            input = draft.input,
            thinkingEnabled = draft.reasoningEffort.enablesReasoning,
            reasoningEffort = draft.reasoningEffort,
            availableReasoningEfforts = currentReasoningCapabilities?.selectableEfforts.orEmpty(),
            pendingImages = draft.pendingImages,
            pendingFileReferences = draft.pendingFileReferences,
        )
        conversationPaneState = conversationPaneState.copy(selectedConversationId = null)
    }

    private fun updateConversation(
        conversationId: String,
        state: AgentChatHomeUiState,
        updateTimestamp: Boolean = true,
    ) {
        conversationsById = conversationsById + (conversationId to state)
        if (updateTimestamp) {
            conversationUpdatedAt = conversationUpdatedAt + (conversationId to System.currentTimeMillis())
        }
        if (conversationId == selectedConversationId) {
            homeState = state
        }
    }

    private fun setConversationStreaming(runId: String, isStreaming: Boolean) {
        val conversationId = conversationIdForRun(runId) ?: return
        val state = conversationsById[conversationId] ?: return
        updateConversation(conversationId, state.copy(isStreaming = isStreaming))
    }

    private fun conversationIdForRun(runId: String): String? = runConversationIds[runId]

    private fun conversationStateForRun(runId: String): AgentChatHomeUiState {
        val conversationId = conversationIdForRun(runId) ?: return emptyChatState(defaultThinkingEnabled)
        return conversationsById[conversationId] ?: emptyChatState(defaultThinkingEnabled)
    }

    private fun refreshConversationSummaries() {
        val summaries = conversationsById.entries
            .sortedByDescending { (id, _) ->
                conversationUpdatedAt[id] ?: 0L
            }
            .map { (id, state) ->
                val lastMessage = state.messages.lastOrNull()
                ConversationSummaryUi(
                    id = id,
                    title = conversationTitles[id] ?: "新对话",
                    preview = when (lastMessage) {
                        is UserMessageUi -> AgentFileReferencePromptCodec
                            .parse(lastMessage.content)
                            .let { parsed ->
                                AgentFileReferencePolicy.titleSource(
                                    request = parsed.request,
                                    references = parsed.references,
                                )
                            }
                        is AgentMessageUi -> lastMessage.content.ifBlank { "Agent 正在思考" }
                        is ThinkingMessageUi -> "Agent 正在思考"
                        is ToolActivityMessageUi -> "调用工具：${lastMessage.toolName}"
                        else -> "直接输入问题，必要时 Agent 会操作手机"
                    }.take(MAX_PREVIEW_CHARS),
                    timeLabel = if (state.isStreaming) {
                        "现在"
                    } else {
                        conversationUpdatedAt[id]?.let(ConversationTimeLabels::label) ?: "最近"
                    },
                    mode = ConversationModeUi.Chat,
                    isActiveRun = state.isStreaming,
                )
            }
        val query = conversationPaneState.searchQuery.trim()
        conversationPaneState = conversationPaneState.copy(
            selectedConversationId = selectedConversationId,
            conversations = if (query.isBlank()) {
                summaries
            } else {
                summaries.filter {
                    it.title.contains(query, ignoreCase = true) ||
                        it.preview.contains(query, ignoreCase = true)
                }
            },
        )
    }

    private fun persistConversations(onSaved: (() -> Unit)? = null) {
        val selected = selectedConversationId
        val conversations = conversationsById
        val titles = conversationTitles
        val timestamps = conversationUpdatedAt
        synchronized(persistenceLock) {
            val previous = persistenceJob
            persistenceJob = scope.launch(Dispatchers.IO) {
                try {
                    previous?.join()
                    AgentConversationStore.save(
                        context = appContext,
                        selectedConversationId = selected,
                        conversationsById = conversations,
                        titles = titles,
                        updatedAt = timestamps,
                    )
                    onSaved?.invoke()
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (throwable: Throwable) {
                    AndroidAgentLogger.error(
                        "Agent conversation persistence failed: type=${throwable.safeLogType()}"
                    )
                }
            }
        }
    }

    private companion object {
        const val HANDOFF_SOURCE = "agent_ui"
        const val MAX_TITLE_CHARS = 24
        const val MAX_PREVIEW_CHARS = 48
        // 数据状态以较粗粒度发布，文字显现由独立的帧时钟连续推进。
        // 这与 Kimi 将流式数据和视觉动画分层的做法一致。
        const val STREAM_UI_UPDATE_INTERVAL_MS = 80L

        fun emptyChatState(thinkingEnabled: Boolean): AgentChatHomeUiState =
            AgentChatHomeUiState(
                messages = emptyList(),
                history = emptyList(),
                input = "",
                isStreaming = false,
                thinkingEnabled = thinkingEnabled,
            )

        fun newConversationId(): String = "conv-${UUID.randomUUID()}"
    }
}

internal data class MessageRevisionImpact(
    val laterTurnCount: Int,
)

private const val EXTERNAL_ARCHIVE_CONVERSATION_PREFIX = "archive-"

/** 上下文裁剪后圆环转圈动画持续时间。 */
private const val TRIM_ANIMATION_MILLIS = 2_500L
private fun String.isReadOnlyExternalArchiveConversation(): Boolean =
    startsWith(EXTERNAL_ARCHIVE_CONVERSATION_PREFIX)

private fun archiveConversationId(source: String, conversationKey: String): String {
    val prefix = if (source == AgentRuntimeWire.ETA_VOICE_HANDOFF_SOURCE) {
        ASSISTANT_CONVERSATION_PREFIX
    } else {
        EXTERNAL_ARCHIVE_CONVERSATION_PREFIX
    }
    return prefix + stableArchiveId("$source:$conversationKey")
}

private const val ASSISTANT_CONVERSATION_PREFIX = "assistant-"

private fun stableArchiveId(value: String): String =
    java.security.MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray(Charsets.UTF_8))
        .take(12)
        .joinToString(separator = "") { byte -> "%02x".format(byte) }

private fun buildToolsState(): AgentToolsUiState =
    AgentToolsUiState(
        groups = listOf(
            ToolGroupUi(
                id = "screen",
                title = "屏幕与控件",
                tools = listOf(
                    ToolItemUi("observe_screen", "观察屏幕", "读取当前节点，必要时附原图"),
                    ToolItemUi("tap_element", "点击元素", "按最近一次观察到的节点点击"),
                    ToolItemUi("tap_area", "点击区域", "按坐标区域点击"),
                    ToolItemUi("long_press", "长按", "长按坐标或元素"),
                    ToolItemUi("swipe", "滑动", "执行上下左右滑动手势"),
                    ToolItemUi("scroll", "滚动", "滚动页面或指定节点"),
                ),
            ),
            ToolGroupUi(
                id = "text",
                title = "文本与剪贴板",
                tools = listOf(
                    ToolItemUi("input_text", "输入文字", "向当前焦点追加或粘贴文本"),
                    ToolItemUi("replace_text", "替换文本", "替换焦点或节点中的文本"),
                    ToolItemUi("clear_text", "清空文本", "清空焦点或节点文本"),
                    ToolItemUi("paste_text", "粘贴文本", "用剪贴板可靠输入长文本"),
                    ToolItemUi("wait_for_text", "等待文本", "等待指定文本出现在屏幕上"),
                ),
            ),
            ToolGroupUi(
                id = "web",
                title = "网页浏览",
                tools = listOf(
                    ToolItemUi("browser_use", "Agent 浏览器", "离屏打开网页，并保持可接管的浏览会话"),
                    ToolItemUi("browser_read", "阅读网页", "提取渲染后的正文、列表与链接"),
                    ToolItemUi("browser_interact", "网页交互", "查找、点击并输入页面元素"),
                    ToolItemUi("browser_screenshot", "页面截图", "把当前网页视口交给视觉模型"),
                ),
            ),
            ToolGroupUi(
                id = "app",
                title = "应用与系统",
                tools = listOf(
                    ToolItemUi("search_apps", "搜索应用", "按名称或包名查询已安装应用"),
                    ToolItemUi("get_current_context", "时间与位置", "读取系统时间与最近位置"),
                    ToolItemUi("launch_app", "打开 App", "启动指定包名或应用名"),
                    ToolItemUi("open_uri", "用应用打开", "把链接或 deep link 显式交给外部应用"),
                    ToolItemUi("press_key", "按键", "返回、主页、最近任务等系统按键"),
                    ToolItemUi("open_system_panel", "系统面板", "打开通知栏、快捷设置等面板"),
                ),
            ),
            ToolGroupUi(
                id = "device_direct",
                title = "设备直达",
                tools = listOf(
                    ToolItemUi("set_alarm", "设置闹钟", "直接创建系统闹钟，失败时打开时钟确认"),
                    ToolItemUi("set_timer", "设置计时器", "直接创建最长 24 小时的系统计时器"),
                    ToolItemUi("device_status", "设备状态", "读取电量、内存、存储与系统版本"),
                    ToolItemUi("network_info", "网络状态", "读取联网方式和当前 Wi‑Fi 状态"),
                    ToolItemUi("media_control", "媒体控制", "播放、暂停、切歌，不操作界面"),
                    ToolItemUi("set_volume", "设置音量", "按媒体、闹钟、铃声等通道设置"),
                    ToolItemUi("top_memory_apps", "内存排行", "查看当前占用最高的进程"),
                    ToolItemUi("top_storage_apps", "存储排行", "查看应用、数据与缓存占用"),
                ),
            ),
            ToolGroupUi(
                id = "device_sensitive",
                title = "敏感设备能力",
                tools = listOf(
                    ToolItemUi("read_sms_code", "读取验证码", "只提取最近短信中的验证码"),
                    ToolItemUi("recent_notifications", "读取通知", "读取当前通知标题与正文"),
                    ToolItemUi("search_notification_history", "通知历史", "检索授权后本机保存的最近 7 天通知"),
                    ToolItemUi("recent_app_activity", "最近应用", "查看最近打开过的应用和时间"),
                    ToolItemUi("app_usage_summary", "应用使用统计", "按前台时长汇总近期应用使用"),
                    ToolItemUi("get_current_location", "当前位置", "读取系统已有的最近位置"),
                    ToolItemUi("get_device_environment", "设备环境", "读取锁屏、勿扰、音频输出和外接屏状态"),
                    ToolItemUi("list_alarms", "闹钟计划", "读取时钟中已创建的闹钟"),
                    ToolItemUi("list_active_timers", "活动计时器", "读取正在运行或暂停的计时器"),
                    ToolItemUi("search_clipboard_history", "剪贴板历史", "检索系统输入法保存的剪贴板内容"),
                    ToolItemUi("get_health_summary", "健康摘要", "汇总步数、睡眠、运动和身体指标"),
                    ToolItemUi("wifi_credentials", "Wi‑Fi 密码", "读取手机保存的网络凭据"),
                    ToolItemUi("get_setting", "读取系统设置", "读取指定 Settings 键"),
                    ToolItemUi("set_setting", "修改系统设置", "修改非安全关键 Settings 键"),
                    ToolItemUi("set_device_state", "网络开关", "直接控制 Wi‑Fi 或蓝牙"),
                    ToolItemUi("app_state_control", "应用状态", "停止、冻结或解冻应用"),
                    ToolItemUi("get_logcat", "系统日志", "有界读取并过滤最近日志"),
                ),
            ),
            ToolGroupUi(
                id = "personal_data",
                title = "个人数据直达",
                tools = listOf(
                    ToolItemUi("search_media", "相册图片", "按文件名或相册路径检索图片"),
                    ToolItemUi("search_audio", "音频文件", "按标题、文件名或作者检索音频"),
                    ToolItemUi("search_recordings", "系统录音", "从系统媒体库检索录音文件"),
                    ToolItemUi("search_files", "共享文件", "检索文档和共享存储中的文件"),
                    ToolItemUi("search_calendar_events", "日历事件", "按标题、地点或说明检索日程"),
                    ToolItemUi("search_contacts", "通讯录", "检索联系人姓名与打开地址"),
                    ToolItemUi("search_call_history", "通话记录", "按号码或联系人名称检索通话"),
                    ToolItemUi("search_messages", "短信", "按发送方或正文关键词检索短信"),
                    ToolItemUi("search_downloads", "下载记录", "检索系统下载任务和文件"),
                    ToolItemUi("search_coloros_notes", "ColorOS 便签", "检索便签、待办及正文内容"),
                    ToolItemUi("search_coloros_recordings", "ColorOS 录音", "检索普通录音与通话录音"),
                    ToolItemUi("search_recording_summaries", "录音摘要", "检索录音关联的转写摘要和便签"),
                    ToolItemUi("search_coloros_memories", "ColorOS 系统记忆", "检索已收集的信息及其结构化关联内容"),
                    ToolItemUi("search_saved_places", "保存地点", "检索系统记忆中的地点信息"),
                    ToolItemUi("search_personal_orders", "个人订单", "检索外卖、购物、快递、票券和出行订单"),
                    ToolItemUi("search_qq_chat_images", "QQ 聊天图片", "检索 QQ 聊天图片缓存中的最近图片"),
                    ToolItemUi("search_wechat_chat_images", "微信聊天图片", "检索微信聊天图片缓存中的最近图片"),
                ),
            ),
            ToolGroupUi(
                id = "file_vision",
                title = "文件视觉",
                tools = listOf(
                    ToolItemUi("read_image", "读取图片", "读取已知路径的图片并交给视觉模型解读"),
                ),
            ),
            ToolGroupUi(
                id = "memory",
                title = "记忆",
                tools = listOf(
                    ToolItemUi("memory_get", "读取记忆", "分页读取或检索 MEMORY.md 中的长期记忆"),
                    ToolItemUi("memory_write", "整理记忆", "局部更新、追加或清空长期记忆"),
                ),
            ),
            ToolGroupUi(
                id = "terminal",
                title = "终端与文件",
                tools = listOf(
                    ToolItemUi("terminal", "会话终端", "user/root shell，会话式执行与异步读取"),
                    ToolItemUi("run_command", "执行命令", "直接执行单条 shell 命令"),
                    ToolItemUi("read_file", "读取文件", "读取手机文件内容"),
                    ToolItemUi("write_file", "写入文件", "写入或覆盖手机文件"),
                    ToolItemUi("list_directory", "列目录", "列出目录内容"),
                ),
            ),
        )
    )

private fun buildPermissionHealthState(context: Context): PermissionHealthUiState {
    val backgroundRunningEnabled = isIgnoringBatteryOptimizations(context)
    val overlayEnabled = Settings.canDrawOverlays(context)
    val appListEnabled = hasAppListAccess(context)
    val accessibilityEnabled = isAgentAccessibilityEnabled(context) || AgentAccessibilityService.isAvailable()
    val rootEnabled = isRootAvailable()
    val locationAccess = DeviceLocationProvider.accessState(context)
    val notificationHistoryEnabled = fuck.andes.agent.device.AgentNotificationHistoryService.isEnabled(context)
    val usageAccessEnabled = fuck.andes.agent.tool.AgentPersonalContextTools.hasUsageAccess(context)

    return PermissionHealthUiState(
        items = listOf(
            PermissionHealthItemUi(
                id = "background",
                title = "后台运行权限",
                summary = "",
                status = if (backgroundRunningEnabled) PermissionStatusUi.Available else PermissionStatusUi.Missing,
                primaryActionLabel = if (backgroundRunningEnabled) null else "去开启",
            ),
            PermissionHealthItemUi(
                id = "overlay",
                title = "悬浮窗权限",
                summary = "",
                status = if (overlayEnabled) PermissionStatusUi.Available else PermissionStatusUi.Missing,
                primaryActionLabel = if (overlayEnabled) null else "去授权",
            ),
            PermissionHealthItemUi(
                id = "app_list",
                title = "应用列表读取",
                summary = "",
                status = if (appListEnabled) PermissionStatusUi.Available else PermissionStatusUi.Missing,
                primaryActionLabel = if (appListEnabled) null else "去开启",
            ),
            PermissionHealthItemUi(
                id = "location",
                title = "位置权限",
                summary = when (locationAccess) {
                    DeviceLocationProvider.AccessState.DENIED -> "用于按需理解手机所在位置"
                    DeviceLocationProvider.AccessState.FOREGROUND_ONLY -> "小布入口需要设为“始终允许”"
                    DeviceLocationProvider.AccessState.DISABLED -> "系统定位服务已关闭"
                    DeviceLocationProvider.AccessState.AVAILABLE -> "仅在 Agent 调用工具时读取"
                },
                status = when (locationAccess) {
                    DeviceLocationProvider.AccessState.DENIED -> PermissionStatusUi.Missing
                    DeviceLocationProvider.AccessState.FOREGROUND_ONLY -> PermissionStatusUi.Warning
                    DeviceLocationProvider.AccessState.DISABLED -> PermissionStatusUi.Disabled
                    DeviceLocationProvider.AccessState.AVAILABLE -> PermissionStatusUi.Available
                },
                primaryActionLabel = when (locationAccess) {
                    DeviceLocationProvider.AccessState.DENIED -> "去授权"
                    DeviceLocationProvider.AccessState.FOREGROUND_ONLY -> "去设置"
                    DeviceLocationProvider.AccessState.DISABLED -> "去开启"
                    DeviceLocationProvider.AccessState.AVAILABLE -> null
                },
            ),
            PermissionHealthItemUi(
                id = "notification_history",
                title = "通知使用权",
                summary = if (notificationHistoryEnabled) {
                    "本机有界保存最近 7 天通知，仅在工具调用时读取"
                } else {
                    "授权后开始记录可检索的通知历史"
                },
                status = if (notificationHistoryEnabled) PermissionStatusUi.Available else PermissionStatusUi.Missing,
                primaryActionLabel = if (notificationHistoryEnabled) null else "去授权",
            ),
            PermissionHealthItemUi(
                id = "usage_access",
                title = "使用情况访问",
                summary = "用于读取最近打开的应用和前台使用时长",
                status = if (usageAccessEnabled) PermissionStatusUi.Available else PermissionStatusUi.Missing,
                primaryActionLabel = if (usageAccessEnabled) null else "去授权",
            ),
            PermissionHealthItemUi(
                id = "accessibility",
                title = "无障碍辅助权限",
                summary = "",
                status = if (accessibilityEnabled) PermissionStatusUi.Available else PermissionStatusUi.Missing,
                primaryActionLabel = if (accessibilityEnabled) null else "去开启",
            ),
            PermissionHealthItemUi(
                id = "root",
                title = "Root 权限",
                summary = "",
                status = if (rootEnabled) PermissionStatusUi.Available else PermissionStatusUi.Missing,
                primaryActionLabel = if (rootEnabled) null else "去开启",
            ),
        )
    )
}

private fun agentBooleanForUi(key: String): Boolean {
    return Prefs.isEnabled(key)
}

private fun AgentTokenUsage.toUi(): TokenUsageUi =
    TokenUsageUi(
        contextTokens = contextTokens,
        inputTokens = inputTokens,
        outputTokens = outputTokens,
        reasoningTokens = reasoningTokens,
        cachedTokens = cachedTokens,
    )

private fun buildSystemEnhanceState(): AgentSystemEnhanceUiState =
    AgentSystemEnhanceUiState(
        sections = listOf(
            SystemEnhanceSectionUi(
                id = "runtime",
                title = "Agent Runtime",
                items = listOf(
                    SystemEnhanceItemUi(
                        id = "streaming",
                        title = "流式事件",
                        summary = "模型增量、工具调用和最终结果会同步到当前对话",
                        status = SystemEnhanceStatusUi.Active,
                    ),
                    SystemEnhanceItemUi(
                        id = "memory",
                        title = "记忆系统",
                        summary = "核心记忆自动注入，详细内容由模型按需检索",
                        status = SystemEnhanceStatusUi.Active,
                    ),
                    SystemEnhanceItemUi(
                        id = "overlay",
                        title = "运行浮窗",
                        summary = "Runtime 服务运行时显示状态浮窗",
                        status = SystemEnhanceStatusUi.Active,
                    ),
                ),
            ),
            SystemEnhanceSectionUi(
                id = "future",
                title = "后续能力",
                items = listOf(
                    SystemEnhanceItemUi(
                        id = "hook",
                        title = "Hook 二级能力",
                        summary = "系统增强能力保留为后续二级功能",
                        status = SystemEnhanceStatusUi.Inactive,
                    ),
                ),
            ),
        )
    )

private fun isAgentAccessibilityEnabled(context: Context): Boolean {
    val expected = ComponentName(
        context,
        AgentAccessibilityService::class.java,
    ).flattenToString()
    val enabledServices = Settings.Secure.getString(
        context.contentResolver,
        Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
    ).orEmpty()
    return enabledServices.split(':').any { it.equals(expected, ignoreCase = true) }
}

private fun isIgnoringBatteryOptimizations(context: Context): Boolean {
    val powerManager = context.getSystemService(Context.POWER_SERVICE) as? PowerManager
    return powerManager?.isIgnoringBatteryOptimizations(context.packageName) ?: false
}

private fun isRootAvailable(): Boolean {
    return try {
        val process = Runtime.getRuntime().exec(arrayOf("su", "-c", "id"))
        val exitCode = process.waitFor()
        exitCode == 0
    } catch (e: Exception) {
        false
    }
}

private fun hasAppListAccess(context: Context): Boolean {
    return try {
        val pm = context.packageManager
        val packages = pm.getInstalledPackages(0)
        packages.size > 10
    } catch (e: Exception) {
        false
    }
}
