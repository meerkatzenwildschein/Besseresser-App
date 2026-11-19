package de.gurum.besseresser

import de.gurum.besseresser.data.AppDispatchers
import de.gurum.besseresser.data.PhotoStorage
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

@OptIn(ExperimentalCoroutinesApi::class)
class PhotoStorageOrientationTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    @Test
    fun correctOrientation_handlesNonExistentFileGracefully() = runTest {
        val nonExistent = File(tempFolder.root, "does_not_exist.jpg")
        assertFalse(nonExistent.exists())

        val testDispatcher = StandardTestDispatcher(testScheduler)
        // Should execute smoothly on dispatcher without throwing
        PhotoStorage.correctOrientation(nonExistent, testDispatcher)
        assertFalse(nonExistent.exists())
    }

    @Test
    fun correctOrientation_handlesEmptyOrCorruptFileGracefully() = runTest {
        val dummyFile = tempFolder.newFile("dummy.jpg")
        dummyFile.writeBytes(byteArrayOf(1, 2, 3, 4, 5))
        assertTrue(dummyFile.exists())

        val testDispatcher = StandardTestDispatcher(testScheduler)
        PhotoStorage.correctOrientation(dummyFile, testDispatcher)
        assertTrue(dummyFile.exists())
    }

    @Test
    fun correctOrientation_instanceMethodWithCustomDispatchers_executesSafely() = runTest {
        val dummyFile = tempFolder.newFile("instance_dummy.jpg")
        dummyFile.writeBytes(byteArrayOf(10, 20, 30))
        assertTrue(dummyFile.exists())

        val testDispatcher = StandardTestDispatcher(testScheduler)
        val dispatchers = AppDispatchers(io = testDispatcher, main = testDispatcher, default = testDispatcher)
        val photoStorage = PhotoStorage(context = org.koin.test.KoinTestRule::class.java.classLoader.let {
            // Dummy mock context or null if possible; PhotoStorage only uses context for dir() / contentResolver
            // For correctOrientation, only file parameter is used
            android.app.Application()
        }, dispatchers = dispatchers)

        photoStorage.correctOrientation(dummyFile)
        assertTrue(dummyFile.exists())
    }

    @Test
    fun correctOrientationSync_handlesErrorsWithoutCrashing() {
        val dummyFile = tempFolder.newFile("corrupt.jpg")
        dummyFile.writeText("not a valid jpeg")

        PhotoStorage.correctOrientationSync(dummyFile)
        assertTrue(dummyFile.exists())
    }
}
