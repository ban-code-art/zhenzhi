package com.example.myapplication.ui.knowledge.reader

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.view.Window
import android.view.accessibility.AccessibilityManager
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.example.myapplication.domain.reader.ReaderFlowMode

@Composable
internal fun ReflowReaderSettingsPanel(state: ReflowReaderRuntimeUiState, onChange:(ReaderSettings)->Unit,
    onDefaults:()->Unit,onInherit:()->Unit,onRetry:()->Unit,onClose:()->Unit,immersiveAvailable:Boolean) {
    val settings=state.settings
    val enabled=!state.closed && !state.readOnly
    var colorText by remember(settings.fontColor){mutableStateOf(settings.fontColor.encode())}
    AlertDialog(onDismissRequest=onClose,modifier=Modifier.testTag("reflow-settings-panel"),
        title={Text("阅读设置")},text={
            Column(Modifier.fillMaxWidth().heightIn(max=580.dp).verticalScroll(rememberScrollState()),
                verticalArrangement=Arrangement.spacedBy(8.dp)) {
                Text("字号 ${(settings.fontScale*100).toInt()}%")
                Slider(value=settings.fontScale,onValueChange={onChange(settings.copy(fontScale=it))},enabled=enabled,valueRange=.8f..1.8f,
                    modifier=Modifier.testTag("reflow-settings-font"))
                Text("行距 ${"%.2f".format(settings.lineHeight)}")
                Slider(value=settings.lineHeight,onValueChange={onChange(settings.copy(lineHeight=it))},enabled=enabled,valueRange=1.2f..2.2f)
                Text("页边距 ${settings.marginDp} dp")
                Slider(value=settings.marginDp.toFloat(),onValueChange={onChange(settings.copy(marginDp=it.toInt()))},enabled=enabled,valueRange=0f..64f)
                Row(horizontalArrangement=Arrangement.spacedBy(6.dp)) {
                    ReaderTheme.entries.forEach { theme -> FilterChip(selected=settings.theme==theme,enabled=enabled,
                        onClick={onChange(settings.copy(theme=theme))},label={Text(when(theme){ReaderTheme.LIGHT->"浅色";ReaderTheme.SEPIA->"护眼";ReaderTheme.NIGHT->"夜间"})}) }
                }
                ReaderFontColorPicker(value=settings.fontColor,theme=settings.theme,
                    onChange={onChange(settings.copy(fontColor=it))},enabled=enabled)
                OutlinedTextField(value=colorText,onValueChange={ text ->
                    colorText=text.take(7)
                    ReaderFontColor.parse(colorText.uppercase())?.let { onChange(settings.copy(fontColor=it)) }
                },enabled=enabled,label={Text("字体颜色 AUTO 或 #RRGGBB")},singleLine=true,
                    isError=ReaderFontColor.parse(colorText.uppercase())==null,modifier=Modifier.testTag("reflow-settings-color"))
                TextButton(onClick={onChange(settings.copy(fontColor=ReaderFontColor.Automatic))},enabled=enabled){Text("字体颜色随主题")}
                Row(horizontalArrangement=Arrangement.spacedBy(6.dp)) {
                    ReaderFlowMode.entries.forEach { mode -> FilterChip(selected=settings.flowMode==mode,enabled=enabled,
                        onClick={onChange(settings.copy(flowMode=mode))},label={Text(if(mode==ReaderFlowMode.SCROLL)"滚动阅读" else "分页阅读")}) }
                }
                SettingsSwitch("点击两侧翻页",settings.edgeTap,enabled){onChange(settings.copy(edgeTap=it))}
                SettingsSwitch("显示阅读位置",settings.pageIndicator,enabled){onChange(settings.copy(pageIndicator=it))}
                SettingsSwitch("沉浸阅读",settings.immersivePreference,enabled&&immersiveAvailable){onChange(settings.copy(immersivePreference=it))}
                Text(if(state.settingsInheritsDefaults)"继承全局默认偏好" else "此文档有独立偏好")
                Row {
                    TextButton(onClick=onDefaults,enabled=enabled&&!state.settingsSaving){Text("设为全局默认")}
                    TextButton(onClick=onInherit,enabled=enabled&&!state.settingsSaving){Text("恢复继承")}
                }
                if(state.settingsUnsupported!=null)Text("当前引擎未应用所选模式：${state.settingsUnsupported}",color=MaterialTheme.colorScheme.error)
                if(state.settingsApplyFailure!=null)Text("阅读布局未应用，仍显示上次布局；可重试。",color=MaterialTheme.colorScheme.error)
                if(state.settingsOutcomeUnknown || state.settingsFailure!=null)Text(
                    if(state.settingsOutcomeUnknown)"偏好保存结果待确认，修改已保留" else "偏好未保存，修改已保留",color=MaterialTheme.colorScheme.error)
                else if(state.settingsPending)Text(if(state.settingsSaving)"正在保存偏好" else "等待保存偏好")
                if(state.settingsFailure!=null || state.settingsOutcomeUnknown || state.settingsApplyFailure!=null)
                    TextButton(onClick=onRetry,enabled=!state.closed&&!state.settingsSaving&&state.settingsRetryable){Text("重试")}
            }
        },confirmButton={TextButton(onClick=onClose){Text("完成")}})
}

@Composable
private fun SettingsSwitch(label:String,value:Boolean,enabled:Boolean,onChange:(Boolean)->Unit) {
    Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceBetween){Text(label);Switch(checked=value,onCheckedChange=onChange,enabled=enabled)}
}

internal fun reflowReaderWindow(context:Context):Window? {
    var current=context
    val seen=java.util.Collections.newSetFromMap(java.util.IdentityHashMap<Context,Boolean>())
    while(seen.add(current)) { if(current is Activity)return current.window;current=(current as? ContextWrapper)?.baseContext ?: return null }
    return null
}

@Composable
internal fun rememberReflowAccessibility(context:Context):Boolean {
    val manager=remember(context){context.getSystemService(Context.ACCESSIBILITY_SERVICE) as? AccessibilityManager}
    var enabled by remember(manager){mutableStateOf(manager?.isTouchExplorationEnabled==true)}
    DisposableEffect(manager){
        val listener=AccessibilityManager.TouchExplorationStateChangeListener { enabled=it }
        manager?.addTouchExplorationStateChangeListener(listener)
        onDispose{manager?.removeTouchExplorationStateChangeListener(listener)}
    }
    return enabled
}
