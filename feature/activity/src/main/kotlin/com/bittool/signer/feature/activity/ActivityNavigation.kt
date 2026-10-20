package com.bittool.signer.feature.activity

import androidx.compose.runtime.remember
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.navigation.NavBackStackEntry
import androidx.navigation.NavController
import androidx.navigation.NavGraphBuilder
import androidx.navigation.compose.composable
import com.bittool.signer.core.navigation.ActivityDetailRoute
import com.bittool.signer.core.navigation.ActivityRoute
import com.bittool.signer.core.navigation.SignedRequestRoute

/**
 * The Activity tab inside `navigation<ActivityGraph>(startDestination = ActivityRoute)` (ARCHITECTURE.md §2.2):
 * the list, one receipt's Signature page and its "Signed Request" page. `SignedRequestRoute` shares the detail's decoded
 * review through the [ActivityDetailViewModel] scoped to the `ActivityDetailRoute` back-stack entry; when that entry is
 * missing (a restored stack that lost it) it gets its own ViewModel, which decodes the stored request again.
 * A receipt that no longer exists pops its pages.
 */
fun NavGraphBuilder.activityDestinations(navController: NavController) {
    composable<ActivityRoute> {
        ActivityScreen(onOpenReceipt = { id -> navController.navigate(ActivityDetailRoute(id)) })
    }
    composable<ActivityDetailRoute> {
        ActivityDetailScreen(
            onBack = { navController.popBackStack() },
            onOpenDecodedFields = { id -> navController.navigate(SignedRequestRoute(id)) },
        )
    }
    composable<SignedRequestRoute> { entry ->
        val detailEntry: NavBackStackEntry? = remember(entry) {
            runCatching { navController.getBackStackEntry<ActivityDetailRoute>() }.getOrNull()
        }
        val viewModel: ActivityDetailViewModel = if (detailEntry != null) hiltViewModel(detailEntry) else hiltViewModel(entry)
        SignedRequestScreen(
            viewModel = viewModel,
            onBack = { navController.popBackStack() },
            onClose = { navController.popBackStack<ActivityRoute>(inclusive = false) },
        )
    }
}
