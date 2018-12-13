/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package cep

import org.apache.flink.streaming.api.scala._
import org.apache.flink.streaming.api.scala.function.ProcessWindowFunction
import org.apache.flink.streaming.api.windowing.time.Time
import org.fiware.cosmos.orion.flink.connector.OrionSource
import org.apache.flink.streaming.api.windowing.windows.TimeWindow
import org.apache.flink.streaming.api.windowing.assigners.{SlidingProcessingTimeWindows}
import org.apache.flink.util.Collector


/**
  * When Importing the Task in Apache Flink
  * EntryClass:
  * org.apache.flink.StreamingJob
  */


object StreamingJob {

  var currentTime: Long = 0
  var timeChecker: Long = 0
  var timerMap: Map[String,Long] = Map()

  def timeoutWriter(sensor: String, sensorTimer: Long, currentTime: Long): String = {
    if (currentTime - sensorTimer > 5){
      sensor + ": timeout! please check if the sensor is still working!" + "\n"
    } else {
      ""
    }
  }
  def getTimeouts(map: Map[String, Long], message: String): String = {
    var text: String = message
    map foreach (x => text += timeoutWriter(x._1, x._2, currentTime))
    text
  }

  def main(args: Array[String]) {

    val env = StreamExecutionEnvironment.getExecutionEnvironment
    val eventStream = env.addSource(new OrionSource(9001))

    val processedDataStream = eventStream.flatMap(event => event.entities)
      .map(entity => (entity.id, entity.attrs("temperature").value.asInstanceOf[String]))
      .keyBy(_._1)
      .window(SlidingProcessingTimeWindows.of(Time.seconds(20), Time.seconds(5)))
      .process(new MyProcessWindowFunction)
      .writeAsText("/tmp/log.txt")

    env.execute("Socket Window NgsiEvent")
  }



  class MyProcessWindowFunction extends ProcessWindowFunction[(String, String), String, String, TimeWindow] {

    override def process(key: String, context: Context, elements: Iterable[(String, String)], out: Collector[String]): Unit = {

      currentTime = System.currentTimeMillis() / 1000
      var messages: String = ""
      val values: Iterable[Float] = elements.map(_._2.toFloat)
      val max: Float = values.max
      val min: Float = values.min

      timerMap += (key -> currentTime)

      if (max >= 60){
        messages += key + ": room on fire!" + " temperature: " + max.toString + "°C" + "\n"
      }
      if (min <= 15) {
        messages += key + ": do not forget to close the windows!" + " temperature: " + min.toString + "°C" + "\n"
      }
      if (max - min >= 3) {
        messages += key + ": room on fire!" + " temperature is rising too fast" + "\n"
      }

      messages = getTimeouts(timerMap, messages).stripLineEnd
      out.collect(messages)
    }
  }
}