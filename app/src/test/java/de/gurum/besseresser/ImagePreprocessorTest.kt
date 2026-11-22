package de.gurum.besseresser

import android.net.TestUri
import de.gurum.besseresser.data.openStreamRobust
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class ImagePreprocessorTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    @Test
    fun openStreamRobust_readsExistingFileViaFilePath() {
        val testFile = tempFolder.newFile("test_photo.jpg")
        testFile.writeText("sample photo bytes")

        val uri = TestUri(testFile.absolutePath)
        val stream = openStreamRobust(resolver = null, uri = uri)
        assertNotNull(stream)
        assertEquals("sample photo bytes", stream?.bufferedReader()?.readText())
    }

    @Test
    fun openStreamRobust_readsFileSchemeUri() {
        val testFile = tempFolder.newFile("test_file_scheme.jpg")
        testFile.writeText("file scheme test")

        val uri = TestUri("file://${testFile.absolutePath}")
        val stream = openStreamRobust(resolver = null, uri = uri)
        assertNotNull(stream)
        assertEquals("file scheme test", stream?.bufferedReader()?.readText())
    }

    @Test
    fun openStreamRobust_returnsNullForNonExistentFile() {
        val uri = TestUri("file:///non/existent/path/photo.jpg")
        val stream = openStreamRobust(resolver = null, uri = uri)
        assertNull(stream)
    }

    @Test
    fun openStreamRobust_returnsNullForEmptyFile() {
        val emptyFile = tempFolder.newFile("empty.jpg")
        val uri = TestUri(emptyFile.absolutePath)
        val stream = openStreamRobust(resolver = null, uri = uri)
        assertNull(stream)
    }
}
