package de.gurum.besseresser.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import de.gurum.besseresser.R
import de.gurum.besseresser.model.TrafficLight

@Composable
internal fun MainTopBar(
    menuOpen: Boolean,
    menuEnabled: Boolean,
    onMenuToggle: (Boolean) -> Unit,
    onOpenProfile: () -> Unit,
    onOpenHistory: () -> Unit,
    onEditSettings: () -> Unit,
    onOpenConfig: () -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Box(
                modifier = Modifier
                    .size(36.dp)
                    .clip(RoundedCornerShape(11.dp))
                    .background(MaterialTheme.colorScheme.primaryContainer),
                contentAlignment = Alignment.Center
            ) {
                Row(horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                    Box(Modifier.size(7.dp).clip(CircleShape).background(TrafficLight.GREEN.color()))
                    Box(Modifier.size(7.dp).clip(CircleShape).background(TrafficLight.YELLOW.color()))
                    Box(Modifier.size(7.dp).clip(CircleShape).background(TrafficLight.RED.color()))
                }
            }
            Text(
                stringResource(R.string.main_title),
                fontSize = 23.sp,
                fontWeight = FontWeight.Bold
            )
        }
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Surface(
                shape = CircleShape,
                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.65f),
                modifier = Modifier.size(42.dp)
            ) {
                IconButton(
                    onClick = onOpenHistory,
                    enabled = menuEnabled,
                    modifier = Modifier.size(42.dp)
                ) {
                    Icon(
                        Icons.Filled.DateRange,
                        contentDescription = stringResource(R.string.menu_history),
                        modifier = Modifier.size(20.dp)
                    )
                }
            }
            Box {
                Surface(
                    shape = CircleShape,
                    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.65f),
                    modifier = Modifier.size(42.dp)
                ) {
                    IconButton(
                        onClick = { onMenuToggle(true) },
                        modifier = Modifier.size(42.dp)
                    ) {
                        Icon(
                            Icons.Filled.Menu,
                            contentDescription = stringResource(R.string.menu_open_description),
                            modifier = Modifier.size(20.dp)
                        )
                    }
                }
                DropdownMenu(
                    expanded = menuOpen,
                    onDismissRequest = { onMenuToggle(false) }
                ) {
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.menu_profile)) },
                        onClick = {
                            onMenuToggle(false)
                            onOpenProfile()
                        },
                        leadingIcon = {
                            Icon(Icons.Filled.Person, contentDescription = null)
                        },
                        enabled = menuEnabled
                    )
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.menu_history)) },
                        onClick = {
                            onMenuToggle(false)
                            onOpenHistory()
                        },
                        leadingIcon = {
                            Icon(Icons.Filled.DateRange, contentDescription = null)
                        },
                        enabled = menuEnabled
                    )
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.menu_settings)) },
                        onClick = {
                            onMenuToggle(false)
                            onEditSettings()
                        },
                        leadingIcon = {
                            Icon(Icons.Filled.Settings, contentDescription = null)
                        }
                    )
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.menu_config)) },
                        onClick = {
                            onMenuToggle(false)
                            onOpenConfig()
                        },
                        leadingIcon = {
                            Icon(Icons.Filled.Build, contentDescription = null)
                        },
                        enabled = menuEnabled
                    )
                }
            }
        }
    }
}
