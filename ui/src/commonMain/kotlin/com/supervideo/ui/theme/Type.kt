package com.supervideo.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.runtime.Composable
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import com.supervideo.ui.resources.Res
import com.supervideo.ui.resources.source_serif_pro_bold
import com.supervideo.ui.resources.source_serif_pro_regular
import com.supervideo.ui.resources.source_serif_pro_semibold
import org.jetbrains.compose.resources.ExperimentalResourceApi
import org.jetbrains.compose.resources.Font

@OptIn(ExperimentalResourceApi::class)
val Typography: Typography
    @Composable get() {
        val fontFamily = FontFamily(
            Font(Res.font.source_serif_pro_regular),
            Font(Res.font.source_serif_pro_semibold, FontWeight.W600),
            Font(Res.font.source_serif_pro_bold, FontWeight.Bold)
        )

        return Typography(
            displayMedium = TextStyle(
                fontWeight = FontWeight.Normal,
                fontFamily = fontFamily,
                fontSize = 42.sp,
                lineHeight = 48.sp,
                letterSpacing = 0.sp
            ),
            headlineLarge = TextStyle(
                fontWeight = FontWeight.SemiBold,
                fontFamily = fontFamily,
                fontSize = 32.sp,
                lineHeight = 40.sp,
                letterSpacing = 0.sp
            ),
            headlineMedium = TextStyle(
                fontWeight = FontWeight.SemiBold,
                fontFamily = fontFamily,
                fontSize = 28.sp,
                lineHeight = 36.sp,
                letterSpacing = 0.sp
            ),
            headlineSmall = TextStyle(
                fontWeight = FontWeight.Normal,
                fontFamily = fontFamily,
                fontSize = 22.sp,
                lineHeight = 30.sp,
                letterSpacing = 0.sp
            ),
            titleLarge = TextStyle(
                fontWeight = FontWeight.SemiBold,
                fontFamily = fontFamily,
                fontSize = 22.sp,
                lineHeight = 28.sp,
                letterSpacing = 0.sp
            ),
            titleMedium = TextStyle(
                fontWeight = FontWeight.SemiBold,
                fontFamily = fontFamily,
                fontSize = 16.sp,
                lineHeight = 24.sp,
                letterSpacing = 0.15.sp
            ),
            titleSmall = TextStyle(
                fontWeight = FontWeight.Bold,
                fontFamily = fontFamily,
                fontSize = 14.sp,
                lineHeight = 20.sp,
                letterSpacing = 0.1.sp
            ),
            bodyLarge = TextStyle(
                fontWeight = FontWeight.Normal,
                fontFamily = fontFamily,
                fontSize = 16.sp,
                lineHeight = 24.sp,
                letterSpacing = 0.15.sp
            ),
            bodyMedium = TextStyle(
                fontWeight = FontWeight.Normal,
                fontFamily = fontFamily,
                fontSize = 14.sp,
                lineHeight = 20.sp,
                letterSpacing = 0.5.sp
            ),
            bodySmall = TextStyle(
                fontWeight = FontWeight.Normal,
                fontFamily = fontFamily,
                fontSize = 12.sp,
                lineHeight = 16.sp,
                letterSpacing = 0.15.sp
            ),
            labelLarge = TextStyle(
                fontWeight = FontWeight.Normal,
                fontFamily = fontFamily,
                fontSize = 18.sp,
                lineHeight = 24.sp,
                letterSpacing = 0.1.sp
            ),
            labelMedium = TextStyle(
                fontWeight = FontWeight.SemiBold,
                fontFamily = fontFamily,
                fontSize = 12.sp,
                lineHeight = 16.sp,
                letterSpacing = 0.5.sp
            ),
            labelSmall = TextStyle(
                fontWeight = FontWeight.SemiBold,
                fontFamily = fontFamily,
                fontSize = 11.sp,
                lineHeight = 16.sp,
                letterSpacing = 0.5.sp
            )
        )
    }
