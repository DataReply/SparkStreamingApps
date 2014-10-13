package sparkapps

import java.nio.ByteBuffer

import org.apache.ctakes.core.fsm.token.BaseToken
import org.apache.uima.analysis_engine.AnalysisEngineDescription
import org.apache.uima.jcas.JCas
import org.uimafit.factory.JCasFactory
import org.uimafit.pipeline.SimplePipeline
import org.uimafit.util.JCasUtil
import twitter4j._
import twitter4j.auth.Authorization
import twitter4j.conf.ConfigurationBuilder
import twitter4j.auth.OAuthAuthorization

import org.apache.spark.streaming._
import org.apache.spark.streaming.dstream._
import org.apache.spark.storage.StorageLevel
import org.apache.spark.Logging
import org.apache.spark.streaming.receiver.Receiver


  /* A stream of Twitter statuses, potentially filtered by one or more keywords.
  *
  * @constructor create a new Twitter stream using the supplied Twitter4J authentication credentials.
  * An optional set of string filters can be used to restrict the set of tweets. The Twitter API is
  * such that this may return a sampled subset of all tweets during each interval.
  *
  * If no Authorization object is provided, initializes OAuth authorization using the system
  * properties twitter4j.oauth.consumerKey, .consumerSecret, .accessToken and .accessTokenSecret.
  */
class TwitterInputDStreamJ(
                             @transient ssc_ : StreamingContext,
                             twitterAuth: Option[Authorization],
                             filters: Seq[String],
                             storageLevel: StorageLevel
                             ) extends ReceiverInputDStream[Status](ssc_) {

    object INSTANCES {
        var instances=0;
    }

    override def slideDuration(): Duration = {
      //System.out.println("DURATION = 30 seconds.");
      return Seconds(100)
    }

    private def createOAuthAuthorization(): Authorization = {
      new OAuthAuthorization(new ConfigurationBuilder().build())
    }

    private val authorization = twitterAuth.getOrElse(createOAuthAuthorization())

    override def getReceiver(): Receiver[Status] = {
      new TwitterReceiver(authorization, filters, storageLevel)
    }
  }

  class TwitterReceiver(
                         twitterAuth: Authorization,
                         filters: Seq[String],
                         storageLevel: StorageLevel
                         ) extends Receiver[Status](storageLevel) with Logging {

    private var twitterStream: TwitterStream = _
    var total=0;
    override def store(status:Status): Unit = {
      // Create a ByteBuffer using a byte array
      for(x <- 1 until 1000){
        total+=1;
       // System.out.println(total + " Storing buffer " + status.getText);
        val buffer = ByteBuffer.wrap(status.getText.getBytes());
        super.store(buffer)

      }

    }

    def onStart() {
      try {
        val newTwitterStream = new TwitterStreamFactory().getInstance(twitterAuth)
        newTwitterStream.addListener(new StatusListener {
          def onStatus(status: Status) = {
              store(status)
          }

          def onDeletionNotice(statusDeletionNotice: StatusDeletionNotice) {}

          def onTrackLimitationNotice(i: Int) {}

          def onScrubGeo(l: Long, l1: Long) {}

          def onStallWarning(stallWarning: StallWarning) {}

          def onException(e: Exception) {
            restart("Error receiving tweets", e)
          }
        })

        val query = new FilterQuery
        if (filters.size > 0) {
          query.track(filters.toArray)
          newTwitterStream.filter(query)
        }
        else {
          newTwitterStream.sample()
        }
        setTwitterStream(newTwitterStream)

        logInfo("Twitter receiver started")
      }
      catch {
        case e: Exception =>
          restart("Error starting Twitter stream", e)
      }
    }

    def onStop() {
      setTwitterStream(null)
      logInfo("Twitter receiver stopped")
    }

    private def setTwitterStream(newTwitterStream: TwitterStream) = synchronized {
      if (twitterStream != null) {
        twitterStream.shutdown()
      }
      twitterStream = newTwitterStream
    }
  }

