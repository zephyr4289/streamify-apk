package com.streamify.app.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import com.streamify.app.R

val Montserrat = FontFamily(
    Font(R.font.montserrat_regular, FontWeight.Normal),
    Font(R.font.montserrat_medium, FontWeight.Medium),
    Font(R.font.montserrat_semibold, FontWeight.SemiBold),
    Font(R.font.montserrat_bold, FontWeight.Bold),
    Font(R.font.montserrat_extrabold, FontWeight.ExtraBold)
)

val Poppins = FontFamily(
    Font(R.font.poppins_light, FontWeight.Light),
    Font(R.font.poppins_regular, FontWeight.Normal),
    Font(R.font.poppins_medium, FontWeight.Medium),
    Font(R.font.poppins_semibold, FontWeight.SemiBold)
)

object StreamifyType {
    val DisplayLarge = TextStyle(fontFamily = Montserrat, fontWeight = FontWeight.ExtraBold, fontSize = 32.sp, letterSpacing = (-0.5).sp)
    val DisplayMedium = TextStyle(fontFamily = Montserrat, fontWeight = FontWeight.Bold, fontSize = 28.sp, letterSpacing = (-0.3).sp)
    val HeadlineLarge = TextStyle(fontFamily = Montserrat, fontWeight = FontWeight.Bold, fontSize = 24.sp, letterSpacing = (-0.2).sp)
    val HeadlineMedium = TextStyle(fontFamily = Montserrat, fontWeight = FontWeight.Bold, fontSize = 20.sp)
    val TitleLarge = TextStyle(fontFamily = Montserrat, fontWeight = FontWeight.SemiBold, fontSize = 18.sp)
    val TitleMedium = TextStyle(fontFamily = Montserrat, fontWeight = FontWeight.SemiBold, fontSize = 16.sp)
    val TitleSmall = TextStyle(fontFamily = Montserrat, fontWeight = FontWeight.Bold, fontSize = 14.sp)

    val BodyLarge = TextStyle(fontFamily = Poppins, fontWeight = FontWeight.Normal, fontSize = 16.sp, lineHeight = 24.sp)
    val BodyMedium = TextStyle(fontFamily = Poppins, fontWeight = FontWeight.Normal, fontSize = 14.sp, lineHeight = 20.sp)
    val BodySmall = TextStyle(fontFamily = Poppins, fontWeight = FontWeight.Normal, fontSize = 12.sp, lineHeight = 16.sp)
    val Caption = TextStyle(fontFamily = Poppins, fontWeight = FontWeight.Medium, fontSize = 11.sp, letterSpacing = 0.5.sp)

    val PlayerTitle = TextStyle(fontFamily = Montserrat, fontWeight = FontWeight.Bold, fontSize = 22.sp)
    val PlayerArtist = TextStyle(fontFamily = Poppins, fontWeight = FontWeight.Normal, fontSize = 15.sp, color = StreamifyColors.TextSub)
    val SeekbarTime = TextStyle(fontFamily = Poppins, fontWeight = FontWeight.Normal, fontSize = 11.sp, color = StreamifyColors.TextSub)

    val CardTitle = TextStyle(fontFamily = Montserrat, fontWeight = FontWeight.Bold, fontSize = 15.sp)
    val CardSubtitle = TextStyle(fontFamily = Poppins, fontWeight = FontWeight.Normal, fontSize = 13.sp, color = StreamifyColors.TextSub)
}

val Typography = Typography(
    displayLarge = StreamifyType.DisplayLarge,
    displayMedium = StreamifyType.DisplayMedium,
    headlineLarge = StreamifyType.HeadlineLarge,
    headlineMedium = StreamifyType.HeadlineMedium,
    titleLarge = StreamifyType.TitleLarge,
    titleMedium = StreamifyType.TitleMedium,
    titleSmall = StreamifyType.TitleSmall,
    bodyLarge = StreamifyType.BodyLarge,
    bodyMedium = StreamifyType.BodyMedium,
    bodySmall = StreamifyType.BodySmall,
    labelSmall = StreamifyType.Caption
)
