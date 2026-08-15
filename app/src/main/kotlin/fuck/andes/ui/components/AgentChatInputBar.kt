package fuck.andes.ui.components

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.dropShadow
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.shadow.Shadow
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.text.font.FontWeight
import com.composables.icons.lucide.R as LucideR
import fuck.andes.data.model.ReasoningEffort
import fuck.andes.ui.model.PendingFileReferenceUi
import fuck.andes.ui.model.PendingImageUi
import top.yukonga.miuix.kmp.basic.DropdownImpl
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.ListPopupColumn
import top.yukonga.miuix.kmp.basic.ListPopupDefaults
import top.yukonga.miuix.kmp.basic.PopupPositionProvider
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.squircle.squircleBorder
import top.yukonga.miuix.kmp.squircle.squircleSurface
import top.yukonga.miuix.kmp.theme.LocalDismissState
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.window.WindowListPopup
import kotlin.math.roundToInt

private val InputIconSize = 20.dp
private val SendButtonVisualSize = 32.dp
private val ThinkingChipShape = RoundedCornerShape(percent = 50)
private val InputContainerShape = RoundedCornerShape(20.dp)

/**
 * Agent 输入器始终保持同一空间结构，聚焦、输入和执行过程只改变状态，不搬动操作入口。
 */
@Composable
fun AgentChatInputBar(
    input: String,
    isStreaming: Boolean,
    reasoningEffort: ReasoningEffort,
    availableReasoningEfforts: List<ReasoningEffort>,
    pendingImages: List<PendingImageUi>,
    pendingFileReferences: List<PendingFileReferenceUi>,
    isEditingMessage: Boolean,
    editHasLaterTurns: Boolean,
    contextUsageTokens: Int?,
    contextWindowTokens: Int?,
    contextJustTrimmed: Boolean,
    onReasoningEffortChange: (ReasoningEffort) -> Unit,
    onSubmit: (String) -> Unit,
    onStop: () -> Unit,
    onAttachImage: (String) -> Unit,
    onRemoveImage: (String) -> Unit,
    onAttachFiles: (List<String>) -> Unit,
    onAttachFolder: (String) -> Unit,
    onAttachFilePath: (String) -> Unit,
    onRemoveFileReference: (String) -> Unit,
    onCancelMessageEdit: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val keyboard = LocalSoftwareKeyboardController.current
    val focusRequester = remember { FocusRequester() }
    var textFieldValue by remember { mutableStateOf(TextFieldValue(input)) }
    var wasEditingMessage by remember { mutableStateOf(isEditingMessage) }
    val canSend = textFieldValue.text.isNotBlank() ||
        pendingImages.isNotEmpty() ||
        pendingFileReferences.isNotEmpty()
    val density = LocalDensity.current
    val statusBarTopPx = WindowInsets.statusBars.getTop(density)
    var inputContainerTopPx by remember { mutableIntStateOf(0) }
    val thinkingPopupMaxHeight = with(density) {
        (inputContainerTopPx - statusBarTopPx).coerceAtLeast(0).toDp()
    }.minus(ChatInputPopupMargin * 2)
        .coerceAtLeast(ListPopupDefaults.MinPopupHeight)

    LaunchedEffect(isEditingMessage) {
        // 编辑态由外部业务状态驱动；普通输入只保留在本地，避免每个字符把聊天舞台
        // 的消息流、滚动和 Markdown 一起带入重组。
        if (isEditingMessage || wasEditingMessage) {
            textFieldValue = TextFieldValue(input)
        }
        if (isEditingMessage) {
            focusRequester.requestFocus()
            keyboard?.show()
        }
        wasEditingMessage = isEditingMessage
    }

    LaunchedEffect(isStreaming) {
        if (isStreaming) {
            // 发送按钮、建议词和外部恢复都可能启动流式任务，统一清掉本地草稿。
            textFieldValue = TextFieldValue()
        }
    }

    Column(
        modifier = modifier
            .fillMaxWidth(),
    ) {
        AnimatedVisibility(
            visible = pendingFileReferences.isNotEmpty(),
            enter = fadeIn(tween(160)),
            exit = fadeOut(tween(100)) + shrinkVertically(tween(160)),
        ) {
            PendingFileReferenceStrip(
                references = pendingFileReferences,
                onRemoveReference = onRemoveFileReference,
                modifier = Modifier.padding(bottom = 8.dp),
            )
        }

        AnimatedVisibility(
            visible = pendingImages.isNotEmpty(),
            enter = fadeIn(tween(160)),
            exit = fadeOut(tween(100)) + shrinkVertically(tween(160)),
        ) {
            PendingImageStrip(
                images = pendingImages,
                onRemoveImage = onRemoveImage,
                modifier = Modifier.padding(bottom = 8.dp),
            )
        }

        AnimatedVisibility(
            visible = isEditingMessage,
            enter = fadeIn(tween(160)),
            exit = fadeOut(tween(100)) + shrinkVertically(tween(140)),
        ) {
            Text(
                text = if (editHasLaterTurns) {
                    "发送后将替换此消息及之后内容"
                } else {
                    "发送后将替换此消息"
                },
                style = MiuixTheme.textStyles.body2,
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                modifier = Modifier.padding(start = 8.dp, bottom = 6.dp),
            )
        }

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .onGloballyPositioned { coordinates ->
                    inputContainerTopPx = coordinates.positionInWindow().y.roundToInt()
                },
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .dropShadow(
                        shape = InputContainerShape,
                        shadow = Shadow(
                            radius = 8.dp,
                            color = Color.Black,
                            alpha = 0.08f,
                        ),
                    )
                    .squircleSurface(
                        color = MiuixTheme.colorScheme.surfaceContainer,
                        cornerRadius = 20.dp,
                    )
                    .squircleBorder(
                        width = 0.5.dp,
                        color = MiuixTheme.colorScheme.outline.copy(alpha = 0.55f),
                        cornerRadius = 20.dp,
                    )
                    .padding(horizontal = 10.dp, vertical = 8.dp),
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .defaultMinSize(minHeight = 40.dp)
                        .padding(horizontal = 8.dp, vertical = 5.dp),
                    contentAlignment = Alignment.TopStart,
                ) {
                    if (textFieldValue.text.isBlank()) {
                        Text(
                            text = if (isStreaming) "Eta 正在执行…" else "交给 Eta 去完成",
                            style = MiuixTheme.textStyles.body1,
                            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                        )
                    }
                    BasicTextField(
                        value = textFieldValue,
                        onValueChange = { textFieldValue = it },
                        modifier = Modifier
                            .fillMaxWidth()
                            .focusRequester(focusRequester),
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Default),
                        textStyle = TextStyle(
                            color = MiuixTheme.colorScheme.onSurface,
                            fontSize = 16.sp,
                            lineHeight = 22.sp,
                        ),
                        cursorBrush = SolidColor(MiuixTheme.colorScheme.primary),
                        maxLines = 6,
                        minLines = 1,
                    )
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    if (isEditingMessage) {
                        IconButton(
                            onClick = onCancelMessageEdit,
                            minWidth = 38.dp,
                            minHeight = 38.dp,
                        ) {
                            Icon(
                                painter = painterResource(LucideR.drawable.lucide_ic_x),
                                contentDescription = "取消编辑",
                                modifier = Modifier.size(InputIconSize),
                                tint = MiuixTheme.colorScheme.onSurface,
                            )
                        }
                    } else {
                        AgentAttachmentPickerButton(
                            popupAnchorTopPx = inputContainerTopPx,
                            popupMaxHeight = thinkingPopupMaxHeight,
                            onAttachImage = onAttachImage,
                            onAttachFiles = onAttachFiles,
                            onAttachFolder = onAttachFolder,
                            onAttachFilePath = onAttachFilePath,
                        )

                        Spacer(modifier = Modifier.width(2.dp))

                        if (availableReasoningEfforts.isNotEmpty()) {
                            ThinkingEffortChip(
                                effort = reasoningEffort,
                                options = availableReasoningEfforts,
                                enabled = !isStreaming,
                                popupAnchorTopPx = inputContainerTopPx,
                                popupMaxHeight = thinkingPopupMaxHeight,
                                onEffortChange = onReasoningEffortChange,
                            )
                        }

                        ContextUsageRing(
                            usageTokens = contextUsageTokens,
                            windowTokens = contextWindowTokens,
                            compressing = contextJustTrimmed,
                            modifier = Modifier.padding(start = 6.dp),
                        )
                    }

                    Spacer(modifier = Modifier.weight(1f))

                    IconButton(
                        onClick = if (isStreaming) {
                            onStop
                        } else {
                            {
                                if (canSend) {
                                    val submittedText = textFieldValue.text
                                    textFieldValue = TextFieldValue()
                                    onSubmit(submittedText)
                                }
                            }
                        },
                        enabled = isStreaming || canSend,
                        minWidth = 38.dp,
                        minHeight = 38.dp,
                    ) {
                        // The 38dp outer button remains easy to hit; only the visible
                        // circle is reduced so it has the same optical weight as the
                        // adjacent toolbar icons.
                        val sendButtonColor by animateColorAsState(
                            targetValue = when {
                                isStreaming -> MiuixTheme.colorScheme.onSurface
                                canSend -> MiuixTheme.colorScheme.primary
                                else -> MiuixTheme.colorScheme.surfaceContainerHigh
                            },
                            animationSpec = tween(durationMillis = 160),
                            label = "send_button_color",
                        )
                        Box(
                            modifier = Modifier
                                .size(SendButtonVisualSize)
                                .clip(CircleShape)
                                .background(sendButtonColor),
                            contentAlignment = Alignment.Center,
                        ) {
                            AnimatedContent(
                                targetState = isStreaming,
                                transitionSpec = {
                                    (fadeIn(tween(130)) + scaleIn(tween(160), initialScale = 0.72f))
                                        .togetherWith(
                                            fadeOut(tween(90)) +
                                                scaleOut(tween(110), targetScale = 0.72f)
                                        )
                                },
                                label = "send_stop_icon",
                            ) { streaming ->
                                Icon(
                                    painter = painterResource(
                                        if (streaming) {
                                            LucideR.drawable.lucide_ic_square
                                        } else {
                                            LucideR.drawable.lucide_ic_arrow_up
                                        }
                                    ),
                                    contentDescription = if (streaming) "停止" else "发送",
                                    modifier = Modifier.size(if (streaming) 12.dp else 17.dp),
                                    tint = when {
                                        streaming -> MiuixTheme.colorScheme.surface
                                        canSend -> MiuixTheme.colorScheme.onPrimary
                                        else -> MiuixTheme.colorScheme.onSurfaceVariantActions
                                    },
                                )
                            }
                        }
                    }
                }
            }
        }
    }

}

/**
 * 思考强度选择：启用推理时填充主色淡底，Off 保持描边线框。
 */
@Composable
private fun ThinkingEffortChip(
    effort: ReasoningEffort,
    options: List<ReasoningEffort>,
    enabled: Boolean,
    popupAnchorTopPx: Int,
    popupMaxHeight: Dp,
    onEffortChange: (ReasoningEffort) -> Unit,
    modifier: Modifier = Modifier,
) {
    var showPopup by remember { mutableStateOf(false) }
    val active = effort != ReasoningEffort.OFF
    val menuEnabled = enabled && options.size > 1
    LaunchedEffect(menuEnabled) {
        if (!menuEnabled) showPopup = false
    }
    val popupPositionProvider = remember(popupAnchorTopPx) {
        InputPopupPositionProvider(popupAnchorTopPx)
    }
    val contentColor by animateColorAsState(
        targetValue = if (active) {
            MiuixTheme.colorScheme.primary
        } else {
            MiuixTheme.colorScheme.onSurfaceVariantSummary
        },
        animationSpec = tween(durationMillis = 160),
        label = "thinking_content",
    )
    val backgroundColor by animateColorAsState(
        targetValue = if (active) {
            MiuixTheme.colorScheme.primary.copy(alpha = 0.10f)
        } else {
            Color.Transparent
        },
        animationSpec = tween(durationMillis = 160),
        label = "thinking_background",
    )
    val outlineColor by animateColorAsState(
        targetValue = if (active) {
            Color.Transparent
        } else {
            MiuixTheme.colorScheme.outline.copy(alpha = 0.6f)
        },
        animationSpec = tween(durationMillis = 160),
        label = "thinking_outline",
    )
    Box(modifier = modifier) {
        Row(
            modifier = Modifier
                .clip(ThinkingChipShape)
                .background(backgroundColor)
                .border(0.5.dp, outlineColor, ThinkingChipShape)
                .clickable(enabled = menuEnabled) { showPopup = true }
                .padding(horizontal = 11.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                painter = painterResource(LucideR.drawable.lucide_ic_atom),
                contentDescription = null,
                modifier = Modifier.size(13.dp),
                tint = contentColor,
            )
            Spacer(modifier = Modifier.width(5.dp))
            Text(
                text = "Thinking · ${effort.displayName}",
                style = MiuixTheme.textStyles.footnote1,
                color = contentColor,
            )
        }
        WindowListPopup(
            show = showPopup && menuEnabled && popupAnchorTopPx > 0,
            popupPositionProvider = popupPositionProvider,
            alignment = PopupPositionProvider.Align.TopStart,
            enableWindowDim = false,
            onDismissRequest = { showPopup = false },
            maxHeight = popupMaxHeight,
        ) {
            val dismiss = LocalDismissState.current
            ListPopupColumn {
                options.forEachIndexed { index, option ->
                    DropdownImpl(
                        text = option.displayName,
                        optionSize = options.size,
                        isSelected = option == effort,
                        index = index,
                        onSelectedIndexChange = {
                            onEffortChange(option)
                            dismiss?.invoke()
                        },
                    )
                }
            }
        }
    }
}

/**
 * 横向跟随 Chip，竖向则避开整个输入面板；默认下拉定位只会避开 Chip 自身。
 */
@Composable
private fun PendingImageStrip(
    images: List<PendingImageUi>,
    onRemoveImage: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        images.forEach { image ->
            Box(
                modifier = Modifier
                    .size(60.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(MiuixTheme.colorScheme.surfaceContainer),
            ) {
                rememberDataUrlBitmap(image.dataUrl)?.let { bitmap ->
                    Image(
                        bitmap = bitmap,
                        contentDescription = null,
                        modifier = Modifier.fillMaxSize(),
                        contentScale = ContentScale.Crop,
                    )
                }
                Box(
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(3.dp)
                        .size(18.dp)
                        .clip(CircleShape)
                        .background(Color.Black.copy(alpha = 0.58f))
                        .clickable { onRemoveImage(image.id) },
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        painter = painterResource(LucideR.drawable.lucide_ic_x),
                        contentDescription = "移除图片",
                        modifier = Modifier.size(11.dp),
                        tint = Color.White,
                    )
                }
            }
        }
    }
}

/**
 * 上下文占用圆环：常态显示占用百分比与窗口上限，压缩时切换为加载转圈动画。
 */
@Composable
private fun ContextUsageRing(
    usageTokens: Int?,
    windowTokens: Int?,
    compressing: Boolean,
    modifier: Modifier = Modifier,
) {
    if (usageTokens == null || windowTokens == null || windowTokens <= 0) return
    val percent = (usageTokens.toFloat() / windowTokens.toFloat() * 100f)
        .coerceIn(0f, 100f)
    val windowLabel = formatContextWindowTokens(windowTokens)
    val progressColor = if (percent >= 90f) {
        Color(0xFFE53935)
    } else {
        MiuixTheme.colorScheme.primary
    }

    Row(
        modifier = modifier,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        val transition = rememberInfiniteTransition(label = "ctx_ring_spin")
        val rotation by transition.animateFloat(
            initialValue = 0f,
            targetValue = 360f,
            animationSpec = infiniteRepeatable(
                animation = tween(durationMillis = 1_100, easing = LinearEasing),
            ),
            label = "ctx_ring_rotation",
        )
        val ringSize = 18.dp
        val strokeWidth = 2.dp
        val trackColor = MiuixTheme.colorScheme.outline.copy(alpha = 0.35f)
        Box(modifier = Modifier.size(ringSize)) {
            Canvas(
                modifier = Modifier
                    .fillMaxSize()
                    .then(if (compressing) Modifier.rotate(rotation) else Modifier),
            ) {
                val strokePx = strokeWidth.toPx()
                val inset = strokePx / 2f
                val arcSize = Size(size.width - strokePx, size.height - strokePx)
                drawArc(
                    color = trackColor,
                    startAngle = 0f,
                    sweepAngle = 360f,
                    useCenter = false,
                    topLeft = Offset(inset, inset),
                    size = arcSize,
                    style = Stroke(width = strokePx, cap = StrokeCap.Round),
                )
                if (compressing) {
                    drawArc(
                        color = progressColor,
                        startAngle = -90f,
                        sweepAngle = 290f,
                        useCenter = false,
                        topLeft = Offset(inset, inset),
                        size = arcSize,
                        style = Stroke(width = strokePx, cap = StrokeCap.Round),
                    )
                } else if (percent > 0f) {
                    drawArc(
                        color = progressColor,
                        startAngle = -90f,
                        sweepAngle = 360f * percent / 100f,
                        useCenter = false,
                        topLeft = Offset(inset, inset),
                        size = arcSize,
                        style = Stroke(width = strokePx, cap = StrokeCap.Round),
                    )
                }
            }
        }

        Spacer(modifier = Modifier.width(5.dp))

        if (compressing) {
            Text(
                text = "压缩中",
                style = MiuixTheme.textStyles.body2,
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                fontSize = 10.sp,
            )
        } else {
            Text(
                text = "${percent.roundToInt()}%",
                style = MiuixTheme.textStyles.body2,
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                fontSize = 10.sp,
                fontWeight = FontWeight.Medium,
            )
        }

        Spacer(modifier = Modifier.width(3.dp))

        Text(
            text = windowLabel,
            style = MiuixTheme.textStyles.body2,
            color = MiuixTheme.colorScheme.onSurfaceVariantActions,
            fontSize = 10.sp,
        )
    }
}

private fun formatContextWindowTokens(tokens: Int): String = when {
    tokens >= 1_000_000 -> "%.1fM".format(tokens / 1_000_000f)
    tokens >= 1_000 -> "${tokens / 1_000}K"
    else -> tokens.toString()
}
