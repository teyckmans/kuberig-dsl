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

import io.kuberig.dsl.generator.meta.DslTypeName
import io.kuberig.dsl.generator.meta.collections.DslMapDslMeta

class KotlinMapDslTypeGenerator(private val classWriterProducer : KotlinClassWriterProducer,
                                private val factoryMethodsGenerator: KotlinFactoryMethodsGenerator) {

    fun generateMapDslType(mapDslMeta: DslMapDslMeta) {
        KotlinClassWriter(mapDslMeta.declarationType(), this.classWriterProducer).use { classWriter ->

            val resultMapItemType = mapDslMeta.meta.itemType.typeShortName()

            classWriter.typeAnnotation("io.kuberig.dsl.KubeRigDslMarker")

            classWriter.typeInterface(
                "DslType<Map<String, $resultMapItemType>>",
                listOf("io.kuberig.dsl.DslType", mapDslMeta.meta.itemType.absoluteName)
            )

            val mapItemType = mapDslMeta.dslItemType().typeShortName()

            classWriter.mapTypeAttribute(
                listOf("private", "val"),
                "map",
                DslTypeName("MutableMap"),
                DslTypeName("String"),
                DslTypeName(mapDslMeta.dslItemType().absoluteName),
                "mutableMapOf()"
            )

            val addMethodName = mapDslMeta.addMethodName()

            classWriter.typeMethod(
                methodName = addMethodName,
                methodParameters = listOf(
                    Pair("key", "String"),
                    Pair("value", mapItemType)
                ),
                methodCode = listOf(
                    "this.map[key] = value"
                )
            )

            classWriter.typeMethod(
                methodName = addMethodName,
                methodParameters = listOf(
                    Pair("pair", "Pair<String, $mapItemType>")
                ),
                methodCode = listOf(
                    "this.map[pair.first] = pair.second"
                )
            )

            if (mapDslMeta.complexItemType()) {
                classWriter.typeMethod(
                    methodName = addMethodName,
                    methodParameters = listOf(
                        Pair("key", "String"),
                        Pair("init", "$mapItemType.() -> Unit")
                    ),
                    methodCode = listOf(
                        "val itemValue = $mapItemType()",
                        "itemValue.init()",
                        "this.map[key] = itemValue"
                    )
                )

                classWriter.typeMethod(
                    modifiers = listOf("override"),
                    methodName = "toValue",
                    methodReturnType = "Map<String, $resultMapItemType>",
                    methodCode = listOf(
                        "return Collections.unmodifiableMap(this.map.entries.stream()",
                        "    .collect(Collectors.toMap(",
                        "        { e -> e.key },",
                        "        { e -> e.value.toValue() }",
                        "    )))"
                    ),
                    methodTypeDependencies = listOf(
                        "java.util.stream.Collectors",
                        "java.util.Collections"
                    )
                )
            }
            else {
                classWriter.typeMethod(
                    modifiers = listOf("override"),
                    methodName = "toValue",
                    methodReturnType = "Map<String, $resultMapItemType>",
                    methodCode = listOf(
                        "return Collections.unmodifiableMap(this.map)"
                    ),
                    methodTypeDependencies = listOf(
                        "java.util.Collections"
                    )
                )
            }

            factoryMethodsGenerator.addFactoryMethod(
                mapDslMeta.declarationType().methodName(),
                mapDslMeta.declarationType()
            )
        }
    }

}