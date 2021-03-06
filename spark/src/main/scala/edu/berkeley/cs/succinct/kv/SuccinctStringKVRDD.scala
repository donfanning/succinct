package edu.berkeley.cs.succinct.kv

import java.io.ObjectOutputStream

import edu.berkeley.cs.succinct.buffers.SuccinctIndexedFileBuffer
import edu.berkeley.cs.succinct.kv.impl.{SuccinctKVRDDImpl, SuccinctStringKVRDDImpl}
import edu.berkeley.cs.succinct.regex.RegExMatch
import edu.berkeley.cs.succinct.util.{SuccinctConfiguration, SuccinctConstants}
import org.apache.hadoop.conf.Configuration
import org.apache.hadoop.fs.{FileSystem, InvalidPathException, Path, PathFilter}
import org.apache.spark.rdd.RDD
import org.apache.spark.sql.SparkSession
import org.apache.spark.storage.StorageLevel
import org.apache.spark.succinct.kv.{SuccinctKVPartition, SuccinctStringKVPartition}
import org.apache.spark._
import org.apache.spark.unsafe.KVIterator

import scala.collection.mutable.ArrayBuffer
import scala.reflect.ClassTag

abstract class SuccinctStringKVRDD[K: ClassTag](@transient private val sc: SparkContext,
                                                @transient private val deps: Seq[Dependency[_]])
  extends RDD[(K, String)](sc, deps) {

  override def compute(split: Partition, context: TaskContext): Iterator[(K, String)] = {
    val succinctIterator = firstParent[SuccinctStringKVPartition[K]].iterator(split, context)
    if (succinctIterator.hasNext) {
      succinctIterator.next().iterator
    } else {
      Iterator[(K, String)]()
    }
  }

  /**
    * Get the value for a given key.
    *
    * @param key Input key.
    * @return Value corresponding to key.
    */
  def get(key: K): String = {
    val values = partitionsRDD.map(buf => buf.get(key)).filter(v => v != null).collect()
    if (values.length > 1) {
      throw new IllegalStateException(s"Key ${key.toString} returned ${values.length} values")
    }
    if (values.length == 0) null else values(0)
  }

  /**
    * Random access into the value for a given key.
    *
    * @param key    Input key.
    * @param offset Offset into value.
    * @param length Number of bytes to be fetched.
    * @return Extracted bytes from the value corresponding to given key.
    */
  def extract(key: K, offset: Int, length: Int): String = {
    val values = partitionsRDD.map(buf => buf.extract(key, offset, length)).filter(v => v != null)
      .collect()
    if (values.length > 1) {
      throw new IllegalStateException(s"Key ${key.toString} returned ${values.length} values")
    }
    if (values.length == 0) null else values(0)
  }

  /**
    * Search for a term across values, and return matched keys.
    *
    * @param query The search term.
    * @return An RDD of matched keys.
    */
  def search(query: String): RDD[K] = {
    partitionsRDD.flatMap(_.search(query))
  }

  /**
    * Search for a term across values, and return matched key-value pairs.
    *
    * @param query The search term.
    * @return An RDD of matched key-value pairs.
    */
  def filterByValue(query: String): RDD[(K, String)] = {
    partitionsRDD.flatMap(_.searchAndGet(query))
  }

  /**
    * Search for a term across values, and return the total number of occurrences.
    *
    * @param query The search term.
    * @return Count of the number of occurrences.
    */
  def count(query: String): Long = {
    partitionsRDD.map(_.count(query)).aggregate(0L)(_ + _, _ + _)
  }

  /**
    * Search for a term across values and return offsets for the matches relative to the beginning of
    * the value. The result is a collection of (key, offset) pairs, where the offset is relative to
    * the beginning of the corresponding value.
    *
    * @param query The search term.
    * @return An RDD of (key, offset) pairs.
    */
  def searchOffsets(query: String): RDD[(K, Int)] = {
    partitionsRDD.flatMap(_.searchOffsets(query))
  }

  /**
    * Search for a regular expression across values, and return matched keys.
    *
    * @param query The regex query.
    * @return An RDD of matched keys.
    */
  def regexSearch(query: String): RDD[K] = {
    partitionsRDD.flatMap(_.regexSearch(query))
  }

  /**
    * Search for a regular expression across values, and return matches relative to the beginning of
    * the value. The result is a collection of (key, RegExMatch) pairs, where the RegExMatch
    * encapsulates the offset relative to the beginning of the corresponding value, and the length
    * of the match.
    *
    * @param query The regex query.
    * @return An RDD of matched keys.
    */
  def regexMatch(query: String): RDD[(K, RegExMatch)] = {
    partitionsRDD.flatMap(_.regexMatch(query))
  }

  /**
    * Bulk append data to SuccinctStringKVRDD; returns a new SuccinctStringKVRDD, with the newly appended
    * data encoded as Succinct data structures. The original RDD is removed from memory after this
    * operation.
    *
    * @param data                 The data to be appended.
    * @param preservePartitioning Preserves the partitioning for the appended data if true;
    *                             repartitions the data otherwise.
    * @return A new SuccinctStringKVRDD containing the newly appended data.
    */
  def bulkAppend(data: RDD[(K, String)], preservePartitioning: Boolean = false): SuccinctStringKVRDD[K]

  /**
    * Saves the SuccinctStringKVRDD at the specified path.
    *
    * @param location The path where the SuccinctStringKVRDD should be stored.
    */
  def save(location: String, conf: Configuration = new Configuration()): Unit = {
    val path = new Path(location)
    val fs = FileSystem.get(path.toUri, conf)
    if (!fs.exists(path)) {
      fs.mkdirs(path)
    }

    val serializableConf = new SerializableWritable(conf)

    partitionsRDD.zipWithIndex().foreach(entry => {
      val i = entry._2
      val partition = entry._1
      val partitionLocation = location.stripSuffix("/") + "/part-" + "%05d".format(i)
      partition.save(partitionLocation, serializableConf.value)
    })

    val successPath = new Path(location.stripSuffix("/") + "/_SUCCESS")
    fs.create(successPath).close()
  }

  /**
    * Returns first parent of the RDD.
    *
    * @return The first parent of the RDD.
    */
  protected[succinct] def getFirstParent: RDD[SuccinctStringKVPartition[K]] = {
    firstParent[SuccinctStringKVPartition[K]]
  }

  /**
    * Returns the array of partitions.
    *
    * @return The array of partitions.
    */
  override protected def getPartitions: Array[Partition] = partitionsRDD.partitions

  /**
    * Returns the RDD of partitions.
    *
    * @return The RDD of partitions.
    */
  private[succinct] def partitionsRDD: RDD[SuccinctStringKVPartition[K]]
}

/** Factory for [[SuccinctStringKVRDD]] instances **/
object SuccinctStringKVRDD {

  /**
    * Converts an input RDD to [[SuccinctStringKVRDD]].
    *
    * @param inputRDD The input RDD.
    * @tparam K The key type.
    * @return The SuccinctStringKVRDD.
    */
  def apply[K: ClassTag](inputRDD: RDD[(K, String)], sync: Boolean = false)
                        (implicit ordering: Ordering[K]): SuccinctStringKVRDD[K] = {
    val partitionsRDD = inputRDD.sortByKey()
      .mapPartitions(if (sync) createSuccinctStringKVPartitionSync[K] else createSuccinctStringKVPartition[K]).cache()
    val firstKeys = partitionsRDD.map(_.firstKey).collect()
    new SuccinctStringKVRDDImpl[K](partitionsRDD, firstKeys)
  }

  def constructAndSave[K: ClassTag](inputRDD: RDD[(K, String)], location: String, sync: Boolean = false,
                                    sort: Boolean = true)
                                   (implicit ordering: Ordering[K]): Unit = {
    val conf = inputRDD.sparkContext.hadoopConfiguration
    val path = new Path(location)
    val fs = FileSystem.get(path.toUri, conf)
    if (!fs.exists(path)) {
      fs.mkdirs(path)
    }

    val serializableConf = new SerializableWritable(conf)
    if (sort)
      inputRDD.sortByKey().foreachPartition(it => {
        val i = TaskContext.getPartitionId()
        val partitionLocation = location.stripSuffix("/") + "/part-" + "%05d".format(i)
        val localConf = serializableConf.value
        if (sync) createAndSaveSuccinctStringKVPartitionSync[K](it, partitionLocation, localConf)
        else createAndSaveSuccinctStringKVPartition[K](it, partitionLocation, localConf)
      })
    else
      inputRDD.foreachPartition(it => {
        val i = TaskContext.getPartitionId()
        val partitionLocation = location.stripSuffix("/") + "/part-" + "%05d".format(i)
        val localConf = serializableConf.value
        if (sync) createAndSaveSuccinctStringKVPartitionSync[K](it, partitionLocation, localConf)
        else createAndSaveSuccinctStringKVPartition[K](it, partitionLocation, localConf)
      })
    val successPath = new Path(location.stripSuffix("/") + "/_SUCCESS")
    fs.create(successPath).close()
  }

  /**
    * Creates a [[SuccinctStringKVPartition]] from a partition of the input RDD.
    *
    * @param kvIter The iterator over the input partition data.
    * @tparam K The type for the keys.
    * @return An iterator over the [[SuccinctStringKVPartition]]
    */
  private[succinct] def createSuccinctStringKVPartition[K: ClassTag](kvIter: Iterator[(K, String)])
                                                                    (implicit ordering: Ordering[K]):
  Iterator[SuccinctStringKVPartition[K]] = {
    val keysBuffer = new ArrayBuffer[K]()
    var offsetsBuffer = new ArrayBuffer[Int]()
    val rawBufferOS = new StringBuilder()

    var offset = 0

    while (kvIter.hasNext) {
      val curRecord = kvIter.next()
      keysBuffer += curRecord._1
      rawBufferOS.append(curRecord._2)
      rawBufferOS.append(SuccinctConstants.EOL.toChar)
      offsetsBuffer += offset
      offset = rawBufferOS.length
    }

    val keys = keysBuffer.toArray
    val rawValueBuffer = rawBufferOS.toArray
    val offsets = offsetsBuffer.toArray
    val valueBuffer = new SuccinctIndexedFileBuffer(rawValueBuffer, offsets)

    Iterator(new SuccinctStringKVPartition[K](keys, valueBuffer))
  }

  /**
    * Creates a [[SuccinctStringKVPartition]] from a partition of the input RDD; ensures only one task per Spark
    * executor gets to execute this at a time. Helps in reducing GC overheads.
    *
    * @param kvIter The iterator over the input partition data.
    * @tparam K The type for the keys.
    * @return An iterator over the [[SuccinctStringKVPartition]]
    */
  private[succinct] def createSuccinctStringKVPartitionSync[K: ClassTag](kvIter: Iterator[(K, String)])
                                                                    (implicit ordering: Ordering[K]):
  Iterator[SuccinctStringKVPartition[K]] = synchronized {
    createSuccinctStringKVPartition[K](kvIter)
  }

  /**
    * Creates and saves the [[SuccinctStringKVPartition]] to a given location.
    *
    * @param kvIter Iterator over key-value pairs.
    * @param location Output location for Succinct index.
    * @param conf Configuration parameters.
    * @param ordering Ordering for keys.
    * @tparam K Key-type.
    */
  private[succinct] def createAndSaveSuccinctStringKVPartition[K: ClassTag](kvIter: Iterator[(K, String)],
                                                                            location: String, conf: Configuration)
                                                                           (implicit ordering: Ordering[K]):
  Unit = {
    val keysBuffer = new ArrayBuffer[K]()
    var offsetsBuffer = new ArrayBuffer[Int]()
    val rawBufferOS = new StringBuilder()

    var offset = 0

    while (kvIter.hasNext) {
      val curRecord = kvIter.next()
      keysBuffer += curRecord._1
      rawBufferOS.append(curRecord._2)
      rawBufferOS.append(SuccinctConstants.EOL.toChar)
      offsetsBuffer += offset
      offset = rawBufferOS.length
    }

    val pathVals = new Path(location + ".vals")
    val pathKeys = new Path(location + ".keys")

    val fs = FileSystem.get(pathVals.toUri, conf)

    val osKeys = new ObjectOutputStream(fs.create(pathKeys))
    osKeys.writeObject(keysBuffer.toArray)
    osKeys.close()

    println(s"Constructing partition $location with ${keysBuffer.length} keys and ${rawBufferOS.length} value bytes")

    val osVals = fs.create(pathVals)
    SuccinctIndexedFileBuffer.construct(rawBufferOS.toArray, offsetsBuffer.toArray, osVals, new SuccinctConfiguration())
    osVals.close()

    fs.create(new Path(location + ".success")).close()
    System.gc()

    println(s"Finished constructing partition $location")
  }

  /**
    * Creates and saves the [[SuccinctStringKVPartition]], ensuring only one task per executor gets to execute. Helps
    * in reducing GC overheads.
    *
    * @param kvIter Iterator over key-value pairs.
    * @param location Output location for Succinct index.
    * @param conf Configuration parameters.
    * @param ordering Ordering for keys.
    * @tparam K Key-type.
    */
  private[succinct] def createAndSaveSuccinctStringKVPartitionSync[K: ClassTag](kvIter: Iterator[(K, String)],
                                                                            location: String, conf: Configuration)
                                                                           (implicit ordering: Ordering[K]):
  Unit = synchronized {
    createAndSaveSuccinctStringKVPartition[K](kvIter, location, conf)
  }

  /**
    * Reads a SuccinctStringKVRDD from disk.
    *
    * @param sc           The spark context.
    * @param location     The path to read the SuccinctStringKVRDD from.
    * @param storageLevel Storage level of the SuccinctStringKVRDD.
    * @return The SuccinctStringKVRDD.
    */
  def apply[K: ClassTag](sc: SparkContext, location: String, storageLevel: StorageLevel)
                        (implicit ordering: Ordering[K])
  : SuccinctStringKVRDD[K] = {
    val locationPath = new Path(location)
    val fs = FileSystem.get(locationPath.toUri, sc.hadoopConfiguration)
    val serializableConf = new SerializableWritable(sc.hadoopConfiguration)
    if (fs.isDirectory(locationPath)) {
      val status = fs.listStatus(locationPath, new PathFilter {
        override def accept(path: Path): Boolean = {
          path.getName.startsWith("part-") && path.getName.endsWith(".vals")
        }
      })
      val numPartitions = status.length
      val succinctPartitions = sc.parallelize(0 until numPartitions, numPartitions)
        .mapPartitionsWithIndex[SuccinctStringKVPartition[K]]((i, _) => {
        val partitionLocation = location.stripSuffix("/") + "/part-" + "%05d".format(i)
        val localConf = serializableConf.value
        Iterator(SuccinctStringKVPartition[K](partitionLocation, storageLevel, localConf))
      }).cache()
      val firstKeys = succinctPartitions.map(_.firstKey).collect()
      new SuccinctStringKVRDDImpl[K](succinctPartitions, firstKeys)
    } else if (fs.isFile(new Path(location + ".keys"))) {
      val succinctPartitions = sc.parallelize(0 until 1, 1).mapPartitions[SuccinctStringKVPartition[K]](_ => {
        val localConf = serializableConf.value
        Iterator(SuccinctStringKVPartition[K](location, storageLevel, localConf))
      }).cache()
      val firstKeys = succinctPartitions.map(_.firstKey).collect()
      new SuccinctStringKVRDDImpl[K](succinctPartitions, firstKeys)
    } else {
      throw new InvalidPathException("Path is not a file or directory")
    }
  }

  /**
    * Reads a SuccinctStringKVRDD from disk.
    *
    * @param spark        The spark session.
    * @param location     The path to read the SuccinctStringKVRDD from.
    * @param storageLevel Storage level of the SuccinctStringKVRDD.
    * @return The SuccinctStringKVRDD.
    */
  def apply[K: ClassTag](spark: SparkSession, location: String, storageLevel: StorageLevel)
                        (implicit ordering: Ordering[K])
  : SuccinctStringKVRDD[K] = {
    apply(spark.sparkContext, location, storageLevel)
  }
}
