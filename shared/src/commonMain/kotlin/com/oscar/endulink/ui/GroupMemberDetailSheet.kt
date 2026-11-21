package com.oscar.endulink.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.oscar.endulink.group.GroupMember
import com.oscar.endulink.group.RiderStatus
import com.oscar.endulink.trail.GeoCoord
import kotlinx.datetime.Clock
import kotlin.math.roundToInt

@Composable
fun GroupMemberDetailSheet(
    member: GroupMember,
    riderCoord: GeoCoord?,
    hasActiveRoute: Boolean,
    onNavigate: () -> Unit,
    onCenter: () -> Unit,
    onAddStop: () -> Unit,
    onSaveAsWaypoint: () -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier
) {
    val memberColor = Color(member.color.argb)
    val nowMs = Clock.System.now().toEpochMilliseconds()
    val ageSec = member.ageSeconds(nowMs)
    val ageText = if (ageSec < 60L) "hace ${ageSec} s" else "hace ${ageSec / 60L} min"
    val distStr = riderCoord?.let {
        val m = it.distanceTo(member.coordinate)
        if (m < 1000.0) "${m.roundToInt()} m" else "${((m / 100.0).roundToInt() / 10.0)} km"
    } ?: "—"

    val statusColor = when (member.status) {
        RiderStatus.SOS -> EnduPalette.Danger
        RiderStatus.MECHANICAL -> EnduPalette.Accent
        RiderStatus.STOPPED_WAIT -> EnduPalette.Warning
        RiderStatus.RIDING -> EnduPalette.Success
        RiderStatus.LEFT -> EnduPalette.TextMuted
    }

    Card(
        shape = RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp),
        colors = CardDefaults.cardColors(containerColor = EnduPalette.Surface),
        modifier = modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 18.dp, vertical = 16.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier
                        .size(46.dp)
                        .clip(CircleShape)
                        .background(memberColor.copy(alpha = 0.24f))
                        .border(2.dp, memberColor, CircleShape)
                ) {
                    Text(
                        text = member.initials().take(2),
                        color = Color.White,
                        fontSize = 15.sp,
                        fontWeight = FontWeight.Black
                    )
                }

                Spacer(modifier = Modifier.width(12.dp))

                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = member.name,
                        color = EnduPalette.TextPrimary,
                        fontSize = 17.sp,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1
                    )
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(
                            text = member.status.shortLabel,
                            color = statusColor,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            text = "· ${member.source.label} · $ageText",
                            color = EnduPalette.TextMuted,
                            fontSize = 11.sp
                        )
                    }
                }

                Text(
                    text = "✕",
                    color = EnduPalette.TextMuted,
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier
                        .clip(CircleShape)
                        .clickable { onClose() }
                        .padding(8.dp)
                )
            }

            if (member.statusMessage.isNotBlank()) {
                Spacer(modifier = Modifier.height(10.dp))
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(8.dp))
                        .background(statusColor.copy(alpha = 0.14f))
                        .border(1.dp, statusColor.copy(alpha = 0.5f), RoundedCornerShape(8.dp))
                        .padding(horizontal = 10.dp, vertical = 7.dp)
                ) {
                    Text(
                        text = member.statusMessage,
                        color = EnduPalette.TextPrimary,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Medium
                    )
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(10.dp))
                    .background(EnduPalette.SurfaceHigh)
                    .padding(horizontal = 12.dp, vertical = 10.dp),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                MemberStatCol(label = "DISTANCIA", value = distStr, accent = EnduPalette.Accent)
                MemberStatCol(label = "VELOCIDAD", value = "${member.speedKmh.roundToInt()} km/h")
                MemberStatCol(label = "ALTITUD", value = "${member.altitudeM.roundToInt()} m")
                MemberStatCol(
                    label = "BATERÍA",
                    value = if (member.batteryPct >= 0) "${member.batteryPct}%" else "—"
                )
            }

            Spacer(modifier = Modifier.height(14.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier
                        .weight(1.25f)
                        .height(42.dp)
                        .clip(RoundedCornerShape(10.dp))
                        .background(EnduPalette.Accent)
                        .clickable {
                            onNavigate()
                            onClose()
                        }
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        NavArrowIcon(color = Color.White, size = 13.dp)
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("Ir hasta aquí", color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.Bold)
                    }
                }

                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier
                        .weight(1f)
                        .height(42.dp)
                        .clip(RoundedCornerShape(10.dp))
                        .background(Color(0xFF00D4FF).copy(alpha = 0.2f))
                        .border(1.dp, Color(0xFF00D4FF), RoundedCornerShape(10.dp))
                        .clickable {
                            onCenter()
                            onClose()
                        }
                ) {
                    Text("Centrar", color = Color(0xFF00D4FF), fontSize = 13.sp, fontWeight = FontWeight.Bold)
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                if (hasActiveRoute) {
                    Box(
                        contentAlignment = Alignment.Center,
                        modifier = Modifier
                            .weight(1f)
                            .height(36.dp)
                            .clip(RoundedCornerShape(8.dp))
                            .background(EnduPalette.SurfaceHigh)
                            .clickable {
                                onAddStop()
                                onClose()
                            }
                    ) {
                        Text("➕ Añadir parada", color = EnduPalette.TextPrimary, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                    }
                }

                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier
                        .weight(1f)
                        .height(36.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .background(EnduPalette.SurfaceHigh)
                        .clickable { onSaveAsWaypoint() }
                ) {
                    Text("📌 Guardar punto", color = EnduPalette.TextPrimary, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                }
            }
        }
    }
}

@Composable
private fun MemberStatCol(
    label: String,
    value: String,
    accent: Color = EnduPalette.TextPrimary
) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            text = label,
            color = EnduPalette.TextMuted,
            fontSize = 9.sp,
            fontWeight = FontWeight.Bold
        )
        Text(
            text = value,
            color = accent,
            fontSize = 13.sp,
            fontWeight = FontWeight.Bold
        )
    }
}
