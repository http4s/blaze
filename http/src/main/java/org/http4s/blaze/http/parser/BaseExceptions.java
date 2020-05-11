/*
 * Copyright 2014-2020 http4s.org
 *
 * SPDX-License-Identifier: Apache-2.0
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
