package cz.dynawest.csvcruncher

import HsqlDbTableCreator
import cz.dynawest.csvcruncher.HsqlDbHelper.Companion.quote
import cz.dynawest.csvcruncher.app.Options.CombineInputFiles
import cz.dynawest.csvcruncher.app.Options.JsonExportFormat
import cz.dynawest.csvcruncher.converters.JsonFileFlattener
import cz.dynawest.csvcruncher.util.FilesUtils
import cz.dynawest.csvcruncher.util.JsonUtils
import cz.dynawest.csvcruncher.util.Utils.resolvePathToUserDirIfRelative
import cz.dynawest.csvcruncher.util.logger
import org.apache.commons.io.FileUtils
import org.apache.commons.lang3.StringUtils
import java.io.File
import java.io.IOException
import java.nio.file.Path
import java.nio.file.Paths
import java.sql.*
import java.util.regex.Pattern

class Cruncher(private val options: Options2) {
    private lateinit var jdbcConn: Connection
    private lateinit var dbHelper: HsqlDbHelper
    private val log = logger()

    private fun init() {
        System.setProperty("textdb.allow_full_path", "true")
        //System.setProperty("hsqldb.reconfig_logging", "false");
        try {
            Class.forName("org.hsqldb.jdbc.JDBCDriver")
        } catch (e: ClassNotFoundException) {
            throw CsvCruncherException("Couldn't find JDBC driver: " + e.message, e)
        }
        val dbPath = StringUtils.defaultIfEmpty(options.dbPath, "hsqldb").toString() + "/cruncher"
        try {
            FileUtils.forceMkdir(File(dbPath))
            jdbcConn = DriverManager.getConnection("jdbc:hsqldb:file:$dbPath;shutdown=true", "SA", "")
        } catch (e: IOException) {
            throw CsvCruncherException("Can't create HSQLDB data dir $dbPath: ${e.message}", e)
        } catch (e: SQLException) {
            throw CsvCruncherException("Can't connect to the database $dbPath: ${e.message}", e)
        }
        dbHelper = HsqlDbHelper(jdbcConn)
    }

    /**
     * Performs the whole process.
     */
    fun crunch() {
        try { options.validateAndApplyDefaults() }
        catch (ex: Exception) {
            throw CrucherConfigException("ERROR: Invalid options: ${ex.message}")
        }

        val addCounterColumn = options.initialRowNumber != null
        val convertResultToJson = options.jsonExportFormat != JsonExportFormat.NONE
        val printAsArray = options.jsonExportFormat == JsonExportFormat.ARRAY
        val tablesToFiles: MutableMap<String, File> = HashMap()
        var outputs: List<CruncherOutputPart> = emptyList()

        // Should the result have a unique incremental ID as an added 1st column?
        val counterColumn = CounterColumn()
        if (addCounterColumn) counterColumn.setDdlAndVal()
        try {
            dbHelper.executeSql("SET AUTOCOMMIT TRUE", "Error setting AUTOCOMMIT TRUE")
            for (script in options.initSqlArguments) dbHelper.executeSqlScript(script.path, "Error executing init SQL script")

            // Sort the input paths.
            //var inputPaths =
            var importArguments = options.importArguments.filter { it.path != null }
            importArguments = FilesUtils.sortImports(importArguments, options.sortInputPaths)
            log.debug(" --- Sorted imports: --- " + importArguments.map { "\n * $it" }.joinToString())

            // Convert the .json files to .csv files.
            importArguments = importArguments.map { import ->
                if (!import.path!!.fileName.toString().endsWith(".json")) {
                    import
                }
                else {
                    log.debug("Converting JSON to CSV: subtree ${import.itemsPathInTree} in ${import.path}")
                    val convertedFilePath = convertJsonToCsv(import.path!!, import.itemsPathInTree)
                    import.apply { path = convertedFilePath }  // Hack - replacing the path with the converted file.
                }
            }

            // A shortcut - for case of: crunch -in foo.json -out bar.csv, we are done.
            if (importArguments.size == 1 && options.exportArguments.size == 1){
                val singleConverted = options.importArguments.first()
                val singleExport = options.exportArguments.first()
                if (singleExport.sqlQuery == null && singleExport.formats == setOf(Format.CSV)) {
                    singleExport.path!!.toFile().mkdirs()
                    Files.move(singleConverted.path!!, singleExport.path!!)
                    return
                }
            }

            // Combine files. Should we concat the files or UNION the tables?
            val inputSubparts: List<CruncherInputSubpart>
            if (options.combineInputFiles == CombineInputFiles.NONE) {
                inputSubparts = importArguments.map { import -> CruncherInputSubpart.trivial(import.path!!) } .toList()
            }
            else {
                val inputFileGroups: Map<Path?, List<Path>> = FilesUtils.expandFilterSortInputFilesGroups(importArguments.map { it.path!! }, options)
                inputSubparts = FilesUtils.combineInputFiles(inputFileGroups, options)
                log.info(" --- Combined input files: --- " + inputSubparts.map { p: CruncherInputSubpart -> "\n * ${p.combinedFile}" }.joinToString())
            }
            if (inputSubparts.isEmpty()) return
            FilesUtils.validateInputFiles(inputSubparts)

            // For each input CSV file...
            for (inputSubpart in inputSubparts) {
                val csvInFile = resolvePathToUserDirIfRelative(inputSubpart.combinedFile)
                log.info(" * CSV input: $csvInFile")

                val tableName: String = HsqlDbHelper.normalizeFileNameForTableName(csvInFile)
                val previousIfAny = tablesToFiles.put(tableName, csvInFile)
                require(previousIfAny == null) { "File names normalized to table names collide: $previousIfAny, $csvInFile" }

                val colNames: List<String> = FilesUtils.parseColumnsFromFirstCsvLine(csvInFile)
                // Create a table and bind the CSV to it.
                HsqlDbTableCreator(dbHelper).createTableFromInputFile(tableName, csvInFile, colNames, true, options.overwrite)
                inputSubpart.tableName = tableName
            }


            // Perform the SQL SELECTs

            if (options.exportArguments.size > 1)
                throw UnsupportedOperationException("Currently, only 1 export is supported.")


            for (export in options.exportArguments) {

                outputs = mutableListOf()

                // SQL can be executed:
                // A) Once over all tables, and generate a single result.
                if (!options.queryPerInputSubpart) {
                    val csvOutFile = resolvePathToUserDirIfRelative(export.path!!)

                    // If there's just one input, then the generic SQL can be used.
                    val output = CruncherOutputPart(csvOutFile.toPath(), if (inputSubparts.size == 1) inputSubparts.first().tableName else null)
                    outputs.add(output)
                }
                // B) With each table, with a generic SQL query (using "$table"), and generate one result per table.
                else {
                    val usedOutputFiles: MutableSet<Path> = HashSet()
                    for (inputSubpart in inputSubparts) {
                        var outputFile = export.path!!.resolve(inputSubpart.combinedFile.fileName)
                        outputFile = FilesUtils.getNonUsedName(outputFile, usedOutputFiles)
                        val output = CruncherOutputPart(outputFile, inputSubpart.tableName)
                        outputs.add(output)
                    }
                }

                val genericSql = dbHelper.quoteColumnAndTableNamesInQuery(export.sqlQuery ?: DEFAULT_SQL)

                // For each output...
                for (output in outputs) {
                    log.debug("Output part: {}", output)
                    val csvOutFile = output.outputFile.toFile()
                    val outputTableName = output.deriveOutputTableName()

                    var sql = genericSql
                    if (output.inputTableName != null) {
                        sql = sql.replace(SQL_TABLE_PLACEHOLDER, HsqlDbHelper.quote(output.inputTableName))
                    }


                    // Create the parent dir.
                    val dirToCreate = csvOutFile.absoluteFile.parentFile
                    dirToCreate.mkdirs()

                    // Get the columns info: Perform the SQL, LIMIT 1.
                    val columnsDef: Map<String, String> = dbHelper.extractColumnsInfoFrom1LineSelect(sql)
                    output.columnNamesAndTypes = columnsDef


                    // Write the result into a CSV
                    log.info(" * CSV output: $csvOutFile")
                    HsqlDbTableCreator(dbHelper).createTableAndBindCsv(outputTableName, csvOutFile, columnsDef, true, counterColumn.ddl, false, options.overwrite)

                    // TBD: The export SELECT could reference the counter column, like "SELECT @counter, foo FROM ..."
                    // On the other hand, that's too much space for the user to screw up. Let's force it:
                    val selectSql = sql.replace("SELECT ", "SELECT ${counterColumn.value} ")
                    output.sql = selectSql
                    val userSql = "INSERT INTO ${quote(outputTableName)} ($selectSql)"
                    log.debug(" * User's SQL: $userSql")

                    val rowsAffected = dbHelper.executeSql(userSql, "Error executing user SQL: ")
                    log.debug("Affected rows: $rowsAffected")


                    // Now let's convert it to JSON if necessary.
                    if (convertResultToJson) {
                        var pathStr: String = csvOutFile.toPath().toString()
                        pathStr = StringUtils.removeEndIgnoreCase(pathStr, ".csv")
                        pathStr = StringUtils.appendIfMissing(pathStr, ".json")
                        val destJsonFile = Paths.get(pathStr)
                        log.info(" * JSON output: $destJsonFile")

                        jdbcConn.createStatement().use { statement2 ->
                            JsonUtils.convertResultToJson(statement2.executeQuery("SELECT * FROM ${quote(outputTableName)}"), destJsonFile, printAsArray)
                            if (!options.keepWorkFiles) csvOutFile.deleteOnExit()
                        }
                    }
                }
            }
        } catch (ex: Exception) {
            throw ex
        } finally {
            log.debug(" *** SHUTDOWN CLEANUP SEQUENCE ***")
            cleanUpInputOutputTables(tablesToFiles, outputs)
            dbHelper.executeSql("DROP SCHEMA PUBLIC CASCADE", "Failed to delete the database: ")
            jdbcConn.close()
            log.debug(" *** END SHUTDOWN CLEANUP SEQUENCE ***")
        }
    }

    private fun convertJsonToCsv(inputPath: Path, itemsAt: String): Path {
        return JsonFileFlattener().convert(inputPath, itemsAt)
    }

    private fun cleanUpInputOutputTables(inputTablesToFiles: Map<String, File>, outputs: List<CruncherOutputPart>) {
        // TODO: Implement a cleanup at start. https://github.com/OndraZizka/csv-cruncher/issues/18
        dbHelper.detachTables(inputTablesToFiles.keys, "Could not delete the input table: ")

        val outputTablesNames = outputs.map { outputPart -> outputPart.deriveOutputTableName() }.toSet()
        dbHelper.detachTables(outputTablesNames, "Could not delete the output table: ")
    }

    // A timestamp at the beginning:
    //sql = "DECLARE crunchCounter BIGINT DEFAULT UNIX_MILLIS() - 1530000000000";
    //executeDbCommand(sql, "Failed creating the counter variable: ");
    // Uh oh. Variables can't be used in SELECTs.

    /**
     * @return The initial number to use for unique row IDs.
     * Takes the value from options, or generates from timestamp if not set.
     */
    private val initialNumber: Long
        get() {
            val initialNumber: Long
            initialNumber = if (options.initialRowNumber != -1L) {
                options.initialRowNumber!!
            } else {
                // A timestamp at the beginning:
                //sql = "DECLARE crunchCounter BIGINT DEFAULT UNIX_MILLIS() - 1530000000000";
                //executeDbCommand(sql, "Failed creating the counter variable: ");
                // Uh oh. Variables can't be used in SELECTs.
                System.currentTimeMillis() - TIMESTAMP_SUBSTRACT
            }
            return initialNumber
        }

    /**
     * Information for the extra column used to add a unique id to each row.
     */
    private inner class CounterColumn {
        var ddl = ""
        var value = ""
        fun setDdlAndVal(): CounterColumn {
            val initialNumber = initialNumber
            var sql: String

            // Using an IDENTITY column which has an unnamed sequence?
            //ddl = "crunchCounter BIGINT GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY";
            // ALTER TABLE output ALTER COLUMN crunchCounter RESTART WITH UNIX_MILLIS() - 1530000000000;
            // INSERT INTO otherTable VALUES (IDENTITY(), ...)

            // Or using a sequence?
            sql = "CREATE SEQUENCE IF NOT EXISTS crunchCounter AS BIGINT NO CYCLE" // MINVALUE 1 STARTS WITH <number>
            dbHelper.executeSql(sql, "Failed creating the counter sequence: ")
            sql = "ALTER SEQUENCE crunchCounter RESTART WITH $initialNumber"
            dbHelper.executeSql(sql, "Failed altering the counter sequence: ")

            // ... referencing it explicitely?
            //ddl = "crunchCounter BIGINT PRIMARY KEY, ";
            // INSERT INTO output VALUES (NEXT VALUE FOR crunchCounter, ...)
            //value = "NEXT VALUE FOR crunchCounter, ";

            // ... or using it through GENERATED BY?
            ddl = "crunchCounter BIGINT GENERATED BY DEFAULT AS SEQUENCE crunchCounter PRIMARY KEY, "
            //value = "DEFAULT, ";
            value = "NULL AS crunchCounter, "
            // INSERT INTO output (id, firstname, lastname) VALUES (DEFAULT, ...)
            // INSERT INTO otherTable VALUES (CURRENT VALUE FOR crunchCounter, ...)
            return this
        }
    }

    companion object {
        const val TABLE_NAME__OUTPUT = "output"
        const val TIMESTAMP_SUBSTRACT = 1530000000000L // To make the unique ID a smaller number.
        const val FILENAME_SUFFIX_CSV = ".csv"
        val REGEX_SQL_COLUMN_VALID_NAME = Pattern.compile("[a-z][a-z0-9_]*", Pattern.CASE_INSENSITIVE)
        const val SQL_TABLE_PLACEHOLDER = "\$table"
        const val DEFAULT_SQL = "SELECT $SQL_TABLE_PLACEHOLDER.* FROM $SQL_TABLE_PLACEHOLDER"
    }

    init { init() }
}