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

package org.http4s.blaze.http.parser;

public class BaseExceptions {

  public abstract static class ParserException extends Exception {
    private static final long serialVersionUID = 8132184654773444925L;

    public ParserException(String msg) {
      super(msg);
    }
  }

  public static class BadMessage extends ParserException {
    private static final long serialVersionUID = -6447645402380938086L;

    public BadMessage(String msg) {
      super(msg);
    }
  }

  public static class BadCharacter extends BadMessage {
    private static final long serialVersionUID = -6336838845289468590L;

    public BadCharacter(String msg) {
      super(msg);
    }
  }

  public static class InvalidState extends ParserException {
    private static final long serialVersionUID = -1803189728615965013L;

    public InvalidState(String msg) {
      super(msg);
    }
  }
}
