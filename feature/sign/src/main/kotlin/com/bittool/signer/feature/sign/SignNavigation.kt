package com.bittool.signer.feature.sign

import androidx.navigation.NavController
import androidx.navigation.NavGraphBuilder
import androidx.navigation.compose.composable
import com.bittool.signer.core.navigation.ScanRoute
import com.bittool.signer.core.navigation.ScannerRoute

/**
 * Contract stub (ARCHITECTURE.md §3, W2-sign replaces the bodies): the Scan tab inside
 * `navigation<ScanGraph>(startDestination = ScanRoute)`. `ScanRoute` switches between Home, Review and Signature from
 * `SignerState`; `ScannerRoute` is the full-screen camera (modal). The paste sheet, the file picker, the "Discard partial
 * signatures?" dialog, the authorize sheet and the export picker are hosted by these screens (ARCHITECTURE.md §2.5
 * rows 45–55); the signature QR is part of the result page itself.
 */
fun NavGraphBuilder.scanDestinations(navController: NavController) {
    composable<ScanRoute> { }
    composable<ScannerRoute> { }
}
