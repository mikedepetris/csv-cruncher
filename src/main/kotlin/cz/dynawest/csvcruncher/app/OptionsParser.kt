package cz.dynawest.csvcruncher.app

import cz.dynawest.csvcruncher.*
import cz.dynawest.csvcruncher.app.Options.*
import cz.dynawest.csvcruncher.util.VersionUtils
import cz.dynawest.csvcruncher.util.logger
import org.apache.commons.lang3.StringUtils
import java.nio.file.InvalidPathException
import java.nio.file.Path
import java.util.regex.Pattern
import kotlin.io.path.name

object OptionsParser {

    fun parseArgs(args: Array<String>): Options2? {

        val options = Options2()
        var next: OptionsCurrentContext = OptionsCurrentContext.GLOBAL

        lateinit var currentImport: ImportArgument
        lateinit var currentExport: ExportArgument

        log.trace(" Parameters: ")

        var argIndex = 0
        while (argIndex < args.size) {
            val arg = args[argIndex]
            logArgument(arg)

            if ("-in" == arg) {
                next = OptionsCurrentContext.IN
                currentImport = options.newImportArgument()
            }
            else if ("-out" == arg) {
                when (next) {
                    OptionsCurrentContext.OUT -> {
                        // If there was a -sql before -out, then just set it's path.
                        if (currentExport.path != null)
                            currentExport = options.newExportArgument()
                    }
                    else -> {
                        next = OptionsCurrentContext.OUT
                        currentExport = options.newExportArgument()
                    }
                }

            }
            else if ("-all" == arg) {
                next = OptionsCurrentContext.GLOBAL
            }
            else if (!arg.startsWith("-")) {
                when (next) {
                    OptionsCurrentContext.IN -> {
                        currentImport.path = Path.of(arg)
                        if (currentImport.path!!.name.lowercase().endsWith(".json")) currentImport.format = Format.JSON
                    }
                    OptionsCurrentContext.OUT -> {
                        currentExport.path = Path.of(arg)
                        if (currentExport.path!!.name.lowercase().endsWith(".json")) {
                            currentExport.formats.clear()
                            currentExport.formats += Format.JSON
                        }
                    }
                    OptionsCurrentContext.DBPATH -> options.dbPath = arg
                    OptionsCurrentContext.INIT_SQL -> options.initSqlArguments.add(InitSqlArgument(tryParsePath(arg)))
                    else -> throw CrucherConfigException("Not sure what to do with the argument at this place: $arg.")
                }
            }
            else if ("-as" == arg) {
                when (next) {
                    OptionsCurrentContext.IN -> currentImport.alias = args.getOrNull(++argIndex)
                    OptionsCurrentContext.OUT -> currentExport.alias = args.getOrNull(++argIndex)
                    else -> throw CrucherConfigException("-as may only come as part of an import or export, i.e. after `-in` or `-out`.")
                }
            }
            else if ("-format" == arg) {
                val format = enumValueOr(args.getOrNull(++argIndex), Format.CSV)
                when (next) {
                    OptionsCurrentContext.IN -> currentImport.format = format
                    OptionsCurrentContext.OUT -> currentExport.formats.add(format)
                    else -> throw CrucherConfigException("-format may only come as part of an import or export, i.e. after `-in` or `-out`.")
                }
            }
            else if ("-sql" == arg) {
                when (next) {
                    OptionsCurrentContext.OUT -> currentExport.sqlQuery = args.getOrNull(++argIndex)
                    else -> {
                        //throw CsvCruncherException("`-sql` must come as part of some export, i.e. defined after `-out`.")
                        // TBD: Could support it ad-hoc, but then the -out <path> would have to set the path of the current export, rather than creating a new one.
                        next = OptionsCurrentContext.OUT
                        currentExport = options.newExportArgument()
                        currentExport.sqlQuery = args.getOrNull(++argIndex)
                    }
                }
            }

            else if ("-itemsAt" == arg) {
                when (next) {
                    OptionsCurrentContext.IN -> {
                        currentImport.itemsPathInTree = args.getOrNull(++argIndex)?.also { logArgument(it) }
                            ?: throw CrucherConfigException("Missing value after --itemsAt; should be a path to the array of entries in the source file.")
                    }
                    else -> throw CrucherConfigException("-itemsAt may only come as part of an import, i.e. after `-in`.")
                }
            }


            // JSON output
            else if (arg.startsWith("--" + JsonExportFormat.PARAM_NAME)) {
                if (arg.endsWith("=" + JsonExportFormat.ARRAY.optionValue))
                    options.jsonExportFormat = JsonExportFormat.ARRAY
                else options.jsonExportFormat = JsonExportFormat.ENTRY_PER_LINE
            }
            else if (arg.startsWith("--include")) {
                require(arg.startsWith("--include=")) { "Option --include has to have a value (regular expression)." }
                val regex = StringUtils.removeStart(arg, "--include=")
                try {
                    options.includePathsRegex = Pattern.compile(regex)
                }
                catch (ex: Exception) {
                    throw CrucherConfigException("Not a valid regex: $regex. ${ex.message}")
                }
            }
            else if (arg.startsWith("--exclude")) {
                require(arg.startsWith("--exclude=")) { "Option --exclude has to have a value (regular expression)." }
                val regex = arg.removePrefix("--exclude=")
                try {
                    options.excludePathsRegex = Pattern.compile(regex)
                }
                catch (ex: Exception) {
                    throw CrucherConfigException("Not a valid regex: $regex. ${ex.message}")
                }
            }
            else if (arg.startsWith("--ignoreFirstLines")) {
                options.ignoreFirstLines = 1
                if (arg.startsWith("--ignoreFirstLines=")) {
                    val numberStr = arg.removePrefix("--ignoreFirstLines=")
                    try {
                        val number = numberStr.toInt()
                        options.ignoreFirstLines = number
                    }
                    catch (ex: Exception) {
                        throw CrucherConfigException("Not a valid number: $numberStr. ${ex.message}")
                    }
                }
            }
            else if (arg.startsWith("--ignoreLinesMatching")) {
                require(arg.startsWith("--ignoreLinesMatching=")) { "Option --ignoreLinesMatching has to have a value (regular expression)." }
                val regex = arg.removePrefix("--ignoreFirstLines=")
                try {
                    options.ignoreLineRegex = Pattern.compile(regex)
                }
                catch (ex: Exception) {
                    throw CrucherConfigException("Not a valid regex: $regex. ${ex.message}")
                }
            }
            else if (arg.startsWith("--rowNumbers")) {
                options.initialRowNumber = -1L
                if (arg.startsWith("--rowNumbers=")) {
                    val numberStr = arg.removePrefix("--rowNumbers=")
                    try {
                        val number = numberStr.toLong()
                        options.initialRowNumber = number
                    }
                    catch (ex: Exception) {
                        throw CrucherConfigException("Not a valid number: $numberStr. ${ex.message}")
                    }
                }
            }
            else if (arg.startsWith("--" + SortInputPaths.PARAM_SORT_INPUT_PATHS)) {
                if (arg.endsWith("--" + SortInputPaths.PARAM_SORT_INPUT_PATHS) ||
                    arg.endsWith("=" + SortInputPaths.PARAMS_ORDER.optionValue))
                    options.sortInputPaths = SortInputPaths.PARAMS_ORDER
                else if (arg.endsWith("=" + SortInputPaths.ALPHA.optionValue))
                    options.sortInputPaths = SortInputPaths.ALPHA
                else if (arg.endsWith("=" + SortInputPaths.TIME.optionValue))
                    options.sortInputPaths = SortInputPaths.TIME
                else
                    throw CrucherConfigException("Unknown value for ${SortInputPaths.PARAM_SORT_INPUT_PATHS}: $arg Try one of ${SortInputPaths.optionValues}")
            }
            else if (arg.startsWith("--" + SortInputPaths.PARAM_SORT_FILE_GROUPS)) {
                if (arg.endsWith("--" + SortInputPaths.PARAM_SORT_FILE_GROUPS) ||
                    arg.endsWith("=" + SortInputPaths.ALPHA.optionValue))
                    options.sortInputFileGroups = SortInputPaths.ALPHA
                else if (arg.endsWith("=" + SortInputPaths.TIME.optionValue))
                    options.sortInputFileGroups = SortInputPaths.TIME
                else if (arg.endsWith("=" + SortInputPaths.PARAMS_ORDER.optionValue))
                    options.sortInputFileGroups = SortInputPaths.PARAMS_ORDER
                else
                    throw CrucherConfigException("Unknown value for ${SortInputPaths.PARAM_SORT_FILE_GROUPS}: $arg Try one of ${SortInputPaths.optionValues}")
            }
            else if (arg.startsWith("--" + CombineInputFiles.PARAM_NAME)) {
                if (arg.endsWith("--" + CombineInputFiles.PARAM_NAME) ||
                    arg.endsWith("=" + CombineInputFiles.CONCAT.optionValue))
                    options.combineInputFiles = CombineInputFiles.CONCAT
                else if (arg.endsWith("=" + CombineInputFiles.INTERSECT.optionValue))
                    options.combineInputFiles = CombineInputFiles.INTERSECT
                else if (arg.endsWith("=" + CombineInputFiles.EXCEPT.optionValue))
                    options.combineInputFiles = CombineInputFiles.EXCEPT
                else
                    throw CrucherConfigException("Unknown value for ${CombineInputFiles.PARAM_NAME}: $arg Try one of ${CombineInputFiles.optionValues}")

                // TODO: Do something like this instead:
                //opt.combineInputFiles = Utils.processOptionIfMatches(arg, Options.CombineInputFiles.class, Options.CombineInputFiles.CONCAT);
                // Or move it to the respective enum class.
                // Enum<Options.CombineDirectories> val = Options.CombineDirectories.COMBINE_ALL_FILES;
            }
            else if (arg.startsWith("--" + CombineDirectories.PARAM_NAME)) {
                // Sorted from most fine-grained to least.
                if (arg.endsWith("=" + CombineDirectories.COMBINE_PER_EACH_DIR.optionValue))
                    options.combineDirs = CombineDirectories.COMBINE_PER_EACH_DIR
                else if (arg.endsWith("=" + CombineDirectories.COMBINE_PER_INPUT_SUBDIR.optionValue))
                    options.combineDirs = CombineDirectories.COMBINE_PER_INPUT_SUBDIR
                else if (arg.endsWith("=" + CombineDirectories.COMBINE_PER_INPUT_DIR.optionValue))
                    options.combineDirs = CombineDirectories.COMBINE_PER_INPUT_DIR
                else if (arg.endsWith("=" + CombineDirectories.COMBINE_ALL_FILES.optionValue))
                    options.combineDirs = CombineDirectories.COMBINE_ALL_FILES
                else if (arg == "--" + CombineDirectories.PARAM_NAME)
                    options.combineDirs = CombineDirectories.COMBINE_ALL_FILES
                else
                    throw CrucherConfigException("Unknown value for ${CombineDirectories.PARAM_NAME}: $arg Try one of ${CombineDirectories.optionValues}")
            }
            else if ("--queryPerInputSubpart" == arg) {
                options.queryPerInputSubpart = true
            }
            else if ("--skipNonReadable" == arg) {
                options.skipNonReadable = true
            }


            // Global only options

            else if (arg.startsWith("--logLevel=")) {
                val name = arg.substringAfter("=").uppercase()
                enumValueOrNull<LogLevel>(name)
                    ?.also { options.logLevel = it }
                    ?: log.error("Invalid logLevel '$name', will use the defaults. Try one of " + LogLevel.values().joinToString(", "))
            }
            else if ("--keepWorkFiles" == arg) {
                options.keepWorkFiles = true
            }
            else if ("-db" == arg) {
                next = OptionsCurrentContext.DBPATH
            }
            else if ("-initSql" == arg) {
                next = OptionsCurrentContext.INIT_SQL
            }
            else if ("-v" == arg || "--version" == arg) {
                println(" CSV Cruncher version ${VersionUtils.version}")
                return null
            }
            else if ("-h" == arg || "--help" == arg) {
                println(" CSV Cruncher version ${VersionUtils.version}")
                App.printUsage(System.out)
                return null
            }
            else if (arg.startsWith("--overwrite")) {
                options.overwrite = true
            }
            else if (arg.startsWith("-")) {
                val msg = "Unknown parameter: $arg"
                println("ERROR: $msg")
                throw CrucherConfigException(msg)
            }

            argIndex++
        }

        preventHsqldbBug(options)

        return if (!options.isFilled) {
            App.printUsage(System.err)
            throw CrucherConfigException("Not enough arguments.")
        } else {
            options
        }
    }

    private fun logArgument(arg: String) {
        log.trace(" * $arg")
    }

    private fun tryParsePath(arg: String) =
        try {
            Path.of(arg)
        }
        catch (ex: InvalidPathException) {
            throw CrucherConfigException("Unparsable path to an init SQL script '$arg': ${ex.message}")
        }

    /** HSQLDB bug, see https://stackoverflow.com/questions/52708378/hsqldb-insert-into-select-null-from-leads-to-duplicate-column-name */
    private fun preventHsqldbBug(options: Options2) {
        val numberedImport = options.importArguments.firstOrNull { import ->
            val initialRowNumber = import.initialRowNumber ?: options.initialRowNumber
            initialRowNumber != null
        }
        if (numberedImport == null) return

        for (export in options.exportArguments) {
            if (export.sqlQuery == null) continue

            val itsForSure = export.sqlQuery!!.matches(".*SELECT +\\*.*|.*[^.]\\* +FROM .*".toRegex())
            if (itsForSure || export.sqlQuery!!.matches(".*SELECT.*[^.]\\* .*FROM.*".toRegex())) {
                val msg = """|
                         |    WARNING! It looks like you use --rowNumbers for ${numberedImport.path} with `SELECT *` in:
                         |      ${export.sqlQuery}.
                         |    Due to a bug in HSQLDB, this causes an error 'duplicate column name in derived table'.
                         |    Use table-qualified way: `SELECT myTable.*`""".trimMargin()
                if (itsForSure) {
                    log.error("\n$msg\n\n")
                    throw CrucherConfigException(msg)
                } else {
                    val notSure = "\n    (This detection is not reliable so the program will continue, but likely fail.)"
                    log.warn("\n$msg$notSure\n\n")
                }
            }
        }
    }

    private inline fun <reified T : OptionEnum> tryParseEnumOption(enumArgumentDefault: T, arg: String): T? {
        val optionIntro = "--${enumArgumentDefault.optionName}"

        if (!arg.startsWith(optionIntro))
            return null

        if (arg.endsWith(optionIntro) || arg.endsWith("=${enumArgumentDefault.optionValue}"))
            return enumArgumentDefault

        val valueStr = arg.substringAfter(optionIntro)

        val enumConstants = T::class.java.enumConstants
        return enumConstants.firstOrNull { it.optionName == valueStr }
            ?: throw CrucherConfigException("Unknown value for ${enumArgumentDefault.optionName}: $arg Try one of ${enumConstants.map { it.optionValue }}")
    }

    enum class OptionsCurrentContext {
        GLOBAL, IN, OUT, SQL, DBPATH, INIT_SQL
    }

    private val log = logger()
}

inline fun <reified T : Enum<*>> enumValueOrNull(name: String?): T? {
    if (name == null)
        return null
    return T::class.java.enumConstants.firstOrNull { it.name == name }
}

inline fun <reified T : Enum<*>> enumValueOr(name: String?, default: T): T {
    return enumValueOrNull<T>(name) ?: default
}