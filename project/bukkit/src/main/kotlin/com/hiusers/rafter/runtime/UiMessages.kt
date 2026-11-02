package com.hiusers.rafter.runtime

import com.hiusers.rafter.api.UiFailureCode
import java.util.Locale
import org.bukkit.command.CommandSender
import taboolib.common.platform.ProxyCommandSender
import taboolib.module.lang.sendLang
import taboolib.platform.util.sendLang

/** 主线程发送本地化提示，不向玩家转发异常文本。 */
internal fun CommandSender.sendUiFailure(code: UiFailureCode) {
    val key = code.name.lowercase(Locale.ROOT).replace('_', '-')
    sendLang("ui-failure-$key")
}

/** 命令发送者与玩家共用失败提示。 */
internal fun ProxyCommandSender.sendUiFailure(code: UiFailureCode) {
    val key = code.name.lowercase(Locale.ROOT).replace('_', '-')
    sendLang("ui-failure-$key")
}
