package io.github.puvon.enetrend

import android.content.pm.PackageManager
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.platform.app.InstrumentationRegistry
import io.github.puvon.enetrend.health.AndroidHealthConnection
import io.github.puvon.enetrend.health.HealthConnectionState
import io.github.puvon.enetrend.ui.HealthConnectionScreen
import io.github.puvon.enetrend.ui.theme.EnetrendTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class HealthConnectionScreenTest {
    @get:Rule val compose = createComposeRule()

    @Test fun partialPermissionOffersRequestAndSettings() {
        var requests = 0
        var settings = 0
        compose.setContent {
            EnetrendTheme {
                HealthConnectionScreen(
                    HealthConnectionState.PermissionsRequired(1), false,
                    { requests++ }, {}, { settings++ }, {}, {},
                )
            }
        }
        compose.onNodeWithText("読み取り権限を許可").performClick()
        compose.onNodeWithText("Health Connect の設定").performClick()
        compose.runOnIdle {
            assertEquals(1, requests)
            assertEquals(1, settings)
        }
    }

    @Test fun providerUpdateOffersInstallation() {
        var installs = 0
        compose.setContent {
            EnetrendTheme {
                HealthConnectionScreen(
                    HealthConnectionState.UpdateRequired, false, {}, {}, {}, { installs++ }, {},
                )
            }
        }
        compose.onNodeWithText("Health Connect のインストールまたは更新が必要です。").assertIsDisplayed()
        compose.onNodeWithText("インストール・更新").performClick()
        compose.runOnIdle { assertEquals(1, installs) }
    }

    @Test fun manifestAndRequestContainOnlyThreeReadPermissions() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val permissions = context.packageManager.getPackageInfo(
            context.packageName, PackageManager.GET_PERMISSIONS,
        ).requestedPermissions.orEmpty().filter { it.startsWith("android.permission.health.") }.toSet()
        val expected = setOf(
            "android.permission.health.READ_NUTRITION",
            "android.permission.health.READ_TOTAL_CALORIES_BURNED",
            "android.permission.health.READ_WEIGHT",
        )
        assertEquals(expected, permissions)
        assertEquals(expected, AndroidHealthConnection.READ_PERMISSIONS)
    }
}
