package fuck.andes.ui.app

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import kotlinx.coroutines.launch
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.navigation3.runtime.NavKey
import fuck.andes.FuckAndesApp
import fuck.andes.agent.device.DeviceLocationProvider
import fuck.andes.data.repository.RuntimeConfigRepository
import androidx.navigation3.runtime.entryProvider
import androidx.navigation3.runtime.rememberDecoratedNavEntries
import androidx.navigation3.ui.NavDisplay
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.ui.unit.dp
import fuck.andes.ui.components.MiuixDialogActions
import fuck.andes.ui.model.ConversationSummaryUi
import top.yukonga.miuix.kmp.basic.TextField
import top.yukonga.miuix.kmp.window.WindowDialog
import fuck.andes.ui.SettingsScreen
import fuck.andes.ui.pages.providers.ModelProviderDetailScreen
import fuck.andes.ui.pages.providers.ModelProviderListScreen
import fuck.andes.ui.model.AgentChatAction
import fuck.andes.ui.model.AgentHomeAction
import fuck.andes.ui.model.AgentSkillsAction
import fuck.andes.ui.model.AgentMemoryAction
import fuck.andes.ui.model.AgentSystemEnhanceAction
import fuck.andes.ui.model.AgentToolsAction
import fuck.andes.ui.model.PermissionHealthAction
import fuck.andes.ui.navigation.AgentNavigator
import fuck.andes.ui.navigation.AppRoute
import fuck.andes.ui.screens.chat.AgentChatScreen
import fuck.andes.ui.screens.browser.AgentBrowserScreen
import fuck.andes.ui.screens.enhance.SystemEnhanceScreen
import fuck.andes.ui.screens.home.AgentHomeScreen
import fuck.andes.ui.screens.memory.AgentMemoryScreen
import fuck.andes.ui.screens.permissions.PermissionHealthScreen
import fuck.andes.ui.screens.skills.AgentSkillsScreen
import fuck.andes.ui.screens.terminal.LinuxEnvironmentScreen
import fuck.andes.ui.screens.tools.AgentToolsScreen

/**
 * Agent App 根组件：持有本地导航栈，并把 Screen actions 交给 [AgentAppState]。
 */
@Composable
fun AgentAppRoot(
    assistantConversationKey: String? = null,
    onAssistantConversationOpened: (Boolean) -> Unit = {},
) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    val backStack = remember { mutableStateListOf<NavKey>(AppRoute.Home) }
    val navigator = remember { AgentNavigator(backStack) }
    val agentState = remember(context.applicationContext) {
        AgentAppState(
            context = context.applicationContext,
            scope = coroutineScope,
        )
    }
    val locationPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) {
        agentState.refreshPermissionHealth()
    }
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                agentState.refreshPermissionHealth()
                agentState.refreshRuntimeResults()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    var conversationPaneOpen by remember { mutableStateOf(false) }
    var conversationRenameTarget by remember { mutableStateOf<ConversationSummaryUi?>(null) }
    var conversationDeleteTarget by remember { mutableStateOf<ConversationSummaryUi?>(null) }
    var messageDeleteTarget by remember { mutableStateOf<MessageMutationTarget?>(null) }
    var messageRegenerateTarget by remember { mutableStateOf<MessageMutationTarget?>(null) }
    val focusManager = LocalFocusManager.current

    LaunchedEffect(Unit) {
        RuntimeConfigRepository.ensureDefaults(FuckAndesApp.serviceInstance)
    }

    LaunchedEffect(assistantConversationKey) {
        val conversationKey = assistantConversationKey ?: return@LaunchedEffect
        val opened = agentState.openAssistantConversation(conversationKey)
        if (opened) {
            navigator.replace(AppRoute.Chat)
        }
        onAssistantConversationOpened(opened)
    }

    fun pushRoute(
        route: AppRoute,
        restoreConversationPaneOnBack: Boolean = conversationPaneOpen,
    ) {
        conversationPaneOpen = restoreConversationPaneOnBack
        navigator.push(route)
    }

    fun popRoute() {
        if (!navigator.pop()) {
            (context as? Activity)?.finish()
        }
    }

    fun selectConversation(conversationId: String) {
        focusManager.clearFocus()
        agentState.selectConversation(conversationId)
        conversationPaneOpen = false
    }

    fun createConversation() {
        focusManager.clearFocus()
        agentState.createConversation()
        conversationPaneOpen = false
    }

    @Composable
    fun RoutedShell(
        route: AppRoute,
        content: @Composable () -> Unit,
    ) {
        AgentAppShell(
            currentRoute = route,
            isCurrentRoute = backStack.lastOrNull() == route,
            conversationPaneState = agentState.conversationPaneState,
            isConversationPaneOpen = conversationPaneOpen,
            currentModelName = agentState.currentModelName,
            currentProviderModels = agentState.currentProviderModels,
            onBack = { popRoute() },
            onOpenConversationPane = { conversationPaneOpen = true },
            onDismissConversationPane = { conversationPaneOpen = false },
            onSearchConversations = { query -> agentState.updateSearchQuery(query) },
            onNewConversation = { createConversation() },
            onSwitchModel = { modelId -> agentState.switchModel(modelId) },
            onSelectConversation = { conversationId -> selectConversation(conversationId) },
            onConversationRename = { conversation ->
                conversationRenameTarget = conversation
            },
            onConversationDelete = { conversation ->
                conversationDeleteTarget = conversation
            },
            onOpenTools = { pushRoute(AppRoute.Tools) },
            onOpenSkills = { pushRoute(AppRoute.Skills) },
            onOpenPermissions = { pushRoute(AppRoute.Permissions) },
            onOpenSettings = { pushRoute(AppRoute.Settings) },
            onOpenModelProviders = { pushRoute(AppRoute.ModelProviders) },
        ) { padding ->
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
            ) {
                content()
            }
        }
    }

    val entryProvider = remember(backStack) {
        entryProvider<NavKey> {
            entry<AppRoute.Home> {
                RoutedShell(route = AppRoute.Home) {
                    AgentHomeScreen(
                        state = agentState.homeState,
                        conversationKey = agentState.conversationPaneState.selectedConversationId,
                        contextUsageTokens = agentState.contextUsageTokens,
                        contextWindowTokens = agentState.contextWindowTokens,
                        contextJustTrimmed = agentState.contextJustTrimmed,
                        onAction = { action ->
                            when (action) {
                                is AgentHomeAction.ReasoningEffortChanged ->
                                    agentState.updateReasoningEffort(action.effort)
                                is AgentHomeAction.SubmitMessage -> agentState.sendCurrentMessage(action.text)
                                AgentHomeAction.StopRun -> agentState.stopCurrentRun()
                                is AgentHomeAction.ImageAttached -> agentState.attachImage(action.uri)
                                is AgentHomeAction.RemoveImage -> agentState.removePendingImage(action.id)
                                is AgentHomeAction.FilesAttached -> agentState.attachFiles(action.uris)
                                is AgentHomeAction.FolderAttached -> agentState.attachFolder(action.uri)
                                is AgentHomeAction.FilePathAttached -> agentState.attachFilePath(action.path)
                                is AgentHomeAction.RemoveFileReference ->
                                    agentState.removePendingFileReference(action.id)
                                is AgentHomeAction.EditMessage -> agentState.beginMessageEdit(action.id)
                                AgentHomeAction.CancelMessageEdit -> agentState.cancelMessageEdit()
                                is AgentHomeAction.DeleteMessage -> {
                                    agentState.messageRevisionImpact(action.id)?.let { impact ->
                                        messageDeleteTarget = MessageMutationTarget(action.id, impact.laterTurnCount)
                                    }
                                }
                                is AgentHomeAction.RegenerateMessage -> {
                                    val impact = agentState.messageRevisionImpact(action.id)
                                    if (impact?.laterTurnCount == 0) {
                                        agentState.regenerateMessage(action.id)
                                    } else if (impact != null) {
                                        messageRegenerateTarget = MessageMutationTarget(action.id, impact.laterTurnCount)
                                    }
                                }
                                AgentHomeAction.OpenTools -> pushRoute(AppRoute.Tools)
                                AgentHomeAction.OpenSkills -> pushRoute(AppRoute.Skills)
                                AgentHomeAction.OpenPermissions -> pushRoute(AppRoute.Permissions)
                                AgentHomeAction.OpenSystemEnhance -> pushRoute(AppRoute.SystemEnhance)
                                AgentHomeAction.OpenSettings -> pushRoute(AppRoute.Settings)
                                AgentHomeAction.OpenBrowser -> pushRoute(AppRoute.Browser)
                                AgentHomeAction.ExpandRunTrace -> Unit
                            }
                        },
                        isDrawerOpen = conversationPaneOpen,
                    )
                }
            }
            entry<AppRoute.Chat> {
                RoutedShell(route = AppRoute.Chat) {
                    AgentChatScreen(
                        state = agentState.homeState,
                        conversationKey = agentState.conversationPaneState.selectedConversationId,
                        contextUsageTokens = agentState.contextUsageTokens,
                        contextWindowTokens = agentState.contextWindowTokens,
                        contextJustTrimmed = agentState.contextJustTrimmed,
                        onAction = { action ->
                            when (action) {
                                AgentChatAction.NavigateBack -> popRoute()
                                is AgentChatAction.ReasoningEffortChanged ->
                                    agentState.updateReasoningEffort(action.effort)
                                is AgentChatAction.SubmitMessage -> agentState.sendCurrentMessage(action.text)
                                AgentChatAction.StopRun -> agentState.stopCurrentRun()
                                AgentChatAction.OpenBrowser -> pushRoute(AppRoute.Browser)
                                is AgentChatAction.ImageAttached -> agentState.attachImage(action.uri)
                                is AgentChatAction.RemoveImage -> agentState.removePendingImage(action.id)
                                is AgentChatAction.FilesAttached -> agentState.attachFiles(action.uris)
                                is AgentChatAction.FolderAttached -> agentState.attachFolder(action.uri)
                                is AgentChatAction.FilePathAttached -> agentState.attachFilePath(action.path)
                                is AgentChatAction.RemoveFileReference ->
                                    agentState.removePendingFileReference(action.id)
                                is AgentChatAction.EditMessage -> agentState.beginMessageEdit(action.id)
                                AgentChatAction.CancelMessageEdit -> agentState.cancelMessageEdit()
                                is AgentChatAction.DeleteMessage -> {
                                    agentState.messageRevisionImpact(action.id)?.let { impact ->
                                        messageDeleteTarget = MessageMutationTarget(action.id, impact.laterTurnCount)
                                    }
                                }
                                is AgentChatAction.RegenerateMessage -> {
                                    val impact = agentState.messageRevisionImpact(action.id)
                                    if (impact?.laterTurnCount == 0) {
                                        agentState.regenerateMessage(action.id)
                                    } else if (impact != null) {
                                        messageRegenerateTarget = MessageMutationTarget(action.id, impact.laterTurnCount)
                                    }
                                }
                            }
                        },
                    )
                }
            }
            entry<AppRoute.Browser> {
                RoutedShell(route = AppRoute.Browser) {
                    AgentBrowserScreen()
                }
            }
            entry<AppRoute.Tools> {
                AgentToolsScreen(
                    state = agentState.toolsState,
                    onAction = { action ->
                        when (action) {
                            AgentToolsAction.NavigateBack -> popRoute()
                            AgentToolsAction.OpenBrowser -> pushRoute(AppRoute.Browser)
                        }
                    },
                )
            }
            entry<AppRoute.Skills> {
                LaunchedEffect(Unit) {
                    agentState.refreshSkills()
                }
                AgentSkillsScreen(
                    state = agentState.skillsState,
                    onAction = { action ->
                        when (action) {
                            AgentSkillsAction.NavigateBack -> popRoute()
                            is AgentSkillsAction.ImportZip -> agentState.importSkillZip(action.uri)
                            AgentSkillsAction.ConfirmZipReplacement -> agentState.confirmSkillZipReplacement()
                            AgentSkillsAction.CancelZipReplacement -> agentState.cancelSkillZipReplacement()
                            AgentSkillsAction.DismissNotice -> agentState.dismissSkillNotice()
                            is AgentSkillsAction.ToggleSkill -> agentState.toggleSkill(action.skillId, action.enabled)
                            is AgentSkillsAction.DeleteSkill -> agentState.deleteSkill(action.skillId)
                            is AgentSkillsAction.ReinstallBuiltin -> agentState.reinstallBuiltin(action.skillId)
                        }
                    },
                )
            }
            entry<AppRoute.Permissions> {
                LaunchedEffect(Unit) {
                    agentState.refreshPermissionHealth()
                }
                PermissionHealthScreen(
                    state = agentState.permissionHealthState,
                    onAction = { action ->
                        when (action) {
                            PermissionHealthAction.NavigateBack -> popRoute()
                            is PermissionHealthAction.OpenItemAction -> {
                                when (action.itemId) {
                                    "accessibility" -> {
                                        runCatching {
                                            context.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
                                        }
                                    }
                                    "overlay" -> {
                                        runCatching {
                                            context.startActivity(
                                                Intent(
                                                    Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                                                    Uri.parse("package:${context.packageName}")
                                                )
                                            )
                                        }
                                    }
                                    "background" -> {
                                        runCatching {
                                            context.startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
                                        }
                                    }
                                    "app_list" -> {
                                        runCatching {
                                            context.startActivity(
                                                Intent(
                                                    Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                                                    Uri.parse("package:${context.packageName}")
                                                )
                                            )
                                        }
                                    }
                                    "location" -> {
                                        when (DeviceLocationProvider.accessState(context)) {
                                            DeviceLocationProvider.AccessState.DENIED -> {
                                                locationPermissionLauncher.launch(
                                                    arrayOf(
                                                        Manifest.permission.ACCESS_COARSE_LOCATION,
                                                        Manifest.permission.ACCESS_FINE_LOCATION,
                                                    )
                                                )
                                            }
                                            DeviceLocationProvider.AccessState.FOREGROUND_ONLY -> {
                                                runCatching {
                                                    context.startActivity(
                                                        Intent(
                                                            Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                                                            Uri.parse("package:${context.packageName}")
                                                        )
                                                    )
                                                }
                                            }
                                            DeviceLocationProvider.AccessState.DISABLED -> {
                                                runCatching {
                                                    context.startActivity(
                                                        Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS)
                                                    )
                                                }
                                            }
                                            DeviceLocationProvider.AccessState.AVAILABLE -> {
                                                agentState.refreshPermissionHealth()
                                            }
                                        }
                                    }
                                    "notification_history" -> {
                                        runCatching {
                                            context.startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
                                        }
                                    }
                                    "usage_access" -> {
                                        runCatching {
                                            context.startActivity(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS))
                                        }
                                    }
                                    "root" -> {
                                        coroutineScope.launch {
                                            try {
                                                val process = Runtime.getRuntime().exec(arrayOf("su", "-c", "id"))
                                                process.waitFor()
                                            } catch (e: Exception) {
                                                // no-op
                                            }
                                            agentState.refreshPermissionHealth()
                                        }
                                    }
                                }
                            }
                        }
                    },
                )
            }
            entry<AppRoute.SystemEnhance> {
                SystemEnhanceScreen(
                    state = agentState.systemEnhanceState,
                    onAction = { action ->
                        when (action) {
                            AgentSystemEnhanceAction.NavigateBack -> popRoute()
                            is AgentSystemEnhanceAction.ToggleItem -> Unit
                        }
                    },
                )
            }
            entry<AppRoute.Settings> {
                SettingsScreen(
                    context = context,
                    onNavigate = { route -> pushRoute(route) },
                    onBack = ::popRoute
                )
            }
            entry<AppRoute.Memory> {
                LaunchedEffect(Unit) {
                    agentState.refreshMemory()
                }
                AgentMemoryScreen(
                    state = agentState.memoryState,
                    onAction = { action ->
                        when (action) {
                            AgentMemoryAction.NavigateBack -> popRoute()
                            is AgentMemoryAction.ToggleEnabled -> agentState.setMemoryEnabled(action.enabled)
                            is AgentMemoryAction.DraftChanged -> agentState.updateMemoryDraft(action.content)
                            AgentMemoryAction.Save -> agentState.saveMemory()
                            AgentMemoryAction.Clear -> agentState.clearMemory()
                            AgentMemoryAction.DismissNotice -> agentState.dismissMemoryNotice()
                        }
                    },
                )
            }
            entry<AppRoute.LinuxEnvironment> {
                LinuxEnvironmentScreen(
                    context = context,
                    onBack = ::popRoute,
                )
            }
            entry<AppRoute.ModelProviders> {
                ModelProviderListScreen(
                    onNavigate = { route -> pushRoute(route) },
                    onBack = ::popRoute
                )
            }
            entry<AppRoute.ModelProviderDetail> { route ->
                ModelProviderDetailScreen(
                    providerId = route.providerId,
                    onBack = ::popRoute
                )
            }
            entry<AppRoute.ModelProviderNew> { route ->
                ModelProviderDetailScreen(
                    newType = route.type,
                    onBack = ::popRoute
                )
            }
        }
    }
    val entries = rememberDecoratedNavEntries(
        backStack = backStack,
        entryProvider = entryProvider,
    )

    NavDisplay(
        entries = entries,
        onBack = { popRoute() },
    )

    conversationRenameTarget?.let { conversation ->
        var renameInput by remember(conversation.id) { mutableStateOf(conversation.title) }
        WindowDialog(
            show = true,
            title = "重命名对话",
            onDismissRequest = { conversationRenameTarget = null },
        ) {
            Column {
                TextField(
                    value = renameInput,
                    onValueChange = { renameInput = it },
                    label = "对话名称",
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                MiuixDialogActions(
                    confirmText = "确定",
                    confirmEnabled = renameInput.isNotBlank(),
                    onCancel = { conversationRenameTarget = null },
                    onConfirm = {
                        agentState.renameConversation(conversation.id, renameInput)
                        conversationRenameTarget = null
                    },
                    modifier = Modifier.padding(top = 16.dp),
                )
            }
        }
    }

    conversationDeleteTarget?.let { conversation ->
        WindowDialog(
            show = true,
            title = "删除对话",
            summary = "删除后，该对话将不可恢复",
            onDismissRequest = { conversationDeleteTarget = null },
        ) {
            MiuixDialogActions(
                confirmText = "删除",
                destructive = true,
                onCancel = { conversationDeleteTarget = null },
                onConfirm = {
                    agentState.deleteConversation(conversation.id)
                    conversationDeleteTarget = null
                },
            )
        }
    }

    messageDeleteTarget?.let { target ->
        WindowDialog(
            show = true,
            title = "删除这轮对话",
            summary = target.destructiveSummary("删除"),
            onDismissRequest = { messageDeleteTarget = null },
        ) {
            MiuixDialogActions(
                confirmText = "删除",
                destructive = true,
                onCancel = { messageDeleteTarget = null },
                onConfirm = {
                    agentState.deleteMessageTurn(target.messageId)
                    messageDeleteTarget = null
                },
            )
        }
    }

    messageRegenerateTarget?.let { target ->
        WindowDialog(
            show = true,
            title = "重新生成回复",
            summary = target.destructiveSummary("重新生成"),
            onDismissRequest = { messageRegenerateTarget = null },
        ) {
            MiuixDialogActions(
                confirmText = "重新生成",
                destructive = true,
                onCancel = { messageRegenerateTarget = null },
                onConfirm = {
                    agentState.regenerateMessage(target.messageId)
                    messageRegenerateTarget = null
                },
            )
        }
    }
}

private data class MessageMutationTarget(
    val messageId: String,
    val laterTurnCount: Int,
) {
    fun destructiveSummary(action: String): String =
        if (laterTurnCount == 0) {
            "$action 后，当前轮次将不可恢复"
        } else {
            "$action 后，当前轮次及之后的 $laterTurnCount 轮对话将不可恢复"
        }
}
