package com.example.myapplication.ui.mine

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.myapplication.data.local.entity.UserEntity
import com.example.myapplication.ui.components.LocalMainDockBottomInset
import com.example.myapplication.ui.components.SettingItem
import com.example.myapplication.ui.theme.UiIcons
import com.example.myapplication.ui.theme.UiRoles
import com.example.myapplication.ui.theme.UiSurfaceRole

@Composable
fun MineScreen(
    onNavigate: (MineDestination) -> Unit,
) {
    val context = LocalContext.current
    val factory = remember { MineViewModelFactory(context.applicationContext) }
    val viewModel: MineViewModel = viewModel(factory = factory)
    val state by viewModel.state.collectAsState()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(UiRoles.surface(UiSurfaceRole.Page))
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp),
    ) {
        Spacer(Modifier.height(16.dp))
        ProfileHeader(
            user = state.user,
            onClick = { onNavigate(MineDestination.EditProfile) },
        )
        Spacer(Modifier.height(16.dp))
        MineLearningDaysCard(state)
        Spacer(Modifier.height(16.dp))
        MenuCard(onNavigate = onNavigate)
        Spacer(Modifier.height(32.dp + LocalMainDockBottomInset.current))
    }
}

@Composable
private fun ProfileHeader(user: UserEntity?, onClick: () -> Unit) {
    Surface(
        color = UiRoles.surface(UiSurfaceRole.Card),
        shape = RoundedCornerShape(24.dp),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 104.dp)
                .clickable(role = Role.Button, onClick = onClick)
                .testTag("mine-profile-entry")
                .padding(horizontal = 16.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            UserAvatar(
                avatarId = user?.avatarId ?: 0,
                name = user?.nickname?.takeIf { it.isNotBlank() } ?: user?.username,
                size = 76.dp,
                avatarPath = user?.avatarPath,
            )
            Spacer(Modifier.width(16.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    text = user?.nickname?.takeIf { it.isNotBlank() } ?: user?.username ?: "未登录",
                    style = UiRoles.text(MaterialTheme.typography.headlineSmall),
                    fontWeight = FontWeight.Bold,
                    color = TextPrimary,
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    text = "针灸学习者",
                    style = UiRoles.text(MaterialTheme.typography.bodyMedium),
                    color = TextSecondary,
                )
            }
            Box(Modifier.width(36.dp), contentAlignment = Alignment.CenterEnd) {
                Icon(
                    imageVector = UiIcons.Chevron,
                    contentDescription = null,
                    modifier = Modifier.size(20.dp),
                    tint = TextSecondary,
                )
            }
        }
    }
}

/**
 * 学习日卡片：两行四格（学习天数 / 连续打卡 / 阅读天数 / 随堂测天数）。
 *
 * 数值全部来自 [MineUiState]（MineViewModel 已按 learning_activity_days 计算）；
 * 这里只负责展示，任何维度没有数据就显示 0，不补数、不隐藏。
 */
@Composable
internal fun MineLearningDaysCard(state: MineUiState) {
    val stats = mineStatsOf(state)
    val fontScale = LocalDensity.current.fontScale
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(24.dp),
        color = UiRoles.surface(UiSurfaceRole.Card),
    ) {
        BoxWithConstraints(Modifier.fillMaxWidth().padding(16.dp)) {
            val singleColumn = maxWidth < 312.dp || fontScale >= 1.6f
            Column(Modifier.fillMaxWidth()) {
                val rows = stats.chunked(if (singleColumn) 1 else 2)
                rows.forEachIndexed { rowIndex, rowStats ->
                    if (rowIndex > 0) {
                        HorizontalDivider(
                            modifier = Modifier.padding(vertical = 12.dp),
                            thickness = 0.5.dp,
                            color = HairlineDivider,
                        )
                    }
                    Row(
                        modifier = Modifier.fillMaxWidth().height(IntrinsicSize.Min),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        rowStats.forEachIndexed { index, stat ->
                            if (index > 0) {
                                VerticalDivider(
                                    modifier = Modifier.fillMaxHeight().padding(horizontal = 8.dp),
                                    thickness = 0.5.dp,
                                    color = HairlineDivider,
                                )
                            }
                            StatItem(stat = stat, modifier = Modifier.weight(1f))
                        }
                    }
                }
            }
        }
    }
}

/** 学习日卡片的图标/配色（按维度固定，避免同一数字在不同版本换语义）。 */
private data class StatStyle(
    val icon: ImageVector,
    val tint: Color,
    val background: Color,
)

@Composable
private fun statStyleOf(kind: MineStatKind): StatStyle = when (kind) {
    MineStatKind.LEARNED_DAYS -> StatStyle(UiIcons.Progress, UiRoles.iconTint, UiRoles.iconContainer)
    MineStatKind.STREAK_DAYS -> StatStyle(UiIcons.Favorite, UiRoles.iconTint, UiRoles.iconContainer)
    MineStatKind.READING_DAYS -> StatStyle(UiIcons.Records, UiRoles.iconTint, UiRoles.iconContainer)
    MineStatKind.QUIZ_DAYS -> StatStyle(UiIcons.Quiz, UiRoles.iconTint, UiRoles.iconContainer)
}

@Composable
private fun StatItem(
    stat: MineStatUi,
    modifier: Modifier = Modifier,
) {
    val style = statStyleOf(stat.kind)
    Row(
        modifier = modifier
            .padding(vertical = 4.dp)
            .semantics(mergeDescendants = true) {}
            .testTag("mine-stat-${stat.kind.name}"),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(40.dp)
                .clip(RoundedCornerShape(14.dp))
                .background(style.background),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = style.icon,
                contentDescription = null,
                tint = style.tint,
                modifier = Modifier.size(20.dp),
            )
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(
                text = stat.value,
                style = UiRoles.text(MaterialTheme.typography.headlineSmall)
                    .copy(fontFeatureSettings = "tnum"),
                fontWeight = FontWeight.Bold,
                color = TextPrimary,
            )
            Text(
                text = stat.label,
                style = UiRoles.text(MaterialTheme.typography.bodySmall),
                color = TextSecondary,
            )
        }
    }
}

@Composable
private fun MenuCard(
    onNavigate: (MineDestination) -> Unit,
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(24.dp),
        color = UiRoles.surface(UiSurfaceRole.Card),
    ) {
        Column(Modifier.fillMaxWidth()) {
            SettingItem(
                icon = UiIcons.Books,
                title = "我的课程",
                modifier = Modifier.testTag("mine-study-progress-entry"),
                onClick = { onNavigate(MineDestination.StudyProgress) },
            )
            MenuDivider()
            SettingItem(
                icon = UiIcons.Favorite,
                title = "收藏题目",
                modifier = Modifier.testTag("mine-favorites-entry"),
                onClick = { onNavigate(MineDestination.Favorites) },
            )
            MenuDivider()
            SettingItem(
                icon = UiIcons.Records,
                title = "学习记录",
                modifier = Modifier.testTag("mine-scores-entry"),
                onClick = { onNavigate(MineDestination.Scores) },
            )
            MenuDivider()
            SettingItem(
                icon = UiIcons.Quiz,
                title = "随堂测记录",
                modifier = Modifier.testTag("mine-quiz-history-entry"),
                onClick = { onNavigate(MineDestination.QuizHistory) },
            )
            MenuDivider()
            SettingItem(
                icon = UiIcons.WrongQuestions,
                title = "错题本",
                modifier = Modifier.testTag("mine-wrong-questions-entry"),
                onClick = { onNavigate(MineDestination.WrongQuestions) },
            )
            MenuDivider()
            SettingItem(
                icon = UiIcons.Notifications,
                title = "消息通知",
                modifier = Modifier.testTag("mine-notifications-entry"),
                onClick = { onNavigate(MineDestination.Notifications) },
            )
            MenuDivider()
            SettingItem(
                icon = UiIcons.Settings,
                title = "设置",
                modifier = Modifier.testTag("mine-settings-entry"),
                onClick = { onNavigate(MineDestination.Settings) },
            )
        }
    }
}

@Composable
private fun MenuDivider() {
    HorizontalDivider(
        modifier = Modifier.padding(start = 72.dp, end = 14.dp),
        thickness = 0.5.dp,
        color = HairlineDivider,
    )
}
