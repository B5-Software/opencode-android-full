package io.github.opencodeandroid.files

import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.util.UUID

class ProjectFilesTest {
    private fun scenario(action: (ProjectFiles, File, File) -> Unit) {
        val cache = InstrumentationRegistry.getInstrumentation().targetContext.cacheDir.canonicalFile
        val temporary = File(cache, "project-files-${UUID.randomUUID()}")
        check(temporary.canonicalFile.parentFile == cache)
        check(temporary.mkdir())
        val workspace = File(temporary, "workspace")
        val repository = ProjectFiles(workspace, File(temporary, "trash"))
        try { action(repository, workspace, temporary) }
        finally {
            check(temporary.canonicalFile.parentFile == cache)
            Files.walk(temporary.toPath()).use { it.sorted(Comparator.reverseOrder()).forEach(Files::delete) }
        }
    }

    @Test fun importEditConflictRenameAndRecoverDirectory() = scenario { repository, root, _ ->
        repository.create("", "项目", true)
        val name = repository.import("项目", "hello.txt", "hello\n".byteInputStream())
        assertEquals("hello.txt", name)
        assertEquals("hello (1).txt", repository.import("项目", "hello.txt", "another".byteInputStream()))
        val document = repository.read("项目/hello.txt")
        val saved = repository.save(document, "updated\n")
        assertEquals("updated\n", File(root, "项目/hello.txt").readText())
        File(root, "项目/hello.txt").writeText("external change")
        assertTrue(runCatching { repository.save(saved, "must not overwrite") }.isFailure)
        assertEquals("external change", File(root, "项目/hello.txt").readText())
        repository.rename("项目/hello.txt", "renamed.txt")
        assertTrue(runCatching { repository.rename("项目/renamed.txt", "hello (1).txt") }.isFailure)
        repository.trash("项目")
        assertTrue(repository.list("").isEmpty())
        val trash = repository.trashEntries().single()
        repository.restore(trash.id)
        val output = java.io.ByteArrayOutputStream()
        repository.export("项目/renamed.txt", output)
        assertEquals("external change", output.toString("UTF-8"))
        assertTrue(repository.trashEntries().isEmpty())
    }

    @Test fun blocksTraversalAndSymlinkReadsWithoutTouchingTarget() = scenario { repository, root, temporary ->
        val outside = File(temporary, "outside").apply { mkdir() }
        File(outside, "secret").writeText("PRIVATE")
        Files.createSymbolicLink(File(root, "link").toPath(), outside.toPath())
        assertTrue(repository.list("").single().link)
        for (path in listOf("../outside/secret", "/etc/passwd", "link/secret")) {
            assertTrue(path, runCatching { repository.read(path) }.isFailure)
        }
        assertTrue(runCatching { repository.create("link", "new", false) }.isFailure)
        assertFalse(File(outside, "new").exists())
        repository.trash("link")
        assertEquals("PRIVATE", File(outside, "secret").readText())
        repository.restore(repository.trashEntries().single().id)
        assertTrue(Files.isSymbolicLink(File(root, "link").toPath()))
        assertTrue(root.renameTo(File(temporary, "old-workspace")))
        Files.createSymbolicLink(root.toPath(), outside.toPath())
        assertTrue(runCatching { repository.list("") }.isFailure)
    }

    @Test fun failedImportLeavesNoPartialDestinationAndBinaryRemainsIntact() = scenario { repository, root, _ ->
        val failed = object : java.io.InputStream() { override fun read(): Int = throw java.io.IOException("fixture interruption") }
        assertTrue(runCatching { repository.import("", "partial", failed) }.isFailure)
        assertTrue(root.listFiles()!!.isEmpty())
        val binary = byteArrayOf(0, 1, 2, -1)
        repository.import("", "binary", binary.inputStream())
        assertTrue(runCatching { repository.read("binary") }.isFailure)
        val output = java.io.ByteArrayOutputStream()
        repository.export("binary", output)
        assertArrayEquals(binary, output.toByteArray())
    }

    @Test fun copyTreePreservesLinksAndModesAndMoveAvoidsOverwrite() = scenario { repository, root, _ ->
        repository.create("", "source", true); repository.create("", "target", true)
        val script = File(root, "source/build.sh").apply { writeText("#!/bin/sh\necho copied\n"); setExecutable(true, true) }
        Files.createSymbolicLink(File(root, "source/link").toPath(), java.nio.file.Paths.get("build.sh"))
        val copied = repository.transfer("source", "target", false)
        assertEquals("target/source", copied)
        assertEquals(script.readText(), File(root, "$copied/build.sh").readText())
        assertEquals(Files.getPosixFilePermissions(script.toPath()), Files.getPosixFilePermissions(File(root, "$copied/build.sh").toPath()))
        assertTrue(Files.isSymbolicLink(File(root, "$copied/link").toPath()))
        assertEquals("build.sh", Files.readSymbolicLink(File(root, "$copied/link").toPath()).toString())
        assertEquals("target/source (1)", repository.transfer("source", "target", true))
        assertFalse(File(root, "source").exists())
        assertTrue(File(root, "target/source/build.sh").isFile)
        assertTrue(runCatching { repository.transfer("target", "target/source", false) }.isFailure)
        assertEquals("target/source", repository.transfer("target/source", "target", true))
    }

    @Test fun cancelledCopyPreservesSourceAndRemovesStaging() = scenario { repository, root, temporary ->
        File(root, "large.bin").writeBytes(ByteArray(512 * 1024) { 19 })
        repository.create("", "destination", true)
        var copied = 0L
        val result = runCatching { repository.transfer("large.bin", "destination", false, { if (copied >= 128 * 1024) throw java.util.concurrent.CancellationException("test cancel") }) { copied = it.bytes } }
        assertTrue(result.exceptionOrNull() is java.util.concurrent.CancellationException)
        assertEquals(512L * 1024, File(root, "large.bin").length())
        assertFalse(File(root, "destination/large.bin").exists())
        assertTrue(File(temporary, "project-file-staging").listFiles()!!.isEmpty())
    }
}
