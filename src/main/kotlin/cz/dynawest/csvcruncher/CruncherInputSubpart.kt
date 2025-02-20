package cz.dynawest.csvcruncher

import java.nio.file.Path
import kotlin.io.path.nameWithoutExtension

/**
 * One part of input data, maps to one or more SQL tables. Can be created out of multiple input files.
 */
data class CruncherInputSubpart(
    private val originalInputPath: Path? = null,
    val combinedFile: Path,
    val combinedFromFiles: List<Path>? = null,
    var tableName: String? = null,
) {

    companion object {
        fun trivial(path: Path): CruncherInputSubpart {
            val cis = CruncherInputSubpart(
                    originalInputPath = path,
                    combinedFile = path,
                    combinedFromFiles = listOf(path),
                    tableName = path.fileName.nameWithoutExtension
            )
            return cis
        }
    }
}