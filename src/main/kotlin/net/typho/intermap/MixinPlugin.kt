package net.typho.intermap

import com.chocohead.mm.api.ClassTinkerers
import com.google.common.collect.Queues
import kotlinx.io.files.FileNotFoundException
import net.fabricmc.loader.api.FabricLoader
import net.fabricmc.loader.impl.lib.mappingio.MappingReader
import net.fabricmc.loader.impl.lib.mappingio.format.MappingFormat
import net.fabricmc.loader.impl.lib.mappingio.format.tiny.Tiny1FileReader
import net.fabricmc.loader.impl.lib.mappingio.format.tiny.Tiny2FileReader
import net.fabricmc.loader.impl.lib.mappingio.tree.MappingTreeView
import net.fabricmc.loader.impl.lib.mappingio.tree.MemoryMappingTree
import net.fabricmc.loader.impl.util.mappings.FilteringMappingVisitor
import net.fabricmc.loader.impl.util.mappings.MixinIntermediaryDevRemapper
import net.fabricmc.loader.impl.util.mappings.MixinRemapper
import org.objectweb.asm.ClassReader
import org.objectweb.asm.ClassWriter
import org.objectweb.asm.commons.ClassRemapper
import org.objectweb.asm.commons.Remapper
import org.objectweb.asm.tree.ClassNode
import org.spongepowered.asm.mixin.MixinEnvironment
import org.spongepowered.asm.mixin.extensibility.IMixinConfigPlugin
import org.spongepowered.asm.mixin.extensibility.IMixinInfo
import org.spongepowered.asm.mixin.transformer.ClassInfo
import java.io.BufferedReader
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.InputStreamReader
import java.net.URI
import java.nio.file.Files
import java.nio.file.Paths
import kotlin.io.path.absolute
import kotlin.io.path.inputStream
import kotlin.io.path.outputStream

class MixinPlugin : IMixinConfigPlugin {
    companion object {
        @JvmField
        val mappings: MemoryMappingTree?

        init {
            if (FabricLoader.getInstance().isDevelopmentEnvironment) {
                mappings = null
            } else {
                mappings = MemoryMappingTree()

                try {
                    val stream = getMappingsStream()

                    BufferedReader(InputStreamReader(stream)).use { reader ->
                        val filter = FilteringMappingVisitor(mappings)
                        reader.mark(4096)
                        val format = MappingReader.detectFormat(reader)
                        reader.reset()
                        when (format) {
                            MappingFormat.TINY_FILE -> Tiny1FileReader.read(reader, filter)
                            MappingFormat.TINY_2_FILE -> Tiny2FileReader.read(reader, filter)
                            else -> throw UnsupportedOperationException("Unsupported mapping format: $format")
                        }
                    }
                } catch (e: IOException) {
                    throw RuntimeException(e)
                }
            }
        }

        @JvmStatic
        fun getMappingsStream(
            version: String = FabricLoader
                .getInstance()
                .getModContainer("minecraft")
                .orElseThrow()
                .metadata
                .version
                .friendlyString
        ): InputStream {
            val cached = Paths.get("intermap_mappings/$version.tiny")

            if (!Files.exists(cached.parent)) {
                Files.createDirectories(cached.parent)
            }

            if (Files.exists(cached)) {
                println("[Intermap] Found cached mappings at ${cached.absolute()}")
                return cached.inputStream()
            }

            val url = "https://raw.githubusercontent.com/TheTypholorian/intermap/refs/heads/master/mappings/$version.tiny"
            val connection = URI.create(url)
                .toURL()
                .openConnection() ?: throw FileNotFoundException("Could not get mappings for minecraft version $version")

            connection.getInputStream().use { it.transferTo(cached.outputStream()) }
            println("[Intermap] Loading mappings from $url, caching to ${cached.absolute()}")
            return cached.inputStream()
        }
    }

    override fun onLoad(mixinPackage: String) {
        if (mappings != null) {
            MixinEnvironment.getDefaultEnvironment().remappers.add(object : MixinRemapper(
                mappings,
                mappings.getNamespaceId("named"),
                mappings.getNamespaceId(FabricLoader.getInstance().mappingResolver.currentRuntimeNamespace)
            ) {
                override fun map(typeName: String?): String? {
                    println("map $typeName")
                    return super.map(typeName)
                }
            })
        }
    }

    override fun getRefMapperConfig(): String? {
        return null
    }

    override fun shouldApplyMixin(targetClassName: String, mixinClassName: String): Boolean {
        return true
    }

    override fun acceptTargets(
        myTargets: Set<String>,
        otherTargets: Set<String>
    ) {
        if (mappings == null) {
            return
        }

        val target = mappings.getNamespaceId(FabricLoader.getInstance().mappingResolver.currentRuntimeNamespace)
        val named = mappings.getNamespaceId("named")
        val classMappingsCache = HashMap<String, MappingTreeView.ClassMappingView?>()

        fun lookupClass(name: String): MappingTreeView.ClassMappingView? {
            return classMappingsCache.computeIfAbsent(name) { key -> mappings.getClass(key, named) ?: mappings.getClass(key, target) }
        }

        for (mod in FabricLoader.getInstance().allMods) {
            mod.metadata.customValues["intermap"]?.asObject?.let { settings ->
                if (settings["enabled"]?.asBoolean == true) {
                    println("[Intermap] Remapping mod ${mod.metadata.id}")
                    val aliases = settings["aliases"]?.asObject?.associate { it.key to it.value.asString } ?: emptyMap()

                    for (rootPath in mod.rootPaths) {
                        try {
                            Files.walk(rootPath).use { stream ->
                                stream.filter { it.toString().endsWith(".class") }.forEach { classPath ->
                                    val relPath = rootPath.relativize(classPath).toString()
                                    val namePath = relPath.substring(0, relPath.length - 6).replace(File.separatorChar, '.')
                                    val info = ClassInfo.forName(namePath)

                                    if (!info.isMixin) {
                                        ClassTinkerers.addTransformation(namePath) { node ->
                                            val writer = ClassWriter(0)
                                            val remapper = ClassRemapper(writer, object : Remapper() {
                                                override fun mapMethodName(
                                                    owner: String,
                                                    name: String,
                                                    descriptor: String
                                                ): String {
                                                    val queue = Queues.newArrayDeque<String>(listOf(owner))

                                                    while (!queue.isEmpty()) {
                                                        val og = queue.poll()
                                                        val cls = lookupClass(aliases.getOrDefault(og, og)) ?: continue

                                                        val method = cls.getMethod(name, descriptor, named)?.getName(target)

                                                        if (method != null) {
                                                            return method
                                                        }

                                                        ClassInfo.forName(cls.getName(target) ?: og)?.let { info ->
                                                            info.superClass?.let { queue.add(it.name) }
                                                            queue.addAll(info.interfaces)
                                                        }
                                                    }

                                                    return name
                                                }

                                                override fun mapFieldName(
                                                    owner: String,
                                                    name: String,
                                                    descriptor: String
                                                ): String {
                                                    val queue = Queues.newArrayDeque<String>(listOf(owner))

                                                    while (!queue.isEmpty()) {
                                                        val og = queue.poll()
                                                        val cls = lookupClass(aliases.getOrDefault(og, og)) ?: continue

                                                        val field = cls.getField(name, descriptor, named)?.getName(target)

                                                        if (field != null) {
                                                            return field
                                                        }

                                                        ClassInfo.forName(cls.getName(target) ?: og)?.let { info ->
                                                            info.superClass?.let { queue.add(it.name) }
                                                            queue.addAll(info.interfaces)
                                                        }
                                                    }

                                                    return name
                                                }

                                                override fun map(internalName: String): String? {
                                                    return lookupClass(aliases.getOrDefault(internalName, internalName))?.getName(target)
                                                }
                                            })
                                            node.accept(remapper)

                                            val reader = ClassReader(writer.toByteArray())
                                            node.methods?.clear()
                                            node.fields?.clear()
                                            node.innerClasses?.clear()
                                            node.attrs?.clear()
                                            node.invisibleAnnotations?.clear()
                                            node.invisibleTypeAnnotations?.clear()
                                            node.nestMembers?.clear()
                                            node.permittedSubclasses?.clear()
                                            node.visibleAnnotations?.clear()
                                            node.visibleTypeAnnotations?.clear()
                                            reader.accept(node, 0)
                                        }
                                    }
                                }
                            }
                        } catch (e: IOException) {
                            throw RuntimeException(e)
                        }
                    }
                }
            }
        }
    }

    override fun getMixins(): List<String> {
        return listOf()
    }

    override fun preApply(
        targetClassName: String,
        targetClass: ClassNode,
        mixinClassName: String,
        mixinInfo: IMixinInfo
    ) {
    }

    override fun postApply(
        targetClassName: String,
        targetClass: ClassNode,
        mixinClassName: String,
        mixinInfo: IMixinInfo
    ) {
    }
}