package io.github.opencodeandroid.runtime

import android.content.Intent
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.opencodeandroid.MainActivity
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID
import java.util.concurrent.TimeUnit

/** Verifies the bundled suite offline and protects user data during the explicit upgrade. */
@RunWith(AndroidJUnit4::class)
class DeveloperPackAcceptanceTest {
    @Test fun bundledSuiteBuildsRealProgramsAndPreservesUserData() {
        assumeTrue("Developer pack acceptance targets ARM64", android.os.Build.SUPPORTED_ABIS.contains("arm64-v8a"))
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val paths = RuntimePaths(context)
        paths.prepare()
        val name = "devkit-acceptance-${UUID.randomUUID()}"
        val directory = File(paths.workspace, name).apply { mkdir() }
        val homeProbe = File(paths.home, "$name.txt").apply { writeText(name) }
        val stateProbe = File(paths.state, "$name.txt").apply { writeText(name) }
        File(directory, "preserve.txt").writeText(name)
        val activity = instrumentation.startActivitySync(Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        try {
            val installer = RootfsInstaller(context, paths)
            if (!paths.installed) installer.install(::println)
            else if (installer.updateAvailable()) {
                val oldManifest = File(paths.rootfs, ".installed-pack").readText()
                val existing = paths.backups.listFiles().orEmpty().map { it.name }.toSet()
                installer.updateBundled(::println)
                val backup = paths.backups.listFiles().orEmpty().single { it.name !in existing }
                assertEquals(oldManifest, File(backup, ".installed-pack").readText())
            }
            assertEquals(name, File(directory, "preserve.txt").readText())
            assertEquals(name, homeProbe.readText())
            assertEquals(name, stateProbe.readText())
            val manifest = JSONObject(File(paths.rootfs, ".installed-pack").readText())
            assertEquals("developer-v1", manifest.getJSONObject("developer").getString("profile"))
            assertFalse(installer.updateAvailable())
            val script = """
                set -euo pipefail
                cd '/workspace/$name'
                for tool in curl wget git gh ssh gcc g++ clang ld.lld make cmake ninja pkg-config node npm python3 pip3 rustc cargo gdb strace file patch diff jq rg fdfind less nano vi tmux zip unzip xz bzip2 tar gzip; do command -v "${'$'}tool"; done
                curl --version | head -n 1
                wget --version | head -n 1
                gh --version
                ssh -V
                ssh-keygen -q -t ed25519 -N '' -f acceptance-key
                ssh-keygen -l -f acceptance-key.pub
                dpkg --audit
                printf '#include <stdio.h>\nint answer(void);int main(void){printf("C_OK %%d\\n",answer());return answer()!=42;}\n' > main.c
                printf 'int answer(void){return 42;}\n' > answer.c
                gcc main.c answer.c -o dynamic && ./dynamic
                gcc -static main.c answer.c -o static && ./static
                clang main.c answer.c -o clang-app && ./clang-app
                printf '#include <iostream>\nint main(){std::cout << "CPP_OK" << std::endl;}\n' > main.cpp
                g++ -std=c++17 main.cpp -o cpp-app && ./cpp-app
                node -e "require('node:child_process').execFileSync('/bin/echo',['NODE_CHILD_OK'],{stdio:'inherit'})"
                npm --version
                python3 -c "import ctypes,errno,subprocess; c=ctypes.CDLL(None,use_errno=True); assert c.syscall(436,4294967295,4294967295,0)==-1 and ctypes.get_errno()==errno.ENOSYS; subprocess.run(['/usr/bin/true'],check=True);print('PYTHON_SUBPROCESS_OK')"
                python3 -m venv venv
                venv/bin/python -m pip --version
                mkdir -p rust/src
                printf '[package]\nname="offline-smoke"\nversion="0.1.0"\nedition="2021"\n' > rust/Cargo.toml
                printf 'fn main(){println!("RUST_OK");}\n' > rust/src/main.rs
                cargo run --offline --manifest-path rust/Cargo.toml
                git init --initial-branch=main git-project
                git init --bare --initial-branch=main remote.git
                cp main.c answer.c git-project/
                git -C git-project add main.c answer.c
                git -C git-project -c user.name=RuntimeAcceptance -c user.email=acceptance@localhost commit -m 'Verify multi-file project'
                git -C git-project remote add origin '/workspace/$name/remote.git'
                git -C git-project push origin main
                git --git-dir=remote.git show main:answer.c | grep 'return 42'
                printf 'GIT_LOCAL_PUSH_OK\n'
                printf 'PASS bundled developer suite offline\n'
            """.trimIndent()
            File(directory, "verify.sh").writeText(script)
            val child = ProotCommand(paths).process(listOf("/bin/bash", "/workspace/$name/verify.sh")).start()
            val output = StringBuilder()
            val drain = Thread {
                try { child.inputStream.bufferedReader().useLines { lines -> lines.forEach { line -> println(line); synchronized(output) { if (output.length < 128*1024) output.appendLine(line) } } } }
                catch (_: java.io.IOException) { }
            }.apply { start() }
            val done = child.waitFor(300, TimeUnit.SECONDS)
            if (!done) { child.destroyForcibly(); child.waitFor(5, TimeUnit.SECONDS) }
            drain.join(5000)
            assertTrue("Developer verification timed out: ${output.takeLast(6000)}", done)
            assertEquals(output.takeLast(6000).toString(), 0, child.exitValue())
            assertTrue(output.contains("PASS bundled developer suite offline"))
            instrumentation.sendStatus(2, android.os.Bundle().apply { putString("stream", output.toString()) })
            val port = File(directory, "preview-port")
            val guestPort = JSONObject.quote("/workspace/$name/preview-port")
            val preview = ProotCommand(paths).process(listOf("/usr/bin/node", "-e",
                "const s=require('node:http').createServer((q,r)=>r.end('PREVIEW_OK'));s.listen(0,'127.0.0.1',()=>require('node:fs').writeFileSync($guestPort,String(s.address().port)))")).start()
            try {
                val deadline = android.os.SystemClock.elapsedRealtime() + 15_000
                while (!port.exists() && preview.isAlive && android.os.SystemClock.elapsedRealtime() < deadline) Thread.sleep(50)
                assertTrue("Node preview did not start", port.isFile)
                val connection = java.net.URL("http://127.0.0.1:${port.readText().toInt()}/").openConnection() as java.net.HttpURLConnection
                try {
                    connection.connectTimeout = 5000; connection.readTimeout = 5000
                    assertEquals("PREVIEW_OK", connection.inputStream.bufferedReader().use { it.readText() })
                } finally { connection.disconnect() }
                instrumentation.sendStatus(2, android.os.Bundle().apply { putString("stream", "PASS Android HTTP client -> Debian Node development server\n") })
            } finally { preview.destroyForcibly(); preview.waitFor(5, TimeUnit.SECONDS) }
        } finally {
            instrumentation.runOnMainSync { activity.finish() }
            // Generated venv links must never be followed during test cleanup.
            java.nio.file.Files.walk(directory.toPath()).use { entries -> entries.sorted(Comparator.reverseOrder()).forEach { java.nio.file.Files.delete(it) } }
            homeProbe.delete(); stateProbe.delete()
        }
    }
}
