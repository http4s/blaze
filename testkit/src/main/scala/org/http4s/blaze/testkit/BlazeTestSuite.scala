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

import munit.{Assertions, FunSuite, Location}

import scala.concurrent.{ExecutionContext, Future}

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
}
