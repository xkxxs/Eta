package fuck.andes.ui.app

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.composables.icons.lucide.R as LucideR
import fuck.andes.ui.components.ConversationSidePaneScaffold
import fuck.andes.ui.model.ConversationPaneUiState
import fuck.andes.ui.model.ConversationSummaryUi
import fuck.andes.ui.navigation.AppRoute
import top.yukonga.miuix.kmp.basic.DropdownImpl
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.ListPopupColumn
import top.yukonga.miuix.kmp.basic.PopupPositionProvider
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.SmallTopAppBar
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.LocalDismissState
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.window.WindowListPopup

/**
 * Agent App 统一壳层。
 *
 * - 负责全局 Scaffold、状态栏/横向安全边距、顶层工具栏。
 * - 首页工具栏只保留历史入口与新建对话，保持聊天舞台干净。
 * - 非首页子路由统一提供返回按钮与标题，避免每个页面各自像独立设置页。
 * - Settings 保留旧 SettingsScreen 自己的 TopAppBar，壳层在此路由不显示顶部工具栏。
 */
@Composable
fun AgentAppShell(
    currentRoute: AppRoute?,
    isCurrentRoute: Boolean,
    conversationPaneState: ConversationPaneUiState?,
    isConversationPaneOpen: Boolean,
    currentModelName: String?,
    currentProviderModels: List<ModelSwitcherItem>,
    onBack: () -> Unit,
    onOpenConversationPane: () -> Unit,
    onDismissConversationPane: () -> Unit,
    onSearchConversations: (String) -> Unit,
    onNewConversation: () -> Unit,
    onSwitchModel: (String) -> Unit,
    onSelectConversation: (String) -> Unit,
    onConversationRename: (ConversationSummaryUi) -> Unit,
    onConversationDelete: (ConversationSummaryUi) -> Unit,
    onOpenTools: () -> Unit,
    onOpenSkills: () -> Unit,
    onOpenPermissions: () -> Unit,
    onOpenSettings: () -> Unit,
    onOpenModelProviders: () -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable (PaddingValues) -> Unit,
) {
    val pageContent: @Composable () -> Unit = {
        Scaffold(
            modifier = Modifier.fillMaxSize(),
            contentWindowInsets = WindowInsets.safeDrawing.only(
                WindowInsetsSides.Top + WindowInsetsSides.Horizontal,
            ),
            topBar = {
                if (currentRoute !is AppRoute.Settings) {
                    AgentTopBar(
                        route = currentRoute,
                        currentModelName = currentModelName,
                        currentProviderModels = currentProviderModels,
                        onBack = onBack,
                        onOpenConversationPane = onOpenConversationPane,
                        onNewConversation = onNewConversation,
                        onSwitchModel = onSwitchModel,
                    )
                }
            },
        ) { padding ->
            content(padding)
        }
    }

    Box(modifier = modifier.fillMaxSize()) {
        if (conversationPaneState != null && currentRoute is AppRoute.Home) {
            ConversationSidePaneScaffold(
                state = conversationPaneState,
                visible = isConversationPaneOpen,
                backHandlerEnabled = isCurrentRoute,
                onOpen = onOpenConversationPane,
                onDismiss = onDismissConversationPane,
                onSearchChange = onSearchConversations,
                onConversationSelected = onSelectConversation,
                onConversationRename = onConversationRename,
                onConversationDelete = onConversationDelete,
                onOpenSettings = onOpenSettings,
                onOpenModelProviders = onOpenModelProviders,
                onOpenTools = onOpenTools,
                onOpenSkills = onOpenSkills,
                onOpenPermissions = onOpenPermissions,
            ) {
                pageContent()
            }
        } else {
            pageContent()
        }
    }
}

@Composable
private fun AgentTopBar(
    route: AppRoute?,
    currentModelName: String?,
    currentProviderModels: List<ModelSwitcherItem>,
    onBack: () -> Unit,
    onOpenConversationPane: () -> Unit,
    onNewConversation: () -> Unit,
    onSwitchModel: (String) -> Unit,
) {
    val isHome = route is AppRoute.Home
    if (isHome) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .windowInsetsPadding(WindowInsets.statusBars)
                .height(56.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onOpenConversationPane) {
                Icon(
                    painter = painterResource(LucideR.drawable.lucide_ic_menu),
                    contentDescription = "会话历史",
                )
            }
            Spacer(modifier = Modifier.weight(1f))
            ModelSwitcherTitle(
                currentModelName = currentModelName,
                models = currentProviderModels,
                onSwitchModel = onSwitchModel,
            )
            Spacer(modifier = Modifier.weight(1f))
            IconButton(onClick = onNewConversation) {
                Icon(
                    painter = painterResource(LucideR.drawable.lucide_ic_message_circle_plus),
                    contentDescription = "新建对话",
                )
            }
        }
    } else {
        SmallTopAppBar(
            title = titleForRoute(route),
            color = if (route is AppRoute.Tools) Color.Transparent else MiuixTheme.colorScheme.surface,
            navigationIcon = {
                IconButton(onClick = onBack) {
                    Icon(
                        painter = painterResource(LucideR.drawable.lucide_ic_chevron_left),
                        contentDescription = "返回",
                    )
                }
            },
        )
    }
}

/**
 * 顶部中间模型名称：点击弹出同提供商模型列表，选择即切换。
 */
@Composable
private fun ModelSwitcherTitle(
    currentModelName: String?,
    models: List<ModelSwitcherItem>,
    onSwitchModel: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    var showPopup by remember { mutableStateOf(false) }
    WindowListPopup(
        show = showPopup && models.size > 1,
        alignment = PopupPositionProvider.Align.TopStart,
        enableWindowDim = false,
        onDismissRequest = { showPopup = false },
    ) {
        val dismiss = LocalDismissState.current
        ListPopupColumn {
            models.forEachIndexed { index, model ->
                DropdownImpl(
                    text = model.displayName,
                    optionSize = models.size,
                    isSelected = model.displayName == currentModelName,
                    index = index,
                    onSelectedIndexChange = {
                        onSwitchModel(model.id)
                        dismiss?.invoke()
                    },
                )
            }
        }
    }
    Row(
        modifier = modifier
            .clip(CircleShape)
            .clickable(enabled = models.size > 1) { showPopup = true }
            .padding(horizontal = 10.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = currentModelName ?: "未选择模型",
            style = MiuixTheme.textStyles.title4,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        if (models.size > 1) {
            Spacer(modifier = Modifier.width(3.dp))
            Icon(
                painter = painterResource(LucideR.drawable.lucide_ic_chevron_down),
                contentDescription = null,
                modifier = Modifier.size(12.dp),
                tint = MiuixTheme.colorScheme.onSurfaceVariantSummary,
            )
        }
    }
}

@Composable
private fun titleForRoute(route: AppRoute?): String = when (route) {
    is AppRoute.Home -> ""
    is AppRoute.Chat -> "对话"
    is AppRoute.Browser -> "Agent 浏览器"
    is AppRoute.Tools -> "工具能力"
    is AppRoute.Skills -> "技能"
    is AppRoute.Permissions -> "权限健康"
    is AppRoute.SystemEnhance -> "系统增强"
    is AppRoute.Settings -> "设置"
    is AppRoute.Memory -> "记忆"
    is AppRoute.LinuxEnvironment -> "Linux 工具环境"
    is AppRoute.ModelProviders -> "模型提供商"
    is AppRoute.ModelProviderDetail -> route.providerId.let { "Provider 详情" }
    is AppRoute.ModelProviderNew -> "新建提供商"
    null -> "Eta"
}
