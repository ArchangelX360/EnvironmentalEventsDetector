package modules.scheduler

import java.io.File

import akka.actor.{Actor, ActorRef, Props}
import akka.pattern.ask
import akka.util.Timeout
import models.Query
import models.Query.Queries
import modules.imagefetcher.{FetcherActor, ZipUtil}
import modules.imagefetcher.FetcherActor._
import modules.scheduler.MonitoringActor.{GetProcess, UpdateProcess}
import modules.scheduler.SchedulerActor.{CancelProcessing, StartProcessing}
import org.joda.time.format.DateTimeFormat
import play.api.libs.concurrent.Execution.Implicits.defaultContext
import play.api.libs.ws.WSClient

import scala.concurrent.Future
import scala.concurrent.duration._

object SchedulerActor {
  def props(processes: Queries,
            configuration: play.api.Configuration,
            ws: WSClient,
            monitoring: ActorRef): Props =
    Props(new SchedulerActor(processes, configuration, ws, monitoring))

  //Message definitions
  case class StartProcessing(id: String)

  case class CancelProcessing(id: String)

}

/**
  * This actor manage lifecycle of tasks and queries
  */
class SchedulerActor(processes: Queries, configuration: play.api.Configuration, ws: WSClient, monitoring: ActorRef) extends Actor {

  private val fetcherActor = context.actorOf(FetcherActor.props(ws, configuration.underlying.getString("pfs.servers.imageFetcherUrl"), monitoring))

  implicit val timeout: Timeout = 50.seconds

  // TODO: Move this property to a config file
  val outputFolder = "Downloaded"

  /**
    * Date formatter used to convert Joda Time Instant to simple formatted string for earth engine request
    */
  private val fullFmt = DateTimeFormat.forPattern("yyyy-MM-dd")
  private val shortFmt = DateTimeFormat.forPattern("yyyy")

  override def receive: Receive = {
    case StartProcessing(id) => startProcessing(id)
    case CancelProcessing(id) => cancelProcessing(id)
  }

  /**
    * Entry point of the pipeline
    * First it send query to the search engine which gather parameters such as places and dates.
    * Then the the request is send to the python server which generate an url which is used to download images
    * Finally, the image is extracted from the downloaded zip
    *
    * @param id Identifier of the process to start (The process must already have been initialized by the search engine)
    */
  def startProcessing(id: String): Unit = {

    // Gather details about the query and register tasks with the monitoring actor
    val details = monitoring
      .ask(GetProcess(id))
      .mapTo[Query]
      .map(query => query.details)
      .map {
        case Some(detail) => detail
        case _ =>
          monitoring ! UpdateProcess(id, "failed: no details")
          throw new Exception("no details")
      }

    // Send processing request to Google Earth Engine using the python server
    val rgbUrl = details
      .flatMap(details => fetcherActor.ask(FetchRGB(fullFmt.print(details.from), details.place, None, id)))
      .mapTo[FetchResponse]
      .map(response => response.url)

    val forestDiffUrl = details
      .flatMap(details => fetcherActor.ask(
          FetchForestDiff(shortFmt.print(details.from), shortFmt.print(details.to), details.place, None, id)))
      .mapTo[FetchResponse]
      .map(response => response.url)

    val rgbImage = downloadAndExtractImage(rgbUrl, id)
    val forestDiffImage = downloadAndExtractImage(forestDiffUrl, id)

    rgbImage foreach (file => println(file.map(f => f.getAbsolutePath).getOrElse("file (rhb) not found in zip")))
    forestDiffImage foreach (file => println(file.map(f => f.getAbsolutePath).getOrElse("file (diff) not found in zip")))

    // TODO: Add forest diff processing here

    // TODO: Add ontology processing here

  }

  /**
    * Remove a query from the query list (if processing hasn't started yet)
    */
  def cancelProcessing(id: String): Unit = {
    val index = processes.indexWhere(query => query.id == id)
    if (index >= 0) processes.remove(index)
  }

  /**
    * Download image generated by the Earth Engine as a zip file and extract it
    * @param url Future url of the download link
    * @param queryId Identifier of the query which triggered the download
    * @return The future optionally extracted file
    */
  def downloadAndExtractImage(url: Future[String], queryId: String): Future[Option[File]] = {
    // Download result from Earth Engine
    val zip = url
      .flatMap(url => fetcherActor.ask(DownloadFile(url, queryId, outputFolder)))
      .mapTo[Downloaded]

    // Extract image from the zip file
    val images = zip map (downloaded => ZipUtil.extractZip(downloaded.file, outputFolder = outputFolder))

    images
  }

}
