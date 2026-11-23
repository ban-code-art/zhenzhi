package com.example.myapplication.ui.mine

import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccountCircle
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Face
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.ImageLoader
import coil3.compose.SubcomposeAsyncImage
import coil3.svg.SvgDecoder
import com.example.myapplication.data.avatar.AvatarStorage
import com.example.myapplication.ui.components.UiAction
import com.example.myapplication.ui.components.UiAlertDecision
import com.example.myapplication.ui.components.UiAlertHost
import com.example.myapplication.ui.theme.UiIcons
import com.example.myapplication.ui.theme.UiRoles

data class AvatarOption(
    val id: Int,
    val icon: ImageVector,
    val tint: Color,
    val background: Color,
    val assetName: String? = null,
    val previewColorHex: String? = null,
    val label: String = "",
)

val LegacyAvatarOptions = listOf(
    AvatarOption(1, Icons.Filled.Person, Color(0xFF3F8F76), Color(0xFFCFE9DE)),
    AvatarOption(2, Icons.Filled.Face, Color(0xFF5B8DEF), Color(0xFFE8F0FE)),
    AvatarOption(3, Icons.Filled.Star, Color(0xFFE8894C), Color(0xFFFCEFE0)),
    AvatarOption(4, Icons.Filled.Favorite, Color(0xFFD9534F), Color(0xFFFDECEA)),
    AvatarOption(5, Icons.Filled.AccountCircle, Color(0xFF8A6FD1), Color(0xFFEFEAFB)),
    AvatarOption(6, Icons.Filled.CheckCircle, Color(0xFF4FA98C), Color(0xFFE4F3EC)),
)

val CartoonAvatarOptions = listOf(
    cartoon(101, "草莓团子", "avatar_101_moods_momo.svg", "#E11D48", 0xFFE11D48),
    cartoon(102, "珊瑚花猫", "avatar_102_cats_berry_cat.svg", "#FF8FA3", 0xFFFF8FA3),
    cartoon(103, "樱花眨眼猫", "avatar_103_cats_mochi_cat.svg", "#FFD6E0", 0xFFFFD6E0),
    cartoon(104, "蜜桃橘猫", "avatar_104_cats_yuzu.svg", "#FDBA74", 0xFFFDBA74),
    cartoon(105, "柠檬笑猫", "avatar_105_cats_lemon_cat.svg", "#FEF08A", 0xFFFEF08A),
    cartoon(106, "青柠小猫", "avatar_106_cats_kiwi.svg", "#CAFFBF", 0xFFCAFFBF),
    cartoon(107, "薄荷仙人掌", "avatar_107_sprouts_sprout_mint.svg", "#A7F3D0", 0xFFA7F3D0),
    cartoon(108, "翡翠领结犬", "avatar_108_dogs_clover_pup.svg", "#6EE7B7", 0xFF6EE7B7),
    cartoon(109, "碧波笑团子", "avatar_109_moods_mood_sun.svg", "#06B6D4", 0xFF06B6D4),
    cartoon(110, "晴空卷毛犬", "avatar_110_dogs_blueberry_pup.svg", "#7DD3FC", 0xFF7DD3FC),
    cartoon(111, "矢车菊花盆", "avatar_111_sprouts_yuzu.svg", "#818CF8", 0xFF818CF8),
    cartoon(112, "星夜独眼怪", "avatar_112_critters_yuzu.svg", "#4338CA", 0xFF4338CA),
    cartoon(113, "紫藤四眼怪", "avatar_113_critters_momo.svg", "#8B5CF6", 0xFF8B5CF6),
    cartoon(114, "香芋睡眼犬", "avatar_114_dogs_plum_pup.svg", "#D8B4FE", 0xFFD8B4FE),
    cartoon(115, "丁香仙人掌", "avatar_115_sprouts_boba.svg", "#F5D0FE", 0xFFF5D0FE),
    cartoon(116, "云雾小栗子", "avatar_116_clay_peach.svg", "#CBD5E1", 0xFFCBD5E1),
)

/** Keep the old public list usable while the picker exposes only the new cartoon catalog. */
val AvatarOptions: List<AvatarOption> = LegacyAvatarOptions + CartoonAvatarOptions

private fun cartoon(id: Int, label: String, asset: String, hex: String, background: Long) =
    AvatarOption(id, Icons.Filled.Person, Color.Unspecified, Color(background), asset, hex, label)

private object AvatarImages {
    @Volatile private var loader: ImageLoader? = null

    fun get(context: Context): ImageLoader = loader ?: synchronized(this) {
        loader ?: ImageLoader.Builder(context.applicationContext)
            .components { add(SvgDecoder.Factory()) }
            .build()
            .also { loader = it }
    }
}

/** Shared local decoder/cache; no network fetcher dependency is needed for either source. */
internal fun avatarImageLoader(context: Context): ImageLoader = AvatarImages.get(context)

@Composable
fun UserAvatar(
    avatarId: Int,
    name: String?,
    size: Dp = 76.dp,
    avatarPath: String? = null,
    modifier: Modifier = Modifier,
    username: String? = null,
) {
    val option = AvatarOptions.firstOrNull { it.id == avatarId }
    val context = LocalContext.current
    val imageLoader = remember(context.applicationContext) { avatarImageLoader(context) }
    val file = remember(context, avatarPath) {
        avatarPath?.takeIf { it.isNotBlank() }?.let { AvatarStorage.resolve(context, it) }
    }
    val initial = remember(name, username) {
        val text = name?.trim()?.takeIf { it.isNotEmpty() }
            ?: username?.trim()?.takeIf { it.isNotEmpty() } ?: "我"
        String(Character.toChars(text.codePointAt(0)))
    }
    Box(
        modifier = modifier
            .size(size)
            .clip(CircleShape)
            .background(UiRoles.iconContainer)
            .border(.5.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = .35f), CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        if (file != null) {
            SubcomposeAsyncImage(
                model = file,
                imageLoader = imageLoader,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
                loading = { AvatarFallback(option, initial, size, imageLoader) },
                error = { AvatarFallback(option, initial, size, imageLoader) },
            )
        } else {
            AvatarFallback(option, initial, size, imageLoader)
        }
    }
}

@Composable
private fun AvatarFallback(option: AvatarOption?, initial: String, size: Dp, imageLoader: ImageLoader) {
    val asset = option?.assetName
    when {
        asset != null -> SubcomposeAsyncImage(
            model = "file:///android_asset/avatars/$asset",
            imageLoader = imageLoader,
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = Modifier.fillMaxSize(),
            loading = { InitialAvatar(initial, size) },
            error = { InitialAvatar(initial, size) },
        )
        option != null -> Box(
            Modifier.fillMaxSize().background(option.background),
            contentAlignment = Alignment.Center,
        ) {
            Icon(option.icon, contentDescription = null, tint = option.tint, modifier = Modifier.size(size * .5f))
        }
        else -> InitialAvatar(initial, size)
    }
}

@Composable
private fun InitialAvatar(initial: String, size: Dp) {
    Box(Modifier.fillMaxSize().background(UiRoles.iconContainer), contentAlignment = Alignment.Center) {
        Text(
            text = initial,
            fontSize = (size.value * .36f).sp,
            fontWeight = FontWeight.Bold,
            color = UiRoles.iconTint,
            fontFamily = MaterialTheme.typography.bodyLarge.fontFamily,
        )
    }
}

@Composable
fun AvatarPickerDialog(
    selectedId: Int,
    onSelect: (Int) -> Unit,
    onDismiss: () -> Unit,
    hasCustomAvatar: Boolean = false,
    name: String? = "我",
    username: String? = null,
    enabled: Boolean = true,
) {
    val owner = remember { Any() }
    val decision = remember(owner) { UiAlertDecision(owner) }
    val exit = decision.exit
    val colors = MaterialTheme.colorScheme
    val fontScale = LocalDensity.current.fontScale
    UiAlertHost(
        title = "选择头像",
        ownerKey = owner,
        onDismissRequest = { scale, pull -> decision.request(false, scale, pull) },
        exitRequested = exit != null,
        frozenScale = exit?.scale,
        frozenPull = exit?.pull,
        onVisibilitySettled = { if (decision.consume(owner) == false) onDismiss() },
        modifier = Modifier.testTag("avatar-picker-dialog"),
    ) { _, currentScale, currentPull ->
        Text(
            text = "选择头像",
            style = UiRoles.text(UiRoles.pageTitle),
            color = UiRoles.foreground,
            modifier = Modifier.semantics { heading() },
        )
        Text(
            text = "点击挑选喜欢的卡通头像，或恢复默认首字",
            style = UiRoles.text(MaterialTheme.typography.bodyMedium),
            color = colors.onSurfaceVariant,
        )
        Spacer(Modifier.height(14.dp))
        BoxWithConstraints(Modifier.fillMaxWidth()) {
            val columns = if (maxWidth < 280.dp || fontScale >= 1.6f) 3 else 4
            LazyVerticalGrid(
                columns = GridCells.Fixed(columns),
                modifier = Modifier.fillMaxWidth().heightIn(min = 64.dp, max = 340.dp)
                    .selectableGroup().testTag("avatar-picker-grid"),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                item(key = 0) {
                    AvatarChoice(
                        avatarId = 0,
                        name = name,
                        username = username,
                        label = "默认首字",
                        isSelected = selectedId == 0 && !hasCustomAvatar,
                        enabled = enabled && exit == null,
                        onClick = { onSelect(0) },
                    )
                }
                items(CartoonAvatarOptions, key = { it.id }) { option ->
                    AvatarChoice(
                        avatarId = option.id,
                        name = null,
                        username = null,
                        label = option.label,
                        isSelected = selectedId == option.id && !hasCustomAvatar,
                        enabled = enabled && exit == null,
                        onClick = { onSelect(option.id) },
                    )
                }
            }
        }
        Spacer(Modifier.height(12.dp))
        UiAction(
            label = "完成",
            onClick = { decision.request(false, currentScale(), currentPull()) },
            modifier = Modifier.align(Alignment.End).testTag("avatar-picker-done"),
            enabled = exit == null,
            inheritedPane = true,
            foreground = UiRoles.accent,
            actionId = "avatar-picker.done",
            animatePress = false,
            keepEnabledStyle = true,
        )
    }
}

@Composable
private fun AvatarChoice(
    avatarId: Int,
    name: String?,
    username: String?,
    label: String,
    isSelected: Boolean,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    Box(
        modifier = Modifier.fillMaxWidth().height(64.dp)
            .selectable(selected = isSelected, enabled = enabled, role = Role.RadioButton, onClick = onClick)
            .semantics {
                contentDescription = label
                selected = isSelected
                stateDescription = if (isSelected) "已选中" else "未选中"
            }
            .testTag("avatar-choice-$avatarId"),
        contentAlignment = Alignment.Center,
    ) {
        Box(Modifier.size(64.dp), contentAlignment = Alignment.Center) {
            Box(
                Modifier.size(61.dp).border(
                    if (isSelected) 2.5.dp else 1.dp,
                    if (isSelected) colors.primary else colors.outlineVariant.copy(alpha = .45f),
                    CircleShape,
                ),
            )
            UserAvatar(
                avatarId = avatarId,
                name = name,
                username = username,
                size = 52.dp,
                modifier = Modifier.clearAndSetSemantics {},
            )
            if (isSelected) {
                Box(
                    Modifier.align(Alignment.BottomEnd).size(18.dp).background(colors.primary, CircleShape),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(UiIcons.Check, contentDescription = null, tint = colors.onPrimary, modifier = Modifier.size(12.dp))
                }
            }
        }
    }
}
