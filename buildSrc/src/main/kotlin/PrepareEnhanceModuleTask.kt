// Copyright 2026, AsteriskNG contributors
// SPDX-License-Identifier: GPL-3.0

import org.gradle.api.DefaultTask
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.FileSystemOperations
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.tasks.InputDirectory
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.TaskAction
import javax.inject.Inject

abstract class PrepareEnhanceModuleTask : DefaultTask() {
    @get:Inject
    abstract val fileSystemOperations: FileSystemOperations

    @get:InputDirectory
    abstract val moduleDirectory: DirectoryProperty

    @get:InputFile
    abstract val licenseFile: RegularFileProperty

    @get:InputDirectory
    abstract val nativeLibrariesDirectory: DirectoryProperty

    @get:OutputDirectory
    abstract val outputDirectory: DirectoryProperty

    @get:OutputDirectory
    abstract val generatedAssetsDir: DirectoryProperty

    @TaskAction
    fun prepare() {
        fileSystemOperations.sync {
            from(moduleDirectory)
            from(licenseFile)
            into(outputDirectory)
        }
        // Keep installer text independent of Windows checkout line endings.
        for (name in listOf("customize.sh", "post-fs-data.sh", "module.prop", "zn_modules.txt", "sepolicy.rule")) {
            val file = outputDirectory.file(name).get().asFile
            file.writeText(file.readText(Charsets.UTF_8).replace("\r\n", "\n").replace('\r', '\n'), Charsets.UTF_8)
        }
        fileSystemOperations.sync {
            from(nativeLibrariesDirectory) {
                include("*/libasterisk_enhance.so", "*/libae_resolver29.so", "*/runtime-identity.prop")
                into("enhance/lib")
            }
            into(generatedAssetsDir)
        }
    }
}

