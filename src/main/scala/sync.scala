//
// Fureteur
//
// Synchronization objects
//

package fureteur.sync

// We are using Akka actors
import akka.actor._
import akka.actor.Actor._
import akka.event.EventHandler
import fureteur.collection.FIFO



// Control messages
abstract class Ctrl
case class StatsReq(handler:List[(String,String)]=>Unit) extends Ctrl // Get stats out of the actor model
case class Stats(l:List[(String,String)]) extends Ctrl                // Get stats as a message
case class NoData extends Ctrl
case class PseudoTimeout extends Ctrl
case class DataReq(req:ActorRef, n:Int) extends Ctrl
case class DataIn[T](req:ActorRef, e:List[T]) extends Ctrl
case class DataOut[T](req:ActorRef, e:List[T]) extends Ctrl


// Generic processor base class
// The processor gets data of type T and process them into data of type U
abstract class genericProcessor[T,U] (thres_in: Int,             // Input threshold
                                      thres_out: Int,            // Output threshold
                                      producer: ActorRef, 
                                      reseller: ActorRef,
                                      timeout: Option[Long]
                                      ) extends Actor {

  self.receiveTimeout = timeout
  val fifo= new FIFO[T](Some( (thres_in, request) ))
  var fifo_max= 0
  var processed= List[U]()
  var processed_count= 0
  var total_count= 0
  var partial_send_count= 0
  var send_count= 0
    
  def request(n:Int): Unit = {
    producer ! DataReq(self, n)
  }
  
  override def preStart() = {
    fifo init
  }
    
  def receive = {
    case DataIn(_, elems: List[T]) => { fifo pushn elems; processShift(); }
    case StatsReq(handler) => handler(stats())
    case ReceiveTimeout => { processShift() }
    case _ => EventHandler.info(this, "received unknown message")
  }
  
  def processShift(): Unit ={
    if(fifo.isEmpty) {
      if(processed_count>0) { send() }
      return
    }
    if(fifo.length>fifo_max) { fifo_max= fifo.length }
    try {
      while(true) {
        val e= fifo pop;
        processed= process(e)::processed; // Queueing up the answers
        processed_count+= 1;
        if (processed_count>=thres_out) { send() }
        total_count+= 1;
      }
    } catch {
      case e:NoSuchElementException => // The queue is empty, we need more stuff
    }    
  }
  
  def send() = {
    reseller ! DataOut(self, processed)
    processed= List[U]()
    send_count+= 1
    if(processed_count<thres_out) { partial_send_count+= 1 }
    processed_count= 0
  }

  def stats(): List[(String,String)] = {
    var l= getStats()
    l= ("uuid",self.uuid.toString)::("id",self.id)::("total_processed", total_count.toString)::l
    l= ("fifo_max_length", fifo_max.toString)::l
    l= ("partial_send_count", partial_send_count.toString)::l
    l= ("send_count", send_count.toString)::l
    l
  }
  
  def process(in: T): U;
  def getStats(): List[(String,String)] = { List() }
}


// Generic producer base class (working in batches)
//
abstract class genericBatchProducer[T] (size:Int,          // Size of a batch
                                        thres: Int,        // Treshold (expressed in number of batches)
                                        timeout: Option[Long]
                                       ) extends Actor {
  self.receiveTimeout = timeout
  var timeouts= 0
  var batches_sent= 0                             
                                           
  val fifo= new FIFO[List[T]](Some(thres, singleRequest))
  val reqfifo= new FIFO[ActorRef](None)
  
  def singleRequest(n:Int): Unit = {
    if(fifo.length<=thres) { requestBatch() }
  }
  
  def multiRequest(): Unit = {
    if(fifo.length<=thres && requestBatch()) { multiRequest() }
  }
    
  def receive = {
    case DataReq(req, _) => { reqfifo push req; handleRequests(); }
    case ReceiveTimeout => { timeouts+=1; handleRequests() }
    case StatsReq(handler) => handler(stats())
    case _ => EventHandler.info(this, "received unknown message")
  }
  
  def handleRequests(): Unit = {
    (fifo.isEmpty, reqfifo.isEmpty) match {
      case (false, false) => { batches_sent+= 1; reqfifo.pop ! DataIn(self, fifo.pop); handleRequests() }
      case (true,  false) => { singleRequest(0); if(!fifo.isEmpty) { handleRequests() } }
      case (_,     true) => { multiRequest(); }
    }
  }

  override def preStart() = {
    multiRequest()
  }
  
  def requestBatch(): Boolean = {
    getBatch(size) match {
      case Some(l:List[T]) => { fifo push l; return true }
      case None => { return false; } 
    } 
  }
  
  def stats(): List[(String,String)] = {
    ("uuid",self.uuid.toString)::("id",self.id)::("timeouts",timeouts.toString)::("batches_sent",batches_sent.toString)::getStats()
  }
  
  def getBatch(n:Int): Option[List[T]] // This function MUST not block
  def getStats(): List[(String,String)] = { List() }
}

// Generic batch reseller
//
abstract class genericBatchReseller[T] extends Actor {
  var c=0
  
  def receive = {
    case DataOut(req, out:List[T]) => { c+= out.length; resell(out) }
    case StatsReq(handler) => handler(stats())
    case _ => EventHandler.info(this, "received unknown message")
  }
  
  def stats(): List[(String,String)] = {
    ("uuid",self.uuid.toString)::("id",self.id)::getStats()
  }
  
  def resell(d:List[T])
  def getStats(): List[(String,String)] = { List() }
}





// Test

class testProducer extends Actor {
  var n=0
  
  def next(): List[String] ={
    n+= 5
    List(n-5, n-4, n-3, n-2, n-1) map (""+_)
  }
  
  def receive = {
    case DataReq(req, n) => req ! DataIn(self, next)
    case _ => EventHandler.info(this, "received unknown message")
  }
}


class testBatchProducer(size:Int, thres:Int, timeout: Option[Long]) extends genericBatchProducer[String](size, thres, timeout) {

  val data= scala.io.Source.fromFile("li_50k_rev").getLines.toArray
  var index= 0
  
  override def getBatch(sz:Int):Option[List[String]] = {
    if(index>data.size) { return None }
    index+= sz
    print(".")
    Some( data.slice(index-sz, index).toList )
  }
}


class testReseller extends Actor {
  var c=0
  var cc= 0
  def receive = {
    case DataOut(req, out) => { c+= out.length; if(c/1000>cc) { println("Received "+c); cc= c/1000 } }   //println("From "+req.id+" got "+out)
    case 0 => println("Got "+c)
    case _ => EventHandler.info(this, "received unknown message")
  }
}

class testProcessor(thres_in: Int,
                                      thres_out: Int,
                                      producer: ActorRef, 
                                     reseller: ActorRef, s:Int, timeout:Option[Long]) 
                            extends genericProcessor[String,String](thres_in, thres_out, producer, reseller, timeout) {
  import testProcessor._
  self.id= "processor-"+iid
  iid+= 1                     
  def process(x:String):String ={ Thread.sleep(s); return "done "+x; }                                
                                                                   
}

object testProcessor {
  var iid= 0;
}

class Cron(a: List[ActorRef]) extends Actor {

  self.receiveTimeout = Some(10000L)
  
  def receive() = {
    case ReceiveTimeout => { a map ( _ ! StatsReq( ((l:List[(String,String)]) => { println(l); scala.Console.out.flush() } )) ) }
  }

}

class testAll {
  val p= actorOf(new testBatchProducer(50, 3, Some(500L))).start
  val r= actorOf(new testReseller).start
  val pp1= actorOf(new testProcessor(10, 50, p, r, 1, Some(1000L))).start
  val pp2= actorOf(new testProcessor(10, 50, p, r, 2, Some(1000L))).start
  val pp3= actorOf(new testProcessor(10, 50, p, r, 3, Some(1000L))).start
  val pp4= actorOf(new testProcessor(10, 50, p, r, 4, Some(1000L))).start
  actorOf(new Cron(List(pp1, pp2, pp3, pp4)) ).start
}

