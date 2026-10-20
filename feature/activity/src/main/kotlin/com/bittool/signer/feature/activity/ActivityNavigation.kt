package com.bittool.signer.feature.activity

import androidx.navigation.NavController
import androidx.navigation.NavGraphBuilder
import androidx.navigation.compose.composable
import com.bittool.signer.core.navigation.ActivityDetailRoute
import com.bittool.signer.core.navigation.ActivityRoute
import com.bittool.signer.core.navigation.SignedRequestRoute

/**
 * Contract stub (ARCHITECTURE.md §3, W2-activity-settings replaces the bodies): the Activity tab inside
 * `navigation<ActivityGraph>(startDestination = ActivityRoute)`. `SignedRequestRoute` shares the detail's decoded
 * review through a ViewModel scoped to the `ActivityDetailRoute` back-stack entry.
 */
fun NavGraphBuilder.activityDestinations(navController: NavController) {
    composable<ActivityRoute> { }
    composable<ActivityDetailRoute> { }
    composable<SignedRequestRoute> { }
}
