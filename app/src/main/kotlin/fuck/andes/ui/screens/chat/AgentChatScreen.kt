package fuck.andes.ui.screens.chat

import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import androidx.compose.ui.Modifier
import fuck.andes.ui.components.AgentChatBody
import fuck.andes.ui.components.chatConversationCompositionKey
import fuck.andes.ui.model.AgentChatAction
import fuck.andes.ui.model.AgentChatUiState

/**
 * 独立对话页：与首页聊天主舞台共用同一套消息/输入组件，
 * 区别仅在于顶部返回由 Shell 统一提供。
 */
@Composable
internal fun AgentChatScreen(
    state: AgentChatUiState,
    conversationKey: String?,
    contextUsageTokens: Int?,
    contextWindowTokens: Int?,
    contextJustTrimmed: Boolean,
    onAction: (AgentChatAction) -> Unit,
    modifier: Modifier = Modifier,
) {
    key(chatConversationCompositionKey(conversationKey)) {
        AgentChatBody(
            messages = state.messages,
            input = state.input,
            isStreaming = state.isStreaming,
            reasoningEffort = state.reasoningEffort,
            availableReasoningEfforts = state.availableReasoningEfforts,
            pendingImages = state.pendingImages,
            pendingFileReferences = state.pendingFileReferences,
            messageEdit = state.messageEdit,
            contextUsageTokens = contextUsageTokens,
            contextWindowTokens = contextWindowTokens,
            contextJustTrimmed = contextJustTrimmed,
            onReasoningEffortChange = { onAction(AgentChatAction.ReasoningEffortChanged(it)) },
            onSubmit = { text -> onAction(AgentChatAction.SubmitMessage(text)) },
            onStop = { onAction(AgentChatAction.StopRun) },
            onAttachImage = { uri -> onAction(AgentChatAction.ImageAttached(uri)) },
            onRemoveImage = { id -> onAction(AgentChatAction.RemoveImage(id)) },
            onAttachFiles = { uris -> onAction(AgentChatAction.FilesAttached(uris)) },
            onAttachFolder = { uri -> onAction(AgentChatAction.FolderAttached(uri)) },
            onAttachFilePath = { path -> onAction(AgentChatAction.FilePathAttached(path)) },
            onRemoveFileReference = { id -> onAction(AgentChatAction.RemoveFileReference(id)) },
            onEditMessage = { id -> onAction(AgentChatAction.EditMessage(id)) },
            onCancelMessageEdit = { onAction(AgentChatAction.CancelMessageEdit) },
            onDeleteMessage = { id -> onAction(AgentChatAction.DeleteMessage(id)) },
            onRegenerateMessage = { id -> onAction(AgentChatAction.RegenerateMessage(id)) },
            onSuggestionClick = { prompt ->
                onAction(AgentChatAction.SubmitMessage(prompt))
            },
            onRunTraceClick = { /* 对话页暂不做 Run trace 展开 */ },
            onOpenBrowser = { onAction(AgentChatAction.OpenBrowser) },
            modifier = modifier,
        )
    }
}
