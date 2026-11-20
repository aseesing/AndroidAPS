// ============================================================================================
// PERSONAL PATCH - not part of upstream AAPS (branch local/personal, see .github/README.md).
// Own file: keep upstream files to small hooks that call into here.
// ============================================================================================
package app.aaps.wear.watchfaces.wff

/**
 * Where the files of a converted face go, relative to the face folder that Pack (the APK builder of
 * Watch Face Push) reads.
 *
 * Why: Pack reads pictures only from `res/drawable`. A picture in `res/drawable-nodpi`, the usual
 * place for pictures that must not be scaled, is not found, and the face does not build. Fonts go to
 * `res/font`.
 */
object WffResources {

    /** The face itself, as [WffFaceWriter] writes it. */
    const val WATCH_FACE_XML = "res/raw/watchface.xml"

    /** The face's info: its preview, and that it can be edited. */
    const val WATCH_FACE_INFO_XML = "res/xml/watch_face_info.xml"

    /** The strings of the face ([WffFaceWriter.Face.strings]). */
    const val STRINGS_XML = "res/values/strings.xml"

    /** The manifest of the face APK. */
    const val MANIFEST = "AndroidManifest.xml"

    private const val DRAWABLE = "res/drawable"
    private const val FONT = "res/font"

    /** The file of the picture with the resource name [name] (`@drawable/name` in the xml), in [format]. */
    fun picture(name: String, format: WffPictures.Format): String = "$DRAWABLE/$name.${format.extension}"

    /** The file of the font with the resource name [name], of a font file with the extension [extension] (`ttf`, `otf`). */
    fun font(name: String, extension: String): String = "$FONT/$name.$extension"
}
