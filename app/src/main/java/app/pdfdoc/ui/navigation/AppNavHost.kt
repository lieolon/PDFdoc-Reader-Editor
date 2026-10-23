package app.pdfdoc.ui.navigation

import android.net.Uri
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import app.pdfdoc.ui.home.HomeScreen
import app.pdfdoc.ui.merge.MergeScreen
import app.pdfdoc.ui.pages.PagesScreen
import app.pdfdoc.ui.settings.SettingsScreen
import app.pdfdoc.ui.viewer.ViewerScreen

@Composable
fun AppNavHost(incomingUri: Uri?, onIncomingHandled: () -> Unit) {
    val nav = rememberNavController()

    LaunchedEffect(incomingUri) {
        if (incomingUri != null) {
            nav.navigate(ViewerRoute(incomingUri.toString())) { launchSingleTop = true }
            onIncomingHandled()
        }
    }

    NavHost(navController = nav, startDestination = HomeRoute) {
        composable<HomeRoute> {
            HomeScreen(
                onOpenDocument = { nav.navigate(ViewerRoute(it.toString())) },
                onMerge = { nav.navigate(MergeRoute) },
                onSettings = { nav.navigate(SettingsRoute) },
            )
        }
        composable<ViewerRoute> {
            ViewerScreen(
                onBack = nav::popBackStack,
                onPages = { uri -> nav.navigate(PagesRoute(uri)) },
            )
        }
        composable<PagesRoute> {
            PagesScreen(
                onBack = nav::popBackStack,
                onOpenDocument = { uri ->
                    nav.navigate(ViewerRoute(uri.toString())) { popUpTo<HomeRoute>() }
                },
            )
        }
        composable<MergeRoute> {
            MergeScreen(
                onBack = nav::popBackStack,
                onMerged = { uri ->
                    nav.navigate(ViewerRoute(uri.toString())) { popUpTo<HomeRoute>() }
                },
            )
        }
        composable<SettingsRoute> {
            SettingsScreen(onBack = nav::popBackStack)
        }
    }
}
