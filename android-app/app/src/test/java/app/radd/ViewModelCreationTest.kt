package app.radd

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.viewmodel.compose.LocalViewModelStoreOwner
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import app.radd.ui.appViewModel
import app.radd.vm.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Every ViewModel is created the way the app creates it: from a ViewModelStoreOwner that has NO default creation extras.
 * That is the exact situation that crashed مستند v1.0.0: the factory read the Application out of CreationExtras and got null.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = TestApp::class)
@org.robolectric.annotation.SQLiteMode(org.robolectric.annotation.SQLiteMode.Mode.NATIVE)
class ViewModelCreationTest {
    @get:Rule(order = 0) val reset = ResetViewModelCaches
    @get:Rule(order = 1) val compose = createComposeRule()

    private val ownerWithoutExtras = object : ViewModelStoreOwner { override val viewModelStore = ViewModelStore() }

    @Test fun allViewModelsCanBeCreatedFromAnOwnerWithoutExtras() {
        val created = ArrayList<Any>()
        compose.setContent {
            CompositionLocalProvider(LocalViewModelStoreOwner provides ownerWithoutExtras) {
                created += appViewModel { NavViewModel(it) }
                created += appViewModel { HomeViewModel(it) }
                created += appViewModel { ScanViewModel(it) }
                created += appViewModel { ResultsViewModel(it) }
            }
        }
        compose.waitForIdle()
        assertEquals(4, created.size)
    }

    @Test fun theOldStyleFactoryFailsWithoutAnApplication() {
        // documents the bug: reading APPLICATION_KEY from the extras of such an owner gives null
        val factory = viewModelFactory { initializer { this[ViewModelProvider.AndroidViewModelFactory.APPLICATION_KEY] as android.app.Application; NavViewModel(graph.context) } }
        try { ViewModelProvider(ownerWithoutExtras, factory)[NavViewModel::class.java]; fail("expected the old-style factory to fail") }
        catch (e: Exception) { /* NullPointerException: that is why the app passes the application Context explicitly */ }
    }

    @Test fun homeViewModelReportsMissingPermissionsInsteadOfCrashing() {
        val vm = HomeViewModel(graph.context) { app.radd.ui.PermissionUi(allFilesGranted = false, mediaGranted = false) }
        assertFalse(vm.permission.value.ready)
        vm.refresh()
        assertFalse(vm.permission.value.ready)
    }

    @Test fun navigationBackWalksTheFlow() {
        val nav = NavViewModel(graph.context)
        assertFalse(nav.back())
        nav.go(Route.PREVIEW); assertTrue(nav.back()); assertEquals(Route.RESULTS, nav.route)
        assertTrue(nav.back()); assertEquals(Route.HOME, nav.route)
        nav.go(Route.DONE); assertTrue(nav.back()); assertEquals(Route.RESULTS, nav.route)
    }
}
