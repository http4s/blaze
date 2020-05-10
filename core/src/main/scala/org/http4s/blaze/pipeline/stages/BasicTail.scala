/*
 * Copyright 2014-2020 http4s.org
 *
 * SPDX-License-Identifier: Apache-2.0
 */

package org.http4s.blaze.pipeline.stages

import org.http4s.blaze.pipeline.TailStage

final class BasicTail[T](val name: String) extends TailStage[T]
