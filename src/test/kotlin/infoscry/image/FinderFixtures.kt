package infoscry.image

import infoscry.domain.ContentUnitId
import infoscry.domain.SourceLocation
import java.awt.Color
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import javax.imageio.ImageIO

/** Pictures and parents the finder tests build their documents from. */
internal object FinderFixtures {

    /** A picture whose pixels vary, so it is neither uniform nor reduced to nothing. */
    fun picture(width: Int, height: Int, seed: Int = 0): BufferedImage {
        val image = BufferedImage(width, height, BufferedImage.TYPE_INT_RGB)
        for (y in 0 until height) {
            for (x in 0 until width) {
                image.setRGB(x, y, Color((x * 3 + seed * 40) % 256, (y * 5 + seed * 70) % 256, (x + y + seed) % 256).rgb)
            }
        }
        return image
    }

    fun png(width: Int, height: Int, seed: Int = 0): ByteArray = encode(picture(width, height, seed), "png")

    fun jpeg(width: Int, height: Int, seed: Int = 0): ByteArray = encode(picture(width, height, seed), "jpg")

    fun encode(image: BufferedImage, format: String): ByteArray {
        val output = ByteArrayOutputStream()
        check(ImageIO.write(image, format, output))
        return output.toByteArray()
    }

    fun parent(locator: SourceLocation, ordinal: Int = 0): ParentUnit =
        ParentUnit(ContentUnitId.new(), ordinal, locator)
}
