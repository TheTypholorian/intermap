package net.typho.intermap

import com.chocohead.mm.api.ClassTinkerers
import net.fabricmc.loader.api.FabricLoader
import net.fabricmc.loader.impl.lib.mappingio.MappingReader
import net.fabricmc.loader.impl.lib.mappingio.format.MappingFormat
import net.fabricmc.loader.impl.lib.mappingio.format.tiny.Tiny1FileReader
import net.fabricmc.loader.impl.lib.mappingio.format.tiny.Tiny2FileReader
import net.fabricmc.loader.impl.lib.mappingio.tree.MemoryMappingTree
import net.fabricmc.loader.impl.util.mappings.FilteringMappingVisitor
import org.objectweb.asm.ClassReader
import org.objectweb.asm.ClassWriter
import org.objectweb.asm.commons.ClassRemapper
import org.objectweb.asm.commons.Remapper
import org.objectweb.asm.tree.ClassNode
import org.spongepowered.asm.mixin.extensibility.IMixinConfigPlugin
import org.spongepowered.asm.mixin.extensibility.IMixinInfo
import java.io.BufferedReader
import java.io.File
import java.io.IOException
import java.io.InputStreamReader
import java.nio.file.Files
import java.nio.file.Paths

class MixinPlugin : IMixinConfigPlugin {
    override fun onLoad(mixinPackage: String) {
        val mappings = MemoryMappingTree()

        try {
            val connection = MixinPlugin::class.java.getClassLoader().getResource("assets/intermap/mappings/mappings.tiny")!!.openConnection()

            if (connection != null) {
                BufferedReader(InputStreamReader(connection.getInputStream())).use { reader ->
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
            }
        } catch (e: IOException) {
            throw RuntimeException(e)
        }

        val target = mappings.getNamespaceId(FabricLoader.getInstance().mappingResolver.currentRuntimeNamespace);
        val named = mappings.getNamespaceId("named")

        println("Testing mappings: ")

        println("\t" + mappings.mapClassName("net/minecraft/client/Minecraft", named, target))
        println("\t" + mappings.mapClassName("net/minecraft/network/codec/ByteBufCodecs", named, target))

        val mods = arrayOf(
            "big_shot_api",
            "big_shot_lib",
            "vibrancy",
        )

        for (mod in mods) {
            val container = FabricLoader.getInstance().getModContainer(mod).orElseThrow()

            for (rootPath in container.rootPaths) {
                try {
                    Files.walk(rootPath).use { stream ->
                        stream.filter { it.toString().endsWith(".class") }.forEach { classPath ->
                            val relPath = rootPath.relativize(classPath).toString()
                            val namePath = relPath.substring(0, relPath.length - 6).replace(File.separatorChar, '.')
                            //val info = ClassInfo.forName(namePath)

                            //println("$namePath ${info.isMixin}")

                            if (!namePath.contains("Mixin") && !namePath.contains("Accessor")) {
                                ClassTinkerers.addTransformation(namePath) { node ->
                                    val writer = ClassWriter(0)
                                    val remapper = ClassRemapper(writer, object : Remapper() {
                                        override fun mapMethodName(
                                            owner: String,
                                            name: String,
                                            descriptor: String
                                        ): String {
                                            return mappings.getMethod(owner, name, descriptor, named)?.getName(target) ?: name
                                        }

                                        override fun mapFieldName(
                                            owner: String,
                                            name: String,
                                            descriptor: String
                                        ): String {
                                            return mappings.getField(owner, name, descriptor, named)?.getName(target) ?: name
                                        }

                                        override fun map(internalName: String): String? {
                                            return mappings.mapClassName(internalName, named, target)
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
                                    Files.write(Paths.get(namePath.replace('/', '_').replace('.', '_') + ".class"), writer.toByteArray())
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