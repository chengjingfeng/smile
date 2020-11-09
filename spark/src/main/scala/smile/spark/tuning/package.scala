/*******************************************************************************
 * Copyright (c) 2010-2020 Haifeng Li. All rights reserved.
 *
 * Smile is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Lesser General Public License as
 * published by the Free Software Foundation, either version 3 of
 * the License, or (at your option) any later version.
 *
 * Smile is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public License
 * along with Smile.  If not, see <https://www.gnu.org/licenses/>.
 ******************************************************************************/

package smile.spark

import java.util.function.BiFunction

import org.apache.spark.sql.SparkSession
import smile.classification.Classifier
import smile.regression.Regression
import smile.validation.{Accuracy, ClassificationMeasure, CrossValidation, RMSE, RegressionMeasure}

import scala.reflect.ClassTag

/**
 * Class to make [[ClassificationMeasure]] serializable so Spark can send instances on remote Spark Executors.
 */
case class SerializableClassificationMeasure(@transient measure: ClassificationMeasure)

/**
 * Class to make [[RegressionMeasure]] serializable so Spark can send instances on remote Spark Executors.
 */
case class SerializableRegressionMeasure(@transient measure: RegressionMeasure)

/**
 * Package containing functions to tune and validate smile trainers over multiple Spark Executors in a parallel and distributed fashion.
 */
package object tuning {

  object classification {

    /**
     * Distributed GridSearch Cross Validation for [[Classifier]].
     *
     * @param spark running spark session
     * @param k number of round of cross validation
     * @param x instances
     * @param y labels
     * @param measures classification measures
     * @param trainers classification trainers
     *
     * @return an array of array of classification measures, the first layer has the same size as the number of trainers,
     *         the second layer as a size equals to k.
     */
    def sparkgscv[T <: Object: ClassTag](
                                          spark: SparkSession)(k: Int, x: Array[T], y: Array[Int], measures: ClassificationMeasure*)(
                                          trainers: ((Array[T], Array[Int]) => Classifier[T])*): Array[Array[Double]] = {

      val sc = spark.sparkContext

      val xBroadcasted = sc.broadcast[Array[T]](x)
      val yBroadcasted = sc.broadcast[Array[Int]](y)

      val trainersRDD = sc.parallelize(trainers)

      val measuresBroadcasted = measures.map(SerializableClassificationMeasure).map(sc.broadcast)

      val res = trainersRDD
        .map(trainer => {
          //TODO: add smile-scala dependency and import the implicit conversion
          val biFunctionTrainer = new BiFunction[Array[T],Array[Int],Classifier[T]] {
            override def apply(x: Array[T], y:Array[Int]): Classifier[T] = trainer(x,y)
          }
          val x = xBroadcasted.value
          val y = yBroadcasted.value
          val measures = measuresBroadcasted.map(_.value.measure)
          //TODO: add smile-scala dependency and use smile.validation.cv
          val prediction =  CrossValidation.classification(k, x, y, biFunctionTrainer)
          val measuresOrAccuracy = if (measures.isEmpty) Seq(new Accuracy()) else measures
          measuresOrAccuracy.map { measure =>
            val result = measure.measure(y, prediction)
            result
          }.toArray
        })
        .collect()

      xBroadcasted.destroy()
      yBroadcasted.destroy()

      res

    }

  }

  object regression {

    /**
     * Distributed GridSearch Cross Validation for [[Regression]].
     *
     * @param spark running spark session
     * @param k number of round of cross validation
     * @param x instances
     * @param y labels
     * @param measures regression measures
     * @param trainers regression trainers
     *
     * @return an array of array of regression measures, the first layer has the same size as the number of trainers,
     *         the second layer as a size equals to k.
     */
    def sparkgscv[T <: Object: ClassTag](
                                          spark: SparkSession)(k: Int, x: Array[T], y: Array[Double], measures: RegressionMeasure*)(
                                          trainers: ((Array[T], Array[Double]) => Regression[T])*): Array[Array[Double]] = {

      val sc = spark.sparkContext

      val xBroadcasted = sc.broadcast[Array[T]](x)
      val yBroadcasted = sc.broadcast[Array[Double]](y)

      val trainersRDD = sc.parallelize(trainers)

      val measuresBroadcasted = measures.map(SerializableRegressionMeasure).map(sc.broadcast)

      val res = trainersRDD
        .map(trainer => {
          //TODO: add smile-scala dependency and import the implicit conversion
          val biFunctionTrainer = new BiFunction[Array[T],Array[Double],Regression[T]] {
            override def apply(x: Array[T], y:Array[Double]): Regression[T] = trainer(x,y)
          }
          val x = xBroadcasted.value
          val y = yBroadcasted.value
          val measures = measuresBroadcasted.map(_.value.measure)
          //TODO: add smile-scala dependency and use smile.validation.cv
          val prediction =  CrossValidation.regression(k, x, y, biFunctionTrainer)
          val measuresOrRMSE = if (measures.isEmpty) Seq(new RMSE()) else measures
          measuresOrRMSE.map { measure =>
            val result = measure.measure(y, prediction)
            result
          }.toArray
        })
        .collect()

      xBroadcasted.destroy()
      yBroadcasted.destroy()

      res

    }

  }


}
