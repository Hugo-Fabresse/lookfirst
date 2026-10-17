package com.lookfirst.ui.screens.result

import androidx.compose.ui.test.junit4.createComposeRule
import com.lookfirst.data.platform.ActionExecutor
import com.lookfirst.data.platform.ClipboardWriter
import com.lookfirst.data.platform.OpenOutcome
import com.lookfirst.data.platform.PayloadIdGenerator
import com.lookfirst.data.reputation.NoReputationLists
import com.lookfirst.domain.model.ScanPayload
import com.lookfirst.domain.model.Untrusted
import com.lookfirst.domain.rdap.RdapClient
import com.lookfirst.domain.rdap.RdapFailure
import com.lookfirst.domain.rdap.RdapOutcome
import com.lookfirst.domain.rdap.RdapPlan
import com.lookfirst.domain.rdap.RdapPlanner
import com.lookfirst.domain.rdap.RdapUnavailable
import com.lookfirst.domain.redirect.TraceFailure
import com.lookfirst.domain.redirect.TraceOutcome
import com.lookfirst.domain.usecase.AnalyzeScan
import com.lookfirst.domain.usecase.ApplyDomainAge
import com.lookfirst.domain.usecase.ApplyRedirectTrace
import com.lookfirst.ui.theme.LookFirstTheme
import kotlinx.coroutines.Dispatchers
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class ResultRouteTest {
    @get:Rule
    val composeRule = createComposeRule()

    private val opened = mutableListOf<ScanPayload>()

    private fun viewModel() = ResultViewModel(
        clipboard = object : ClipboardWriter {
            override fun copy(text: String, sensitive: Boolean) = Unit

            override fun onAppStopped() = Unit

            override fun onWindowFocused() = Unit
        },
        actions = object : ActionExecutor {
            override fun hasNonBrowserHandler(url: ScanPayload.Url): Boolean = false

            override fun open(payload: ScanPayload): OpenOutcome {
                opened += payload
                return OpenOutcome.Started
            }
        },
        analyzeScan = AnalyzeScan(NoReputationLists()),
        regionHints = { null },
        payloadIds = PayloadIdGenerator { "id-1" },
        redirectTracer = { TraceOutcome.Failed(TraceFailure.NETWORK) },
        applyRedirectTrace = ApplyRedirectTrace(AnalyzeScan(NoReputationLists())),
        rdapPlanner = RdapPlanner { RdapPlan.Unavailable(RdapUnavailable.NO_BOOTSTRAP) },
        rdapClient = RdapClient { RdapOutcome.Failed(RdapFailure.NETWORK) },
        applyDomainAge = ApplyDomainAge(),
        networkStatus = { true },
        workDispatcher = Dispatchers.Unconfined,
    )

    private fun show(viewModel: ResultViewModel, onClose: () -> Unit) {
        composeRule.setContent { LookFirstTheme { ResultRoute(viewModel = viewModel, onClose = onClose) } }
    }

    @Test
    fun aRestoredResultWithoutContentClosesByItself() {
        var closes = 0

        show(viewModel()) { closes++ }
        composeRule.waitForIdle()

        assertEquals(1, closes)
        assertEquals(emptyList<ScanPayload>(), opened)
    }

    @Test
    fun aResultWithContentStaysOpenAndOpensNothing() {
        var closes = 0
        val viewModel = viewModel().also { it.show(Untrusted("https://example.com/")) }

        show(viewModel) { closes++ }
        composeRule.waitForIdle()

        assertEquals(0, closes)
        assertEquals(emptyList<ScanPayload>(), opened)
    }
}
