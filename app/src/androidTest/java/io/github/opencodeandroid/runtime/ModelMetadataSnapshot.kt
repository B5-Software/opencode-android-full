package io.github.opencodeandroid.runtime

import android.database.sqlite.SQLiteDatabase
import android.os.Bundle
import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONObject
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.util.UUID
import java.util.concurrent.TimeUnit

/** Read-only diagnostic of normal user state. No credential values, names, endpoints or model IDs leave the device. */
class ModelMetadataSnapshot {
    @Test fun collectNormalProfileMetadataWithoutReadingCredentialValues() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val paths = RuntimePaths(context)
        val report = JSONObject().put("diagnostic_only", true).put("database_credential_values_selected", false)
        val database = File(paths.state, "data/opencode/opencode-dev.db")
        report.put("normal_database_exists", database.isFile)
        if (database.isFile) {
            try {
                SQLiteDatabase.openDatabase(database.path, null, SQLiteDatabase.OPEN_READONLY or SQLiteDatabase.NO_LOCALIZED_COLLATORS).use { db ->
                    db.rawQuery("SELECT count(*), coalesce(sum(active = 1), 0) FROM credential WHERE integration_id IS NOT NULL", null).use {
                        check(it.moveToFirst()); report.put("credential_rows", it.getLong(0)).put("active_credential_rows", it.getLong(1))
                    }
                }
            } catch (_: Exception) { report.put("database_count_unavailable", true) }
        }
        val root = File(paths.config, "model-metadata-${UUID.randomUUID()}").apply { check(mkdir()) }
        try {
            context.assets.open("plugins/opencode-android-lsp/config.mjs").use { input -> File(root, "config.mjs").outputStream().use { input.copyTo(it) } }
            WorkspaceContext.initialize(context)
            val script = """
                import fs from 'node:fs'; import path from 'node:path'; import {pathToFileURL} from 'node:url';
                const {parseJSONC} = await import(pathToFileURL(process.argv[1]).href);
                const report = {config_files:0, parse_errors:0, skipped_files:0, provider_override_entries:0, raw_api_key_fields:0, default_model_fields:0};
                const files = [];
                for (const name of ['opencode.json','opencode.jsonc']) files.push('/opencode-state/config/opencode/'+name);
                let directory=process.argv[2];
                for (;;) {
                  for (const base of [directory,path.join(directory,'.opencode')]) for (const name of ['opencode.json','opencode.jsonc']) files.push(path.join(base,name));
                  const parent=path.dirname(directory); if(parent===directory) break; directory=parent;
                }
                for (const name of ['opencode.json','opencode.jsonc']) files.push('/etc/opencode/'+name);
                for (const file of new Set(files)) {
                  let metadata; try{metadata=fs.lstatSync(file)}catch(error){if(error.code!=='ENOENT')report.skipped_files++;continue}
                  if(!metadata.isFile() || metadata.size>2*1024*1024){report.skipped_files++;continue}
                  report.config_files++;
                  try {
                    const config=parseJSONC(fs.readFileSync(file,'utf8'));
                    if(config.model && (typeof config.model==='object' || typeof config.model==='string'))report.default_model_fields++;
                    for(const value of Object.values(config.providers ?? config.provider ?? {})){
                      report.provider_override_entries++;
                      const key=value?.settings?.apiKey ?? value?.options?.apiKey;
                      if(typeof key==='string' && key.length)report.raw_api_key_fields++;
                    }
                  } catch {report.parse_errors++}
                }
                process.stdout.write(JSON.stringify(report));
            """.trimIndent()
            val process = ProotCommand(paths).process(listOf("/usr/bin/node", "--input-type=module", "-e", script,
                "/android-runtime/${root.name}/config.mjs", WorkspaceContext.directory.value)).start()
            val output = StringBuilder()
            val reader = Thread({ process.inputStream.bufferedReader().use { output.append(it.readText()) } }, "model-metadata-output").apply { isDaemon = true; start() }
            try {
                check(process.waitFor(15, TimeUnit.SECONDS)) { "Metadata reader timed out" }; reader.join(2000)
                check(process.exitValue() == 0) { "Metadata reader failed" }
                report.put("config_metadata", JSONObject(output.toString()))
            } finally { if (process.isAlive) process.destroyForcibly(); process.waitFor(3, TimeUnit.SECONDS); reader.join(2000) }
        } finally {
            check(root.canonicalFile.parentFile == paths.config.canonicalFile)
            Files.walk(root.toPath()).use { it.sorted(Comparator.reverseOrder()).forEach(Files::delete) }
        }
        instrumentation.sendStatus(2, Bundle().apply { putString("stream", "\nMODEL_METADATA_SNAPSHOT=$report\nDiagnostic metadata only, not a model connection or inference acceptance test.\n") })
    }
}
