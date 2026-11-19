package de.gurum.besseresser

import de.gurum.besseresser.data.ApiClient
import de.gurum.besseresser.data.ApiException
import de.gurum.besseresser.data.AppDispatchers
import de.gurum.besseresser.data.InsecureEndpointException
import de.gurum.besseresser.data.NetworkSecurity
import de.gurum.besseresser.data.OpenAiChatClient
import de.gurum.besseresser.data.PromptBundle
import de.gurum.besseresser.data.SplitGoalsRequest
import de.gurum.besseresser.data.UrlSecurityStatus
import de.gurum.besseresser.model.Settings
import de.gurum.besseresser.ui.SetupViewModel
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

@Suppress("kotlin:S1313")
class NetworkSecurityTest {

    @Test
    fun testAssessUrl_httpsEndpoints() {
        val httpsUrls = listOf(
            "https://api.openai.com/v1",
            "https://openrouter.ai/api/v1",
            "https://api.groq.com/openai/v1",
            "https://192.168.1.100:8443/v1",
            "https://custom-server.internal:9000/v1/chat/completions",
            "  https://api.openai.com/v1  "
        )
        for (url in httpsUrls) {
            assertEquals("Expected SECURE_HTTPS for $url", UrlSecurityStatus.SECURE_HTTPS, NetworkSecurity.assessUrl(url))
            assertTrue("Expected isPermittedUrl=true for $url", NetworkSecurity.isPermittedUrl(url))
        }
    }

    @Test
    fun testAssessUrl_localCleartextEndpoints() {
        val localUrls = listOf(
            "http://localhost:11434/v1",
            "http://localhost:8080",
            "http://127.0.0.1:8000/v1",
            "http://127.0.0.5:1234",
            "http://10.0.2.2:8000/v1",
            "http://[::1]:8080/v1",
            "  http://localhost:11434  "
        )
        for (url in localUrls) {
            assertEquals("Expected LOCAL_CLEARTEXT_HTTP for $url", UrlSecurityStatus.LOCAL_CLEARTEXT_HTTP, NetworkSecurity.assessUrl(url))
            assertTrue("Expected isPermittedUrl=true for $url", NetworkSecurity.isPermittedUrl(url))
        }
    }

    @Test
    fun testAssessUrl_insecureRemoteCleartextEndpoints() {
        val insecureUrls = listOf(
            "http://api.openai.com/v1",
            "http://openrouter.ai/api/v1",
            "http://api.groq.com/openai/v1",
            "http://192.168.1.50:8000/v1",
            "http://example.com/api"
        )
        for (url in insecureUrls) {
            assertEquals("Expected INSECURE_REMOTE_HTTP for $url", UrlSecurityStatus.INSECURE_REMOTE_HTTP, NetworkSecurity.assessUrl(url))
            assertFalse("Expected isPermittedUrl=false for $url", NetworkSecurity.isPermittedUrl(url))
        }
    }

    @Test
    fun testAssessUrl_invalidUrls() {
        val invalidUrls = listOf(
            "",
            "   ",
            "ftp://api.openai.com/v1",
            "file:///data/local/tmp",
            "http://",
            "https://",
            "not-a-valid-url",
            "://invalid",
            "javascript:void(0)"
        )
        for (url in invalidUrls) {
            assertEquals("Expected INVALID for '$url'", UrlSecurityStatus.INVALID, NetworkSecurity.assessUrl(url))
            assertFalse("Expected isPermittedUrl=false for '$url'", NetworkSecurity.isPermittedUrl(url))
        }
    }

    @Test
    fun testRequirePermittedEndpoint() {
        // Valid endpoints should not throw
        NetworkSecurity.requirePermittedEndpoint("https://api.openai.com/v1")
        NetworkSecurity.requirePermittedEndpoint("http://localhost:11434/v1")
        NetworkSecurity.requirePermittedEndpoint("http://10.0.2.2:8000")

        // Insecure endpoint must throw InsecureEndpointException
        try {
            NetworkSecurity.requirePermittedEndpoint("http://api.openai.com/v1")
            fail("Expected InsecureEndpointException for remote HTTP")
        } catch (e: InsecureEndpointException) {
            assertTrue(e.message?.contains("HTTPS") == true)
        }

        // Invalid endpoint must throw InsecureEndpointException
        try {
            NetworkSecurity.requirePermittedEndpoint("not-a-valid-url")
            fail("Expected InsecureEndpointException for invalid URL")
        } catch (e: InsecureEndpointException) {
            assertTrue(e.message?.contains("Ungültige") == true)
        }
    }

    @Test
    fun testOpenAiChatClient_rejectsInsecureEndpoint() {
        val dummyBody = buildJsonObject { put("model", "test") }
        try {
            OpenAiChatClient.postChat("http://api.openai.com/v1/chat/completions", "key", dummyBody)
            fail("Expected InsecureEndpointException")
        } catch (e: InsecureEndpointException) {
            assertTrue(e.message?.contains("HTTPS") == true)
        }
    }

    @Test
    fun testApiClient_rejectsInsecureEndpoints() = runTest {
        val testDispatcher = StandardTestDispatcher(testScheduler)
        val apiClient = ApiClient(AppDispatchers(main = testDispatcher, io = testDispatcher, default = testDispatcher))

        // 1. fetchModels
        val fetchResult = apiClient.fetchModels("http://api.openai.com/v1", "key")
        assertTrue(fetchResult.isFailure)
        assertTrue(fetchResult.exceptionOrNull()?.cause is InsecureEndpointException || fetchResult.exceptionOrNull() is InsecureEndpointException)

        // 2. splitGoals
        val splitResult = apiClient.splitGoals(
            SplitGoalsRequest("http://api.openai.com/v1", "key", "model", "Ziele", "Intro", "Rules")
        )
        assertTrue(splitResult.isFailure)
        assertTrue(splitResult.exceptionOrNull()?.cause is InsecureEndpointException || splitResult.exceptionOrNull() is InsecureEndpointException)

        // 3. analyze
        val analyzeResult = apiClient.analyze(
            imageDataUri = "data:image/jpeg;base64,1234",
            settings = Settings(baseUrl = "http://api.openai.com/v1", apiKey = "key", model = "m", goals = listOf("G1")),
            promptBundle = PromptBundle("intro", "format", "ampel", "exactlyOne", "lang", "rec", "honesty", "note")
        )
        assertTrue(analyzeResult.isFailure)
        assertTrue(analyzeResult.exceptionOrNull()?.cause is InsecureEndpointException || analyzeResult.exceptionOrNull() is InsecureEndpointException)
    }

    @Test
    fun testSetupViewModel_urlSecurityStatus() {
        val fakeApi = FakeApiService()
        val fakeBackup = FakeBackupService()
        val fakeRepo = FakeSettingsRepository()
        val vm = SetupViewModel(fakeApi, fakeBackup, fakeRepo)

        vm.setUrl("http://api.openai.com/v1")
        assertEquals(UrlSecurityStatus.INSECURE_REMOTE_HTTP, vm.urlSecurityStatus)
        assertFalse(vm.isUrlPermitted)

        vm.setUrl("http://localhost:11434")
        assertEquals(UrlSecurityStatus.LOCAL_CLEARTEXT_HTTP, vm.urlSecurityStatus)
        assertTrue(vm.isUrlPermitted)

        vm.setUrl("https://api.openai.com/v1")
        assertEquals(UrlSecurityStatus.SECURE_HTTPS, vm.urlSecurityStatus)
        assertTrue(vm.isUrlPermitted)

        vm.setUrl("invalid url")
        assertEquals(UrlSecurityStatus.INVALID, vm.urlSecurityStatus)
        assertFalse(vm.isUrlPermitted)
    }
}
