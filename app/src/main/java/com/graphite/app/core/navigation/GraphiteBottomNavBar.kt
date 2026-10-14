package com.graphite.app.core.navigation

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Book
import androidx.compose.material.icons.filled.EditNote
import androidx.compose.material.icons.filled.MenuBook
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.outlined.Book
import androidx.compose.material.icons.outlined.EditNote
import androidx.compose.material.icons.outlined.MenuBook
import androidx.compose.material.icons.outlined.Person
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.graphite.app.core.theme.GraphiteBlack
import com.graphite.app.core.theme.GraphiteOrange
import com.graphite.app.core.theme.GraphiteOutline

enum class NavigationTab {
    RECENT,
    BOOKSHELF,
    ME,
    NOTESHELF
}

@Composable
fun GraphiteBottomNavBar(
    selectedTab: NavigationTab,
    onTabSelected: (NavigationTab) -> Unit,
    modifier: Modifier = Modifier
) {
    NavigationBar(
        modifier = modifier
            .fillMaxWidth()
            .background(GraphiteBlack),
        containerColor = GraphiteBlack,
        tonalElevation = 0.dp
    ) {
        // 1. Recent Tab (Page 1)
        NavigationBarItem(
            selected = selectedTab == NavigationTab.RECENT,
            onClick = { onTabSelected(NavigationTab.RECENT) },
            icon = {
                Icon(
                    imageVector = if (selectedTab == NavigationTab.RECENT) Icons.Filled.MenuBook else Icons.Outlined.MenuBook,
                    contentDescription = "Recent"
                )
            },
            label = { Text("Recent", fontSize = 11.sp) },
            colors = NavigationBarItemDefaults.colors(
                selectedIconColor = GraphiteOrange,
                selectedTextColor = GraphiteOrange,
                unselectedIconColor = Color(0xFF888888),
                unselectedTextColor = Color(0xFF888888),
                indicatorColor = Color.Transparent
            )
        )

        // 2. Bookshelf Tab (Page 2)
        NavigationBarItem(
            selected = selectedTab == NavigationTab.BOOKSHELF,
            onClick = { onTabSelected(NavigationTab.BOOKSHELF) },
            icon = {
                Icon(
                    imageVector = if (selectedTab == NavigationTab.BOOKSHELF) Icons.Filled.Book else Icons.Outlined.Book,
                    contentDescription = "Bookshelf"
                )
            },
            label = { Text("Bookshelf", fontSize = 11.sp) },
            colors = NavigationBarItemDefaults.colors(
                selectedIconColor = GraphiteOrange,
                selectedTextColor = GraphiteOrange,
                unselectedIconColor = Color(0xFF888888),
                unselectedTextColor = Color(0xFF888888),
                indicatorColor = Color.Transparent
            )
        )

        // 3. Noteshelf Tab (Tablet Notes studio shortcut)
        NavigationBarItem(
            selected = selectedTab == NavigationTab.NOTESHELF,
            onClick = { onTabSelected(NavigationTab.NOTESHELF) },
            icon = {
                Icon(
                    imageVector = if (selectedTab == NavigationTab.NOTESHELF) Icons.Filled.EditNote else Icons.Outlined.EditNote,
                    contentDescription = "Noteshelf"
                )
            },
            label = { Text("Notes", fontSize = 11.sp) },
            colors = NavigationBarItemDefaults.colors(
                selectedIconColor = GraphiteOrange,
                selectedTextColor = GraphiteOrange,
                unselectedIconColor = Color(0xFF888888),
                unselectedTextColor = Color(0xFF888888),
                indicatorColor = Color.Transparent
            )
        )

        // 4. Me Tab (Page 3)
        NavigationBarItem(
            selected = selectedTab == NavigationTab.ME,
            onClick = { onTabSelected(NavigationTab.ME) },
            icon = {
                Icon(
                    imageVector = if (selectedTab == NavigationTab.ME) Icons.Filled.Person else Icons.Outlined.Person,
                    contentDescription = "Me"
                )
            },
            label = { Text("Me", fontSize = 11.sp) },
            colors = NavigationBarItemDefaults.colors(
                selectedIconColor = GraphiteOrange,
                selectedTextColor = GraphiteOrange,
                unselectedIconColor = Color(0xFF888888),
                unselectedTextColor = Color(0xFF888888),
                indicatorColor = Color.Transparent
            )
        )
    }
}
