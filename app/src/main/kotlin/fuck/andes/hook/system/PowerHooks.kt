package fuck.andes.hook.system

import fuck.andes.core.HookSupport
import fuck.andes.core.HookInstallation
import fuck.andes.core.HookRegistrar
import fuck.andes.core.ModuleConfig
import fuck.andes.core.ModuleLogger
import fuck.andes.core.safeLogType

import android.content.Context
import android.content.Intent
import android.os.Handler
import android.view.KeyEvent
import android.os.Message
import android.os.SystemClock
import fuck.andes.config.PowerAssistantTarget
import fuck.andes.config.Prefs
import io.github.libxposed.api.XposedInterface
import io.github.libxposed.api.XposedModule

internal object PowerHooks {
    private const val OEM_ASSISTANT_HAPTIC_EFFECT_ID = 0
    private const val OEM_ASSISTANT_HAPTIC_REASON = "Speech - Long Press"

    @Volatile
    private var lastInterceptUptime = 0L

    // ── 电源键长按判定:按键流方法只记录 down/up,长按成立后延迟启动助手 ──────
    @Volatile
    private var cachedPowerKeyContext: Context? = null

    @Volatile
    private var cachedPowerKeySelf: Any? = null

    @Volatile
    private var powerKeyDownUptime = 0L

    @Volatile
    private var powerKeyUpUptime = 0L

    @Volatile
    private var powerKeyCheckToken = 0L

    private val powerKeyHandler: Handler by lazy {
        Handler(android.os.HandlerThread("eta-power-key").apply { start() }.looper)
    }

    private fun notePowerKeyDown(logger: ModuleLogger) {
        val now = SystemClock.uptimeMillis()
        if (now - powerKeyDownUptime < 100L) return
        powerKeyDownUptime = now
        val token = ++powerKeyCheckToken
        powerKeyHandler.postDelayed(
            {
                if (token != powerKeyCheckToken) return@postDelayed
                if (powerKeyUpUptime > powerKeyDownUptime) return@postDelayed
                launchAssistantFromKeyEvent(logger)
            },
            ModuleConfig.POWER_KEY_LONG_PRESS_DELAY_MS,
        )
    }

    private fun notePowerKeyUp() {
        powerKeyUpUptime = SystemClock.uptimeMillis()
    }

    /**
     * 宽松长按判定:语音助手专用入口(系统判定长按后才调用)一般无需再判;
     * 但部分方法可能在按键瞬间被调用,用参数携带的 downTime 兜底,
     * 不足阈值(<400ms)直接放行,避免短按电源键误吞。
     */
    private fun isPowerKeyLongPressContext(chain: XposedInterface.Chain): Boolean {
        var downTime: Long? = null
        for (arg in chain.args) {
            var candidate: Long? = null
            if (arg is Long && arg > 1_000_000_000L) candidate = arg
            else if (arg is KeyEvent) candidate = arg.downTime
            if (candidate != null) {
                downTime = minOf(downTime ?: Long.MAX_VALUE, candidate)
            }
        }
        val down = downTime ?: return true
        return SystemClock.uptimeMillis() - down >= ModuleConfig.POWER_KEY_LONG_PRESS_DELAY_MS - 100L
    }

    private fun launchAssistantFromKeyEvent(logger: ModuleLogger) {
        val target = Prefs.powerAssistantTarget()
        val binding = assistantBindingFor(target)
        if (binding == null) return
        val context = cachedPowerKeyContext ?: return
        if (!HookSupport.isPackageInstalled(context, binding.packageName)) return
        logger.info("[PowerKeyEntry] 长按判定成立(≥${ModuleConfig.POWER_KEY_LONG_PRESS_DELAY_MS}ms), 启动 ${binding.displayName}")
        val now = SystemClock.uptimeMillis()
        if (now - lastInterceptUptime <= ModuleConfig.INTERCEPT_DEDUP_WINDOW_MS) return
        val fallbackSelf = cachedPowerKeySelf
        val launched = AssistantManager.showAssistantSession(
            context = context,
            target = target,
            logger = logger,
            source = "PowerKeyEntry",
            logFailures = true
        ) || (
            fallbackSelf != null && tryStartAssistantActivityFallback(
                target = target,
                binding = binding,
                logger = logger,
                phoneWindowManager = fallbackSelf,
                source = "PowerKeyEntry"
            )
            )
        if (launched) {
            finalizeSuccessfulLaunch(logger, fallbackSelf ?: context, "PowerKeyEntry", now)
            logger.info("[PowerKeyEntry] 长按启动成功")
        }
    }

    private enum class LaunchResult {
        LAUNCHED,
        ACTIVITY_FALLBACK_REQUIRED,
        NOT_HANDLED
    }

    fun install(
        module: XposedModule,
        rootLogger: ModuleLogger,
        classLoader: ClassLoader
    ): HookInstallation {
        val hooks = HookRegistrar(module, rootLogger, "Power")
        return hooks.install {
            hookPowerKeyAssistantEntry(hooks, classLoader)
            // 当前机型实测证明 OplusSpeechHandler 是必要路径，目标在热路径即时读取。
            hookOplusSpeechHandler(hooks, classLoader)
        }
    }

    private fun hookPowerKeyAssistantEntry(
        hooks: HookRegistrar,
        classLoader: ClassLoader
    ) {
        val logger = hooks.logger
        val ext = HookSupport.findClassOrNull(classLoader, "com.android.server.policy.PhoneWindowManagerExtImpl")
        val pwm = HookSupport.findClassOrNull(classLoader, "com.android.server.policy.PhoneWindowManager")
        // ── 语音助手专用入口:系统判定长按后才调用,接管成功直接吞掉 ──────────
        val assistEntries = mutableListOf<Triple<String, String, java.lang.reflect.Method>>()
        ext?.let { c ->
            HookSupport.findMethod(c, "launchAssistGoogleSpeechAssistantAction", Message::class.java)?.let {
                assistEntries += Triple(
                    "system.power-assist-entry.launch",
                    "PhoneWindowManagerExtImpl.launchAssistGoogleSpeechAssistantAction(Message)",
                    it
                )
            }
            HookSupport.findMethod(c, "oplusInterceptLongPowerPress")?.let {
                assistEntries += Triple(
                    "system.power-assist-entry.longpress",
                    "PhoneWindowManagerExtImpl.oplusInterceptLongPowerPress()",
                    it
                )
            }
            HookSupport.findMethod(c, "sendSpeechMessage", java.lang.Long::class.java)?.let {
                assistEntries += Triple(
                    "system.power-assist-entry.speech-message",
                    "PhoneWindowManagerExtImpl.sendSpeechMessage(Long)",
                    it
                )
            }
            HookSupport.findMethod(c, "startSpeech", Int::class.javaPrimitiveType!!, java.lang.Long::class.java)?.let {
                assistEntries += Triple(
                    "system.power-assist-entry.speech-start",
                    "PhoneWindowManagerExtImpl.startSpeech(int,Long)",
                    it
                )
            }
            HookSupport.findMethod(
                c, "startSpeech",
                Int::class.javaPrimitiveType!!, Boolean::class.javaPrimitiveType!!, java.lang.Long::class.java
            )?.let {
                assistEntries += Triple(
                    "system.power-assist-entry.speech-start2",
                    "PhoneWindowManagerExtImpl.startSpeech(int,boolean,Long)",
                    it
                )
            }
        }
        pwm?.let { c ->
            HookSupport.findMethod(
                c, "launchAssistAction",
                String::class.java, Int::class.javaPrimitiveType!!,
                Long::class.javaPrimitiveType!!, Int::class.javaPrimitiveType!!, Int::class.javaPrimitiveType!!
            )?.let {
                assistEntries += Triple(
                    "system.power-assist-entry.action",
                    "PhoneWindowManager.launchAssistAction(String,int,long,int,int)",
                    it
                )
            }
        }
        // 本机 ExtImpl 的语音助手消息可能由内部 Handler 类处理（mAsynHandler），逐一挂上 handleMessage。
        ext?.declaredClasses?.forEach { inner ->
            val handleMessage = HookSupport.findMethod(inner, "handleMessage", Message::class.java) ?: return@forEach
            assistEntries += Triple(
                "system.power-assist-entry.inner",
                "${inner.name}.handleMessage(Message)",
                handleMessage
            )
        }
        if (assistEntries.isNotEmpty()) {
            assistEntries.forEach { (id, description, method) ->
                hooks.intercept(id = id, executable = method, description = description) { chain ->
                    if (!isPowerKeyLongPressContext(chain)) {
                        return@intercept chain.proceed()
                    }
                    tryLaunchAssistantFromPowerEntry(chain, logger)
                }
            }
        } else {
            hooks.missing(
                id = "system.power-assist-entry",
                description = "长按电源键→助手入口",
                detail = "未找到任何可拦截的助手启动方法"
            )
        }
        // ── 电源键事件流方法:每次按键都调用,只做长按判定,永不吞返回值 ──────
        // 短按(<500ms)放行;长按成立后启动 Eta,但系统原逻辑(3 秒关机菜单等)完整保留。
        val keyEventMethods: List<Pair<String, Array<Class<*>>>> = listOf(
            "interceptPowerKeyDown" to emptyArray(),
            "interceptPowerKeyUp" to emptyArray(),
            "oplusInterceptPowerKeyDown" to arrayOf(KeyEvent::class.java, Boolean::class.javaPrimitiveType!!),
            "oplusInterceptPowerKeyUp" to arrayOf(Boolean::class.javaPrimitiveType!!),
            "oplusPowerPress" to arrayOf(Long::class.javaPrimitiveType!!, Boolean::class.javaPrimitiveType!!, Int::class.javaPrimitiveType!!),
            "enqueuePowerKeyDownEvent" to arrayOf(Long::class.javaPrimitiveType!!),
            "correctPowerKeyEventLocked" to arrayOf(Long::class.javaPrimitiveType!!),
        )
        ext?.let { c ->
            keyEventMethods.forEach { (name, params) ->
                val m = HookSupport.findMethod(c, name, *params) ?: return@forEach
                val isDown = !name.endsWith("Up") && name != "correctPowerKeyEventLocked"
                hooks.intercept(
                    id = "system.power-assist-entry.power.$name",
                    executable = m,
                    description = "PhoneWindowManagerExtImpl.$name(${params.joinToString(",") { it.simpleName }})"
                ) { chain ->
                    val self = chain.getThisObject()
                    val context = HookSupport.getFieldValue(self, "mContext") as? Context
                    if (context != null) {
                        cachedPowerKeyContext = context
                        cachedPowerKeySelf = self
                    }
                    if (isDown) {
                        notePowerKeyDown(logger)
                    } else {
                        notePowerKeyUp()
                    }
                    chain.proceed()
                }
            }
        }
    }

    private fun tryLaunchAssistantFromPowerEntry(
        chain: XposedInterface.Chain,
        logger: ModuleLogger
    ): Any? {
        val target = Prefs.powerAssistantTarget()
        val binding = assistantBindingFor(target)
        if (binding == null) {
            logger.info("[PowerKeyEntry] 无绑定目标 target=$target, 放行")
            return chain.proceed()
        }
        val self = chain.getThisObject() ?: return chain.proceed()
        val context = HookSupport.getFieldValue(self, "mContext") as? Context
            ?: return chain.proceed()
        if (!HookSupport.isPackageInstalled(context, binding.packageName)) {
            logger.info("[PowerKeyEntry] ${binding.packageName} 未安装, 放行")
            return chain.proceed()
        }
        logger.info("[PowerKeyEntry] 入口触发 target=$target, 尝试接管")
        val now = SystemClock.uptimeMillis()
        if (now - lastInterceptUptime <= ModuleConfig.INTERCEPT_DEDUP_WINDOW_MS) {
            logger.debug { "电源键入口: 命中去重窗口，吞掉重复触发" }
            return null
        }
        if (AssistantManager.showAssistantSession(
                context = context,
                target = target,
                logger = logger,
                source = "PowerKeyEntry",
                logFailures = true
            )
        ) {
            finalizeSuccessfulLaunch(logger, self, "PowerKeyEntry", now)
            logger.info("[PowerKeyEntry] voiceinteraction 启动成功, 已吞掉本次触发")
            return null
        }
        if (tryStartAssistantActivityFallback(
                target = target,
                binding = binding,
                logger = logger,
                phoneWindowManager = self,
                source = "PowerKeyEntry"
            )
        ) {
            logger.info("[PowerKeyEntry] Activity 兜底启动成功, 已吞掉本次触发")
            return null
        }
        logger.info("[PowerKeyEntry] 启动失败, 放行原逻辑")
        return chain.proceed()
    }

    private fun hookOplusSpeechHandler(
        hooks: HookRegistrar,
        classLoader: ClassLoader
    ) {
        val logger = hooks.logger
        val handlerClass = HookSupport.findClassOrNull(classLoader, ModuleConfig.OP_LUS_SPEECH_HANDLER_CLASS)
        val handleMessageMethod = handlerClass?.let {
            HookSupport.findMethod(it, "handleMessage", Message::class.java)
        }
        if (handleMessageMethod == null) {
            hooks.missing(
                id = "system.power-assist-message",
                description = "OplusSpeechHandler.handleMessage",
                detail = "未找到 OplusSpeechHandler.handleMessage(Message)"
            )
            return
        }

        hooks.intercept(
            id = "system.power-assist-message",
            executable = handleMessageMethod,
            description = "PhoneWindowManagerExtImpl\$OplusSpeechHandler.handleMessage"
        ) { chain ->
            val message = chain.getArg(0) as? Message
            if (message?.what != ModuleConfig.OP_LUS_ASSIST_MESSAGE_WHAT) {
                return@intercept chain.proceed()
            }

            val target = Prefs.powerAssistantTarget()
            val binding = assistantBindingFor(target)
            if (binding == null) {
                return@intercept chain.proceed()
            }

            val handler = chain.getThisObject() as? Handler
            val pwm = resolvePhoneWindowManager(chain.getThisObject())
            if (pwm == null) {
                logger.warnThrottled("oplus_speech_missing_pwm") {
                    "OplusSpeechHandler 未能解析 PhoneWindowManager，回退原逻辑"
                }
                return@intercept chain.proceed()
            }

            when (tryLaunchAssistant(
                target = target,
                binding = binding,
                logger = logger,
                phoneWindowManager = pwm,
                source = "OplusSpeechHandler"
            )) {
                LaunchResult.LAUNCHED -> null
                LaunchResult.ACTIVITY_FALLBACK_REQUIRED -> {
                    val activityStarted = tryStartAssistantActivityFallback(
                        target = target,
                        binding = binding,
                        logger = logger,
                        phoneWindowManager = pwm,
                        source = "OplusSpeechHandler"
                    )
                    // Activity 兜底能处理本次触发，但仍需后台修复首选 voiceinteraction 路径。
                    scheduleBackgroundRecovery(
                        handler = handler,
                        logger = logger,
                        phoneWindowManager = pwm,
                        source = "OplusSpeechHandler"
                    )
                    if (activityStarted) {
                        null
                    } else {
                        // 当前触发不等待后台修复；所有快速路径失败后立即回退小布。
                        chain.proceed()
                    }
                }
                LaunchResult.NOT_HANDLED -> chain.proceed()
            }
        }
    }

    private fun tryLaunchAssistant(
        target: PowerAssistantTarget,
        binding: AssistantBinding,
        logger: ModuleLogger,
        phoneWindowManager: Any,
        source: String
    ): LaunchResult {
        val context = HookSupport.getFieldValue(phoneWindowManager, "mContext") as? Context
        if (context == null) {
            logger.warnThrottled("${source}_missing_context") {
                "$source 缺少 mContext，回退原逻辑"
            }
            return LaunchResult.NOT_HANDLED
        }

        if (!HookSupport.isPackageInstalled(context, binding.packageName)) {
            logger.warnThrottled("${source}_${target.persistedValue}_missing") {
                "$source: ${binding.displayName} 未安装，回退原逻辑"
            }
            return LaunchResult.NOT_HANDLED
        }

        val now = SystemClock.uptimeMillis()
        if (now - lastInterceptUptime <= ModuleConfig.INTERCEPT_DEDUP_WINDOW_MS) {
            logger.debug { "$source: 命中去重窗口，直接吞掉重复触发" }
            return LaunchResult.LAUNCHED
        }

        if (AssistantManager.showAssistantSession(
                context = context,
                target = target,
                logger = logger,
                source = source,
                logFailures = false
            )) {
            finalizeSuccessfulLaunch(logger, phoneWindowManager, source, now)
            logger.debug { "$source: 已通过 voiceinteraction 启动 ${binding.displayName}" }
            return LaunchResult.LAUNCHED
        }

        return LaunchResult.ACTIVITY_FALLBACK_REQUIRED
    }

    private fun tryStartAssistantActivityFallback(
        target: PowerAssistantTarget,
        binding: AssistantBinding,
        logger: ModuleLogger,
        phoneWindowManager: Any,
        source: String
    ): Boolean {
        val context = HookSupport.getFieldValue(phoneWindowManager, "mContext") as? Context
            ?: return false
        val now = SystemClock.uptimeMillis()
        return when (target) {
            PowerAssistantTarget.OEM -> false
            PowerAssistantTarget.GEMINI -> startAssistantActivity(
                context = context,
                binding = binding,
                logger = logger,
                phoneWindowManager = phoneWindowManager,
                source = source,
                now = now,
                action = Intent.ACTION_ASSIST,
            ) || startAssistantActivity(
                context = context,
                binding = binding,
                logger = logger,
                phoneWindowManager = phoneWindowManager,
                source = source,
                now = now,
                action = Intent.ACTION_VOICE_COMMAND,
            )
            PowerAssistantTarget.ETA -> {
                if (!AssistantManager.isAssistantConfigured(context, target)) {
                    false
                } else {
                    startAssistantActivity(
                        context = context,
                        binding = binding,
                        logger = logger,
                        phoneWindowManager = phoneWindowManager,
                        source = source,
                        now = now,
                        action = Intent.ACTION_ASSIST,
                    )
                }
            }
        }
    }

    private fun startAssistantActivity(
        context: Context,
        binding: AssistantBinding,
        logger: ModuleLogger,
        phoneWindowManager: Any,
        source: String,
        now: Long,
        action: String
    ): Boolean {
        val intent = Intent(action).apply {
            setPackage(binding.packageName)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        val resolves = runCatching { HookSupport.resolvesActivity(context, intent) }
            .getOrElse { throwable ->
                logger.warnThrottled("${source}_${action}_resolve_failed") {
                    "$source: 查询 ${binding.displayName} $action 入口失败，" +
                        "type=${throwable.safeLogType()}"
                }
                false
            }
        if (!resolves) {
            logger.warnThrottled("${source}_${action}_missing") {
                "$source: ${binding.displayName} 未暴露 $action，回退原逻辑"
            }
            return false
        }

        return runCatching {
            context.startActivity(intent)
            finalizeSuccessfulLaunch(logger, phoneWindowManager, source, now)
            logger.debug { "$source: 已通过 $action 启动 ${binding.displayName}" }
            true
        }.getOrElse { throwable ->
            logger.warnThrottled("${source}_${action}_failed") {
                "$source: $action 启动失败，回退原逻辑，type=${throwable.safeLogType()}"
            }
            false
        }
    }

    private fun finalizeSuccessfulLaunch(
        logger: ModuleLogger,
        phoneWindowManager: Any,
        source: String,
        now: Long
    ) {
        markLaunchSuccess(now)
        maybePerformAssistantHapticFeedback(logger, phoneWindowManager, source)
    }

    private fun maybePerformAssistantHapticFeedback(
        logger: ModuleLogger,
        phoneWindowManager: Any,
        source: String
    ) {
        if (invokeOplusAssistantHapticFeedback(phoneWindowManager)) {
            logger.debug { "$source: 已补发 Oplus 原生助理震感" }
            return
        }

        logger.warnThrottled("${source}_assistant_haptic_missing") {
            "$source: 未找到 Oplus 原生长按助理震感入口"
        }
    }

    private fun invokeOplusAssistantHapticFeedback(phoneWindowManager: Any): Boolean {
        val wrapper = HookSupport.invokeNoArgs(phoneWindowManager, "getWrapper") ?: return false
        val wrapperMethod = HookSupport.findMethod(
            wrapper.javaClass,
            "performHapticFeedback",
            Int::class.javaPrimitiveType!!,
            String::class.java
        ) ?: return false
        return runCatching {
            wrapperMethod.invoke(wrapper, OEM_ASSISTANT_HAPTIC_EFFECT_ID, OEM_ASSISTANT_HAPTIC_REASON)
            true
        }.getOrDefault(false)
    }

    private fun scheduleBackgroundRecovery(
        handler: Handler?,
        logger: ModuleLogger,
        phoneWindowManager: Any,
        source: String
    ) {
        if (!Prefs.isEnabled(Prefs.Keys.ASSISTANT_AUTO_CONFIG) ||
            Prefs.powerAssistantTarget() == PowerAssistantTarget.OEM
        ) {
            return
        }
        if (handler == null) {
            logger.warnThrottled("${source}_recovery_missing_handler") {
                "$source: 无法取得 OplusSpeechHandler 实例，跳过后台配置修复"
            }
            return
        }

        val context = HookSupport.getFieldValue(phoneWindowManager, "mContext") as? Context
        if (context == null) {
            logger.warnThrottled("${source}_recovery_missing_context") {
                "$source: 无法取得 mContext，跳过后台配置修复"
            }
            return
        }

        val scheduled = AssistantManager.scheduleAssistantRecovery(
            context = context,
            logger = logger,
            handler = handler,
            forceRefresh = true,
        )
        if (!scheduled) {
            logger.warnThrottled("${source}_configuration_schedule_failed") {
                "$source: 默认助理后台修复无法入队"
            }
        } else {
            logger.warnThrottled("${source}_assistant_recovery_pending") {
                "$source: voiceinteraction 失败，已在后台修复默认助理配置"
            }
        }
    }

    private fun markLaunchSuccess(now: Long) {
        lastInterceptUptime = now
    }

    private fun resolvePhoneWindowManager(handlerInstance: Any): Any? {
        val owner = HookSupport.getFieldValue(handlerInstance, "this$0") ?: return null
        HookSupport.findField(owner.javaClass, "mPhoneWindowManager")?.let { field ->
            return runCatching { field.get(owner) }.getOrNull()
        }

        var current: Class<*>? = owner.javaClass
        while (current != null) {
            current.declaredFields.forEach { field ->
                if (field.type.name == ModuleConfig.PHONE_WINDOW_MANAGER_CLASS) {
                    field.isAccessible = true
                    return runCatching { field.get(owner) }.getOrNull()
                }
            }
            current = current.superclass
        }
        return null
    }
}
