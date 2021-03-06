package controllers

import play.api._
import play.api.mvc._
import play.api.data._
import play.api.data.Forms._

import views._
import utilities._
import StringUtil._
import java.io.{ File, FileWriter }

object Application extends Controller {
  
  /**
   * Describes the db form.
   */
  val dbForm = Form(
    tuple(
      "driver" -> nonEmptyText,
      "dburl" -> nonEmptyText,
      "username" -> nonEmptyText,
      "password" -> text))

  // -- Actions

  /**
   * Home page
   */
  def index = Action {
    Ok(html.index(dbForm))
  }

  /**
   * Generate a domain entity, its controller and views (to list, create, edit, delete)
   */
  def generateAll = Action { implicit request =>
    dbForm.bindFromRequest.fold(
      formWithErrors => BadRequest(html.index(formWithErrors)),
      {
        case (driver, dburl, username, password) =>
          
          val config = Config(driver=driver,dburl=dburl,username=username,password=password)
          val dbReader = new DBReader(config)
          val testDataFilepath = config.baseDirectory+"/test/resources/testdata.xml"

          val allProperties = dbReader.getProperties()
          Logger.info("properties: " + allProperties)

          // val tablenames = allProperties.keys
          val tablenames = dbReader.getTables().keys

          Logger.info("tablenames: " + tablenames)
            
          /**
           * Help method to write artifacts to a file on disk.
           * @param files a list containing some path information and the generated output
           * @param outputDirectory the root catalog of the target play project 
           */
          def generate(files: List[(String, String)], outputDirectory: String): List[String] =
            for (
              (filepath, output) <- files
            ) yield {
              val absoluteFilename = outputDirectory + filepath      
              dbReader.createDirectoryIfNeeded(absoluteFilename)
              val out = new FileWriter(outputDirectory + filepath)
              try {
                out.write(output)
              } catch {
                case e: Exception => {
                  Logger.info("Failed to write file: " + e.getStackTrace)
                }
              } finally {
                out.close()
              }
              absoluteFilename
            }

          // Generate a domain entity and a controller for each table, as well as several views
          val artifacts = tablenames map { tablename =>
            val primaryKeys = dbReader.getPrimaryKeys(tablename)
            Logger.info("primary keys: " + primaryKeys)

            val foreignKeys = dbReader.getForeignKeys(tablename)
            
            def checkName(input:String): String = input.toLowerCase() match {
              case "type" | "class" => s"${input}1" // reserved words are rewritten with 1 at the end
              case _ => input   // unchanged
            }

            val tableProps = allProperties(tablename) map { prop =>
              val propertyName = checkName(camelifyMethod(prop(3).toString))
              val isNullable = prop(17).toString match {
                case "NO" => false
                case "YES" => true
                }
              val isPK = primaryKeys.contains(prop(3).toString)
              val isFK = foreignKeys.keys.toList.contains(prop(3).toString)
              val propType = DBUtil.mapping(prop(5).toString)
              TableProperty(
                name = propertyName,
                propertyType = propType,
                nullable = isNullable,
                isPrimaryKey = isPK,
                length = prop(6).toString.toInt,
                dbColumnName = prop(3).toString,
                formattedType = if (isPK || isNullable) "Option[" + propType + "]" else propType,
                default = "",  // not used in slick persistence.
                isForeignKey = isFK,
                fKeyInfo = if (isFK) foreignKeys(prop(3).toString).head else Vector.empty)
            }
            Logger.info(tablename + " tableProps: " + tableProps)
            
            val entityName = DBUtil.nameMapping(tablename)

            lazy val mvcFromPlayProject: List[(String, String)] = List(
              s"/app/models/${camelify(tablename.toLowerCase)}.scala" -> txt.entity(tablename, entityName, tableProps).toString,
              s"/app/controllers/${camelify(tablename.toLowerCase)}Controller.scala" -> txt.controller(tablename, entityName, tableProps).toString,
              s"/app/views/${camelify(tablename).toLowerCase}/createForm.scala.html" -> txt.createForm(tablename, entityName, tableProps).toString,
              s"/app/views/${camelify(tablename).toLowerCase}/list.scala.html" -> txt.list(tablename, entityName, tableProps).toString,
              s"/app/views/${camelify(tablename).toLowerCase}/editForm.scala.html" -> txt.editForm(tablename, entityName, tableProps).toString())

            if(!tableProps.find(p=>p.isPrimaryKey).isEmpty) generate(mvcFromPlayProject, config.baseDirectory)
            (tablename,tableProps)
          }
          
          Logger.info("Total Number of Artifacts: "+artifacts.size)
           
          // Filter out tables with no primary key (for now)
          val filteredArtifacts = artifacts filterNot(a=>a._2.find(p=>p.isPrimaryKey).isEmpty)
          
          Logger.info("Number of Filtered Artifacts (artifacts with a primary key): "+filteredArtifacts.size)
          
          // Exporting db testdata to test/resources
          // The testdata is sorted so that foreign key dependencies are resolved
          val testdata = dbReader.saveTestData(testDataFilepath,filteredArtifacts.map(_._1).toArray)
          
          Logger.info("testdata: " + testdata)
          
          // Generate pages concerning the whole application
          // List of pairs of (filepath info, output as text)
          lazy val filesFromPlayProject = List(
            "/conf/routes" -> txt.routes(filteredArtifacts.toList).toString,
            "/conf/application.conf" -> txt.conf(tablenames.toList).toString,
            "/conf/messages" -> txt.messages(tablenames.toList).toString,
            "/app/Global.scala" -> txt.global(tablenames.toList,filteredArtifacts.toList,testdata).toString,
            "/app/models/AllModels.scala" -> txt.allmodels(tablenames.toList).toString,
            "/app/controllers/Application.scala" -> txt.appli(tablenames.toList).toString,
            "/app/views/twitterBootstrapInput.scala.html" -> txt.twitterBootstrapInput(tablenames.toList).toString,
            "/app/views/main.scala.html" -> txt.main(tablenames.toList).toString,
            "/app/views/controllerlist.scala.html" -> txt.controllerlist(filteredArtifacts.toList).toString,
            "/project/Build.scala" -> txt.build(tablenames.toList,config.baseDirectory).toString,
            "/project/plugins.sbt" -> txt.plugins(tablenames.toList).toString,
            "/public/stylesheets/bootstrap.min.css" -> txt.bootstrapmincss(tablenames.toList).toString,
            "/public/stylesheets/main.css" -> txt.maincss(tablenames.toList).toString)
            
          val generatedFiles = generate(filesFromPlayProject, config.baseDirectory)
             
          //This should redirect to the generated play app
          Redirect("http://localhost:9000")
      })
  }
}