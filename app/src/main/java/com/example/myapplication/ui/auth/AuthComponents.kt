package com.example.myapplication.ui.auth

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ColorMatrix
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.copyText
import androidx.compose.ui.semantics.cutText
import androidx.compose.ui.semantics.editableText
import androidx.compose.ui.semantics.inputText
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.password
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.myapplication.R
import com.example.myapplication.data.appearance.AppearancePalette
import com.example.myapplication.ui.components.UiAction
import com.example.myapplication.ui.theme.LocalAppearance
import com.example.myapplication.ui.theme.UiRoles
import com.example.myapplication.ui.theme.UiSurfaceRole

@Composable
internal fun AuthBackground(
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    Box(
        Modifier
            .fillMaxSize()
            .background(UiRoles.surface(UiSurfaceRole.Page))
            .systemBarsPadding()
            .imePadding(),
    ) {
        Column(
            modifier = modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 32.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Column(
                modifier = Modifier.widthIn(max = 480.dp).fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally,
                content = content,
            )
        }
    }
}

@Composable
internal fun AuthLogo() {
    val monochrome = LocalAppearance.current.preferences.palette == AppearancePalette.Monochrome
    val colorFilter = remember(monochrome) {
        if (monochrome) ColorFilter.colorMatrix(ColorMatrix().apply { setToSaturation(0f) }) else null
    }
    Surface(
        modifier = Modifier.size(96.dp),
        shape = RoundedCornerShape(28.dp),
        color = UiRoles.surface(UiSurfaceRole.Block),
    ) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Image(
                painter = painterResource(R.drawable.login_logo),
                contentDescription = null,
                contentScale = ContentScale.Fit,
                colorFilter = colorFilter,
                modifier = Modifier.size(68.dp).clip(RoundedCornerShape(18.dp)),
            )
        }
    }
}

@Composable
internal fun AuthTitle(title: String, subtitle: String) {
    Text(
        text = title,
        style = UiRoles.text(MaterialTheme.typography.titleLarge.copy(
            fontSize = 24.sp,
            lineHeight = 30.sp,
            fontWeight = FontWeight.SemiBold,
        )),
        color = UiRoles.foreground,
        textAlign = TextAlign.Center,
    )
    Spacer(Modifier.height(8.dp))
    Text(
        text = subtitle,
        style = UiRoles.text(MaterialTheme.typography.bodyMedium.copy(fontSize = 15.sp, lineHeight = 20.sp)),
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        textAlign = TextAlign.Center,
    )
}

@Composable
internal fun AuthCard(content: @Composable ColumnScope.() -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(24.dp),
        colors = CardDefaults.cardColors(containerColor = UiRoles.surface(UiSurfaceRole.Card)),
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
            content = content,
        )
    }
}

@Composable
internal fun AuthTextField(
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String,
    leadingIcon: ImageVector,
    isPassword: Boolean = false,
    imeAction: ImeAction = if (isPassword) ImeAction.Done else ImeAction.Next,
    modifier: Modifier = Modifier,
    testTag: String? = null,
    enabled: Boolean = true,
    isError: Boolean = false,
    onNext: (() -> Unit)? = null,
    onDone: (() -> Unit)? = null,
) {
    var passwordVisible by remember(isPassword) { mutableStateOf(false) }
    val focusManager = LocalFocusManager.current
    val keyboard = LocalSoftwareKeyboardController.current
    val colors = MaterialTheme.colorScheme
    val masked = remember(value, isPassword) {
        AnnotatedString(if (isPassword) "\u2022".repeat(value.length) else "")
    }
    // These semantics precede the native text field's semantics on the same layout node.
    // Keep its editing/selection actions while concealing both text representations from accessibility.
    val fieldModifier = modifier
        .fillMaxWidth()
        .then(if (testTag != null) Modifier.testTag(testTag) else Modifier)
        .semantics {
            contentDescription = placeholder
            if (isPassword) {
                password()
                editableText = masked
                inputText = masked
                copyText { false }
                cutText { false }
            }
        }
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        enabled = enabled,
        isError = isError,
        textStyle = UiRoles.text(MaterialTheme.typography.bodyLarge),
        placeholder = {
            Text(
                text = placeholder,
                style = UiRoles.text(MaterialTheme.typography.bodyMedium.copy(fontSize = 15.sp, lineHeight = 20.sp)),
            )
        },
        leadingIcon = {
            Icon(leadingIcon, contentDescription = null, modifier = Modifier.size(20.dp), tint = UiRoles.iconTint)
        },
        trailingIcon = if (isPassword) {
            {
                IconButton(
                    onClick = { passwordVisible = !passwordVisible },
                    enabled = enabled,
                    modifier = Modifier.size(48.dp)
                        .then(if (testTag != null) Modifier.testTag("$testTag-toggle") else Modifier),
                ) {
                    Icon(
                        imageVector = if (passwordVisible) PasswordHideIcon else PasswordShowIcon,
                        contentDescription = if (passwordVisible) "隐藏密码" else "显示密码",
                        modifier = Modifier.size(20.dp),
                        tint = UiRoles.iconTint,
                    )
                }
            }
        } else null,
        singleLine = true,
        shape = RoundedCornerShape(16.dp),
        visualTransformation = if (isPassword && !passwordVisible) PasswordVisualTransformation() else VisualTransformation.None,
        keyboardOptions = KeyboardOptions(
            keyboardType = if (isPassword) KeyboardType.Password else KeyboardType.Text,
            imeAction = imeAction,
        ),
        keyboardActions = KeyboardActions(
            onNext = {
                if (enabled) {
                    if (onNext != null) onNext() else focusManager.moveFocus(FocusDirection.Next)
                }
            },
            onDone = {
                if (enabled) {
                    keyboard?.hide()
                    if (onDone != null) onDone() else focusManager.clearFocus()
                }
            },
        ),
        colors = OutlinedTextFieldDefaults.colors(
            focusedTextColor = UiRoles.foreground,
            unfocusedTextColor = UiRoles.foreground,
            disabledTextColor = colors.onSurfaceVariant,
            errorTextColor = UiRoles.foreground,
            focusedContainerColor = UiRoles.surface(UiSurfaceRole.Card),
            unfocusedContainerColor = UiRoles.surface(UiSurfaceRole.Block),
            disabledContainerColor = UiRoles.surface(UiSurfaceRole.Block),
            errorContainerColor = UiRoles.surface(UiSurfaceRole.Block),
            focusedBorderColor = UiRoles.accent,
            unfocusedBorderColor = colors.outlineVariant,
            disabledBorderColor = colors.outlineVariant,
            errorBorderColor = colors.error,
            focusedPlaceholderColor = colors.onSurfaceVariant,
            unfocusedPlaceholderColor = colors.onSurfaceVariant,
            disabledPlaceholderColor = colors.onSurfaceVariant,
            errorPlaceholderColor = colors.onSurfaceVariant,
            cursorColor = UiRoles.accent,
            errorCursorColor = colors.error,
        ),
        modifier = fieldModifier,
    )
}

@Composable
internal fun AuthErrorText(message: String) {
    Text(
        text = message,
        modifier = Modifier.fillMaxWidth().semantics { liveRegion = LiveRegionMode.Polite },
        style = UiRoles.text(MaterialTheme.typography.bodyMedium.copy(fontSize = 15.sp, lineHeight = 20.sp)),
        color = MaterialTheme.colorScheme.error,
    )
}

@Composable
internal fun AuthPrimaryButton(
    text: String,
    enabled: Boolean,
    onClick: () -> Unit,
    loading: Boolean = false,
    modifier: Modifier = Modifier,
    actionId: String = "auth.submit",
) {
    val minHeight = maxOf(52.dp, with(LocalDensity.current) { UiRoles.actionText.lineHeight.toDp() } + 24.dp)
    UiAction(
        label = text,
        onClick = onClick,
        modifier = modifier.fillMaxWidth().heightIn(min = minHeight),
        enabled = enabled,
        filled = true,
        busy = loading,
        retainFilledWhenBusy = true,
        actionId = actionId,
        visualModifier = Modifier.fillMaxWidth().heightIn(min = minHeight),
    )
}

private val PasswordShowIcon by lazy { passwordVisibilityIcon(slashed = false) }
private val PasswordHideIcon by lazy { passwordVisibilityIcon(slashed = true) }

private fun passwordVisibilityIcon(slashed: Boolean): ImageVector = ImageVector.Builder(
    name = if (slashed) "Auth.HidePassword" else "Auth.ShowPassword",
    defaultWidth = 24.dp,
    defaultHeight = 24.dp,
    viewportWidth = 24f,
    viewportHeight = 24f,
).apply {
    path(
        stroke = SolidColor(Color.Black),
        strokeLineWidth = 1.8f,
        strokeLineCap = StrokeCap.Round,
        strokeLineJoin = StrokeJoin.Round,
    ) {
        moveTo(2.75f, 12f)
        curveTo(5.25f, 7.85f, 8.35f, 5.75f, 12f, 5.75f)
        curveTo(15.65f, 5.75f, 18.75f, 7.85f, 21.25f, 12f)
        curveTo(18.75f, 16.15f, 15.65f, 18.25f, 12f, 18.25f)
        curveTo(8.35f, 18.25f, 5.25f, 16.15f, 2.75f, 12f)
        close()
        moveTo(15f, 12f)
        arcTo(3f, 3f, 0f, false, true, 9f, 12f)
        arcTo(3f, 3f, 0f, false, true, 15f, 12f)
        close()
        if (slashed) {
            moveTo(3.75f, 3.75f)
            lineTo(20.25f, 20.25f)
        }
    }
}.build()
