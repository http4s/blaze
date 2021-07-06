/*
 * Copyright 2014 http4s.org
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.http4s.blaze.testkit

import munit.{Assertions, FailException, FailExceptionLike, FunSuite, Location}

import scala.concurrent.{ExecutionContext, Future}
import scala.reflect.ClassTag
import scala.util.{Failure, Success}
import scala.util.control.NonFatal

abstract class BlazeTestSuite extends FunSuite with BlazeAssertions {
  implicit val executionContext: ExecutionContext = ExecutionContext.Implicits.global
}

trait BlazeAssertions { self: Assertions =>
  def assertFuture[A, B](
      obtained: => Future[A],
      returns: B,
      clue: => Any = "values are not the same"
  )(implicit ev: B <:< A, loc: Location, ec: ExecutionContext): Future[Unit] =
    obtained.flatMap(a => Future(assertEquals(a, returns, clue)))

  def assertFuture_(
      obtained: => Future[Unit],
      clue: => Any = "values are not the same"
  )(implicit loc: Location, ec: ExecutionContext): Future[Unit] =
    obtained.flatMap(a => Future(assertEquals(a, (), clue)))

  protected def assertFutureBoolean(
      obtained: => Future[Boolean],
      clue: => Any = "values are not the same"
  )(implicit loc: Location, ec: ExecutionContext): Future[Unit] =
    assertFuture(obtained, true, clue)

  def interceptFuture[T <: Throwable](
      body: => Future[Any]
  )(implicit T: ClassTag[T], loc: Location, ec: ExecutionContext): Future[T] =
    runInterceptFuture(None, body)

  private def runInterceptFuture[T <: Throwable](
      exceptionMessage: Option[String],
      body: => Future[Any]
  )(implicit T: ClassTag[T], loc: Location, ec: ExecutionContext): Future[T] =
    body.transformWith {
      case Success(value) =>
        Future(
          fail(
            s"intercept failed, expected exception of type '${T.runtimeClass.getName}' but body evaluated successfully",
            clues(value)
          ))

      case Failure(e: FailExceptionLike[_]) if !T.runtimeClass.isAssignableFrom(e.getClass) =>
        Future.failed(e)

      case Failure(NonFatal(e)) if T.runtimeClass.isAssignableFrom(e.getClass) =>
        if (exceptionMessage.isEmpty || exceptionMessage.contains(e.getMessage)) {
          Future.successful(e.asInstanceOf[T])
        } else {
          val obtained = e.getClass.getName

          Future.failed(
            new FailException(
              s"intercept failed, exception '$obtained' had message '${e.getMessage}', which was different from expected message '${exceptionMessage.get}'",
              cause = e,
              isStackTracesEnabled = false,
              location = loc
            )
          )
        }

      case Failure(NonFatal(e)) =>
        val obtained = e.getClass.getName
        val expected = T.runtimeClass.getName

        Future.failed(
          new FailException(
            s"intercept failed, exception '$obtained' is not a subtype of '$expected",
            cause = e,
            isStackTracesEnabled = false,
            location = loc
          )
        )

      case Failure(ex) => Future.failed(ex)
    }
}
