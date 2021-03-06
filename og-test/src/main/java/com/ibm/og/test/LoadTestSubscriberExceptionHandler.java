/* Copyright (c) IBM Corporation 2016. All Rights Reserved.
 * Project name: Object Generator
 * This project is licensed under the Apache License 2.0, see LICENSE.
 */

package com.ibm.og.test;

import static com.google.common.base.Preconditions.checkNotNull;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.common.eventbus.SubscriberExceptionContext;
import com.google.common.eventbus.SubscriberExceptionHandler;

/**
 * An {@code EventBus} exception handler. This handler aborts the OG load test when an eventbus
 * exception occurs.
 * 
 * @since 1.0
 */
public class LoadTestSubscriberExceptionHandler implements SubscriberExceptionHandler {
  private static final Logger _logger =
      LoggerFactory.getLogger(LoadTestSubscriberExceptionHandler.class);
  private static final Logger _exceptionLogger = LoggerFactory.getLogger("ExceptionLogger");
  private LoadTest test;

  /**
   * Creates an instance
   */
  public LoadTestSubscriberExceptionHandler() {}

  @Override
  public void handleException(final Throwable exception, final SubscriberExceptionContext context) {
    _logger.error("Exception while processing subscriber", exception);
    _exceptionLogger.error("Exception while processing subscriber", exception);
    this.test.abortTest(String.format("%s %s", getClass().getSimpleName(), exception.getMessage()));
  }

  /**
   * Set the load test for this instance to abort in the event that an event bus exception occurs
   * 
   * @param test the load test to manage
   */
  // this method exists to break a circular dependency in the dependency graph
  public void setLoadTest(final LoadTest test) {
    this.test = checkNotNull(test);
  }
}
