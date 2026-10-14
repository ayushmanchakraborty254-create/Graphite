package com.graphite.app.feature.reader.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Book
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.DirectionsBoat
import androidx.compose.material.icons.filled.Navigation
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.graphite.app.data.model.BookCoverType

@Composable
fun BookCover(
    coverType: BookCoverType,
    title: String,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier
            .shadow(4.dp, RoundedCornerShape(6.dp))
            .clip(RoundedCornerShape(6.dp))
            .border(1.dp, Color(0xFF2E2E2E), RoundedCornerShape(6.dp))
    ) {
        when (coverType) {
            BookCoverType.TREASURE_ISLAND -> {
                // Nautical vintage book cover matching Page 1 & 2
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(
                            Brush.verticalGradient(
                                colors = listOf(
                                    Color(0xFFFFF8E7),
                                    Color(0xFFF4E4BA),
                                    Color(0xFFE2C992)
                                )
                            )
                        )
                        .padding(4.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        text = "TREASURE\nISLAND",
                        color = Color(0xFF7A1C00),
                        fontSize = 8.sp,
                        fontWeight = FontWeight.Black,
                        fontFamily = FontFamily.Serif,
                        textAlign = TextAlign.Center,
                        lineHeight = 9.sp
                    )

                    // Sailing ship illustration
                    Box(
                        modifier = Modifier
                            .size(36.dp)
                            .background(Color(0xFF2B4C7E).copy(alpha = 0.15f), RoundedCornerShape(4.dp)),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Default.DirectionsBoat,
                            contentDescription = null,
                            tint = Color(0xFFC04E22),
                            modifier = Modifier.size(26.dp)
                        )
                    }

                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.Center
                    ) {
                        Icon(
                            imageVector = Icons.Default.Navigation,
                            contentDescription = null,
                            tint = Color(0xFF8D6E63),
                            modifier = Modifier.size(8.dp)
                        )
                        Spacer(modifier = Modifier.width(2.dp))
                        Text(
                            text = "R. L. STEVENSON",
                            color = Color(0xFF4E342E),
                            fontSize = 6.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
            }
            BookCoverType.CE_PPT -> {
                // Presentation slide cover matching Page 2
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(
                            Brush.verticalGradient(
                                colors = listOf(
                                    Color(0xFFB3E5FC),
                                    Color(0xFFE1F5FE)
                                )
                            )
                        )
                        .padding(6.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.SpaceBetween
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(10.dp)
                            .background(Color(0xFF0288D1), RoundedCornerShape(2.dp))
                    )

                    Icon(
                        imageVector = Icons.Default.Description,
                        contentDescription = null,
                        tint = Color(0xFF0277BD),
                        modifier = Modifier.size(24.dp)
                    )

                    Text(
                        text = "CE PPT",
                        color = Color(0xFF01579B),
                        fontSize = 9.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
            }
            BookCoverType.CUSTOM -> {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(Color(0xFF1E1E1E))
                        .padding(4.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center
                ) {
                    Icon(
                        imageVector = Icons.Default.Book,
                        contentDescription = null,
                        tint = Color(0xFFFF7A30),
                        modifier = Modifier.size(22.dp)
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = title.take(12),
                        color = Color.White,
                        fontSize = 8.sp,
                        maxLines = 2,
                        textAlign = TextAlign.Center
                    )
                }
            }
        }
    }
}
