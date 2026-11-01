package app.pdfdoc.ui.navigation

import android.net.Uri
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import app.pdfdoc.ui.editor.EditorScreen
import app.pdfdoc.ui.forms.FormScreen
import app.pdfdoc.ui.home.HomeScreen
import app.pdfdoc.ui.merge.MergeScreen
import app.pdfdoc.ui.pages.PagesScreen
import app.pdfdoc.ui.scan.ScanScreen
import app.pdfdoc.ui.settings.SettingsScreen
import app.pdfdoc.ui.tools.ToolsScreen
import app.pdfdoc.ui.viewer.ViewerScreen

@Composable
fun AppNavHost(incomingUri: Uri?, onIncomingHandled: () -> Unit) {
    val nav = rememberNavController()

    LaunchedEffect(incomingUri) {
        if (incomingUri != null) {
            nav.navigate(ImportRoute(incomingUri.toString())) { launchSingleTop = true }
            onIncomingHandled()
        }
    }

    NavHost(navController = nav, startDestination = HomeRoute) {
        composable<HomeRoute> {
            HomeScreen(
                onOpenDocument = { nav.navigate(ViewerRoute(it.toString())) },
                onOpenFile = { nav.navigate(ImportRoute(it.toString())) },
                onMerge = { nav.navigate(MergeRoute) },
                onScan = { nav.navigate(ScanRoute()) },
                onImagesToPdf = { nav.navigate(ScanRoute(fromImages = true)) },
                onSettings = { nav.navigate(SettingsRoute) },
                onBatch = { nav.navigate(BatchRoute) },
                onTextToPdf = { nav.navigate(TextToPdfRoute) },
            )
        }
        composable<ViewerRoute> {
            ViewerScreen(
                onBack = nav::popBackStack,
                onPages = { uri -> nav.navigate(PagesRoute(uri)) },
                onEdit = { uri -> nav.navigate(EditorRoute(uri)) },
                onFillForm = { uri -> nav.navigate(FormRoute(uri)) },
                onTools = { uri -> nav.navigate(ToolsRoute(uri)) },
                onReader = { uri, page -> nav.navigate(ReaderRoute(uri, page)) },
                onPresent = { uri, page -> nav.navigate(PresentRoute(uri, page)) },
                onOpenDocument = { uri -> nav.navigate(ViewerRoute(uri.toString())) { popUpTo<HomeRoute>() } },
            )
        }
        composable<ImportRoute> {
            app.pdfdoc.ui.importer.ImportScreen(
                onBack = nav::popBackStack,
                onOpenDocument = { uri -> nav.navigate(ViewerRoute(uri.toString())) { popUpTo<ImportRoute> { inclusive = true } } },
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
        composable<EditorRoute> {
            EditorScreen(
                onBack = nav::popBackStack,
                onOpenDocument = { uri ->
                    nav.navigate(ViewerRoute(uri.toString())) { popUpTo<HomeRoute>() }
                },
            )
        }
        composable<FormRoute> {
            FormScreen(
                onBack = nav::popBackStack,
                onOpenDocument = { uri ->
                    nav.navigate(ViewerRoute(uri.toString())) { popUpTo<HomeRoute>() }
                },
            )
        }
        composable<ScanRoute> {
            ScanScreen(
                onBack = nav::popBackStack,
                onOpenDocument = { uri ->
                    nav.navigate(ViewerRoute(uri.toString())) { popUpTo<HomeRoute>() }
                },
            )
        }
        composable<ToolsRoute> {
            ToolsScreen(
                onBack = nav::popBackStack,
                onOpenDocument = { uri ->
                    nav.navigate(ViewerRoute(uri.toString())) { popUpTo<HomeRoute>() }
                },
                onCompare = { old, new -> nav.navigate(CompareRoute(old.toString(), new.toString())) },
            )
        }
        composable<CompareRoute> {
            app.pdfdoc.ui.compare.CompareScreen(
                onBack = nav::popBackStack,
                onOpenDocument = { uri -> nav.navigate(ViewerRoute(uri.toString())) { popUpTo<HomeRoute>() } },
            )
        }
        composable<ReaderRoute> {
            app.pdfdoc.ui.reader.ReaderScreen(onBack = nav::popBackStack)
        }
        composable<PresentRoute> {
            app.pdfdoc.ui.reader.PresentScreen(onBack = nav::popBackStack)
        }
        composable<BatchRoute> {
            app.pdfdoc.ui.batch.BatchScreen(onBack = nav::popBackStack)
        }
        composable<TextToPdfRoute> {
            app.pdfdoc.ui.batch.TextToPdfScreen(
                onBack = nav::popBackStack,
                onOpenDocument = { uri -> nav.navigate(ViewerRoute(uri.toString())) { popUpTo<HomeRoute>() } },
            )
        }
        composable<SettingsRoute> {
            SettingsScreen(onBack = nav::popBackStack)
        }
    }
}
