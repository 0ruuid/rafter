package com.hiusers.rafter.asset

import java.nio.file.Files
import java.nio.file.Path
import javax.imageio.ImageIO

/** 按 PNG 发现图片，外观和字体来自主题。 */
object AutoAssets {
    fun document(root: Path): Map<String, Any?> {
        val themeDocument = UiThemes.document(root)
        UiThemes.compile(themeDocument)
        val images = linkedMapOf<String, Map<String, Any?>>()
        val directory = root.resolve("images")
        if (Files.isDirectory(directory))
            Files.walk(directory).use { paths ->
                paths
                    .filter { Files.isRegularFile(it) && it.fileName.toString().endsWith(".png") }
                    .sorted()
                    .forEach { path ->
                        require(
                            !Files.isSymbolicLink(path) &&
                                path.toRealPath().startsWith(root.toRealPath())
                        ) {
                            "Unsafe image $path"
                        }
                        require(Files.size(path) <= 16 * 1024 * 1024) {
                            "Image exceeds 16 MiB: $path"
                        }
                        val relative =
                            directory
                                .relativize(path)
                                .toString()
                                .replace('\\', '/')
                                .removeSuffix(".png")
                        val match = Regex("^(.*)@([1-8])x$").matchEntire(relative)
                        val id = "rafter:" + (match?.groupValues?.get(1) ?: relative)
                        require(!ImageSizing.isGeneratedAsset(id)) {
                            "Reserved generated image namespace: $id"
                        }
                        val scale = match?.groupValues?.get(2)?.toInt() ?: 1
                        ImageIO.createImageInputStream(path.toFile()).use { input ->
                            val readers = ImageIO.getImageReaders(input)
                            require(readers.hasNext()) { "Not a PNG: $path" }
                            val reader = readers.next()
                            try {
                                reader.input = input
                                val width = reader.getWidth(0)
                                val height = reader.getHeight(0)
                                require(width % scale == 0 && height % scale == 0) {
                                    "Image size is not divisible by @$scale: $path"
                                }
                                val previous =
                                    images.put(
                                        id,
                                        mapOf(
                                            "width" to ((width / scale + 8) / 9 * 9),
                                            "height" to ((height / scale + 8) / 9 * 9),
                                            "content-width" to width / scale,
                                            "content-height" to height / scale,
                                            "scale" to scale,
                                            "source" to
                                                root.relativize(path).toString().replace('\\', '/'),
                                        ),
                                    )
                                require(previous == null) { "Duplicate image ID $id" }
                            } finally {
                                reader.dispose()
                            }
                        }
                    }
            }
        return mapOf(
            "theme" to themeDocument,
            "hide-dialog-warning" to (themeDocument.getValue("hide-dialog-warning") as Boolean),
            "hide-canvas-focus-outline" to
                (themeDocument.getValue("hide-canvas-focus-outline") as Boolean),
            "hide-native-focus-outline" to
                (themeDocument.getValue("hide-native-focus-outline") as Boolean),
            "images" to images,
        )
    }
}
