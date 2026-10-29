package io.github.opencodeandroid.runtime

import androidx.test.platform.app.InstrumentationRegistry
import io.github.opencodeandroid.runtime.update.*
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

/** Exercises the real Python/JCA producer against the installed APK identity; never downloads or installs. */
class SignedFeedHostFixtureTest {
    @Test fun productionFeedToolMatchesActualApkPublisherAndBundledCore() {
        assumeTrue(InstrumentationRegistry.getArguments().getString("includeHostSignedFeed") == "true")
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val fixture = File(context.getExternalFilesDir(null), "updates/native-publisher.ocafeed")
        try {
            check(fixture.isFile && fixture.length() <= SignedUpdateFeed.MAX_BYTES)
            val verifier = SignedOpenCodeUpdate(context)
            val feed = SignedUpdateFeed.inspect(fixture.readBytes(), verifier, context.packageManager.getPackageInfo(context.packageName, 0).longVersionCode)
            val publisher = PublishedOpenCode(RuntimePaths(context))
            val metadata = publisher.metadata(checkNotNull(publisher.bundled()))
            assertEquals(metadata.version, feed.version); assertEquals(metadata.commit, feed.commit)
            assertEquals(metadata.manifestSha256, feed.manifestSha256); assertEquals(metadata.binarySha256, feed.binarySha256)
            assertTrue(feed.packageSize > metadata.archiveSize)
            assertTrue(feed.packageUrl.startsWith("https://github.com/fixture-owner/fixture-repository/releases/download/v0.1.0-fixture/"))
        } finally { fixture.delete() }
    }
}
