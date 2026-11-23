package com.example.myapplication.ui

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.example.myapplication.ui.components.ColorSwatchOption
import com.example.myapplication.ui.components.ColorSwatchRow
import com.example.myapplication.ui.components.SettingItem
import com.example.myapplication.ui.theme.MyApplicationTheme

/** Internal debug-only screen for the real screen reader; never registered in release builds. */
class M01AccessibilityActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        check(android.os.Process.myUid()/100000 == 10) { "M01 fixture requires isolated Android user 10" }
        setContent { MyApplicationTheme {
            var selected by remember { mutableStateOf<String?>("yellow") }
            var calls by remember { mutableIntStateOf(0) }
            val options = listOf(ColorSwatchOption("yellow","#F6D76C","暖黄色"),ColorSwatchOption("green","#70AD8A","森林绿"),
                ColorSwatchOption("blue","#8CBDE4","天空蓝"),ColorSwatchOption("pink","#EAA5BA","玫瑰粉"),ColorSwatchOption("purple","#B6A1DA","淡紫色"))
            Surface(Modifier.fillMaxSize()) {
                Column(Modifier.padding(20.dp),verticalArrangement=Arrangement.spacedBy(16.dp)) {
                    Text("M01隔离无障碍验收",style=MaterialTheme.typography.titleLarge)
                    SettingItem("阅读偏好",{ calls++ },summary="一个入口，一个回调")
                    ColorSwatchRow(options,selected,{ selected=it;calls++ })
                    SettingItem("暂不可用",{ calls+=1000 },enabled=false)
                    Text("回调次数：$calls；当前：$selected")
                }
            }
        } }
    }
}
