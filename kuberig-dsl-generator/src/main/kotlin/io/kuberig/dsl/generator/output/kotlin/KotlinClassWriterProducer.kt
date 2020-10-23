/*
 * Copyright 2019 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package io.kuberig.dsl.generator.output.kotlin

import java.io.BufferedWriter
import java.io.File
import java.io.FileOutputStream
import java.io.OutputStreamWriter
import java.nio.charset.StandardCharsets

class KotlinClassWriterProducer(private val sourceOutputDirectory : File) {

    /**
     * @param typeName The absolute type name (including package name).
     */
    fun classWriter(typeName : String) : BufferedWriter {
        val allSplits = typeName.split(".")
        val directorySplits = allSplits.subList(0, allSplits.size - 1)

        var currentDirectory = sourceOutputDirectory
        if (!currentDirectory.exists()) {
            currentDirectory.mkdirs()
        }
        directorySplits.forEach {
            currentDirectory = File(currentDirectory, it)
            if (!currentDirectory.exists() && !currentDirectory.mkdir()) {
                throw IllegalStateException("Failed to create ${currentDirectory.absolutePath}")
            }
        }

        return BufferedWriter(OutputStreamWriter(FileOutputStream(
            File(currentDirectory, allSplits.last() + ".kt")),
            StandardCharsets.UTF_8)
        )
    }

}